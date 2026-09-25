package com.mars.harness.tests.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.6 and Scenario 7: the full reference-pack migration of the Spring Boot 3 -> 4 fixture
 * (baseline, sandbox, rounds, compiler repair, runtime comparison, report), compared with the
 * recorded reference run in {@code legacy-sources/spring-migration-reference}.
 */
public class FullMigrationE2ETest {

    private static final Path RECORDED = TestHarness.harnessRoot().resolve(
            "legacy-sources/spring-migration-reference/.github/.pipeline-context/version-migration/spring-boot-3-to-4");

    @Test
    void migrationOnlyRunsThePackRoundsToGreenWithBehaviourCompared(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "migration/employee-demo-sb3");
        h.employeeEnvironment();
        String original = TestHarness.treeHash(h.repository);

        HarnessEngine.RunSummary summary = h.analyzeEmployee();
        String run = summary.runId();
        assertThat(summary.highlights()).containsEntry("migration_traffic_light", "YELLOW");
        JsonNode baseline = h.json(run, "baseline/baseline-build.json");
        assertThat(baseline.path("outcome").asText()).isEqualTo("TESTS_FAILED");
        assertThat(baseline.path("failing_tests").toString()).contains("testActuatorHealth_Public");

        h.engine.decideExecution(run, "MIGRATION_ONLY", "dev.lead", "owner", "Boot 3.5 reaches end of OSS support");
        summary = h.engine.resume(run, false);

        // exactly as on the real toolchain: a class no pack rule covers stops the rounds for a human
        assertThat(summary.phase()).isEqualTo("NEEDS_HUMAN");
        assertThat(h.json(run, "plans/migration-execution.json").path("needs_human_reason").asText())
                .contains("AutoConfigureTestDatabase").contains("never guess an import path");
        String manual = resolveAutoConfigureTestDatabase(h.engine, run);
        summary = h.engine.resume(run, false);
        assertThat(h.session(run).record.proposalStatus.get(manual)).isIn("APPLIED", "VALIDATED");

        RunSession session = h.session(run);
        JsonNode execution = h.json(run, "plans/migration-execution.json");
        assertThat(execution.path("status").asText()).isEqualTo("GREEN");
        JsonNode rounds = execution.path("rounds");
        assertThat(rounds.get(0).path("label").asText()).isEqualTo("Pre-migration reference build");

        // round 1 applies exactly the pack's build-file section; no source file is touched there
        Set<String> firstRules = strings(rounds.get(1).path("applied_rules"));
        assertThat(firstRules).contains("SB4-PARENT", "SB4-JAVA", "SB4-STARTER-WEBMVC", "SB4-DOCKER-JRE");
        for (JsonNode proposalId : rounds.get(1).path("proposal_ids")) {
            ChangeProposal p = session.proposals.find(proposalId.asText()).orElseThrow();
            assertThat(p.edits()).allMatch(e -> !e.path().endsWith(".java"));
        }

        // compiler repair: every source rule is justified by an observed symptom (round N failed, round N+1 applied it)
        Set<String> applied = new LinkedHashSet<>();
        for (int i = 2; i < rounds.size(); i++) {
            Set<String> rules = strings(rounds.get(i).path("applied_rules"));
            rules.removeIf(r -> r.startsWith("("));
            if (!rules.isEmpty()) {
                assertThat(rounds.get(i - 1).path("outcome").asText()).isIn("compile-failed", "tests-failed");
            }
            applied.addAll(rules);
        }
        // parity with the recorded reference run: the same rules were needed
        Set<String> recorded = recordedRules();
        assertThat(applied).containsAll(Set.of("SB4-JACKSON3", "SB4-HEALTH", "SB4-MOCKITOBEAN", "SB4-WEBMVC-TEST",
                "SB4-DATAJPA-TEST", "SB4-SECURITY-TEST"));
        assertThat(recorded).isNotEmpty();
        // the recorded run finished with a package round that skipped round 0's pre-existing test failures
        JsonNode last = rounds.get(rounds.size() - 1);
        assertThat(last.path("intent").asText()).isEqualTo("package-skip-tests");
        assertThat(last.path("outcome").asText()).isEqualTo("passed");
        assertThat(recordedLastRound().path("outcome").asText()).isEqualTo("passed");

        // the migrated workspace is really Boot 4 / Jackson 3
        String pom = TestHarness.read(session.layout.workspace().resolve("pom.xml"));
        assertThat(pom).contains("<version>4.1.1</version>").contains("spring-boot-starter-webmvc")
                .contains("spring-boot-starter-security-test");
        String jackson = TestHarness.read(session.layout.workspace().resolve(
                "src/main/java/com/example/migrationdemo/config/JacksonConfig.java"));
        assertThat(jackson).contains("tools.jackson").contains("JsonMapperBuilderCustomizer")
                .doesNotContain("com.fasterxml.jackson.databind");

        // runtime comparison: the health document differs and is explained by pack §13; nothing unexplained
        JsonNode behaviour = execution.path("behaviour");
        assertThat(behaviour.path("final_started").asBoolean()).isTrue();
        for (JsonNode probe : behaviour.path("probes")) {
            if (!"identical".equals(probe.path("verdict").asText())) {
                assertThat(probe.path("note").asText()).as(probe.toString()).contains("§13");
            }
        }
        JsonNode validation = h.json(run, "validation/migration-validation.json");
        assertThat(validation.toString()).contains("EQUIVALENT_WITH_EXPLAINED_DIFFS");

        // graph diff after every applied batch, identity kept for every migrated file
        try (var files = Files.list(h.runDir(run).resolve("graph"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("graph-diff-")).count()).isPositive();
        }
        String jacksonId = session.fileRegistry.byPath("src/main/java/com/example/migrationdemo/config/JacksonConfig.java")
                .orElseThrow().getFileId();
        assertThat(session.lineage.entries()).anyMatch(e -> jacksonId.equals(e.fileId()) && "CHANGE".equals(e.kind()));

        // final evidence and report
        assertThat(summary.phase()).isIn("COMPLETE", "NEEDS_HUMAN");
        assertThat(h.runDir(run).resolve("reports/migration/migration_spring-boot-3-to-4.md")).exists();
        String report = TestHarness.read(h.runDir(run).resolve("reports/final-report.md"));
        assertThat(report).contains("MIGRATION_ONLY").contains("SB4-JACKSON3");
        assertThat(TestHarness.treeHash(h.repository)).isEqualTo(original);
    }

    @Test
    void anErrorNoRuleCoversStopsForAHuman(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "migration/employee-demo-sb3");
        h.tools.extraOutput = (dir, intent) -> dir.getFileName().toString().startsWith("round")
                && TestHarness.read(dir.resolve("pom.xml")).contains("<version>4.1.1</version>")
                ? java.util.List.of("[ERROR] " + dir.resolve("src/main/java/com/example/migrationdemo/service/EmployeeService.java")
                + ":[12,8] package org.example.legacy.audit does not exist") : java.util.List.of();
        String run = h.analyzeEmployee().runId();
        h.engine.decideExecution(run, "MIGRATION_ONLY", "dev.lead", "owner", "Upgrade");
        HarnessEngine.RunSummary summary = h.engine.resume(run, false);
        assertThat(summary.phase()).isEqualTo("NEEDS_HUMAN");
        JsonNode execution = h.json(run, "plans/migration-execution.json");
        assertThat(execution.path("status").asText()).isEqualTo("NEEDS_HUMAN");
        assertThat(execution.path("needs_human_reason").asText()).contains("never guess an import path");
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Set<String> recordedRules() throws Exception {
        Set<String> labels = new LinkedHashSet<>();
        try (var files = Files.list(RECORDED.resolve("rounds"))) {
            for (Path f : files.sorted().toList()) {
                labels.add(KernelJson.read(f).path("label").asText());
            }
        }
        return labels;
    }

    private static JsonNode recordedLastRound() throws Exception {
        try (var files = Files.list(RECORDED.resolve("rounds"))) {
            return KernelJson.read(files.sorted().reduce((a, b) -> b).orElseThrow());
        }
    }

    static final String IT_TEST = "src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java";

    /**
     * The step the reference workflow leaves to a human: the error names a class no pack rule covers.
     * The new location was resolved from the actual Boot 4.1.1 dependency (spring-boot-jdbc-test), not
     * guessed; the fix is submitted as a patch, approved, and the rounds resume.
     */
    public static String resolveAutoConfigureTestDatabase(com.mars.harness.kernel.engine.HarnessEngine engine, String run) {
        var session = engine.load(run);
        String text = com.mars.harness.tests.support.TestHarness.read(session.layout.workspace().resolve(IT_TEST));
        String fixed = text.replace("import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;",
                "import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;");
        var patch = engine.submitPatch(run, java.util.List.of(), java.util.Map.of(IT_TEST, fixed),
                "AutoConfigureTestDatabase moved to spring-boot-jdbc-test (resolved from the 4.1.1 jar)",
                com.mars.harness.kernel.core.change.ChangeProposal.ProviderType.MANUAL_PATCH,
                new com.mars.harness.kernel.core.change.ChangeProposal.Provenance("manual", null, null, null, null, null, null,
                        null, java.util.List.of()));
        engine.decideProposal(run, patch.proposalId(), "APPROVED", "dev.lead", "owner", "Checked against spring-boot-jdbc-test 4.1.1");
        return patch.proposalId();
    }
}
