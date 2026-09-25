package com.bootshift.tests.impact;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.util.Json;
import com.bootshift.stages.stage09.AccuracyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures the impact analyzer against the held-out fixture corpus and writes the numbers down.
 *
 * <p>The numbers are earned rather than declared: the fixture supplies an input tree and the ground
 * truth for it, the harness runs the real matcher, and the comparison is between what the matcher
 * predicted and what is true. Nothing in a fixture states what the matcher should predict.
 *
 * <p>The measurement is published to {@code reports/impact-accuracy.json} so a reader can see the
 * corpus size alongside the score. A precision figure from six examples is not a general accuracy
 * claim, and printing it without the denominator is how it becomes one.
 */
class ImpactCorpusAccuracyTest {

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isDirectory(candidate.resolve("fixtures"))) {
            candidate = candidate.getParent();
        }
        return candidate == null ? Path.of("").toAbsolutePath() : candidate;
    }

    @Test
    @DisplayName("the corpus covers a range of fact categories, positive and negative")
    void corpusIsBroadEnoughToBeInformative() throws IOException {
        Path corpus = repositoryRoot().resolve(AccuracyHarness.FIXTURE_DIRECTORY);
        assertThat(Files.isDirectory(corpus)).isTrue();

        Map<String, Integer> byFactType = new TreeMap<>();
        int positives = 0;
        int negatives = 0;
        int tuning = 0;
        List<String> ids = new ArrayList<>();

        try (var stream = Files.list(corpus)) {
            for (Path directory : stream.filter(Files::isDirectory).sorted().toList()) {
                Path descriptor = directory.resolve("fixture.json");
                if (!Files.isRegularFile(descriptor)) {
                    continue;
                }
                JsonNode node = Json.read(descriptor);
                ids.add(node.path("id").asText());
                byFactType.merge(node.path("fact").path("type").asText("UNKNOWN"), 1, Integer::sum);
                if (node.path("tuning").asBoolean(false)) {
                    tuning++;
                }
                if (node.path("true_affected_paths").isEmpty()) {
                    negatives++;
                } else {
                    positives++;
                }
            }
        }

        // A corpus of only positives cannot catch a matcher that flags everything, which is the
        // single most likely way for an impact analyzer to look excellent and be useless.
        assertThat(negatives)
                .as("the corpus must contain at least one fixture whose correct answer is nothing")
                .isGreaterThanOrEqualTo(1);
        assertThat(positives).isGreaterThanOrEqualTo(8);
        assertThat(byFactType.keySet())
                .as("a corpus covering one fact category measures one category")
                .hasSizeGreaterThanOrEqualTo(4);

        System.out.println("impact corpus: " + ids.size() + " fixture(s), " + positives
                + " positive, " + negatives + " negative, " + tuning + " tuning; by fact type "
                + byFactType);
    }

    @Test
    @DisplayName("measured accuracy is recorded with its denominator")
    void accuracyIsMeasuredAndPublished() throws IOException {
        Path root = repositoryRoot();
        AccuracyHarness.Result result = new AccuracyHarness().evaluate(root);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("measured_at", java.time.Instant.now().toString());
        record.put("method", "ImpactStage's own matcher is executed over each held-out fixture tree; "
                + "the fixture supplies the input and the ground truth, never the prediction");
        record.put("evaluated", result.evaluated());
        record.put("held_out_fixtures", result.fixtures());
        record.put("true_positives", result.truePositives());
        record.put("false_positives", result.falsePositives());
        record.put("false_negatives", result.falseNegatives());
        record.put("precision", result.evaluated() ? result.precision() : null);
        record.put("recall", result.evaluated() ? result.recall() : null);
        record.put("f1", result.evaluated() ? result.f1() : null);
        record.put("notes", result.notes());
        record.put("scope_warning", "These figures describe this corpus of " + result.fixtures()
                + " held-out fixture(s). They are not a general accuracy claim about the analyzer on "
                + "arbitrary repositories, and must not be quoted as one.");

        Path output = root.resolve("reports/impact-accuracy.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, Json.pretty(record) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        System.out.println("impact accuracy: " + Json.pretty(record));

        if (result.evaluated()) {
            assertThat(result.precision()).isBetween(0.0, 1.0);
            assertThat(result.recall()).isBetween(0.0, 1.0);
            assertThat(result.fixtures()).isGreaterThan(0);
        } else {
            // Unmeasured is a legitimate outcome and must not be reported as perfect.
            assertThat(result.precision()).isZero();
            assertThat(result.notes()).isNotEmpty();
        }
    }
}
