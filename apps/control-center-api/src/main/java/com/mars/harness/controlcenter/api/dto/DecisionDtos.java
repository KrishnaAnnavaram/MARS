package com.mars.harness.controlcenter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Human decisions: what was recorded, and the commands that record them. */
public final class DecisionDtos {

    private DecisionDtos() {
    }

    /**
     * A recorded decision as the approval store holds it.
     *
     * @param integrity          VERIFIED when the stored integrity hash still matches the content,
     *                           TAMPERED otherwise. Integrity is not authentication.
     * @param actorAuthentication how the entry point established the actor (LOCALLY_ASSERTED,
     *                           DEVELOPMENT_ASSERTED, OIDC_AUTHENTICATED:&lt;issuer&gt;)
     * @param authenticationNote what that means, in plain words
     */
    public record DecisionView(String decisionId, String type, String selected, String recommendation, String proposalId,
                               String proposalHash, String planId, String planHash, String assessmentHash,
                               String baselineSeal, String ledgerHead, List<String> findingIds, String actor, String role,
                               String actorAuthentication, String authenticationNote, String rationale, String timestamp,
                               String policyVersion, String integrityHash, String integrity, boolean superseded) {
    }

    /** Gate A. {@code expectedAssessmentHash} is the combined-assessment hash the reviewer saw. */
    public record ExecutionDecisionRequest(@NotBlank String strategy, @NotBlank @Size(max = 4000) String rationale,
                                           @NotBlank String expectedAssessmentHash) {
    }

    /** Gate A2. {@code expectedAssessmentHash} is the post-security reassessment hash the reviewer saw. */
    public record PostSecurityDecisionRequest(@NotBlank String choice, @NotBlank @Size(max = 4000) String rationale,
                                              @NotBlank String expectedAssessmentHash) {
    }

    public record MigrationPlanDecisionRequest(@NotBlank String verdict, @NotBlank @Size(max = 4000) String rationale,
                                               @NotBlank String expectedPlanHash) {
    }

    /**
     * Gate B for one exact proposal.
     *
     * @param expectedProposalHash the hash the reviewer inspected; a changed proposal is refused
     * @param supersedesDecisionId required to replace an existing decision (optimistic concurrency):
     *                             it must name the proposal's current latest decision
     */
    public record ProposalDecisionRequest(@NotBlank String verdict, @NotBlank @Size(max = 4000) String rationale,
                                          @NotBlank String expectedProposalHash, String supersedesDecisionId) {
    }

    /**
     * What the API returns once the engine recorded a decision.
     *
     * @param advancing true when recording the decision also started advancing the run (Gate A, A2,
     *                  plan approval); Gate B decisions leave advancement to an explicit resume
     */
    public record DecisionRecorded(DecisionView decision, boolean advancing, String next) {
    }
}
