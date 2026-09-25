package com.bootshift.adapters.build;

import com.bootshift.adapters.exec.ProcessRunner;
import com.bootshift.ports.build.BuildSystemPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven adapter (Agent 02).
 *
 * <p>R5 in practice: the effective model comes from {@code help:effective-pom},
 * {@code dependency:list} and {@code dependency:resolve-plugins}. The XML in the repository is read
 * only to locate modules and to supply hints when Maven itself cannot run, and in that case the
 * model is explicitly marked non-authoritative with a degraded reason rather than being presented
 * as fact.
 */
public final class MavenBuildAdapter implements BuildSystemPort {

    private static final Logger LOG = LoggerFactory.getLogger(MavenBuildAdapter.class);

    private static final Duration RESOLVE_TIMEOUT = Duration.ofMinutes(12);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(25);

    /** groupId:artifactId:type[:classifier]:version:scope as emitted by dependency:list. */
    private static final Pattern DEP_LINE = Pattern.compile(
            "^\\s*(?:\\[INFO\\]\\s+)?([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)(?::([\\w.\\-]+))?:"
                    + "([\\w.\\-]+):(compile|provided|runtime|test|system|import)\\b.*$");

    private static final Pattern MODULE_TAG = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
    // Property names legitimately contain dots and hyphens (java.version, spring-cloud.version),
    // so the element-name class has to be wider than the default word class.
    private static final Pattern TAG = Pattern.compile("<([\\w.\\-]+)>\\s*([^<]*?)\\s*</\\1>");

    private final ProcessRunner runner;

    public MavenBuildAdapter() {
        this(new ProcessRunner());
    }

    public MavenBuildAdapter(ProcessRunner runner) {
        this.runner = runner;
    }

    @Override
    public Kind detect(Path repositoryRoot) {
        return supports(repositoryRoot) ? Kind.MAVEN : Kind.UNKNOWN;
    }

    @Override
    public boolean supports(Path repositoryRoot) {
        if (Files.isRegularFile(repositoryRoot.resolve("pom.xml"))) {
            return true;
        }
        return !discoverModulePoms(repositoryRoot).isEmpty();
    }

    /**
     * Finds every reactor root under the given directory. The input corpus is a directory of
     * independent services rather than a single reactor, so more than one root is normal.
     */
    public List<Path> discoverModulePoms(Path root) {
        List<Path> poms = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            poms.add(root.resolve("pom.xml"));
            return poms;
        }
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory).sorted().forEach(dir -> {
                Path pom = dir.resolve("pom.xml");
                if (Files.isRegularFile(pom)) {
                    poms.add(pom);
                }
            });
        } catch (IOException e) {
            LOG.warn("Cannot enumerate {}: {}", root, e.getMessage());
        }
        return poms;
    }

    @Override
    public BuildModel resolve(Path repositoryRoot, Path evidenceSink) {
        List<Path> reactorPoms = discoverModulePoms(repositoryRoot);
        List<ModuleModel> modules = new ArrayList<>();
        List<ResolvedDependency> dependencies = new ArrayList<>();
        List<ResolvedPlugin> plugins = new ArrayList<>();
        List<ManagedVersion> managed = new ArrayList<>();
        List<RepositoryRef> repositories = new ArrayList<>();
        List<ResolutionIssue> issues = new ArrayList<>();
        Map<String, String> toolchains = new LinkedHashMap<>();

        // Candidate chain: repository wrapper first (spec section 12), then an installed Maven.
        // Each candidate is probed rather than assumed, and the first one that answers wins.
        List<String> candidates = executableCandidates(repositoryRoot, reactorPoms);
        String executable = null;
        String toolVersion = null;
        List<String> probeFailures = new ArrayList<>();
        for (String candidate : candidates) {
            String version = probeVersion(candidate, probeDirectory(candidate, repositoryRoot));
            if (version != null) {
                executable = candidate;
                toolVersion = version;
                break;
            }
            probeFailures.add(candidate);
        }

        boolean wrapperUsed = executable != null && executable.toLowerCase(Locale.ROOT).contains("mvnw");
        boolean authoritative = executable != null;
        String degradedReason = null;

        if (!authoritative) {
            degradedReason = candidates.isEmpty()
                    ? "No Maven executable or wrapper could be located; the build model is derived from "
                      + "descriptor parsing and is NOT authoritative (R5)."
                    : "None of the located Maven executables responded to -version: " + probeFailures
                      + "; the build model is descriptor-derived and NOT authoritative (R5).";
            issues.add(new ResolutionIssue("BLOCKING", null, degradedReason,
                    "Install Maven or make the project wrapper usable, then re-run resolve-build."));
        } else if (!probeFailures.isEmpty()) {
            issues.add(new ResolutionIssue("INFO", null,
                    "Preferred Maven candidate(s) did not respond and were skipped: " + probeFailures,
                    "The model came from " + executable + " (" + toolVersion + ")."));
        }

        toolchains.put("java.version", System.getProperty("java.version"));
        toolchains.put("java.vendor", System.getProperty("java.vendor"));
        toolchains.put("java.home", System.getProperty("java.home"));

        for (Path pom : reactorPoms) {
            Path moduleDir = pom.getParent();
            String moduleId = repositoryRoot.relativize(moduleDir).toString().replace((char) 92, '/');
            if (moduleId.isEmpty()) {
                moduleId = ".";
            }
            String pomText = readSafely(pom);

            ModuleModel model = parseModuleDescriptor(moduleId, moduleDir, pomText);
            repositories.addAll(defaultRepositories());

            if (authoritative) {
                Path moduleEvidence = evidenceSink == null ? null
                        : evidenceSink.resolve("maven").resolve(sanitize(moduleId));
                collectEffectivePom(executable, moduleDir, moduleEvidence, moduleId, managed, issues);
                collectDependencies(executable, moduleDir, moduleEvidence, moduleId, dependencies, issues);
                collectPlugins(executable, moduleDir, moduleEvidence, moduleId, plugins, issues);
                model = withClasspath(model,
                        collectClasspath(executable, moduleDir, moduleEvidence, moduleId, issues));
            } else {
                parseDeclaredDependenciesAsHints(pomText, moduleId, dependencies);
            }
            modules.add(model);
        }

        return new BuildModel(Kind.MAVEN, toolVersion,
                executable == null ? null : executable + " -B",
                wrapperUsed, modules, dedupe(dependencies), plugins, managed,
                new ArrayList<>(new LinkedHashSet<>(repositories)), issues, toolchains,
                authoritative, degradedReason);
    }

    private void collectEffectivePom(String executable, Path moduleDir, Path evidenceDir, String moduleId,
                                     List<ManagedVersion> managed, List<ResolutionIssue> issues) {
        Path output = tempFile(evidenceDir, "effective-pom.xml");
        ProcessRunner.Result result = runner.run(
                List.of(executable, "-B", "-q", "org.apache.maven.plugins:maven-help-plugin:3.4.0:effective-pom",
                        "-Doutput=" + output.toAbsolutePath()),
                moduleDir, RESOLVE_TIMEOUT, Map.of(),
                evidenceDir == null ? null : evidenceDir.resolve("effective-pom.log"));
        if (!result.success() || !Files.isRegularFile(output)) {
            issues.add(new ResolutionIssue("WARNING", moduleId,
                    "effective-pom could not be produced: " + lastMeaningfulLine(result),
                    "Check network access to the plugin repository and the module parent resolution."));
            return;
        }
        String effective = readSafely(output);
        managed.addAll(parseManagedVersions(effective, moduleId));
    }

    private void collectDependencies(String executable, Path moduleDir, Path evidenceDir, String moduleId,
                                     List<ResolvedDependency> dependencies, List<ResolutionIssue> issues) {
        ProcessRunner.Result result = runner.run(
                List.of(executable, "-B", "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:list",
                        "-DoutputAbsoluteArtifactFilename=false", "-DincludeParents=true"),
                moduleDir, RESOLVE_TIMEOUT, Map.of(),
                evidenceDir == null ? null : evidenceDir.resolve("dependency-list.log"));
        Set<String> declared = declaredCoordinates(readSafely(moduleDir.resolve("pom.xml")));
        boolean any = false;
        for (String line : result.stdout()) {
            Matcher matcher = DEP_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            any = true;
            String group = matcher.group(1);
            String artifact = matcher.group(2);
            String type = matcher.group(3);
            String version = matcher.group(5);
            String scope = matcher.group(6);
            dependencies.add(new ResolvedDependency(group, artifact, version, type, scope, moduleId,
                    null, "central", declared.contains(group + ":" + artifact), true, null, "RESOLVED"));
        }
        if (!result.success() || !any) {
            // dependency:list builds a full project model for every transitive artifact, so a single
            // malformed upstream POM fails the whole goal. dependency:tree walks the resolved graph
            // instead and tolerates that, so it is the second strategy rather than an equal one.
            issues.add(new ResolutionIssue("WARNING", moduleId,
                    "dependency:list did not produce resolved artifacts: " + lastMeaningfulLine(result),
                    "Falling back to dependency:tree, which does not require a buildable project model "
                            + "for every transitive artifact."));
            any = collectDependenciesFromTree(executable, moduleDir, evidenceDir, moduleId,
                    dependencies, declared);
        }
        if (!any) {
            issues.add(new ResolutionIssue("BLOCKING", moduleId,
                    "Neither dependency:list nor dependency:tree resolved artifacts for this module",
                    "Unresolved required artifacts are a blocking issue or an explicit blind spot; "
                            + "they are never substituted."));
            parseDeclaredDependenciesAsHints(readSafely(moduleDir.resolve("pom.xml")), moduleId, dependencies);
        }
    }

    /** groupId:artifactId:packaging[:classifier]:version:scope as emitted by dependency:tree. */
    private static final Pattern TREE_LINE = Pattern.compile(
            "^[\\s|+\\\\-]*([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)(?::([\\w.\\-]+))?:"
                    + "([\\w.\\-]+):(compile|provided|runtime|test|system)\\b.*$");

    private boolean collectDependenciesFromTree(String executable, Path moduleDir, Path evidenceDir,
                                                String moduleId, List<ResolvedDependency> dependencies,
                                                Set<String> declared) {
        Path output = tempFile(evidenceDir, "dependency-tree.txt");
        ProcessRunner.Result result = runner.run(
                List.of(executable, "-B", "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:tree",
                        "-DoutputType=text", "-DoutputFile=" + output.toAbsolutePath()),
                moduleDir, RESOLVE_TIMEOUT, Map.of(),
                evidenceDir == null ? null : evidenceDir.resolve("dependency-tree.log"));
        if (!result.success() || !Files.isRegularFile(output)) {
            return false;
        }
        boolean any = false;
        // Split on any line terminator and strip: Maven writes platform separators, and a trailing
        // carriage return would leave an unconsumed character that defeats a full-line match.
        for (String raw : readSafely(output).split("\\R")) {
            String line = raw.stripTrailing();
            // The first line is the project itself and carries no tree prefix; skip it so a module
            // never appears as a dependency of itself.
            if (line.isBlank() || Character.isLetterOrDigit(line.charAt(0))) {
                continue;
            }
            Matcher matcher = TREE_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String group = matcher.group(1);
            String artifact = matcher.group(2);
            boolean direct = line.indexOf('+') == line.lastIndexOf('+') && !line.contains("|");
            any = true;
            dependencies.add(new ResolvedDependency(group, artifact, matcher.group(5),
                    matcher.group(3), matcher.group(6), moduleId, null, "central",
                    direct || declared.contains(group + ":" + artifact), true, null, "RESOLVED"));
        }
        return any;
    }

    /**
     * Resolves the real compile-plus-test classpath.
     *
     * <p>This is what makes type attribution meaningful: without dependency jars the symbol solver
     * cannot resolve a call into Spring or Jackson, and every such relationship would be recorded as
     * UNRESOLVED, which in turn caps impact classification.
     */
    private List<String> collectClasspath(String executable, Path moduleDir, Path evidenceDir,
                                          String moduleId, List<ResolutionIssue> issues) {
        Path output = tempFile(evidenceDir, "classpath.txt");
        ProcessRunner.Result result = runner.run(
                List.of(executable, "-B", "-q",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath",
                        "-Dmdep.outputFile=" + output.toAbsolutePath(),
                        "-Dmdep.includeScope=test", "-Dmdep.pathSeparator=" + java.io.File.pathSeparator),
                moduleDir, RESOLVE_TIMEOUT, Map.of(),
                evidenceDir == null ? null : evidenceDir.resolve("build-classpath.log"));
        if (!result.success() || !Files.isRegularFile(output)) {
            issues.add(new ResolutionIssue("WARNING", moduleId,
                    "build-classpath did not produce a classpath: " + lastMeaningfulLine(result),
                    "Type attribution for dependency-owned symbols will be UNRESOLVED, which caps "
                            + "impact classification at POSSIBLY_AFFECTED."));
            return List.of();
        }
        String text = readSafely(output).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : text.split(Pattern.quote(java.io.File.pathSeparator))) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    private static ModuleModel withClasspath(ModuleModel model, List<String> classpath) {
        return new ModuleModel(model.moduleId(), model.path(), model.groupId(), model.artifactId(),
                model.version(), model.packaging(), model.parentGav(), model.javaVersion(),
                model.properties(), model.activeProfiles(), classpath);
    }

    private void collectPlugins(String executable, Path moduleDir, Path evidenceDir, String moduleId,
                                List<ResolvedPlugin> plugins, List<ResolutionIssue> issues) {
        ProcessRunner.Result result = runner.run(
                List.of(executable, "-B", "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:resolve-plugins"),
                moduleDir, RESOLVE_TIMEOUT, Map.of(),
                evidenceDir == null ? null : evidenceDir.resolve("resolve-plugins.log"));
        for (String line : result.stdout()) {
            Matcher matcher = DEP_LINE.matcher(line);
            if (matcher.matches()) {
                plugins.add(new ResolvedPlugin(matcher.group(1), matcher.group(2), matcher.group(5),
                        moduleId, null));
            }
        }
        if (!result.success()) {
            issues.add(new ResolutionIssue("WARNING", moduleId,
                    "resolve-plugins failed: " + lastMeaningfulLine(result),
                    "Plugin toolchain differences are a common first root cause during migration."));
        }
    }

    @Override
    public ExecutionResult compile(Path repositoryRoot, Path modulePath, Map<String, String> options) {
        return invokeIn(modulePath, List.of("-B", "-DskipTests", "test-compile"), options,
                repositoryRoot);
    }

    @Override
    public ExecutionResult test(Path repositoryRoot, Path modulePath, Map<String, String> options) {
        return invokeIn(modulePath, List.of("-B", "test"), options, repositoryRoot);
    }

    @Override
    public ExecutionResult invoke(Path repositoryRoot, List<String> goals, Map<String, String> options) {
        return invokeIn(repositoryRoot, goals, options, repositoryRoot);
    }

    private ExecutionResult invokeIn(Path workingDirectory, List<String> goals,
                                     Map<String, String> options, Path repositoryRoot) {
        String executable = resolveExecutable(repositoryRoot, List.of(workingDirectory.resolve("pom.xml")));
        if (executable == null) {
            return new ExecutionResult(false, -1, Duration.ZERO, "maven-not-available",
                    List.of(), List.of("No Maven executable or wrapper available"), workingDirectory);
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(goals);
        options.forEach((k, v) -> {
            if (k.startsWith("bootshift.")) {
                return;
            }
            command.add("-D" + k + (v == null || v.isEmpty() ? "" : "=" + v));
        });
        Path logSink = options.containsKey("bootshift.logSink") ? Path.of(options.get("bootshift.logSink")) : null;

        // The toolchain is part of the environment, not part of the application. Overriding JAVA_HOME
        // lets the baseline run on a JDK the original project actually supports without editing it.
        Map<String, String> environment = new LinkedHashMap<>();
        String javaHome = options.get("bootshift.javaHome");
        if (javaHome != null && !javaHome.isBlank()) {
            environment.put("JAVA_HOME", javaHome);
            environment.put("PATH", Path.of(javaHome, "bin") + java.io.File.pathSeparator
                    + System.getenv().getOrDefault("PATH", ""));
        }
        ProcessRunner.Result result =
                runner.run(command, workingDirectory, BUILD_TIMEOUT, environment, logSink);
        return new ExecutionResult(result.success(), result.exitCode(), result.duration(),
                result.command(), tail(result.stdout(), 400), tail(result.stderr(), 200), workingDirectory);
    }

    // ------------------------------------------------------------------ helpers

    /** Prefers the repository wrapper, then an installed Maven. Order is the spec's preference order. */
    public List<String> executableCandidates(Path repositoryRoot, List<Path> candidatePoms) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String wrapperName = windows ? "mvnw.cmd" : "mvnw";
        List<String> candidates = new ArrayList<>();
        for (Path pom : candidatePoms) {
            if (pom == null || pom.getParent() == null) {
                continue;
            }
            Path wrapper = pom.getParent().resolve(wrapperName);
            if (Files.isRegularFile(wrapper) && wrapperUsable(wrapper)) {
                candidates.add(wrapper.toAbsolutePath().toString());
                break;
            }
        }
        Path rootWrapper = repositoryRoot.resolve(wrapperName);
        if (Files.isRegularFile(rootWrapper) && wrapperUsable(rootWrapper)) {
            candidates.add(rootWrapper.toAbsolutePath().toString());
        }
        String onPath = ProcessRunner.which("mvn");
        if (onPath != null) {
            candidates.add(onPath);
        }
        for (String variable : List.of("MAVEN_HOME", "M2_HOME", "BOOTSHIFT_MAVEN_HOME")) {
            String home = System.getenv(variable);
            if (home == null || home.isBlank()) {
                continue;
            }
            Path candidate = Path.of(home, "bin", windows ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(candidate)) {
                candidates.add(candidate.toAbsolutePath().toString());
            }
        }
        return candidates.stream().distinct().toList();
    }

    private final Map<String, String> executableCache = new LinkedHashMap<>();

    /**
     * Convenience for callers that only need one executable, e.g. compile and test invocation.
     * Memoized: probing a wrapper can cost a distribution download, and doing that per module would
     * dominate the run.
     */
    public String resolveExecutable(Path repositoryRoot, List<Path> candidatePoms) {
        String key = repositoryRoot.toAbsolutePath().toString();
        String cached = executableCache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        for (String candidate : executableCandidates(repositoryRoot, candidatePoms)) {
            if (probeVersion(candidate, probeDirectory(candidate, repositoryRoot)) != null) {
                executableCache.put(key, candidate);
                return candidate;
            }
        }
        executableCache.put(key, "");
        return null;
    }

    /**
     * A Maven wrapper resolves its project base directory from the working directory, so probing one
     * from the repository root would make it look for a {@code .mvn} folder that is not there. The
     * wrapper is therefore always probed from the module it belongs to.
     */
    private static Path probeDirectory(String executable, Path repositoryRoot) {
        if (executable.toLowerCase(Locale.ROOT).contains("mvnw")) {
            Path parent = Path.of(executable).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        return repositoryRoot;
    }

    /**
     * The Maven wrapper needs its own jar. When the jar is absent the wrapper would try to download
     * one on every invocation, so it is not treated as usable.
     */
    private boolean wrapperUsable(Path wrapper) {
        Path jar = wrapper.getParent().resolve(".mvn/wrapper/maven-wrapper.jar");
        Path properties = wrapper.getParent().resolve(".mvn/wrapper/maven-wrapper.properties");
        try {
            return Files.isRegularFile(jar) && Files.size(jar) > 0 && Files.isRegularFile(properties);
        } catch (IOException e) {
            return false;
        }
    }

    private String probeVersion(String executable, Path workingDirectory) {
        ProcessRunner.Result result = runner.run(List.of(executable, "-B", "-version"),
                workingDirectory, Duration.ofMinutes(8), Map.of());
        for (String line : result.stdout()) {
            if (line.startsWith("Apache Maven")) {
                return line.trim();
            }
        }
        return result.success() && !result.stdout().isEmpty() ? result.stdout().get(0).trim() : null;
    }

    private ModuleModel parseModuleDescriptor(String moduleId, Path moduleDir, String pomText) {
        Map<String, String> properties = new LinkedHashMap<>();
        String propertiesBlock = between(pomText, "<properties>", "</properties>");
        if (propertiesBlock != null) {
            Matcher matcher = TAG.matcher(propertiesBlock);
            while (matcher.find()) {
                properties.put(matcher.group(1), matcher.group(2));
            }
        }
        String parentBlock = between(pomText, "<parent>", "</parent>");
        String parentGav = null;
        if (parentBlock != null) {
            parentGav = tagValue(parentBlock, "groupId") + ":" + tagValue(parentBlock, "artifactId")
                    + ":" + tagValue(parentBlock, "version");
        }
        String body = parentBlock == null ? pomText : pomText.replace(parentBlock, "");
        List<String> submodules = new ArrayList<>();
        Matcher moduleMatcher = MODULE_TAG.matcher(pomText);
        while (moduleMatcher.find()) {
            submodules.add(moduleMatcher.group(1));
        }
        return new ModuleModel(moduleId, moduleDir.toString().replace((char) 92, '/'),
                tagValue(body, "groupId"), tagValue(body, "artifactId"), tagValue(body, "version"),
                tagValue(body, "packaging") == null ? "jar" : tagValue(body, "packaging"),
                parentGav, properties.getOrDefault("java.version", properties.get("maven.compiler.release")),
                properties, submodules);
    }

    private List<ManagedVersion> parseManagedVersions(String effectivePom, String moduleId) {
        List<ManagedVersion> managed = new ArrayList<>();
        String block = between(effectivePom, "<dependencyManagement>", "</dependencyManagement>");
        if (block == null) {
            return managed;
        }
        for (String dependency : splitBlocks(block, "<dependency>", "</dependency>")) {
            String group = tagValue(dependency, "groupId");
            String artifact = tagValue(dependency, "artifactId");
            String version = tagValue(dependency, "version");
            if (group != null && artifact != null && version != null) {
                managed.add(new ManagedVersion(group, artifact, version, "effective-pom:" + moduleId));
            }
        }
        return managed;
    }

    private void parseDeclaredDependenciesAsHints(String pomText, String moduleId,
                                                  List<ResolvedDependency> dependencies) {
        String block = between(pomText, "<dependencies>", "</dependencies>");
        if (block == null) {
            return;
        }
        for (String dependency : splitBlocks(block, "<dependency>", "</dependency>")) {
            String group = tagValue(dependency, "groupId");
            String artifact = tagValue(dependency, "artifactId");
            if (group == null || artifact == null) {
                continue;
            }
            dependencies.add(new ResolvedDependency(group, artifact, tagValue(dependency, "version"),
                    "jar", tagValue(dependency, "scope") == null ? "compile" : tagValue(dependency, "scope"),
                    moduleId, null, null, true, false, null, "DECLARED_HINT_NOT_RESOLVED"));
        }
    }

    private Set<String> declaredCoordinates(String pomText) {
        Set<String> declared = new LinkedHashSet<>();
        String block = between(pomText, "<dependencies>", "</dependencies>");
        if (block == null) {
            return declared;
        }
        for (String dependency : splitBlocks(block, "<dependency>", "</dependency>")) {
            String group = tagValue(dependency, "groupId");
            String artifact = tagValue(dependency, "artifactId");
            if (group != null && artifact != null) {
                declared.add(group + ":" + artifact);
            }
        }
        return declared;
    }

    private List<RepositoryRef> defaultRepositories() {
        return List.of(new RepositoryRef("central", "https://repo.maven.apache.org/maven2", false, true));
    }

    private static List<ResolvedDependency> dedupe(List<ResolvedDependency> input) {
        Map<String, ResolvedDependency> unique = new LinkedHashMap<>();
        for (ResolvedDependency dependency : input) {
            unique.putIfAbsent(dependency.module() + "|" + dependency.gav() + "|" + dependency.scope(),
                    dependency);
        }
        return new ArrayList<>(unique.values());
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(close, start);
        return end < 0 ? null : text.substring(start + open.length(), end);
    }

    private static List<String> splitBlocks(String text, String open, String close) {
        List<String> blocks = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = text.indexOf(open, cursor);
            if (start < 0) {
                break;
            }
            int end = text.indexOf(close, start);
            if (end < 0) {
                break;
            }
            blocks.add(text.substring(start + open.length(), end));
            cursor = end + close.length();
        }
        return blocks;
    }

    private static String tagValue(String text, String tag) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("<" + tag + ">\\s*([^<]*?)\\s*</" + tag + ">").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String readSafely(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static Path tempFile(Path evidenceDir, String name) {
        try {
            if (evidenceDir != null) {
                Files.createDirectories(evidenceDir);
                return evidenceDir.resolve(name);
            }
            return Files.createTempFile("bootshift-", "-" + name);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String lastMeaningfulLine(ProcessRunner.Result result) {
        List<String> candidates = new ArrayList<>(result.stderr());
        candidates.addAll(result.stdout());
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String line = candidates.get(i).trim();
            if (line.contains("ERROR") || line.contains("BUILD FAILURE") || line.contains("Cannot")) {
                return line;
            }
        }
        return result.timedOut() ? "timed out" : "exit code " + result.exitCode();
    }

    private static List<String> tail(List<String> lines, int count) {
        return lines.size() <= count ? lines : new ArrayList<>(lines.subList(lines.size() - count, lines.size()));
    }
}
