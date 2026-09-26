package com.mars.harness.tests.e2e;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.mars.harness.tests.e2e.RedMigrationDeclinedE2ETest.find;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scenario 10: no missing approval is treated as approval; no AI self-approval. Plus Scenario 2 (YELLOW declined). */
class HumanApprovalsE2ETest {

    private static final String REPO_JAVA = "src/main/java/com/acme/inventory/repo/ProductRepository.java";

    @Test
    void aMissingDecisionIsNeverApproval(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();

        // nothing runs past Gate A without a decision
        HarnessEngine.RunSummary summary = h.engine.resume(run, false);
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_EXECUTION_DECISION");

        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        String before = TestHarness.read(h.session(run).layout.workspace().resolve(REPO_JAVA));
        // resuming again without any Gate B decision changes nothing
        summary = h.engine.resume(run, false);
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_REMEDIATION_APPROVAL");
        assertThat(TestHarness.read(h.session(run).layout.workspace().resolve(REPO_JAVA))).isEqualTo(before);

        // --accept-pending ends the security phase but never applies an undecided proposal
        summary = h.engine.resume(run, true);
        RunSession session = h.session(run);
        assertThat(session.record.proposalChanges).isEmpty();
        assertThat(TestHarness.read(session.layout.workspace().resolve(REPO_JAVA))).isEqualTo(before);
        h.engine.decidePostSecurityMigration(run, "STOP", "dev.lead", "owner", "Stop here");
        h.engine.resume(run, false);
        var verdict = h.json(run, "reports/verdict.json");
        assertThat(find(verdict.path("items"), "item_id", h.finding(run, "INV-101").findingId()).path("status").asText())
                .isEqualTo("PENDING_APPROVAL");
        assertThat(verdict.path("outcome").asText()).isNotEqualTo("CLEARED");
    }

    @Test
    void machineActorsCannotDecide(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        for (String machine : List.of("harness", "llm", "agent", "copilot", "claude")) {
            assertThatThrownBy(() -> h.engine.decideExecution(run, "MIGRATE_FIRST", machine, "owner", "I recommend it"))
                    .as(machine).isInstanceOf(HarnessOutcomeException.class);
        }
        assertThat(h.session(run).approvals.all()).isEmpty();
        assertThat(h.session(run).record.machine.current.name()).isEqualTo("WAITING_FOR_EXECUTION_DECISION");
    }

    @Test
    void anLlmPatchIsOnlyAProposalAndNeedsAHuman(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        String findingId = h.finding(run, "INV-101").findingId();
        String current = TestHarness.read(h.session(run).layout.workspace().resolve(REPO_JAVA));
        String patched = current.replace("\"SELECT id, name, price FROM product WHERE name = '\" + name + \"'\"",
                "\"SELECT id, name, price FROM product WHERE name = ?\"").replace("queryForList(sql)", "queryForList(sql, name)");

        // an LLM patch without model/prompt/response provenance is not even registrable as applicable
        ChangeProposal unsourced = h.engine.submitPatch(run, List.of(findingId), Map.of(REPO_JAVA, patched), "LLM fix",
                ChangeProposal.ProviderType.LLM, new ChangeProposal.Provenance("llm-repair-agent", null, null, null, null,
                        null, null, null, List.of()));
        // an LLM cannot approve its own work
        assertThatThrownBy(() -> h.engine.decideProposal(run, unsourced.proposalId(), "APPROVED", "llm", "reviewer", "LGTM"))
                .isInstanceOf(HarnessOutcomeException.class);
        // a human approval does not launder missing provenance
        h.approve(run, unsourced);
        h.engine.resume(run, false);
        assertThat(h.session(run).record.proposalStatus.get(unsourced.proposalId())).isEqualTo("REJECTED");
        assertThat(TestHarness.read(h.session(run).layout.workspace().resolve(REPO_JAVA))).isEqualTo(current);

        ChangeProposal sourced = h.engine.submitPatch(run, List.of(findingId), Map.of(REPO_JAVA, patched), "LLM fix",
                ChangeProposal.ProviderType.LLM, new ChangeProposal.Provenance("llm-repair-agent", null, null, "model-x", "1",
                        "sha256:prompt", "sha256:context", "sha256:response", List.of()));
        assertThat(sourced.llmAuthored()).isTrue();
        h.engine.resume(run, false);
        assertThat(h.session(run).record.proposalStatus.get(sourced.proposalId())).isIn("PROPOSED", "AWAITING_APPROVAL");
        h.approve(run, sourced);
        h.engine.resume(run, false);
        assertThat(h.session(run).record.proposalStatus.get(sourced.proposalId())).as(h.session(run).record.notes + " " + h.session(run).record.machine.current).isIn("APPLIED", "VALIDATED");
    }

    @Test
    void aDecisionIsBoundToTheProposalItApproved(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        ChangeProposal fix = h.proposalFor(run, "INV-101");
        var decision = h.engine.decideProposal(run, fix.proposalId(), "APPROVED", "dev.lead", "owner", "Reviewed");
        assertThat(decision.proposalHash()).isEqualTo(fix.proposalHash());
        assertThat(decision.baselineSeal()).isEqualTo(h.session(run).baselineSeal());

        // tampering with the recorded decision (e.g. to widen what it approved) breaks its integrity hash
        Path file = h.session(run).layout.decisions().resolve(decision.decisionId() + ".json");
        ObjectNode node = (ObjectNode) KernelJson.read(file);
        node.put("rationale", "edited after the fact");
        Files.writeString(file, KernelJson.mapper().writeValueAsString(node));
        String before = TestHarness.read(h.session(run).layout.workspace().resolve(REPO_JAVA));
        h.engine.resume(run, false);
        RunSession session = h.session(run);
        assertThat(session.approvals.verifyIntegrity()).contains(decision.decisionId());
        assertThat(session.record.proposalStatus.get(fix.proposalId())).isNotIn("APPLIED", "VALIDATED");
        assertThat(TestHarness.read(session.layout.workspace().resolve(REPO_JAVA))).isEqualTo(before);
    }

    @Test
    void aDeferralIsReportedAsADeferralEvenForAStrategyOnlyProposal(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        ChangeProposal key = h.proposalFor(run, "INV-104");
        ChangeProposal research = h.proposalFor(run, "INV-103");
        assertThat(key.strategyOnly()).isTrue();
        h.engine.decideProposal(run, key.proposalId(), "DEFERRED", "dev.lead", "owner", "Needs a secrets-manager decision");
        h.engine.decideProposal(run, research.proposalId(), "REJECTED", "dev.lead", "owner", "Deserialization endpoint is being removed");
        h.engine.resume(run, true);
        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "No");
        h.engine.resume(run, false);
        assertThat(h.session(run).record.proposalStatus.get(key.proposalId())).isEqualTo("DEFERRED");
        var items = h.json(run, "reports/verdict.json").path("items");
        assertThat(find(items, "item_id", h.finding(run, "INV-104").findingId()).path("status").asText())
                .isEqualTo("DEFERRED_BY_DEVELOPER");
        assertThat(find(items, "item_id", h.finding(run, "INV-103").findingId()).path("status").asText())
                .isEqualTo("REJECTED_BY_DEVELOPER");
    }

    @Test
    void yellowMigrationDeclinedDoesNotExecute(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "migration/employee-demo-sb3");
        HarnessEngine.RunSummary summary = h.analyzeEmployee();
        String run = summary.runId();
        assertThat(summary.highlights()).containsEntry("migration_traffic_light", "YELLOW");
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Not this quarter");
        summary = h.engine.resume(run, true);
        // Gate A2 is offered because the assessment is not GREEN; the developer skips again
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION");
        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "Still not this quarter");
        h.engine.resume(run, false);
        assertThat(h.runDir(run).resolve("plans/migration-execution.json")).doesNotExist();
        assertThat(h.tools.calls).noneMatch(c -> c.directory().startsWith("round-"));
        assertThat(h.session(run).proposals.all()).noneMatch(p -> p.capability() == ChangeProposal.Capability.MIGRATION);
        assertThat(find(h.json(run, "reports/verdict.json").path("items"), "item_id", "MIGRATION").path("status").asText())
                .isEqualTo("MIGRATION_DECLINED");
    }
}
