package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.adapters.maven.BuildErrorClassifier;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.ports.build.BuildPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.4, C5/C6: the migration reference's {@code parseBuildErrors}, {@code buildOutcome},
 * {@code summariseErrors} and the renderer's {@code testTotals} (all run unchanged) against
 * {@link BuildErrorClassifier} on every recorded round log of the {@code spring-boot-3-to-4}
 * session and on synthetic Maven/javac outputs (missing package with the build root stripped,
 * cannot find symbol with its {@code symbol:}/{@code location:} continuation lines, duplicates,
 * test failures, dependency resolution, Java release, environment, success). Same errors in the
 * same order with the same file, line, column, message, category and folded detail; same outcome;
 * same per-category counts, labels and hints; same last "Tests run" summary.
 *
 * <p>{@code testTotals} only reads rounds whose intent runs tests; the port leaves that choice to
 * its caller, so the summary is compared for those rounds and the legacy result is asserted empty
 * for the others.
 */
class BuildErrorClassifierParityTest {

    private static final String ROUNDS = "spring-migration-reference/.github/.pipeline-context/version-migration/spring-boot-3-to-4/rounds";
    private static final Set<String> TEST_INTENTS = Set.of("test", "package", "verify");

    @Test
    void recordedRoundLogs() {
        JsonNode legacy = LegacyNode.run("build-errors.js", "rounds", LegacyNode.legacy(ROUNDS).toString());
        assertThat(legacy.size()).isEqualTo(8);
        for (JsonNode c : legacy) {
            assertThat(c.path("outcome").asText()).as(c.path("name").asText()).isEqualTo(c.path("recorded").path("outcome").asText());
            assertSame(c, null, c.path("recorded").path("intent").asText());
        }
    }

    @Test
    void syntheticMavenAndJavacOutputs(@TempDir Path dir) throws Exception {
        String root = "/home/ci/work/demo";
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(build("missing-package", 1, root, """
                [INFO] --- compiler:3.13.0:compile (default-compile) @ demo ---
                [INFO] -------------------------------------------------------------
                [ERROR] COMPILATION ERROR :\s
                [INFO] -------------------------------------------------------------
                [ERROR] /home/ci/work/demo/src/main/java/com/acme/Health.java:[3,47] package org.springframework.boot.actuate.health does not exist
                [ERROR] /home/ci/work/demo/src/main/java/com/acme/Health.java:[9,39] cannot find symbol
                  symbol: class HealthIndicator
                [INFO] 2 errors
                [INFO] BUILD FAILURE
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project demo: Compilation failure: Compilation failure:\s
                [ERROR] /home/ci/work/demo/src/main/java/com/acme/Health.java:[3,47] package org.springframework.boot.actuate.health does not exist
                [ERROR] /home/ci/work/demo/src/main/java/com/acme/Health.java:[9,39] cannot find symbol
                [ERROR]   symbol:   class HealthIndicator
                [ERROR]   location: class com.acme.Health
                [ERROR] -> [Help 1]
                [ERROR]\s
                [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
                [ERROR] Re-run Maven using the -X switch to enable full debug logging.
                [ERROR] For more information about the errors and possible solutions, please read the following articles:
                [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
                """));
        cases.add(build("javac-symbols", 1, "D:/work/demo", """
                /D:/work/demo/src/main/java/com/acme/JacksonConfig.java:22: error: cannot find symbol
                [ERROR] /D:/work/demo/src/main/java/com/acme/JacksonConfig.java:[22,12] cannot find symbol
                [ERROR]   symbol:   class ObjectMapper
                [ERROR]   location: class com.acme.JacksonConfig
                [ERROR] /D:/work/demo/src/main/java/com/acme/Bean.java:[5,1] cannot find symbol
                [ERROR]   symbol: class MockBean
                [ERROR] /D:/work/demo/src/main/java/com/acme/Sec.java:[14,8] com.acme.Sec is not abstract and does not override abstract method configure(Object) in Base
                [ERROR] /D:/work/demo/src/main/java/com/acme/Sec.java:[30,20] incompatible types: String cannot be converted to Duration
                [ERROR] /D:/work/demo/src/main/java/com/acme/Sec.java:[31,9] no suitable method found for of(int)
                [ERROR] /D:/work/demo/src/main/java/com/acme/Sec.java:[40,5] method does not override or implement a method from a supertype
                [ERROR] /D:/work/demo/src/main/java/com/acme/Sec.java:[44,17] getX() has private access in com.acme.Base
                [ERROR]   required: int
                [ERROR]   found:    no arguments
                [ERROR]   reason: actual and formal argument lists differ in length
                """));
        cases.add(build("tests-failed", 1, null, """
                [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.2 s -- in com.acme.ServiceTest
                [ERROR] Tests run: 4, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 1.1 s <<< FAILURE! -- in com.acme.ControllerTest
                [ERROR] com.acme.ControllerTest.health -- Time elapsed: 0.1 s <<< FAILURE!
                java.lang.AssertionError: Status expected:<200> but was:<404>
                [INFO] Results:
                [ERROR] Failures:\s
                [ERROR]   ControllerTest.health:45 Status expected:<200> but was:<404>
                [ERROR]   ControllerTest.create:61 Status expected:<201> but was:<401>
                [ERROR] Errors:\s
                [ERROR]   RepositoryIT � IllegalState Could not find a valid Docker environment. Please see logs
                [ERROR] Tests run: 7, Failures: 2, Errors: 1, Skipped: 1
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test (default-test) on project demo: There are test failures.
                [ERROR] See /home/ci/work/demo/target/surefire-reports for the individual test results.
                [ERROR] -> [Help 1]
                """));
        cases.add(build("dependency-resolution", 1, null, """
                [ERROR] Failed to execute goal on project demo: Could not resolve dependencies for project com.acme:demo:jar:1.0: \
                The following artifacts could not be resolved: org.springframework.boot:spring-boot-starter-jackson:jar:4.0.0 (absent): \
                Could not find artifact org.springframework.boot:spring-boot-starter-jackson:jar:4.0.0 in central -> [Help 1]
                [ERROR] Failed to execute goal on project demo: Could not resolve dependencies for project com.acme:demo:jar:1.0 -> [Help 1]
                """));
        cases.add(build("java-release", 1, null, """
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project demo: Fatal error compiling: error: invalid target release: 21 -> [Help 1]
                [ERROR] Plugin org.acme:old-plugin:1.0 or one of its dependencies could not be resolved
                """));
        cases.add(build("success", 0, null, """
                [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 2
                [INFO] BUILD SUCCESS
                """));
        cases.add(build("windows-crlf", 1, "C:/build/ws", "[ERROR] /C:/build/ws/src/main/java/A.java:[1,8] package com.fasterxml.jackson.databind does not exist\r\n"
                + "[ERROR] Tests run: 2, Failures: 0, Errors: 1\r\n[ERROR] connection refused\r\n"));
        if (File.separatorChar == '\\') {
            cases.add(build("windows-backslash-root", 1, "C:\\build\\ws",
                    "[ERROR] C:\\build\\ws\\src\\main\\java\\A.java:[7,3] cannot find symbol\n[ERROR]   symbol:   method foo()\n"));
        }

        Path file = dir.resolve("cases.json");
        Files.writeString(file, KernelJson.mapper().writeValueAsString(cases));
        JsonNode legacy = LegacyNode.run("build-errors.js", "cases", file.toString());
        assertThat(legacy.size()).isEqualTo(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            assertSame(legacy.get(i), (String) cases.get(i).get("root"), "test");
        }
    }

    private static Map<String, Object> build(String name, int status, String root, String log) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("status", status);
        c.put("root", root);
        c.put("log", log);
        return c;
    }

    private static void assertSame(JsonNode c, String root, String intent) {
        String name = c.path("name").asText();
        String log = c.path("log").asText();
        JsonNode expected = c.path("errors");
        List<BuildPort.BuildError> errors = BuildErrorClassifier.parse(log, root);

        assertThat(errors).as(name).hasSameSizeAs(expected);
        for (int i = 0; i < errors.size(); i++) {
            BuildPort.BuildError a = errors.get(i);
            JsonNode e = expected.get(i);
            String as = name + " #" + i;
            assertThat(a.file()).as(as).isEqualTo(LegacyNode.text(e.path("file")));
            assertThat(a.line()).as(as).isEqualTo(e.path("line").isNull() ? null : e.path("line").asInt());
            assertThat(a.column()).as(as).isEqualTo(e.path("column").isNull() ? null : e.path("column").asInt());
            assertThat(a.message()).as(as).isEqualTo(e.path("message").asText());
            assertThat(a.category()).as(as).isEqualTo(e.path("category").asText());
            assertThat(a.raw()).as(as).isEqualTo(String.join(" | ", LegacyNode.strings(e.path("detail"))));
        }

        assertThat(BuildErrorClassifier.outcome(c.path("status").asInt(), false, errors).legacyId()).as(name).isEqualTo(c.path("outcome").asText());

        Map<String, Integer> byCategory = BuildErrorClassifier.byCategory(errors);
        Map<String, Integer> legacyCounts = new LinkedHashMap<>();
        for (JsonNode cat : c.path("summary").path("byCategory")) {
            legacyCounts.put(cat.path("id").asText(), cat.path("count").asInt());
            BuildErrorClassifier.Category meta = BuildErrorClassifier.meta(cat.path("id").asText());
            assertThat(meta.label()).as(name).isEqualTo(cat.path("label").asText());
            assertThat(meta.hint()).as(name).isEqualTo(cat.path("hint").asText());
        }
        assertThat(byCategory).as(name).isEqualTo(legacyCounts);

        JsonNode totals = c.path("totals");
        if (TEST_INTENTS.contains(intent)) {
            BuildPort.TestSummary summary = BuildErrorClassifier.testSummary(log);
            if (totals.isNull()) {
                assertThat(summary).as(name).isNull();
            } else {
                assertThat(summary).as(name).isEqualTo(new BuildPort.TestSummary(totals.path("run").asInt(),
                        totals.path("failures").asInt(), totals.path("errors").asInt(), totals.path("skipped").asInt()));
            }
        } else {
            assertThat(totals.isNull()).as(name).isTrue();
        }
    }
}
