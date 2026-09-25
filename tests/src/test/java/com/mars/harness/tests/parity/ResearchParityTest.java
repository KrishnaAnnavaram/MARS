package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.capabilities.security.research.ResearchAssembler;
import com.mars.harness.kernel.core.KernelJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.4, B5: the legacy 04d {@code generate-strategy.js} (run unchanged as a CLI, writing
 * only into a temporary {@code PIPELINE_CONTEXT_DATA_DIR}) against {@link ResearchAssembler#assemble}
 * on the same understanding and analysis: research status, Low confidence, summary, approach,
 * alternatives, risks, verification plan, open questions, planned changes, derived-pattern block,
 * candidate count and promotion-candidate flag. Established research yields a promotion
 * candidate, an evidence gap never does. Inputs are the legacy {@code SAMPLE-CWE502} and
 * {@code SAMPLE-THIN} fixtures plus variants of them (a different chosen candidate with cited
 * external sources, and an evidence gap with no missing evidence and no conservative default).
 *
 * <p>The port is given what the legacy script reads: {@code defect} is
 * {@code understanding.defect.statement}, {@code affected} is {@code understanding.affected_files}.
 */
class ResearchParityTest {

    private static final String FIXTURES = "vulnerability-remediation-harness/.github/skills/04d-remediation-research/fixtures/";

    @Test
    void establishedResearchFixture(@TempDir Path dir) {
        assertSame(LegacyNode.run("research.js", dir.toString(), "--fixture", "SAMPLE-CWE502"));
    }

    @Test
    void insufficientEvidenceFixture(@TempDir Path dir) {
        assertSame(LegacyNode.run("research.js", dir.toString(), "--fixture", "SAMPLE-THIN"));
    }

    @Test
    void secondCandidateChosenWithCitedSources(@TempDir Path dir) throws IOException {
        ObjectNode u = (ObjectNode) KernelJson.read(LegacyNode.legacy(FIXTURES + "SAMPLE-CWE502.understanding.json"));
        ObjectNode a = (ObjectNode) KernelJson.read(LegacyNode.legacy(FIXTURES + "SAMPLE-CWE502.analysis.json"));
        u.put("issue_id", "SYN-ALT");
        a.put("issue_id", "SYN-ALT");
        ((ObjectNode) a.path("recommendation")).put("candidate_id", "CAND-002");
        ((ObjectNode) a.path("candidates").get(1)).remove("limitations");
        ObjectNode provenance = (ObjectNode) a.path("provenance");
        provenance.put("external_research_available", true);
        provenance.putArray("sources").add("https://example.org/deserialization-cheat-sheet").add("JEP 290");
        provenance.remove("reasoning");
        u.putArray("affected_files").add("svc/A.java").add("svc/B.java");
        assertSame(LegacyNode.run("research.js", stage(dir, u, a), "--issue", "SYN-ALT"));
    }

    @Test
    void evidenceGapWithDefaultsOnly(@TempDir Path dir) throws IOException {
        ObjectNode u = (ObjectNode) KernelJson.read(LegacyNode.legacy(FIXTURES + "SAMPLE-THIN.understanding.json"));
        ObjectNode a = (ObjectNode) KernelJson.read(LegacyNode.legacy(FIXTURES + "SAMPLE-THIN.analysis.json"));
        u.put("issue_id", "SYN-GAP");
        a.put("issue_id", "SYN-GAP");
        a.remove("missing_evidence");
        a.remove("conservative_default");
        ((ObjectNode) a.path("provenance")).remove("reasoning");
        assertSame(LegacyNode.run("research.js", stage(dir, u, a), "--issue", "SYN-GAP"));
    }

    private static String stage(Path dir, JsonNode understanding, JsonNode analysis) throws IOException {
        Path research = Files.createDirectories(dir.resolve("research"));
        String id = understanding.path("issue_id").asText();
        Files.writeString(research.resolve(id + ".understanding.json"), KernelJson.pretty(understanding));
        Files.writeString(research.resolve(id + ".analysis.json"), KernelJson.pretty(analysis));
        return dir.toString();
    }

    private static void assertSame(JsonNode legacy) {
        assertThat(legacy.path("exitCode").asInt()).as(legacy.path("stderr").asText()).isZero();
        JsonNode u = legacy.path("understanding");
        JsonNode s = legacy.path("strategy");
        assertThat(s.isObject()).isTrue();

        ResearchAssembler.Strategy j = ResearchAssembler.assemble(u.path("gap").path("primary_cwe").asText(),
                Optional.of(legacy.path("analysis")), LegacyNode.text(u.path("defect").path("statement")),
                u.path("title").asText(), LegacyNode.strings(u.path("affected_files")));

        assertThat(j.validationErrors()).isEmpty();
        assertThat(j.cwe()).isEqualTo(s.path("cwe").asText());
        assertThat(j.researchStatus()).isEqualTo(s.path("research").path("research_status").asText());
        assertThat(j.confidence()).isEqualTo(s.path("confidence").asText()).isEqualTo("Low");
        assertThat(j.plainSummary()).isEqualTo(s.path("plain_summary").asText());
        assertThat(j.approach()).isEqualTo(s.path("approach").asText());
        assertThat(j.alternatives()).isEqualTo(LegacyNode.strings(s.path("alternatives_considered")));
        assertThat(j.riskNotes()).isEqualTo(LegacyNode.strings(s.path("risk_notes")));
        assertThat(j.verificationPlan()).isEqualTo(LegacyNode.strings(s.path("verification_plan")));
        assertThat(j.openQuestions()).isEqualTo(LegacyNode.strings(s.path("open_questions")));
        List<String> planned = new ArrayList<>();
        s.path("affected_files").forEach(f -> planned.add(f.path("file").asText() + ": " + f.path("planned_change").asText()));
        assertThat(j.plannedChanges()).isEqualTo(planned);

        JsonNode dp = s.path("derived_pattern");
        assertThat(j.derivedPattern().get("type")).isEqualTo(dp.path("type").asText());
        assertThat(j.derivedPattern().get("sources")).isEqualTo(LegacyNode.strings(dp.path("sources")));
        assertThat(j.derivedPattern().get("reasoning")).isEqualTo(dp.path("reasoning").asText());
        assertThat(j.derivedPattern().get("candidate_count")).isEqualTo(dp.path("candidate_count").asInt());
        assertThat(j.derivedPattern().get("external_research_available")).isEqualTo(dp.path("external_research_available").asBoolean());
        assertThat(j.candidateCount()).isEqualTo(dp.path("candidate_count").asInt());
        assertThat(j.promotionCandidate()).isEqualTo(s.path("promotion_candidate").asBoolean());

        boolean established = "established".equals(j.researchStatus());
        assertThat(legacy.path("promotion").isObject()).isEqualTo(established);
        assertThat(legacy.path("result").isObject()).isEqualTo(!established);
        if (!established) {
            assertThat(legacy.path("result").path("research_status").asText()).isEqualTo("insufficient_evidence");
            assertThat(j.candidateCount()).isZero();
        }
    }
}
