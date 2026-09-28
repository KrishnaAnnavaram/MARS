package com.mars.harness.controlcenter.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.controlcenter.api.dto.MigrationDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.core.verdict.Verdict;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.migration.MigrationCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The migration cockpit, from the assessment, plan, execution and validation artifacts. */
public final class MigrationProjector {

    private static final Set<RunPhase> RUNNING = Set.of(RunPhase.MIGRATION_RUNNING);
    private static final Set<RunPhase> VALIDATING = Set.of(RunPhase.MIGRATION_VALIDATING);

    private MigrationProjector() {
    }

    public record Status(String status, String detail, String blocker) {
    }

    /** The migration's status, from what the artifacts and state say, never from a guess. */
    public static Status status(RunReader run) {
        var record = run.record();
        Optional<MigrationCapability.MigrationPlan> plan = run.migrationPlan();
        Optional<MigrationCapability.MigrationExecution> execution = run.migrationExecution();
        RunPhase phase = record.machine.current;
        Optional<MigrationAssessment> assessment = run.migrationAssessment();
        if (plan.isPresent() && !plan.get().stopConditions().isEmpty()) {
            return new Status("BLOCKED", "The migration plan cannot execute", String.join("; ", plan.get().stopConditions()));
        }
        if (execution.isPresent()) {
            MigrationCapability.MigrationExecution e = execution.get();
            if (RUNNING.contains(phase)) {
                return new Status("RUNNING", e.rounds().size() + " round(s) recorded so far", null);
            }
            if (phase == RunPhase.NEEDS_HUMAN && e.status() == MigrationCapability.ExecutionStatus.NEEDS_HUMAN) {
                return new Status("NEEDS_HUMAN", e.needsHumanReason(), e.needsHumanReason());
            }
            if (VALIDATING.contains(phase)) {
                return new Status("VALIDATING", "rounds ended " + e.status(), null);
            }
            return switch (e.status()) {
                case GREEN -> new Status("GREEN", "green after " + e.rounds().size() + " round(s)", null);
                case NEEDS_HUMAN -> new Status("NEEDS_HUMAN", e.needsHumanReason(), e.needsHumanReason());
                case TOOL_UNAVAILABLE -> new Status("FAILED", "the build tool was unavailable: " + e.needsHumanReason(),
                        e.needsHumanReason());
                default -> new Status("FAILED", "rounds ended " + e.status(), null);
            };
        }
        if (phase == RunPhase.WAITING_FOR_MIGRATION_APPROVAL) {
            return new Status("AWAITING_PLAN_APPROVAL", "policy requires the frozen plan to be approved", null);
        }
        if (RUNNING.contains(phase)) {
            return new Status("RUNNING", "the first round is running", null);
        }
        if (record.strategy == null) {
            return new Status(assessment.map(a -> a.trafficLight() == MigrationAssessment.TrafficLight.GREEN
                    ? "NOT_REQUIRED" : "NOT_SELECTED").orElse("UNKNOWN"), "awaiting the Gate A decision", null);
        }
        boolean included = Decision.ExecutionStrategy.valueOf(record.strategy).includesMigration() || !record.migrationDeclined;
        if (!included) {
            return new Status("DECLINED", record.strategy + " at Gate A" + (record.postSecurityDecisionId != null
                    ? "; Gate A2 did not proceed" : ""), null);
        }
        return new Status("NOT_PLANNED", "the migration branch has not started yet", null);
    }

    public static RunDtos.MigrationSummary summary(RunReader run) {
        Optional<MigrationAssessment> a = run.migrationAssessment();
        Optional<MigrationCapability.MigrationPlan> plan = run.migrationPlan();
        Optional<MigrationCapability.MigrationExecution> execution = run.migrationExecution();
        Status status = status(run);
        return new RunDtos.MigrationSummary(a.map(x -> x.trafficLight().name()).orElse(null),
                a.map(x -> x.need().name()).orElse(null),
                a.map(x -> x.current() == null ? null : x.current().frameworkVersion()).orElse(null),
                plan.map(MigrationCapability.MigrationPlan::toVersion).orElse(a.map(x -> x.recommendedTarget() == null ? null
                        : x.recommendedTarget().version()).orElse(null)),
                a.map(x -> x.current() == null ? null : x.current().framework()).orElse(null), status.status(),
                status.detail(), execution.map(e -> e.rounds().size()).orElse(null),
                plan.map(MigrationCapability.MigrationPlan::maxRounds).orElse(null),
                a.map(MigrationAssessment::referencePackId).orElse(null));
    }

    public static MigrationDtos.MigrationView view(RunReader run) {
        Status status = status(run);
        var record = run.record();
        boolean selected = record.strategy != null && (Decision.ExecutionStrategy.valueOf(record.strategy).includesMigration()
                || !record.migrationDeclined);
        List<MigrationDtos.DimensionView> dims = run.migrationValidation().map(v -> v.dimensions().stream()
                .map(d -> dimension(d, false)).toList()).orElse(List.of());
        Optional<Verdict.ItemResult> item = FindingProjector.item(run, "MIGRATION");
        return new MigrationDtos.MigrationView(status.status(), status.detail(), selected,
                run.migrationAssessment().map(MigrationProjector::assessment).orElse(null),
                run.refreshedMigrationAssessment().map(MigrationProjector::assessment).orElse(null),
                run.migrationPlan().map(p -> plan(p, record.migrationPlanHash)).orElse(null),
                run.migrationExecution().map(MigrationProjector::execution).orElse(null), dims,
                run.postSecurityReassessment().map(MigrationProjector::postSecurity).orElse(null),
                item.map(i -> i.status().name()).orElse(null), item.map(Verdict.ItemResult::reason).orElse(null),
                status.blocker(), run.proposals().stream().filter(p -> p.capability() == ChangeProposal.Capability.MIGRATION)
                .map(p -> ProposalProjector.row(run, p)).toList());
    }

    static MigrationDtos.AssessmentView assessment(MigrationAssessment a) {
        MigrationAssessment.CurrentPlatform c = a.current();
        MigrationAssessment.RecommendedTarget t = a.recommendedTarget();
        List<MigrationDtos.EffortFactorView> factors = a.effort() == null ? List.of() : a.effort().factors().stream()
                .map(f -> new MigrationDtos.EffortFactorView(f.name(), f.rawValue(), f.saturation(), f.weight(),
                        f.contribution(), f.known(), f.note())).toList();
        return new MigrationDtos.AssessmentView(a.assessmentId(), a.generatedAt(), name(a.trafficLight()),
                a.trafficLightRationale(), name(a.need()), name(a.priority()), name(a.complexity()),
                a.effort() == null ? null : a.effort().migrationEffortScore(), a.effort() == null ? null
                : a.effort().weightsVersion(), factors, name(a.evidenceConfidence()), a.confidenceBasis(),
                c == null ? null : new MigrationDtos.PlatformView(c.framework(), c.frameworkVersion(), c.frameworkLine(),
                        c.javaVersion(), c.buildSystem(), c.buildModelAuthoritative(), c.lifecycleQuality(), c.supportEnds(),
                        c.endOfLife(), c.supportHorizonMonths()),
                t == null ? null : new MigrationDtos.TargetView(t.framework(), t.version(), t.line(), t.javaVersion(), t.basis()),
                a.recommendedSequence(), a.objectives().stream().map(o -> new MigrationDtos.ObjectiveView(o.objectiveId(),
                        o.description(), o.requiresMigration(), o.requiredPlatform(), o.why())).toList(),
                a.issues().stream().map(i -> new MigrationDtos.IssueView(i.issueId(), i.ruleId(), i.referenceSection(),
                        i.mandatory(), i.category(), i.subject(), i.fileId(), i.location())).toList(),
                a.blockers().stream().map(b -> new MigrationDtos.NamedItem(b.blockerId(), b.description(), b.kind(),
                        b.evidenceRefs())).toList(),
                a.unknowns().stream().map(u -> new MigrationDtos.NamedItem(u.dimension(), u.question(), u.impact(),
                        u.evidenceRefs())).toList(), a.referencePackId(), a.referencePackSha256(), a.evidence());
    }

    static MigrationDtos.PlanView plan(MigrationCapability.MigrationPlan p, String planHash) {
        return new MigrationDtos.PlanView(p.planId(), planHash, p.engine(), p.referencePackId(), p.referencePackSha256(),
                p.framework(), p.fromVersion(), p.toVersion(), p.fromJava(), p.toJava(), rules(p.buildFileRules()),
                rules(p.symptomRules()), p.allowedRuleIds(), p.maxRounds(), p.stopConditions(), p.generatedAt(),
                p.evidenceRefs());
    }

    private static List<MigrationDtos.RuleView> rules(List<MigrationCapability.PlannedRule> rules) {
        return rules.stream().map(r -> new MigrationDtos.RuleView(r.ruleId(), r.section(), r.phase(), r.description(),
                r.symptoms())).toList();
    }

    static MigrationDtos.ExecutionView execution(MigrationCapability.MigrationExecution e) {
        List<MigrationDtos.RoundView> rounds = new ArrayList<>();
        for (MigrationCapability.RoundRecord r : e.rounds()) {
            rounds.add(new MigrationDtos.RoundView(r.round(), r.label(), r.intent(), r.outcome(), r.exitCode(), r.durationMs(),
                    r.jdk(), r.errorsByCategory(), r.errorCount(), r.errors().stream().limit(50)
                    .map(x -> new MigrationDtos.BuildErrorView(x.file(), x.line(), x.category(), x.message())).toList(),
                    r.errorsTruncated() || r.errors().size() > 50, tests(r.tests()), r.appliedRules(), r.proposalIds(),
                    r.changeIds(), r.diagnosis(), r.logRef(), r.evidenceRefs()));
        }
        MigrationCapability.RuntimeComparison b = e.behaviour();
        MigrationCapability.TestComparison t = e.tests();
        return new MigrationDtos.ExecutionView(e.planId(), e.status().name(), rounds, e.proposalIds(), e.changeIds(),
                e.unmatchedErrors(), e.needsHumanReason(),
                b == null ? null : new MigrationDtos.BehaviourView(b.overall(), b.verdict(), b.probes().stream()
                        .map(p -> new MigrationDtos.ProbeDiffView(p.name(), p.verdict(), p.beforeStatus(), p.afterStatus(),
                                p.note())).toList(), b.baselineStarted(), b.finalStarted(), b.explanations()),
                t == null ? null : new MigrationDtos.TestComparisonView(t.verdict(), tests(t.before()), tests(t.after()),
                        t.newFailures(), t.preExistingFailures()), e.reportRef() == null ? null : "reports/migration");
    }

    static MigrationDtos.TestsView tests(BuildPort.TestSummary s) {
        return s == null ? null : new MigrationDtos.TestsView(s.run(), s.failures(), s.errors(), s.skipped());
    }

    public static MigrationDtos.DimensionView dimension(ValidationResult.DimensionResult d, boolean mandatory) {
        return new MigrationDtos.DimensionView(d.dimension().name(), d.status().name(), d.detail(), d.summary(),
                d.evidenceRefs(), d.producer(), mandatory);
    }

    static MigrationDtos.PostSecurityView postSecurity(JsonNode n) {
        List<String> changed = new ArrayList<>();
        n.path("what_changed").forEach(c -> changed.add(c.asText()));
        return new MigrationDtos.PostSecurityView(n.path("previous_traffic_light").asText(null),
                n.path("current_traffic_light").asText(null),
                n.hasNonNull("previous_effort_score") ? n.path("previous_effort_score").asInt() : null,
                n.hasNonNull("current_effort_score") ? n.path("current_effort_score").asInt() : null, changed,
                n.path("why").asText(null), n.path("current_assessment_hash").asText(null));
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
