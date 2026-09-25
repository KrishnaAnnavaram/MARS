package com.bootshift.adapters.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Isolated execution of repository-controlled tools (spec section 48).
 *
 * <p>Repository code is untrusted: Maven plugins, Gradle scripts, annotation processors and tests all
 * execute arbitrary code. This runner therefore enforces a command allowlist, a timeout, an output
 * cap, and an explicit working directory.
 *
 * <p>Arguments are always passed as a vector, never as a command string, so there is no parsing step
 * an argument can escape from. On Windows a {@code .cmd} or {@code .bat} wrapper cannot be launched
 * directly by {@code ProcessBuilder} and is prefixed with {@code cmd.exe /c} by {@link #shellWrap};
 * the allowlist is checked against the <em>real</em> target before that wrapping happens, and
 * {@code cmd.exe} is deliberately absent from the allowlist so it can never be a caller's target.
 */
public final class ProcessRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessRunner.class);

    /** Executables the harness is permitted to launch. Anything else is refused. */
    private static final Set<String> DEFAULT_ALLOWLIST = Set.of(
            "mvn", "mvn.cmd", "mvnw", "mvnw.cmd", "gradle", "gradle.bat", "gradlew", "gradlew.bat",
            "java", "java.exe", "javap", "javap.exe", "jdeps", "jdeps.exe", "jdeprscan", "jdeprscan.exe",
            "git", "git.exe");
    // cmd.exe is NOT listed. shellWrap adds it after assertAllowed has already checked the real
    // target, so allowlisting it would buy nothing and would let a caller pass cmd.exe /c <anything>
    // as its own command - which is the whole allowlist, undone.

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public record Result(int exitCode, List<String> stdout, List<String> stderr, Duration duration,
                         boolean timedOut, String command) {

        public boolean success() {
            return exitCode == 0 && !timedOut;
        }

        public String stdoutText() {
            return String.join(System.lineSeparator(), stdout);
        }

        public String stderrText() {
            return String.join(System.lineSeparator(), stderr);
        }

        public List<String> tail(List<String> lines, int count) {
            return lines.size() <= count ? lines : lines.subList(lines.size() - count, lines.size());
        }
    }

    /**
     * Environment variables a child process is allowed to inherit.
     *
     * <p>The runner used to copy the harness process environment wholesale into every build. That
     * environment routinely holds credentials belonging to whoever launched the tool - registry
     * tokens, cloud keys, CI secrets - and handing them to an untrusted Maven plugin is exactly the
     * supply-chain exposure the isolation section exists to prevent. Inheritance is now opt-in.
     */
    private static final Set<String> INHERITED_ENVIRONMENT = Set.of(
            "PATH", "HOME", "USERPROFILE", "SystemRoot", "SYSTEMROOT", "windir", "TEMP", "TMP",
            "COMSPEC", "PATHEXT", "SystemDrive", "SYSTEMDRIVE", "NUMBER_OF_PROCESSORS", "OS",
            "PROCESSOR_ARCHITECTURE", "JAVA_HOME", "M2_HOME", "MAVEN_HOME", "GRADLE_USER_HOME",
            "USER", "USERNAME", "LOGNAME", "SHELL", "LANG", "LC_ALL", "TZ");

    /** Variable names that are dropped even if something adds them to the inherit list. */
    private static final List<String> SECRET_NAME_FRAGMENTS = List.of(
            "TOKEN", "SECRET", "PASSWORD", "PASSWD", "APIKEY", "API_KEY", "ACCESS_KEY",
            "PRIVATE_KEY", "CREDENTIAL", "AUTH", "SESSION", "COOKIE", "NPM_", "PYPI_", "AWS_",
            "AZURE_", "GCP_", "GOOGLE_", "GH_", "GITHUB_", "GITLAB_", "DOCKER_", "SONAR");

    private final Set<String> allowlist;
    private final int maxOutputLines;

    public ProcessRunner() {
        this(DEFAULT_ALLOWLIST, 20000);
    }

    public ProcessRunner(Set<String> allowlist, int maxOutputLines) {
        this.allowlist = allowlist;
        this.maxOutputLines = maxOutputLines;
    }

    public static Set<String> defaultAllowlist() {
        return DEFAULT_ALLOWLIST;
    }

    /** Refuses any executable outside the allowlist before a process is created. */
    public void assertAllowed(String executable) {
        String name = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (!allowlist.contains(name)) {
            throw new SecurityException("Execution refused: " + name + " is not on the command allowlist");
        }
    }

    public Result run(List<String> command, Path workingDirectory, Duration timeout,
                      Map<String, String> environment) {
        return run(command, workingDirectory, timeout, environment, null);
    }

    /**
     * Runs a command, streaming output to an optional log sink so a long build leaves evidence even
     * if it is later killed by the timeout.
     */
    public Result run(List<String> command, Path workingDirectory, Duration timeout,
                      Map<String, String> environment, Path logSink) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Empty command");
        }
        assertAllowed(command.get(0));

        ProcessBuilder builder = new ProcessBuilder(shellWrap(command));
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(false);
        applySanitizedEnvironment(builder, environment);

        Instant start = Instant.now();
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(-1, List.of(), List.of("Cannot start process: " + e.getMessage()),
                    Duration.between(start, Instant.now()), false, String.join(" ", command));
        }

        Thread outThread = drain(process.getInputStream(), stdout);
        Thread errThread = drain(process.getErrorStream(), stderr);

        boolean timedOut = false;
        int exitCode;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                // Kill the tree, not the direct child. Maven and Gradle fork: destroying the
                // launcher leaves the forked JVM holding the port, the workspace lock and the
                // database connection, and the next stage then fails for a reason that has nothing
                // to do with the migration.
                terminateTree(process, Duration.ofSeconds(15));
            }
            exitCode = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminateTree(process, Duration.ofSeconds(5));
            exitCode = -1;
        } catch (IllegalThreadStateException e) {
            exitCode = -1;
        }

        join(outThread);
        join(errThread);

        Result result = new Result(exitCode, List.copyOf(stdout), List.copyOf(stderr),
                Duration.between(start, Instant.now()), timedOut, String.join(" ", command));
        if (logSink != null) {
            writeLog(logSink, result);
        }
        if (timedOut) {
            LOG.warn("Command timed out after {}: {}", timeout, result.command());
        }
        return result;
    }

    private Thread drain(java.io.InputStream stream, List<String> sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        if (sink.size() < maxOutputLines) {
                            sink.add(line);
                        } else if (sink.size() == maxOutputLines) {
                            sink.add("... output truncated at " + maxOutputLines + " lines ...");
                        }
                    }
                }
            } catch (IOException ignored) {
                // stream closed by process termination
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void join(Thread thread) {
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Persists an execution log with credentials removed before anything is written.
     *
     * <p>Redacting a final summary is not enough: a build log is itself evidence, is copied into the
     * evidence store, and is what an operator reads first. A connection string printed by a failing
     * datasource lands here long before any summary exists.
     */
    private void writeLog(Path logSink, Result result) {
        try {
            Files.createDirectories(logSink.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("$ ").append(redact(result.command())).append(System.lineSeparator());
            sb.append("exit=").append(result.exitCode())
                    .append(" timedOut=").append(result.timedOut())
                    .append(" duration=").append(result.duration()).append(System.lineSeparator());
            sb.append("--- stdout ---").append(System.lineSeparator());
            result.stdout().forEach(l -> sb.append(redact(l)).append(System.lineSeparator()));
            sb.append("--- stderr ---").append(System.lineSeparator());
            result.stderr().forEach(l -> sb.append(redact(l)).append(System.lineSeparator()));
            Files.writeString(logSink, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Cannot persist execution log {}: {}", logSink, e.getMessage());
        }
    }

    /** Redaction applied to every line before it reaches disk. */
    public static String redact(String line) {
        return com.bootshift.core.security.SensitiveValues.redactLine(null, line);
    }

    /**
     * Builds the child environment from an explicit allowlist plus the caller's own additions.
     *
     * <p>Locale and timezone are pinned so two sides of a differential comparison format dates and
     * numbers identically; a difference caused by the harness running in a different locale is not a
     * migration difference.
     */
    void applySanitizedEnvironment(ProcessBuilder builder, Map<String, String> additions) {
        Map<String, String> inherited = new LinkedHashMap<>(builder.environment());
        builder.environment().clear();
        for (Map.Entry<String, String> entry : inherited.entrySet()) {
            if (isInheritable(entry.getKey())) {
                builder.environment().put(entry.getKey(), entry.getValue());
            }
        }
        // Caller additions are deliberate and are not filtered by name; they are values the harness
        // itself computed, such as the JAVA_HOME for the frozen edge toolchain.
        additions.forEach((key, value) -> {
            if (value != null) {
                builder.environment().put(key, value);
            }
        });
        builder.environment().put("LANG", "C");
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("TZ", "UTC");
    }

    /** True when a variable may pass from the harness process into an untrusted child. */
    public static boolean isInheritable(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (SECRET_NAME_FRAGMENTS.stream().anyMatch(upper::contains)) {
            return false;
        }
        return INHERITED_ENVIRONMENT.contains(name) || INHERITED_ENVIRONMENT.contains(upper);
    }

    public static Set<String> inheritedEnvironmentNames() {
        return INHERITED_ENVIRONMENT;
    }

    /**
     * Starts a long-running process under the same controls as {@link #run}.
     *
     * <p>The runtime probe used to construct its own {@code ProcessBuilder}, which meant the
     * application under analysis was launched with none of the allowlist, none of the environment
     * sanitisation and no tree termination - for the one process in the whole harness that runs
     * untrusted application code for ninety seconds and opens a port.
     */
    public Handle start(List<String> command, Path workingDirectory, Map<String, String> environment,
                        Path logFile) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Empty command");
        }
        assertAllowed(command.get(0));
        try {
            if (logFile != null && logFile.getParent() != null) {
                Files.createDirectories(logFile.getParent());
            }
            ProcessBuilder builder = new ProcessBuilder(shellWrap(command));
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            if (logFile != null) {
                builder.redirectOutput(logFile.toFile());
            }
            applySanitizedEnvironment(builder, environment);
            return new Handle(builder.start(), null, String.join(" ", command));
        } catch (IOException e) {
            return new Handle(null, e.getMessage(), String.join(" ", command));
        }
    }

    /** A started long-running process, with deterministic cleanup. */
    public record Handle(Process process, String failure, String command) {

        public boolean started() {
            return process != null;
        }

        /** Terminates the whole descendant tree and waits for it to be gone. */
        public void terminate(Duration grace) {
            if (process != null) {
                ProcessRunner.terminateTree(process, grace);
            }
        }
    }

    /**
     * Terminates a process and every descendant it spawned.
     *
     * <p>Descendants are collected before the parent is signalled: killing the parent first
     * reparents its children and they become unreachable through the handle.
     */
    public static void terminateTree(Process process, Duration grace) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        process.destroy();
        descendants.forEach(ProcessHandle::destroy);
        try {
            if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        // Anything still alive after the forcible pass is reported rather than left silently behind.
        List<ProcessHandle> survivors = descendants.stream().filter(ProcessHandle::isAlive).toList();
        if (!survivors.isEmpty()) {
            LOG.warn("{} descendant process(es) survived termination: {}", survivors.size(),
                    survivors.stream().map(ProcessHandle::pid).toList());
        }
    }

    /**
     * Windows batch launchers ({@code mvnw.cmd}, {@code gradlew.bat}) are not directly executable by
     * {@code CreateProcess}, so they are dispatched through {@code cmd.exe /c}. The argument vector
     * is preserved rather than flattened into a shell string, so nothing is re-parsed.
     */
    static List<String> shellWrap(List<String> command) {
        if (!WINDOWS) {
            return command;
        }
        String executable = command.get(0).toLowerCase(Locale.ROOT);
        if (!executable.endsWith(".cmd") && !executable.endsWith(".bat")) {
            return command;
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }

    /** Resolves an executable on PATH, honouring Windows extensions. */
    public static String which(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> candidates = windows
                ? List.of(name + ".cmd", name + ".exe", name + ".bat", name)
                : List.of(name);
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : candidates) {
                Path p = Path.of(dir).resolve(candidate);
                if (Files.isRegularFile(p)) {
                    return p.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    /** Locates the JDK tool of the given name next to the running JVM. */
    public static String jdkTool(String name) {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path tool = Path.of(javaHome, "bin", windows ? name + ".exe" : name);
            if (Files.isRegularFile(tool)) {
                return tool.toAbsolutePath().toString();
            }
        }
        return which(name);
    }
}
