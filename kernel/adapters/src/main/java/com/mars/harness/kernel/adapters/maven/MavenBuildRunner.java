package com.mars.harness.kernel.adapters.maven;

import com.bootshift.adapters.exec.ProcessRunner;
import com.mars.harness.kernel.ports.build.BuildPort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maven build execution through Bootshift's {@link ProcessRunner}, which provides the allowlist,
 * timeout, output cap, sanitised environment and log redaction.
 *
 * <p>Executable resolution follows the migration reference ({@code resolveBuildTool}) extended
 * with Bootshift's variables:
 *
 * <ol>
 *   <li>the project's {@code mvnw} wrapper</li>
 *   <li>{@code HARNESS_MAVEN} / {@code MIGRATION_MVN}</li>
 *   <li>{@code mvn} on PATH</li>
 *   <li>{@code MAVEN_HOME}, {@code M2_HOME} or {@code BOOTSHIFT_MAVEN_HOME}</li>
 *   <li>the Maven wrapper distribution cache under {@code ~/.m2/wrapper/dists}</li>
 * </ol>
 *
 * <p>No Maven means {@link BuildPort.Outcome#TOOL_UNAVAILABLE}: an honest adapter failure, never a
 * fabricated result.
 */
public final class MavenBuildRunner implements BuildPort {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final int LOG_TAIL = 8000;

    private final ProcessRunner runner = new ProcessRunner();
    private final Duration timeout;
    private final boolean offline;
    private final Map<String, String> environment;

    public MavenBuildRunner(Duration timeout, boolean offline) {
        this(timeout, offline, Map.of());
    }

    public MavenBuildRunner(Duration timeout, boolean offline, Map<String, String> environment) {
        this.timeout = timeout;
        this.offline = offline;
        this.environment = environment;
    }

    @Override
    public Availability availability(Path projectDir) {
        if (!Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            return new Availability(false, "maven", null, "No pom.xml in " + projectDir.getFileName());
        }
        String executable = resolveExecutable(projectDir);
        if (executable == null) {
            return new Availability(false, "maven", null,
                    "No Maven executable found (wrapper, HARNESS_MAVEN, PATH, MAVEN_HOME, ~/.m2/wrapper/dists)");
        }
        return new Availability(true, "maven", executable, null);
    }

    @Override
    public BuildResult build(Path projectDir, Intent intent, Path logFile) {
        Availability availability = availability(projectDir);
        String jdk = System.getProperty("java.version");
        if (!availability.available()) {
            return new BuildResult(intent, Outcome.TOOL_UNAVAILABLE, -1, 0, "maven", null, jdk, List.of(), List.of(),
                    Map.of(), null, List.of(), "", null, availability.reason());
        }
        List<String> command = new ArrayList<>();
        command.add(availability.version());
        command.add("-B");
        if (offline) {
            command.add("-o");
        }
        command.addAll(args(intent));
        Map<String, String> env = new LinkedHashMap<>(environment);
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            env.putIfAbsent("JAVA_HOME", javaHome);
        }
        ProcessRunner.Result result = runner.run(command, projectDir, timeout, env, logFile);
        String output = String.join("\n", result.stdout()) + "\n" + String.join("\n", result.stderr());
        List<BuildError> errors = BuildErrorClassifier.parse(output, projectDir.toAbsolutePath().toString());
        List<BuildError> capped = errors.size() > 200 ? errors.subList(0, 200) : errors;
        Outcome outcome = BuildErrorClassifier.outcome(result.exitCode(), result.timedOut(), errors);
        String tail = output.length() > LOG_TAIL ? output.substring(output.length() - LOG_TAIL) : output;
        tail = tail.replace(projectDir.toAbsolutePath().toString(), ".");
        return new BuildResult(intent, outcome, result.exitCode(), result.duration().toMillis(), "maven",
                availability.version(), jdk, command, capped, BuildErrorClassifier.byCategory(errors),
                BuildErrorClassifier.testSummary(output), BuildErrorClassifier.failingTests(output), tail, logFile, null);
    }

    /** The reference workflow's intent to Maven arguments table, verbatim. */
    static List<String> args(Intent intent) {
        return switch (intent) {
            case COMPILE -> List.of("clean", "compile");
            case TEST_COMPILE -> List.of("clean", "test-compile");
            case TEST -> List.of("clean", "test");
            case PACKAGE -> List.of("clean", "package");
            case VERIFY -> List.of("clean", "verify");
            case PACKAGE_SKIP_TESTS -> List.of("clean", "package", "-DskipTests");
            case DEPENDENCY_TREE -> List.of("dependency:tree");
        };
    }

    static String resolveExecutable(Path projectDir) {
        Path wrapper = projectDir.resolve(WINDOWS ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper) && Files.isRegularFile(projectDir.resolve(".mvn/wrapper/maven-wrapper.properties"))) {
            return wrapper.toAbsolutePath().toString();
        }
        for (String variable : List.of("HARNESS_MAVEN", "MIGRATION_MVN")) {
            String value = System.getenv(variable);
            if (value != null && Files.isRegularFile(Path.of(value))) {
                return value;
            }
        }
        String onPath = ProcessRunner.which("mvn");
        if (onPath != null) {
            return onPath;
        }
        for (String variable : List.of("MAVEN_HOME", "M2_HOME", "BOOTSHIFT_MAVEN_HOME")) {
            String home = System.getenv(variable);
            if (home != null) {
                Path candidate = Path.of(home, "bin", WINDOWS ? "mvn.cmd" : "mvn");
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        Path dists = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        return findInWrapperCache(dists);
    }

    private static String findInWrapperCache(Path dists) {
        if (!Files.isDirectory(dists)) {
            return null;
        }
        try (var stream = Files.walk(dists, 6)) {
            return stream.filter(p -> p.getFileName().toString().equals(WINDOWS ? "mvn.cmd" : "mvn"))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("bin"))
                    .map(Path::toString)
                    .sorted()
                    .reduce((a, b) -> b)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
