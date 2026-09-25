package com.mars.harness.tests.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.evidence.EvidenceLog;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Scenario 1: analyze only. Inventory, identity, graph, baseline, assessment, discovery, report; no mutation. */
class AnalyzeOnlyE2ETest {

    @Test
    void analyzeOnlyProducesEvidenceAndNeverMutates(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String original = TestHarness.treeHash(h.repository);

        HarnessEngine.RunSummary summary = h.analyzeComposite(true);
        String run = summary.runId();
        RunSession session = h.session(run);

        // inventory + identity: every tracked file has a FILE_ID; Java files have sub-file identity
        assertThat(session.fileRegistry.size()).isGreaterThan(10);
        assertThat(session.identity.programUnits).isNotEmpty();
        assertThat(session.identity.symbols).isNotEmpty();
        assertThat(session.identity.statements).isNotEmpty();
        assertThat(session.identity.modules).isNotEmpty();
        // graph: Bootshift's graph plus the identity overlay
        assertThat(session.graph.nodeCount()).isPositive();
        // baseline sealed before discovery, with round 0 recorded
        JsonNode manifest = h.json(run, "baseline/baseline-manifest.json");
        assertThat(manifest.path("baseline_manifest_hash").asText()).isEqualTo(session.baselineSeal());
        assertThat(manifest.path("baseline_build_outcome").asText()).isEqualTo("passed");
        assertThat(manifest.path("baseline_runtime_started").asBoolean()).isTrue();
        // discovery: both planes
        assertThat(h.runDir(run).resolve("discovery/security/security-discovery.json")).exists();
        assertThat(h.runDir(run).resolve("discovery/migration/migration-assessment.json")).exists();
        JsonNode combined = h.json(run, "discovery/combined-assessment.json");
        assertThat(combined.path("traffic_light").asText()).isNotBlank();
        assertThat(combined.path("complexity").asText()).isNotBlank();
        assertThat(combined.path("evidence_confidence").asText()).isNotBlank();
        assertThat(h.runDir(run).resolve("reports/analysis-report.md")).exists();
        assertThat(EvidenceLog.verify(session.layout.evidenceLog())).isEmpty();

        String workspaceBefore = TestHarness.treeHash(session.layout.workspace());
        h.engine.decideExecution(run, "ANALYZE_ONLY", "dev.lead", "owner", "Assessment only");
        summary = h.engine.resume(run, false);

        session = h.session(run);
        assertThat(summary.phase()).isEqualTo("COMPLETE");
        assertThat(session.proposals.all()).isEmpty();
        assertThat(session.record.proposalChanges).isEmpty();
        assertThat(session.lineage.entries()).noneMatch(e -> "CHANGE".equals(e.kind()));
        assertThat(TestHarness.treeHash(session.layout.workspace())).isEqualTo(workspaceBefore);
        assertThat(TestHarness.treeHash(h.repository)).isEqualTo(original);
        assertThat(h.runDir(run).resolve("reports/final-report.md")).exists();
        assertThat(h.json(run, "reports/verdict.json").path("outcome").asText()).isNotEqualTo("CLEARED");
    }
}
