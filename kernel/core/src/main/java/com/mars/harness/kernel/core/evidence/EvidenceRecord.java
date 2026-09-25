package com.mars.harness.kernel.core.evidence;

import java.util.List;

/**
 * One grounded fact (spec §11.6, §37). Every recommendation, decision and verdict statement in the
 * harness cites EVIDENCE_IDs, and each record answers the grounding questions:
 *
 * <ul>
 *   <li>what was observed ({@code observed})</li>
 *   <li>where ({@code where}, with FILE / SYMBOL / STATEMENT subjects)</li>
 *   <li>which rule, lifecycle fact or reference supports it ({@code basis})</li>
 *   <li>how fresh and reliable it is ({@code observedAt}, {@code asOf}, {@code reliability})</li>
 * </ul>
 *
 * <p>Unknowns are recorded as evidence of kind {@link EvidenceKind#UNKNOWN}, never omitted.
 * Logs are not evidence: an evidence record points at an artifact by path and hash.
 */
public record EvidenceRecord(String evidenceId, String runId, EvidenceKind kind, String summary,
                             String observed, String where, List<String> subjects, String basis,
                             Reliability reliability, String observedAt, String asOf, String artifactRef,
                             String artifactSha256, String producer) {

    public EvidenceRecord {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
    }

    public enum EvidenceKind {
        /** Directly observed in the repository or build (a version, an import, a file). */
        OBSERVATION,
        /** Output of an external tool the harness ran (compiler, test runner, scanner). */
        TOOL_RESULT,
        /** A curated lifecycle or compatibility fact (Bootshift LifecycleSource). */
        LIFECYCLE_FACT,
        /** A rule from a reference pack, CWE catalog, KB or policy. */
        REFERENCE_RULE,
        /** An imported finding from an external source (Excel register, SARIF). */
        IMPORTED_FINDING,
        /** A human decision artifact. */
        DECISION,
        /** A validation dimension result. */
        VALIDATION,
        /** A declared-but-unknown fact: what the harness could not establish. */
        UNKNOWN
    }

    /** How far the fact can be trusted. The first four mirror Bootshift's lifecycle qualities. */
    public enum Reliability {
        VERIFIED,
        ADVISORY,
        ESTIMATED,
        UNKNOWN,
        /** Read from a descriptor without the build tool confirming it (for example, a raw pom). */
        DECLARED,
        /** Derived by a deterministic rule from other evidence. */
        DERIVED,
        /** Asserted by a human; integrity-checked, identity locally asserted. */
        ASSERTED
    }
}
