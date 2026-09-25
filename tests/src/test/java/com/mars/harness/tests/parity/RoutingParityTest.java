package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.capabilities.security.knowledge.VrhKnowledge;
import com.mars.harness.capabilities.security.routing.CweRouter;
import com.mars.harness.kernel.adapters.excel.IssueRegister;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.4, B2/B3: VRH gap detection ({@code 04a plans.detectCweMentions}, 04c
 * {@code classifyGap} and {@code detect-gap.js verdictFor}, 04d {@code research.js gapStatus})
 * against {@link CweRouter#route}, on hand-written texts (catalog hit, catalog hit beside a gap,
 * KB-only hit, double gap, no CWE) and on every issue body of the legacy VRH register.
 *
 * <p>Route mapping: {@code COVERED} is {@code CATALOG} with the first catalogued CWE; {@code GAP}
 * with {@code kb_status=match} is {@code KB}; {@code GAP} with {@code research_required} is
 * {@code RESEARCH} on {@code gapStatus.primary_cwe}; {@code NO-CWE} is {@code UNCLASSIFIED}.
 *
 * <p>Two differences are documented and asserted as such:
 * <ul>
 *   <li>The unified catalog adds the migration reference's CWE-1104 entry ({@link VrhKnowledge}
 *       Javadoc), so CWE-1104 is catalogued in Java and a gap for the VRH-only catalog.</li>
 *   <li>When the first gap has no KB entry but a later gap does (legacy {@code gapStatus} says
 *       {@code kb_status=match}, while {@code run-fallback.js} without {@code --cwe} would stop on
 *       {@code primaryGap}), the router takes the first KB-backed gap, which is what
 *       {@code run-fallback.js --cwe <that CWE>} accepts and derives from.</li>
 * </ul>
 */
class RoutingParityTest {

    private static final Pattern CWE_LINE = Pattern.compile("in catalog=(true|false), in KB=(true|false)");

    private static VrhKnowledge knowledge;

    @BeforeAll
    static void load() {
        UnifiedPolicy policy = UnifiedPolicy.load(LegacyNode.root().resolve("policies/default/unified-policy.json"));
        knowledge = VrhKnowledge.load(LegacyNode.root(), policy);
    }

    @Test
    void handWrittenTextsRouteAsTheLegacyDetectorClassifiesThem(@TempDir Path dir) throws Exception {
        List<String> texts = List.of(
                "CWE-89 SQL injection in the product search",
                "cwe-22 path traversal, and CWE-89 in the same handler",
                "CWE-22 path traversal in the export filename (CWE-22 again)",
                "SSRF via CWE-918, then CWE-22 on the download path",
                "CWE-502 unsafe deserialization of the prefs token",
                "CWE-502 and CWE-840 together",
                "CWE-502 first, then CWE-22 which the KB covers",
                "CWE-022 written with a leading zero",
                "Nothing classifies this finding",
                "");
        assertRoutes(texts, dir);
    }

    @Test
    void legacyRegisterIssueBodiesRouteAlike(@TempDir Path dir) throws Exception {
        List<String> bodies = IssueRegister.list(LegacyNode.legacy(
                "vulnerability-remediation-harness/docs/agent_output/00-issues/issue-register.xlsx")).stream()
                .map(IssueRegister.Issue::body).toList();
        assertThat(bodies).hasSize(5);
        assertRoutes(bodies, dir);
    }

    @Test
    void singleCweLookupMatchesDetectGapCweMode() {
        List<String> cwes = new ArrayList<>(List.of("CWE-89", "CWE-798", "CWE-22", "CWE-918", "CWE-862", "CWE-502", "cwe-359"));
        for (String cwe : cwes) {
            Matcher m = CWE_LINE.matcher(LegacyNode.run("routing.js", "--cwe", cwe).path("line").asText());
            assertThat(m.find()).as(cwe).isTrue();
            String id = cwe.toUpperCase();
            assertThat(knowledge.catalog().containsKey(id)).as(id + " in catalog").isEqualTo(Boolean.parseBoolean(m.group(1)));
            assertThat(knowledge.kb().containsKey(id)).as(id + " in KB").isEqualTo(Boolean.parseBoolean(m.group(2)));
        }
    }

    @Test
    void supplementalCatalogEntryIsTheDocumentedDifference() {
        Matcher m = CWE_LINE.matcher(LegacyNode.run("routing.js", "--cwe", "CWE-1104").path("line").asText());
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("false");
        CweRouter.Decision decision = CweRouter.route("CWE-1104 vulnerable dependency version", knowledge);
        assertThat(decision.route()).isEqualTo(CweRouter.Route.CATALOG);
        assertThat(knowledge.catalog().get("CWE-1104").provenance()).startsWith("migration-reference 04a catalog");
    }

    private static void assertRoutes(List<String> texts, Path dir) throws Exception {
        Path cases = dir.resolve("cases.json");
        Files.writeString(cases, KernelJson.mapper().writeValueAsString(texts.stream().map(t -> Map.of("text", t)).toList()));
        JsonNode legacy = LegacyNode.run("routing.js", cases.toString());
        assertThat(knowledge.kb().keySet()).containsExactlyElementsOf(LegacyNode.strings(legacy.path("kb")));
        assertThat(knowledge.catalog().keySet()).containsAll(LegacyNode.strings(legacy.path("catalog")));

        for (JsonNode c : legacy.path("cases")) {
            String text = c.path("text").asText();
            CweRouter.Decision d = CweRouter.route(text, knowledge);
            String as = "'" + text.substring(0, Math.min(60, text.length())) + "'";

            List<String> expectedDetected = new ArrayList<>();
            c.path("candidates").forEach(k -> expectedDetected.add(k.path("cwe").asText() + " catalog="
                    + k.path("inCatalog").asBoolean() + " kb=" + k.path("inKb").asBoolean()));
            assertThat(d.detected().stream().map(k -> k.cwe() + " catalog=" + k.inCatalog() + " kb=" + k.inKb()).toList())
                    .as(as).containsExactlyElementsOf(expectedDetected);
            assertThat(CweRouter.detectCweMentions(text)).as(as)
                    .containsExactlyElementsOf(expectedDetected.stream().map(s -> s.split(" ")[0]).toList());

            JsonNode status = c.path("gapStatus");
            assertThat(d.catalogStatus()).as(as).isEqualTo(status.path("catalog_status").asText());
            assertThat(d.kbStatus()).as(as).isEqualTo(status.path("kb_status").asText());
            assertThat(d.researchRequired()).as(as).isEqualTo(status.path("research_required").asBoolean());

            JsonNode gap = c.path("gap");
            switch (c.path("verdict").path("verdict").asText()) {
                case "NO-CWE" -> {
                    assertThat(d.route()).as(as).isEqualTo(CweRouter.Route.UNCLASSIFIED);
                    assertThat(d.cwe()).as(as).isNull();
                }
                case "COVERED" -> {
                    assertThat(d.route()).as(as).isEqualTo(CweRouter.Route.CATALOG);
                    assertThat(d.cwe()).as(as).isEqualTo(gap.path("catalogued").get(0).asText());
                }
                case "GAP" -> {
                    assertThat(gap.path("warranted").asBoolean()).as(as).isTrue();
                    if (status.path("research_required").asBoolean()) {
                        assertThat(d.route()).as(as).isEqualTo(CweRouter.Route.RESEARCH);
                        assertThat(d.cwe()).as(as).isEqualTo(status.path("primary_cwe").asText());
                    } else {
                        assertThat(d.route()).as(as).isEqualTo(CweRouter.Route.KB);
                        String primaryGap = gap.path("primaryGap").asText();
                        String firstKbGap = null;
                        for (JsonNode k : c.path("candidates")) {
                            if (!k.path("inCatalog").asBoolean() && k.path("inKb").asBoolean()) {
                                firstKbGap = k.path("cwe").asText();
                                break;
                            }
                        }
                        assertThat(d.cwe()).as(as).isEqualTo(knowledge.kb().containsKey(primaryGap) ? primaryGap : firstKbGap);
                    }
                }
                default -> throw new AssertionError("unexpected legacy verdict " + c.path("verdict"));
            }
        }
    }
}
