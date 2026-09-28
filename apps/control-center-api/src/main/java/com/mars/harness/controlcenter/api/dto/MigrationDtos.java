package com.mars.harness.controlcenter.api.dto;

import java.util.List;
import java.util.Map;

/** The migration cockpit: assessment, plan, rounds and validation, as the capability recorded them. */
public final class MigrationDtos {

    private MigrationDtos() {
    }

    public record PlatformView(String framework, String frameworkVersion, String frameworkLine, String javaVersion,
                               String buildSystem, boolean buildModelAuthoritative, String lifecycleQuality,
                               String supportEnds, Boolean endOfLife, Long supportHorizonMonths) {
    }

    public record TargetView(String framework, String version, String line, String javaVersion, String basis) {
    }

    public record EffortFactorView(String name, double rawValue, double saturation, double weight, int contribution,
                                   boolean known, String note) {
    }

    public record NamedItem(String id, String description, String kind, List<String> evidenceRefs) {
    }

    public record ObjectiveView(String objectiveId, String description, boolean requiresMigration, String requiredPlatform,
                                String why) {
    }

    public record IssueView(String issueId, String ruleId, String referenceSection, boolean mandatory, String category,
                            String subject, String fileId, String location) {
    }

    public record AssessmentView(String assessmentId, String generatedAt, String trafficLight, String trafficLightRationale,
                                 String need, String priority, String complexity, Integer effortScore,
                                 String effortWeightsVersion, List<EffortFactorView> effortFactors,
                                 String evidenceConfidence, List<String> confidenceBasis, PlatformView current,
                                 TargetView recommendedTarget, String recommendedSequence, List<ObjectiveView> objectives,
                                 List<IssueView> issues, List<NamedItem> blockers, List<NamedItem> unknowns,
                                 String referencePackId, String referencePackSha256, List<String> evidenceRefs) {
    }

    public record RuleView(String ruleId, String section, String phase, String description, List<String> symptoms) {
    }

    public record PlanView(String planId, String planHash, String engine, String referencePackId, String referencePackSha256,
                           String framework, String fromVersion, String toVersion, String fromJava, String toJava,
                           List<RuleView> buildFileRules, List<RuleView> symptomRules, List<String> allowedRuleIds,
                           int maxRounds, List<String> stopConditions, String generatedAt, List<String> evidenceRefs) {
    }

    public record BuildErrorView(String file, Integer line, String category, String message) {
    }

    public record TestsView(Integer run, Integer failures, Integer errors, Integer skipped) {
    }

    public record RoundView(int round, String label, String intent, String outcome, int exitCode, long durationMs,
                            String jdk, Map<String, Integer> errorsByCategory, int errorCount,
                            List<BuildErrorView> errors, boolean errorsTruncated, TestsView tests,
                            List<String> appliedRules, List<String> proposalIds, List<String> changeIds, String diagnosis,
                            String logRef, List<String> evidenceRefs) {
    }

    public record ProbeDiffView(String name, String verdict, Integer beforeStatus, Integer afterStatus, String note) {
    }

    public record BehaviourView(String overall, String verdict, List<ProbeDiffView> probes, boolean baselineStarted,
                                boolean finalStarted, List<String> explanations) {
    }

    public record TestComparisonView(String verdict, TestsView before, TestsView after, List<String> newFailures,
                                     List<String> preExistingFailures) {
    }

    public record ExecutionView(String planId, String status, List<RoundView> rounds, List<String> proposalIds,
                                List<String> changeIds, List<String> unmatchedErrors, String needsHumanReason,
                                BehaviourView behaviour, TestComparisonView tests, String reportRef) {
    }

    public record DimensionView(String dimension, String status, String detail, String summary, List<String> evidenceRefs,
                                String producer, boolean mandatory) {
    }

    public record PostSecurityView(String previousTrafficLight, String currentTrafficLight, Integer previousEffortScore,
                                   Integer currentEffortScore, List<String> whatChanged, String why,
                                   String currentAssessmentHash) {
    }

    /**
     * @param status see {@link RunDtos.MigrationSummary#status()}
     * @param blocker the prominent reason migration cannot run, when there is one (stop conditions,
     *                a missing reference pack, a baseline that does not build)
     */
    public record MigrationView(String status, String statusDetail, boolean selected, AssessmentView assessment,
                                AssessmentView refreshedAssessment, PlanView plan, ExecutionView execution,
                                List<DimensionView> validation, PostSecurityView postSecurity, String itemStatus,
                                String itemReason, String blocker, List<ProposalDtos.ProposalRow> proposals) {
    }
}
