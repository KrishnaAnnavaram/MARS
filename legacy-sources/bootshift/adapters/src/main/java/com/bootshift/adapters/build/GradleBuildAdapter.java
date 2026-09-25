package com.bootshift.adapters.build;

import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.ports.build.BuildSystemPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle adapter (Agent 02, second build system).
 *
 * <p>Follows the same authority rule as Maven: the model comes from {@code dependencies},
 * {@code buildEnvironment} and {@code properties} tasks. Build scripts are read only to locate
 * included builds and convention plugins, which are then reported as hints.
 */
public final class GradleBuildAdapter implements BuildSystemPort {

    private static final Duration RESOLVE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);

    /** Lines such as "+--- org.springframework.boot:spring-boot-starter-web:2.7.12". */
    private static final Pattern DEP_LINE = Pattern.compile(
            "^[|+\\\\ ---]*([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+(?:\\s+->\\s+[\\w.\\-]+)?).*$");

    private static final Pattern CONFIGURATION_HEADER = Pattern.compile("^(\\w+)\\s+-\\s+.*$");

    private final ProcessRunner runner;

    public GradleBuildAdapter() {
        this(new ProcessRunner());
    }

    public GradleBuildAdapter(ProcessRunner runner) {
        this.runner = runner;
    }

    @Override
    public Kind detect(Path repositoryRoot) {
        return supports(repositoryRoot) ? Kind.GRADLE : Kind.UNKNOWN;
    }

    @Override
    public boolean supports(Path repositoryRoot) {
        return Files.isRegularFile(repositoryRoot.resolve("build.gradle"))
                || Files.isRegularFile(repositoryRoot.resolve("build.gradle.kts"))
                || Files.isRegularFile(repositoryRoot.resolve("settings.gradle"))
                || Files.isRegularFile(repositoryRoot.resolve("settings.gradle.kts"));
    }

    @Override
    public BuildModel resolve(Path repositoryRoot, Path evidenceSink) {
        List<ModuleModel> modules = new ArrayList<>();
        List<ResolvedDependency> dependencies = new ArrayList<>();
        List<ResolvedPlugin> plugins = new ArrayList<>();
        List<ResolutionIssue> issues = new ArrayList<>();
        Map<String, String> toolchains = new LinkedHashMap<>();

        String executable = resolveExecutable(repositoryRoot);
        boolean wrapperUsed = executable != null && executable.toLowerCase(Locale.ROOT).contains("gradlew");
        String toolVersion = null;
        boolean authoritative = false;
        String degradedReason;

        if (executable != null) {
            ProcessRunner.Result version = runner.run(List.of(executable, "--version", "--console=plain"),
                    repositoryRoot, Duration.ofMinutes(5), Map.of());
            for (String line : version.stdout()) {
                if (line.startsWith("Gradle ")) {
                    toolVersion = line.trim();
                }
            }
            authoritative = version.success() && toolVersion != null;
        }
        degradedReason = authoritative ? null
                : "No usable Gradle distribution; dependency model derived from build script hints only "
                  + "and is NOT authoritative (R5).";
        if (!authoritative) {
            issues.add(new ResolutionIssue("BLOCKING", null, degradedReason,
                    "Provide a Gradle wrapper distribution or install Gradle, then re-run resolve-build."));
        }

        modules.add(new ModuleModel(".", repositoryRoot.toString().replace((char) 92, '/'),
                null, repositoryRoot.getFileName().toString(), null, "jar", null, null,
                new LinkedHashMap<>(), includedBuilds(repositoryRoot)));

        if (authoritative) {
            ProcessRunner.Result deps = runner.run(
                    List.of(executable, "dependencies", "--console=plain", "-q"),
                    repositoryRoot, RESOLVE_TIMEOUT, Map.of(),
                    evidenceSink == null ? null : evidenceSink.resolve("gradle/dependencies.log"));
            String configuration = "unknown";
            for (String line : deps.stdout()) {
                Matcher header = CONFIGURATION_HEADER.matcher(line);
                if (header.matches()) {
                    configuration = header.group(1);
                    continue;
                }
                Matcher matcher = DEP_LINE.matcher(line);
                if (matcher.matches()) {
                    String resolvedVersion = matcher.group(3);
                    if (resolvedVersion.contains("->")) {
                        resolvedVersion = resolvedVersion.substring(resolvedVersion.indexOf("->") + 2).trim();
                    }
                    dependencies.add(new ResolvedDependency(matcher.group(1), matcher.group(2),
                            resolvedVersion, "jar", configuration, ".", null, null,
                            !line.startsWith("|"), true, null, "RESOLVED"));
                }
            }
            ProcessRunner.Result env = runner.run(
                    List.of(executable, "buildEnvironment", "--console=plain", "-q"),
                    repositoryRoot, RESOLVE_TIMEOUT, Map.of(),
                    evidenceSink == null ? null : evidenceSink.resolve("gradle/build-environment.log"));
            for (String line : env.stdout()) {
                Matcher matcher = DEP_LINE.matcher(line);
                if (matcher.matches()) {
                    plugins.add(new ResolvedPlugin(matcher.group(1), matcher.group(2), matcher.group(3),
                            ".", "buildscript"));
                }
            }
        } else {
            parseScriptHints(repositoryRoot, dependencies, plugins);
        }

        toolchains.put("java.version", System.getProperty("java.version"));
        return new BuildModel(Kind.GRADLE, toolVersion, executable, wrapperUsed, modules, dependencies,
                plugins, List.of(), List.of(), issues, toolchains, authoritative, degradedReason);
    }

    @Override
    public ExecutionResult compile(Path repositoryRoot, Path modulePath, Map<String, String> options) {
        return invoke(modulePath, List.of("classes", "testClasses", "--console=plain"), options);
    }

    @Override
    public ExecutionResult test(Path repositoryRoot, Path modulePath, Map<String, String> options) {
        return invoke(modulePath, List.of("test", "--console=plain"), options);
    }

    @Override
    public ExecutionResult invoke(Path repositoryRoot, List<String> goals, Map<String, String> options) {
        String executable = resolveExecutable(repositoryRoot);
        if (executable == null) {
            return new ExecutionResult(false, -1, Duration.ZERO, "gradle-not-available",
                    List.of(), List.of("No Gradle distribution available"), repositoryRoot);
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(goals);
        options.forEach((k, v) -> command.add("-P" + k + "=" + v));
        ProcessRunner.Result result = runner.run(command, repositoryRoot, BUILD_TIMEOUT, Map.of());
        return new ExecutionResult(result.success(), result.exitCode(), result.duration(),
                result.command(), result.stdout(), result.stderr(), repositoryRoot);
    }

    public String resolveExecutable(Path repositoryRoot) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path wrapper = repositoryRoot.resolve(windows ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(wrapper)
                && Files.isRegularFile(repositoryRoot.resolve("gradle/wrapper/gradle-wrapper.jar"))) {
            return wrapper.toAbsolutePath().toString();
        }
        return ProcessRunner.which("gradle");
    }

    private List<String> includedBuilds(Path repositoryRoot) {
        List<String> included = new ArrayList<>();
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = repositoryRoot.resolve(name);
            if (!Files.isRegularFile(settings)) {
                continue;
            }
            Matcher matcher = Pattern.compile("include(?:Build)?[\\s(]+['\"]([^'\"]+)['\"]")
                    .matcher(read(settings));
            while (matcher.find()) {
                included.add(matcher.group(1));
            }
        }
        if (Files.isDirectory(repositoryRoot.resolve("buildSrc"))) {
            included.add("buildSrc");
        }
        return included;
    }

    private void parseScriptHints(Path repositoryRoot, List<ResolvedDependency> dependencies,
                                  List<ResolvedPlugin> plugins) {
        for (String name : List.of("build.gradle", "build.gradle.kts")) {
            Path script = repositoryRoot.resolve(name);
            if (!Files.isRegularFile(script)) {
                continue;
            }
            String text = read(script);
            Matcher matcher = Pattern.compile(
                            "(implementation|api|testImplementation|compileOnly|runtimeOnly|annotationProcessor)"
                                    + "[\\s(]+['\"]([\\w.\\-]+):([\\w.\\-]+)(?::([\\w.\\-]+))?['\"]")
                    .matcher(text);
            while (matcher.find()) {
                dependencies.add(new ResolvedDependency(matcher.group(2), matcher.group(3),
                        matcher.group(4), "jar", matcher.group(1), ".", null, null, true, false,
                        null, "DECLARED_HINT_NOT_RESOLVED"));
            }
            Matcher pluginMatcher = Pattern.compile("id[\\s(]+['\"]([\\w.\\-]+)['\"]").matcher(text);
            while (pluginMatcher.find()) {
                plugins.add(new ResolvedPlugin(pluginMatcher.group(1), pluginMatcher.group(1), null,
                        ".", "plugins-block-hint"));
            }
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
