package com.mars.harness.kernel.core.identity;

/**
 * Reattachment methods in descending order of trust (spec §7.4).
 *
 * <p>Declaration order is the order in which the reattacher tries them. {@link #ALLOCATED_NEW} is
 * the fallback, and {@link #BASELINE} marks the first allocation.
 */
public enum ReattachmentMethod {
    BASELINE,
    /** 1. The transformation provider said explicitly which old node became which new node. */
    PROVIDER_MAPPING,
    /** Program unit or symbol: identical fully qualified name or signature. */
    EXACT_NAME,
    /** 2. AST-diff sequence alignment: identical node, same position in the aligned sequence. */
    AST_DIFF,
    /** 2b. AST-diff update: the aligned slot holds the same node kind with changed contents. */
    AST_DIFF_UPDATE,
    /** 3. Identical normalized fingerprint under the same parent, at a different position. */
    EXACT_FINGERPRINT,
    /** Symbol: same name and kind, changed parameter list. */
    SIGNATURE_CHANGED,
    /** Program unit: the only unit of its kind in a file whose FILE_ID lineage is known. */
    FILE_LINEAGE,
    /** 4. Structural or token similarity under the same or reattached parent. */
    STRUCTURAL_SIMILARITY,
    /** 5. Contextual: matched across parents (a moved method or statement). */
    CONTEXTUAL,
    /** 6. No acceptable evidence: new identity. */
    ALLOCATED_NEW;

    public boolean exact() {
        return this == BASELINE || this == PROVIDER_MAPPING || this == EXACT_NAME || this == AST_DIFF
                || this == EXACT_FINGERPRINT;
    }
}
