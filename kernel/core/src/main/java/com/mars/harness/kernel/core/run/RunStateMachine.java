package com.mars.harness.kernel.core.run;

import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The unified run state machine (spec §25).
 *
 * <p>It says <em>where</em> a run is. The artifact plane says what is true (Bootshift ADR-002). On
 * resume the engine verifies that the artifacts proving the current state exist before trusting
 * the cursor.
 *
 * <p>Two invariants are enforced here, not left to convention:
 *
 * <ul>
 *   <li>transitions must be declared</li>
 *   <li>no mutating state before the baseline seal</li>
 * </ul>
 */
public final class RunStateMachine {

    public record Transition(RunPhase from, RunPhase to, String reason, String at) {
    }

    private static final Map<RunPhase, Set<RunPhase>> ALLOWED = new EnumMap<>(RunPhase.class);

    static {
        allow(RunPhase.CREATED, RunPhase.SOURCE_SNAPSHOTTED);
        allow(RunPhase.SOURCE_SNAPSHOTTED, RunPhase.INVENTORY_READY);
        allow(RunPhase.INVENTORY_READY, RunPhase.IDENTITY_SEALED);
        allow(RunPhase.IDENTITY_SEALED, RunPhase.GRAPH_READY);
        allow(RunPhase.GRAPH_READY, RunPhase.BASELINE_SEALED);
        allow(RunPhase.BASELINE_SEALED, RunPhase.DISCOVERY_RUNNING);
        allow(RunPhase.DISCOVERY_RUNNING, RunPhase.DISCOVERY_READY);
        allow(RunPhase.DISCOVERY_READY, RunPhase.WAITING_FOR_EXECUTION_DECISION);
        allow(RunPhase.WAITING_FOR_EXECUTION_DECISION, RunPhase.EXECUTION_PLANNED, RunPhase.COMPLETE);
        allow(RunPhase.EXECUTION_PLANNED, RunPhase.MIGRATION_PLANNED, RunPhase.SECURITY_FINDINGS_READY,
                RunPhase.FINAL_VALIDATION, RunPhase.COMPLETE);

        allow(RunPhase.MIGRATION_PLANNED, RunPhase.WAITING_FOR_MIGRATION_APPROVAL, RunPhase.MIGRATION_RUNNING,
                RunPhase.SECURITY_FINDINGS_READY, RunPhase.FINAL_VALIDATION);
        allow(RunPhase.WAITING_FOR_MIGRATION_APPROVAL, RunPhase.MIGRATION_RUNNING, RunPhase.FINAL_VALIDATION,
                RunPhase.SECURITY_FINDINGS_READY);
        allow(RunPhase.MIGRATION_RUNNING, RunPhase.MIGRATION_VALIDATING);
        allow(RunPhase.MIGRATION_VALIDATING, RunPhase.MIGRATION_COMPLETE, RunPhase.MIGRATION_RUNNING);
        allow(RunPhase.MIGRATION_COMPLETE, RunPhase.SECURITY_FINDINGS_READY, RunPhase.FINAL_VALIDATION);

        allow(RunPhase.SECURITY_FINDINGS_READY, RunPhase.SECURITY_ANALYSIS_RUNNING, RunPhase.FINAL_VALIDATION);
        allow(RunPhase.SECURITY_ANALYSIS_RUNNING, RunPhase.REMEDIATION_PROPOSED, RunPhase.SECURITY_COMPLETE);
        allow(RunPhase.REMEDIATION_PROPOSED, RunPhase.WAITING_FOR_REMEDIATION_APPROVAL, RunPhase.SECURITY_COMPLETE);
        allow(RunPhase.WAITING_FOR_REMEDIATION_APPROVAL, RunPhase.REMEDIATION_RUNNING, RunPhase.SECURITY_COMPLETE);
        allow(RunPhase.REMEDIATION_RUNNING, RunPhase.SECURITY_VALIDATING);
        allow(RunPhase.SECURITY_VALIDATING, RunPhase.SECURITY_COMPLETE, RunPhase.WAITING_FOR_REMEDIATION_APPROVAL);
        allow(RunPhase.SECURITY_COMPLETE, RunPhase.WAITING_FOR_POST_SECURITY_MIGRATION_DECISION,
                RunPhase.MIGRATION_PLANNED, RunPhase.FINAL_VALIDATION);

        allow(RunPhase.WAITING_FOR_POST_SECURITY_MIGRATION_DECISION, RunPhase.MIGRATION_PLANNED,
                RunPhase.FINAL_VALIDATION, RunPhase.COMPLETE);

        allow(RunPhase.FINAL_VALIDATION, RunPhase.CLEARED, RunPhase.PARTIAL, RunPhase.BLOCKED,
                RunPhase.NEEDS_HUMAN, RunPhase.COMPLETE);
        for (RunPhase verdict : List.of(RunPhase.CLEARED, RunPhase.PARTIAL, RunPhase.BLOCKED, RunPhase.NEEDS_HUMAN)) {
            allow(verdict, RunPhase.COMPLETE);
        }
        // Any non-terminal state may fail, and any state waiting on evidence may escalate to a human.
        for (RunPhase phase : RunPhase.values()) {
            if (!phase.terminal()) {
                ALLOWED.computeIfAbsent(phase, k -> EnumSet.noneOf(RunPhase.class)).add(RunPhase.FAILED);
            }
        }
        for (RunPhase phase : EnumSet.of(RunPhase.MIGRATION_RUNNING, RunPhase.MIGRATION_VALIDATING,
                RunPhase.REMEDIATION_RUNNING, RunPhase.SECURITY_VALIDATING, RunPhase.MIGRATION_PLANNED)) {
            ALLOWED.get(phase).add(RunPhase.NEEDS_HUMAN);
        }
        // NEEDS_HUMAN mid-execution is resumable back into the phase that escalated.
        allow(RunPhase.NEEDS_HUMAN, RunPhase.MIGRATION_RUNNING, RunPhase.REMEDIATION_RUNNING,
                RunPhase.FINAL_VALIDATION, RunPhase.SECURITY_FINDINGS_READY, RunPhase.MIGRATION_COMPLETE,
                RunPhase.SECURITY_COMPLETE, RunPhase.COMPLETE);
    }

    private static void allow(RunPhase from, RunPhase... to) {
        ALLOWED.computeIfAbsent(from, k -> EnumSet.noneOf(RunPhase.class)).addAll(List.of(to));
    }

    public RunPhase current = RunPhase.CREATED;
    public boolean baselineSealed;
    public String baselineSealHash;
    public List<Transition> history = new ArrayList<>();

    public boolean canTransition(RunPhase to) {
        return ALLOWED.getOrDefault(current, Set.of()).contains(to);
    }

    public Transition transition(RunPhase to, String reason) {
        if (current == to) {
            Transition rerun = new Transition(current, to, "RE-ENTRY: " + reason, Instant.now().toString());
            history.add(rerun);
            return rerun;
        }
        if (!canTransition(to)) {
            throw new HarnessOutcomeException(OutcomeCategory.REFUSAL,
                    "Illegal run transition " + current + " -> " + to + "; declared successors: "
                            + ALLOWED.getOrDefault(current, Set.of()));
        }
        if (to.mutating() && !baselineSealed) {
            throw new HarnessOutcomeException(OutcomeCategory.BASELINE_INVALID,
                    "Refusing to enter mutating state " + to + " before the baseline is sealed");
        }
        Transition t = new Transition(current, to, reason, Instant.now().toString());
        history.add(t);
        current = to;
        return t;
    }

    public void recordBaselineSeal(String hash) {
        if (baselineSealed && !hash.equals(baselineSealHash)) {
            throw new HarnessOutcomeException(OutcomeCategory.BASELINE_INVALID,
                    "Refusing to replace the baseline seal within a run; a new baseline needs a new RUN_ID");
        }
        baselineSealed = true;
        baselineSealHash = hash;
    }

    public boolean reached(RunPhase phase) {
        return current == phase || history.stream().anyMatch(t -> t.to() == phase);
    }

    public static Set<RunPhase> successorsOf(RunPhase phase) {
        return Set.copyOf(ALLOWED.getOrDefault(phase, Set.of()));
    }
}
