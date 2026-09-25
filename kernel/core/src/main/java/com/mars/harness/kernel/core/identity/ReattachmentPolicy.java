package com.mars.harness.kernel.core.identity;

/**
 * Thresholds for non-exact reattachment. They live in versioned policy
 * ({@code policies/default/unified-policy.json}); nothing here is a magic number in the algorithm.
 *
 * @param statementHigh   similarity at or above which a statement match is HIGH certainty
 * @param statementLow    similarity at or above which a statement match is attached as LOW
 *                        (uncertain); below this a new STATEMENT_ID is allocated
 * @param symbolHigh      body similarity for a HIGH-certainty renamed-method match
 * @param symbolLow       body similarity for a LOW-certainty renamed-method match
 * @param unitSimilarity  member-signature similarity for a renamed or split program unit
 * @param ambiguityMargin the best candidate must beat the runner-up by at least this much, or
 *                        the match is ambiguous and nothing is attached
 */
public record ReattachmentPolicy(double statementHigh, double statementLow, double symbolHigh,
                                 double symbolLow, double unitSimilarity, double ambiguityMargin) {

    public static ReattachmentPolicy defaults() {
        return new ReattachmentPolicy(0.80, 0.60, 0.85, 0.70, 0.60, 0.10);
    }

    public Certainty statementCertainty(double score) {
        if (score >= 0.999) {
            return Certainty.EXACT;
        }
        if (score >= statementHigh) {
            return Certainty.HIGH;
        }
        return score >= statementLow ? Certainty.LOW : Certainty.NONE;
    }

    public Certainty symbolCertainty(double score) {
        if (score >= 0.999) {
            return Certainty.EXACT;
        }
        if (score >= symbolHigh) {
            return Certainty.HIGH;
        }
        return score >= symbolLow ? Certainty.LOW : Certainty.NONE;
    }
}
