package com.mars.harness.kernel.core.migration;

import java.util.List;

/**
 * The single evidence-based picture shown at Human Gate A (spec §10). It combines the migration
 * assessment and the security discovery summary, together with how they interact. Its hash is
 * recorded in the execution decision, so a decision taken against a different assessment is
 * detectably stale.
 */
public record CombinedAssessment(String runId, String generatedAt, String migrationAssessmentId,
                                 MigrationAssessment.TrafficLight trafficLight,
                                 MigrationAssessment.Complexity complexity, int effortScore,
                                 MigrationAssessment.EvidenceConfidence evidenceConfidence,
                                 SecuritySummary security, List<Interaction> interactions,
                                 SequenceRecommendation sequence, List<String> offeredStrategies,
                                 String recommendedStrategy, List<String> unknowns) {

    public CombinedAssessment {
        interactions = interactions == null ? List.of() : List.copyOf(interactions);
        offeredStrategies = offeredStrategies == null ? List.of() : List.copyOf(offeredStrategies);
        unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
    }

    public record SecuritySummary(int total, int critical, int high, int medium, int low, int unknownSeverity,
                                  int independentlyRemediable, int platformConstrained, int unanchored,
                                  List<String> sources) {
        public SecuritySummary {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /** A way the two capabilities affect each other (for example, fix B needs Spring Boot 4). */
    public record Interaction(String kind, String findingId, String description, List<String> evidenceRefs) {
        public Interaction {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }
}
