package com.mars.harness.kernel.adapters.store;

import com.bootshift.core.util.Hashing;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.decision.DecisionValidator;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.ports.approval.ApprovalPort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The authoritative decision store: {@code decisions/DEC-*.json}, write-once.
 *
 * <p>The Markdown next to each decision is a rendering for people and is never read back. That is
 * the fix for VRH's "edit the Status cell" approval: a Markdown edit can no longer authorize
 * anything.
 *
 * <p>Integrity is a keyed hash (HMAC-SHA256) with a run-local key, as in Bootshift's
 * FilesystemDecisionStore. It detects modification and does not authenticate the actor. The
 * actor is recorded as {@code LOCALLY_ASSERTED}.
 */
public final class FilesystemApprovalStore implements ApprovalPort {

    public static final String ACTOR_AUTHENTICATION = "LOCALLY_ASSERTED";

    private final RunLayout layout;
    private final DecisionValidator validator;
    private final String policyVersion;

    public FilesystemApprovalStore(RunLayout layout, DecisionValidator validator, String policyVersion) {
        this.layout = layout;
        this.validator = validator;
        this.policyVersion = policyVersion;
    }

    @Override
    public synchronized Decision record(Decision draft) {
        Decision candidate = new Decision(
                draft.decisionId() == null ? HarnessIds.allocate(HarnessIds.Kind.DECISION) : draft.decisionId(),
                draft.runId(), draft.type(), draft.selected(), draft.recommendation(), draft.proposalId(),
                draft.proposalHash(), draft.findingIds(), draft.affectedFileIds(), draft.affectedSymbolIds(),
                draft.affectedStatementIds(), draft.planId(), draft.planHash(), draft.assessmentHash(),
                draft.baselineSeal(), draft.ledgerHead(), draft.actor() == null ? null : draft.actor().trim(),
                draft.role() == null ? null : draft.role().trim(), ACTOR_AUTHENTICATION,
                draft.rationale() == null ? null : draft.rationale().trim(), Instant.now().toString(),
                draft.policyVersion() == null ? policyVersion : draft.policyVersion(), null);
        List<String> errors = validator.validate(candidate);
        if (!errors.isEmpty()) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL,
                    "Decision refused: " + String.join("; ", errors), errors);
        }
        Decision sealed = candidate.withIntegrity(integrity(candidate));
        Path file = layout.decisions().resolve(sealed.decisionId() + ".json");
        if (Files.exists(file)) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL,
                    "Decision " + sealed.decisionId() + " already exists; decisions are immutable");
        }
        try {
            Files.createDirectories(layout.decisions());
            Files.writeString(file, KernelJson.pretty(sealed), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(layout.decisions().resolve(sealed.decisionId() + ".md"), render(sealed),
                    StandardCharsets.UTF_8);
            Files.writeString(layout.decisions().resolve("index.jsonl"),
                    KernelJson.canonical(java.util.Map.of("decision_id", sealed.decisionId(), "type",
                            sealed.type().name(), "at", sealed.timestamp())) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot record decision " + file, e);
        }
        return sealed;
    }

    @Override
    public Optional<Decision> find(String decisionId) {
        Path file = layout.decisions().resolve(decisionId + ".json");
        return Files.isRegularFile(file) ? Optional.of(KernelJson.read(file, Decision.class)) : Optional.empty();
    }

    @Override
    public List<Decision> all() {
        Path index = layout.decisions().resolve("index.jsonl");
        List<Decision> decisions = new ArrayList<>();
        if (!Files.isRegularFile(index)) {
            return decisions;
        }
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    find(KernelJson.parse(line).path("decision_id").asText()).ifPresent(decisions::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return decisions;
    }

    @Override
    public Optional<Decision> latest(Decision.DecisionType type) {
        List<Decision> matching = all().stream().filter(d -> d.type() == type).toList();
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(matching.size() - 1));
    }

    @Override
    public Optional<Decision> latestForProposal(String proposalId) {
        List<Decision> matching = all().stream()
                .filter(d -> d.type() == Decision.DecisionType.PROPOSAL_APPROVAL && proposalId.equals(d.proposalId()))
                .toList();
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(matching.size() - 1));
    }

    @Override
    public List<String> verifyIntegrity() {
        List<String> tampered = new ArrayList<>();
        try (Stream<Path> files = Files.list(layout.decisions())) {
            files.filter(p -> p.getFileName().toString().matches("DEC-.*\\.json")).forEach(p -> {
                Decision d = KernelJson.read(p, Decision.class);
                Decision unsealed = d.withIntegrity(null);
                if (d.integrityHash() == null || !d.integrityHash().equals(integrity(unsealed))) {
                    tampered.add(d.decisionId());
                }
            });
        } catch (IOException e) {
            if (Files.isDirectory(layout.decisions())) {
                throw new UncheckedIOException(e);
            }
        }
        return tampered;
    }

    private String integrity(Decision unsealed) {
        return Hashing.hmacSha256(key(), KernelJson.canonical(unsealed.withIntegrity(null)));
    }

    private String key() {
        Path keyFile = layout.decisions().resolve(".integrity-key");
        try {
            if (!Files.isRegularFile(keyFile)) {
                Files.createDirectories(keyFile.getParent());
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                Files.writeString(keyFile, HexFormat.of().formatHex(key), StandardCharsets.UTF_8);
            }
            return Files.readString(keyFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot access decision integrity key", e);
        }
    }

    /** Human-readable rendering. Informational only; never parsed. */
    static String render(Decision d) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Decision ").append(d.decisionId()).append("\n\n");
        sb.append("> Rendered from the machine decision `").append(d.decisionId())
                .append(".json`. Editing this Markdown has no effect on the harness.\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        row(sb, "Type", d.type().name());
        row(sb, "Selected", d.selected());
        row(sb, "Harness recommendation", d.recommendation());
        row(sb, "Proposal", d.proposalId());
        row(sb, "Proposal hash", d.proposalHash());
        row(sb, "Plan", d.planId());
        row(sb, "Assessment hash", d.assessmentHash());
        row(sb, "Baseline seal", d.baselineSeal());
        row(sb, "Findings", String.join(", ", d.findingIds()));
        row(sb, "Actor", d.actor() + " (" + d.actorAuthentication() + ")");
        row(sb, "Role", d.role());
        row(sb, "Rationale", d.rationale());
        row(sb, "Timestamp", d.timestamp());
        row(sb, "Policy version", d.policyVersion());
        row(sb, "Integrity (HMAC, not a signature)", d.integrityHash());
        return sb.toString();
    }

    private static void row(StringBuilder sb, String k, String v) {
        if (v != null && !v.isBlank()) {
            sb.append("| ").append(k).append(" | ").append(v.replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
    }
}
