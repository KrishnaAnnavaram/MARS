package com.mars.harness.kernel.core.identity;

import java.util.List;

/**
 * Why an identity is attached where it is (spec §7.4). Every reattachment that is not exact must
 * carry method, confidence, evidence and old and new locations. Exact ones carry them too, so the
 * lineage is complete.
 *
 * @param candidates for an ambiguous or weak match, the identities that were considered and not
 *                   attached, each rendered as {@code id@score}
 */
public record ReattachmentEvidence(String changeId, ReattachmentMethod method, double confidence,
                                   Certainty certainty, String evidence, Location oldLocation,
                                   Location newLocation, List<String> candidates, String at) {

    public ReattachmentEvidence {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public boolean uncertain() {
        return certainty == Certainty.LOW;
    }
}
