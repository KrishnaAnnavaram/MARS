package com.mars.harness.kernel.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mars.harness.kernel.adapters.bootshift.BootshiftBridge;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.graph.CanonicalGraph;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.core.verdict.VerdictCalculator;
import com.mars.harness.kernel.engine.capability.KernelCapabilityContext;
import com.mars.harness.kernel.engine.graph.CanonicalGraphBuilder;
import com.mars.harness.kernel.engine.identity.IdentitySynchronizer;
import com.mars.harness.kernel.engine.mutation.KernelProposalSink;
import com.mars.harness.kernel.engine.mutation.MutationGateway;
import com.mars.harness.kernel.engine.proposal.ProposalStore;
import com.mars.harness.kernel.engine.report.ReportRenderer;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.engine.validation.UnifiedValidator;
import com.mars.harness.kernel.ports.migration.MigrationCapability;
import com.mars.harness.kernel.ports.mutation.MutationPort;
import com.mars.harness.kernel.ports.mutation.ProposalSink;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Advances a run from its current state to the next human gate or to its verdict.
 *
 * <p>This class is resumable by construction. Each step is a function of the persisted state and
 * the artifacts, never of in-memory progress. Applied proposals are recognised from the run
 * record and the lineage ledger, and are never re-applied.
 */
final class RunAdvancer {

    private final RunSession session;
    private final EngineConfig config;
    private final MutationGateway gateway;

    RunAdvancer(RunSession session, EngineConfig config) {
        this.session = session;
        this.config = config;
        IdentitySynchronizer sync = new IdentitySynchronizer(session, config.codeModel());
        this.gateway = new MutationGateway(session, sync, this::rebuildGraph);
    }

    void advance() {
        for (int guard = 0; guard < 64; guard++) {
            RunPhase phase = session.record.machine.current;
            boolean progressed = switch (phase) {
                case EXECUTION_PLANNED -> route();
                case MIGRATION_PLANNED -> planMigration();
                case WAITING_FOR_MIGRATION_APPROVAL -> migrationApprovalGate();
                case MIGRATION_RUNNING -> runMigration();
                case MIGRATION_VALIDATING -> validateMigration();
                case MIGRATION_COMPLETE -> afterMigration();
                case SECURITY_FINDINGS_READY -> planRemediation();
                case REMEDIATION_PROPOSED -> to(RunPhase.WAITING_FOR_REMEDIATION_APPROVAL, "Human Gate B");
                case WAITING_FOR_REMEDIATION_APPROVAL -> processApprovals();
                case REMEDIATION_RUNNING, SECURITY_VALIDATING -> recoverRemediation();
                case SECURITY_ANALYSIS_RUNNING -> planRemediation();
                case SECURITY_COMPLETE -> afterSecurity();
                case WAITING_FOR_POST_SECURITY_MIGRATION_DECISION -> postSecurityGate();
                case FINAL_VALIDATION -> finalValidation();
                case NEEDS_HUMAN -> resumeFromNeedsHuman();
                default -> false;
            };
            if (!progressed) {
                return;
            }
        }
    }

    private boolean to(RunPhase next, String reason) {
        session.record.machine.transition(next, reason);
        session.saveRecord();
        return true;
    }

    private KernelCapabilityContext context() {
        return new KernelCapabilityContext(session, config);
    }

    // ------------------------------------------------------------------ routing after Gate A

    private boolean route() {
        Decision.ExecutionStrategy strategy = Decision.ExecutionStrategy.valueOf(session.record.strategy);
        return switch (strategy) {
            case MIGRATE_FIRST, MIGRATION_ONLY -> to(RunPhase.MIGRATION_PLANNED, strategy + ": migration first");
            case SECURITY_FIRST, SECURITY_ONLY -> to(RunPhase.SECURITY_FINDINGS_READY, strategy + ": security first");
            case ANALYZE_ONLY, STOP -> to(RunPhase.FINAL_VALIDATION, strategy + ": no execution");
        };
    }

    // ------------------------------------------------------------------ migration

    private boolean planMigration() {
        KernelCapabilityContext context = context();
        MigrationAssessment assessment = currentAssessment(context);
        MigrationCapability.MigrationPlan plan = config.migration().plan(context, assessment);
        session.artifacts.writeJson("plans", "migration-plan.json", plan);
        session.record.migrationPlanned = true;
        session.record.migrationPlanId = plan.planId();
        session.record.migrationPlanHash = KernelJson.hash(plan);
        session.saveRecord();
        if (!plan.stopConditions().isEmpty()) {
            session.note("Migration plan cannot execute: " + plan.stopConditions());
            session.evidence.record(EvidenceRecord.EvidenceKind.UNKNOWN, "Migration stopped before execution",
                    String.join("; ", plan.stopConditions()), "plans/migration-plan.json", List.of(plan.planId()),
                    "reference workflow stop conditions (no improvising)", EvidenceRecord.Reliability.VERIFIED, null,
                    "plans/migration-plan.json", null, "kernel.migration");
            return to(nextAfterMigration(), "migration not executable: " + plan.stopConditions());
        }
        if (session.policy.migration().planRequiresApproval()) {
            return to(RunPhase.WAITING_FOR_MIGRATION_APPROVAL, "policy requires plan approval");
        }
        return to(RunPhase.MIGRATION_RUNNING, "plan " + plan.planId() + " authorized by " + authorizingDecision().decisionId());
    }

    private boolean migrationApprovalGate() {
        Optional<Decision> approval = session.approvals.latest(Decision.DecisionType.MIGRATION_PLAN_APPROVAL);
        if (approval.isEmpty() || !session.record.migrationPlanHash.equals(approval.get().planHash())) {
            return false;
        }
        if (!approval.get().approved()) {
            session.note("Migration plan " + approval.get().selected() + " by " + approval.get().actor());
            return to(nextAfterMigration(), "migration plan " + approval.get().selected());
        }
        return to(RunPhase.MIGRATION_RUNNING, "plan approved by " + approval.get().actor());
    }

    private boolean runMigration() {
        MigrationCapability.MigrationPlan plan = KernelJson.read(session.layout.area("plans").resolve("migration-plan.json"),
                MigrationCapability.MigrationPlan.class);
        MigrationCapability.MigrationExecution previous = readExecution().orElse(null);
        MutationPort.Authorization authorization = new MutationPort.Authorization("migration-plan", authorizingDecision(),
                session.approvals.all(), new LinkedHashSet<>(plan.allowedRuleIds()), plan.planId(),
                session.record.migrationPlanHash);
        ProposalSink sink = new KernelProposalSink(gateway, authorization);
        MigrationCapability.MigrationExecution execution = config.migration().execute(context(), plan, sink, previous);
        session.artifacts.writeJson("plans", "migration-execution.json", execution);
        if (execution.status() == MigrationCapability.ExecutionStatus.NEEDS_HUMAN) {
            session.note("MIGRATION NEEDS_HUMAN: " + execution.needsHumanReason());
            return to(RunPhase.NEEDS_HUMAN, "migration needs a human: " + execution.needsHumanReason());
        }
        return to(RunPhase.MIGRATION_VALIDATING, "migration execution " + execution.status());
    }

    private boolean validateMigration() {
        MigrationCapability.MigrationPlan plan = KernelJson.read(session.layout.area("plans").resolve("migration-plan.json"),
                MigrationCapability.MigrationPlan.class);
        MigrationCapability.MigrationExecution execution = readExecution().orElseThrow();
        MigrationCapability.CapabilityValidation validation = config.migration().validate(context(), plan, execution);
        session.artifacts.writeJson("validation", "migration-validation.json", validation);
        boolean passed = execution.status() == MigrationCapability.ExecutionStatus.GREEN
                && validation.dimensions().stream().noneMatch(d -> d.status().failed());
        for (String proposalId : execution.proposalIds()) {
            if ("APPLIED".equals(session.record.proposalStatus.get(proposalId))) {
                gateway.recordValidation(proposalId, passed, "validation/migration-validation.json",
                        "migration " + execution.status() + (passed ? "" : "; see migration validation"));
            }
        }
        session.record.migrationExecuted = execution.status() == MigrationCapability.ExecutionStatus.GREEN;
        session.saveRecord();
        return to(RunPhase.MIGRATION_COMPLETE, "migration " + execution.status());
    }

    private boolean afterMigration() {
        return to(nextAfterMigration(), "migration phase done");
    }

    private RunPhase nextAfterMigration() {
        Decision.ExecutionStrategy strategy = Decision.ExecutionStrategy.valueOf(session.record.strategy);
        if (strategy == Decision.ExecutionStrategy.MIGRATE_FIRST && !session.record.securityExecuted) {
            return RunPhase.SECURITY_FINDINGS_READY;
        }
        // security ran before the migration (SECURITY_FIRST, or Gate A2 PROCEED): the work it left blocked by the
        // platform is re-planned once against the migrated platform, never silently reported as pending
        if (session.record.migrationExecuted && strategy.includesSecurity() && !session.record.replannedAfterMigration
                && remediationPlans().stream().anyMatch(RemediationCapability.RemediationPlan::blockedByPlatform)) {
            return RunPhase.SECURITY_FINDINGS_READY;
        }
        return RunPhase.FINAL_VALIDATION;
    }

    private Decision authorizingDecision() {
        if (session.record.postSecurityDecisionId != null) {
            return session.approvals.find(session.record.postSecurityDecisionId).orElseThrow();
        }
        return session.approvals.find(session.record.executionDecisionId).orElseThrow();
    }

    private MigrationAssessment currentAssessment(KernelCapabilityContext context) {
        boolean codeChanged = session.record.proposalStatus.values().stream().anyMatch(s -> s.equals("APPLIED")
                || s.equals("VALIDATED") || s.equals("FAILED_VALIDATION"));
        Path initial = session.layout.area("discovery").resolve("migration").resolve("migration-assessment.json");
        if (!codeChanged && Files.isRegularFile(initial)) {
            return KernelJson.read(initial, MigrationAssessment.class);
        }
        MigrationAssessment refreshed = config.migration().assess(context, HarnessEngine.objectives(openFindings()));
        session.artifacts.writeJson("discovery/migration", "migration-assessment-refreshed.json", refreshed);
        return refreshed;
    }

    private Optional<MigrationCapability.MigrationExecution> readExecution() {
        Path file = session.layout.area("plans").resolve("migration-execution.json");
        return Files.isRegularFile(file) ? Optional.of(KernelJson.read(file, MigrationCapability.MigrationExecution.class))
                : Optional.empty();
    }

    // ------------------------------------------------------------------ security

    private boolean planRemediation() {
        if (session.record.machine.current != RunPhase.SECURITY_ANALYSIS_RUNNING) {
            session.record.machine.transition(RunPhase.SECURITY_ANALYSIS_RUNNING, "strategy routing");
            session.saveRecord();
        }
        // a finding is planned once; only a plan the platform blocked is re-planned, after the migration ran
        List<RemediationCapability.RemediationPlan> previous = remediationPlans();
        Set<String> settled = new LinkedHashSet<>();
        previous.stream().filter(p -> !(p.blockedByPlatform() && session.record.migrationExecuted))
                .forEach(p -> settled.add(p.findingId()));
        if (!previous.isEmpty()) {
            session.record.replannedAfterMigration = true;
            session.saveRecord();
        }
        List<Finding> open = openFindings().stream().filter(f -> !settled.contains(f.findingId())).toList();
        List<RemediationCapability.RemediationPlan> plans = open.isEmpty() ? List.of() : config.remediation().plan(
                context(), open, session.record.researchInputs, session.record.migrationExecuted);
        List<RemediationCapability.RemediationPlan> merged = new ArrayList<>(previous.stream()
                .filter(p -> settled.contains(p.findingId())).toList());
        merged.addAll(plans);
        session.artifacts.writeJson("plans", "remediation-plans.json", merged);
        int registered = 0;
        // every plan, not only the new ones: an interruption between writing the plans and registering their
        // proposals must not lose a proposal (re-entry registers what is missing, never twice)
        for (RemediationCapability.RemediationPlan plan : merged) {
            if (plan.blockedByPlatform() && !session.record.migrationExecuted) {
                continue;
            }
            if (plan.proposal() != null && session.proposals.find(plan.proposal().proposalId()).isEmpty()) {
                gateway.register(plan.proposal());
                registered++;
            }
        }
        new ReportRenderer(session, config).renderLegacyPlans();
        long awaiting = session.proposals.all().stream()
                .filter(p -> p.capability() != ChangeProposal.Capability.MIGRATION)
                .filter(p -> "PROPOSED".equals(session.record.proposalStatus.get(p.proposalId()))
                        || "AWAITING_APPROVAL".equals(session.record.proposalStatus.get(p.proposalId()))).count();
        boolean replanAfterMigration = !previous.isEmpty() && session.record.migrationExecuted
                && previous.stream().anyMatch(RemediationCapability.RemediationPlan::blockedByPlatform);
        if (awaiting == 0 || replanAfterMigration && registered == 0) {
            // a re-plan that produced nothing new does not reopen Gate B for proposals the developer already left pending
            return to(RunPhase.SECURITY_COMPLETE, awaiting == 0 ? "no remediation proposal is executable"
                    : "re-plan after migration produced no new proposal");
        }
        return to(RunPhase.REMEDIATION_PROPOSED, registered + " new proposal(s); " + awaiting + " await a decision");
    }

    private boolean processApprovals() {
        List<RemediationCapability.RemediationPlan> plans = remediationPlans();
        List<ChangeProposal> candidates = session.proposals.all().stream()
                .filter(p -> p.capability() != ChangeProposal.Capability.MIGRATION)
                .filter(p -> {
                    String status = session.record.proposalStatus.get(p.proposalId());
                    return "PROPOSED".equals(status) || "AWAITING_APPROVAL".equals(status);
                }).toList();
        List<ChangeProposal> undecided = new ArrayList<>();
        boolean running = false;
        for (ChangeProposal proposal : candidates) {
            Optional<Decision> decision = session.approvals.latestForProposal(proposal.proposalId());
            if (decision.isEmpty()) {
                undecided.add(proposal);
                continue;
            }
            if (decision.get().approved() && proposal.strategyOnly()) {
                session.record.proposalStatus.put(proposal.proposalId(), ProposalStore.Status.APPROVED.name());
                session.saveRecord();
                continue;
            }
            if (!running && decision.get().approved()) {
                session.record.machine.transition(RunPhase.REMEDIATION_RUNNING, "applying approved proposals");
                session.saveRecord();
                running = true;
            }
            MutationPort.Authorization authorization = new MutationPort.Authorization("proposal-approval", null,
                    List.of(decision.get()), Set.of(), null, null);
            MutationPort.Outcome outcome = gateway.apply(authorization, List.of(proposal)).get(0);
            if (outcome.status() == ProposalSink.Status.APPLIED && decision.get().approved()) {
                verifyFix(plans, proposal, outcome);
            } else if (decision.get().approved()) {
                session.note("Approved proposal " + proposal.proposalId() + " was not applied: " + outcome.status() + " "
                        + outcome.reason());
            }
        }
        if (running) {
            // left even when the gateway refused every approved proposal: a refusal must not strand the run
            session.record.machine.transition(RunPhase.SECURITY_VALIDATING, "verification of applied fixes");
            session.saveRecord();
        }
        if (!undecided.isEmpty() && !session.record.acceptPending) {
            if (running) {
                session.record.machine.transition(RunPhase.WAITING_FOR_REMEDIATION_APPROVAL, undecided.size()
                        + " proposal(s) still await a decision");
                session.saveRecord();
            }
            session.note(undecided.size() + " proposal(s) await a human decision: "
                    + undecided.stream().map(ChangeProposal::proposalId).toList());
            return false;
        }
        session.record.securityExecuted = true;
        session.saveRecord();
        return to(RunPhase.SECURITY_COMPLETE, undecided.isEmpty() ? "every proposal decided"
                : undecided.size() + " undecided proposal(s) left pending by --accept-pending");
    }

    /**
     * Resume after an interruption between the gateway applying an approved fix and its verification
     * (spec §32.14). The applied change is recognised from the run record and is never re-applied;
     * what was not verified yet is verified now, then approvals continue as usual.
     */
    private boolean recoverRemediation() {
        List<RemediationCapability.RemediationPlan> plans = remediationPlans();
        for (ChangeProposal proposal : session.proposals.all()) {
            String status = session.record.proposalStatus.get(proposal.proposalId());
            if (proposal.capability() == ChangeProposal.Capability.MIGRATION
                    || !("APPLIED".equals(status) || "VALIDATED".equals(status))) {
                continue;
            }
            Optional<RemediationCapability.RemediationPlan> plan = plans.stream()
                    .filter(p -> proposal.findingRefs().contains(p.findingId())).findFirst();
            if (plan.isEmpty() || session.record.verification.containsKey(plan.get().planId())) {
                continue;
            }
            session.note("Recovered " + proposal.proposalId() + ": applied before the interruption, verifying now");
            List<String> changes = session.record.proposalChanges.getOrDefault(proposal.proposalId(), List.of());
            verifyFix(plans, proposal, new MutationPort.Outcome(proposal.proposalId(), ProposalSink.Status.APPLIED,
                    "recovered after interruption", changes, proposal.affectedFileIds(), null));
        }
        if (session.record.machine.current == RunPhase.REMEDIATION_RUNNING) {
            session.record.machine.transition(RunPhase.SECURITY_VALIDATING, "recovery: verification of applied fixes");
        }
        return to(RunPhase.WAITING_FOR_REMEDIATION_APPROVAL, "recovered after an interruption");
    }

    private void verifyFix(List<RemediationCapability.RemediationPlan> plans, ChangeProposal proposal,
                           MutationPort.Outcome outcome) {
        Optional<RemediationCapability.RemediationPlan> plan = plans.stream()
                .filter(p -> proposal.findingRefs().contains(p.findingId())).findFirst();
        if (plan.isEmpty()) {
            session.note("Applied " + proposal.proposalId() + " matches no remediation plan; verified by unified validation only");
            return;
        }
        RemediationCapability.VerificationReport report = config.remediation().verify(context(), plan.get(),
                new RemediationCapability.AppliedFix(plan.get().planId(), proposal.proposalId(), outcome.changeIds(),
                        outcome.changedFileIds(), null, null));
        session.artifacts.writeJson("validation/security", plan.get().planId() + ".json", report);
        session.record.verification.put(plan.get().planId(), report.decision());
        session.saveRecord();
        gateway.recordValidation(proposal.proposalId(), "Cleared".equals(report.decision()),
                "validation/security/" + plan.get().planId() + ".json",
                "VRH verdict " + report.decision() + " (score " + report.score() + "/" + report.threshold() + ")");
    }

    private boolean afterSecurity() {
        session.record.securityExecuted = true;
        session.saveRecord();
        Decision.ExecutionStrategy strategy = Decision.ExecutionStrategy.valueOf(session.record.strategy);
        if (strategy == Decision.ExecutionStrategy.SECURITY_FIRST && !session.record.migrationPlanned) {
            return to(RunPhase.MIGRATION_PLANNED, "security first done; migration next");
        }
        if (session.record.migrationDeclined && !session.record.postSecurityReassessed && !session.record.stopRequested) {
            return reassessAfterSecurity();
        }
        return to(RunPhase.FINAL_VALIDATION, "security phase done");
    }

    /** Spec §23: re-index, rebuild the graph, re-run the assessment, compare, and offer Gate A2 if still relevant. */
    private boolean reassessAfterSecurity() {
        Path initialFile = session.layout.area("discovery").resolve("migration").resolve("migration-assessment.json");
        MigrationAssessment previous = KernelJson.read(initialFile, MigrationAssessment.class);
        if (previous.trafficLight() == MigrationAssessment.TrafficLight.GREEN) {
            session.record.postSecurityReassessed = true;
            session.saveRecord();
            return to(RunPhase.FINAL_VALIDATION, "migration was GREEN; no post-security migration prompt");
        }
        MigrationAssessment current = config.migration().assess(context(), HarnessEngine.objectives(openFindings()));
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("previous_assessment_id", previous.assessmentId());
        comparison.put("previous_traffic_light", previous.trafficLight().name());
        comparison.put("previous_effort_score", previous.effort().migrationEffortScore());
        comparison.put("previous_complexity", previous.complexity().name());
        comparison.put("current_assessment_id", current.assessmentId());
        comparison.put("current_traffic_light", current.trafficLight().name());
        comparison.put("current_effort_score", current.effort().migrationEffortScore());
        comparison.put("current_complexity", current.complexity().name());
        comparison.put("current_assessment_hash", KernelJson.hash(current));
        List<String> changes = new ArrayList<>();
        if (previous.trafficLight() != current.trafficLight()) {
            changes.add("traffic light " + previous.trafficLight() + " -> " + current.trafficLight());
        }
        if (previous.effort().migrationEffortScore() != current.effort().migrationEffortScore()) {
            changes.add("effort " + previous.effort().migrationEffortScore() + " -> " + current.effort().migrationEffortScore());
        }
        long before = previous.objectives().stream().filter(MigrationAssessment.ObjectiveImpact::requiresMigration).count();
        long after = current.objectives().stream().filter(MigrationAssessment.ObjectiveImpact::requiresMigration).count();
        if (before != after) {
            changes.add("objectives requiring migration " + before + " -> " + after);
        }
        if (previous.issues().size() != current.issues().size()) {
            changes.add("migration issues observed " + previous.issues().size() + " -> " + current.issues().size());
        }
        comparison.put("what_changed", changes);
        comparison.put("why", changes.isEmpty()
                ? "The security changes did not alter the platform, the lifecycle facts or the migration issues: the "
                + "recommendation stands for the same evidenced reasons (" + current.trafficLightRationale() + ")"
                : "The recommendation moved because: " + String.join("; ", changes) + ". Current rationale: "
                + current.trafficLightRationale());
        comparison.put("current_rationale", current.trafficLightRationale());
        session.artifacts.writeJson("discovery/migration", "migration-assessment-post-security.json", current);
        session.artifacts.writeJson("discovery/migration", "post-security-reassessment.json", comparison);
        session.record.postSecurityReassessed = true;
        session.saveRecord();
        if (current.trafficLight() == MigrationAssessment.TrafficLight.GREEN) {
            return to(RunPhase.FINAL_VALIDATION, "reassessed GREEN after security; no pointless migration prompt");
        }
        return to(RunPhase.WAITING_FOR_POST_SECURITY_MIGRATION_DECISION,
                "Human Gate A2: migration is " + current.trafficLight() + " after security work");
    }

    private boolean postSecurityGate() {
        if (session.record.postSecurityDecisionId == null) {
            return false;
        }
        Decision decision = session.approvals.find(session.record.postSecurityDecisionId).orElseThrow();
        return switch (Decision.MigrationChoice.valueOf(decision.selected())) {
            case PROCEED -> {
                session.record.migrationDeclined = false;
                session.saveRecord();
                yield to(RunPhase.MIGRATION_PLANNED, "Gate A2: proceed with migration (" + decision.actor() + ")");
            }
            case SKIP -> to(RunPhase.FINAL_VALIDATION, "Gate A2: migration skipped again (" + decision.actor() + ")");
            case STOP -> {
                session.record.stopRequested = true;
                session.saveRecord();
                yield to(RunPhase.FINAL_VALIDATION, "Gate A2: stop (" + decision.actor() + ")");
            }
        };
    }

    private boolean resumeFromNeedsHuman() {
        // a migration that needed a human continues once the human's input exists (a submitted, approved patch)
        boolean migrationPending = readExecution().map(e -> e.status() == MigrationCapability.ExecutionStatus.NEEDS_HUMAN)
                .orElse(false);
        if (migrationPending) {
            boolean approvedManual = session.proposals.all().stream()
                    .filter(p -> p.capability() == ChangeProposal.Capability.MANUAL)
                    .anyMatch(p -> session.approvals.latestForProposal(p.proposalId()).map(Decision::approved).orElse(false)
                            && !"APPLIED".equals(session.record.proposalStatus.get(p.proposalId())));
            if (!approvedManual) {
                return false;
            }
            for (ChangeProposal manual : session.proposals.all()) {
                if (manual.capability() != ChangeProposal.Capability.MANUAL
                        || "APPLIED".equals(session.record.proposalStatus.get(manual.proposalId()))) {
                    continue;
                }
                session.approvals.latestForProposal(manual.proposalId()).filter(Decision::approved).ifPresent(d ->
                        gateway.apply(new MutationPort.Authorization("manual-patch", null, List.of(d), Set.of(), null, null),
                                List.of(manual)));
            }
            return to(RunPhase.MIGRATION_RUNNING, "human-supplied patch approved and applied; migration rounds resume");
        }
        return false;
    }

    // ------------------------------------------------------------------ final validation and verdict

    private boolean finalValidation() {
        boolean mutated = session.record.proposalChanges.values().stream().anyMatch(c -> !c.isEmpty());
        List<ValidationResult.DimensionResult> capabilityDims = new ArrayList<>();
        Map<String, String> explanations = new LinkedHashMap<>();
        Optional<MigrationCapability.MigrationExecution> execution = readExecution();
        Path migrationValidation = session.layout.area("validation").resolve("migration-validation.json");
        if (Files.isRegularFile(migrationValidation)) {
            capabilityDims.addAll(KernelJson.read(migrationValidation, MigrationCapability.CapabilityValidation.class).dimensions());
        }
        execution.map(MigrationCapability.MigrationExecution::behaviour).ifPresent(b -> {
            for (String explanation : b.explanations()) {
                int colon = explanation.indexOf(':');
                if (colon > 0) {
                    explanations.put(explanation.substring(0, colon).trim(), explanation.substring(colon + 1).trim());
                }
            }
        });
        boolean securityApplied = false;
        for (Map.Entry<String, String> entry : session.record.verification.entrySet()) {
            securityApplied = true;
            RemediationCapability.VerificationReport report = KernelJson.read(session.layout.area("validation/security")
                    .resolve(entry.getKey() + ".json"), RemediationCapability.VerificationReport.class);
            capabilityDims.addAll(SecurityDimensions.from(report));
        }
        Set<String> authorizedFiles = new LinkedHashSet<>();
        session.proposals.all().forEach(p -> authorizedFiles.addAll(p.affectedFileIds()));
        session.lineage.entries().forEach(e -> {
            if (e.fileId() != null) {
                authorizedFiles.add(e.fileId());
            }
        });
        ValidationResult validation = new UnifiedValidator(session, config, gateway).validate(new UnifiedValidator.Inputs(
                mutated, session.record.migrationExecuted || execution.isPresent(), securityApplied, capabilityDims,
                explanations, authorizedFiles));
        List<Verdict.ItemResult> items = new VerdictItems(session, config).items(execution.orElse(null));
        List<String> pending = new ArrayList<>();
        List<String> hard = new ArrayList<>();
        if (session.record.notes.stream().anyMatch(n -> n.startsWith("BYPASS DETECTED"))) {
            hard.add("Mutation bypass detected during the run");
        }
        Verdict verdict = VerdictCalculator.compute(session.layout.runId(), items, validation, pending, hard,
                List.of(), session.policy.policyVersion());
        session.artifacts.writeJson("reports", "verdict.json", verdict);
        session.record.verdict = verdict.outcome().name();
        session.saveRecord();
        new ReportRenderer(session, config).renderFinal();
        RunPhase terminal = switch (verdict.outcome()) {
            case CLEARED -> RunPhase.CLEARED;
            case PARTIAL -> RunPhase.PARTIAL;
            case BLOCKED -> RunPhase.BLOCKED;
            case NEEDS_HUMAN -> RunPhase.NEEDS_HUMAN;
            case INSUFFICIENT_EVIDENCE -> RunPhase.COMPLETE;
        };
        session.record.machine.transition(terminal, "verdict " + verdict.outcome());
        if (terminal != RunPhase.NEEDS_HUMAN && terminal != RunPhase.COMPLETE) {
            session.record.machine.transition(RunPhase.COMPLETE, "evidence package sealed");
        }
        session.saveRecord();
        return false;
    }

    // ------------------------------------------------------------------ shared

    private List<Finding> openFindings() {
        return session.findings.stream().filter(f -> f.status() != Finding.FindingStatus.FIXED
                && f.status() != Finding.FindingStatus.CLOSED).toList();
    }

    List<RemediationCapability.RemediationPlan> remediationPlans() {
        Path file = session.layout.area("plans").resolve("remediation-plans.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        return KernelJson.mapper().convertValue(KernelJson.read(file),
                new TypeReference<List<RemediationCapability.RemediationPlan>>() { });
    }

    /**
     * A batch that changed a build file makes the analysis-time build model stale (a migration bumps the parent and
     * explicit versions). The model is re-read from the workspace. Declared versions are authoritative; resolved
     * versions of managed dependencies are unknown until a build re-resolves them, and are left unknown rather
     * than carried over from the pre-change model.
     */
    private void refreshBuildModel(String batch, List<String> changedFiles) {
        boolean buildFileChanged = changedFiles.stream().map(id -> session.fileRegistry.byId(id)
                        .map(r -> r.getCurrentPath()).orElse(id))
                .anyMatch(p -> p.equals("pom.xml") || p.endsWith("/pom.xml"));
        if (!buildFileChanged) {
            return;
        }
        session.buildModel = new com.mars.harness.kernel.adapters.build.PomModelReader().read(session.layout.workspace(), null);
        session.saveBuildModel();
        session.evidence.record(EvidenceRecord.EvidenceKind.OBSERVATION, "Build model re-read after " + batch
                        + " changed a build file", "declared versions from the workspace descriptors; managed versions "
                        + "are unresolved until a build re-resolves them", "state/build-model.json", changedFiles,
                "the analysis-time model is stale once a descriptor changes", EvidenceRecord.Reliability.DERIVED, null,
                null, null, "kernel.build-model");
    }

    /** Spec §20: after identity reattachment, rebuild the canonical graph and diff it against the previous one. */
    private void rebuildGraph(String batch, List<String> changedFiles) {
        refreshBuildModel(batch, changedFiles);
        CanonicalGraph before = session.graph;
        com.bootshift.core.domain.OutputLayout output = new com.bootshift.core.domain.OutputLayout(session.layout.bootshiftOutput());
        var buildNode = output.readLatest("02-build", "build-model.json");
        var depNode = output.readLatest("02-build", "dependency-model.json");
        com.bootshift.core.graph.ApplicationGraph base = null;
        if (buildNode != null && depNode != null) {
            try {
                base = BootshiftBridge.rebuildGraph(session.layout.workspace(), session.fileRegistry, buildNode, depNode,
                        "G_UNIFIED:" + batch);
            } catch (RuntimeException e) {
                session.note("Graph rebuild after " + batch + " failed: " + e.getMessage());
            }
        }
        CanonicalGraph after = CanonicalGraphBuilder.build(session.layout.runId(), session.identity.repositoryId, base,
                session.identity, session.fileRegistry, session.findings);
        Map<String, Object> diff = new LinkedHashMap<>(CanonicalGraphBuilder.diff(before, after));
        List<String> changed = new ArrayList<>(changedFiles);
        Object baseChanged = diff.get("changed_file_ids");
        if (baseChanged instanceof List<?> list) {
            list.forEach(f -> {
                if (!changed.contains(String.valueOf(f))) {
                    changed.add(String.valueOf(f));
                }
            });
        }
        diff.put("changed_file_ids", changed);
        diff.put("batch", batch);
        session.artifacts.writeJson("graph", "graph-diff-" + batch + ".json", diff);
        session.graph = after;
        session.saveGraph();
    }
}
