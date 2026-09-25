package com.mars.harness.tests.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.15: a direct write to tracked source, outside the Mutation Gateway, is flagged by the
 * runtime bypass detector and blocks the verdict. (The architecture tests cover the static half.)
 */
class MutationBypassIT {

    @Test
    void directWorkspaceWriteIsDetectedAndBlocksTheVerdict(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);

        // a rogue tool edits tracked source directly
        RunSession session = h.session(run);
        Path target = session.layout.workspace().resolve("src/main/java/com/acme/inventory/prefs/PreferencesController.java");
        Files.writeString(target, Files.readString(target) + "\n// edited behind the gateway's back\n");

        // a legitimate batch runs through the gateway: the post-batch detector sees the foreign write
        h.approve(run, h.proposalFor(run, "INV-101"));
        h.engine.resume(run, true);
        session = h.session(run);
        assertThat(session.record.notes).anyMatch(n -> n.startsWith("BYPASS DETECTED") && n.contains("PreferencesController"));

        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "No");
        h.engine.resume(run, false);
        JsonNode verdict = h.json(run, "reports/verdict.json");
        assertThat(verdict.path("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(verdict.path("hard_failures").toString()).contains("bypass");
    }

    @Test
    void bypassWithoutAnyLaterBatchIsCaughtByFinalValidation(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "ANALYZE_ONLY", "dev.lead", "owner", "Assess");
        Path target = h.session(run).layout.workspace().resolve("src/main/java/com/acme/inventory/InventoryApplication.java");
        Files.writeString(target, Files.readString(target) + "\n// rogue\n");
        h.engine.resume(run, false);
        JsonNode verdict = h.json(run, "reports/verdict.json");
        assertThat(verdict.path("outcome").asText()).isEqualTo("BLOCKED");
    }
}
