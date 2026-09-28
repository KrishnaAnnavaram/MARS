package com.mars.harness.kernel.core.event;

/**
 * Every kind of execution event the harness emits. Each constant names an operation the harness
 * actually performs; there is no type for work it does not do.
 *
 * <p>{@code capabilityReportable} marks the types a capability pack may report about its own
 * work through the {@code ActivityReporter} port. Gate, decision, mutation, state and verdict
 * events are kernel-only: a capability can describe what it is doing, but it cannot claim that a
 * human decided, that source changed, or that the run moved.
 */
public enum ExecutionEventType {

    // ---------------------------------------------------------------- run lifecycle (kernel)
    RUN_CREATED(Category.KERNEL, false),
    STATE_TRANSITION(Category.KERNEL, false),
    ADVANCE_STARTED(Category.KERNEL, false),
    ADVANCE_STOPPED(Category.KERNEL, false),
    RUN_COMPLETED(Category.KERNEL, false),
    RUN_FAILED(Category.ERROR, false),

    // ---------------------------------------------------------------- phases 0 to 3 (kernel)
    INGEST_STARTED(Category.KERNEL, false),
    INGEST_STAGE_COMPLETED(Category.KERNEL, false),
    INGEST_COMPLETED(Category.KERNEL, false),
    INVENTORY_COMPLETED(Category.KERNEL, false),
    IDENTITY_STARTED(Category.KERNEL, false),
    IDENTITY_COMPLETED(Category.KERNEL, false),
    GRAPH_BUILD_STARTED(Category.KERNEL, false),
    GRAPH_BUILD_COMPLETED(Category.KERNEL, false),
    GRAPH_REBUILT(Category.KERNEL, false),
    BASELINE_BUILD_STARTED(Category.KERNEL, false),
    BASELINE_BUILD_COMPLETED(Category.KERNEL, false),
    BASELINE_PROBE_STARTED(Category.KERNEL, false),
    BASELINE_PROBE_COMPLETED(Category.KERNEL, false),
    BASELINE_SEALED(Category.KERNEL, false),

    // ---------------------------------------------------------------- phase 4: discovery
    DISCOVERY_STARTED(Category.KERNEL, false),
    DISCOVERY_COMPLETED(Category.KERNEL, false),
    SECURITY_DISCOVERY_STARTED(Category.SECURITY, false),
    SECURITY_SCAN_COMPLETED(Category.SECURITY, true),
    RCA_STARTED(Category.SECURITY, true),
    RCA_COMPLETED(Category.SECURITY, true),
    BLAST_RADIUS_STARTED(Category.SECURITY, true),
    BLAST_RADIUS_COMPLETED(Category.SECURITY, true),
    SECURITY_DISCOVERY_COMPLETED(Category.SECURITY, false),
    MIGRATION_ASSESSMENT_STARTED(Category.MIGRATION, false),
    MIGRATION_ASSESSMENT_COMPLETED(Category.MIGRATION, false),
    SEQUENCE_ASSESSMENT_COMPLETED(Category.KERNEL, false),

    // ---------------------------------------------------------------- humans
    HUMAN_ACTION_REQUIRED(Category.HUMAN, false),
    DECISION_RECORDED(Category.HUMAN, false),

    // ---------------------------------------------------------------- migration
    MIGRATION_PLAN_CREATED(Category.MIGRATION, false),
    MIGRATION_EXECUTION_STARTED(Category.MIGRATION, false),
    MIGRATION_ROUND_STARTED(Category.MIGRATION, true),
    MIGRATION_ROUND_COMPLETED(Category.MIGRATION, true),
    MIGRATION_RULE_APPLIED(Category.MIGRATION, true),
    MIGRATION_EXECUTION_COMPLETED(Category.MIGRATION, false),
    MIGRATION_VALIDATION_STARTED(Category.VALIDATION, false),
    MIGRATION_VALIDATION_COMPLETED(Category.VALIDATION, false),
    POST_SECURITY_REASSESSMENT_STARTED(Category.MIGRATION, false),
    POST_SECURITY_REASSESSMENT_COMPLETED(Category.MIGRATION, false),

    // ---------------------------------------------------------------- security remediation
    REMEDIATION_PLANNING_STARTED(Category.SECURITY, false),
    REMEDIATION_ROUTED(Category.SECURITY, true),
    REMEDIATION_PLANNING_COMPLETED(Category.SECURITY, false),
    FIX_VERIFICATION_STARTED(Category.VALIDATION, false),
    FIX_VERIFICATION_COMPLETED(Category.VALIDATION, false),

    // ---------------------------------------------------------------- mutation gateway
    PROPOSAL_REGISTERED(Category.MUTATION, false),
    MUTATION_STARTED(Category.MUTATION, false),
    MUTATION_APPLIED(Category.MUTATION, false),
    MUTATION_REFUSED(Category.MUTATION, false),
    MUTATION_ROLLED_BACK(Category.ERROR, false),
    MUTATION_BYPASS_DETECTED(Category.ERROR, false),
    PROPOSAL_VALIDATION_RECORDED(Category.VALIDATION, false),

    // ---------------------------------------------------------------- final validation and verdict
    FINAL_VALIDATION_STARTED(Category.VALIDATION, false),
    FINAL_VALIDATION_COMPLETED(Category.VALIDATION, false),
    VERDICT_COMPUTED(Category.KERNEL, false);

    /** Coarse grouping for filtering. It is derived from the type and never set independently. */
    public enum Category { KERNEL, MIGRATION, SECURITY, HUMAN, MUTATION, VALIDATION, ERROR }

    private final Category category;
    private final boolean capabilityReportable;

    ExecutionEventType(Category category, boolean capabilityReportable) {
        this.category = category;
        this.capabilityReportable = capabilityReportable;
    }

    public Category category() {
        return category;
    }

    public boolean capabilityReportable() {
        return capabilityReportable;
    }
}
