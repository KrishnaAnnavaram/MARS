package com.mars.harness.tests.e2e;

import com.bootshift.core.ledger.ChangeLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.evidence.EvidenceLog;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.mars.harness.tests.e2e.RedMigrationDeclinedE2ETest.find;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario 8 (composite migrate + remediate in one run), §32.11 SECURITY_FIRST, §32.12
 * MIGRATE_FIRST and §32.13 post-security re-assessment with Human Gate A2.
 */
class CompositeSequencingE2ETest {

    private static final String REPO_JAVA = "src/main/java/com/acme/inventory/repo/ProductRepository.java";

    @Test
    void migrateFirstSequencesThePlatformConstrainedFixAfterTheApprovedMigration(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        String advisory = h.finding(run, "FIXTURE-ADV-SPRINGDOC-0001").findingId();

        h.engine.decideExecution(run, "MIGRATE_FIRST", "dev.lead", "owner", "Follow the harness recommendation");
        HarnessEngine.RunSummary summary = h.engine.resume(run, false);

        // migration ran first and is green; the springdoc line moved to the Boot 4 compatible 3.x
        JsonNode execution = h.json(run, "plans/migration-execution.json");
        assertThat(execution.path("status").asText()).isEqualTo("GREEN");
        RunSession session = h.session(run);
        String pom = TestHarness.read(session.layout.workspace().resolve("pom.xml"));
        assertThat(pom).contains("<version>4.1.1</version>").contains("<version>3.1.0</version>");
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_REMEDIATION_APPROVAL");

        // the platform-constrained finding is sequenced after the migration: it is no longer blocked
        JsonNode plans = h.json(run, "plans/remediation-plans.json");
        JsonNode advisoryPlan = find(plans, "finding_id", advisory);
        assertThat(advisoryPlan.path("blocked_by_platform").asBoolean()).isFalse();
        assertThat(advisoryPlan.path("route").asText()).isEqualTo("ALREADY_REMEDIATED");

        ChangeProposal sqlFix = h.proposalFor(run, "INV-101");
        // the security proposal was computed against the migrated workspace
        String migratedHash = session.fileRegistry.byPath(REPO_JAVA).orElseThrow().getCurrentSha256();
        assertThat(sqlFix.baseHashes()).containsValue(migratedHash);
        h.approve(run, sqlFix);
        h.approve(run, h.proposalFor(run, "INV-102"));
        summary = h.engine.resume(run, true);
        assertThat(summary.phase()).isIn("COMPLETE", "NEEDS_HUMAN");

        // Scenario 8: one run, one identity plane, one graph, one evidence plane, one ledger, one gateway
        session = h.session(run);
        List<LineageLedger.Entry> changes = session.lineage.entries().stream().filter(e -> "CHANGE".equals(e.kind())).toList();
        assertThat(changes).anyMatch(e -> "MIGRATION".equals(e.capability()));
        assertThat(changes).anyMatch(e -> "SECURITY".equals(e.capability()));
        int lastMigration = lastIndex(changes, "MIGRATION");
        int firstSecurity = firstIndex(changes, "SECURITY");
        assertThat(firstSecurity).isGreaterThan(lastMigration);
        assertThat(LineageLedger.verify(session.layout.lineageLedger())).isEmpty();
        assertThat(EvidenceLog.verify(session.layout.evidenceLog())).isEmpty();
        assertThat(ChangeLedger.verify(session.layout.ledgerFile(), session.layout.ledgerHead()).valid()).isTrue();
        assertThat(session.record.notes).noneMatch(n -> n.startsWith("BYPASS DETECTED"));

        JsonNode verdict = h.json(run, "reports/verdict.json");
        assertThat(find(verdict.path("items"), "item_id", "MIGRATION").path("status").asText()).isEqualTo("MIGRATED");
        assertThat(find(verdict.path("items"), "item_id", advisory).path("status").asText()).isEqualTo("FIXED");
        assertThat(find(verdict.path("items"), "item_id", h.finding(run, "INV-101").findingId()).path("status").asText())
                .isEqualTo("FIXED");
    }

    @Test
    void securityFirstFixesTheIndependentCriticalBeforeTheMigration(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        String advisory = h.finding(run, "FIXTURE-ADV-SPRINGDOC-0001").findingId();

        h.engine.decideExecution(run, "SECURITY_FIRST", "dev.lead", "owner", "The SQL injection cannot wait for the upgrade");
        HarnessEngine.RunSummary summary = h.engine.resume(run, false);
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_REMEDIATION_APPROVAL");
        assertThat(h.runDir(run).resolve("plans/migration-execution.json")).doesNotExist();
        assertThat(find(h.json(run, "plans/remediation-plans.json"), "finding_id", advisory)
                .path("blocked_by_platform").asBoolean()).isTrue();

        h.approve(run, h.proposalFor(run, "INV-101"));
        summary = h.engine.resume(run, true);

        RunSession session = h.session(run);
        List<LineageLedger.Entry> changes = session.lineage.entries().stream().filter(e -> "CHANGE".equals(e.kind())).toList();
        assertThat(firstIndex(changes, "SECURITY")).isLessThan(firstIndex(changes, "MIGRATION"));
        assertThat(h.json(run, "plans/migration-execution.json").path("status").asText()).isEqualTo("GREEN");
        // the migration started from the patched code: the security fix survives it
        assertThat(TestHarness.read(session.layout.workspace().resolve(REPO_JAVA))).contains("queryForList(sql, name)");
        // the platform-blocked fix was re-planned once against the migrated platform
        assertThat(session.record.replannedAfterMigration).isTrue();
        assertThat(find(h.json(run, "plans/remediation-plans.json"), "finding_id", advisory).path("route").asText())
                .isEqualTo("ALREADY_REMEDIATED");
        assertThat(summary.phase()).isIn("COMPLETE", "NEEDS_HUMAN");
        JsonNode verdict = h.json(run, "reports/verdict.json");
        assertThat(find(verdict.path("items"), "item_id", advisory).path("status").asText()).isEqualTo("FIXED");
        assertThat(find(verdict.path("items"), "item_id", h.finding(run, "INV-101").findingId()).path("status").asText())
                .isEqualTo("FIXED");
    }

    @Test
    void postSecurityReassessmentOffersGateA2AndProceedRunsTheMigration(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only for now");
        h.engine.resume(run, false);
        h.approve(run, h.proposalFor(run, "INV-101"));
        HarnessEngine.RunSummary summary = h.engine.resume(run, true);

        assertThat(summary.phase()).isEqualTo("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION");
        JsonNode comparison = h.json(run, "discovery/migration/post-security-reassessment.json");
        assertThat(comparison.path("previous_traffic_light").asText()).isEqualTo("RED");
        assertThat(comparison.path("current_traffic_light").asText()).isEqualTo("RED");
        assertThat(comparison.path("why").asText()).isNotBlank();
        assertThat(h.runDir(run).resolve("discovery/migration/migration-assessment-post-security.json")).exists();

        var decision = h.engine.decidePostSecurityMigration(run, "PROCEED", "dev.lead", "owner", "Unblock the advisory");
        assertThat(decision.assessmentHash()).isEqualTo(comparison.path("current_assessment_hash").asText());
        summary = h.engine.resume(run, false);

        assertThat(h.json(run, "plans/migration-execution.json").path("status").asText()).isEqualTo("GREEN");
        assertThat(summary.phase()).isIn("COMPLETE", "NEEDS_HUMAN");
        JsonNode verdict = h.json(run, "reports/verdict.json");
        assertThat(find(verdict.path("items"), "item_id", "MIGRATION").path("status").asText()).isEqualTo("MIGRATED");
        assertThat(find(verdict.path("items"), "item_id", h.finding(run, "FIXTURE-ADV-SPRINGDOC-0001").findingId())
                .path("status").asText()).isEqualTo("FIXED");
    }

    private static int firstIndex(List<LineageLedger.Entry> entries, String capability) {
        for (int i = 0; i < entries.size(); i++) {
            if (capability.equals(entries.get(i).capability())) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndex(List<LineageLedger.Entry> entries, String capability) {
        int last = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (capability.equals(entries.get(i).capability())) {
                last = i;
            }
        }
        return last;
    }
}
