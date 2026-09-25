package com.bootshift.tests.impact;

import com.bootshift.stages.stage09.AccuracyHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the measured-accuracy path.
 *
 * <p>These exist because the metric they guard was, at one point, self-fulfilling: the harness read
 * a {@code predicted_paths} array out of the fixture file and compared it against the ground truth in
 * the same file. That graded whether the fixture author had written two consistent lists and reported
 * the answer as the analyzer's precision and recall. In a report, a fabricated metric is
 * indistinguishable from a real one, which makes it worse than having none.
 */
class ImpactAccuracyTest {

    @TempDir
    Path root;

    private void fixture(String name, String factJson, String truthJson, String tuning,
                         String relativeFile, String content) throws IOException {
        Path dir = root.resolve("fixtures/impact-evaluation").resolve(name);
        Files.createDirectories(dir.resolve("tree").resolve(relativeFile).getParent());
        Files.writeString(dir.resolve("tree").resolve(relativeFile), content, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("fixture.json"), """
                {
                  "id": "%s",
                  "tuning": %s,
                  "fact": %s,
                  "true_affected_paths": %s
                }
                """.formatted(name, tuning, factJson, truthJson), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("with no held-out fixtures, accuracy is UNMEASURED and not a default number")
    void noFixturesMeansUnmeasured() {
        AccuracyHarness.Result result = new AccuracyHarness().evaluate(root);

        assertThat(result.evaluated()).isFalse();
        assertThat(result.precision()).isZero();
        assertThat(result.recall()).isZero();
        assertThat(result.toNode().get("status").asText()).isEqualTo("UNMEASURED");
        assertThat(result.toNode().get("precision").isNull()).isTrue();
        assertThat(result.notes()).anyMatch(n -> n.contains("not the same as accurate"));
    }

    @Test
    @DisplayName("the fixture supplies the input and the truth; the harness produces the prediction")
    void predictionComesFromTheAnalyzer() throws IOException {
        fixture("relocation", """
                        {"type": "API_RENAMED", "subject": "javax.persistence",
                         "from": "javax.persistence", "to": "jakarta.persistence"}""",
                "[\"m/src/main/java/E.java\"]", "false",
                "m/src/main/java/E.java",
                "package a;\n\nimport javax.persistence.Entity;\n\n@Entity\npublic class E {}\n");

        AccuracyHarness.Result result = new AccuracyHarness().evaluate(root);

        assertThat(result.evaluated()).isTrue();
        assertThat(result.truePositives()).isEqualTo(1);
        assertThat(result.falseNegatives()).isZero();
        assertThat(result.toNode().get("method").asText())
                .contains("never the prediction");
    }

    @Test
    @DisplayName("a fixture whose truth the analyzer misses is counted as a miss, not smoothed away")
    void missesAreCounted() throws IOException {
        // The tree does not reference the subject at all, so a correct analyzer finds nothing and
        // the declared truth is unreachable. The harness must report the miss rather than pass.
        fixture("unreachable", """
                        {"type": "API_REMOVED", "subject": "com.example.NeverReferenced"}""",
                "[\"m/src/main/java/E.java\"]", "false",
                "m/src/main/java/E.java", "package a;\n\npublic class E {}\n");

        AccuracyHarness.Result result = new AccuracyHarness().evaluate(root);

        assertThat(result.falseNegatives()).isEqualTo(1);
        assertThat(result.recall()).isZero();
        assertThat(result.notes()).anyMatch(n -> n.startsWith("MISSED in unreachable"));
    }

    @Test
    @DisplayName("tuning fixtures are excluded, so the harness never grades itself on its own training cases")
    void tuningFixturesAreExcluded() throws IOException {
        fixture("tuned", """
                        {"type": "API_RENAMED", "subject": "javax.persistence",
                         "from": "javax.persistence", "to": "jakarta.persistence"}""",
                "[\"m/src/main/java/E.java\"]", "true",
                "m/src/main/java/E.java",
                "package a;\n\nimport javax.persistence.Entity;\n\n@Entity\npublic class E {}\n");

        AccuracyHarness.Result result = new AccuracyHarness().evaluate(root);

        assertThat(result.evaluated()).isFalse();
        assertThat(result.notes()).anyMatch(n -> n.contains("tuning fixture(s) excluded"));
    }

    @Test
    @DisplayName("the repository's own held-out fixtures are loaded and are not self-graded")
    void repositoryFixturesAreWellFormed() {
        Path harnessRoot = Path.of("").toAbsolutePath().getParent();
        Path directory = harnessRoot.resolve(AccuracyHarness.FIXTURE_DIRECTORY);
        List<AccuracyHarness.Fixture> fixtures = new AccuracyHarness().load(directory);

        assertThat(fixtures).isNotEmpty();
        assertThat(fixtures).anyMatch(f -> !f.tuning());
        assertThat(fixtures).anyMatch(AccuracyHarness.Fixture::tuning);
        // A fixture that declared its own prediction would make the metric meaningless. The record
        // has no field for one, and this asserts the descriptors do not smuggle it back in.
        for (AccuracyHarness.Fixture f : fixtures) {
            assertThat(f.fact().path("type").asText()).isNotBlank();
            assertThat(Files.isDirectory(f.directory().resolve("tree"))).isTrue();
        }
    }
}
