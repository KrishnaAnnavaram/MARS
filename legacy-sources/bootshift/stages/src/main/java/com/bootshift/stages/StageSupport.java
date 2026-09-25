package com.bootshift.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared plumbing for stages: envelope construction, schema-validated publishing and upstream
 * artifact resolution.
 *
 * <p>Publishing goes through {@link #publish} so no stage can accidentally advance
 * {@code latest.json} past a schema violation.
 */
public final class StageSupport {

    public static final String SCHEMA_VERSION = "1.0.0";

    private StageSupport() {
    }

    public static Envelope envelope(StageContext context, String stageId) {
        return new Envelope(stageId, SCHEMA_VERSION, context.run().runId())
                .stat("policy", context.policy().name())
                .stat("policy_hash", context.policy().policyHash())
                .stat("ai_enabled", context.run().aiEnabled())
                .stat("environment_mode", context.run().environmentMode());
    }

    /** Merges stage payload into the envelope node, refusing to shadow reserved keys. */
    public static ObjectNode compose(Envelope envelope, ObjectNode payload) {
        ObjectNode node = envelope.toNode();
        payload.fields().forEachRemaining(entry -> {
            if (Envelope.RESERVED_KEYS.contains(entry.getKey())) {
                throw HarnessException.refusal(
                        "Stage payload attempted to shadow reserved envelope key: " + entry.getKey());
            }
            node.set(entry.getKey(), entry.getValue());
        });
        return node;
    }

    /**
     * Validates an artifact against its schema and records any violation on the writer so
     * {@link OutputLayout.StageWriter#publish} refuses to advance the pointer.
     */
    public static void validate(StageContext context, OutputLayout.StageWriter writer,
                                String schemaPath, String artifactName, JsonNode payload) {
        if (!context.schemas().available()) {
            return;
        }
        List<String> errors = context.schemas().validate(schemaPath, payload);
        boolean missing = errors.size() == 1 && errors.get(0).startsWith("Schema not found");
        if (missing) {
            return;
        }
        errors.forEach(e -> writer.validationError(artifactName + ": " + e));
    }

    /** Reads an artifact published by an upstream stage, refusing when the upstream never ran. */
    public static JsonNode requireUpstream(StageContext context, String stageDirectory,
                                           String artifactName, String remediation) {
        JsonNode node = context.run().output().readLatest(stageDirectory, artifactName);
        if (node == null) {
            throw HarnessException.refusal("Missing required upstream artifact "
                    + stageDirectory + "/" + artifactName + ". " + remediation);
        }
        return node;
    }

    public static JsonNode optionalUpstream(StageContext context, String stageDirectory,
                                            String artifactName) {
        return context.run().output().readLatest(stageDirectory, artifactName);
    }

    public static Path upstreamPath(StageContext context, String stageDirectory, String artifactName) {
        return context.run().output().latestArtifactPath(stageDirectory, artifactName);
    }

    /** Stores an artifact copy in the content-addressed evidence store and returns its id. */
    public static String toEvidence(StageContext context, String kind, ObjectNode payload,
                                    EvidenceManifest.Classification classification,
                                    String retentionClass, String producedBy) {
        byte[] bytes = Json.canonical(payload).getBytes(StandardCharsets.UTF_8);
        return context.evidenceStore()
                .put(kind, kind + ".json", bytes, classification, retentionClass, producedBy)
                .evidenceId();
    }

    public static String toEvidence(StageContext context, String kind, String text,
                                    EvidenceManifest.Classification classification,
                                    String retentionClass, String producedBy) {
        return context.evidenceStore()
                .put(kind, kind + ".txt", text.getBytes(StandardCharsets.UTF_8), classification,
                        retentionClass, producedBy)
                .evidenceId();
    }

    /** Publishes the writer and returns the manifest hash for the stage result. */
    public static String publish(StageContext context, OutputLayout.StageWriter writer) {
        writer.publish(context.run().runId());
        return writer.hashOf("manifest.json") == null
                ? String.valueOf(writer.hashes().hashCode()) : writer.hashOf("manifest.json");
    }

    /**
     * Publishes an edge-scoped stage and records where it published into the edge index.
     *
     * <p>Publishing advances one {@code latest.json} pointer per stage. In a per-edge loop that
     * pointer names whichever edge ran last, so a later reader that only follows the pointer sees
     * one edge and reports it as the migration. Recording the directory here is what lets final
     * evidence aggregate all of them.
     */
    public static String publishForEdge(StageContext context, OutputLayout.StageWriter writer,
                                        String edgeId, String stageDirectory,
                                        EdgeIndex.Phase phase, String outcome) {
        String hash = publish(context, writer);
        EdgeIndex.open(context)
                .recordStage(edgeId, stageDirectory, writer.dir(), phase, outcome)
                .persist();
        return hash;
    }
}
