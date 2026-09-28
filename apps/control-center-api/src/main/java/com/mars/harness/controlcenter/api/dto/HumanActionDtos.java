package com.mars.harness.controlcenter.api.dto;

import java.util.List;
import java.util.Map;

/** Why the run is waiting for a human, and what a human can do about it. */
public final class HumanActionDtos {

    private HumanActionDtos() {
    }

    /**
     * @param consequence what the engine does after this choice, from its routing code
     */
    public record Option(String value, String label, String consequence, boolean recommended) {
    }

    public record Link(String kind, String id, String label) {
    }

    /**
     * One action a human can take.
     *
     * @param gate         GATE_A, GATE_A2, GATE_B, MIGRATION_PLAN or NEEDS_HUMAN
     * @param decisionType the decision a human records, or null when no decision can resolve it here
     * @param boundTo      the hash the decision binds to (assessment, plan or proposal), echoed back
     *                     by the client as the expected hash
     * @param recommendation MARS's advice with its rationale; advice only, never pre-selected
     * @param canDecide    the caller's role permits recording this decision
     * @param resumable    resuming the run would make progress (Gate B after decisions, recovered states)
     */
    public record HumanAction(String id, String gate, String decisionType, String title, String whyStopped,
                              String whatToDecide, List<Option> options, Option recommendation,
                              String recommendationRationale, List<Link> inspect, String boundTo, String boundHash,
                              boolean requiresRationale, boolean canDecide, String cannotDecideReason, boolean resumable,
                              String resumeNote, List<ProposalDtos.ProposalRow> proposals, Map<String, Object> context,
                              List<String> manualSteps) {
    }

    public record HumanActionsView(String runId, String phase, boolean waiting, List<HumanAction> actions) {
    }
}
