package com.mars.harness.kernel.core.validation;

/**
 * Status of one validation dimension.
 *
 * <p>Only {@link #PASS} counts as passed. {@link #NOT_RUN}, {@link #TOOL_UNAVAILABLE},
 * {@link #NOT_COMPARED} and {@link #INSUFFICIENT_EVIDENCE} are never converted to PASS, by any
 * component (spec §22: "Never convert unknown/unexecuted dimensions into PASS").
 */
public enum DimensionStatus {
    PASS,
    FAIL,
    /** Ran, and the result is acceptable only with the differences explained by recorded evidence or decisions. */
    PASS_WITH_EXPLAINED_DIFFERENCES,
    NOT_RUN,
    NOT_APPLICABLE,
    NOT_COMPARED,
    TOOL_UNAVAILABLE,
    INSUFFICIENT_EVIDENCE;

    public boolean passed() {
        return this == PASS || this == PASS_WITH_EXPLAINED_DIFFERENCES;
    }

    public boolean failed() {
        return this == FAIL;
    }

    /** Neither passed nor failed: the evidence does not exist. */
    public boolean unknown() {
        return this == NOT_RUN || this == NOT_COMPARED || this == TOOL_UNAVAILABLE || this == INSUFFICIENT_EVIDENCE;
    }
}
