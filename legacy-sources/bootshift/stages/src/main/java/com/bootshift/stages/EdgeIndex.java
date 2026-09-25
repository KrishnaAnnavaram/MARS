package com.bootshift.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-edge execution index.
 *
 * <p>Two problems this exists to solve, both of which come from the same root cause: the artifact
 * plane publishes one {@code latest.json} pointer per stage, and the per-edge loop runs the same
 * stage once per edge.
 *
 * <ul>
 *   <li>Final evidence aggregated only the <em>latest</em> {@code 13-build-repair}, {@code 15-test},
 *       {@code 16-runtime} and {@code 17-differential} artifact. With eight planned edges that meant
 *       the report described edge eight and silently claimed it for the whole migration.</li>
 *   <li>Resumption reconstructed the analysis half from published pointers but had no way to know
 *       which edges had already run, so a resumed run either redid completed edges or skipped
 *       incomplete ones.</li>
 * </ul>
 *
 * <p>The index records, for each edge, which output directory each stage published into and which
 * phases the edge has reached. It lives in the run workspace beside the ledger, and it is written
 * after a stage publishes, so it never claims a phase that failed to publish.
 */
public final class EdgeIndex {

    public static final String FILE = "edge-index.json";

    /** Phases an edge passes through, in order. */
    public enum Phase {
        PLANNED, TRANSFORMED, COMPILED, GRAPH_VERIFIED, TESTED, RUNTIME_VALIDATED,
        DIFFERENTIAL_VALIDATED, COMPLETE
    }

    /** One edge's recorded progress and the artifact directories that prove it. */
    public static final class EdgeRecord {
        private final String edgeId;
        private String edgeClass;
        private String sourceState;
        private String targetState;
        private final Map<String, String> stageDirectories = new LinkedHashMap<>();
        private final List<String> phasesReached = new ArrayList<>();
        private final Map<String, String> phaseTimestamps = new LinkedHashMap<>();
        private String lastOutcome;

        EdgeRecord(String edgeId) {
            this.edgeId = edgeId;
        }

        public String edgeId() {
            return edgeId;
        }

        public String edgeClass() {
            return edgeClass;
        }

        public String sourceState() {
            return sourceState;
        }

        public String targetState() {
            return targetState;
        }

        public Map<String, String> stageDirectories() {
            return stageDirectories;
        }

        public List<String> phasesReached() {
            return phasesReached;
        }

        public String lastOutcome() {
            return lastOutcome;
        }

        public boolean reached(Phase phase) {
            return phasesReached.contains(phase.name());
        }

        public boolean complete() {
            return reached(Phase.COMPLETE);
        }
    }

    private final Path file;
    private final Map<String, EdgeRecord> byEdge = new LinkedHashMap<>();
    private String runId;

    private EdgeIndex(Path file) {
        this.file = file;
    }

    /** Opens the index for a run, reading whatever is already recorded. */
    public static EdgeIndex open(StageContext context) {
        return open(context.run().runWorkspace().resolve(FILE), context.run().runId());
    }

    public static EdgeIndex open(Path file, String runId) {
        EdgeIndex index = new EdgeIndex(file);
        index.runId = runId;
        if (!Files.isRegularFile(file)) {
            return index;
        }
        JsonNode node = Json.read(file);
        index.runId = node.path("run_id").asText(runId);
        for (JsonNode edge : node.path("edges")) {
            EdgeRecord record = new EdgeRecord(edge.path("edge_id").asText());
            record.edgeClass = edge.path("edge_class").asText(null);
            record.sourceState = edge.path("source_state").asText(null);
            record.targetState = edge.path("target_state").asText(null);
            record.lastOutcome = edge.path("last_outcome").asText(null);
            edge.path("stage_directories").fields().forEachRemaining(e ->
                    record.stageDirectories.put(e.getKey(), e.getValue().asText()));
            edge.path("phases_reached").forEach(p -> record.phasesReached.add(p.asText()));
            edge.path("phase_timestamps").fields().forEachRemaining(e ->
                    record.phaseTimestamps.put(e.getKey(), e.getValue().asText()));
            byEdgePut(index, record);
        }
        return index;
    }

    private static void byEdgePut(EdgeIndex index, EdgeRecord record) {
        index.byEdge.put(record.edgeId(), record);
    }

    /** Seeds the index from the frozen plan so every planned edge is present from the start. */
    public EdgeIndex seedFromPlan(JsonNode edgePlanArtifact) {
        for (JsonNode edge : edgePlanArtifact.path("edges")) {
            String edgeId = edge.path("edge_id").asText();
            if (edgeId.isBlank()) {
                continue;
            }
            EdgeRecord record = byEdge.computeIfAbsent(edgeId, EdgeRecord::new);
            record.edgeClass = edge.path("edge_class").asText(null);
            record.sourceState = edge.path("source_state").asText(null);
            record.targetState = edge.path("target_state").asText(null);
            if (!record.phasesReached.contains(Phase.PLANNED.name())) {
                record.phasesReached.add(Phase.PLANNED.name());
                record.phaseTimestamps.put(Phase.PLANNED.name(), Instant.now().toString());
            }
        }
        return this;
    }

    /**
     * Records that a stage published for an edge.
     *
     * @param stageDirectory the stage's output directory name, e.g. {@code 15-test}
     * @param publishedDir   the timestamped directory the stage actually wrote into
     */
    public EdgeIndex recordStage(String edgeId, String stageDirectory, Path publishedDir,
                                 Phase phase, String outcome) {
        if (edgeId == null || edgeId.isBlank()) {
            return this;
        }
        EdgeRecord record = byEdge.computeIfAbsent(edgeId, EdgeRecord::new);
        if (publishedDir != null) {
            record.stageDirectories.put(stageDirectory, publishedDir.getFileName().toString());
        }
        if (phase != null && !record.phasesReached.contains(phase.name())) {
            record.phasesReached.add(phase.name());
            record.phaseTimestamps.put(phase.name(), Instant.now().toString());
        }
        record.lastOutcome = outcome;
        return this;
    }

    public EdgeIndex markComplete(String edgeId) {
        return recordStage(edgeId, null, null, Phase.COMPLETE, "EDGE_COMPLETE");
    }

    public Optional<EdgeRecord> edge(String edgeId) {
        return Optional.ofNullable(byEdge.get(edgeId));
    }

    public List<EdgeRecord> edges() {
        return new ArrayList<>(byEdge.values());
    }

    public List<String> incompleteEdges() {
        List<String> incomplete = new ArrayList<>();
        byEdge.values().forEach(record -> {
            if (!record.complete()) {
                incomplete.add(record.edgeId());
            }
        });
        return incomplete;
    }

    public boolean allComplete() {
        return !byEdge.isEmpty() && byEdge.values().stream().allMatch(EdgeRecord::complete);
    }

    /**
     * Resolves an artifact published by a stage for a specific edge.
     *
     * <p>This is what lets Stage 19 aggregate every edge instead of reading the single pointer that
     * happens to name the last one.
     */
    public Optional<JsonNode> artifact(StageContext context, String edgeId, String stageDirectory,
                                       String artifactName) {
        EdgeRecord record = byEdge.get(edgeId);
        if (record == null) {
            return Optional.empty();
        }
        String directory = record.stageDirectories.get(stageDirectory);
        if (directory == null) {
            return Optional.empty();
        }
        Path candidate = context.run().output().stageRoot(stageDirectory)
                .resolve(directory).resolve(artifactName);
        return Files.isRegularFile(candidate) ? Optional.of(Json.read(candidate)) : Optional.empty();
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("run_id", runId);
        node.put("edge_count", byEdge.size());
        node.put("complete_edges", byEdge.values().stream().filter(EdgeRecord::complete).count());
        node.put("purpose", "Binds each planned edge to the artifact directories that prove what it "
                + "did. Final evidence aggregates every edge from here rather than reading the single "
                + "latest.json pointer, which names only the edge that ran last.");
        List<ObjectNode> edges = new ArrayList<>();
        for (EdgeRecord record : byEdge.values()) {
            ObjectNode edge = Json.obj();
            edge.put("edge_id", record.edgeId);
            edge.put("edge_class", record.edgeClass);
            edge.put("source_state", record.sourceState);
            edge.put("target_state", record.targetState);
            edge.put("complete", record.complete());
            edge.put("last_outcome", record.lastOutcome);
            edge.set("phases_reached", Json.toTree(record.phasesReached));
            edge.set("phase_timestamps", Json.toTree(record.phaseTimestamps));
            edge.set("stage_directories", Json.toTree(record.stageDirectories));
            edges.add(edge);
        }
        node.set("edges", Json.toTree(edges));
        return node;
    }

    /** Persists the index. Called after a stage publishes, never before. */
    public void persist() {
        Json.writeAtomic(file, toNode());
    }
}
