package com.mars.harness.kernel.engine.mutation;

import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.ports.mutation.MutationPort;
import com.mars.harness.kernel.ports.mutation.ProposalSink;

import java.util.ArrayList;
import java.util.List;

/**
 * The capability-facing side of the gateway. A capability submits; the kernel authorizes with the
 * authorization it holds (a capability cannot supply its own) and applies through the single
 * gateway.
 */
public final class KernelProposalSink implements ProposalSink {

    private final MutationGateway gateway;
    private final MutationPort.Authorization authorization;

    public KernelProposalSink(MutationGateway gateway, MutationPort.Authorization authorization) {
        this.gateway = gateway;
        this.authorization = authorization;
    }

    @Override
    public List<Result> submit(List<ChangeProposal> batch) {
        List<Result> results = new ArrayList<>();
        for (MutationPort.Outcome outcome : gateway.apply(authorization, batch)) {
            results.add(new Result(outcome.proposalId(), outcome.status(), outcome.reason(), outcome.changeIds(),
                    outcome.checkpointId(), List.of()));
        }
        return results;
    }
}
