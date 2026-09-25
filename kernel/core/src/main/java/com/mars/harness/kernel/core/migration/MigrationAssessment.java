package com.mars.harness.kernel.core.migration;

import java.util.List;

/**
 * Advisory migration assessment (spec §11). Every dimension is separate: urgency (traffic light
 * and priority), complexity, effort and evidence confidence are never collapsed into one score.
 *
 * <p>The assessment never authorizes anything. A RED light is an explanation, not a permission.
 */
public record MigrationAssessment(String assessmentId, String runId, String policyVersion, String generatedAt,
                                  TrafficLight trafficLight, String trafficLightRationale,
                                  MigrationNeed need, Priority priority, Complexity complexity,
                                  EffortScore effort, EvidenceConfidence evidenceConfidence,
                                  List<String> confidenceBasis, CurrentPlatform current,
                                  RecommendedTarget recommendedTarget, String recommendedSequence,
                                  List<ObjectiveImpact> objectives, List<MigrationIssue> issues,
                                  List<String> evidence, List<Unknown> unknowns, List<Blocker> blockers,
                                  String referencePackId, String referencePackSha256) {

    public MigrationAssessment {
        confidenceBasis = confidenceBasis == null ? List.of() : List.copyOf(confidenceBasis);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        issues = issues == null ? List.of() : List.copyOf(issues);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    /** Spec §11.2, exact semantics. */
    public enum TrafficLight {
        /** Migration is not currently required for the requested objectives. */
        GREEN,
        /** Migration is recommended or beneficial but not a hard prerequisite. */
        YELLOW,
        /** Migration is a prerequisite for at least one specific requested objective (named). */
        RED,
        /** Not enough trustworthy evidence to classify. Never coerced into GREEN. */
        UNKNOWN
    }

    public enum MigrationNeed { NOT_REQUIRED, RECOMMENDED, PREREQUISITE, UNKNOWN }

    public enum Priority { NONE, LOW, MEDIUM, HIGH, CRITICAL, UNKNOWN }

    /** Spec §11.3. */
    public enum Complexity { TRIVIAL, LOW, MODERATE, HIGH, REARCHITECTURE, UNKNOWN }

    /** Spec §11.4: confidence in the <em>evidence</em>, not model confidence. */
    public enum EvidenceConfidence { HIGH, MEDIUM, LOW, INSUFFICIENT }

    /**
     * Deterministic 0–100 effort score (spec §11.5). It is not a probability. Its factors are
     * decomposable and their weights come from versioned policy.
     */
    public record EffortScore(int migrationEffortScore, String weightsVersion, List<EffortFactor> factors,
                              String method) {
        public EffortScore {
            factors = factors == null ? List.of() : List.copyOf(factors);
        }
    }

    /**
     * @param rawValue     the observed quantity (for example, 12 removed-API usages)
     * @param normalized   rawValue scaled to [0, 1] against the policy saturation point
     * @param weight       the policy weight, in points
     * @param contribution weight × normalized, rounded; the sum over factors is the score
     */
    public record EffortFactor(String name, double rawValue, double saturation, double normalized, double weight,
                               int contribution, boolean known, List<String> evidenceRefs, String note) {
        public EffortFactor {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record CurrentPlatform(String framework, String frameworkVersion, String frameworkLine,
                                  String javaVersion, String buildSystem, boolean buildModelAuthoritative,
                                  String lifecycleQuality, String supportEnds, Boolean endOfLife,
                                  Long supportHorizonMonths, List<String> evidenceRefs) {
        public CurrentPlatform {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record RecommendedTarget(String framework, String version, String line, String javaVersion,
                                    String basis, List<String> evidenceRefs) {
        public RecommendedTarget {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /**
     * How migration relates to one requested objective (usually a finding to remediate).
     *
     * @param requiresMigration true only when evidence shows the objective is not achievable on
     *                          the current platform line
     */
    public record ObjectiveImpact(String objectiveId, String description, boolean requiresMigration,
                                  String requiredPlatform, String why, List<String> evidenceRefs) {
        public ObjectiveImpact {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    /** A concrete migration issue observed in the code (mandatory or optional), with its rule. */
    public record MigrationIssue(String issueId, String ruleId, String referenceSection, boolean mandatory,
                                 String category, String subject, String fileId, String symbolId,
                                 String statementId, String location, List<String> evidenceRefs) {
        public MigrationIssue {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record Unknown(String dimension, String question, String impact, List<String> evidenceRefs) {
        public Unknown {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public record Blocker(String blockerId, String description, String kind, List<String> evidenceRefs) {
        public Blocker {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }
}
