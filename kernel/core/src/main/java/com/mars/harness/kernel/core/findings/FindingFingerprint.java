package com.mars.harness.kernel.core.findings;

import com.bootshift.core.util.Hashing;

import java.util.Locale;

/**
 * Stable, identity-aware finding fingerprints.
 *
 * <p>SARIF's lesson (partialFingerprints) is that a finding must survive irrelevant line movement.
 * Anchoring to allocated identities goes further: the fingerprint survives line movement, edits
 * elsewhere in the file, file rename, and migration, because none of those change the
 * STATEMENT_ID or SYMBOL_ID.
 *
 * <p>The fingerprint uses the most specific anchor available (statement, then symbol, then file,
 * then source id) and names it, so a coarse match is never mistaken for a precise one.
 */
public final class FindingFingerprint {

    private FindingFingerprint() {
    }

    public static String of(String ruleOrCwe, String statementId, String symbolId, String fileId,
                            String sourceFindingId) {
        String rule = ruleOrCwe == null ? "unknown-rule" : ruleOrCwe.toUpperCase(Locale.ROOT);
        String anchor;
        if (statementId != null) {
            anchor = "stmt:" + statementId;
        } else if (symbolId != null) {
            anchor = "sym:" + symbolId;
        } else if (fileId != null) {
            anchor = "file:" + fileId;
        } else {
            anchor = "src:" + sourceFindingId;
        }
        return anchor.substring(0, anchor.indexOf(':')) + "/" + Hashing.sha256(rule + "|" + anchor).substring(0, 24);
    }

    /** Anchor precision: STATEMENT > SYMBOL > FILE > UNANCHORED. */
    public static String quality(String statementId, String symbolId, String fileId) {
        if (statementId != null) {
            return "STATEMENT";
        }
        if (symbolId != null) {
            return "SYMBOL";
        }
        return fileId != null ? "FILE" : "UNANCHORED";
    }
}
