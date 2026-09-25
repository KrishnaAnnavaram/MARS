package com.mars.harness.kernel.core.verdict;

import java.util.List;

/**
 * The final verdict and per-item statuses (spec §22).
 *
 * <p>A hard failed gate is never auto-overridden to CLEARED. Unknown or unexecuted dimensions
 * never count as passed. {@code reasons} traces each conclusion to evidence.
 */
public record Verdict(String runId, Outcome outcome, String generatedAt, List<ItemResult> items,
                      List<String> reasons, List<String> hardFailures, List<String> unknownDimensions,
                      List<String> pendingDecisions, List<String> evidenceRefs, String policyVersion) {

    public Verdict {
        items = items == null ? List.of() : List.copyOf(items);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        hardFailures = hardFailures == null ? List.of() : List.copyOf(hardFailures);
        unknownDimensions = unknownDimensions == null ? List.of() : List.copyOf(unknownDimensions);
        pendingDecisions = pendingDecisions == null ? List.of() : List.copyOf(pendingDecisions);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }

    public enum Outcome { CLEARED, BLOCKED, PARTIAL, NEEDS_HUMAN, INSUFFICIENT_EVIDENCE }

    /** Per-finding and per-capability status (spec §22). */
    public enum ItemStatus {
        FIXED,
        STILL_VULNERABLE,
        BLOCKED_BY_PLATFORM,
        DEFERRED_BY_DEVELOPER,
        REJECTED_BY_DEVELOPER,
        NOT_APPLICABLE,
        NOT_COMPARED,
        PENDING_APPROVAL,
        NEEDS_HUMAN,
        INSUFFICIENT_EVIDENCE,
        FAILED,
        MIGRATED,
        MIGRATION_DECLINED,
        MIGRATION_INCOMPLETE,
        NOT_IN_SCOPE
    }

    /**
     * @param legacyVerdict the preserved capability-native verdict: VRH {@code Cleared|Blocked}
     *                      with its score and threshold, or the migration round outcome
     */
    public record ItemResult(String itemId, String kind, ItemStatus status, String legacyVerdict, String reason,
                             List<String> evidenceRefs) {
        public ItemResult {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }
}
