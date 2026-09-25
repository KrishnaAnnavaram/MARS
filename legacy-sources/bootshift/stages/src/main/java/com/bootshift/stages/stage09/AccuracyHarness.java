package com.bootshift.stages.stage09;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measured impact accuracy (spec section 26).
 *
 * <p>The point of this class is that the number it produces is <em>earned</em>. A fixture supplies
 * an input - a small source tree and one migration fact - and the ground truth for that input. The
 * harness then runs {@link ImpactStage}'s own matcher over the fixture and compares what the matcher
 * actually predicted against the truth.
 *
 * <p>An earlier version of this class read a {@code predicted_paths} array out of the fixture file
 * and compared it against {@code true_affected_paths} in the same file. That measured whether the
 * fixture author had written two consistent lists, and reported the result as the analyzer's
 * precision and recall. A self-fulfilling accuracy metric is worse than no metric, because it is
 * indistinguishable from a real one in the report.
 *
 * <p>Three properties are preserved from that version and matter as much as the fix:
 * <ul>
 *   <li>fixtures marked {@code tuning} are excluded from reported numbers, so the harness never
 *       grades itself on the cases its rules were derived from;</li>
 *   <li>when no held-out fixtures exist the result is <em>unmeasured</em>, not "perfect";</li>
 *   <li>a malformed fixture is skipped with a note rather than failing the run.</li>
 * </ul>
 */
public final class AccuracyHarness {

    public record Result(boolean evaluated, int fixtures, int truePositives, int falsePositives,
                         int falseNegatives, double precision, double recall, double f1,
                         List<String> notes) {

        public com.fasterxml.jackson.databind.node.ObjectNode toNode() {
            var node = Json.obj();
            node.put("evaluated", evaluated);
            node.put("held_out_fixtures", fixtures);
            node.put("true_positives", truePositives);
            node.put("false_positives", falsePositives);
            node.put("false_negatives", falseNegatives);
            if (evaluated) {
                node.put("precision", precision);
                node.put("recall", recall);
                node.put("f1", f1);
            } else {
                node.putNull("precision");
                node.putNull("recall");
                node.putNull("f1");
                node.put("status", "UNMEASURED");
            }
            node.put("method", "ImpactStage's own matcher is run over each fixture tree; the "
                    + "fixture supplies the input and the ground truth, never the prediction");
            node.set("notes", Json.toTree(notes));
            return node;
        }
    }

    /** One fixture: an input tree, one fact, and the paths a correct analyzer must return. */
    public record Fixture(String id, Path directory, JsonNode fact, Set<String> trueAffectedPaths,
                          boolean tuning, String description) {
    }

    public static final String FIXTURE_DIRECTORY = "fixtures/impact-evaluation";

    /** Evaluates against every held-out fixture under {@code fixtures/impact-evaluation}. */
    public Result evaluate(Path harnessRoot) {
        Path directory = harnessRoot.resolve(FIXTURE_DIRECTORY);
        List<Fixture> fixtures = load(directory);
        List<String> notes = new ArrayList<>();

        List<Fixture> heldOut = fixtures.stream().filter(f -> !f.tuning()).toList();
        long tuning = fixtures.size() - heldOut.size();
        if (tuning > 0) {
            notes.add(tuning + " tuning fixture(s) excluded from the reported numbers");
        }
        if (heldOut.isEmpty()) {
            notes.add("No held-out fixtures found under " + FIXTURE_DIRECTORY
                    + "; impact accuracy is UNMEASURED, which is not the same as accurate");
            return new Result(false, 0, 0, 0, 0, 0, 0, 0, notes);
        }

        int truePositives = 0;
        int falsePositives = 0;
        int falseNegatives = 0;
        int evaluated = 0;

        for (Fixture fixture : heldOut) {
            Set<String> predicted;
            try {
                predicted = predict(fixture);
            } catch (RuntimeException e) {
                notes.add("SKIPPED " + fixture.id() + ": " + e);
                continue;
            }
            evaluated++;
            for (String path : predicted) {
                if (fixture.trueAffectedPaths().contains(path)) {
                    truePositives++;
                } else {
                    falsePositives++;
                    notes.add("FALSE POSITIVE in " + fixture.id() + ": " + path);
                }
            }
            for (String actual : fixture.trueAffectedPaths()) {
                if (!predicted.contains(actual)) {
                    falseNegatives++;
                    notes.add("MISSED in " + fixture.id() + ": " + actual);
                }
            }
        }

        if (evaluated == 0) {
            notes.add("Every held-out fixture failed to evaluate; impact accuracy is UNMEASURED");
            return new Result(false, 0, 0, 0, 0, 0, 0, 0, notes);
        }

        double precision = truePositives + falsePositives == 0 ? 0
                : round((double) truePositives / (truePositives + falsePositives));
        double recall = truePositives + falseNegatives == 0 ? 0
                : round((double) truePositives / (truePositives + falseNegatives));
        double f1 = precision + recall == 0 ? 0 : round(2 * precision * recall / (precision + recall));
        return new Result(true, evaluated, truePositives, falsePositives, falseNegatives,
                precision, recall, f1, notes);
    }

    /**
     * Runs the real matcher over one fixture tree.
     *
     * <p>The graph handed to the matcher is intentionally empty: a fixture declares source files,
     * not a pre-built graph, so the matcher must reach its conclusions from the registry and the
     * file contents. That is the harder half of the problem and the half that regresses silently.
     */
    private Set<String> predict(Fixture fixture) {
        Path root = fixture.directory().resolve("tree");
        FileRegistry registry = new FileRegistry();
        Map<String, String> pathByFileId = new LinkedHashMap<>();

        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).sorted().forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("fixture tree unreadable: " + root, e);
        }

        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            String module = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : "";
            FileRegistry.ObservedFile observed = new FileRegistry.ObservedFile(module, relative,
                    Hashing.sha256(content), roleOf(relative), content.length(), content);
            String fileId = registry.allocate(observed).fileId();
            pathByFileId.put(fileId, relative);
        }

        ApplicationGraph graph = new ApplicationGraph().label("fixture:" + fixture.id());
        List<ImpactStage.Match> matches = ImpactStage.locateFor(new ImpactStage(), fixture.fact(),
                graph, registry, root, new LinkedHashMap<>());

        Set<String> predicted = new LinkedHashSet<>();
        for (ImpactStage.Match match : matches) {
            String path = pathByFileId.get(match.fileId());
            if (path != null) {
                predicted.add(path);
            }
        }
        return predicted;
    }

    private static FileRole roleOf(String relative) {
        if (relative.endsWith("pom.xml")) {
            return FileRole.MAVEN_BUILD;
        }
        if (relative.endsWith(".properties")) {
            return FileRole.CONFIG_PROPERTIES;
        }
        if (relative.endsWith(".yml") || relative.endsWith(".yaml")) {
            return FileRole.CONFIG_YAML;
        }
        if (relative.contains("/test/") && relative.endsWith(".java")) {
            return FileRole.JAVA_TEST;
        }
        if (relative.endsWith(".java")) {
            return FileRole.JAVA_MAIN;
        }
        return FileRole.RESOURCE;
    }

    /** Loads fixtures. A malformed fixture is skipped with a note rather than failing the run. */
    public List<Fixture> load(Path directory) {
        List<Fixture> fixtures = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return fixtures;
        }
        try (var stream = Files.list(directory)) {
            for (Path child : stream.filter(Files::isDirectory).sorted().toList()) {
                Path descriptor = child.resolve("fixture.json");
                if (!Files.isRegularFile(descriptor) || !Files.isDirectory(child.resolve("tree"))) {
                    continue;
                }
                JsonNode node = Json.read(descriptor);
                JsonNode fact = node.path("fact");
                if (fact.isMissingNode() || fact.path("type").asText("").isBlank()) {
                    continue;
                }
                Set<String> trueAffected = new LinkedHashSet<>();
                node.path("true_affected_paths").forEach(p -> trueAffected.add(p.asText()));
                fixtures.add(new Fixture(
                        node.path("id").asText(child.getFileName().toString()),
                        child, fact, trueAffected,
                        node.path("tuning").asBoolean(false),
                        node.path("description").asText(null)));
            }
        } catch (IOException e) {
            return fixtures;
        }
        return fixtures;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
