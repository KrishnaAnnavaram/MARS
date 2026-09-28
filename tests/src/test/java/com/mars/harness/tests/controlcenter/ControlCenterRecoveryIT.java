package com.mars.harness.tests.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.cli.HarnessFactory;
import com.mars.harness.kernel.adapters.store.FilesystemExecutionEventStore;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Control Center over runs it did not start: runs driven by another process (the CLI), runs
 * interrupted between a decision and its advancement, and a server that restarts. The persisted
 * run is the state; the server only ever reads it back.
 */
class ControlCenterRecoveryIT extends ControlCenterTestBase {

    /** A second engine over the same runs root: what a CLI process in another terminal is. */
    private static HarnessEngine cli() {
        return new HarnessEngine(HarnessFactory.config(TestHarness.harnessRoot(), RUNS, null, TestHarness.TODAY, true, false)
                .withRuntime(TOOLS, TOOLS));
    }

    private static String analyzeWithCli() {
        Path repo = REPOS.resolve("composite/inventory-service");
        Path inputs = REPOS.resolve("composite/inputs");
        return cli().analyze(new HarnessEngine.AnalyzeRequest(repo, List.of(inputs.resolve("issue-register.xlsx"),
                inputs.resolve("inventory.advisories.json")), Map.of("INV-103", inputs.resolve("INV-103.analysis.json")),
                repo.resolve("probes.json"), false)).runId();
    }

    @Test
    void aRunDrivenByTheCliIsVisibleWithItsCompleteEventHistory() {
        String runId = analyzeWithCli();
        ControlCenterClient viewer = client("viewer");
        JsonNode list = viewer.ok(viewer.get("/api/v1/runs?status=waiting"));
        assertThat(list.path("runs").findValuesAsText("run_id")).contains(runId);
        JsonNode snapshot = viewer.ok(viewer.get("/api/v1/runs/" + runId));
        assertThat(snapshot.path("phase").asText()).isEqualTo("WAITING_FOR_EXECUTION_DECISION");
        long onDisk = new FilesystemExecutionEventStore(new RunLayout(RUNS, runId).events()).lastSequence();
        assertThat(snapshot.path("last_event_sequence").asLong()).isEqualTo(onDisk).isGreaterThan(10);

        // a fresh subscriber (as after a server restart) replays the whole history from the event log
        List<ControlCenterClient.SseEvent> events = viewer.sse("/api/v1/runs/" + runId + "/events", null,
                l -> l.stream().filter(e -> "execution-event".equals(e.name())).count() >= onDisk, Duration.ofSeconds(20));
        assertThat(events.stream().filter(e -> "execution-event".equals(e.name())).count()).isEqualTo(onDisk);
        assertThat(events.get(events.size() - 1).data().path("type").asText()).isEqualTo("HUMAN_ACTION_REQUIRED");
    }

    @Test
    void aRunInterruptedAfterItsDecisionIsReportedIdleAndResumesThroughTheApi() {
        String runId = analyzeWithCli();
        // the CLI recorded Gate A, but the process ended before `harness resume`
        cli().decideExecution(runId, "ANALYZE_ONLY", "dev.lead", "owner", "Report only");

        ControlCenterClient operator = client("operator");
        JsonNode snapshot = operator.ok(operator.get("/api/v1/runs/" + runId));
        assertThat(snapshot.path("phase").asText()).isEqualTo("EXECUTION_PLANNED");
        // it is neither waiting nor finished, and nothing here advances it: that is said, not hidden
        assertThat(snapshot.path("liveness").path("state").asText()).isIn("IDLE", "EXTERNAL_ACTIVITY");
        assertThat(stage(snapshot, "EXECUTION_PLAN").path("status").asText()).isEqualTo("IDLE");
        // the decision recorded by the CLI shows its honest authentication
        JsonNode decisions = operator.ok(operator.get("/api/v1/runs/" + runId + "/decisions"));
        assertThat(decisions.get(0).path("actor_authentication").asText()).isEqualTo("LOCALLY_ASSERTED");

        assertThat(operator.post("/api/v1/runs/" + runId + "/resume", Map.of(), null).status()).isEqualTo(202);
        JsonNode done = operator.awaitPhase(runId, Set.of("COMPLETE", "NEEDS_HUMAN"), Duration.ofMinutes(3));
        assertThat(done.path("verdict").asText()).isNotBlank();
        assertThat(done.path("liveness").path("last_job").path("failed").asBoolean()).isFalse();
    }

    @Test
    void anAnalysisThatStoppedBeforeGateAIsNotOfferedAsResumable() {
        String runId = analyzeWithCli();
        // simulate an analysis interrupted at DISCOVERY_RUNNING by rewinding the persisted cursor in a copy
        Path copyRoot = RUNS;
        RunLayout layout = new RunLayout(copyRoot, runId);
        JsonNode state = com.mars.harness.kernel.core.KernelJson.read(layout.state());
        ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("machine")).put("current", "DISCOVERY_RUNNING");
        writeState(layout, state);
        ControlCenterClient operator = client("operator");
        JsonNode snapshot = operator.ok(operator.get("/api/v1/runs/" + runId));
        assertThat(snapshot.path("liveness").path("state").asText()).isIn("INTERRUPTED_ANALYSIS", "EXTERNAL_ACTIVITY");
        var resume = operator.post("/api/v1/runs/" + runId + "/resume", Map.of(), null);
        assertThat(resume.status()).isEqualTo(409);
        assertThat(resume.code()).isEqualTo("INVALID_STATE");
    }

    private static void writeState(RunLayout layout, JsonNode state) {
        try {
            java.nio.file.Files.writeString(layout.state(), com.mars.harness.kernel.core.KernelJson.pretty(state));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
