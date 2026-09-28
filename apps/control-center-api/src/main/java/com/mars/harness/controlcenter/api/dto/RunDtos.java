package com.mars.harness.controlcenter.api.dto;

import com.mars.harness.kernel.core.event.ExecutionEvent;

import java.util.List;
import java.util.Map;

/** Run-level read models. Every value is read from the run's persisted artifacts. */
public final class RunDtos {

    private RunDtos() {
    }

    /** One row of the run history. */
    public record RunSummary(String runId, String application, String source, String createdAt, String updatedAt,
                             String phase, String phaseLabel, boolean waitingForHuman, boolean terminal, String verdict,
                             String strategy, String migration, String security, int findings, int humanActions,
                             String gate, Liveness liveness, String harnessVersion) {
    }

    /**
     * Whether anything is advancing the run, as far as this server can know.
     *
     * @param state ADVANCING (this server is running an engine operation), WAITING_FOR_HUMAN,
     *              FINISHED, FAILED, IDLE (not waiting, not finished, and this server is not
     *              advancing it: resume continues it) or INTERRUPTED_ANALYSIS (the analysis
     *              stopped before Gate A; analysis is not resumable, a new run is needed)
     */
    public record Liveness(String state, String explanation, Job job, LastJob lastJob) {
    }

    public record Job(String kind, String triggeredBy, String startedAt, String description) {
    }

    public record LastJob(String kind, String startedAt, String finishedAt, boolean failed, String error) {
    }

    /** The overview snapshot: what is true now. */
    public record RunSnapshot(String runId, String application, String source, String createdAt, String updatedAt,
                              String phase, String phaseLabel, String phaseDescription, boolean waitingForHuman,
                              boolean terminal, Liveness liveness, String verdict, String strategy,
                              String executionDecisionId, String baselineSeal, String harnessVersion, String policyVersion,
                              String evaluationDate, boolean skipBuild, long lastEventSequence, List<PipelineStage> pipeline,
                              StageProgress stageProgress, CurrentActivity currentActivity, HumanActionsSummary humanActions,
                              MigrationSummary migration, SecuritySummary security, ChangesSummary changes,
                              ValidationSummary validation, List<String> notes, Integrity integrity,
                              Environment environment) {
    }

    /**
     * Workflow position counted in stages actually completed out of the stages that apply to the
     * path this run is on. Not a time estimate; stages differ widely in duration.
     */
    public record StageProgress(int completed, int applicable, String basis) {
    }

    /**
     * One stage of the real lifecycle, derived from the state machine history.
     *
     * @param status COMPLETED, ACTIVE, WAITING, BLOCKED, FAILED, SKIPPED, PENDING or IDLE
     */
    public record PipelineStage(String id, String label, String group, String status, List<String> phases,
                                String enteredAt, String exitedAt, Long durationMs, String reason, String summary,
                                List<String> next, int entries) {
    }

    /**
     * The most recent activity the engine reported as started and not yet completed.
     *
     * @param stale true when this server is not advancing the run: the report may describe work a
     *              stopped process never finished (or a CLI process still running)
     */
    public record CurrentActivity(String activity, String component, String title, String message, String status,
                                  String startedAt, String lastUpdateAt, ExecutionEvent.Progress progress,
                                  List<ExecutionEvent.SubjectRef> subjects, long sequence, boolean stale, String capability) {
    }

    public record HumanActionsSummary(int count, String gate, String headline) {
    }

    /**
     * @param status NOT_SELECTED, NOT_PLANNED, BLOCKED, AWAITING_PLAN_APPROVAL, RUNNING, VALIDATING,
     *               GREEN, NEEDS_HUMAN, FAILED, DECLINED, NOT_REQUIRED or UNKNOWN (from the artifacts)
     */
    public record MigrationSummary(String trafficLight, String need, String currentVersion, String targetVersion,
                                   String framework, String status, String statusDetail, Integer rounds, Integer maxRounds,
                                   String referencePack) {
    }

    public record SecuritySummary(int total, Map<String, Integer> bySeverity, int analysed, int planned,
                                  int proposalsAwaiting, int applied, int cleared, int blocked, int deferred, int rejected,
                                  int blockedByPlatform) {
    }

    public record ChangesSummary(int total, Map<String, Integer> byStatus, Map<String, Integer> byCapability) {
    }

    /** @param status NOT_RUN, PASSED, FAILED, INCOMPLETE (mandatory dimensions unknown) */
    public record ValidationSummary(String status, int dimensions, int passed, int failed, int unknown,
                                    List<String> mandatoryFailed, List<String> mandatoryUnknown) {
    }

    /** Recomputed on read: decisions' integrity hashes and the evidence hash chain. */
    public record Integrity(int decisions, List<String> tamperedDecisions, int evidenceRecords,
                            List<String> evidenceChainViolations) {
    }

    public record Environment(String javaVersion, String os, String buildTool, Boolean buildToolAvailable,
                              String buildToolUnavailableReason, Boolean networkEnabled) {
    }

    /** The state machine history: the authoritative timeline. */
    public record Transition(int index, String from, String to, String at, String reason) {
    }

    public record Timeline(String runId, List<Transition> transitions, List<DecisionDtos.DecisionView> decisions) {
    }

    public record RunPage(List<RunSummary> runs, int total) {
    }

    public record StartRunRequest(String repository, List<String> findingInputs, Map<String, String> researchInputs,
                                  String probes, Boolean skipBuild) {
    }

    public record StartRunResponse(String runId, Job job) {
    }

    public record ResumeRequest(Boolean acceptPending) {
    }

    public record CommandAccepted(String runId, Job job, String message) {
    }

    /** A repository the Control Center may start a run on (under a configured root). */
    public record RepositoryOption(String path, String name, String root, List<String> findingInputs, String probes) {
    }
}
