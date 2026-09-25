package com.bootshift.stages.stage08;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * A migration fact (spec section 19).
 *
 * <p>Only {@link Status#VERIFIED} facts may authorize automatic transformation. A fact extracted
 * from documentation alone stays CANDIDATE until an artifact-channel observation corroborates it, and
 * a fact the two channels disagree about becomes CONFLICTING rather than being quietly resolved.
 */
public final class MigrationFact {

    public enum Type {
        API_REMOVED,
        API_RENAMED,
        API_SIGNATURE_CHANGED,
        PROPERTY_REMOVED,
        PROPERTY_RENAMED,
        PROPERTY_SILENTLY_IGNORED,
        DEFAULT_CHANGED,
        ARTIFACT_REMOVED,
        ARTIFACT_RELOCATED,
        MANAGED_VERSION_CHANGED,
        BEHAVIOR_CHANGED_NO_API_CHANGE,
        COMPATIBILITY_REQUIREMENT,
        BASELINE_REQUIREMENT
    }

    public enum Status {
        CANDIDATE, VERIFIED, CONFLICTING, REJECTED
    }

    public enum Channel {
        DOCUMENTATION, ARTIFACT, BOTH
    }

    private final String knowledgeId;
    private final Type type;
    private final String subject;
    private String from;
    private String to;
    private String sourceVersion;
    private String targetVersion;
    private String summary;
    private Status status = Status.CANDIDATE;
    private Channel channel = Channel.DOCUMENTATION;
    private double confidence;
    private final List<String> documentRefs = new ArrayList<>();
    private final List<String> artifactEvidence = new ArrayList<>();
    private final List<String> conflicts = new ArrayList<>();
    private String detectionRule;
    private boolean aiAssisted;

    /**
     * The component the fact is about, e.g. spring-boot, spring-security, hibernate, jakarta, jdk.
     *
     * <p>Without it, "this fact concerns X" can only be guessed from the subject string, and a
     * Hibernate change and a Jackson change with similar names become indistinguishable.
     */
    private String component = "spring-boot";

    /**
     * The version interval in which this fact is true, as a half-open range on the component's own
     * version line: the fact holds for a migration edge whose (from, to] interval intersects
     * [validFrom, validTo].
     *
     * <p>Before this existed, every fact carried only the whole migration's source and target
     * version, so a fact about the 2.7 to 3.0 boundary was offered as authorization for the 3.3 to
     * 3.4 edge. Authorization has to be scoped to the edge that actually crosses the change.
     */
    private String validFrom;
    private String validTo;

    /** Edge ids this fact was resolved against, filled in by the planner. Diagnostic, not authority. */
    private final List<String> appliesToEdges = new ArrayList<>();

    /**
     * How tightly the validity window is known.
     *
     * <p>EDGE_EXACT means the evidence names the exact version pair the change occurred across.
     * ARTIFACT_VERSION_WINDOW means the change is somewhere inside the edges on which the owning
     * artifact's managed version actually moved - narrower than the whole migration, still not exact.
     * SPAN_ONLY means the evidence only covers the whole migration and the fact cannot be attributed
     * to a single edge. The distinction is published rather than smoothed over, because a fact
     * presented as edge-exact when it is span-only is an overstated claim.
     */
    public enum ValidityPrecision {
        EDGE_EXACT, ARTIFACT_VERSION_WINDOW, SPAN_ONLY, UNKNOWN
    }

    private ValidityPrecision validityPrecision = ValidityPrecision.SPAN_ONLY;

    public MigrationFact(String knowledgeId, Type type, String subject) {
        this.knowledgeId = knowledgeId;
        this.type = type;
        this.subject = subject;
    }

    public String knowledgeId() {
        return knowledgeId;
    }

    public Type type() {
        return type;
    }

    public String subject() {
        return subject;
    }

    public String from() {
        return from;
    }

    public MigrationFact from(String value) {
        this.from = value;
        return this;
    }

    public String to() {
        return to;
    }

    public MigrationFact to(String value) {
        this.to = value;
        return this;
    }

    public MigrationFact versions(String sourceVersion, String targetVersion) {
        this.sourceVersion = sourceVersion;
        this.targetVersion = targetVersion;
        if (validFrom == null) {
            this.validFrom = sourceVersion;
        }
        if (validTo == null) {
            this.validTo = targetVersion;
        }
        return this;
    }

    public String component() {
        return component;
    }

    public MigrationFact component(String value) {
        this.component = value == null || value.isBlank() ? "spring-boot" : value;
        return this;
    }

    public String validFrom() {
        return validFrom;
    }

    public String validTo() {
        return validTo;
    }

    /** Narrows the interval in which this fact is true. Always narrower than the whole migration. */
    public MigrationFact validity(String from, String to) {
        this.validFrom = from;
        this.validTo = to;
        return this;
    }

    public MigrationFact validity(String from, String to, ValidityPrecision precision) {
        this.validFrom = from;
        this.validTo = to;
        this.validityPrecision = precision;
        return this;
    }

    public ValidityPrecision validityPrecision() {
        return validityPrecision;
    }

    public List<String> appliesToEdges() {
        return appliesToEdges;
    }

    public MigrationFact appliesToEdge(String edgeId) {
        if (edgeId != null && !appliesToEdges.contains(edgeId)) {
            appliesToEdges.add(edgeId);
        }
        return this;
    }

    /**
     * True when this fact is in force for the edge that moves the application from {@code edgeFrom}
     * to {@code edgeTo}.
     *
     * <p>The test is interval intersection on the component's version line, not string equality: a
     * fact valid across [2.7.18, 3.0.13] authorizes the edge 2.7.18 to 3.0.13 and does not authorize
     * the edge 3.3.13 to 3.4.13.
     *
     * <p>An unknown validity window is not treated as "always true". A fact whose interval cannot be
     * determined applies only to the edge whose target it names, because "unknown means UNKNOWN, not
     * compatible" applies to a fact's scope exactly as it applies to a version's support status.
     */
    public boolean appliesToEdge(String edgeFrom, String edgeTo) {
        if (edgeFrom == null || edgeTo == null) {
            return false;
        }
        String lower = validFrom == null ? sourceVersion : validFrom;
        String upper = validTo == null ? targetVersion : validTo;
        if (lower == null || upper == null) {
            return false;
        }
        // The fact is in force somewhere in (edgeFrom, edgeTo] when the two intervals overlap.
        // compare(a, b) < 0 means a is strictly older than b.
        boolean startsBeforeEdgeEnds = compare(lower, edgeTo) <= 0;
        boolean endsAfterEdgeStarts = compare(upper, edgeFrom) > 0;
        return startsBeforeEdgeEnds && endsAfterEdgeStarts;
    }

    /** Numeric-segment version comparison. Shared with the compatibility registry's ordering. */
    public static int compare(String left, String right) {
        if (left == null || right == null) {
            return 0;
        }
        String[] a = left.split("[.\\-]");
        String[] b = right.split("[.\\-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? numeric(a[i]) : 0;
            int y = i < b.length ? numeric(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int numeric(String token) {
        StringBuilder digits = new StringBuilder();
        for (char c : token.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String sourceVersion() {
        return sourceVersion;
    }

    public String targetVersion() {
        return targetVersion;
    }

    public String summary() {
        return summary;
    }

    public MigrationFact summary(String value) {
        this.summary = value;
        return this;
    }

    public Status status() {
        return status;
    }

    public MigrationFact status(Status value) {
        this.status = value;
        return this;
    }

    public Channel channel() {
        return channel;
    }

    public MigrationFact channel(Channel value) {
        this.channel = value;
        return this;
    }

    public double confidence() {
        return confidence;
    }

    public MigrationFact confidence(double value) {
        this.confidence = value;
        return this;
    }

    public List<String> documentRefs() {
        return documentRefs;
    }

    public MigrationFact document(String ref) {
        documentRefs.add(ref);
        return this;
    }

    public List<String> artifactEvidence() {
        return artifactEvidence;
    }

    public MigrationFact evidence(String value) {
        artifactEvidence.add(value);
        return this;
    }

    public List<String> conflicts() {
        return conflicts;
    }

    public MigrationFact conflict(String value) {
        conflicts.add(value);
        this.status = Status.CONFLICTING;
        return this;
    }

    public String detectionRule() {
        return detectionRule;
    }

    public MigrationFact detectionRule(String value) {
        this.detectionRule = value;
        return this;
    }

    public boolean aiAssisted() {
        return aiAssisted;
    }

    public MigrationFact aiAssisted(boolean value) {
        this.aiAssisted = value;
        return this;
    }

    /**
     * Promotes a fact to VERIFIED. Requires artifact-channel evidence: documentation alone describes
     * intent, artifacts describe reality, and only reality can authorize a code change (R10).
     */
    public MigrationFact verifyWithArtifactEvidence(String evidence) {
        artifactEvidence.add(evidence);
        if (conflicts.isEmpty()) {
            this.status = Status.VERIFIED;
            this.channel = documentRefs.isEmpty() ? Channel.ARTIFACT : Channel.BOTH;
            this.confidence = Math.max(confidence, documentRefs.isEmpty() ? 0.85 : 0.97);
        }
        return this;
    }

    public boolean authorizesTransformation() {
        return status == Status.VERIFIED;
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("knowledge_id", knowledgeId);
        node.put("type", type.name());
        node.put("subject", subject);
        node.put("from", from);
        node.put("to", to);
        node.put("component", component);
        node.put("source_version", sourceVersion);
        node.put("target_version", targetVersion);
        node.put("valid_from", validFrom);
        node.put("valid_to", validTo);
        node.put("validity_precision", validityPrecision.name());
        node.put("summary", summary);
        node.put("status", status.name());
        node.put("channel", channel.name());
        node.put("confidence", confidence);
        node.put("detection_rule", detectionRule);
        node.put("ai_assisted", aiAssisted);
        node.put("authorizes_transformation", authorizesTransformation());
        node.set("document_refs", Json.toTree(documentRefs));
        node.set("artifact_evidence", Json.toTree(artifactEvidence));
        node.set("conflicts", Json.toTree(conflicts));
        node.set("applies_to_edges", Json.toTree(appliesToEdges));
        node.put("verification_status", status.name());
        return node;
    }
}
