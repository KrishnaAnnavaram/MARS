package com.mars.harness.tests.integration;

import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.engine.mutation.ProjectApplier;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Protection map C2 (reference {@code apply-migration.js}; spec §13.2): the developer's project is
 * written only by an explicit, decision-bound apply step, which refuses unsafe states.
 */
class ApplyToProjectIT {

    private static final String REPO_JAVA = "src/main/java/com/acme/inventory/repo/ProductRepository.java";

    private static void category(Runnable action, OutcomeCategory expected) {
        assertThatThrownBy(action::run).isInstanceOf(HarnessOutcomeException.class)
                .satisfies(e -> assertThat(((HarnessOutcomeException) e).category()).isEqualTo(expected));
    }

    @Test
    void applyIsExplicitDecisionBoundAndRefusesUnsafeStates(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        ChangeProposal fix = h.proposalFor(run, "INV-101");
        h.approve(run, fix);
        h.engine.resume(run, true);

        // while decisions are outstanding the verdict is NEEDS_HUMAN, and nothing may be applied
        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "No");
        h.engine.resume(run, false);
        assertThat(h.session(run).record.verdict).isEqualTo("NEEDS_HUMAN");
        Decision early = h.engine.decideApply(run, "APPROVED", "dev.lead", "owner", "Ship it");
        category(() -> h.engine.applyToProject(run, early.decisionId()), OutcomeCategory.POLICY_BLOCK);
        assertThat(TestHarness.read(h.repository.resolve(REPO_JAVA))).doesNotContain("queryForList(sql, name)");
        // an approval of something else is not an apply decision
        String proposalDecision = h.session(run).approvals.latestForProposal(fix.proposalId()).orElseThrow().decisionId();
        category(() -> h.engine.applyToProject(run, proposalDecision), OutcomeCategory.AUTHORIZATION_MISSING);
    }

    @Test
    void aResolvedRunIsAppliedExactlyAndOnlyWhenTheProjectHasNotMovedOn(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        ChangeProposal fix = h.proposalFor(run, "INV-101");
        h.approve(run, fix);
        for (String other : new String[]{"INV-102", "INV-103", "INV-104"}) {
            h.engine.decideProposal(run, h.proposalFor(run, other).proposalId(), "REJECTED", "dev.lead", "owner", "Not now");
        }
        h.engine.resume(run, false);
        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "No");
        h.engine.resume(run, false);
        assertThat(h.session(run).record.verdict).as(h.json(run, "reports/verdict.json").path("reasons").toString()).isEqualTo("PARTIAL");
        String untouched = TestHarness.read(h.repository.resolve("src/main/java/com/acme/inventory/web/ProductController.java"));

        // someone edits the project after the snapshot: applying would overwrite their work
        Path target = h.repository.resolve(REPO_JAVA);
        String original = Files.readString(target);
        Files.writeString(target, original + "\n// a colleague's change\n");
        Decision apply = h.engine.decideApply(run, "APPROVED", "dev.lead", "owner", "Apply the reviewed fix");
        category(() -> h.engine.applyToProject(run, apply.decisionId()), OutcomeCategory.STALE_PROPOSAL);
        Files.writeString(target, original);

        ProjectApplier.ApplyResult result = h.engine.applyToProject(run, apply.decisionId());
        assertThat(result.files()).containsExactly(REPO_JAVA);
        assertThat(Files.readString(target)).isEqualTo(TestHarness.read(h.session(run).layout.workspace().resolve(REPO_JAVA)))
                .contains("queryForList(sql, name)");
        assertThat(TestHarness.read(h.repository.resolve("src/main/java/com/acme/inventory/web/ProductController.java")))
                .isEqualTo(untouched);
        assertThat(h.session(run).lineage.entries()).anyMatch(e -> "APPLY_TO_PROJECT".equals(e.kind())
                && apply.decisionId().equals(e.decisionId()));
    }
}
