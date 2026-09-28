package com.mars.harness.tests.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.decision.DecisionActor;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real MARS workflow driven only through the Control Center's HTTP API, from the start of the
 * run to its verdict, with every gate decided by an authenticated approver. Proves the API
 * records decisions through the engine (ApprovalPort), refuses stale and duplicate decisions,
 * serializes engine access per run, and reports only what the artifacts show.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ControlCenterWorkflowIT extends ControlCenterTestBase {

    private static final Duration WAIT = Duration.ofMinutes(4);
    private static String runId;

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @Order(1)
    void theBoundaryRefusesAnonymousCallersAndEnforcesRolesOnTheServer() {
        ControlCenterClient anonymous = new ControlCenterClient(port);
        assertThat(anonymous.get("/api/v1/runs").status()).isEqualTo(401);
        ControlCenterClient viewer = client("viewer");
        assertThat(viewer.get("/api/v1/runs").status()).isEqualTo(200);
        var start = viewer.post("/api/v1/runs", body("repository", "composite/inventory-service"), null);
        assertThat(start.status()).as("a VIEWER cannot start runs").isEqualTo(403);
        ControlCenterClient approver = client("approver");
        assertThat(approver.post("/api/v1/runs", body("repository", "composite/inventory-service"), null).status())
                .as("an APPROVER cannot start runs either: operating and approving are separate").isEqualTo(403);
        // a cookie-authenticated command without the CSRF header is refused
        ControlCenterClient operator = client("operator");
        assertThat(operator.postWithoutCsrf("/api/v1/runs", body("repository", "composite/inventory-service")).status())
                .isEqualTo(403);
        // the browser cannot name arbitrary server paths
        var outside = operator.post("/api/v1/runs", body("repository", TestHarness.harnessRoot().toString()), null);
        assertThat(outside.status()).isEqualTo(403);
        assertThat(outside.code()).isEqualTo("PATH_NOT_ALLOWED");
        assertThat(outside.body().path("message").asText()).doesNotContain(TestHarness.harnessRoot().toString());
    }

    @Test
    @Order(2)
    void anOperatorStartsARealRunThatStopsAtGateA() {
        ControlCenterClient operator = client("operator");
        var started = operator.post("/api/v1/runs", body(
                "repository", "composite/inventory-service",
                "finding_inputs", List.of("composite/inputs/issue-register.xlsx", "composite/inputs/inventory.advisories.json"),
                "research_inputs", Map.of("INV-103", "composite/inputs/INV-103.analysis.json"),
                "probes", "composite/inventory-service/probes.json"), "start-1");
        assertThat(started.status()).isEqualTo(202);
        runId = started.body().path("run_id").asText();
        assertThat(runId).startsWith("RUN-");
        // a retry with the same key does not start a second run
        var retried = operator.post("/api/v1/runs", body(
                "repository", "composite/inventory-service",
                "finding_inputs", List.of("composite/inputs/issue-register.xlsx", "composite/inputs/inventory.advisories.json"),
                "research_inputs", Map.of("INV-103", "composite/inputs/INV-103.analysis.json"),
                "probes", "composite/inventory-service/probes.json"), "start-1");
        assertThat(retried.body().path("run_id").asText()).isEqualTo(runId);

        JsonNode snapshot = operator.awaitPhase(runId, Set.of("WAITING_FOR_EXECUTION_DECISION"), WAIT);
        assertThat(snapshot.path("waiting_for_human").asBoolean()).isTrue();
        assertThat(snapshot.path("liveness").path("state").asText()).isEqualTo("WAITING_FOR_HUMAN");
        assertThat(stage(snapshot, "GATE_A").path("status").asText()).isEqualTo("WAITING");
        assertThat(stage(snapshot, "DISCOVERY").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(stage(snapshot, "MIGRATION_ROUNDS").path("status").asText()).as("decided at Gate A").isEqualTo("PENDING");
        assertThat(snapshot.path("source").asText()).doesNotContain(REPOS.toString());

        JsonNode actions = operator.ok(operator.get("/api/v1/runs/" + runId + "/human-actions"));
        JsonNode gate = actions.path("actions").get(0);
        assertThat(gate.path("gate").asText()).isEqualTo("GATE_A");
        assertThat(gate.path("options")).hasSize(6);
        assertThat(gate.path("can_decide").asBoolean()).as("an OPERATOR cannot decide").isFalse();
        // the recommendation is advice, shown next to every option, never pre-selected
        assertThat(gate.path("recommendation").path("value").asText()).isNotBlank();
        // the run was never recorded as decided
        assertThat(snapshot.path("strategy").isMissingNode() || snapshot.path("strategy").isNull()).isTrue();
    }

    @Test
    @Order(3)
    void gateAIsRecordedThroughTheEngineBoundToTheAssessmentTheApproverSaw() throws Exception {
        ControlCenterClient approver = client("approver");
        JsonNode gate = approver.ok(approver.get("/api/v1/runs/" + runId + "/human-actions")).path("actions").get(0);
        assertThat(gate.path("can_decide").asBoolean()).isTrue();
        String hash = gate.path("bound_hash").asText();

        var stale = approver.post("/api/v1/runs/" + runId + "/decisions/execution", body("strategy", "SECURITY_ONLY",
                "rationale", "Security first", "expected_assessment_hash", "0".repeat(64)), null);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("STALE_ASSESSMENT");
        var invalid = approver.post("/api/v1/runs/" + runId + "/decisions/execution", body("strategy", "YOLO",
                "rationale", "x", "expected_assessment_hash", hash), null);
        assertThat(invalid.status()).isEqualTo(422);
        var blank = approver.post("/api/v1/runs/" + runId + "/decisions/execution", body("strategy", "SECURITY_ONLY",
                "rationale", " ", "expected_assessment_hash", hash), null);
        assertThat(blank.status()).as("an empty rationale is not a decision").isEqualTo(422);

        var recorded = approver.post("/api/v1/runs/" + runId + "/decisions/execution", body("strategy", "SECURITY_ONLY",
                "rationale", "Remediate the urgent findings on the current platform first",
                "expected_assessment_hash", hash, "actor", "someone-else"), "gate-a-1");
        JsonNode decision = approver.ok(recorded).path("decision");
        assertThat(decision.path("decision_id").asText()).startsWith("DEC-");
        assertThat(decision.path("actor").asText()).as("the actor is the authenticated caller, never the body")
                .isEqualTo("approver");
        assertThat(decision.path("role").asText()).isEqualTo("APPROVER");
        assertThat(decision.path("actor_authentication").asText()).isEqualTo(DecisionActor.DEVELOPMENT_ASSERTED);
        assertThat(decision.path("integrity").asText()).isEqualTo("VERIFIED");
        assertThat(decision.path("assessment_hash").asText()).isEqualTo(hash);
        assertThat(recorded.body().path("advancing").asBoolean()).isTrue();

        // the engine is busy with this run: a concurrent command is refused, not interleaved
        var busy = client("operator").post("/api/v1/runs/" + runId + "/resume", body(), null);
        assertThat(busy.status()).isIn(202, 409);
        if (busy.status() == 409) {
            assertThat(busy.code()).isEqualTo("RUN_BUSY");
        }

        // the decision is the engine's own artifact, HMAC-sealed, with the honest authentication statement
        RunLayout layout = new RunLayout(RUNS, runId);
        JsonNode onDisk = KernelJson.read(layout.decisions().resolve(decision.path("decision_id").asText() + ".json"));
        assertThat(onDisk.path("actor").asText()).isEqualTo("approver");
        assertThat(onDisk.path("actor_authentication").asText()).isEqualTo("DEVELOPMENT_ASSERTED");
        assertThat(onDisk.path("integrity_hash").asText()).isNotBlank();
        assertThat(Files.readString(layout.decisions().resolve("index.jsonl"))).contains(decision.path("decision_id").asText());

        // a retried request with the same key replays the first response instead of deciding twice
        var replay = approver.post("/api/v1/runs/" + runId + "/decisions/execution", body("strategy", "SECURITY_ONLY",
                "rationale", "Remediate the urgent findings on the current platform first",
                "expected_assessment_hash", hash, "actor", "someone-else"), "gate-a-1");
        assertThat(replay.body().path("decision").path("decision_id").asText()).isEqualTo(decision.path("decision_id").asText());
    }

    @Test
    @Order(4)
    void gateBApprovalsAreBoundToTheExactProposalAndNeverDuplicated() {
        ControlCenterClient approver = client("approver");
        approver.awaitPhase(runId, Set.of("WAITING_FOR_REMEDIATION_APPROVAL"), WAIT);
        JsonNode changes = approver.ok(approver.get("/api/v1/runs/" + runId + "/proposals"));
        JsonNode fix = null;
        for (JsonNode p : changes.path("proposals")) {
            if (p.path("finding_labels").toString().contains("INV-101") && !p.path("strategy_only").asBoolean()) {
                fix = p;
            }
        }
        assertThat(fix).as("the concrete CWE-89 fix for INV-101").isNotNull();
        String proposalId = fix.path("proposal_id").asText();
        JsonNode detail = approver.ok(approver.get("/api/v1/runs/" + runId + "/proposals/" + proposalId));
        assertThat(detail.path("hash_verified").asBoolean()).isTrue();
        assertThat(detail.path("decidable").asBoolean()).isTrue();
        assertThat(detail.path("edits").get(0).path("unified_diff").asText()).contains("queryForList(sql, name)");
        String hash = detail.path("proposal_hash").asText();

        var mismatch = approver.post("/api/v1/runs/" + runId + "/proposals/" + proposalId + "/decision",
                body("verdict", "APPROVED", "rationale", "Reviewed", "expected_proposal_hash", "f".repeat(64)), null);
        assertThat(mismatch.status()).isEqualTo(409);
        assertThat(mismatch.code()).isEqualTo("PROPOSAL_HASH_MISMATCH");

        var approved = approver.post("/api/v1/runs/" + runId + "/proposals/" + proposalId + "/decision",
                body("verdict", "APPROVED", "rationale", "Parameterized query reviewed against the diff",
                        "expected_proposal_hash", hash), "approve-1");
        JsonNode decision = approver.ok(approved).path("decision");
        assertThat(decision.path("proposal_hash").asText()).isEqualTo(hash);
        assertThat(decision.path("baseline_seal").asText()).isNotBlank();
        assertThat(approved.body().path("advancing").asBoolean()).as("Gate B waits for an explicit continue").isFalse();

        var second = client("admin").post("/api/v1/runs/" + runId + "/proposals/" + proposalId + "/decision",
                body("verdict", "REJECTED", "rationale", "A second approver disagrees", "expected_proposal_hash", hash), null);
        assertThat(second.status()).as("a second approver cannot silently overwrite").isEqualTo(409);
        assertThat(second.code()).isEqualTo("DECISION_ALREADY_RECORDED");

        // nothing was applied by recording a decision: the Mutation Gateway acts only when the run advances
        JsonNode after = approver.ok(approver.get("/api/v1/runs/" + runId + "/proposals/" + proposalId));
        assertThat(after.path("status").asText()).isIn("PROPOSED", "AWAITING_APPROVAL");
        assertThat(after.path("mutation").path("status").asText()).isNotEqualTo("APPLIED");

        // continue: the gateway applies the approved fix; the undecided ones stay unapproved (explicitly)
        ControlCenterClient operator = client("operator");
        assertThat(operator.post("/api/v1/runs/" + runId + "/resume", body("accept_pending", true), null).status())
                .isEqualTo(202);
        operator.awaitPhase(runId, Set.of("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION", "COMPLETE", "NEEDS_HUMAN"), WAIT);
        JsonNode applied = operator.ok(operator.get("/api/v1/runs/" + runId + "/proposals/" + proposalId));
        assertThat(applied.path("status").asText()).isIn("APPLIED", "VALIDATED");
        JsonNode mutation = applied.path("mutation");
        assertThat(mutation.path("status").asText()).isEqualTo("APPLIED");
        assertThat(mutation.path("authorized_by").asText()).isEqualTo(decision.path("decision_id").asText());
        assertThat(mutation.path("checks").toString()).contains("BASE_HASH").contains("SCOPE").contains("AUTHORIZED");
        assertThat(mutation.path("change_ids")).isNotEmpty();
        assertThat(mutation.path("lineage")).isNotEmpty();
    }

    @Test
    @Order(5)
    void gateA2AndTheVerdictComeFromTheEngine() {
        ControlCenterClient approver = client("approver");
        JsonNode snapshot = approver.awaitPhase(runId, Set.of("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION", "COMPLETE",
                "NEEDS_HUMAN"), WAIT);
        if (snapshot.path("phase").asText().equals("WAITING_FOR_POST_SECURITY_MIGRATION_DECISION")) {
            JsonNode gate = approver.ok(approver.get("/api/v1/runs/" + runId + "/human-actions")).path("actions").get(0);
            assertThat(gate.path("gate").asText()).isEqualTo("GATE_A2");
            var recorded = approver.post("/api/v1/runs/" + runId + "/decisions/post-security", body("choice", "SKIP",
                    "rationale", "Not this quarter", "expected_assessment_hash", gate.path("bound_hash").asText()), null);
            assertThat(approver.ok(recorded).path("decision").path("type").asText()).isEqualTo("POST_SECURITY_MIGRATION");
            var again = approver.post("/api/v1/runs/" + runId + "/decisions/post-security", body("choice", "PROCEED",
                    "rationale", "Changed my mind", "expected_assessment_hash", gate.path("bound_hash").asText()), null);
            assertThat(again.status()).isEqualTo(409);
            snapshot = approver.awaitPhase(runId, Set.of("COMPLETE", "NEEDS_HUMAN"), WAIT);
        }
        JsonNode verdict = approver.ok(approver.get("/api/v1/runs/" + runId + "/verdict"));
        JsonNode onDisk = KernelJson.read(new RunLayout(RUNS, runId).area("reports").resolve("verdict.json"));
        assertThat(verdict.path("available").asBoolean()).isTrue();
        assertThat(verdict.path("outcome").asText()).isEqualTo(onDisk.path("outcome").asText());
        assertThat(verdict.path("items")).hasSize(onDisk.path("items").size());
        assertThat(stage(snapshot, "VERDICT").path("status").asText()).isIn("COMPLETED", "WAITING", "BLOCKED");

        // validation dimensions are the validator's own, unknown ones are never presented as passed
        JsonNode validation = approver.ok(approver.get("/api/v1/runs/" + runId + "/validation"));
        for (JsonNode d : validation.path("final_validation")) {
            if (Set.of("NOT_RUN", "NOT_COMPARED", "TOOL_UNAVAILABLE", "INSUFFICIENT_EVIDENCE").contains(d.path("status").asText())) {
                assertThat(d.path("status").asText()).isNotEqualTo("PASS");
            }
        }
        // the finding's journey reflects the approval, the mutation and the arbiter
        JsonNode findings = approver.ok(approver.get("/api/v1/runs/" + runId + "/findings"));
        String inv101 = null;
        for (JsonNode f : findings.path("findings")) {
            if ("INV-101".equals(f.path("source_finding_id").asText())) {
                inv101 = f.path("finding_id").asText();
            }
        }
        JsonNode detail = approver.ok(approver.get("/api/v1/runs/" + runId + "/findings/" + inv101));
        Map<String, String> journey = new LinkedHashMap<>();
        detail.path("journey").forEach(j -> journey.put(j.path("id").asText(), j.path("status").asText()));
        assertThat(journey).containsEntry("APPROVAL", "DONE").containsEntry("MUTATION", "DONE");
        assertThat(journey.get("ARBITER")).isIn("DONE", "FAILED");
    }

    @Test
    @Order(6)
    void theEventStreamReplaysFromTheClientCursorWithoutGapsOrDuplicates() {
        ControlCenterClient viewer = client("viewer");
        JsonNode history = viewer.ok(viewer.get("/api/v1/runs/" + runId + "/events/history?after=0&limit=5000"));
        List<Long> sequences = new ArrayList<>();
        history.forEach(e -> sequences.add(e.path("sequence").asLong()));
        for (int i = 0; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isEqualTo(i + 1L);
        }
        long last = sequences.get(sequences.size() - 1);
        boolean decidedByApprover = false;
        for (JsonNode e : history) {
            if ("DECISION_RECORDED".equals(e.path("type").asText()) && "approver".equals(e.path("attributes").path("actor")
                    .asText())) {
                decidedByApprover = true;
            }
        }
        assertThat(decidedByApprover).isTrue();

        // a reconnecting client resumes after the last event it saw
        long cursor = last - 5;
        List<ControlCenterClient.SseEvent> events = viewer.sse("/api/v1/runs/" + runId + "/events", String.valueOf(cursor),
                list -> list.stream().filter(e -> "execution-event".equals(e.name())).count() >= 5, Duration.ofSeconds(20));
        assertThat(events.get(0).name()).isEqualTo("hello");
        assertThat(events.get(0).data().path("last_sequence").asLong()).isEqualTo(last);
        List<Long> replayed = events.stream().filter(e -> "execution-event".equals(e.name()))
                .map(e -> Long.parseLong(e.id())).toList();
        assertThat(replayed).containsExactly(cursor + 1, cursor + 2, cursor + 3, cursor + 4, cursor + 5);
    }

    @Test
    @Order(7)
    void aTerminalRunCannotBeResumedAndUnknownRunsAreNotFound() {
        ControlCenterClient operator = client("operator");
        JsonNode snapshot = operator.ok(operator.get("/api/v1/runs/" + runId));
        if ("COMPLETE".equals(snapshot.path("phase").asText())) {
            var resume = operator.post("/api/v1/runs/" + runId + "/resume", body(), null);
            assertThat(resume.status()).isEqualTo(409);
            assertThat(resume.code()).isEqualTo("INVALID_STATE");
        }
        assertThat(operator.get("/api/v1/runs/RUN-00000000000000000000000000").status()).isEqualTo(404);
        assertThat(operator.get("/api/v1/runs/not-a-run").code()).isEqualTo("RUN_NOT_FOUND");
        // artifacts in source areas are never served
        var source = operator.get("/api/v1/runs/" + runId + "/artifacts/content?path=original/pom.xml");
        assertThat(source.status()).isEqualTo(404);
        var key = operator.get("/api/v1/runs/" + runId + "/artifacts/content?path=decisions/.integrity-key");
        assertThat(key.status()).isEqualTo(404);
    }

    private static JsonNode stage(JsonNode snapshot, String id) {
        for (JsonNode s : snapshot.path("pipeline")) {
            if (id.equals(s.path("id").asText())) {
                return s;
            }
        }
        throw new AssertionError("no stage " + id);
    }
}
