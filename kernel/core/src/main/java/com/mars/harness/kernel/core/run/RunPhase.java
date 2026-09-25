package com.mars.harness.kernel.core.run;

/**
 * Unified run states (spec §25).
 *
 * <p>{@code mutating} marks states that imply tracked source may change. Entering one before the
 * baseline seal exists is refused, the same invariant Bootshift's StateMachine enforces (R7).
 */
public enum RunPhase {
    CREATED(false),
    SOURCE_SNAPSHOTTED(false),
    INVENTORY_READY(false),
    IDENTITY_SEALED(false),
    GRAPH_READY(false),
    BASELINE_SEALED(false),
    DISCOVERY_RUNNING(false),
    DISCOVERY_READY(false),
    WAITING_FOR_EXECUTION_DECISION(false),
    EXECUTION_PLANNED(false),

    MIGRATION_PLANNED(false),
    WAITING_FOR_MIGRATION_APPROVAL(false),
    MIGRATION_RUNNING(true),
    MIGRATION_VALIDATING(true),
    MIGRATION_COMPLETE(true),

    SECURITY_FINDINGS_READY(false),
    SECURITY_ANALYSIS_RUNNING(false),
    REMEDIATION_PROPOSED(false),
    WAITING_FOR_REMEDIATION_APPROVAL(false),
    REMEDIATION_RUNNING(true),
    SECURITY_VALIDATING(true),
    SECURITY_COMPLETE(true),

    WAITING_FOR_POST_SECURITY_MIGRATION_DECISION(false),

    FINAL_VALIDATION(false),
    NEEDS_HUMAN(false),
    BLOCKED(false),
    PARTIAL(false),
    CLEARED(false),
    COMPLETE(false),
    FAILED(false);

    private final boolean mutating;

    RunPhase(boolean mutating) {
        this.mutating = mutating;
    }

    public boolean mutating() {
        return mutating;
    }

    public boolean waitingForHuman() {
        return this == WAITING_FOR_EXECUTION_DECISION || this == WAITING_FOR_MIGRATION_APPROVAL
                || this == WAITING_FOR_REMEDIATION_APPROVAL || this == WAITING_FOR_POST_SECURITY_MIGRATION_DECISION
                || this == NEEDS_HUMAN;
    }

    public boolean terminal() {
        return this == COMPLETE || this == FAILED;
    }
}
