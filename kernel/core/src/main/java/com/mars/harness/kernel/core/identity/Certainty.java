package com.mars.harness.kernel.core.identity;

/**
 * How certain a reattachment is.
 *
 * <p>{@link #LOW} is attached but explicitly marked uncertain, so reports and lineage queries show
 * it as such. An ambiguous or too-weak mapping is never attached. It allocates a new identity and
 * records the candidates as {@link #NONE}, which is what "never silently attach ambiguous identity"
 * means in practice.
 */
public enum Certainty {
    /** Exact evidence: provider mapping, identical fingerprint, identical signature. */
    EXACT,
    /** Strong non-exact evidence above the high threshold. */
    HIGH,
    /** Attached, but uncertain: between the low and high thresholds. Always flagged. */
    LOW,
    /** Not attached: a new identity was allocated, and candidates, if any, are recorded. */
    NONE;

    public boolean uncertain() {
        return this == LOW;
    }
}
