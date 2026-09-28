package com.mars.harness.controlcenter.run;

import com.mars.harness.controlcenter.api.ApiErrorCode;
import com.mars.harness.controlcenter.api.ApiException;
import com.mars.harness.controlcenter.api.dto.DecisionDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.controlcenter.query.DecisionMapper;
import com.mars.harness.controlcenter.query.PhaseText;
import com.mars.harness.controlcenter.query.ProposalProjector;
import com.mars.harness.controlcenter.query.RunQueryService;
import com.mars.harness.controlcenter.query.RunReader;
import com.mars.harness.controlcenter.security.CurrentActor;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.decision.DecisionActor;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.migration.CombinedAssessment;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.engine.HarnessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every state-changing operation the Control Center offers, each one a call to the engine's own
 * public API (analyze, decide*, resume) and nothing else.
 *
 * <p>Before calling the engine, a command checks what the reviewer saw against what is true now:
 * the run is at the gate the decision is for, the assessment, plan or proposal still hashes to
 * the value the reviewer inspected, and no decision was recorded meanwhile. These checks are the
 * Control Center's; the engine's own checks (decision validation, the Mutation Gateway's
 * authorization, scope, identity and base-hash checks) still apply unchanged afterwards.
 */
@Service
public class RunCommandService {

    private static final Logger LOG = LoggerFactory.getLogger(RunCommandService.class);

    private final HarnessEngine engine;
    private final RunQueryService queries;
    private final RunCoordinator coordinator;
    private final ControlCenterPaths paths;
    private final CurrentActor actor;
    private final IdempotencyStore idempotency;

    public RunCommandService(HarnessEngine engine, RunQueryService queries, RunCoordinator coordinator,
                             ControlCenterPaths paths, CurrentActor actor, IdempotencyStore idempotency) {
        this.engine = engine;
        this.queries = queries;
        this.coordinator = coordinator;
        this.paths = paths;
        this.actor = actor;
        this.idempotency = idempotency;
    }

    // ------------------------------------------------------------------ start and resume

    public RunDtos.StartRunResponse start(RunDtos.StartRunRequest request, String idempotencyKey) {
        String fingerprint = "start|" + request;
        Optional<RunDtos.StartRunResponse> replay = idempotency.replay("runs", idempotencyKey, fingerprint,
                RunDtos.StartRunResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.repository() == null || request.repository().isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILURE, null, "repository is required");
        }
        Path repository = resolveAllowed(request.repository(), true);
        List<Path> findings = new ArrayList<>();
        for (String input : request.findingInputs() == null ? List.<String>of() : request.findingInputs()) {
            findings.add(resolveAllowed(input, false));
        }
        Map<String, Path> research = new LinkedHashMap<>();
        if (request.researchInputs() != null) {
            request.researchInputs().forEach((id, p) -> research.put(id, resolveAllowed(p, false)));
        }
        Path probes = request.probes() == null || request.probes().isBlank() ? null : resolveAllowed(request.probes(), false);
        boolean skipBuild = Boolean.TRUE.equals(request.skipBuild());
        String runId = HarnessIds.allocate(HarnessIds.Kind.RUN);
        String who = actor.username();
        HarnessEngine.AnalyzeRequest analyze = new HarnessEngine.AnalyzeRequest(repository, findings, research, probes, skipBuild);
        RunCoordinator.Job job = coordinator.start(runId, RunCoordinator.JobKind.ANALYZE, who,
                "Phases 0-4: ingest, inventory, identity, graph, baseline, discovery; stops at Gate A",
                () -> engine.analyze(analyze, runId));
        LOG.info("Run {} started by {} on {}", runId, who, paths.display(repository));
        RunDtos.StartRunResponse response = new RunDtos.StartRunResponse(runId, job(job));
        idempotency.remember("runs", idempotencyKey, fingerprint, response);
        return response;
    }

    public RunDtos.CommandAccepted resume(String runId, boolean acceptPending, String idempotencyKey) {
        String fingerprint = "resume|" + runId + "|" + acceptPending;
        Optional<RunDtos.CommandAccepted> replay = idempotency.replay(runId, idempotencyKey, fingerprint,
                RunDtos.CommandAccepted.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        RunReader run = queries.reader(runId);
        RunPhase phase = run.record().machine.current;
        if (phase.terminal()) {
            throw new ApiException(ApiErrorCode.INVALID_STATE, runId, "The run is " + phase + "; there is nothing to resume");
        }
        if (PhaseText.analysisPhase(phase)) {
            throw new ApiException(ApiErrorCode.INVALID_STATE, runId, "The run stopped at " + phase + " during analysis. "
                    + "Analysis is not resumable in MARS; start a new run.");
        }
        String who = actor.username();
        RunCoordinator.Job job = coordinator.start(runId, RunCoordinator.JobKind.ADVANCE, who,
                "Advance from " + phase + (acceptPending ? " (undecided proposals stay unapproved)" : ""),
                () -> engine.resume(runId, acceptPending));
        RunDtos.CommandAccepted response = new RunDtos.CommandAccepted(runId, job(job), "MARS is advancing the run from "
                + phase + " to its next gate or its verdict");
        idempotency.remember(runId, idempotencyKey, fingerprint, response);
        return response;
    }

    // ------------------------------------------------------------------ Gate A

    public DecisionDtos.DecisionRecorded decideExecution(String runId, DecisionDtos.ExecutionDecisionRequest request,
                                                         String idempotencyKey) {
        String fingerprint = "execution|" + request;
        Optional<DecisionDtos.DecisionRecorded> replay = idempotency.replay(runId, idempotencyKey, fingerprint,
                DecisionDtos.DecisionRecorded.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DecisionActor who = actor.decisionActor();
        requireEnum(runId, request.strategy(), Decision.ExecutionStrategy.class, "strategy");
        DecisionDtos.DecisionRecorded recorded = coordinator.exclusiveThen(runId, () -> {
            RunReader run = queries.reader(runId);
            requirePhase(run, RunPhase.WAITING_FOR_EXECUTION_DECISION, "Gate A");
            CombinedAssessment combined = run.combinedAssessment().orElseThrow(() -> new ApiException(
                    ApiErrorCode.INVALID_STATE, runId, "The combined assessment is missing"));
            if (!request.expectedAssessmentHash().equals(run.record().combinedAssessmentHash)) {
                throw new ApiException(ApiErrorCode.STALE_ASSESSMENT, runId, "The assessment changed since it was reviewed",
                        Map.of("expected", request.expectedAssessmentHash(), "current",
                                String.valueOf(run.record().combinedAssessmentHash)));
            }
            if (!combined.offeredStrategies().contains(request.strategy())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILURE, runId, "Strategy " + request.strategy()
                        + " is not offered for this run", Map.of("offered", combined.offeredStrategies()));
            }
            Decision d = engine.decideExecution(runId, request.strategy(), who, request.rationale());
            DecisionDtos.DecisionRecorded result = new DecisionDtos.DecisionRecorded(view(runId, d), true,
                    "MARS is advancing the run under " + d.selected());
            idempotency.remember(runId, idempotencyKey, fingerprint, result);
            return result;
        }, RunCoordinator.JobKind.ADVANCE, who.name(), "Advance after Gate A", () -> engine.resume(runId, false));
        LOG.info("Gate A decision {} ({}) recorded for {} by {}", recorded.decision().decisionId(),
                recorded.decision().selected(), runId, who.name());
        return recorded;
    }

    // ------------------------------------------------------------------ Gate A2

    public DecisionDtos.DecisionRecorded decidePostSecurity(String runId, DecisionDtos.PostSecurityDecisionRequest request,
                                                            String idempotencyKey) {
        String fingerprint = "post-security|" + request;
        Optional<DecisionDtos.DecisionRecorded> replay = idempotency.replay(runId, idempotencyKey, fingerprint,
                DecisionDtos.DecisionRecorded.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DecisionActor who = actor.decisionActor();
        requireEnum(runId, request.choice(), Decision.MigrationChoice.class, "choice");
        return coordinator.exclusiveThen(runId, () -> {
            RunReader run = queries.reader(runId);
            requirePhase(run, RunPhase.WAITING_FOR_POST_SECURITY_MIGRATION_DECISION, "Gate A2");
            if (run.record().postSecurityDecisionId != null) {
                throw new ApiException(ApiErrorCode.DECISION_ALREADY_RECORDED, runId, "Gate A2 was already decided ("
                        + run.record().postSecurityDecisionId + ")", Map.of("decision_id", run.record().postSecurityDecisionId));
            }
            String current = run.postSecurityReassessment().map(n -> n.path("current_assessment_hash").asText(null))
                    .orElse(null);
            if (!request.expectedAssessmentHash().equals(current)) {
                throw new ApiException(ApiErrorCode.STALE_ASSESSMENT, runId, "The post-security reassessment changed since it "
                        + "was reviewed", Map.of("expected", request.expectedAssessmentHash(), "current", String.valueOf(current)));
            }
            Decision d = engine.decidePostSecurityMigration(runId, request.choice(), who, request.rationale());
            DecisionDtos.DecisionRecorded result = new DecisionDtos.DecisionRecorded(view(runId, d), true,
                    "MARS is advancing the run (Gate A2: " + d.selected() + ")");
            idempotency.remember(runId, idempotencyKey, fingerprint, result);
            return result;
        }, RunCoordinator.JobKind.ADVANCE, who.name(), "Advance after Gate A2", () -> engine.resume(runId, false));
    }

    // ------------------------------------------------------------------ migration plan approval

    public DecisionDtos.DecisionRecorded decideMigrationPlan(String runId, DecisionDtos.MigrationPlanDecisionRequest request,
                                                             String idempotencyKey) {
        String fingerprint = "migration-plan|" + request;
        Optional<DecisionDtos.DecisionRecorded> replay = idempotency.replay(runId, idempotencyKey, fingerprint,
                DecisionDtos.DecisionRecorded.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DecisionActor who = actor.decisionActor();
        requireEnum(runId, request.verdict(), Decision.ApprovalVerdict.class, "verdict");
        return coordinator.exclusiveThen(runId, () -> {
            RunReader run = queries.reader(runId);
            requirePhase(run, RunPhase.WAITING_FOR_MIGRATION_APPROVAL, "the migration plan approval");
            String planHash = run.record().migrationPlanHash;
            if (!request.expectedPlanHash().equals(planHash)) {
                throw new ApiException(ApiErrorCode.PLAN_HASH_MISMATCH, runId, "The migration plan changed since it was reviewed",
                        Map.of("expected", request.expectedPlanHash(), "current", String.valueOf(planHash)));
            }
            Optional<Decision> existing = run.latestDecision(Decision.DecisionType.MIGRATION_PLAN_APPROVAL)
                    .filter(d -> planHash.equals(d.planHash()));
            if (existing.isPresent()) {
                throw new ApiException(ApiErrorCode.DECISION_ALREADY_RECORDED, runId, "This plan was already decided ("
                        + existing.get().decisionId() + ")", Map.of("decision_id", existing.get().decisionId()));
            }
            Decision d = engine.decideMigrationPlan(runId, request.verdict(), who, request.rationale());
            DecisionDtos.DecisionRecorded result = new DecisionDtos.DecisionRecorded(view(runId, d), true,
                    "MARS is advancing the run (plan " + d.selected() + ")");
            idempotency.remember(runId, idempotencyKey, fingerprint, result);
            return result;
        }, RunCoordinator.JobKind.ADVANCE, who.name(), "Advance after migration plan decision", () -> engine.resume(runId, false));
    }

    // ------------------------------------------------------------------ Gate B (one exact proposal)

    public DecisionDtos.DecisionRecorded decideProposal(String runId, String proposalId,
                                                        DecisionDtos.ProposalDecisionRequest request, String idempotencyKey) {
        String fingerprint = "proposal|" + proposalId + "|" + request;
        Optional<DecisionDtos.DecisionRecorded> replay = idempotency.replay(runId, idempotencyKey, fingerprint,
                DecisionDtos.DecisionRecorded.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        DecisionActor who = actor.decisionActor();
        requireEnum(runId, request.verdict(), Decision.ApprovalVerdict.class, "verdict");
        return coordinator.exclusive(runId, () -> {
            RunReader run = queries.reader(runId);
            ChangeProposal proposal = run.proposal(proposalId).orElseThrow(() -> new ApiException(
                    ApiErrorCode.PROPOSAL_NOT_FOUND, runId, "No proposal " + proposalId));
            String status = run.proposalStatus(proposalId);
            ProposalProjector.Decidability decidable = ProposalProjector.decidability(run, proposal, status);
            if (!decidable.allowed()) {
                boolean stale = status != null && !List.of("PROPOSED", "AWAITING_APPROVAL").contains(status);
                throw new ApiException(stale ? ApiErrorCode.STALE_PROPOSAL : ApiErrorCode.INVALID_STATE, runId,
                        decidable.reason(), Map.of("status", String.valueOf(status), "phase",
                        run.record().machine.current.name()));
            }
            if (!request.expectedProposalHash().equals(proposal.proposalHash())) {
                throw new ApiException(ApiErrorCode.PROPOSAL_HASH_MISMATCH, runId, "The proposal no longer hashes to what was "
                        + "reviewed", Map.of("expected", request.expectedProposalHash(), "current", proposal.proposalHash()));
            }
            if (proposal.baselineSeal() != null && !proposal.baselineSeal().equals(run.record().machine.baselineSealHash)) {
                throw new ApiException(ApiErrorCode.STALE_PROPOSAL, runId, "The proposal was computed against another baseline");
            }
            Optional<Decision> existing = run.latestDecisionFor(proposalId);
            if (existing.isPresent() && !existing.get().decisionId().equals(request.supersedesDecisionId())) {
                throw new ApiException(ApiErrorCode.DECISION_ALREADY_RECORDED, runId, "Proposal " + proposalId
                        + " already has decision " + existing.get().decisionId() + " (" + existing.get().selected() + " by "
                        + existing.get().actor() + "); to change it, supersede that decision explicitly",
                        Map.of("decision_id", existing.get().decisionId(), "selected", existing.get().selected()));
            }
            if (existing.isEmpty() && request.supersedesDecisionId() != null && !request.supersedesDecisionId().isBlank()) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILURE, runId, "Decision " + request.supersedesDecisionId()
                        + " is not the proposal's current decision; there is none");
            }
            Decision d = engine.decideProposal(runId, proposalId, request.verdict(), who, request.rationale());
            DecisionDtos.DecisionRecorded result = new DecisionDtos.DecisionRecorded(view(runId, d), false,
                    "Recorded. The Mutation Gateway acts on it when the run continues (Continue execution).");
            idempotency.remember(runId, idempotencyKey, fingerprint, result);
            return result;
        });
    }

    // ------------------------------------------------------------------ helpers

    private DecisionDtos.DecisionView view(String runId, Decision d) {
        RunReader fresh = queries.reader(runId);
        return DecisionMapper.view(d, fresh.tamperedDecisions(), fresh.decisions());
    }

    private static void requirePhase(RunReader run, RunPhase phase, String gate) {
        RunPhase current = run.record().machine.current;
        if (current != phase) {
            throw new ApiException(ApiErrorCode.INVALID_STATE, run.runId(), "The run is at " + current + ", not waiting for "
                    + gate, Map.of("phase", current.name(), "expected", phase.name()));
        }
    }

    private static <E extends Enum<E>> void requireEnum(String runId, String value, Class<E> type, String field) {
        boolean ok = value != null && Arrays.stream(type.getEnumConstants()).anyMatch(c -> c.name().equals(value));
        if (!ok) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILURE, runId, "'" + value + "' is not a valid " + field,
                    Map.of("allowed", Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        }
    }

    /** A path under one of the configured repository roots, following links, or PATH_NOT_ALLOWED. */
    private Path resolveAllowed(String value, boolean directory) {
        Path given = Path.of(value.replace('\\', '/'));
        List<Path> candidates = new ArrayList<>();
        if (given.isAbsolute()) {
            candidates.add(given);
        } else {
            paths.repositoryRoots().forEach(root -> candidates.add(root.resolve(given)));
        }
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!(directory ? Files.isDirectory(normalized) : Files.isRegularFile(normalized))) {
                continue;
            }
            try {
                Path real = normalized.toRealPath();
                for (Path root : paths.repositoryRoots()) {
                    if (Files.isDirectory(root) && real.startsWith(root.toRealPath())) {
                        return real;
                    }
                }
            } catch (IOException e) {
                throw new ApiException(ApiErrorCode.PATH_NOT_ALLOWED, null, "Cannot resolve " + value);
            }
        }
        throw new ApiException(ApiErrorCode.PATH_NOT_ALLOWED, null, "'" + value + "' is not a " + (directory ? "directory"
                : "file") + " under a configured repository root", Map.of("roots", paths.repositoryRoots().stream()
                .map(r -> r.getFileName() == null ? r.toString() : r.getFileName().toString()).toList()));
    }

    private static RunDtos.Job job(RunCoordinator.Job j) {
        return new RunDtos.Job(j.kind().name(), j.triggeredBy(), j.startedAt().toString(), j.description());
    }
}
