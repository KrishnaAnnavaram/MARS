package com.mars.harness.controlcenter.api;

import com.mars.harness.controlcenter.api.dto.DecisionDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.controlcenter.run.IdempotencyStore;
import com.mars.harness.controlcenter.run.RunCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Commands. Each one ends in exactly one engine call: {@code analyze}, {@code resume} or a
 * {@code decide*}. Decisions are recorded by the engine through the ApprovalPort; the actor is the
 * authenticated caller, never a value from the request.
 *
 * <p>Every command accepts an optional {@code Idempotency-Key} header: a retry with the same key
 * returns the first attempt's response instead of acting twice.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Runs (commands)", description = "Start, resume and decide; all through the MARS engine")
public class RunCommandController {

    private static final String KEY = "Idempotency-Key";

    private final RunCommandService commands;

    public RunCommandController(RunCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/runs")
    @Operation(summary = "Start a run (phases 0-4, stopping at Gate A). OPERATOR.")
    public ResponseEntity<RunDtos.StartRunResponse> start(@RequestBody RunDtos.StartRunRequest request,
                                                          @Parameter(description = "optional retry key")
                                                          @RequestHeader(value = KEY, required = false) String key) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(commands.start(request, key));
    }

    @PostMapping("/runs/{runId}/resume")
    @Operation(summary = "Advance the run to its next gate or its verdict (the CLI's resume). OPERATOR.")
    public ResponseEntity<RunDtos.CommandAccepted> resume(@PathVariable String runId,
                                                          @RequestBody(required = false) RunDtos.ResumeRequest request,
                                                          @RequestHeader(value = KEY, required = false) String key) {
        boolean acceptPending = request != null && Boolean.TRUE.equals(request.acceptPending());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(commands.resume(runId, acceptPending, key));
    }

    @PostMapping("/runs/{runId}/decisions/execution")
    @Operation(summary = "Human Gate A: record the execution strategy, then advance. APPROVER.")
    public DecisionDtos.DecisionRecorded execution(@PathVariable String runId,
                                                   @Valid @RequestBody DecisionDtos.ExecutionDecisionRequest request,
                                                   @RequestHeader(value = KEY, required = false) String key) {
        return commands.decideExecution(runId, request, key);
    }

    @PostMapping("/runs/{runId}/decisions/post-security")
    @Operation(summary = "Human Gate A2: PROCEED, SKIP or STOP, then advance. APPROVER.")
    public DecisionDtos.DecisionRecorded postSecurity(@PathVariable String runId,
                                                      @Valid @RequestBody DecisionDtos.PostSecurityDecisionRequest request,
                                                      @RequestHeader(value = KEY, required = false) String key) {
        return commands.decidePostSecurity(runId, request, key);
    }

    @PostMapping("/runs/{runId}/decisions/migration-plan")
    @Operation(summary = "Approve, reject or defer the frozen migration plan, then advance. APPROVER.")
    public DecisionDtos.DecisionRecorded migrationPlan(@PathVariable String runId,
                                                       @Valid @RequestBody DecisionDtos.MigrationPlanDecisionRequest request,
                                                       @RequestHeader(value = KEY, required = false) String key) {
        return commands.decideMigrationPlan(runId, request, key);
    }

    @PostMapping("/runs/{runId}/proposals/{proposalId}/decision")
    @Operation(summary = "Human Gate B: approve, reject or defer one exact proposal (bound to its hash). APPROVER.")
    public DecisionDtos.DecisionRecorded proposal(@PathVariable String runId, @PathVariable String proposalId,
                                                  @Valid @RequestBody DecisionDtos.ProposalDecisionRequest request,
                                                  @RequestHeader(value = KEY, required = false) String key) {
        return commands.decideProposal(runId, proposalId, request, key);
    }

    @ExceptionHandler(IdempotencyStore.KeyReusedException.class)
    public ResponseEntity<Map<String, Object>> keyReused(IdempotencyStore.KeyReusedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("code", "VALIDATION_FAILURE",
                "message", e.getMessage()));
    }
}
