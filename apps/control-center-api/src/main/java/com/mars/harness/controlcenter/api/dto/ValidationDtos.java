package com.mars.harness.controlcenter.api.dto;

import java.util.List;

/** Validation and the final verdict, exactly as the validator and the verdict calculator produced them. */
public final class ValidationDtos {

    private ValidationDtos() {
    }

    /**
     * @param finalValidation  the unified validation's dimensions; empty when it has not run
     * @param notRunDimensions the unified dimensions with no result at all (never shown as passing)
     */
    public record ValidationView(String status, String validationId, String scope, String generatedAt,
                                 List<MigrationDtos.DimensionView> finalValidation, List<String> notRunDimensions,
                                 List<MigrationDtos.DimensionView> migrationValidation,
                                 List<FindingDtos.VerificationView> securityVerification,
                                 List<ProposalValidation> proposalValidation, BaselineView baseline) {
    }

    public record ProposalValidation(String proposalId, String status, String capability, List<String> findingIds) {
    }

    public record BaselineView(String seal, String sealedAt, String buildOutcome, Object tests, List<String> preExistingFailures,
                               Boolean runtimeStarted) {
    }

    public record VerdictItemView(String itemId, String label, String kind, String status, String legacyVerdict,
                                  String reason, List<String> evidenceRefs) {
    }

    /**
     * @param available false until the verdict has been computed; nothing here is computed by the API
     */
    public record VerdictView(boolean available, String outcome, String generatedAt, List<String> reasons,
                              List<String> hardFailures, List<String> unknownDimensions, List<String> pendingDecisions,
                              List<VerdictItemView> items, java.util.Map<String, Integer> itemsByStatus,
                              java.util.Map<String, Integer> proposalsByStatus, List<String> reports, String policyVersion,
                              List<String> evidenceRefs) {
    }
}
