package com.mars.harness.kernel.ports.mutation;

import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;

import java.util.List;
import java.util.Set;

/**
 * The single writer of tracked customer source (spec §18, §27 {@code MutationPort}).
 *
 * <p>Exactly one implementation exists. It adds the unified checks (identity scope, human
 * approval, stale proposal, LLM provenance) and then delegates the physical write, registry
 * update, patch, ledger event and checkpoint to Bootshift's {@code FileMutationGateway}.
 */
public interface MutationPort {

    /**
     * Proof that a batch may be applied.
     *
     * @param executionDecision the Gate A or A2 decision that authorised the capability
     *                          (required for deterministic migration rules)
     * @param approvals         Gate B proposal approvals; required for every LLM, manual or
     *                          security proposal
     * @param allowedRuleIds    for execution-authorised migration, the frozen plan's rule allowlist
     */
    record Authorization(String authorizedBy, Decision executionDecision, List<Decision> approvals,
                         Set<String> allowedRuleIds, String planId, String planHash) {
        public Authorization {
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            allowedRuleIds = allowedRuleIds == null ? Set.of() : Set.copyOf(allowedRuleIds);
        }
    }

    record Outcome(String proposalId, ProposalSink.Status status, String reason, List<String> changeIds,
                   List<String> changedFileIds, String checkpointId) {
        public Outcome {
            changeIds = changeIds == null ? List.of() : List.copyOf(changeIds);
            changedFileIds = changedFileIds == null ? List.of() : List.copyOf(changedFileIds);
        }
    }

    List<Outcome> apply(Authorization authorization, List<ChangeProposal> proposals);

    /** Writes that did not come through the gateway (on-disk hash differs from the registry). */
    List<String> detectBypass();
}
