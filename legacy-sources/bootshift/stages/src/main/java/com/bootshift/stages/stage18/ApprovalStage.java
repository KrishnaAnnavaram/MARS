package com.bootshift.stages.stage18;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;
import com.bootshift.ports.approval.ApprovalPort;
import com.bootshift.ports.approval.DecisionStore;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 18 - Approval (spec section 36).
 *
 * <p>Collects the judgments the machine must not self-authorize. The stage raises requests from the
 * evidence produced so far, records any decisions an operator has filed, and reports what remains
 * outstanding.
 *
 * <p>Two invariants: an empty rationale is not a decision, and a decision the harness recorded for
 * itself is not an approval. Decisions arrive from outside, through
 * {@code harness approve --decision-file}, and are signed with an integrity hash.
 */
public final class ApprovalStage implements Stage, ApprovalPort {

    public static final String OUTPUT_DIR = "18-approval";
    public static final String DECISIONS_FILE = "approval-decisions.jsonl";

    private final AtomicLong sequence = new AtomicLong();
    private final List<Request> pending = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();
    /** Where decisions actually live. The stage reads them; it never writes one for a human. */
    private DecisionStore store;
    private final List<DecisionStore.StoredDecision> stored = new ArrayList<>();

    @Override
    public String id() {
        return OUTPUT_DIR;
    }

    @Override
    public String outputDirectory() {
        return OUTPUT_DIR;
    }

    @Override
    public String purpose() {
        return "Raise and record the human decisions the harness must not make for itself";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.EDGE_COMPLETE);
    }

    @Override
    public RunState postcondition() {
        return RunState.FINAL_APPROVAL;
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("approval-report.json", "approval-requests.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        store = context.decisions();
        loadDecisions();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        raiseFromEvidence(context);

        List<ObjectNode> requestNodes = new ArrayList<>();
        int satisfied = 0;
        int outstanding = 0;
        for (Request request : pending) {
            Optional<Decision> decision = decisionFor(request.requestId());
            ObjectNode node = Json.obj();
            node.put("request_id", request.requestId());
            node.put("gate", request.gate().name());
            node.put("scope", request.scope());
            node.put("summary", request.summary());
            node.set("evidence_refs", Json.toTree(request.evidenceRefs()));
            node.put("raised_by", request.raisedBy());
            node.put("raised_at", request.raisedAt());
            node.put("satisfied", decision.isPresent());
            decision.ifPresent(d -> {
                node.put("decision_id", d.decisionId());
                node.put("verdict", d.verdict().name());
                node.put("actor", d.actor());
                node.put("role", d.role());
            });
            requestNodes.add(node);
            if (decision.isPresent()) {
                satisfied++;
            } else {
                outstanding++;
            }
        }

        ObjectNode requests = Json.obj();
        requests.put("request_count", pending.size());
        requests.put("satisfied", satisfied);
        requests.put("outstanding", outstanding);
        requests.set("requests", Json.toTree(requestNodes));
        requests.put("how_to_decide", "bootshift approve --request <REQUEST_ID> --actor <name> "
                + "--role <role> --verdict APPROVED --rationale \"...\"");
        writer.write("approval-requests.json", StageSupport.compose(envelope, requests));

        ObjectNode report = Json.obj();
        report.put("decision_count", decisions.size());
        report.put("outstanding_requests", outstanding);
        report.put("rule", "An empty rationale is not a decision. The harness never approves itself.");
        report.put("decision_sources", store == null ? "none" : store.describeSources());
        report.put("integrity_note", "integrity_hash detects modification of a stored decision. It is "
                + "not a signature and does not authenticate the actor; actor identity is locally "
                + "asserted unless a stored decision says otherwise.");
        List<ObjectNode> integrity = new ArrayList<>();
        if (store != null) {
            for (DecisionStore.IntegrityCheck check : store.verifyIntegrity()) {
                ObjectNode node = Json.obj();
                node.put("decision_id", check.decisionId());
                node.put("intact", check.intact());
                node.put("detail", check.detail());
                integrity.add(node);
            }
        }
        report.set("integrity_checks", Json.toTree(integrity));
        long tampered = integrity.stream().filter(n -> !n.path("intact").asBoolean(true)).count();
        report.put("decisions_failing_integrity", tampered);
        ArrayNode decisionArray = Json.arr();
        for (Decision decision : decisions) {
            ObjectNode node = Json.obj();
            node.put("decisionId", decision.decisionId());
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
            node.put("integrity_hash", decision.integrityHash());
            storedFor(decision.decisionId()).ifPresent(record -> {
                node.put("integrity_algorithm", record.integrityAlgorithm());
                node.put("actor_authentication", record.authentication().name());
                node.put("source_ref", record.sourceRef());
            });
            decisionArray.add(node);
        }
        report.set("decisions", decisionArray);
        ObjectNode reportArtifact = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR)
                        .stat("outstanding", outstanding)
                        .stat("decisions", decisions.size()), report);
        StageSupport.validate(context, writer, "evidence/approval-report.schema.json",
                "approval-report.json", reportArtifact);
        writer.write("approval-report.json", reportArtifact);

        StageSupport.toEvidence(context, "approval-report", reportArtifact,
                EvidenceManifest.Classification.CONFIDENTIAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        String hash = StageSupport.publish(context, writer);

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        if (outstanding > 0) {
            context.stateMachine().transition(RunState.NEEDS_HUMAN,
                    outstanding + " approval(s) outstanding");
            context.runStateStore().updateState(context.run().runId(), RunState.NEEDS_HUMAN,
                    "approvals outstanding");
            List<String> messages = new ArrayList<>();
            pending.stream().filter(r -> decisionFor(r.requestId()).isEmpty())
                    .forEach(r -> messages.add(r.requestId() + " [" + r.gate() + "] " + r.summary()));
            return new StageResult(OUTPUT_DIR, ExitCode.HUMAN_DECISION_REQUIRED,
                    outstanding + " approval gate(s) require an authorized decision before the "
                            + "migration can complete",
                    messages, artifacts, hash);
        }

        context.stateMachine().transition(RunState.FINAL_APPROVAL,
                decisions.size() + " decision(s) recorded");
        context.runStateStore().updateState(context.run().runId(), RunState.FINAL_APPROVAL,
                "approvals complete");

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                pending.size() + " gate(s) evaluated, " + satisfied + " satisfied, "
                        + decisions.size() + " decision(s) on record",
                List.of(), artifacts, hash);
    }

    // ------------------------------------------------------------------ gate discovery

    /** Derives approval requests from what the evidence actually shows. */
    private void raiseFromEvidence(StageContext context) {
        JsonNode differential = StageSupport.optionalUpstream(context, "17-differential",
                "differential-report.json");
        if (differential != null) {
            for (JsonNode comparison : differential.path("comparisons")) {
                String classification = comparison.path("classification").asText();
                String dimension = comparison.path("dimension").asText();
                if ("UNEXPECTED".equals(classification) || "UNEXPLAINED".equals(classification)) {
                    Gate gate = switch (dimension) {
                        case "SECURITY_AUTHORIZATION" -> Gate.INTENTIONAL_SECURITY_CHANGE;
                        case "PERSISTENCE_STATE", "QUERY_RESULT", "TRANSACTION_EFFECT" ->
                                Gate.PERSISTENCE_SCHEMA_CHANGE;
                        case "BUSINESS_RULE_OUTCOME" -> Gate.BUSINESS_OUTCOME_CHANGE;
                        default -> Gate.EVIDENCE_SHORTFALL;
                    };
                    raise(gate, dimension + "/" + comparison.path("module").asText(),
                            classification + " difference in " + dimension + ": "
                                    + comparison.path("detail").asText(),
                            List.of("17-differential/differential-report.json"), OUTPUT_DIR);
                }
            }
        }

        JsonNode internal = StageSupport.optionalUpstream(context, "05-compatibility",
                "internal-components.json");
        if (internal != null && internal.path("unknown_count").asInt() > 0) {
            raise(Gate.UNSUPPORTED_INTERNAL_STARTER, "internal-components",
                    internal.path("unknown_count").asInt() + " internal component(s) have UNKNOWN "
                            + "compatibility and were never proven safe for the target",
                    List.of("05-compatibility/internal-components.json"), OUTPUT_DIR);
        }

        JsonNode plan = StageSupport.optionalUpstream(context, "11-plan", "edge-plan.json");
        if (plan != null) {
            for (JsonNode decision : plan.path("reconciliation_decisions")) {
                if (decision.asText().startsWith("COLLAPSE_WITH_ESCALATED_VALIDATION")) {
                    raise(Gate.CHECKPOINT_COLLAPSE, "migration-plan", decision.asText(),
                            List.of("11-plan/edge-plan.json"), OUTPUT_DIR);
                }
            }
        }

        JsonNode coverage = StageSupport.optionalUpstream(context, "15-test", "coverage-report.json");
        if (coverage != null) {
            for (JsonNode module : coverage.path("modules")) {
                if (module.path("gate_triggered").asBoolean(false)) {
                    raise(Gate.COVERAGE_REGRESSION, module.path("module").asText(),
                            "Coverage dropped " + module.path("percentage_point_change").asDouble()
                                    + " percentage points in " + module.path("module").asText(),
                            List.of("15-test/coverage-report.json"), OUTPUT_DIR);
                }
            }
        }

        JsonNode repair = StageSupport.optionalUpstream(context, "13-build-repair", "repair-report.json");
        if (repair != null && repair.path("ai_attempts_used").asInt() > 0) {
            raise(Gate.HIGH_RISK_AI_PATCH, "compile-repair",
                    repair.path("ai_attempts_used").asInt() + " AI-authored repair proposal(s) were "
                            + "accepted; every one requires human sign-off before completion",
                    List.of("13-build-repair/repair-report.json"), OUTPUT_DIR);
        }

        JsonNode target = StageSupport.optionalUpstream(context, "06-target", "target-state.json");
        if (target != null
                && target.path("support_horizon_months").asLong(999)
                < context.policy().minimumSupportHorizonMonths() * 2L) {
            raise(Gate.SHORT_HORIZON_TARGET, target.path("landing_version").asText(),
                    "The landing target has only "
                            + target.path("support_horizon_months").asLong()
                            + " month(s) of open-source support remaining",
                    List.of("06-target/target-resolution-report.json"), OUTPUT_DIR);
        }
    }

    // ------------------------------------------------------------------ ApprovalPort

    @Override
    public Request raise(Gate gate, String scope, String summary, List<String> evidenceRefs,
                         String raisedBy) {
        String requestId = "REQ-" + gate.name() + "-"
                + Hashing.sha256(gate.name() + scope).substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        Optional<Request> existing = pending.stream()
                .filter(r -> r.requestId().equals(requestId)).findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Request request = new Request(requestId, gate, scope, summary, evidenceRefs, raisedBy,
                Instant.now().toString());
        pending.add(request);
        return request;
    }

    /**
     * Files a decision into the store on behalf of a named human operator.
     *
     * <p>This is the CLI path: a person ran {@code bootshift approve} and supplied their name, their
     * role and a rationale. The harness records what they said; it does not decide anything. The
     * refusals below are the mechanism - no actor and no rationale means no decision, so a machine
     * calling this with blank fields cannot manufacture an approval.
     */
    @Override
    public Decision record(String requestId, String actor, String role, Verdict verdict,
                           String rationale, List<String> evidenceRefs, String policyVersion) {
        if (rationale == null || rationale.isBlank()) {
            throw HarnessException.refusal("A decision without a rationale is not a decision. "
                    + "Provide --rationale.");
        }
        if (actor == null || actor.isBlank()) {
            throw HarnessException.refusal("A decision requires a named actor.");
        }
        if (store == null) {
            throw HarnessException.refusal("No decision store is bound; call bind(context) first.");
        }
        String scope = pending.stream().filter(r -> r.requestId().equals(requestId))
                .map(Request::scope).findFirst().orElse(requestId);
        Gate gate = pending.stream().filter(r -> r.requestId().equals(requestId))
                .map(Request::gate).findFirst().orElse(Gate.EVIDENCE_SHORTFALL);
        DecisionStore.StoredDecision recorded = store.record(requestId, gate, scope, actor, role,
                verdict, rationale, evidenceRefs, policyVersion, "cli:bootshift approve");
        stored.add(recorded);
        decisions.add(recorded.decision());
        return recorded.decision();
    }

    @Override
    public Optional<Decision> decisionFor(String requestId) {
        return decisions.stream()
                .filter(d -> d.requestId().equals(requestId))
                .reduce((first, second) -> second);
    }

    @Override
    public List<Request> pending() {
        return List.copyOf(pending);
    }

    @Override
    public List<Decision> decisions() {
        return List.copyOf(decisions);
    }

    /** Binds the port to the run's decision store so decisions can be filed outside a stage run. */
    public ApprovalStage bind(StageContext context) {
        this.store = context.decisions();
        loadDecisions();
        raiseFromEvidence(context);
        return this;
    }

    /** Reads decisions from the store. The stage is a reader here, never a writer. */
    private void loadDecisions() {
        decisions.clear();
        stored.clear();
        if (store == null) {
            return;
        }
        for (DecisionStore.StoredDecision record : store.all()) {
            stored.add(record);
            decisions.add(record.decision());
            sequence.incrementAndGet();
        }
    }

    /** The stored form of a decision, when one exists, so its authentication level can be reported. */
    public java.util.Optional<DecisionStore.StoredDecision> storedFor(String decisionId) {
        return stored.stream()
                .filter(d -> d.decision().decisionId().equals(decisionId))
                .findFirst();
    }

    public List<DecisionStore.StoredDecision> storedDecisions() {
        return List.copyOf(stored);
    }
}
