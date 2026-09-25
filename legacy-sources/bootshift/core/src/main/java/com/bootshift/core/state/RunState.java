package com.bootshift.core.state;

/**
 * Explicit run states (spec section 53).
 *
 * <p>The state machine says <em>where</em> a run is. It is never the source of truth for
 * <em>what is true</em> - that is the artifact plane (R23).
 */
public enum RunState {

    CREATED(false),
    WORKSPACE_READY(false),
    OSS_POLICY_VERIFIED(false),
    INVENTORY_COMPLETE(false),
    FILE_REGISTRY_SEALED(false),
    BUILD_RESOLVED(false),
    APPLICATION_GRAPH_BUILT(false),
    GRAPH_VERIFIED(false),
    BASELINE_CAPTURED(false),
    BASELINE_SEALED(false),
    COMPATIBILITY_REGISTRY_READY(false),
    TARGET_RESOLVED(false),
    TARGET_FROZEN(false),
    DOCUMENTATION_RETRIEVED(false),
    KNOWLEDGE_VERIFIED(false),
    IMPACT_ANALYZED(false),
    CHARACTERIZATION_COMPLETE(false),
    PLAN_FROZEN(false),

    EDGE_TRANSFORMED(true),
    EDGE_COMPILED(true),
    EDGE_GRAPH_REBUILT(true),
    EDGE_SCOPE_VERIFIED(true),
    EDGE_TESTED(true),
    EDGE_RUNTIME_VALIDATED(true),
    EDGE_RUNTIME_GRAPH_ENRICHED(true),
    EDGE_DIFFERENTIAL_VALIDATED(true),
    EDGE_COMPLETE(true),

    FINAL_APPROVAL(true),
    EVIDENCE_SEALED(true),
    MIGRATION_COMPLETE(true),

    // side and failure states
    FAILED(false),
    BLOCKED(false),
    NEEDS_HUMAN(false),
    REPAIRING(true),
    ROLLED_BACK(true),
    CANCELLED(false);

    private final boolean mutating;

    RunState(boolean mutating) {
        this.mutating = mutating;
    }

    /** True when reaching this state implies source mutation has begun (gated by R7). */
    public boolean isMutating() {
        return mutating;
    }

    public boolean isTerminal() {
        return this == MIGRATION_COMPLETE || this == FAILED || this == BLOCKED
                || this == NEEDS_HUMAN || this == CANCELLED;
    }
}
