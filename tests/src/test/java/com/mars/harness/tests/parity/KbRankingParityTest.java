package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.capabilities.security.kb.HybridRanker;
import com.mars.harness.capabilities.security.kb.KbStrategyDeriver;
import com.mars.harness.capabilities.security.knowledge.VrhKnowledge;
import com.mars.harness.capabilities.security.routing.CweRouter;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Spec §32.4, B4: the legacy 04c fallback ({@code run-fallback.js --dry-run}, run unchanged on a
 * context bundle) against {@link HybridRanker} and {@link KbStrategyDeriver} on the same bundle:
 * the same ranked historical fixes in the same order with identical keyword, TF-IDF and hybrid
 * scores and matched keywords/synonyms, Low confidence, and the exact strategy strings (summary,
 * provenance approach, alternatives, risks, verification, open questions, planned change,
 * derived-pattern block). Inputs are the two legacy fixtures plus bundles for the other KB CWEs, a
 * forced {@code --cwe}, a KB gap and a catalogued CWE.
 *
 * <p>The optional local embedding model is not available to the JVM harness (documented in
 * {@link HybridRanker}); the comparison is skipped if the legacy skill has one set up, because the
 * legacy ranking would then include a signal the port deliberately omits.
 */
class KbRankingParityTest {

    private static final String FIXTURES = "vulnerability-remediation-harness/.github/skills/04c-remediation-intelligence/fixtures/";

    private static VrhKnowledge knowledge;

    @BeforeAll
    static void load() {
        UnifiedPolicy policy = UnifiedPolicy.load(LegacyNode.root().resolve("policies/default/unified-policy.json"));
        knowledge = VrhKnowledge.load(LegacyNode.root(), policy);
    }

    @Test
    void cwe22Fixture(@TempDir Path dir) {
        assertSameStrategy(dir, (ObjectNode) KernelJson.read(LegacyNode.legacy(FIXTURES + "SAMPLE-CWE22.context.json")), null);
    }

    @Test
    void cwe22SynonymFixture(@TempDir Path dir) {
        assertSameStrategy(dir, (ObjectNode) KernelJson.read(LegacyNode.legacy(FIXTURES + "SAMPLE-CWE22-SYNONYM.context.json")), null);
    }

    @Test
    void ssrfBundle(@TempDir Path dir) {
        assertSameStrategy(dir, context("SYN-918", "Server-side request forgery in the avatar importer",
                "The avatar import endpoint fetches whatever URL the caller supplies with RestTemplate, so an attacker can make "
                        + "the server request internal hosts such as the cloud metadata address 169.254.169.254.",
                "Validate the destination host against an allow-list and block private, loopback and link-local ranges.",
                "## Summary\n\nUser-controlled url parameter reaches an outbound HTTP client (CWE-918).", "profile-service/src/main/java/"
                        + "com/acme/profile/AvatarController.java", List.of("CWE-918")), null);
    }

    @Test
    void missingAuthorizationBundle(@TempDir Path dir) {
        assertSameStrategy(dir, context("SYN-862", "Any logged-in user can delete another user's department",
                "DELETE /departments/{id} checks only that the caller is authenticated and never verifies the caller owns or "
                        + "administers the department, so a regular user can delete any department.",
                "Enforce an ownership or role check on the server before the delete executes (deny by default).",
                "Detection Notes: missing authorization check on a state-changing endpoint (CWE-862).",
                "department-service/src/main/java/com/acme/dept/DepartmentController.java", List.of("CWE-862")), null);
    }

    @Test
    void piiExposureBundle(@TempDir Path dir) {
        assertSameStrategy(dir, context("SYN-359", "Employee export leaks personal data",
                "The employee export serialises the full JPA entity, including home address, date of birth and national id, "
                        + "to every caller of the report API.",
                "Return an explicit projection DTO with only the fields the caller is authorized to see.",
                "Summary: private personal information exposed to an unauthorized actor (CWE-359).",
                "employee-service/src/main/java/com/acme/employee/EmployeeController.java", List.of("CWE-359")), null);
    }

    @Test
    void emptyRootCauseStatementFallsBackToTheTitle(@TempDir Path dir) {
        assertSameStrategy(dir, context("SYN-EMPTY", "Report download writes outside the reports folder", "",
                "Resolve under a fixed base directory.", "The filename parameter reaches a FileOutputStream (CWE-22).",
                "report-service/src/main/java/com/acme/report/DownloadController.java", List.of("CWE-22")), null);
    }

    @Test
    void forcedGapCweAmongSeveralGaps(@TempDir Path dir) {
        assertSameStrategy(dir, context("SYN-MIXED", "Deserialization and traversal in the import endpoint",
                "The import endpoint deserializes a caller-supplied blob and then writes it to a path built from the filename "
                        + "parameter, so ../ sequences escape the upload directory.",
                "Resolve the target under a fixed base directory and verify containment after normalization.",
                "Mentions CWE-502 and CWE-22.", "import-service/src/main/java/com/acme/imp/ImportController.java",
                List.of("CWE-502", "CWE-22")), "CWE-22");
    }

    @Test
    void kbGapIsRefusedLikeTheRouterSendsItToResearch(@TempDir Path dir) {
        ObjectNode context = context("SYN-502", "Unsafe deserialization", "ObjectInputStream.readObject on request bytes.",
                null, "CWE-502", null, List.of("CWE-502"));
        JsonNode legacy = runLegacy(dir, context, null);
        assertThat(legacy.path("exitCode").asInt()).isEqualTo(3);
        assertThat(legacy.path("stderr").asText()).contains("gap in BOTH the catalog and the knowledge base");
        assertThat(knowledge.kb()).doesNotContainKey("CWE-502");
        assertThat(CweRouter.route("CWE-502", knowledge).route()).isEqualTo(CweRouter.Route.RESEARCH);
    }

    @Test
    void catalogedCweIsDeclinedLikeTheRouterSendsItToTheCatalog(@TempDir Path dir) {
        ObjectNode context = context("SYN-89", "SQL injection", "String concatenation into a JDBC query.", null,
                "CWE-89 and CWE-22", null, List.of("CWE-89", "CWE-22"));
        JsonNode legacy = runLegacy(dir, context, null);
        assertThat(legacy.path("exitCode").asInt()).isEqualTo(2);
        assertThat(legacy.path("stderr").asText()).contains("is not a catalog gap");
        assertThat(CweRouter.route("CWE-89 and CWE-22", knowledge).route()).isEqualTo(CweRouter.Route.CATALOG);
    }

    private static ObjectNode context(String id, String title, String statement, String recommendedFix, String body,
                                      String file, List<String> cwes) {
        ObjectNode c = KernelJson.obj();
        c.put("id", id);
        c.put("title", title);
        ObjectNode rc = c.putObject("rootCause");
        rc.put("statement", statement);
        rc.put("recommendedFix", recommendedFix);
        rc.put("defectLocation", file == null ? null : file + ":42");
        c.putObject("issue").put("title", title).put("body", body);
        if (file != null) {
            c.putArray("files").addObject().put("file", file).put("found", true);
        }
        cwes.forEach(cwe -> c.withArray("cwe").addObject().put("cwe", cwe)
                .put("inCatalog", knowledge.catalog().containsKey(cwe)).putNull("entry"));
        return c;
    }

    private static JsonNode runLegacy(Path dir, ObjectNode context, String cwe) {
        try {
            Path bundle = dir.resolve("fix-strategy").resolve(context.path("id").asText() + ".context.json");
            Files.createDirectories(bundle.getParent());
            Files.writeString(bundle, KernelJson.pretty(context));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        String id = context.path("id").asText();
        return cwe == null ? LegacyNode.run("kb-fallback.js", dir.toString(), id)
                : LegacyNode.run("kb-fallback.js", dir.toString(), id, cwe);
    }

    private static void assertSameStrategy(Path dir, ObjectNode context, String forcedCwe) {
        JsonNode legacy = runLegacy(dir, context, forcedCwe);
        assumeFalse(legacy.path("embeddingAvailable").asBoolean(), "legacy 04c has a local embedding model set up");
        assertThat(legacy.path("exitCode").asInt()).as(legacy.path("stderr").asText()).isZero();
        JsonNode s = legacy.path("strategy");

        String gapCwe = s.path("cwe").asText();
        if (forcedCwe == null) {
            List<String> texts = new ArrayList<>();
            context.path("cwe").forEach(c -> texts.add(c.path("cwe").asText()));
            assertThat(CweRouter.route(String.join(" ", texts), knowledge).cwe()).isEqualTo(gapCwe);
        } else {
            assertThat(gapCwe).isEqualTo(forcedCwe);
        }
        JsonNode rc = context.path("rootCause");
        KbStrategyDeriver.Derived d = KbStrategyDeriver.derive(gapCwe, knowledge.kb().get(gapCwe), knowledge.kb(),
                knowledge.weights(), LegacyNode.text(context.path("title")), LegacyNode.text(rc.path("statement")),
                LegacyNode.text(rc.path("recommendedFix")), LegacyNode.text(context.path("issue").path("body")));

        JsonNode ranked = s.path("derived_pattern").path("ranked_examples");
        assertThat(d.ranked()).hasSameSizeAs(ranked);
        for (int i = 0; i < d.ranked().size(); i++) {
            HybridRanker.Ranked r = d.ranked().get(i);
            JsonNode e = ranked.get(i);
            assertThat(r.id()).isEqualTo(e.path("id").asText());
            assertThat(r.pattern()).isEqualTo(LegacyNode.text(e.path("pattern")));
            assertThat(r.summary()).isEqualTo(LegacyNode.text(e.path("summary")));
            assertThat(r.source()).isEqualTo(LegacyNode.text(e.path("source")));
            assertThat(r.score()).as(r.id() + " score").isEqualTo(e.path("score").asDouble());
            assertThat(r.keywordScore()).as(r.id() + " keyword").isEqualTo(e.path("keyword_score").asDouble());
            assertThat(r.tfidfScore()).as(r.id() + " tfidf").isEqualTo(e.path("tfidf_score").asDouble());
            assertThat(e.path("embedding_score").isNull()).isTrue();
            assertThat(r.embeddingScore()).isNull();
            assertThat(r.matchedKeywords()).isEqualTo(LegacyNode.strings(e.path("matched_keywords")));
            assertThat(r.matchedSynonyms()).isEqualTo(LegacyNode.strings(e.path("matched_synonyms")));
        }

        assertThat(d.cwe()).isEqualTo(gapCwe);
        assertThat(d.confidence()).isEqualTo(s.path("confidence").asText()).isEqualTo("Low");
        assertThat(d.plainSummary()).isEqualTo(s.path("plain_summary").asText());
        assertThat(d.approach()).isEqualTo(s.path("approach").asText());
        assertThat(d.alternatives()).isEqualTo(LegacyNode.strings(s.path("alternatives_considered")));
        assertThat(d.riskNotes()).isEqualTo(LegacyNode.strings(s.path("risk_notes")));
        assertThat(d.verificationPlan()).isEqualTo(LegacyNode.strings(s.path("verification_plan")));
        assertThat(d.openQuestions()).isEqualTo(LegacyNode.strings(s.path("open_questions")));
        assertThat(s.path("affected_files")).isNotEmpty();
        s.path("affected_files").forEach(f -> assertThat(d.plannedChange()).isEqualTo(f.path("planned_change").asText()));

        JsonNode dp = s.path("derived_pattern");
        assertThat(d.derivedPattern().get("source_type")).isEqualTo(dp.path("source_type").asText());
        assertThat(d.derivedPattern().get("sources")).isEqualTo(LegacyNode.strings(dp.path("sources")));
        assertThat(d.derivedPattern().get("kb_entry")).isEqualTo(dp.path("kb_entry").asText());
        assertThat(d.derivedPattern().get("note")).isEqualTo(dp.path("note").asText());
    }
}
