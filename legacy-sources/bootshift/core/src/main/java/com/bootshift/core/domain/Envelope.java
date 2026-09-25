package com.bootshift.core.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard artifact envelope (spec section 43). Every stage artifact carries the same metadata
 * header so downstream consumers can bind an artifact to a run, an edge, a repository state and a
 * baseline without guessing.
 *
 * <p>Envelopes are mutable during construction and frozen into JSON on write.
 */
public final class Envelope {

    /** A dimension the run could not observe at all. */
    public record BlindSpot(String id, String dimension, String description, String reason) {
    }

    /** A dimension observed only partially. */
    public record Gap(String id, String dimension, String description, String impact) {
    }

    /** Where the repository actually was when the artifact was produced. */
    public record RepoState(String kind, String rootPath, String commitSha, String treeSha,
                            String branch, String contentManifestHash) {
    }

    /** Binds a mutating artifact to the sealed baseline it is allowed to build on. */
    public record BaselineBinding(String baselineManifestHash, String originalTreeHash, boolean sealed) {
    }

    private final String pipelineStage;
    private final String schemaVersion;
    private final Instant generatedAt;
    private final String runId;
    private String edgeId;
    private final Map<String, String> upstream = new LinkedHashMap<>();
    private boolean mutating;
    private RepoState repoState;
    private BaselineBinding baselineBinding;
    private String environmentFingerprint;
    private final List<BlindSpot> blindSpots = new ArrayList<>();
    private final List<Gap> gaps = new ArrayList<>();
    private final Map<String, Object> stats = new LinkedHashMap<>();

    public Envelope(String pipelineStage, String schemaVersion, String runId) {
        this.pipelineStage = pipelineStage;
        this.schemaVersion = schemaVersion;
        this.runId = runId;
        this.generatedAt = Instant.now();
    }

    public Envelope edgeId(String value) {
        this.edgeId = value;
        return this;
    }

    public Envelope upstream(String stage, String artifactHash) {
        this.upstream.put(stage, artifactHash);
        return this;
    }

    public Envelope mutating(boolean value) {
        this.mutating = value;
        return this;
    }

    public Envelope repoState(RepoState value) {
        this.repoState = value;
        return this;
    }

    public Envelope baselineBinding(BaselineBinding value) {
        this.baselineBinding = value;
        return this;
    }

    public Envelope environmentFingerprint(String value) {
        this.environmentFingerprint = value;
        return this;
    }

    public Envelope blindSpot(BlindSpot value) {
        this.blindSpots.add(value);
        return this;
    }

    public Envelope gap(Gap value) {
        this.gaps.add(value);
        return this;
    }

    public Envelope stat(String key, Object value) {
        this.stats.put(key, value);
        return this;
    }

    public List<BlindSpot> blindSpots() {
        return blindSpots;
    }

    public List<Gap> gaps() {
        return gaps;
    }

    public String runId() {
        return runId;
    }

    public String pipelineStage() {
        return pipelineStage;
    }

    /**
     * Renders the envelope into a fresh object node. The stage then adds its own payload fields to
     * the returned node; envelope keys are reserved and must not be overwritten.
     */
    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("pipeline_stage", pipelineStage);
        node.put("schema_version", schemaVersion);
        node.put("generated_at", generatedAt.toString());
        node.put("run_id", runId);
        node.put("edge_id", edgeId);
        node.set("upstream", Json.toTree(upstream));
        node.put("mutating", mutating);
        node.set("repo_state", repoState == null ? Json.obj() : Json.toTree(repoState));
        node.set("baseline_binding", baselineBinding == null ? Json.obj() : Json.toTree(baselineBinding));
        node.put("environment_fingerprint", environmentFingerprint);
        node.set("blind_spots", Json.toTree(blindSpots));
        node.set("gaps", Json.toTree(gaps));
        node.set("stats", Json.toTree(stats));
        return node;
    }

    /** Reserved envelope keys. Stages must not shadow these when adding payload. */
    public static final List<String> RESERVED_KEYS = List.of(
            "pipeline_stage", "schema_version", "generated_at", "run_id", "edge_id", "upstream",
            "mutating", "repo_state", "baseline_binding", "environment_fingerprint",
            "blind_spots", "gaps", "stats");
}
