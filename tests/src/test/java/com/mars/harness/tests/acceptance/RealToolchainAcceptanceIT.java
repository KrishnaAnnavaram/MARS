package com.mars.harness.tests.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.cli.HarnessFactory;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance on the REAL toolchain: the production composition root (real Maven through
 * {@code MavenBuildRunner}, real {@code java -jar} runtimes probed over loopback), no doubles.
 * Excluded from the default build (it downloads Spring Boot 4 and takes minutes); run with
 * {@code mvn -pl tests test -Dharness.excludedGroups= -Dtest=RealToolchainAcceptanceIT}.
 */
@Tag("real-toolchain")
class RealToolchainAcceptanceIT {

    private static HarnessEngine engine(Path runs) {
        EngineConfig config = HarnessFactory.config(TestHarness.harnessRoot(), runs, null, LocalDate.of(2026, 9, 24), false, false);
        Assumptions.assumeTrue(config.build().availability(TestHarness.harnessRoot()).available(), "Maven is not available");
        return new HarnessEngine(config);
    }

    @Test
    void employeeServiceMigratesFromBoot35To411WithBehaviourCompared(@TempDir Path temp) {
        Path repo = temp.resolve("repo");
        TestHarness.copyTree(TestHarness.fixture("migration/employee-demo-sb3"), repo);
        String original = TestHarness.treeHash(repo);
        HarnessEngine engine = engine(temp.resolve("runs"));

        HarnessEngine.RunSummary summary = engine.analyze(new HarnessEngine.AnalyzeRequest(repo, List.of(), Map.of(),
                repo.resolve("probes.json"), false));
        String run = summary.runId();
        RunSession session = engine.load(run);
        JsonNode baseline = KernelJson.read(session.layout.area("baseline").resolve("baseline-build.json"));
        System.out.println("[acceptance] round 0: " + baseline.path("outcome").asText() + " tests " + baseline.path("tests")
                + " failing " + baseline.path("failing_tests"));
        assertThat(baseline.path("outcome").asText()).isIn("PASSED", "TESTS_FAILED");
        JsonNode runtime = KernelJson.read(session.layout.area("baseline").resolve("runtime-baseline.json"));
        assertThat(runtime.path("started").asBoolean()).as("the Boot 3.5 application starts for the baseline probes").isTrue();

        engine.decideExecution(run, "MIGRATION_ONLY", "acceptance.reviewer", "owner", "Real-toolchain acceptance");
        summary = engine.resume(run, false);
        if ("NEEDS_HUMAN".equals(summary.phase())) {
            String reason = KernelJson.read(engine.load(run).layout.area("plans").resolve("migration-execution.json"))
                    .path("needs_human_reason").asText();
            System.out.println("[acceptance] stopped for a human: " + reason);
            assertThat(reason).contains("AutoConfigureTestDatabase");
            com.mars.harness.tests.e2e.FullMigrationE2ETest.resolveAutoConfigureTestDatabase(engine, run);
            summary = engine.resume(run, false);
        }

        JsonNode execution = KernelJson.read(engine.load(run).layout.area("plans").resolve("migration-execution.json"));
        for (JsonNode round : execution.path("rounds")) {
            System.out.println("[acceptance] round " + round.path("round").asInt() + " " + round.path("intent").asText() + " -> "
                    + round.path("outcome").asText() + " rules " + round.path("applied_rules") + " errors "
                    + round.path("errors_by_category"));
        }
        System.out.println("[acceptance] status " + execution.path("status").asText() + ": "
                + execution.path("needs_human_reason").asText(""));
        JsonNode lastRound = execution.path("rounds").get(execution.path("rounds").size() - 1);
        lastRound.path("errors").forEach(e -> System.out.println("[acceptance]   " + e.path("file").asText("") + ":"
                + e.path("line").asText("") + " [" + e.path("category").asText() + "] " + e.path("message").asText()
                + " | " + e.path("raw").asText("")));
        System.out.println("[acceptance] behaviour: " + execution.path("behaviour").path("overall").asText());
        assertThat(execution.path("status").asText()).isEqualTo("GREEN");
        assertThat(execution.path("behaviour").path("final_started").asBoolean()).isTrue();
        for (JsonNode probe : execution.path("behaviour").path("probes")) {
            if (!"identical".equals(probe.path("verdict").asText())) {
                assertThat(probe.path("note").asText()).as("unexplained behaviour change: " + probe).isNotBlank();
            }
        }
        String pom = TestHarness.read(engine.load(run).layout.workspace().resolve("pom.xml"));
        assertThat(pom).contains("<version>4.1.1</version>");
        JsonNode verdict = KernelJson.read(engine.load(run).layout.area("reports").resolve("verdict.json"));
        System.out.println("[acceptance] verdict " + verdict.path("outcome").asText() + " " + verdict.path("reasons"));
        assertThat(summary.phase()).isIn("COMPLETE", "NEEDS_HUMAN");
        assertThat(TestHarness.treeHash(repo)).isEqualTo(original);
    }

    @Test
    void compositeServiceMigratesThenRemediates(@TempDir Path temp) {
        Path repo = temp.resolve("repo");
        TestHarness.copyTree(TestHarness.fixture("composite/inventory-service"), repo);
        HarnessEngine engine = engine(temp.resolve("runs"));
        Path inputs = TestHarness.fixture("composite/inputs");
        String run = engine.analyze(new HarnessEngine.AnalyzeRequest(repo, List.of(inputs.resolve("issue-register.xlsx"),
                inputs.resolve("inventory.advisories.json")), Map.of("INV-103", inputs.resolve("INV-103.analysis.json")),
                repo.resolve("probes.json"), false)).runId();
        engine.decideExecution(run, "MIGRATE_FIRST", "acceptance.reviewer", "owner", "Real-toolchain acceptance");
        engine.resume(run, false);
        RunSession session = engine.load(run);
        JsonNode execution = KernelJson.read(session.layout.area("plans").resolve("migration-execution.json"));
        System.out.println("[acceptance] composite migration " + execution.path("status").asText() + " in "
                + execution.path("rounds").size() + " round(s); behaviour " + execution.path("behaviour").path("overall").asText());
        assertThat(execution.path("status").asText()).isEqualTo("GREEN");

        String sqli = session.findings.stream().filter(f -> "INV-101".equals(f.sourceFindingId())).findFirst().orElseThrow()
                .findingId();
        var fix = session.proposals.all().stream().filter(p -> p.findingRefs().contains(sqli)).findFirst().orElseThrow();
        engine.decideProposal(run, fix.proposalId(), "APPROVED", "acceptance.reviewer", "owner", "Reviewed");
        engine.resume(run, true);
        session = engine.load(run);
        System.out.println("[acceptance] composite verification " + session.record.verification);
        assertThat(session.record.verification.values()).contains("Cleared");
        JsonNode verdict = KernelJson.read(session.layout.area("reports").resolve("verdict.json"));
        System.out.println("[acceptance] composite verdict " + verdict.path("outcome").asText() + " " + verdict.path("reasons"));
    }
}
