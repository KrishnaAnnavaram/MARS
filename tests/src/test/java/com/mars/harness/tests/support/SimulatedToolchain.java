package com.mars.harness.tests.support;

import com.bootshift.core.util.Hashing;
import com.mars.harness.kernel.adapters.maven.BuildErrorClassifier;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.runtime.RuntimePort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * TEST DOUBLE. A content-aware stand-in for Maven and for a running application, used so the
 * end-to-end scenarios run without Docker, a network or a 10-minute Maven build. It is never
 * wired into the CLI; the real adapters are {@code MavenBuildRunner} and {@code JarProbeRuntime},
 * which the {@code real-toolchain} tests exercise.
 *
 * <p>It is not a scripted "return PASSED": it reads the project it is given and reproduces the
 * failure modes the recorded reference migration (spring-boot-3-to-4, rounds 00 to 07) observed
 * from the real compiler and test runner, in Maven's own output format, which the production
 * {@link BuildErrorClassifier} then parses:
 *
 * <ul>
 *   <li>on a Boot 4 parent, imports of packages Boot 4 removed fail with
 *       {@code package X does not exist}; on Boot 3, imports of the Boot 4 packages fail the same way</li>
 *   <li>Boot 4's modular test packages exist only when their starter is declared</li>
 *   <li>on Boot 4 with raw {@code spring-security-test} (no {@code spring-boot-starter-security-test}),
 *       {@code @WithMockUser} tests return 401</li>
 *   <li>{@code @Testcontainers(disabledWithoutDocker = true)} classes are skipped (no Docker here)</li>
 *   <li>environment-dependent failures ({@link #preExistingFailures}) fail on every build, as in round 0</li>
 *   <li>at runtime, springdoc-openapi 2.x does not start on Boot 4; Boot 4's health document and
 *       error bodies differ from Boot 3's (reference pack §13)</li>
 * </ul>
 */
public final class SimulatedToolchain implements BuildPort, RuntimePort {

    /** Packages that no longer exist on a Boot 4 classpath (reference pack §2, §3, §4). */
    static final List<String> REMOVED_IN_BOOT4 = List.of(
            "com.fasterxml.jackson.databind", "com.fasterxml.jackson.datatype.jsr310",
            "org.springframework.boot.autoconfigure.jackson", "org.springframework.boot.actuate.health",
            "org.springframework.boot.test.mock.mockito", "org.springframework.boot.test.autoconfigure.web.servlet",
            "org.springframework.boot.test.autoconfigure.orm.jpa");

    /**
     * Classes that left a package which still exists on Boot 4: javac reports {@code cannot find symbol}
     * with a symbol/location continuation (observed on the real toolchain for this fixture).
     */
    static final List<String> CLASSES_MOVED_IN_BOOT4 = List.of(
            "org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase");

    /** Packages that exist only on Boot 4 (the other direction). */
    static final List<String> BOOT4_ONLY = List.of("tools.jackson", "org.springframework.boot.health.contributor",
            "org.springframework.boot.jackson.autoconfigure", "org.springframework.boot.webmvc.test.autoconfigure",
            "org.springframework.boot.data.jpa.test.autoconfigure", "org.springframework.boot.jdbc.test.autoconfigure");

    /** Boot 4 test packages that exist only when their starter is on the classpath. */
    static final Map<String, String> MODULE_GATED = Map.of(
            "org.springframework.boot.webmvc.test.autoconfigure", "spring-boot-starter-webmvc-test",
            "org.springframework.boot.data.jpa.test.autoconfigure", "spring-boot-starter-data-jpa-test",
            // observed on the real toolchain: spring-boot-jdbc-test arrives with the data-jpa-test starter
            "org.springframework.boot.jdbc.test.autoconfigure", "spring-boot-starter-data-jpa-test");

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;");
    private static final Pattern PARENT_VERSION = Pattern.compile(
            "<parent>.*?<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>(?:\\s*<version>([^<]+)</version>)?",
            Pattern.DOTALL);

    /** A build invocation, for assertions. */
    public record Call(Intent intent, String directory) {
    }

    public final List<Call> calls = new ArrayList<>();
    public final List<String> runtimeCalls = new ArrayList<>();
    /** Test ids ("Class.method") that fail on every build: the environment, not the code. */
    public final List<String> preExistingFailures = new ArrayList<>();
    /** Extra Maven output lines injected into compiling builds matching the predicate (e.g. an error no rule covers). */
    public BiFunction<Path, Intent, List<String>> extraOutput = (dir, intent) -> List.of();
    public boolean available = true;
    /** Crash injection: thrown by the next build call, then cleared. */
    public RuntimeException crashOnNextBuild;

    @Override
    public Availability availability(Path projectDir) {
        return available ? new Availability(true, "maven", "simulated", null)
                : new Availability(false, "maven", null, "simulated: build tool unavailable");
    }

    @Override
    public BuildResult build(Path projectDir, Intent intent, Path logFile) {
        calls.add(new Call(intent, projectDir.getFileName().toString()));
        if (crashOnNextBuild != null) {
            RuntimeException crash = crashOnNextBuild;
            crashOnNextBuild = null;
            throw crash;
        }
        if (!available) {
            return new BuildResult(intent, Outcome.TOOL_UNAVAILABLE, -1, 0, "maven", null, jdk(), List.of("mvn"), List.of(),
                    Map.of(), null, List.of(), null, null, "simulated: build tool unavailable");
        }
        Project project = Project.read(projectDir);
        List<String> out = new ArrayList<>();
        out.add("[INFO] Scanning for projects...");
        out.add("[INFO] Building " + projectDir.getFileName() + " (simulated Maven, Spring Boot " + project.bootVersion + ")");
        if (intent == Intent.DEPENDENCY_TREE) {
            project.dependencies.forEach((ga, version) -> out.add("[INFO] +- " + ga + ":jar:"
                    + (version == null ? project.bootVersion : version) + ":compile"));
            out.add("[INFO] BUILD SUCCESS");
            return result(projectDir, intent, 0, out, logFile);
        }
        List<String> compileErrors = new ArrayList<>(compile(project, projectDir.resolve("src/main/java")));
        if (intent != Intent.COMPILE) {
            compileErrors.addAll(compile(project, projectDir.resolve("src/test/java")));
        }
        compileErrors.addAll(extraOutput.apply(projectDir, intent));
        if (!compileErrors.isEmpty()) {
            out.add("[ERROR] COMPILATION ERROR : ");
            out.addAll(compileErrors);
            out.add("[INFO] BUILD FAILURE");
            return result(projectDir, intent, 1, out, logFile);
        }
        boolean runsTests = intent == Intent.TEST || intent == Intent.PACKAGE || intent == Intent.VERIFY;
        if (!runsTests) {
            out.add("[INFO] BUILD SUCCESS");
            return result(projectDir, intent, 0, out, logFile);
        }
        int run = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        boolean securityTestAutoConfig = project.boot4() && project.dependencies.containsKey("org.springframework.security:spring-security-test")
                && !project.dependencies.containsKey("org.springframework.boot:spring-boot-starter-security-test");
        for (TestMethod test : tests(projectDir.resolve("src/test/java"))) {
            run++;
            if (test.skipped) {
                skipped++;
                continue;
            }
            if (preExistingFailures.contains(test.id())) {
                failures.add("[ERROR]   " + test.id() + ":" + test.line + " Status expected:<200> but was:<503>");
            } else if (securityTestAutoConfig && test.withMockUser) {
                failures.add("[ERROR]   " + test.id() + ":" + test.line + " Status expected:<200> but was:<401>");
            }
        }
        out.add("[INFO] Results:");
        if (!failures.isEmpty()) {
            out.add("[ERROR] Failures: ");
            out.addAll(failures);
        }
        out.add((failures.isEmpty() ? "[INFO] " : "[ERROR] ") + "Tests run: " + run + ", Failures: " + failures.size()
                + ", Errors: 0, Skipped: " + skipped);
        out.add(failures.isEmpty() ? "[INFO] BUILD SUCCESS" : "[INFO] BUILD FAILURE");
        return result(projectDir, intent, failures.isEmpty() ? 0 : 1, out, logFile);
    }

    private BuildResult result(Path dir, Intent intent, int exit, List<String> out, Path logFile) {
        String output = String.join("\n", out);
        try {
            if (logFile != null) {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, output, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<BuildError> errors = exit == 0 ? List.of() : BuildErrorClassifier.parse(output, dir.toString());
        Outcome outcome = BuildErrorClassifier.outcome(exit, false, errors);
        return new BuildResult(intent, outcome, exit, 5, "maven", "simulated", jdk(), List.of("mvn", "-B", intent.name()),
                errors, BuildErrorClassifier.byCategory(errors), BuildErrorClassifier.testSummary(output),
                BuildErrorClassifier.failingTests(output), output, logFile, null);
    }

    private static String jdk() {
        return String.valueOf(Runtime.version().feature());
    }

    private static List<String> compile(Project project, Path sourceRoot) {
        List<String> errors = new ArrayList<>();
        for (Path file : javaFiles(sourceRoot)) {
            List<String> lines = lines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = IMPORT.matcher(lines.get(i));
                if (!m.find()) {
                    continue;
                }
                int line = i + 1;
                if (project.boot4() && CLASSES_MOVED_IN_BOOT4.contains(m.group(1))) {
                    String imported = m.group(1);
                    errors.add("[ERROR] " + file.toAbsolutePath() + ":[" + line + ",53] cannot find symbol");
                    errors.add("[ERROR]   symbol:   class " + imported.substring(imported.lastIndexOf('.') + 1));
                    errors.add("[ERROR]   location: package " + imported.substring(0, imported.lastIndexOf('.')));
                    continue;
                }
                missingPackage(project, m.group(1)).ifPresent(pkg -> errors.add("[ERROR] " + file.toAbsolutePath() + ":["
                        + line + ",8] package " + pkg + " does not exist"));
            }
        }
        return errors;
    }

    private static Optional<String> missingPackage(Project project, String imported) {
        List<String> gone = project.boot4() ? REMOVED_IN_BOOT4 : BOOT4_ONLY;
        for (String prefix : gone) {
            if (imported.startsWith(prefix + ".") || imported.equals(prefix)) {
                return Optional.of(packageOf(imported));
            }
        }
        if (project.boot4()) {
            for (Map.Entry<String, String> gated : MODULE_GATED.entrySet()) {
                if (imported.startsWith(gated.getKey() + ".")
                        && !project.dependencies.containsKey("org.springframework.boot:" + gated.getValue())) {
                    return Optional.of(packageOf(imported));
                }
            }
        }
        return Optional.empty();
    }

    private static String packageOf(String imported) {
        int last = imported.lastIndexOf('.');
        String tail = imported.substring(last + 1);
        return !tail.isEmpty() && Character.isUpperCase(tail.charAt(0)) ? imported.substring(0, last) : imported;
    }

    private record TestMethod(String className, String method, int line, boolean withMockUser, boolean skipped) {
        String id() {
            return className + "." + method;
        }
    }

    private static List<TestMethod> tests(Path testRoot) {
        List<TestMethod> tests = new ArrayList<>();
        Pattern method = Pattern.compile("^\\s*(?:public\\s+)?void\\s+(\\w+)\\s*\\(");
        for (Path file : javaFiles(testRoot)) {
            List<String> lines = lines(file);
            String text = String.join("\n", lines);
            String className = file.getFileName().toString().replace(".java", "");
            boolean classSkipped = text.contains("@Testcontainers(disabledWithoutDocker = true)") || text.contains("@Disabled\nclass")
                    || text.contains("@Disabled\npublic class");
            boolean pendingTest = false;
            boolean pendingMockUser = false;
            boolean pendingDisabled = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.equals("@Test")) {
                    pendingTest = true;
                } else if (line.startsWith("@WithMockUser")) {
                    pendingMockUser = true;
                } else if (line.startsWith("@Disabled")) {
                    pendingDisabled = true;
                } else {
                    Matcher m = method.matcher(lines.get(i));
                    if (m.find()) {
                        if (pendingTest) {
                            tests.add(new TestMethod(className, m.group(1), i + 1, pendingMockUser, classSkipped || pendingDisabled));
                        }
                        pendingTest = false;
                        pendingMockUser = false;
                        pendingDisabled = false;
                    }
                }
            }
        }
        return tests;
    }

    // ------------------------------------------------------------------ runtime

    @Override
    public RuntimeRun run(Path projectDir, ProbeSpec spec, String phase, Path logFile) {
        runtimeCalls.add(phase + ":" + projectDir.getFileName());
        Project project = Project.read(projectDir);
        String springdoc = project.dependencies.get("org.springdoc:springdoc-openapi-starter-webmvc-ui");
        if (project.boot4() && springdoc != null && major(springdoc) < 3) {
            return new RuntimeRun(phase, false, "APPLICATION FAILED TO START: springdoc-openapi " + springdoc
                    + " is not compatible with Spring Boot " + project.bootVersion, null, "simulated.jar", jdk(), null, null,
                    List.of(), "java.lang.NoSuchMethodError (simulated)");
        }
        List<ProbeObservation> probes = new ArrayList<>();
        for (ProbeRequest request : spec.requests()) {
            int status = request.path().contains("/999") ? 404 : 200;
            String body = "{\"path\":\"" + request.path() + "\"}";
            if (request.path().startsWith("/actuator/health")) {
                body = project.boot4() ? "{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}" : "{\"status\":\"UP\"}";
            } else if (status >= 400) {
                body = "{\"status\":404,\"timestamp\":\"" + (project.boot4() ? "2026-09-24T10:00:00.000Z" : "2026-09-24T10:00:00.000+00:00")
                        + "\"}";
            }
            probes.add(new ProbeObservation(request.name(), request.method(), request.path(), !request.noAuth(), 3, true,
                    status, "application/json", body.length(), Hashing.sha256(body), body, null));
        }
        return new RuntimeRun(phase, true, null, 1.5, "simulated.jar", jdk(), 200, 1500L, probes, "Started (simulated)");
    }

    // ------------------------------------------------------------------ project model

    private record Project(String bootVersion, Map<String, String> dependencies) {
        boolean boot4() {
            return bootVersion != null && major(bootVersion) >= 4;
        }

        static Project read(Path dir) {
            Path pom = dir.resolve("pom.xml");
            String text = Files.isRegularFile(pom) ? String.join("\n", lines(pom)) : "";
            Matcher parent = PARENT_VERSION.matcher(text);
            String boot = parent.find() ? parent.group(1).trim() : null;
            Map<String, String> deps = new LinkedHashMap<>();
            Matcher d = DEPENDENCY.matcher(text);
            while (d.find()) {
                deps.put(d.group(1).trim() + ":" + d.group(2).trim(), d.group(3) == null ? null : d.group(3).trim());
            }
            return new Project(boot, deps);
        }
    }

    private static int major(String version) {
        try {
            return Integer.parseInt(version.trim().split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
