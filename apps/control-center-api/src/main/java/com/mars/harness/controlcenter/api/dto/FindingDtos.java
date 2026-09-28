package com.mars.harness.controlcenter.api.dto;

import java.util.List;
import java.util.Map;

/** Security findings and their remediation journey. */
public final class FindingDtos {

    private FindingDtos() {
    }

    public record LocationView(String path, int lineStart, int lineEnd) {
    }

    /** One row of the findings table. */
    public record FindingRow(String findingId, String sourceFindingId, String source, String severity, List<String> cwe,
                             String title, LocationView location, String anchorQuality, String route, String planId,
                             String proposalId, String proposalStatus, String decision, String verification,
                             String itemStatus, boolean blockedByPlatform, String blastRadiusScope) {
    }

    public record SecurityView(RunDtos.SecuritySummary summary, List<FindingRow> findings, List<String> gaps,
                               List<String> sources, boolean discovered) {
    }

    public record PlatformRequirementView(String component, String currentVersion, String minimumFixedVersion,
                                          String requiresPlatform, String requiresPlatformMinimum, String requiresJavaMinimum,
                                          String basis, List<String> evidenceRefs) {
    }

    public record RootCauseView(String ref, String statement, String location, String fileId, String symbolId,
                                String statementId, String severity, String confidence, List<String> entryPoints,
                                List<String> dataFlow, String howToFix, List<String> evidenceRefs) {
    }

    public record BlastRadiusView(String ref, String priority, String scope, List<String> affectedEndpoints,
                                  List<String> affectedServices, List<String> affectedSymbolIds, String confidence,
                                  List<String> evidenceRefs) {
    }

    public record PlanView(String planId, String issueId, String route, String cwe, String catalogTitle, String owasp,
                           String confidence, String plainSummary, String approach, List<String> antiPatterns,
                           List<String> alternatives, List<String> riskNotes, List<String> verificationPlan,
                           List<String> openQuestions, String researchStatus, String catalogStatus, String kbStatus,
                           boolean blockedByPlatform, String proposalId, boolean strategyOnly, List<String> evidenceRefs) {
    }

    public record VerificationView(String planId, String fixStatus, String rescan, String rescanReason, String redteam,
                                   List<String> attemptedVectors, String behavior, List<String> outOfScopeChanges, String qa,
                                   String build, int score, int threshold, List<String> gatesTriggered, String decision,
                                   List<String> evidenceRefs) {
    }

    /**
     * One step of the remediation journey, only for steps the implementation performs.
     *
     * @param status DONE, CURRENT, WAITING, PENDING, FAILED, SKIPPED or NOT_APPLICABLE
     */
    public record JourneyStep(String id, String label, String status, String detail) {
    }

    public record FindingDetail(String findingId, String sourceFindingId, String source, String ruleId, List<String> cwe,
                                List<String> cve, String severity, String status, String title, String description,
                                LocationView location, List<LocationView> codeFlow, String fileId, String filePath,
                                String programUnitId, String symbolId, String statementId, String anchorQuality,
                                String fingerprint, PlatformRequirementView platformRequirement, List<String> evidenceRefs,
                                RootCauseView rootCause, BlastRadiusView blastRadius, PlanView plan,
                                ProposalDtos.ProposalRow proposal, List<DecisionDtos.DecisionView> decisions,
                                VerificationView verification, String itemStatus, String itemReason,
                                List<JourneyStep> journey, SourceContext sourceContext, Map<String, String> resolution) {
    }

    /** Lines around the anchor, read from the run's own workspace copy (never the original project). */
    public record SourceContext(String path, int firstLine, List<String> lines, int highlightStart, int highlightEnd,
                                String origin) {
    }
}
