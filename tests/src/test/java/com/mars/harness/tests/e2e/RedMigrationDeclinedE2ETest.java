package com.mars.harness.tests.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.10 (mandatory) and Scenario 3: the assessment is RED for a specific objective, the
 * developer declines migration; fix A is independently executable, fix B requires a newer
 * platform.
 */
class RedMigrationDeclinedE2ETest {

    @Test
    void redDeclinedRunsIndependentFixAndLabelsPlatformBlockedWork(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String originalBefore = TestHarness.treeHash(h.repository);

        HarnessEngine.RunSummary summary = h.analyzeComposite(true);
        String run = summary.runId();
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_EXECUTION_DECISION");
        assertThat(summary.highlights()).containsEntry("migration_traffic_light", "RED")
                .containsEntry("recommended_strategy", "MIGRATE_FIRST");
        JsonNode assessment = h.json(run, "discovery/migration/migration-assessment.json");
        assertThat(assessment.path("traffic_light_rationale").asText())
                .contains("FIXTURE-ADV-SPRINGDOC-0001").contains("Spring Boot 4.0");

        Finding fixB = h.finding(run, "FIXTURE-ADV-SPRINGDOC-0001");
        assertThat(fixB.platformRequirement()).isNotNull();
        assertThat(fixB.platformRequirement().requiresPlatformMinimum()).isEqualTo("4.0");

        // the developer declines the RED recommendation
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Migration is out of scope this quarter");
        summary = h.engine.resume(run, false);
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_REMEDIATION_APPROVAL");

        // migration does not run
        assertThat(h.runDir(run).resolve("plans/migration-plan.json")).doesNotExist();
        assertThat(h.runDir(run).resolve("plans/migration-execution.json")).doesNotExist();
        RunSession session = h.session(run);
        assertThat(session.proposals.all()).noneMatch(p -> p.capability() == ChangeProposal.Capability.MIGRATION);

        // fix B is labelled blocked by platform, and no proposal exists for it
        assertThat(h.proposalsFor(run, fixB.findingId())).isEmpty();
        JsonNode plans = h.json(run, "plans/remediation-plans.json");
        JsonNode planB = find(plans, "finding_id", fixB.findingId());
        assertThat(planB.path("blocked_by_platform").asBoolean()).isTrue();

        // fix A (catalog, CWE-89) is independently executable
        ChangeProposal fixA = h.proposalFor(run, "INV-101");
        assertThat(fixA.strategyOnly()).isFalse();
        h.approve(run, fixA);
        summary = h.engine.resume(run, true);

        // after security the RED migration is re-assessed and Gate A2 is offered
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION");
        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "Still out of scope");
        summary = h.engine.resume(run, false);

        session = h.session(run);
        assertThat(session.record.proposalStatus.get(fixA.proposalId())).isIn("APPLIED", "VALIDATED");
        assertThat(session.record.migrationExecuted).isFalse();
        JsonNode verdict = h.json(run, "reports/verdict.json");
        JsonNode itemB = find(verdict.path("items"), "item_id", fixB.findingId());
        assertThat(itemB.path("status").asText()).isEqualTo("BLOCKED_BY_PLATFORM");
        JsonNode itemA = find(verdict.path("items"), "item_id", h.finding(run, "INV-101").findingId());
        assertThat(itemA.path("status").asText()).isEqualTo("FIXED");
        assertThat(verdict.path("outcome").asText()).isNotEqualTo("CLEARED");

        // the final report is truthful about both
        String report = TestHarness.read(h.runDir(run).resolve("reports/final-report.md"));
        assertThat(report).contains("BLOCKED_BY_PLATFORM").contains("SECURITY_ONLY").contains("FIXTURE-ADV-SPRINGDOC-0001");

        // the customer's repository was never touched
        assertThat(TestHarness.treeHash(h.repository)).isEqualTo(originalBefore);
        assertThat(Files.exists(h.repository.resolve("reports"))).isFalse();
    }

    static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.path(field).asText())) {
                return n;
            }
        }
        throw new AssertionError("no element with " + field + "=" + value + " in " + array);
    }
}
