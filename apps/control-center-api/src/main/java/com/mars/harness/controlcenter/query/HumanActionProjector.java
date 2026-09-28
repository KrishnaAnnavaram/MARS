package com.mars.harness.controlcenter.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.controlcenter.api.dto.HumanActionDtos;
import com.mars.harness.controlcenter.api.dto.ProposalDtos;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.migration.CombinedAssessment;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.ports.migration.MigrationCapability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Human Action Center: for the gate the run is stopped at, why it stopped, what a human must
 * decide, which evidence to inspect and what the engine does after each choice. The consequences
 * are those of the engine's routing (RunAdvancer); the recommendation is shown as advice and is
 * never pre-selected.
 */
public final class HumanActionProjector {

    private static final Map<String, String> STRATEGY_CONSEQUENCES = Map.of(
            "MIGRATE_FIRST", "Plan and run the migration first; then plan and review security remediation on the migrated "
                    + "platform (Gate B).",
            "SECURITY_FIRST", "Plan and review security remediation first (Gate B); then plan and run the migration. Fixes the "
                    + "platform blocked are re-planned once after the migration.",
            "MIGRATION_ONLY", "Run only the migration. Findings are not remediated in this run; they are reported.",
            "SECURITY_ONLY", "Plan and review security remediation only. Migration is not executed; if it is not GREEN after the "
                    + "security work, MARS re-assesses it and asks again at Gate A2.",
            "ANALYZE_ONLY", "Execute nothing: final validation and the verdict are produced from the analysis alone.",
            "STOP", "Stop: nothing executes; the run is finalised and reported as stopped.");

    private HumanActionProjector() {
    }

    /**
     * @param callerMayDecide whether the caller's role permits recording decisions
     */
    public static HumanActionDtos.HumanActionsView view(RunReader run, boolean callerMayDecide, boolean advancing) {
        RunPhase phase = run.record().machine.current;
        List<HumanActionDtos.HumanAction> actions = new ArrayList<>();
        String cannot = callerMayDecide ? null : "Your role cannot record decisions (APPROVER or ADMIN required)";
        if (advancing) {
            cannot = "MARS is advancing this run; decisions are accepted once it stops";
        }
        boolean may = callerMayDecide && !advancing;
        switch (phase) {
            case WAITING_FOR_EXECUTION_DECISION -> gateA(run, may, cannot).ifPresent(actions::add);
            case WAITING_FOR_MIGRATION_APPROVAL -> actions.add(planApproval(run, may, cannot));
            case WAITING_FOR_REMEDIATION_APPROVAL -> actions.add(gateB(run, may, cannot));
            case WAITING_FOR_POST_SECURITY_MIGRATION_DECISION -> actions.add(gateA2(run, may, cannot));
            case NEEDS_HUMAN -> actions.add(needsHuman(run, may, cannot));
            default -> {
            }
        }
        return new HumanActionDtos.HumanActionsView(run.runId(), phase.name(), phase.waitingForHuman(), actions);
    }

    private static Optional<HumanActionDtos.HumanAction> gateA(RunReader run, boolean may, String cannot) {
        Optional<CombinedAssessment> combined = run.combinedAssessment();
        if (combined.isEmpty()) {
            return Optional.empty();
        }
        CombinedAssessment c = combined.get();
        List<HumanActionDtos.Option> options = c.offeredStrategies().stream().map(s -> new HumanActionDtos.Option(s,
                s.replace('_', ' '), STRATEGY_CONSEQUENCES.getOrDefault(s, ""), s.equals(c.recommendedStrategy()))).toList();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("traffic_light", c.trafficLight().name());
        context.put("complexity", c.complexity().name());
        context.put("effort_score", c.effortScore());
        context.put("evidence_confidence", c.evidenceConfidence().name());
        context.put("security", c.security());
        context.put("interactions", c.interactions());
        context.put("unknowns", c.unknowns());
        context.put("sequence", c.sequence() == null ? null : c.sequence().sequence().name());
        run.migrationAssessment().ifPresent(a -> {
            context.put("migration_need", a.need().name());
            context.put("current_version", a.current() == null ? null : a.current().framework() + " "
                    + a.current().frameworkVersion());
            context.put("target_version", a.recommendedTarget() == null ? null : a.recommendedTarget().framework() + " "
                    + a.recommendedTarget().version());
            context.put("reference_pack", a.referencePackId());
            context.put("blockers", a.blockers().stream().map(MigrationAssessment.Blocker::description).toList());
        });
        HumanActionDtos.Option recommendation = options.stream().filter(HumanActionDtos.Option::recommended).findFirst()
                .orElse(null);
        List<HumanActionDtos.Link> inspect = List.of(
                new HumanActionDtos.Link("ARTIFACT", "discovery/combined-assessment.json", "Combined assessment"),
                new HumanActionDtos.Link("ARTIFACT", "discovery/migration/migration-assessment.json", "Migration assessment"),
                new HumanActionDtos.Link("VIEW", "security", c.security().total() + " open finding(s)"),
                new HumanActionDtos.Link("VIEW", "migration", "Migration cockpit"),
                new HumanActionDtos.Link("ARTIFACT", "reports/analysis-report.md", "Analysis report"));
        return Optional.of(new HumanActionDtos.HumanAction("GATE_A", "GATE_A", Decision.DecisionType.EXECUTION_STRATEGY.name(),
                "Human Gate A: choose the execution strategy",
                "Discovery is complete and read-only. MARS never executes migration or remediation without a recorded "
                        + "human decision.",
                "Which work MARS may execute in this run, and in which order.", options, recommendation,
                c.sequence() == null ? null : c.sequence().rationale(), inspect, "combined assessment",
                run.record().combinedAssessmentHash, true, may, cannot, false, null, List.of(), context, List.of()));
    }

    private static HumanActionDtos.HumanAction planApproval(RunReader run, boolean may, String cannot) {
        Optional<MigrationCapability.MigrationPlan> plan = run.migrationPlan();
        Map<String, Object> context = new LinkedHashMap<>();
        plan.ifPresent(p -> {
            context.put("plan_id", p.planId());
            context.put("from", p.framework() + " " + p.fromVersion());
            context.put("to", p.framework() + " " + p.toVersion());
            context.put("reference_pack", p.referencePackId());
            context.put("allowed_rules", p.allowedRuleIds());
            context.put("max_rounds", p.maxRounds());
        });
        List<HumanActionDtos.Option> options = List.of(
                new HumanActionDtos.Option("APPROVED", "Approve plan", "Migration rounds start under this frozen plan; only its "
                        + "allow-listed deterministic rules can change code, through the Mutation Gateway.", false),
                new HumanActionDtos.Option("REJECTED", "Reject plan", "The migration does not run; the run continues with the "
                        + "next phase (security, or final validation).", false),
                new HumanActionDtos.Option("DEFERRED", "Defer", "As rejection for this run: the migration does not run.", false));
        return new HumanActionDtos.HumanAction("MIGRATION_PLAN", "MIGRATION_PLAN",
                Decision.DecisionType.MIGRATION_PLAN_APPROVAL.name(), "Approve the frozen migration plan",
                "The unified policy requires migration plans to be approved before any rule runs.",
                "Whether the migration may run under exactly this plan.", options, null, null,
                List.of(new HumanActionDtos.Link("ARTIFACT", "plans/migration-plan.json", "Migration plan"),
                        new HumanActionDtos.Link("VIEW", "migration", "Migration cockpit")),
                "migration plan", run.record().migrationPlanHash, true, may, cannot, false, null, List.of(), context, List.of());
    }

    private static HumanActionDtos.HumanAction gateB(RunReader run, boolean may, String cannot) {
        List<ProposalDtos.ProposalRow> rows = run.proposals().stream()
                .filter(p -> p.capability() != ChangeProposal.Capability.MIGRATION)
                .filter(p -> Set.of("PROPOSED", "AWAITING_APPROVAL").contains(run.proposalStatus(p.proposalId())))
                .map(p -> ProposalProjector.row(run, p)).toList();
        long undecided = rows.stream().filter(ProposalDtos.ProposalRow::awaitingDecision).count();
        long decidedNotProcessed = rows.size() - undecided;
        boolean resumable = decidedNotProcessed > 0 || undecided == 0;
        List<HumanActionDtos.Option> options = List.of(
                new HumanActionDtos.Option("APPROVED", "Approve", "When the run continues, the Mutation Gateway re-checks scope, "
                        + "identity, base hashes and this approval, applies the change, and the fix is verified. A strategy-only "
                        + "proposal is not applied: approving it authorizes producing a concrete fix.", false),
                new HumanActionDtos.Option("REJECTED", "Reject", "Never applied; reported as rejected by the developer.", false),
                new HumanActionDtos.Option("DEFERRED", "Defer", "Not applied in this run; reported as deferred.", false));
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("undecided", undecided);
        context.put("decided_not_yet_processed", decidedNotProcessed);
        return new HumanActionDtos.HumanAction("GATE_B", "GATE_B", Decision.DecisionType.PROPOSAL_APPROVAL.name(),
                "Human Gate B: review remediation proposals",
                undecided + " proposal(s) await a decision. A missing decision is never an approval.",
                "For each proposal: approve, reject or defer that exact proposal (bound to its hash and the baseline seal).",
                options, null, null, List.of(new HumanActionDtos.Link("VIEW", "changes", "Change explorer"),
                new HumanActionDtos.Link("VIEW", "security", "Security cockpit")), "proposal", null, true, may, cannot,
                resumable, undecided == 0 ? "Every proposal is decided; continuing applies the approved ones."
                : decidedNotProcessed > 0 ? decidedNotProcessed + " decided proposal(s) are processed when the run continues; "
                + undecided + " stay waiting." : "Continuing now changes nothing until a proposal is decided, unless you "
                + "choose to leave the undecided ones pending (they stay unapproved).",
                rows, context, List.of());
    }

    private static HumanActionDtos.HumanAction gateA2(RunReader run, boolean may, String cannot) {
        Optional<JsonNode> reassessment = run.postSecurityReassessment();
        Map<String, Object> context = new LinkedHashMap<>();
        reassessment.ifPresent(n -> {
            context.put("previous_traffic_light", n.path("previous_traffic_light").asText(null));
            context.put("current_traffic_light", n.path("current_traffic_light").asText(null));
            context.put("why", n.path("why").asText(null));
        });
        List<HumanActionDtos.Option> options = List.of(
                new HumanActionDtos.Option("PROCEED", "Proceed with migration", "Plan and run the migration now, authorized by "
                        + "this decision.", false),
                new HumanActionDtos.Option("SKIP", "Skip migration", "The migration stays declined; final validation and the "
                        + "verdict follow.", false),
                new HumanActionDtos.Option("STOP", "Stop", "Nothing further executes; final validation and the verdict follow.",
                        false));
        return new HumanActionDtos.HumanAction("GATE_A2", "GATE_A2", Decision.DecisionType.POST_SECURITY_MIGRATION.name(),
                "Human Gate A2: migrate after the security work?",
                "Migration was declined at Gate A. After the security changes MARS re-indexed the code, rebuilt the graph and "
                        + "re-assessed the migration, which is still not GREEN.",
                "Whether to run the migration now.", options, null, null,
                List.of(new HumanActionDtos.Link("ARTIFACT", "discovery/migration/post-security-reassessment.json",
                        "Post-security reassessment"), new HumanActionDtos.Link("VIEW", "migration", "Migration cockpit")),
                "post-security reassessment", reassessment.map(n -> n.path("current_assessment_hash").asText(null))
                .orElse(null), true, may, cannot, false, null, List.of(), context, List.of());
    }

    private static HumanActionDtos.HumanAction needsHuman(RunReader run, boolean may, String cannot) {
        Optional<Verdict> verdict = run.verdict();
        Optional<MigrationCapability.MigrationExecution> execution = run.migrationExecution();
        boolean migrationPending = run.record().verdict == null && execution
                .map(e -> e.status() == MigrationCapability.ExecutionStatus.NEEDS_HUMAN).orElse(false);
        Map<String, Object> context = new LinkedHashMap<>();
        if (migrationPending) {
            List<ProposalDtos.ProposalRow> manual = run.proposals().stream()
                    .filter(p -> p.capability() == ChangeProposal.Capability.MANUAL)
                    .filter(p -> !"APPLIED".equals(run.proposalStatus(p.proposalId()))
                            && !"VALIDATED".equals(run.proposalStatus(p.proposalId())))
                    .map(p -> ProposalProjector.row(run, p)).toList();
            boolean approvedManual = manual.stream().anyMatch(r -> "APPROVED".equals(r.decision()));
            context.put("unmatched_errors", execution.get().unmatchedErrors());
            return new HumanActionDtos.HumanAction("NEEDS_HUMAN:MIGRATION", "NEEDS_HUMAN",
                    manual.isEmpty() ? null : Decision.DecisionType.PROPOSAL_APPROVAL.name(),
                    "MARS needs a human: the migration rounds stopped",
                    execution.get().needsHumanReason(),
                    "Resolve the failure from the actual dependency, submit the change as a patch, and approve it. MARS never "
                            + "guesses an import path.", List.of(), null, null,
                    List.of(new HumanActionDtos.Link("VIEW", "migration", "Migration rounds and build errors"),
                            new HumanActionDtos.Link("ARTIFACT", "plans/migration-execution.json", "Migration execution"),
                            new HumanActionDtos.Link("VIEW", "logs", "Round build logs")),
                    null, null, true, may && !manual.isEmpty(), manual.isEmpty() ? "No submitted patch to decide yet" : cannot,
                    approvedManual, approvedManual ? "An approved patch is waiting; continuing applies it through the Mutation "
                    + "Gateway and resumes the rounds." : "The run resumes once an approved patch exists.", manual, context,
                    List.of("harness submit-patch --run " + run.runId() + " --file <repo/path>=<local file> --reason <why>",
                            "Approve the registered patch here (or: harness approve remediation --run " + run.runId()
                                    + " --proposal <PROP-...>)", "Continue execution here (or: harness resume --run "
                                    + run.runId() + ")"));
        }
        List<String> reasons = verdict.map(Verdict::reasons).orElse(List.of());
        List<String> pending = verdict.map(Verdict::pendingDecisions).orElse(List.of());
        List<String> items = verdict.map(v -> v.items().stream()
                .filter(i -> i.status() == Verdict.ItemStatus.NEEDS_HUMAN || i.status() == Verdict.ItemStatus.PENDING_APPROVAL
                        || i.status() == Verdict.ItemStatus.INSUFFICIENT_EVIDENCE)
                .map(i -> ValidationProjector.findingLabel(run, i.itemId()) + ": " + i.status() + (i.reason() == null ? ""
                        : " — " + i.reason())).toList()).orElse(List.of());
        context.put("reasons", reasons);
        context.put("pending_decisions", pending);
        context.put("items", items);
        return new HumanActionDtos.HumanAction("NEEDS_HUMAN:VERDICT", "NEEDS_HUMAN", null,
                "MARS needs a human: the verdict is NEEDS_HUMAN",
                reasons.isEmpty() ? "The verdict could not be reached without a human." : String.join("; ", reasons),
                "Address the outstanding items below. This run will not advance further on its own: MARS does not re-run final "
                        + "validation after a NEEDS_HUMAN verdict.", List.of(), null, null,
                List.of(new HumanActionDtos.Link("VIEW", "verdict", "Verdict"),
                        new HumanActionDtos.Link("ARTIFACT", "reports/final-report.md", "Final report")),
                null, null, false, false, "No decision recorded here resolves a NEEDS_HUMAN verdict", false,
                "Not resumable: start a follow-up run once the items are addressed.", List.of(), context, items);
    }
}
