package com.mars.harness.kernel.engine.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.engine.run.RunSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What a human must do for a run to continue, derived only from the run's persisted facts: the
 * state, the combined assessment, the proposals and their decisions, the verdict and the
 * migration execution. Nothing here decides; it describes the gate the run is already stopped at.
 */
public final class HumanGates {

    private HumanGates() {
    }

    public static Optional<ExecutionEvent.HumanAction> describe(RunSession session) {
        RunPhase phase = session.record.machine.current;
        return switch (phase) {
            case WAITING_FOR_EXECUTION_DECISION -> {
                JsonNode combined = read(session.layout.area("discovery").resolve("combined-assessment.json"));
                List<String> options = new ArrayList<>();
                if (combined != null) {
                    combined.path("offered_strategies").forEach(o -> options.add(o.asText()));
                }
                if (options.isEmpty()) {
                    Arrays.stream(Decision.ExecutionStrategy.values()).forEach(s -> options.add(s.name()));
                }
                String recommended = combined == null ? null : combined.path("recommended_strategy").asText(null);
                yield Optional.of(new ExecutionEvent.HumanAction("GATE_A", Decision.DecisionType.EXECUTION_STRATEGY.name(),
                        "Human Gate A: choose an execution strategy" + (recommended == null ? ""
                                : " (MARS recommends " + recommended + "; the recommendation authorizes nothing)"), options));
            }
            case WAITING_FOR_MIGRATION_APPROVAL -> Optional.of(new ExecutionEvent.HumanAction("MIGRATION_PLAN",
                    Decision.DecisionType.MIGRATION_PLAN_APPROVAL.name(), "Policy requires migration plan "
                    + session.record.migrationPlanId + " to be approved before any migration rule runs", verdicts()));
            case WAITING_FOR_REMEDIATION_APPROVAL -> {
                List<String> undecided = undecidedProposals(session);
                yield Optional.of(new ExecutionEvent.HumanAction("GATE_B", Decision.DecisionType.PROPOSAL_APPROVAL.name(),
                        "Human Gate B: " + undecided.size() + " remediation proposal(s) await a decision: " + undecided,
                        verdicts()));
            }
            case WAITING_FOR_POST_SECURITY_MIGRATION_DECISION -> {
                JsonNode reassessment = read(session.layout.area("discovery").resolve("migration")
                        .resolve("post-security-reassessment.json"));
                String light = reassessment == null ? "unknown" : reassessment.path("current_traffic_light").asText("unknown");
                yield Optional.of(new ExecutionEvent.HumanAction("GATE_A2",
                        Decision.DecisionType.POST_SECURITY_MIGRATION.name(), "Human Gate A2: migration was re-assessed "
                        + "after the security work and is " + light,
                        Arrays.stream(Decision.MigrationChoice.values()).map(Enum::name).toList()));
            }
            case NEEDS_HUMAN -> Optional.of(new ExecutionEvent.HumanAction("NEEDS_HUMAN", null, needsHumanReason(session),
                    List.of()));
            default -> Optional.empty();
        };
    }

    private static List<String> verdicts() {
        return Arrays.stream(Decision.ApprovalVerdict.values()).map(Enum::name).toList();
    }

    static List<String> undecidedProposals(RunSession session) {
        return session.proposals.all().stream()
                .filter(p -> p.capability() != ChangeProposal.Capability.MIGRATION)
                .filter(p -> {
                    String status = session.record.proposalStatus.get(p.proposalId());
                    return "PROPOSED".equals(status) || "AWAITING_APPROVAL".equals(status);
                })
                .filter(p -> session.approvals.latestForProposal(p.proposalId()).isEmpty())
                .map(ChangeProposal::proposalId).toList();
    }

    private static String needsHumanReason(RunSession session) {
        JsonNode verdict = session.record.verdict == null ? null
                : read(session.layout.area("reports").resolve("verdict.json"));
        if (verdict != null) {
            JsonNode pending = verdict.path("pending_decisions");
            List<String> reasons = new ArrayList<>();
            verdict.path("reasons").forEach(r -> reasons.add(r.asText()));
            return "Verdict NEEDS_HUMAN" + (pending.isEmpty() ? "" : ": " + pending.size() + " decision(s) outstanding")
                    + (reasons.isEmpty() ? "" : " — " + String.join("; ", reasons.subList(0, Math.min(3, reasons.size()))));
        }
        JsonNode execution = read(session.layout.area("plans").resolve("migration-execution.json"));
        if (execution != null && "NEEDS_HUMAN".equals(execution.path("status").asText())) {
            return "Migration needs a human: " + execution.path("needs_human_reason").asText();
        }
        return session.record.notes.isEmpty() ? "A human action is required"
                : session.record.notes.get(session.record.notes.size() - 1);
    }

    private static JsonNode read(Path file) {
        return Files.isRegularFile(file) ? KernelJson.read(file) : null;
    }
}
