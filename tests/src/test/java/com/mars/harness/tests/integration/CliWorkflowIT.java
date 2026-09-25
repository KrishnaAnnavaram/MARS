package com.mars.harness.tests.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.cli.HarnessCli;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §34: the developer's operational flow through the real CLI (the production composition
 * root), end to end, without a chat interface. Builds are skipped so the flow is hermetic; the
 * build-backed flows are covered by the E2E and real-toolchain suites.
 */
class CliWorkflowIT {

    private record Result(int exit, String out, String err) {
    }

    private Path runs;
    private Path repo;

    private Result cli(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--harness-root", TestHarness.harnessRoot().toString(), "--runs-root", runs.toString(),
                "--today", "2026-09-24", "--maven-offline"));
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
            int exit = HarnessCli.run(all.toArray(String[]::new));
            return new Result(exit, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void theDeveloperFlowWorksFromTheCommandLine(@TempDir Path temp) throws Exception {
        runs = temp.resolve("runs");
        repo = temp.resolve("repo");
        TestHarness.copyTree(TestHarness.fixture("composite/inventory-service"), repo);
        Path inputs = TestHarness.fixture("composite/inputs");

        Result analyze = cli("analyze", repo.toString(), "--skip-build", "--findings", inputs.resolve("issue-register.xlsx").toString(),
                "--findings", inputs.resolve("inventory.advisories.json").toString(), "--research",
                "INV-103=" + inputs.resolve("INV-103.analysis.json"), "--json");
        assertThat(analyze.exit()).as(analyze.err()).isZero();
        JsonNode summary = KernelJson.parse(analyze.out());
        String run = summary.path("run_id").asText();
        assertThat(summary.path("phase").asText()).isEqualTo("WAITING_FOR_EXECUTION_DECISION");
        assertThat(summary.path("next_actions").toString()).contains("harness decide execution");

        assertThat(cli("status", "--run", run).out()).contains("Human Gate A");
        assertThat(cli("migration-assessment", "--run", run).out()).contains("\"traffic_light\" : \"RED\"");
        String findings = cli("findings", "--run", run).out();
        assertThat(findings).contains("INV-101").contains("CWE-89").contains("requires spring-boot 4.0+");

        // a machine identity is refused with the REFUSAL exit code; a missing rationale is refused
        Result machine = cli("decide", "execution", "--run", run, "--strategy", "SECURITY_ONLY", "--actor", "copilot",
                "--role", "owner", "--rationale", "auto");
        assertThat(machine.exit()).isEqualTo(OutcomeCategory.REFUSAL.exitCode());
        assertThat(machine.err()).contains("reserved machine identity");

        assertThat(cli("decide", "execution", "--run", run, "--strategy", "SECURITY_ONLY", "--actor", "dev.lead", "--role",
                "owner", "--rationale", "Security this sprint").exit()).isZero();
        assertThat(cli("resume", "--run", run).out()).contains("WAITING_FOR_REMEDIATION_APPROVAL");
        String proposals = cli("proposals", "--run", run, "--json").out();
        JsonNode list = KernelJson.parse(proposals);
        String sqlProposal = null;
        for (JsonNode p : list) {
            if (p.toString().contains("CWE-89")) {
                sqlProposal = p.path("proposal_id").asText();
            }
        }
        assertThat(sqlProposal).as(proposals).isNotNull();

        assertThat(cli("approve", "remediation", "--run", run, "--proposal", sqlProposal, "--verdict", "REJECTED", "--actor",
                "dev.lead", "--role", "owner", "--rationale", "Will fix with the ORM migration").exit()).isZero();
        assertThat(cli("resume", "--run", run, "--accept-pending").out()).contains("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION");
        assertThat(cli("decide", "migration", "--run", run, "--decision", "SKIP", "--actor", "dev.lead", "--role", "owner",
                "--rationale", "Not this quarter").exit()).isZero();
        Result finished = cli("resume", "--run", run);
        // three proposals were left undecided: the run ends asking for them, never treating silence as approval
        assertThat(finished.out()).contains("phase    : NEEDS_HUMAN").contains("3 decision(s) outstanding");

        String report = cli("report", "--run", run).out();
        assertThat(report).contains("REJECTED_BY_DEVELOPER").contains("BLOCKED_BY_PLATFORM").contains("```mermaid");
        String fileId = KernelJson.read(runs.resolve(run).resolve("findings/findings.json")).get(0).path("file_id").asText();
        assertThat(cli("lineage", "--run", run, fileId).out()).contains(fileId);
        assertThat(cli("verify", "--run", run).exit()).isZero();

        // tampering with a recorded decision is detected by verify
        Path decisions = runs.resolve(run).resolve("decisions");
        Path decision;
        try (var files = Files.list(decisions)) {
            decision = files.filter(p -> p.getFileName().toString().matches("DEC-.*\\.json")).findFirst().orElseThrow();
        }
        ObjectNode node = (ObjectNode) KernelJson.read(decision);
        node.put("selected", "MIGRATE_FIRST");
        Files.writeString(decision, KernelJson.pretty(node));
        Result verify = cli("verify", "--run", run);
        assertThat(verify.exit()).isEqualTo(3);
        assertThat(verify.out()).contains(node.path("decision_id").asText());
        // the customer's repository was never written
        assertThat(Files.exists(repo.resolve("reports"))).isFalse();
    }
}
