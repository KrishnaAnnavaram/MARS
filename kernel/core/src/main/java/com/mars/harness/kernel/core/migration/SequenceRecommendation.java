package com.mars.harness.kernel.core.migration;

import java.util.List;

/**
 * Sequence advice (spec §24). Advice only: the developer's decision is authoritative.
 */
public record SequenceRecommendation(Sequence sequence, String rationale, List<String> drivingFindings,
                                     List<String> evidenceRefs) {

    public SequenceRecommendation {
        drivingFindings = drivingFindings == null ? List.of() : List.copyOf(drivingFindings);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }

    public enum Sequence { MIGRATE_FIRST, SECURITY_FIRST, NO_SEQUENCE_REQUIRED, INSUFFICIENT_EVIDENCE }
}
