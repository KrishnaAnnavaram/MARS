package com.mars.harness.controlcenter.api.dto;

import java.util.List;
import java.util.Map;

/** Change proposals: what was proposed, decided, applied and validated. */
public final class ProposalDtos {

    private ProposalDtos() {
    }

    /** One row of the change explorer. */
    public record ProposalRow(String proposalId, String capability, String provider, String providerType, String status,
                              boolean strategyOnly, List<String> findingIds, List<String> findingLabels, List<String> files,
                              List<String> operations, String ruleId, String reason, String risk, String generatedAt,
                              String proposalHash, String decision, String decisionId, boolean awaitingDecision,
                              String mutation, String validation) {
    }

    /**
     * @param redacted true when credential-like literals were masked for display; the approval still
     *                 binds to the exact proposal hash, which covers the unmasked content
     */
    public record FileEditView(String fileId, String path, String newPath, String operation, String unifiedDiff,
                               String newContent, boolean newContentTruncated, int addedLines, int removedLines,
                               boolean redacted) {
    }

    public record ProvenanceView(String producer, String ruleId, String referenceSection, String model,
                                 String modelVersion, String promptHash, String contextHash, String responseHash,
                                 List<String> verification) {
    }

    /**
     * What the Mutation Gateway recorded for the proposal, from its events and the lineage ledger.
     *
     * @param status APPLIED, REFUSED, ROLLED_BACK, AWAITING_APPROVAL or NOT_ATTEMPTED
     */
    public record MutationView(String status, String batch, String authorizedBy, List<String> checks, String preCheckpoint,
                               String checkpointId, List<String> changeIds, String identitySync, Integer bypassFiles,
                               String reason, String reasonCode, String at, List<LineageView> lineage) {
    }

    public record LineageView(long sequence, String kind, String changeId, String decisionId, String actor, String fileId,
                              String pathBefore, String pathAfter, String hashBefore, String hashAfter,
                              List<String> symbolIds, List<String> statementIdsChanged, List<String> statementIdsCreated,
                              List<String> statementIdsDeleted, List<String> validationRefs, String status, String at,
                              String reason) {
    }

    /**
     * @param decidable      whether a decision can be recorded now, and {@code decidableReason} why not
     * @param hashVerified   the proposal file still hashes to the value stored next to it at registration
     */
    public record ProposalDetail(String proposalId, String runId, String capability, String provider, String providerType,
                                 String providerVersion, String status, boolean strategyOnly, String reason,
                                 String expectedOutcome, String risk, String generatedAt, String proposalHash,
                                 boolean hashVerified, String baselineSeal, boolean baselineMatches, String supersedes,
                                 List<String> findingIds, List<String> findingLabels, List<String> migrationRefs,
                                 List<String> evidenceRefs, List<String> knowledgeRefs, List<String> affectedFileIds,
                                 List<String> affectedSymbolIds, List<String> affectedStatementIds,
                                 Map<String, String> baseHashes, List<FileEditView> edits, ProvenanceView provenance,
                                 List<DecisionDtos.DecisionView> decisions, MutationView mutation, String validation,
                                 boolean awaitingDecision, boolean decidable, String decidableReason,
                                 List<String> verdictOptions) {
    }

    public record ChangesView(List<ProposalRow> proposals, RunDtos.ChangesSummary summary) {
    }
}
