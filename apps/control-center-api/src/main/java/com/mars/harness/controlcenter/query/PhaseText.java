package com.mars.harness.controlcenter.query;

import com.mars.harness.kernel.core.run.RunPhase;

/** Plain-language labels for run states. The state names themselves stay visible next to them. */
public final class PhaseText {

    private PhaseText() {
    }

    public static String label(RunPhase phase) {
        return switch (phase) {
            case CREATED -> "Created";
            case SOURCE_SNAPSHOTTED -> "Source snapshotted";
            case INVENTORY_READY -> "Inventory ready";
            case IDENTITY_SEALED -> "Identity sealed";
            case GRAPH_READY -> "Graph ready";
            case BASELINE_SEALED -> "Baseline sealed";
            case DISCOVERY_RUNNING -> "Discovery running";
            case DISCOVERY_READY -> "Discovery ready";
            case WAITING_FOR_EXECUTION_DECISION -> "Waiting: Gate A (execution strategy)";
            case EXECUTION_PLANNED -> "Execution planned";
            case MIGRATION_PLANNED -> "Migration planned";
            case WAITING_FOR_MIGRATION_APPROVAL -> "Waiting: migration plan approval";
            case MIGRATION_RUNNING -> "Migration running";
            case MIGRATION_VALIDATING -> "Migration validating";
            case MIGRATION_COMPLETE -> "Migration complete";
            case SECURITY_FINDINGS_READY -> "Security findings ready";
            case SECURITY_ANALYSIS_RUNNING -> "Remediation planning";
            case REMEDIATION_PROPOSED -> "Remediation proposed";
            case WAITING_FOR_REMEDIATION_APPROVAL -> "Waiting: Gate B (remediation approvals)";
            case REMEDIATION_RUNNING -> "Applying approved remediation";
            case SECURITY_VALIDATING -> "Verifying fixes";
            case SECURITY_COMPLETE -> "Security complete";
            case WAITING_FOR_POST_SECURITY_MIGRATION_DECISION -> "Waiting: Gate A2 (post-security migration)";
            case FINAL_VALIDATION -> "Final validation";
            case NEEDS_HUMAN -> "Needs human";
            case BLOCKED -> "Blocked";
            case PARTIAL -> "Partial";
            case CLEARED -> "Cleared";
            case COMPLETE -> "Complete";
            case FAILED -> "Failed";
        };
    }

    public static String description(RunPhase phase) {
        return switch (phase) {
            case CREATED -> "The run exists; ingest has not completed.";
            case SOURCE_SNAPSHOTTED -> "An immutable snapshot and an isolated workspace were created.";
            case INVENTORY_READY -> "Every file was registered with a persistent FILE_ID.";
            case IDENTITY_SEALED -> "Modules, program units, symbols and statements have persistent identity.";
            case GRAPH_READY -> "The canonical graph was built over Bootshift's application graph.";
            case BASELINE_SEALED -> "Round 0 ran and the baseline is sealed; nothing may change before this.";
            case DISCOVERY_RUNNING -> "Read-only discovery: scanning, root cause, blast radius, migration assessment.";
            case DISCOVERY_READY -> "Discovery finished without modifying the workspace.";
            case WAITING_FOR_EXECUTION_DECISION -> "MARS stopped at Human Gate A. Nothing executes until a human chooses "
                    + "an execution strategy.";
            case EXECUTION_PLANNED -> "A human chose the strategy; the run is routed accordingly.";
            case MIGRATION_PLANNED -> "A frozen migration plan (reference pack rules) was produced.";
            case WAITING_FOR_MIGRATION_APPROVAL -> "Policy requires the migration plan to be approved before any rule runs.";
            case MIGRATION_RUNNING -> "Reference-pack rounds: build, match failures to pack rules, apply through the "
                    + "Mutation Gateway.";
            case MIGRATION_VALIDATING -> "Migration rounds ended; the capability validates the result.";
            case MIGRATION_COMPLETE -> "The migration phase is done.";
            case SECURITY_FINDINGS_READY -> "Findings are ready for remediation planning.";
            case SECURITY_ANALYSIS_RUNNING -> "Routing each finding (catalog, KB, research) and producing proposals.";
            case REMEDIATION_PROPOSED -> "Remediation proposals were registered; each needs a human decision.";
            case WAITING_FOR_REMEDIATION_APPROVAL -> "MARS stopped at Human Gate B. Each proposal must be approved, "
                    + "rejected or deferred; a missing decision is never an approval.";
            case REMEDIATION_RUNNING -> "Approved proposals are applied through the Mutation Gateway.";
            case SECURITY_VALIDATING -> "Applied fixes are verified: re-scan, red-team, behaviour, QA, build, arbiter.";
            case SECURITY_COMPLETE -> "The security phase is done.";
            case WAITING_FOR_POST_SECURITY_MIGRATION_DECISION -> "Migration was re-assessed after the security work; "
                    + "MARS stopped at Human Gate A2.";
            case FINAL_VALIDATION -> "Unified validation of every dimension, then the verdict.";
            case NEEDS_HUMAN -> "MARS cannot continue without a human.";
            case BLOCKED -> "The verdict is BLOCKED.";
            case PARTIAL -> "The verdict is PARTIAL.";
            case CLEARED -> "The verdict is CLEARED.";
            case COMPLETE -> "The run is complete and its evidence package is sealed.";
            case FAILED -> "The run failed.";
        };
    }

    public static boolean analysisPhase(RunPhase phase) {
        return phase.ordinal() <= RunPhase.DISCOVERY_READY.ordinal();
    }
}
