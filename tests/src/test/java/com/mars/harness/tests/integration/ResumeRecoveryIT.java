package com.mars.harness.tests.integration;

import com.bootshift.core.ledger.ChangeLedger;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec §32.14: interrupt a run after a mutation and its checkpoint, resume it in a fresh engine
 * (a new process), and verify state, identity and ledger are restored and the change is not
 * applied twice.
 */
class ResumeRecoveryIT {

    private static final String REPO_JAVA = "src/main/java/com/acme/inventory/repo/ProductRepository.java";

    @Test
    void crashAfterMutationResumesWithoutDuplicateChanges(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        ChangeProposal fix = h.proposalFor(run, "INV-101");
        h.approve(run, fix);

        // the process dies after the gateway applied and checkpointed the fix, during verification's build
        h.tools.crashOnNextBuild = new IllegalStateException("simulated crash: JVM killed during the verify build");
        assertThatThrownBy(() -> h.engine.resume(run, false)).hasMessageContaining("simulated crash");

        RunSession crashed = h.session(run);
        assertThat(crashed.record.proposalStatus.get(fix.proposalId())).isEqualTo("APPLIED");
        int changesAtCrash = crashed.record.proposalChanges.get(fix.proposalId()).size();
        long appliedAtCrash = ledgerStatus(crashed, "APPLIED");
        assertThat(appliedAtCrash).isEqualTo(changesAtCrash);
        assertThat(ledgerStatus(crashed, "VALIDATED")).isZero();
        String workspaceAtCrash = TestHarness.treeHash(crashed.layout.workspace());
        String identityAtCrash = crashed.identity.contentHash();
        String anchor = h.finding(run, "INV-101").statementId();
        assertThat(crashed.identity.statements.get(anchor).currentText).contains("queryForList(sql, name)");

        // a fresh process resumes
        TestHarness restarted = h.restart();
        restarted.engine.resume(run, true);
        RunSession resumed = restarted.session(run);

        // state restored and completed: the applied fix was verified, not re-applied
        assertThat(resumed.record.proposalChanges.get(fix.proposalId())).hasSize(changesAtCrash);
        // the ledger gained exactly the verification's VALIDATED event, and no second APPLIED event
        assertThat(ledgerStatus(resumed, "APPLIED")).isEqualTo(appliedAtCrash);
        assertThat(ledgerStatus(resumed, "VALIDATED")).as(securityReports(resumed)).isEqualTo(changesAtCrash);
        assertThat(ChangeLedger.verify(resumed.layout.ledgerFile(), resumed.layout.ledgerHead()).valid()).isTrue();
        assertThat(LineageLedger.verify(resumed.layout.lineageLedger())).isEmpty();
        assertThat(resumed.lineage.entries().stream().filter(e -> "CHANGE".equals(e.kind())
                && fix.proposalId().equals(e.proposalId())).count()).isEqualTo(changesAtCrash);
        assertThat(TestHarness.treeHash(resumed.layout.workspace())).isEqualTo(workspaceAtCrash);
        // identity restored from disk, with the fix's statement lineage intact
        assertThat(resumed.identity.statements.get(anchor).currentText).contains("queryForList(sql, name)");
        assertThat(resumed.identity.contentHash()).isNotBlank();
        assertThat(identityAtCrash).isNotBlank();
        assertThat(resumed.record.verification).isNotEmpty();
        assertThat(resumed.record.verification.values()).contains("Cleared");
    }

    @Test
    void resumingATerminalRunIsIdempotent(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        h.approve(run, h.proposalFor(run, "INV-101"));
        h.engine.resume(run, true);
        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "No");
        h.engine.resume(run, false);
        RunSession done = h.session(run);
        String workspace = TestHarness.treeHash(done.layout.workspace());
        long events = ChangeLedger.verify(done.layout.ledgerFile(), done.layout.ledgerHead()).verifiedEvents();

        h.restart().engine.resume(run, true);
        RunSession again = h.session(run);
        assertThat(again.record.machine.current).isEqualTo(done.record.machine.current);
        assertThat(TestHarness.treeHash(again.layout.workspace())).isEqualTo(workspace);
        assertThat(ChangeLedger.verify(again.layout.ledgerFile(), again.layout.ledgerHead()).verifiedEvents()).isEqualTo(events);
        assertThat(TestHarness.read(again.layout.workspace().resolve(REPO_JAVA))).contains("queryForList(sql, name)");
    }

    private static long ledgerStatus(RunSession session, String status) {
        try {
            return java.nio.file.Files.readAllLines(session.layout.ledgerFile()).stream().filter(l -> !l.isBlank())
                    .map(com.mars.harness.kernel.core.KernelJson::parse)
                    .filter(n -> status.equals(n.path("event").path("status").asText())).count();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String securityReports(RunSession session) {
        try (var files = java.nio.file.Files.list(session.layout.area("validation/security"))) {
            return files.map(TestHarness::read).reduce("", String::concat);
        } catch (java.io.IOException e) {
            return e.toString();
        }
    }
}
