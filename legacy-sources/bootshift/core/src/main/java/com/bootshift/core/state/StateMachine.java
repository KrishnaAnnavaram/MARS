package com.bootshift.core.state;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.util.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validated run state machine.
 *
 * <p>Two invariants are enforced here rather than left to convention:
 *
 * <ul>
 *   <li>a transition must be declared legal from the current state;</li>
 *   <li>no transition into a mutating state is permitted unless the baseline seal is present (R7).
 *       This is the mechanism behind the "mutation before baseline" failure-injection test.</li>
 * </ul>
 */
public final class StateMachine {

    public record Transition(RunState from, RunState to, String reason, String at) {
    }

    private static final Map<RunState, Set<RunState>> ALLOWED = new EnumMap<>(RunState.class);

    private static final Set<RunState> FAILURE_STATES = EnumSet.of(
            RunState.FAILED, RunState.BLOCKED, RunState.NEEDS_HUMAN, RunState.CANCELLED);

    static {
        allow(RunState.CREATED, RunState.WORKSPACE_READY);
        allow(RunState.WORKSPACE_READY, RunState.OSS_POLICY_VERIFIED);
        allow(RunState.OSS_POLICY_VERIFIED, RunState.INVENTORY_COMPLETE);
        allow(RunState.INVENTORY_COMPLETE, RunState.FILE_REGISTRY_SEALED);
        allow(RunState.FILE_REGISTRY_SEALED, RunState.BUILD_RESOLVED);
        allow(RunState.BUILD_RESOLVED, RunState.APPLICATION_GRAPH_BUILT);
        allow(RunState.APPLICATION_GRAPH_BUILT, RunState.GRAPH_VERIFIED);
        allow(RunState.GRAPH_VERIFIED, RunState.BASELINE_CAPTURED);
        allow(RunState.BASELINE_CAPTURED, RunState.BASELINE_SEALED);
        allow(RunState.BASELINE_SEALED, RunState.COMPATIBILITY_REGISTRY_READY);
        allow(RunState.COMPATIBILITY_REGISTRY_READY, RunState.TARGET_RESOLVED);
        allow(RunState.TARGET_RESOLVED, RunState.TARGET_FROZEN);
        allow(RunState.TARGET_FROZEN, RunState.DOCUMENTATION_RETRIEVED);
        allow(RunState.DOCUMENTATION_RETRIEVED, RunState.KNOWLEDGE_VERIFIED);
        allow(RunState.KNOWLEDGE_VERIFIED, RunState.IMPACT_ANALYZED);
        allow(RunState.IMPACT_ANALYZED, RunState.CHARACTERIZATION_COMPLETE);
        allow(RunState.CHARACTERIZATION_COMPLETE, RunState.PLAN_FROZEN);

        allow(RunState.PLAN_FROZEN, RunState.EDGE_TRANSFORMED);
        allow(RunState.EDGE_TRANSFORMED, RunState.EDGE_COMPILED, RunState.REPAIRING);
        allow(RunState.REPAIRING, RunState.EDGE_COMPILED, RunState.REPAIRING, RunState.ROLLED_BACK);
        allow(RunState.EDGE_COMPILED, RunState.EDGE_GRAPH_REBUILT);
        allow(RunState.EDGE_GRAPH_REBUILT, RunState.EDGE_SCOPE_VERIFIED);
        allow(RunState.EDGE_SCOPE_VERIFIED, RunState.EDGE_TESTED, RunState.EDGE_RUNTIME_VALIDATED,
                RunState.EDGE_DIFFERENTIAL_VALIDATED, RunState.EDGE_COMPLETE);
        allow(RunState.EDGE_TESTED, RunState.EDGE_RUNTIME_VALIDATED, RunState.EDGE_DIFFERENTIAL_VALIDATED,
                RunState.EDGE_COMPLETE);
        allow(RunState.EDGE_RUNTIME_VALIDATED, RunState.EDGE_RUNTIME_GRAPH_ENRICHED);
        allow(RunState.EDGE_RUNTIME_GRAPH_ENRICHED, RunState.EDGE_DIFFERENTIAL_VALIDATED, RunState.EDGE_COMPLETE);
        allow(RunState.EDGE_DIFFERENTIAL_VALIDATED, RunState.EDGE_COMPLETE);
        allow(RunState.EDGE_COMPLETE, RunState.EDGE_TRANSFORMED, RunState.FINAL_APPROVAL);
        allow(RunState.FINAL_APPROVAL, RunState.EVIDENCE_SEALED);
        allow(RunState.EVIDENCE_SEALED, RunState.MIGRATION_COMPLETE);
        allow(RunState.ROLLED_BACK, RunState.EDGE_TRANSFORMED, RunState.FINAL_APPROVAL);

        // every non-terminal state may fall into a failure state
        for (RunState state : RunState.values()) {
            if (!state.isTerminal()) {
                ALLOWED.computeIfAbsent(state, k -> EnumSet.noneOf(RunState.class)).addAll(FAILURE_STATES);
            }
        }
    }

    private static void allow(RunState from, RunState... to) {
        ALLOWED.computeIfAbsent(from, k -> EnumSet.noneOf(RunState.class)).addAll(List.of(to));
    }

    private RunState current = RunState.CREATED;
    private final List<Transition> history = new ArrayList<>();
    private boolean baselineSealed;
    private String baselineSealHash;

    public RunState current() {
        return current;
    }

    public List<Transition> history() {
        return List.copyOf(history);
    }

    public boolean baselineSealed() {
        return baselineSealed;
    }

    public String baselineSealHash() {
        return baselineSealHash;
    }

    /** Called exactly once by Agent 04 after the baseline manifest is sealed. */
    public void recordBaselineSeal(String manifestHash) {
        if (baselineSealed && !manifestHash.equals(baselineSealHash)) {
            throw HarnessException.block(
                    "Refusing to replace an existing baseline seal within the same run (R30). "
                            + "A new baseline requires a new run identity.");
        }
        this.baselineSealed = true;
        this.baselineSealHash = manifestHash;
    }

    public boolean canTransition(RunState to) {
        return ALLOWED.getOrDefault(current, Set.of()).contains(to);
    }

    /**
     * Records reaching a state, tolerating a stage re-run.
     *
     * <p>Stages are independently runnable (R24), so running {@code graph} twice must not be an
     * error. When the target state has already been reached, the re-entry is recorded in history and
     * the current state is left alone rather than regressing the run.
     */
    public Transition transition(RunState to, String reason) {
        if (current == to || alreadyReached(to)) {
            Transition rerun = new Transition(current, to, "RE-RUN: " + reason, Instant.now().toString());
            history.add(rerun);
            return rerun;
        }
        if (!canTransition(to)) {
            throw new HarnessException(ExitCode.STRUCTURED_REFUSAL,
                    "Illegal state transition " + current + " -> " + to
                            + ". Declared successors: " + ALLOWED.getOrDefault(current, Set.of()));
        }
        if (to.isMutating() && !baselineSealed) {
            throw HarnessException.block(
                    "Refusing to enter mutating state " + to + " before the baseline is sealed (R7). "
                            + "Run the baseline stage first.");
        }
        Transition t = new Transition(current, to, reason, Instant.now().toString());
        history.add(t);
        current = to;
        return t;
    }

    /**
     * True when the run has already progressed at or beyond the given state.
     *
     * <p>Uses declaration order, which is the pipeline order. Failure and edge-loop states are
     * excluded because they are not points on that line.
     */
    private boolean alreadyReached(RunState to) {
        if (FAILURE_STATES.contains(to) || to.isMutating() || current.isMutating()) {
            return history.stream().anyMatch(t -> t.to() == to);
        }
        return to.ordinal() < current.ordinal();
    }

    /** Reconstructs state from validated artifacts rather than trusting a stored cursor (R23). */
    public void restore(RunState state, boolean sealed, String sealHash) {
        this.current = state;
        this.baselineSealed = sealed;
        this.baselineSealHash = sealHash;
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("current_state", current.name());
        node.put("baseline_sealed", baselineSealed);
        node.put("baseline_seal_hash", baselineSealHash);
        node.set("history", Json.toTree(history));
        return node;
    }

    public static Set<RunState> successorsOf(RunState state) {
        return Set.copyOf(ALLOWED.getOrDefault(state, Set.of()));
    }
}
