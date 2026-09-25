package com.bootshift.core.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sealed evidence manifest (spec sections 37-38, 50).
 *
 * <p>The manifest stays verifiable after large raw captures are pruned, because it records the
 * hashes rather than the payloads. Verification therefore has three outcomes per entry: present and
 * matching, present and mismatched (tampering), or archived (hash retained, payload gone).
 */
public final class EvidenceManifest {

    /** Access classification for a stored evidence object (spec 49). */
    public enum Classification {
        PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED
    }

    public record Entry(String evidenceId, String kind, String relativePath, String sha256,
                        long sizeBytes, Classification classification, String retentionClass,
                        String producedBy, String at) {
    }

    private final String runId;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Claim> claims = new ArrayList<>();
    private final Map<String, String> seals = new LinkedHashMap<>();
    private String manifestHash;
    private String sealedAt;

    public EvidenceManifest(String runId) {
        this.runId = runId;
    }

    public String runId() {
        return runId;
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<Claim> claims() {
        return claims;
    }

    public EvidenceManifest add(Entry entry) {
        entries.add(entry);
        return this;
    }

    public EvidenceManifest claim(Claim claim) {
        claims.add(claim);
        return this;
    }

    /** Records a named cryptographic seal, e.g. baseline manifest hash or ledger head. */
    public EvidenceManifest seal(String name, String hash) {
        seals.put(name, hash);
        return this;
    }

    public Map<String, String> seals() {
        return seals;
    }

    public String manifestHash() {
        return manifestHash;
    }

    /** Computes the manifest hash over entry identities, seals and claim assertions. */
    public String finalizeManifest() {
        List<String> lines = new ArrayList<>();
        entries.forEach(e -> lines.add("E:" + e.evidenceId() + ":" + e.sha256()));
        seals.forEach((k, v) -> lines.add("S:" + k + ":" + v));
        claims.forEach(c -> lines.add("C:" + c.getClaimId() + ":" + c.getLevel().name() + ":"
                + (c.getCoverage() == null ? "NO_COVERAGE" : c.getCoverage().render())));
        lines.sort(java.util.Comparator.naturalOrder());
        this.manifestHash = Hashing.manifestHash(lines);
        this.sealedAt = Instant.now().toString();
        return manifestHash;
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("run_id", runId);
        node.put("manifest_hash", manifestHash);
        node.put("sealed_at", sealedAt);
        node.put("entry_count", entries.size());
        node.set("seals", Json.toTree(seals));
        node.set("entries", Json.toTree(entries));
        node.set("claims", Json.toTree(claims.stream().map(c -> {
            ObjectNode cn = Json.obj();
            cn.put("claim_id", c.getClaimId());
            cn.put("dimension", c.getDimension());
            cn.put("statement", c.getStatement());
            cn.put("evidence_level", c.getLevel().name());
            cn.put("status", c.getStatus());
            cn.put("publishable", c.isPublishable());
            cn.set("coverage", c.getCoverage() == null ? null : Json.toTree(c.getCoverage()));
            cn.put("coverage_rendered", c.getCoverage() == null ? null : c.getCoverage().render());
            cn.set("evidence_refs", Json.toTree(c.getEvidenceRefs()));
            cn.set("approval_refs", Json.toTree(c.getApprovalRefs()));
            cn.set("blind_spot_refs", Json.toTree(c.getBlindSpotRefs()));
            return cn;
        }).toList()));
        return node;
    }

    /** Per-entry verification result. */
    public record EntryVerification(String evidenceId, String outcome, String detail) {
    }

    public record Verification(boolean valid, String computedHash, String recordedHash,
                               List<EntryVerification> entryResults, List<String> violations) {
    }

    /**
     * Verifies a persisted manifest against the evidence store. Missing payloads are reported as
     * ARCHIVED rather than as failures, so retention pruning does not silently invalidate a run.
     */
    public static Verification verify(Path manifestFile, Path evidenceRoot) {
        JsonNode node = Json.read(manifestFile);
        List<EntryVerification> results = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        for (JsonNode entry : node.path("entries")) {
            String id = entry.path("evidenceId").asText();
            String rel = entry.path("relativePath").asText();
            String expected = entry.path("sha256").asText();
            lines.add("E:" + id + ":" + expected);
            Path file = evidenceRoot.resolve(rel);
            if (!Files.isRegularFile(file)) {
                results.add(new EntryVerification(id, "ARCHIVED",
                        "Payload absent; manifest hash retained so the claim stays checkable"));
                continue;
            }
            try {
                String actual = Hashing.sha256File(file);
                if (actual.equals(expected)) {
                    results.add(new EntryVerification(id, "VERIFIED", null));
                } else {
                    results.add(new EntryVerification(id, "TAMPERED",
                            "expected " + expected + " but found " + actual));
                    violations.add("Evidence object " + id + " does not match its recorded hash");
                }
            } catch (IOException e) {
                results.add(new EntryVerification(id, "UNREADABLE", e.getMessage()));
                violations.add("Evidence object " + id + " could not be read: " + e.getMessage());
            }
        }

        node.path("seals").fields().forEachRemaining(e -> lines.add("S:" + e.getKey() + ":" + e.getValue().asText()));
        for (JsonNode claim : node.path("claims")) {
            String rendered = claim.path("coverage_rendered").isNull()
                    || claim.path("coverage_rendered").asText(null) == null
                    ? "NO_COVERAGE" : claim.path("coverage_rendered").asText();
            lines.add("C:" + claim.path("claim_id").asText() + ":"
                    + claim.path("evidence_level").asText() + ":" + rendered);
            if (!claim.path("publishable").asBoolean(false)) {
                violations.add("Claim " + claim.path("claim_id").asText()
                        + " lacks evidence references or a coverage statement");
            }
        }

        lines.sort(java.util.Comparator.naturalOrder());
        String computed = Hashing.manifestHash(lines);
        String recorded = node.path("manifest_hash").asText();
        if (!computed.equals(recorded)) {
            violations.add("Manifest hash mismatch: computed " + computed + " but recorded " + recorded);
        }
        return new Verification(violations.isEmpty(), computed, recorded, results, violations);
    }
}
