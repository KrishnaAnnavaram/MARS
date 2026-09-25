package com.mars.harness.kernel.ports.migration;

import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.capability.CapabilityContext;
import com.mars.harness.kernel.ports.mutation.ProposalSink;

import java.util.List;
import java.util.Map;

/**
 * The migration capability contract (spec §13). {@link #assess} is read-only and advisory. It
 * never authorises migration, whatever the traffic light says.
 */
public interface MigrationCapability {

    String id();

    MigrationAssessment assess(CapabilityContext context, List<Objective> objectives);

    MigrationPlan plan(CapabilityContext context, MigrationAssessment assessment);

    /**
     * Runs the migration rounds against the kernel's workspace. Every change goes through
     * {@code sink}. {@code previous} carries the rounds of an interrupted execution so a resume
     * continues rather than repeating applied work.
     */
    MigrationExecution execute(CapabilityContext context, MigrationPlan plan, ProposalSink sink,
                               MigrationExecution previous);

    CapabilityValidation validate(CapabilityContext context, MigrationPlan plan, MigrationExecution execution);

    /** A requested objective the migration assessment must weigh (usually a finding to remediate). */
    record Objective(String objectiveId, String kind, String findingId, String description,
                     Finding.Severity severity, Finding.PlatformRequirement requirement) {
    }

    record PlannedRule(String ruleId, String section, String phase, String description, List<String> symptoms) {
        public PlannedRule {
            symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        }
    }

    /**
     * @param allowedRuleIds the frozen allowlist. The kernel authorises execution-level migration
     *                       proposals only for these rules.
     */
    record MigrationPlan(String planId, String runId, String engine, String referencePackId,
                         String referencePackSha256, String framework, String fromVersion, String toVersion,
                         String fromJava, String toJava, List<PlannedRule> buildFileRules,
                         List<PlannedRule> symptomRules, List<String> allowedRuleIds, String baselineIntent,
                         int maxRounds, List<String> evidenceRefs, List<String> stopConditions, String generatedAt) {
        public MigrationPlan {
            buildFileRules = buildFileRules == null ? List.of() : List.copyOf(buildFileRules);
            symptomRules = symptomRules == null ? List.of() : List.copyOf(symptomRules);
            allowedRuleIds = allowedRuleIds == null ? List.of() : List.copyOf(allowedRuleIds);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            stopConditions = stopConditions == null ? List.of() : List.copyOf(stopConditions);
        }
    }

    /** One build round, preserved from the reference workflow's round record. */
    record RoundRecord(int round, String label, String intent, String outcome, int exitCode, long durationMs,
                       String jdk, Map<String, Integer> errorsByCategory, int errorCount,
                       List<BuildPort.BuildError> errors, boolean errorsTruncated, BuildPort.TestSummary tests,
                       List<String> appliedRules, List<String> proposalIds, List<String> changeIds, String diagnosis,
                       String logRef, List<String> evidenceRefs) {
        public RoundRecord {
            errorsByCategory = errorsByCategory == null ? Map.of() : Map.copyOf(errorsByCategory);
            errors = errors == null ? List.of() : List.copyOf(errors);
            appliedRules = appliedRules == null ? List.of() : List.copyOf(appliedRules);
            proposalIds = proposalIds == null ? List.of() : List.copyOf(proposalIds);
            changeIds = changeIds == null ? List.of() : List.copyOf(changeIds);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    record ProbeDiff(String name, String verdict, Integer beforeStatus, Integer afterStatus, String beforeHash,
                     String afterHash, String note) {
    }

    /**
     * @param verdict {@code unchanged}, {@code changed} or {@code not-compared} (reference
     *                vocabulary)
     */
    record RuntimeComparison(String overall, String verdict, List<ProbeDiff> probes, boolean baselineStarted,
                             boolean finalStarted, String baselineRef, String finalRef, List<String> explanations) {
        public RuntimeComparison {
            probes = probes == null ? List.of() : List.copyOf(probes);
            explanations = explanations == null ? List.of() : List.copyOf(explanations);
        }
    }

    record TestComparison(String verdict, BuildPort.TestSummary before, BuildPort.TestSummary after,
                          List<String> newFailures, List<String> preExistingFailures) {
        public TestComparison {
            newFailures = newFailures == null ? List.of() : List.copyOf(newFailures);
            preExistingFailures = preExistingFailures == null ? List.of() : List.copyOf(preExistingFailures);
        }
    }

    enum ExecutionStatus { GREEN, NEEDS_HUMAN, ROUND_LIMIT, BASELINE_BROKEN, TOOL_UNAVAILABLE, STOPPED }

    record MigrationExecution(String planId, ExecutionStatus status, List<RoundRecord> rounds,
                              List<String> proposalIds, List<String> changeIds, List<String> unmatchedErrors,
                              String needsHumanReason, RuntimeComparison behaviour, TestComparison tests,
                              String reportRef, String patchRef, List<String> evidenceRefs) {
        public MigrationExecution {
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
            proposalIds = proposalIds == null ? List.of() : List.copyOf(proposalIds);
            changeIds = changeIds == null ? List.of() : List.copyOf(changeIds);
            unmatchedErrors = unmatchedErrors == null ? List.of() : List.copyOf(unmatchedErrors);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }

        public boolean lastRoundGreen() {
            return !rounds.isEmpty() && "passed".equals(rounds.get(rounds.size() - 1).outcome());
        }
    }

    record CapabilityValidation(List<ValidationResult.DimensionResult> dimensions, List<Verdict.ItemResult> items) {
        public CapabilityValidation {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
