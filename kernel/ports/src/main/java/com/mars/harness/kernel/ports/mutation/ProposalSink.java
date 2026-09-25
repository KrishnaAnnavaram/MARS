package com.mars.harness.kernel.ports.mutation;

import com.mars.harness.kernel.core.change.ChangeProposal;

import java.util.List;

/**
 * How a capability asks the kernel to change source. The capability submits proposals, and the
 * kernel decides: it checks authorization, applies through the single Mutation Gateway,
 * reattaches identity and records everything. A capability never learns how to write. It only
 * learns what happened.
 */
public interface ProposalSink {

    enum Status { APPLIED, REJECTED, AWAITING_APPROVAL, STALE, FAILED }

    record Result(String proposalId, Status status, String reason, List<String> changeIds,
                  String checkpointId, List<String> reattachmentEvidence) {
        public Result {
            changeIds = changeIds == null ? List.of() : List.copyOf(changeIds);
            reattachmentEvidence = reattachmentEvidence == null ? List.of() : List.copyOf(reattachmentEvidence);
        }
    }

    List<Result> submit(List<ChangeProposal> batch);
}
