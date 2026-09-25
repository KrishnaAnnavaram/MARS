package com.mars.harness.kernel.core.outcome;

/**
 * Explicit outcome categories for expected workflow conditions (spec §35).
 *
 * <p>These are deliberately not collapsed into a generic failure. A policy block, a missing
 * approval and an unavailable tool call for different operator actions.
 */
public enum OutcomeCategory {
    FAILURE,
    REFUSAL,
    POLICY_BLOCK,
    NEEDS_HUMAN,
    INSUFFICIENT_EVIDENCE,
    BLOCKED_BY_PLATFORM,
    TOOL_UNAVAILABLE,
    BASELINE_INVALID,
    STALE_PROPOSAL,
    AUTHORIZATION_MISSING,
    VALIDATION_FAILED;

    /** Process exit code, compatible with Bootshift's ExitCode where the meaning overlaps. */
    public int exitCode() {
        return switch (this) {
            case FAILURE, TOOL_UNAVAILABLE -> 1;
            case REFUSAL, STALE_PROPOSAL, BASELINE_INVALID -> 2;
            case POLICY_BLOCK, BLOCKED_BY_PLATFORM, VALIDATION_FAILED -> 3;
            case NEEDS_HUMAN, AUTHORIZATION_MISSING, INSUFFICIENT_EVIDENCE -> 4;
        };
    }
}
