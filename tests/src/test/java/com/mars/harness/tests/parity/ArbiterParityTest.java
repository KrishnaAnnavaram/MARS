package com.mars.harness.tests.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.capabilities.security.knowledge.VrhKnowledge;
import com.mars.harness.capabilities.security.verify.MergeArbiter;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.4, B10: the legacy 07a {@code compute-score.js --all} (run unchanged, its upstream
 * reports and issue register served from memory) against {@link MergeArbiter#arbitrate} over the
 * full grid of re-scan × red-team × behaviour × QA × build × severity verdicts, including
 * unknown verdicts (0 points), a blank severity and an unknown severity (default threshold), and
 * both {@code Compiled} and {@code Compile Failed} fix statuses. Score, threshold, hard-gate
 * reasons in order and computed decision must be identical; a {@code Refused} fix is not scored
 * by either side.
 */
class ArbiterParityTest {

    private static final List<String> RESCAN = List.of("NO_LONGER_DETECTED", "STILL_VULNERABLE");
    private static final List<String> REDTEAM = List.of("NO_BYPASS_FOUND", "INCONCLUSIVE", "BYPASS_FOUND", "NOT_RUN");
    private static final List<String> BEHAVIOR = List.of("BEHAVIOR_PRESERVED", "INCONCLUSIVE", "BEHAVIOR_CHANGED", "NOT_RUN");
    private static final List<String> QA = List.of("Passed", "Failed", "Refused");
    private static final List<String> BUILD = List.of("Passed", "Failed");
    private static final List<String> SEVERITY = Arrays.asList("Critical", "High", "Medium", "Low", "", "Informational");

    @Test
    void everyVerdictCombinationScoresAndDecidesAlike(@TempDir Path dir) throws Exception {
        List<Map<String, String>> cases = new ArrayList<>();
        int n = 0;
        for (String rescan : RESCAN) {
            for (String redteam : REDTEAM) {
                for (String behavior : BEHAVIOR) {
                    for (String qa : QA) {
                        for (String build : BUILD) {
                            for (String severity : SEVERITY) {
                                Map<String, String> c = new LinkedHashMap<>();
                                c.put("id", String.format("ARB-%04d", ++n));
                                c.put("severity", severity);
                                c.put("fixStatus", n % 2 == 0 ? "Compiled" : "Compile Failed");
                                c.put("rescan", rescan);
                                c.put("redteam", redteam);
                                c.put("behavior", behavior);
                                c.put("qa", qa);
                                c.put("build", build);
                                cases.add(c);
                            }
                        }
                    }
                }
            }
        }
        Map<String, String> refused = new LinkedHashMap<>(cases.get(0));
        refused.put("id", "ARB-REFUSED");
        refused.put("fixStatus", "Refused");
        cases.add(refused);

        Path file = dir.resolve("cases.json");
        Files.writeString(file, KernelJson.mapper().writeValueAsString(cases));
        Path data = Files.createDirectories(dir.resolve("data"));
        JsonNode legacy = LegacyNode.run("arbiter.js", file.toString(), data.toString());
        assertThat(legacy.path("exitCode").asInt()).isZero();

        UnifiedPolicy policy = UnifiedPolicy.load(LegacyNode.root().resolve("policies/default/unified-policy.json"));
        VrhKnowledge.Scoring scoring = VrhKnowledge.load(LegacyNode.root(), policy).scoring();
        JsonNode legacyScoring = legacy.path("scoring");
        assertThat(scoring.defaultThreshold()).isEqualTo(legacyScoring.path("default_threshold").asInt());
        assertThat(scoring.severityThresholds()).isEqualTo(ints(legacyScoring.path("severity_thresholds")));
        assertThat(scoring.redteam()).isEqualTo(ints(legacyScoring.path("weights").path("redteam")));
        assertThat(scoring.behavior()).isEqualTo(ints(legacyScoring.path("weights").path("behavior")));
        assertThat(scoring.qa()).isEqualTo(ints(legacyScoring.path("weights").path("qa")));

        assertThat(legacy.path("scores").path("ARB-REFUSED").isNull()).isTrue();
        int compared = 0;
        for (Map<String, String> c : cases.subList(0, cases.size() - 1)) {
            JsonNode expected = legacy.path("scores").path(c.get("id"));
            String as = c.toString();
            assertThat(expected.isObject()).as(as).isTrue();
            String severity = c.get("severity").isEmpty() ? null : c.get("severity");
            assertThat(LegacyNode.text(expected.path("severity"))).as(as).isEqualTo(severity);
            MergeArbiter.Result actual = MergeArbiter.arbitrate(scoring, severity, c.get("rescan"), c.get("redteam"),
                    c.get("behavior"), c.get("qa"), c.get("build"), null);
            assertThat(actual.score()).as(as).isEqualTo(expected.path("score").asInt());
            assertThat(actual.threshold()).as(as).isEqualTo(expected.path("threshold").asInt());
            List<String> gates = new ArrayList<>();
            expected.path("gates").forEach(g -> gates.add(g.path("reason").asText()));
            assertThat(actual.gatesTriggered()).as(as).isEqualTo(gates);
            assertThat(actual.computedDecision()).as(as).isEqualTo(expected.path("computedDecision").asText());
            assertThat(actual.decision()).as(as).isEqualTo(actual.computedDecision());
            compared++;
        }
        assertThat(compared).isEqualTo(RESCAN.size() * REDTEAM.size() * BEHAVIOR.size() * QA.size() * BUILD.size() * SEVERITY.size());
    }

    private static Map<String, Integer> ints(JsonNode object) {
        Map<String, Integer> map = new LinkedHashMap<>();
        object.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asInt()));
        return map;
    }
}
