package com.mars.harness.controlcenter.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.controlcenter.api.dto.HumanActionDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.controlcenter.run.RunCoordinator;
import com.mars.harness.kernel.core.event.ActivityStatus;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.run.RunStateMachine;
import com.mars.harness.kernel.engine.run.RunRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Run history rows and the overview snapshot. */
public final class RunProjector {

    /** Events appended this recently, with no job of this server running, mean another process is advancing. */
    static final Duration EXTERNAL_ACTIVITY_WINDOW = Duration.ofSeconds(90);
    private static final Set<String> GENERIC_ACTIVITIES = Set.of("run.advance", "run.state", "run.create");

    private RunProjector() {
    }

    public static RunDtos.RunSummary summary(RunReader run, RunCoordinator coordinator, ControlCenterPaths paths,
                                             List<ExecutionEvent> events) {
        RunRecord record = run.record();
        RunPhase phase = record.machine.current;
        HumanActionDtos.HumanActionsView actions = HumanActionProjector.view(run, false, coordinator.running(run.runId())
                .isPresent());
        int humanActions = actions.actions().stream().mapToInt(a -> a.proposals().isEmpty() ? 1
                : (int) a.proposals().stream().filter(r -> r.awaitingDecision()).count()).sum();
        return new RunDtos.RunSummary(run.runId(), application(record), source(record, paths), record.createdAt,
                updatedAt(run), phase.name(), PhaseText.label(phase), phase.waitingForHuman(), phase.terminal(),
                record.verdict, record.strategy, MigrationProjector.status(run).status(),
                securityLine(run), run.findings().size(), humanActions,
                actions.actions().isEmpty() ? null : actions.actions().get(0).gate(),
                liveness(run, coordinator, events), record.harnessVersion);
    }

    public static RunDtos.RunSnapshot snapshot(RunReader run, RunCoordinator coordinator, ControlCenterPaths paths,
                                               List<ExecutionEvent> events, boolean callerMayDecide) {
        RunRecord record = run.record();
        RunPhase phase = record.machine.current;
        boolean advancing = coordinator.running(run.runId()).isPresent();
        MigrationProjector.Status migration = MigrationProjector.status(run);
        List<RunDtos.PipelineStage> pipeline = PipelineProjector.project(record, new PipelineProjector.Context(advancing,
                "BLOCKED".equals(migration.status()) ? migration.blocker() : null, record.verdict, stageSummaries(run)));
        HumanActionDtos.HumanActionsView actions = HumanActionProjector.view(run, callerMayDecide, advancing);
        int count = actions.actions().stream().mapToInt(a -> a.proposals().isEmpty() ? 1
                : (int) Math.max(1, a.proposals().stream().filter(r -> r.awaitingDecision()).count())).sum();
        RunDtos.HumanActionsSummary human = new RunDtos.HumanActionsSummary(actions.actions().isEmpty() ? 0 : count,
                actions.actions().isEmpty() ? null : actions.actions().get(0).gate(),
                actions.actions().isEmpty() ? null : actions.actions().get(0).title());
        List<String> notes = record.notes.stream().map(paths::redact).toList();
        Optional<JsonNode> env = run.environment();
        RunDtos.Liveness liveness = liveness(run, coordinator, events);
        // a run MARS stopped on purpose (waiting, finished, failed) has no activity in progress, whatever was left open
        boolean mayBeWorking = Set.of("ADVANCING", "EXTERNAL_ACTIVITY", "IDLE", "INTERRUPTED_ANALYSIS").contains(liveness.state());
        return new RunDtos.RunSnapshot(run.runId(), application(record), source(record, paths), record.createdAt,
                updatedAt(run), phase.name(), PhaseText.label(phase), PhaseText.description(phase), phase.waitingForHuman(),
                phase.terminal(), liveness, record.verdict, record.strategy,
                record.executionDecisionId, record.machine.baselineSealHash, record.harnessVersion, record.policyVersion,
                record.today, record.skipBuild, events.isEmpty() ? 0 : events.get(events.size() - 1).sequence(), pipeline,
                PipelineProjector.path(record), PipelineProjector.progress(pipeline, record),
                mayBeWorking ? currentActivity(events, advancing) : null, human,
                MigrationProjector.summary(run), FindingProjector.summary(run), ProposalProjector.summary(run),
                ValidationProjector.summary(run), notes.subList(Math.max(0, notes.size() - 20), notes.size()),
                new RunDtos.Integrity(run.decisions().size(), run.tamperedDecisions(), run.evidence().size(),
                        run.evidenceChainViolations()),
                env.map(e -> new RunDtos.Environment(e.path("java_version").asText(null), e.path("os").asText(null),
                        e.path("build_tool").asText(null), e.hasNonNull("build_tool_available")
                        ? e.path("build_tool_available").asBoolean() : null,
                        paths.redact(e.path("build_tool_unavailable_reason").asText(null)),
                        e.hasNonNull("network_enabled") ? e.path("network_enabled").asBoolean() : null)).orElse(null));
    }

    public static RunDtos.Timeline timeline(RunReader run) {
        List<RunDtos.Transition> transitions = new ArrayList<>();
        List<RunStateMachine.Transition> history = run.record().machine.history;
        for (int i = 0; i < history.size(); i++) {
            RunStateMachine.Transition t = history.get(i);
            transitions.add(new RunDtos.Transition(i, t.from().name(), t.to().name(), t.at(), t.reason()));
        }
        var all = run.decisions();
        return new RunDtos.Timeline(run.runId(), transitions, all.stream()
                .map(d -> DecisionMapper.view(d, run.tamperedDecisions(), all)).toList());
    }

    static RunDtos.Liveness liveness(RunReader run, RunCoordinator coordinator, List<ExecutionEvent> events) {
        RunPhase phase = run.record().machine.current;
        Optional<RunCoordinator.Job> job = coordinator.running(run.runId());
        RunDtos.LastJob last = coordinator.lastFinished(run.runId()).map(f -> new RunDtos.LastJob(f.kind().name(),
                f.startedAt().toString(), f.finishedAt().toString(), f.failed(), f.error())).orElse(null);
        RunDtos.Job current = job.map(RunProjector::job).orElse(null);
        if (job.isPresent()) {
            return new RunDtos.Liveness("ADVANCING", "This server is " + (job.get().kind() == RunCoordinator.JobKind.ANALYZE
                    ? "analyzing" : "advancing") + " the run.", current, last);
        }
        if (phase == RunPhase.COMPLETE) {
            return new RunDtos.Liveness("FINISHED", "The run is complete.", null, last);
        }
        if (phase == RunPhase.FAILED) {
            return new RunDtos.Liveness("FAILED", "The run failed.", null, last);
        }
        if (phase.waitingForHuman()) {
            return new RunDtos.Liveness("WAITING_FOR_HUMAN", "MARS stopped and is waiting for a human.", null, last);
        }
        if (!events.isEmpty()) {
            Instant latest = Instant.parse(events.get(events.size() - 1).timestamp());
            // events up to the end of this server's own last job are this server's, not another process's
            boolean ours = coordinator.lastFinished(run.runId())
                    .map(f -> !f.finishedAt().plusSeconds(2).isBefore(latest)).orElse(false);
            if (!ours && Duration.between(latest, Instant.now()).compareTo(EXTERNAL_ACTIVITY_WINDOW) < 0) {
                return new RunDtos.Liveness("EXTERNAL_ACTIVITY", "Events were appended " + Duration.between(latest,
                        Instant.now()).toSeconds() + "s ago by a process other than this server (for example the CLI).",
                        null, last);
            }
        }
        if (PhaseText.analysisPhase(phase)) {
            return new RunDtos.Liveness("INTERRUPTED_ANALYSIS", "The analysis stopped at " + phase + " before Gate A. "
                    + "Analysis is not resumable: start a new run.", null, last);
        }
        return new RunDtos.Liveness("IDLE", "Nothing is advancing this run. It is not waiting for a human: resuming "
                + "continues it from its persisted state.", null, last);
    }

    static RunDtos.Job job(RunCoordinator.Job j) {
        return new RunDtos.Job(j.kind().name(), j.triggeredBy(), j.startedAt().toString(), j.description());
    }

    /** The latest activity reported STARTED or PROGRESS whose completion has not been reported. */
    static RunDtos.CurrentActivity currentActivity(List<ExecutionEvent> events, boolean advancing) {
        Map<String, ExecutionEvent> lastByActivity = new LinkedHashMap<>();
        Map<String, ExecutionEvent> startedByActivity = new HashMap<>();
        for (ExecutionEvent e : events) {
            if (e.activity() == null || GENERIC_ACTIVITIES.contains(e.activity())) {
                continue;
            }
            lastByActivity.put(e.activity(), e);
            if (e.status() == ActivityStatus.STARTED) {
                startedByActivity.put(e.activity(), e);
            }
        }
        ExecutionEvent open = null;
        for (ExecutionEvent e : lastByActivity.values()) {
            if ((e.status() == ActivityStatus.STARTED || e.status() == ActivityStatus.PROGRESS)
                    && (open == null || e.sequence() > open.sequence())) {
                open = e;
            }
        }
        if (open == null) {
            return null;
        }
        ExecutionEvent started = startedByActivity.getOrDefault(open.activity(), open);
        return new RunDtos.CurrentActivity(open.activity(), open.component(), open.title(), open.message(),
                open.status().name(), started.timestamp(), open.timestamp(), open.progress(), open.subjects(),
                open.sequence(), !advancing, open.attributes().get("capability"));
    }

    private static Map<String, String> stageSummaries(RunReader run) {
        Map<String, String> s = new HashMap<>();
        RunRecord record = run.record();
        run.combinedAssessment().ifPresent(c -> s.put("DISCOVERY", c.security().total() + " open finding(s); migration "
                + c.trafficLight() + "; MARS recommends " + c.recommendedStrategy()));
        if (record.executionDecisionId != null) {
            s.put("GATE_A", record.strategy + " (" + record.executionDecisionId + ")");
        }
        run.migrationExecution().ifPresent(e -> s.put("MIGRATION_ROUNDS", e.rounds().size() + " round(s), " + e.status()));
        run.migrationPlan().ifPresent(p -> s.put("MIGRATION_PLAN", p.stopConditions().isEmpty() ? p.framework() + " "
                + p.fromVersion() + " → " + p.toVersion() + " (" + p.allowedRuleIds().size() + " rules)"
                : "cannot execute: " + String.join("; ", p.stopConditions())));
        List<com.mars.harness.kernel.core.change.ChangeProposal> gated = run.proposals().stream()
                .filter(p -> p.capability() != com.mars.harness.kernel.core.change.ChangeProposal.Capability.MIGRATION).toList();
        long decided = gated.stream().filter(p -> run.latestDecisionFor(p.proposalId()).isPresent()).count();
        if (!gated.isEmpty()) {
            s.put("GATE_B", decided + " of " + gated.size() + " remediation proposal(s) decided");
        }
        if (!record.verification.isEmpty()) {
            long cleared = record.verification.values().stream().filter("Cleared"::equals).count();
            s.put("FIX_VERIFICATION", cleared + " of " + record.verification.size() + " fix(es) Cleared");
        }
        run.validation().ifPresent(v -> s.put("FINAL_VALIDATION", ValidationProjector.summary(run).status() + " ("
                + v.dimensions().size() + " dimensions)"));
        if (record.verdict != null) {
            s.put("VERDICT", record.verdict);
        }
        return s;
    }

    static String application(RunRecord record) {
        return record.repository == null ? null : Path.of(record.repository).getFileName().toString();
    }

    static String source(RunRecord record, ControlCenterPaths paths) {
        return record.repository == null ? null : paths.display(Path.of(record.repository));
    }

    /** When the run last changed state: the last persisted transition (file times are not evidence). */
    static String updatedAt(RunReader run) {
        List<RunStateMachine.Transition> history = run.record().machine.history;
        if (!history.isEmpty()) {
            return history.get(history.size() - 1).at();
        }
        try {
            return Files.getLastModifiedTime(run.layout().state()).toInstant().toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static String securityLine(RunReader run) {
        var s = FindingProjector.summary(run);
        if (s.total() == 0) {
            return run.securityDiscovery().isPresent() ? "no findings" : "not discovered";
        }
        return s.total() + " finding(s)" + (s.cleared() > 0 ? ", " + s.cleared() + " cleared" : "")
                + (s.proposalsAwaiting() > 0 ? ", " + s.proposalsAwaiting() + " awaiting decision" : "");
    }
}
