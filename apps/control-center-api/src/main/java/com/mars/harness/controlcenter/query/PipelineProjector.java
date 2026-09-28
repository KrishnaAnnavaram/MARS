package com.mars.harness.controlcenter.query;

import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.run.RunStateMachine;
import com.mars.harness.kernel.engine.run.RunRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects the run's state-machine history onto the stages of the real MARS lifecycle.
 *
 * <p>Nothing here is a second lifecycle. Every stage is a named group of {@link RunPhase}s, and a
 * stage's status follows from the persisted transitions alone: entered and left means COMPLETED;
 * current means ACTIVE, WAITING or IDLE (the last only when no process of this server is advancing
 * the run); never entered means PENDING, or SKIPPED once the run's path excludes it.
 */
public final class PipelineProjector {

    public record StageDef(String id, String label, String group, Set<RunPhase> phases) {
    }

    public static final List<StageDef> STAGES = List.of(
            new StageDef("SNAPSHOT", "Source snapshot", "ANALYSIS", EnumSet.of(RunPhase.SOURCE_SNAPSHOTTED)),
            new StageDef("INVENTORY", "Inventory", "ANALYSIS", EnumSet.of(RunPhase.INVENTORY_READY)),
            new StageDef("IDENTITY", "Identity", "ANALYSIS", EnumSet.of(RunPhase.IDENTITY_SEALED)),
            new StageDef("GRAPH", "Canonical graph", "ANALYSIS", EnumSet.of(RunPhase.GRAPH_READY)),
            new StageDef("BASELINE", "Baseline seal", "ANALYSIS", EnumSet.of(RunPhase.BASELINE_SEALED)),
            new StageDef("DISCOVERY", "Discovery (scan, RCA, blast radius, assessment)", "ANALYSIS",
                    EnumSet.of(RunPhase.DISCOVERY_RUNNING, RunPhase.DISCOVERY_READY)),
            new StageDef("GATE_A", "Human Gate A", "GATE", EnumSet.of(RunPhase.WAITING_FOR_EXECUTION_DECISION)),
            new StageDef("EXECUTION_PLAN", "Execution plan", "GATE", EnumSet.of(RunPhase.EXECUTION_PLANNED)),
            new StageDef("MIGRATION_PLAN", "Migration plan", "MIGRATION",
                    EnumSet.of(RunPhase.MIGRATION_PLANNED, RunPhase.WAITING_FOR_MIGRATION_APPROVAL)),
            new StageDef("MIGRATION_ROUNDS", "Migration rounds", "MIGRATION", EnumSet.of(RunPhase.MIGRATION_RUNNING)),
            new StageDef("MIGRATION_VALIDATION", "Migration validation", "MIGRATION",
                    EnumSet.of(RunPhase.MIGRATION_VALIDATING, RunPhase.MIGRATION_COMPLETE)),
            new StageDef("REMEDIATION_PLANNING", "Remediation planning (routing)", "SECURITY",
                    EnumSet.of(RunPhase.SECURITY_FINDINGS_READY, RunPhase.SECURITY_ANALYSIS_RUNNING,
                            RunPhase.REMEDIATION_PROPOSED)),
            new StageDef("GATE_B", "Human Gate B", "SECURITY", EnumSet.of(RunPhase.WAITING_FOR_REMEDIATION_APPROVAL)),
            new StageDef("MUTATION", "Mutation Gateway", "SECURITY", EnumSet.of(RunPhase.REMEDIATION_RUNNING)),
            new StageDef("FIX_VERIFICATION", "Fix verification", "SECURITY",
                    EnumSet.of(RunPhase.SECURITY_VALIDATING, RunPhase.SECURITY_COMPLETE)),
            new StageDef("GATE_A2", "Human Gate A2", "GATE",
                    EnumSet.of(RunPhase.WAITING_FOR_POST_SECURITY_MIGRATION_DECISION)),
            new StageDef("FINAL_VALIDATION", "Final validation", "FINAL", EnumSet.of(RunPhase.FINAL_VALIDATION)),
            new StageDef("VERDICT", "Verdict", "FINAL", EnumSet.of(RunPhase.CLEARED, RunPhase.PARTIAL, RunPhase.BLOCKED,
                    RunPhase.COMPLETE)));

    private static final Set<String> ANALYSIS_STAGES = Set.of("SNAPSHOT", "INVENTORY", "IDENTITY", "GRAPH", "BASELINE",
            "DISCOVERY");

    private PipelineProjector() {
    }

    /** Facts the projection needs beyond the history. */
    public record Context(boolean advancing, String migrationBlocker, String verdict, Map<String, String> summaries) {
    }

    public static List<RunDtos.PipelineStage> project(RunRecord record, Context context) {
        List<RunStateMachine.Transition> history = record.machine.history;
        Map<String, Visit> visits = new LinkedHashMap<>();
        String open = null;
        for (RunStateMachine.Transition t : history) {
            String stage = stageOfTransition(t);
            if (stage == null) {
                continue;
            }
            if (!stage.equals(open)) {
                if (open != null) {
                    visits.get(open).exitedAt = t.at();
                }
                Visit visit = visits.computeIfAbsent(stage, k -> new Visit());
                visit.enteredAt = t.at();
                visit.exitedAt = null;
                visit.entries++;
                visit.reason = t.reason();
                open = stage;
            } else {
                visits.get(stage).reason = t.reason();
            }
        }
        RunPhase current = record.machine.current;
        boolean reachedFinal = visits.containsKey("FINAL_VALIDATION") || visits.containsKey("VERDICT")
                || current == RunPhase.FAILED;
        Set<String> applicable = applicableBranches(record);

        List<RunDtos.PipelineStage> stages = new ArrayList<>();
        for (StageDef def : STAGES) {
            Visit visit = visits.get(def.id());
            String status;
            if (visit == null) {
                if (reachedFinal) {
                    status = "SKIPPED";
                } else if (def.id().equals("GATE_A2")) {
                    status = gateA2Possible(record) ? "PENDING" : "SKIPPED";
                } else if (record.strategy == null) {
                    status = "PENDING";
                } else {
                    status = applicable.contains(def.group()) ? "PENDING" : "SKIPPED";
                }
            } else if (def.id().equals(open)) {
                status = currentStatus(def, record, context);
            } else {
                status = "COMPLETED";
            }
            if ("MIGRATION_PLAN".equals(def.id()) && visit != null && context.migrationBlocker() != null) {
                status = "BLOCKED";
            }
            Long duration = null;
            if (visit != null && visit.enteredAt != null && visit.exitedAt != null) {
                duration = Duration.between(Instant.parse(visit.enteredAt), Instant.parse(visit.exitedAt)).toMillis();
            }
            String summary = context.summaries().getOrDefault(def.id(), visit == null ? null : visit.reason);
            stages.add(new RunDtos.PipelineStage(def.id(), def.label(), def.group(), status, def.phases().stream()
                    .map(Enum::name).toList(), visit == null ? null : visit.enteredAt, visit == null ? null : visit.exitedAt,
                    duration, visit == null ? null : visit.reason, summary, next(def, current),
                    visit == null ? 0 : visit.entries));
        }
        return stages;
    }

    /** Stages completed, out of the stages that apply to this run's path so far. */
    public static RunDtos.StageProgress progress(List<RunDtos.PipelineStage> stages, RunRecord record) {
        int completed = (int) stages.stream().filter(s -> s.status().equals("COMPLETED")).count();
        int applicable = (int) stages.stream().filter(s -> !s.status().equals("SKIPPED")).count();
        String basis = record.strategy == null
                ? "stages completed so far; which execution stages apply is decided at Gate A"
                : "stages completed out of the stages on the " + record.strategy + " path; not a time estimate";
        return new RunDtos.StageProgress(completed, applicable, basis);
    }

    private static String currentStatus(StageDef def, RunRecord record, Context context) {
        RunPhase current = record.machine.current;
        if (current == RunPhase.FAILED) {
            return "FAILED";
        }
        if (def.id().equals("VERDICT")) {
            if (current == RunPhase.NEEDS_HUMAN) {
                return "WAITING";
            }
            return "BLOCKED".equals(context.verdict()) ? "BLOCKED" : "COMPLETED";
        }
        if (current.waitingForHuman()) {
            return "WAITING";
        }
        if (context.advancing()) {
            return "ACTIVE";
        }
        return "IDLE";
    }

    /** The stage a transition moves the run into. NEEDS_HUMAN and FAILED stay in the stage that escalated. */
    static String stageOfTransition(RunStateMachine.Transition t) {
        RunPhase to = t.to();
        if (to == RunPhase.NEEDS_HUMAN) {
            return t.from() == RunPhase.FINAL_VALIDATION ? "VERDICT" : stageOf(t.from());
        }
        if (to == RunPhase.FAILED) {
            return stageOf(t.from());
        }
        return stageOf(to);
    }

    static String stageOf(RunPhase phase) {
        for (StageDef def : STAGES) {
            if (def.phases().contains(phase)) {
                return def.id();
            }
        }
        return phase == RunPhase.NEEDS_HUMAN ? "VERDICT" : null;
    }

    private static Set<String> applicableBranches(RunRecord record) {
        Set<String> groups = new LinkedHashSet<>(List.of("ANALYSIS", "GATE", "FINAL"));
        if (record.strategy == null) {
            return groups;
        }
        Decision.ExecutionStrategy strategy = Decision.ExecutionStrategy.valueOf(record.strategy);
        // a security-only path may still migrate, if Gate A2 is reached and a human chooses PROCEED
        boolean migrationStillPossible = strategy.includesSecurity() && !record.stopRequested
                && (record.postSecurityDecisionId == null ? gateA2Possible(record) : !record.migrationDeclined);
        if (strategy.includesMigration() || migrationStillPossible) {
            groups.add("MIGRATION");
        }
        if (strategy.includesSecurity()) {
            groups.add("SECURITY");
        }
        return groups;
    }

    private static boolean gateA2Possible(RunRecord record) {
        if (record.strategy == null) {
            return true;
        }
        Decision.ExecutionStrategy strategy = Decision.ExecutionStrategy.valueOf(record.strategy);
        if (strategy.includesMigration() || !strategy.includesSecurity() || record.stopRequested) {
            return false;
        }
        // reassessed without offering A2 means the migration came out GREEN: the gate is not offered
        return !record.postSecurityReassessed || record.postSecurityDecisionId != null
                || record.machine.current == RunPhase.WAITING_FOR_POST_SECURITY_MIGRATION_DECISION;
    }

    private static List<String> next(StageDef def, RunPhase current) {
        Set<String> next = new LinkedHashSet<>();
        Set<RunPhase> from = def.phases().contains(current) ? Set.of(current) : def.phases();
        for (RunPhase phase : from) {
            for (RunPhase successor : RunStateMachine.successorsOf(phase)) {
                String stage = successor == RunPhase.NEEDS_HUMAN || successor == RunPhase.FAILED ? null : stageOf(successor);
                if (stage != null && !stage.equals(def.id())) {
                    next.add(stage);
                }
            }
        }
        return List.copyOf(next);
    }

    public static boolean analysisStage(String stageId) {
        return ANALYSIS_STAGES.contains(stageId);
    }

    private static final class Visit {
        String enteredAt;
        String exitedAt;
        String reason;
        int entries;
    }
}
