package com.mars.harness.controlcenter.api;

import com.mars.harness.controlcenter.api.dto.DecisionDtos;
import com.mars.harness.controlcenter.api.dto.EvidenceDtos;
import com.mars.harness.controlcenter.api.dto.FindingDtos;
import com.mars.harness.controlcenter.api.dto.GraphDtos;
import com.mars.harness.controlcenter.api.dto.HumanActionDtos;
import com.mars.harness.controlcenter.api.dto.MigrationDtos;
import com.mars.harness.controlcenter.api.dto.ProposalDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.controlcenter.api.dto.ValidationDtos;
import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.controlcenter.events.EventIndex;
import com.mars.harness.controlcenter.query.DecisionMapper;
import com.mars.harness.controlcenter.query.EvidenceProjector;
import com.mars.harness.controlcenter.query.FindingProjector;
import com.mars.harness.controlcenter.query.GraphService;
import com.mars.harness.controlcenter.query.HumanActionProjector;
import com.mars.harness.controlcenter.query.MigrationProjector;
import com.mars.harness.controlcenter.query.ProposalProjector;
import com.mars.harness.controlcenter.query.RunFiles;
import com.mars.harness.controlcenter.query.RunProjector;
import com.mars.harness.controlcenter.query.RunQueryService;
import com.mars.harness.controlcenter.query.RunReader;
import com.mars.harness.controlcenter.query.ValidationProjector;
import com.mars.harness.controlcenter.run.RunCoordinator;
import com.mars.harness.controlcenter.security.CurrentActor;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only views of runs. Every response is built from the run's persisted artifacts and events;
 * none of these endpoints changes anything.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Runs (read)", description = "Snapshots of what is true now, built from persisted run artifacts")
public class RunQueryController {

    private final RunQueryService queries;
    private final RunCoordinator coordinator;
    private final ControlCenterPaths paths;
    private final EventIndex events;
    private final GraphService graphs;
    private final CurrentActor actor;

    public RunQueryController(RunQueryService queries, RunCoordinator coordinator, ControlCenterPaths paths, EventIndex events,
                              GraphService graphs, CurrentActor actor) {
        this.queries = queries;
        this.coordinator = coordinator;
        this.paths = paths;
        this.events = events;
        this.graphs = graphs;
        this.actor = actor;
    }

    @GetMapping("/runs")
    @Operation(summary = "Run history", description = "status: active | waiting | complete | failed | needs_human; "
            + "capability: migration | security; q searches run id, application and source")
    public RunDtos.RunPage runs(@RequestParam(required = false) String status, @RequestParam(required = false) String capability,
                                @RequestParam(name = "q", required = false) String search,
                                @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "50") int limit) {
        return queries.list(status, capability, search, offset, limit);
    }

    @GetMapping("/repositories")
    @Operation(summary = "Repositories under the configured roots, with candidate finding inputs")
    public List<RunDtos.RepositoryOption> repositories() {
        return queries.repositories();
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Run snapshot (overview)")
    public RunDtos.RunSnapshot run(@PathVariable String runId) {
        RunReader run = queries.reader(runId);
        return RunProjector.snapshot(run, coordinator, paths, events.all(runId), actor.mayDecide());
    }

    @GetMapping("/runs/{runId}/pipeline")
    @Operation(summary = "Pipeline stages derived from the state-machine history")
    public List<RunDtos.PipelineStage> pipeline(@PathVariable String runId) {
        return run(runId).pipeline();
    }

    @GetMapping("/runs/{runId}/timeline")
    @Operation(summary = "State transitions and decisions, in order")
    public RunDtos.Timeline timeline(@PathVariable String runId) {
        return RunProjector.timeline(queries.reader(runId));
    }

    @GetMapping("/runs/{runId}/events/history")
    @Operation(summary = "Persisted execution events after a sequence (for pagination; live updates use the SSE stream)")
    public List<ExecutionEvent> eventHistory(@PathVariable String runId, @RequestParam(defaultValue = "0") long after,
                                             @RequestParam(defaultValue = "1000") int limit) {
        queries.reader(runId);
        List<ExecutionEvent> list = events.after(runId, after);
        return list.subList(0, Math.min(list.size(), Math.max(1, Math.min(limit, 5000))));
    }

    @GetMapping("/runs/{runId}/human-actions")
    @Operation(summary = "Why the run is waiting and what a human can do")
    public HumanActionDtos.HumanActionsView humanActions(@PathVariable String runId) {
        return HumanActionProjector.view(queries.reader(runId), actor.mayDecide(), coordinator.running(runId).isPresent());
    }

    @GetMapping("/runs/{runId}/findings")
    @Operation(summary = "Security cockpit: summary and findings")
    public FindingDtos.SecurityView findings(@PathVariable String runId) {
        return FindingProjector.view(queries.reader(runId));
    }

    @GetMapping("/runs/{runId}/findings/{findingId}")
    @Operation(summary = "One finding with RCA, blast radius, plan, proposal, decisions, verification and journey")
    public FindingDtos.FindingDetail finding(@PathVariable String runId, @PathVariable String findingId) {
        RunReader run = queries.reader(runId);
        return FindingProjector.detail(run, run.finding(findingId).orElseThrow(() -> new ApiException(
                ApiErrorCode.FINDING_NOT_FOUND, runId, "No finding " + findingId)));
    }

    @GetMapping("/runs/{runId}/proposals")
    @Operation(summary = "Change explorer")
    public ProposalDtos.ChangesView proposals(@PathVariable String runId) {
        RunReader run = queries.reader(runId);
        return new ProposalDtos.ChangesView(ProposalProjector.rows(run), ProposalProjector.summary(run));
    }

    @GetMapping("/runs/{runId}/proposals/{proposalId}")
    @Operation(summary = "One proposal with its bound diff, provenance, decisions and Mutation Gateway record")
    public ProposalDtos.ProposalDetail proposal(@PathVariable String runId, @PathVariable String proposalId) {
        RunReader run = queries.reader(runId);
        return ProposalProjector.detail(run, run.proposal(proposalId).orElseThrow(() -> new ApiException(
                ApiErrorCode.PROPOSAL_NOT_FOUND, runId, "No proposal " + proposalId)), events.all(runId));
    }

    @GetMapping("/runs/{runId}/decisions")
    @Operation(summary = "Every recorded decision with its integrity status")
    public List<DecisionDtos.DecisionView> decisions(@PathVariable String runId) {
        RunReader run = queries.reader(runId);
        var all = run.decisions();
        return all.stream().map(d -> DecisionMapper.view(d, run.tamperedDecisions(), all)).toList();
    }

    @GetMapping("/runs/{runId}/migration")
    @Operation(summary = "Migration cockpit")
    public MigrationDtos.MigrationView migration(@PathVariable String runId) {
        return MigrationProjector.view(queries.reader(runId));
    }

    @GetMapping("/runs/{runId}/validation")
    @Operation(summary = "Validation dimensions, each reported separately; unknown is never shown as passing")
    public ValidationDtos.ValidationView validation(@PathVariable String runId) {
        return ValidationProjector.view(queries.reader(runId));
    }

    @GetMapping("/runs/{runId}/verdict")
    @Operation(summary = "The verdict exactly as the verdict calculator produced it")
    public ValidationDtos.VerdictView verdict(@PathVariable String runId) {
        return ValidationProjector.verdict(queries.reader(runId));
    }

    @GetMapping("/runs/{runId}/evidence")
    @Operation(summary = "Evidence plane, in hash-chain order")
    public EvidenceDtos.EvidencePage evidence(@PathVariable String runId, @RequestParam(required = false) String kind,
                                              @RequestParam(required = false) String producer,
                                              @RequestParam(required = false) String subject,
                                              @RequestParam(name = "phase", required = false) String phaseGroup,
                                              @RequestParam(name = "q", required = false) String search,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "100") int limit) {
        return EvidenceProjector.page(queries.reader(runId), new EvidenceProjector.Filter(kind, producer, subject, phaseGroup,
                search), offset, limit);
    }

    @GetMapping("/runs/{runId}/evidence/{evidenceId}")
    @Operation(summary = "One evidence record")
    public EvidenceDtos.EvidenceView evidenceRecord(@PathVariable String runId, @PathVariable String evidenceId) {
        RunReader run = queries.reader(runId);
        List<EvidenceRecord> all = run.evidence();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).evidenceId().equals(evidenceId)) {
                return EvidenceProjector.view(all.get(i), i + 1);
            }
        }
        throw new ApiException(ApiErrorCode.EVIDENCE_NOT_FOUND, runId, "No evidence " + evidenceId);
    }

    @GetMapping("/runs/{runId}/graph")
    @Operation(summary = "Canonical graph view, filtered server-side",
            description = "focus: node id; depth: 0-4; q: search; types / edge_types: comma lists; "
                    + "highlight: FINDING | CHANGED | BLAST_RADIUS | MIGRATION_ISSUE; limit: max nodes (≤ 2000)")
    public GraphDtos.GraphView graph(@PathVariable String runId, @RequestParam(required = false) String focus,
                                     @RequestParam(defaultValue = "1") int depth,
                                     @RequestParam(name = "q", required = false) String search,
                                     @RequestParam(required = false) String types,
                                     @RequestParam(name = "edge_types", required = false) String edgeTypes,
                                     @RequestParam(required = false) String highlight,
                                     @RequestParam(defaultValue = "300") int limit) {
        return graphs.view(queries.reader(runId), new GraphService.Query(focus, depth, search, csv(types), csv(edgeTypes),
                highlight, limit));
    }

    @GetMapping("/runs/{runId}/artifacts")
    @Operation(summary = "Evidence artifacts of the run (source areas are never listed)")
    public List<EvidenceDtos.ArtifactEntry> artifacts(@PathVariable String runId) {
        return RunFiles.artifacts(queries.reader(runId));
    }

    @GetMapping("/runs/{runId}/artifacts/content")
    @Operation(summary = "The text of one evidence artifact (credential-like literals masked)")
    public ResponseEntity<String> artifact(@PathVariable String runId, @RequestParam String path) {
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .body(RunFiles.artifact(queries.reader(runId), path));
    }

    @GetMapping("/runs/{runId}/logs")
    @Operation(summary = "Tool logs of the run (build rounds, verification builds, runtime)")
    public List<EvidenceDtos.LogFile> logs(@PathVariable String runId) {
        return RunFiles.logs(queries.reader(runId));
    }

    @GetMapping("/runs/{runId}/logs/content")
    @Operation(summary = "Lines of one log, with optional level and text filters; logs are not the event model")
    public EvidenceDtos.LogChunk log(@PathVariable String runId, @RequestParam String path,
                                     @RequestParam(defaultValue = "1") int from, @RequestParam(defaultValue = "2000") int max,
                                     @RequestParam(required = false) String level, @RequestParam(name = "q", required = false)
                                     String search) {
        return RunFiles.log(queries.reader(runId), path, Math.max(1, from), Math.max(1, Math.min(max, 20_000)), level, search);
    }

    private static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
