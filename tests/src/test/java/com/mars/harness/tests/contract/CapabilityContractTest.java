package com.mars.harness.tests.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.3: every capability satisfies the common contracts. The contracts are checked on what
 * both capability packs actually produced in one composite MIGRATE_FIRST run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapabilityContractTest {

    @TempDir
    static Path temp;
    private TestHarness h;
    private String run;

    @BeforeAll
    void composite() {
        h = TestHarness.over(temp.resolve("composite"), "composite/inventory-service");
        run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "MIGRATE_FIRST", "dev.lead", "owner", "Recommended order");
        h.engine.resume(run, false);
        h.approve(run, h.proposalFor(run, "INV-101"));
        h.engine.resume(run, true);
    }

    @Test
    void everyAssessmentAndFindingCarriesEvidence() {
        RunSession session = h.session(run);
        Set<String> known = session.evidence.all().stream().map(EvidenceRecord::evidenceId).collect(Collectors.toSet());
        JsonNode assessment = h.json(run, "discovery/migration/migration-assessment.json");
        assertThat(assessment.path("evidence").size()).isPositive();
        assessment.path("evidence").forEach(e -> assertThat(known).contains(e.asText()));
        for (Finding f : session.findings) {
            assertThat(f.evidenceRefs()).as(f.sourceFindingId()).isNotEmpty();
            assertThat(known).containsAll(f.evidenceRefs());
        }
        JsonNode combined = h.json(run, "discovery/combined-assessment.json");
        assertThat(combined.path("sequence").path("rationale").asText()).isNotBlank();
        assertThat(combined.path("sequence").path("evidence_refs").size()).isPositive();
    }

    @Test
    void everyProposalCarriesItsBaseIdentity() {
        RunSession session = h.session(run);
        List<ChangeProposal> proposals = session.proposals.all();
        assertThat(proposals).anyMatch(p -> p.capability() == ChangeProposal.Capability.MIGRATION)
                .anyMatch(p -> p.capability() == ChangeProposal.Capability.SECURITY);
        for (ChangeProposal p : proposals) {
            assertThat(p.baselineSeal()).as(p.proposalId()).isEqualTo(session.baselineSeal());
            assertThat(p.provenance()).as(p.proposalId()).isNotNull();
            assertThat(p.proposalHash()).hasSize(64);
            if (p.strategyOnly()) {
                assertThat(p.edits()).isEmpty();
                continue;
            }
            assertThat(p.affectedFileIds()).as(p.proposalId()).isNotEmpty();
            for (ChangeProposal.FileEdit edit : p.edits()) {
                if (!"CREATE".equals(edit.operation())) {
                    assertThat(edit.fileId()).as("FILE_ID on " + edit.path()).startsWith("FILE-");
                    assertThat(p.affectedFileIds()).contains(edit.fileId());
                    assertThat(p.baseHashes()).as("base hash of " + edit.fileId()).containsKey(edit.fileId());
                }
                assertThat(edit.unifiedDiff()).as("reviewable diff").isNotBlank();
            }
            if (p.capability() == ChangeProposal.Capability.MIGRATION) {
                assertThat(p.provenance().ruleId()).as("migration change names its pack rule").startsWith("SB4-");
                assertThat(p.migrationRefs()).isNotEmpty();
            } else {
                assertThat(p.findingRefs()).as("security change names its finding").isNotEmpty();
            }
        }
    }

    @Test
    void everyAppliedChangeIsInTheLedgerWithItsAuthorization() {
        RunSession session = h.session(run);
        Map<String, List<String>> applied = session.record.proposalChanges;
        assertThat(applied).isNotEmpty();
        for (Map.Entry<String, List<String>> e : applied.entrySet()) {
            ChangeProposal p = session.proposals.find(e.getKey()).orElseThrow();
            for (String changeId : e.getValue()) {
                var entries = session.lineage.forChange(changeId);
                assertThat(entries).as(changeId).anyMatch(x -> "CHANGE".equals(x.kind()));
                if (p.capability() != ChangeProposal.Capability.MIGRATION) {
                    assertThat(entries).anyMatch(x -> x.decisionId() != null && x.decisionId().startsWith("DEC-"));
                }
            }
        }
    }

    @Test
    void validationNeverCountsAnUnexecutedDimensionAsPass() {
        TestHarness skip = TestHarness.over(temp.resolve("skip"), "composite/inventory-service");
        Path inputs = TestHarness.fixture("composite/inputs");
        String r = skip.engine.analyze(new HarnessEngine.AnalyzeRequest(skip.repository, List.of(inputs.resolve("issue-register.xlsx")),
                Map.of(), null, true)).runId();
        skip.engine.decideExecution(r, "ANALYZE_ONLY", "dev.lead", "owner", "Assess");
        skip.engine.resume(r, false);
        JsonNode validation = latestValidation(skip, r);
        for (JsonNode d : validation.path("dimensions")) {
            if (Set.of("BUILD_PACKAGE", "TESTS", "RUNTIME_STARTUP", "BEHAVIOR_PROBES").contains(d.path("dimension").asText())) {
                assertThat(d.path("status").asText()).as(d.path("dimension").asText()).isNotIn("PASS",
                        "PASS_WITH_EXPLAINED_DIFFERENCES");
            }
        }
        assertThat(skip.json(r, "reports/verdict.json").path("outcome").asText()).isNotEqualTo("CLEARED");
        assertThat(skip.json(r, "baseline/baseline-manifest.json").path("baseline_build_outcome").asText()).startsWith("NOT_RUN");
    }

    @Test
    void reportsAreViewsNotState() throws Exception {
        TestHarness fresh = TestHarness.over(temp.resolve("reports"), "composite/inventory-service");
        String r = fresh.analyzeComposite(true).runId();
        fresh.engine.decideExecution(r, "SECURITY_ONLY", "dev.lead", "owner", "Security");
        fresh.engine.resume(r, false);
        // delete every report: the run continues from its record, ledgers and registries, and re-renders them
        try (Stream<Path> files = Files.walk(fresh.runDir(r).resolve("reports"))) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
        fresh.approve(r, fresh.proposalFor(r, "INV-101"));
        HarnessEngine.RunSummary summary = fresh.engine.resume(r, true);
        assertThat(summary.phase()).isEqualTo("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION");
        fresh.engine.decidePostSecurityMigration(r, "SKIP", "dev.lead", "owner", "No");
        fresh.engine.resume(r, false);
        assertThat(fresh.runDir(r).resolve("reports/final-report.md")).exists();
        assertThat(fresh.session(r).record.verification.values()).contains("Cleared");
    }

    private static JsonNode latestValidation(TestHarness h, String run) {
        return h.json(run, "validation/latest.json");
    }
}
