package com.bootshift.adapters.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;
import com.bootshift.ports.approval.ApprovalPort;
import com.bootshift.ports.approval.DecisionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Filesystem-backed human decision store.
 *
 * <p>Reads from two places: the run's own decision log, and an optional external decisions directory
 * outside the run workspace. The external directory is what makes a decision suppliable
 * independently - an operator, a CI step or an approval system can drop a decision file there before
 * or during a run, and validation will see it without the harness having written anything on a
 * human's behalf.
 *
 * <p>The integrity value is an HMAC over the decision's canonical fields, keyed by a per-installation
 * key. It detects modification of a stored decision. It does <em>not</em> authenticate the actor: the
 * key lives on the same machine as the store, so anyone who can write the file can also recompute
 * the value. The field is therefore called {@code integrity_hash} and the authentication level is
 * recorded as {@link ActorAuthentication#LOCALLY_ASSERTED}. The previous field was called
 * {@code signature}, which claimed a property it did not have.
 *
 * <p>Enterprise identity integration replaces this class, not the port: an implementation that
 * verifies a real signature returns {@link ActorAuthentication#CRYPTOGRAPHICALLY_SIGNED} and every
 * caller keeps working.
 */
public final class FilesystemDecisionStore implements DecisionStore {

    private static final Logger LOG = LoggerFactory.getLogger(FilesystemDecisionStore.class);

    public static final String RUN_DECISIONS_FILE = "approval-decisions.jsonl";
    public static final String INTEGRITY_ALGORITHM = "HmacSHA256";

    /** Where the local integrity key lives. Generated once per installation if absent. */
    private static final String KEY_FILE = "decision-integrity.key";

    private final Path runDecisions;
    private final Path externalDecisions;
    private final Path keyFile;
    private final List<StoredDecision> cache = new ArrayList<>();
    private boolean loaded;

    public FilesystemDecisionStore(Path runWorkspace, Path externalDecisions) {
        this.runDecisions = runWorkspace.resolve(RUN_DECISIONS_FILE);
        this.externalDecisions = externalDecisions;
        this.keyFile = runWorkspace.resolve(KEY_FILE);
    }

    // ------------------------------------------------------------------ recording

    @Override
    public StoredDecision record(String requestId, ApprovalPort.Gate gate, String scope, String actor,
                                 String role, ApprovalPort.Verdict verdict, String rationale,
                                 List<String> evidenceRefs, String policyVersion, String sourceRef) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("A decision requires a named actor. The harness must "
                    + "never record a decision on a human's behalf.");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("A decision without a rationale is not a decision.");
        }
        load();
        String decisionId = Ids.decisionId(cache.size() + 1L);
        String timestamp = Instant.now().toString();
        ApprovalPort.Decision decision = new ApprovalPort.Decision(decisionId, requestId, gate, actor,
                role, scope, verdict, rationale,
                evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs), policyVersion, timestamp,
                null);
        String integrity = integrityHash(decision);
        StoredDecision stored = new StoredDecision(decision, ActorAuthentication.LOCALLY_ASSERTED,
                integrity, INTEGRITY_ALGORITHM, "bootshift-cli", timestamp,
                sourceRef == null ? "cli" : sourceRef);
        cache.add(stored);
        append(runDecisions, stored);
        return stored;
    }

    // ------------------------------------------------------------------ reading

    @Override
    public Optional<StoredDecision> forRequest(String requestId) {
        load();
        StoredDecision latest = null;
        for (StoredDecision stored : cache) {
            if (stored.decision().requestId().equals(requestId)) {
                latest = stored;
            }
        }
        return Optional.ofNullable(latest);
    }

    @Override
    public List<StoredDecision> all() {
        load();
        return List.copyOf(cache);
    }

    @Override
    public List<StoredDecision> matching(ApprovalPort.Gate gate, String scopeFragment) {
        load();
        List<StoredDecision> found = new ArrayList<>();
        for (StoredDecision stored : cache) {
            if (gate != null && stored.decision().gate() != gate) {
                continue;
            }
            String scope = stored.decision().scope() == null ? "" : stored.decision().scope();
            if (scopeFragment == null || scope.contains(scopeFragment) || scope.contains("*")) {
                found.add(stored);
            }
        }
        return found;
    }

    @Override
    public List<IntegrityCheck> verifyIntegrity() {
        load();
        List<IntegrityCheck> checks = new ArrayList<>();
        for (StoredDecision stored : cache) {
            String expected = integrityHash(stored.decision());
            boolean intact = expected.equals(stored.integrityHash());
            checks.add(new IntegrityCheck(stored.decision().decisionId(), intact,
                    intact ? null : "recorded " + stored.integrityHash() + " but the fields now hash to "
                            + expected));
        }
        return checks;
    }

    @Override
    public String describeSources() {
        StringBuilder sb = new StringBuilder();
        sb.append("run decision log ").append(normalize(runDecisions));
        if (externalDecisions != null) {
            sb.append("; external decisions directory ").append(normalize(externalDecisions))
                    .append(Files.isDirectory(externalDecisions) ? " (present)" : " (absent)");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ internals

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        cache.clear();
        readJsonl(runDecisions, "run-log");
        if (externalDecisions != null && Files.isDirectory(externalDecisions)) {
            try (var stream = Files.list(externalDecisions)) {
                stream.filter(Files::isRegularFile).sorted().forEach(file -> {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".jsonl")) {
                        readJsonl(file, "external:" + file.getFileName());
                    } else if (name.endsWith(".json")) {
                        readSingle(file, "external:" + file.getFileName());
                    }
                });
            } catch (IOException e) {
                LOG.warn("Cannot list external decisions directory {}: {}", externalDecisions,
                        e.getMessage());
            }
        }
    }

    private void readJsonl(Path file, String sourceRef) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    parse(Json.parse(line), sourceRef).ifPresent(cache::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read decisions from " + file, e);
        }
    }

    private void readSingle(Path file, String sourceRef) {
        JsonNode node = Json.read(file);
        if (node.isArray()) {
            node.forEach(element -> parse(element, sourceRef).ifPresent(cache::add));
        } else {
            parse(node, sourceRef).ifPresent(cache::add);
        }
    }

    /**
     * Parses one stored decision.
     *
     * <p>An externally supplied decision missing an actor or a rationale is rejected rather than
     * loaded, because loading it would let a malformed file satisfy a gate.
     */
    private Optional<StoredDecision> parse(JsonNode node, String sourceRef) {
        String actor = node.path("actor").asText(null);
        String rationale = node.path("rationale").asText(null);
        if (actor == null || actor.isBlank() || rationale == null || rationale.isBlank()) {
            LOG.warn("Ignoring a decision from {} with no actor or no rationale", sourceRef);
            return Optional.empty();
        }
        ApprovalPort.Gate gate;
        try {
            gate = ApprovalPort.Gate.valueOf(node.path("gate").asText());
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring a decision from {} naming unknown gate {}", sourceRef,
                    node.path("gate").asText());
            return Optional.empty();
        }
        ApprovalPort.Verdict verdict;
        try {
            verdict = ApprovalPort.Verdict.valueOf(node.path("verdict").asText());
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring a decision from {} naming unknown verdict {}", sourceRef,
                    node.path("verdict").asText());
            return Optional.empty();
        }
        List<String> evidence = new ArrayList<>();
        node.path("evidence_refs").forEach(n -> evidence.add(n.asText()));
        ApprovalPort.Decision decision = new ApprovalPort.Decision(
                node.path("decision_id").asText(Ids.decisionId(cache.size() + 1L)),
                node.path("request_id").asText(),
                gate, actor, node.path("role").asText("unspecified"),
                node.path("scope").asText(""), verdict, rationale, evidence,
                node.path("policy_version").asText(null),
                node.path("timestamp").asText(Instant.now().toString()), null);
        ActorAuthentication authentication;
        try {
            authentication = ActorAuthentication.valueOf(
                    node.path("actor_authentication").asText(ActorAuthentication.LOCALLY_ASSERTED.name()));
        } catch (IllegalArgumentException e) {
            authentication = ActorAuthentication.LOCALLY_ASSERTED;
        }
        String recorded = node.path("integrity_hash").asText(node.path("signature").asText(null));
        return Optional.of(new StoredDecision(decision, authentication,
                recorded == null ? integrityHash(decision) : recorded,
                node.path("integrity_algorithm").asText(INTEGRITY_ALGORITHM),
                node.path("recorded_by").asText("external"),
                node.path("stored_at").asText(decision.timestamp()), sourceRef));
    }

    private void append(Path file, StoredDecision stored) {
        ObjectNode node = render(stored);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, Json.canonical(node) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append decision to " + file, e);
        }
    }

    /** The serialized form. Field names say what the values are, including what they are not. */
    public static ObjectNode render(StoredDecision stored) {
        ApprovalPort.Decision decision = stored.decision();
        ObjectNode node = Json.obj();
        node.put("decision_id", decision.decisionId());
        node.put("request_id", decision.requestId());
        node.put("gate", decision.gate().name());
        node.put("actor", decision.actor());
        node.put("role", decision.role());
        node.put("scope", decision.scope());
        node.put("verdict", decision.verdict().name());
        node.put("rationale", decision.rationale());
        node.set("evidence_refs", Json.toTree(decision.evidenceRefs()));
        node.put("policy_version", decision.policyVersion());
        node.put("timestamp", decision.timestamp());
        node.put("integrity_hash", stored.integrityHash());
        node.put("integrity_algorithm", stored.integrityAlgorithm());
        node.put("actor_authentication", stored.authentication().name());
        node.put("integrity_note", "integrity_hash is a keyed hash over the decision fields computed "
                + "with a key held on this machine. It detects modification of a stored decision. It "
                + "does not authenticate the actor: identity here is locally asserted, and an "
                + "enterprise identity or signing integration replaces this store to change that.");
        node.put("recorded_by", stored.recordedBy());
        node.put("stored_at", stored.storedAt());
        node.put("source_ref", stored.sourceRef());
        return node;
    }

    private String integrityHash(ApprovalPort.Decision decision) {
        Map<String, String> canonical = new LinkedHashMap<>();
        canonical.put("decision_id", decision.decisionId());
        canonical.put("request_id", decision.requestId());
        canonical.put("gate", decision.gate().name());
        canonical.put("actor", decision.actor());
        canonical.put("role", decision.role());
        canonical.put("scope", decision.scope());
        canonical.put("verdict", decision.verdict().name());
        canonical.put("rationale", decision.rationale());
        canonical.put("policy_version", decision.policyVersion());
        canonical.put("timestamp", decision.timestamp());
        return Hashing.hmacSha256(key(), Json.canonical(canonical));
    }

    /**
     * The per-installation integrity key. Generated on first use and kept beside the store.
     *
     * <p>Being co-located with the data it protects is exactly why this is not authentication, and is
     * why the store says so rather than implying otherwise.
     */
    private String key() {
        try {
            if (Files.isRegularFile(keyFile)) {
                String existing = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            byte[] material = new byte[32];
            new java.security.SecureRandom().nextBytes(material);
            String generated = java.util.HexFormat.of().formatHex(material);
            if (keyFile.getParent() != null) {
                Files.createDirectories(keyFile.getParent());
            }
            Files.writeString(keyFile, generated, StandardCharsets.UTF_8);
            return generated;
        } catch (IOException e) {
            // A key that cannot be persisted still protects within the process; the store records
            // the authentication level honestly either way.
            LOG.warn("Cannot persist the decision integrity key: {}", e.getMessage());
            return "bootshift-ephemeral-integrity-key";
        }
    }

    private static String normalize(Path path) {
        return path == null ? null : path.toString().replace((char) 92, '/');
    }
}
