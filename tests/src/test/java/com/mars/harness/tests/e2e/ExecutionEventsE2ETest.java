package com.mars.harness.tests.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.adapters.store.FilesystemExecutionEventStore;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.event.ExecutionEventType;
import com.mars.harness.kernel.core.run.RunStateMachine;
import com.mars.harness.kernel.engine.event.ExecutionEventRecorder;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The execution event stream of real runs: it describes exactly what the engine did, in order,
 * and agrees with the persisted run. Events are witnesses; these tests prove they never claim
 * something the run record, the ledgers or the decisions do not also show.
 */
class ExecutionEventsE2ETest {

    private static List<ExecutionEvent> events(RunSession session) {
        return new FilesystemExecutionEventStore(session.layout.events()).readAfter(0, 100_000);
    }

    private static List<ExecutionEvent> ofType(List<ExecutionEvent> events, ExecutionEventType type) {
        return events.stream().filter(e -> e.type() == type).toList();
    }

    private static long firstIndex(List<ExecutionEvent> events, ExecutionEventType type) {
        return events.stream().filter(e -> e.type() == type).findFirst().map(ExecutionEvent::sequence)
                .orElseThrow(() -> new AssertionError("no " + type));
    }

    @Test
    void aSecurityRunEmitsAnOrderedStreamThatAgreesWithTheRunRecord(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();

        // Gate A: the run stopped for a human, and the stream says so with the options the domain accepts
        List<ExecutionEvent> atGateA = events(h.session(run));
        ExecutionEvent gateA = atGateA.get(atGateA.size() - 1);
        assertThat(gateA.type()).isEqualTo(ExecutionEventType.HUMAN_ACTION_REQUIRED);
        assertThat(gateA.humanAction().gate()).isEqualTo("GATE_A");
        assertThat(gateA.humanAction().options()).containsExactly("MIGRATE_FIRST", "SECURITY_FIRST", "MIGRATION_ONLY",
                "SECURITY_ONLY", "ANALYZE_ONLY", "STOP");

        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        ChangeProposal fix = h.proposalFor(run, "INV-101");
        h.approve(run, fix);
        h.engine.resume(run, true);
        h.engine.decidePostSecurityMigration(run, "SKIP", "dev.lead", "owner", "Not now");
        h.engine.resume(run, false);

        RunSession session = h.session(run);
        List<ExecutionEvent> events = events(session);

        // one gap-free, strictly increasing sequence
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).sequence()).isEqualTo(i + 1L);
            assertThat(events.get(i).runId()).isEqualTo(run);
        }

        // every persisted transition appears exactly once, in order, and nothing else claims a transition
        List<RunStateMachine.Transition> history = session.record.machine.history;
        List<ExecutionEvent> transitions = ofType(events, ExecutionEventType.STATE_TRANSITION);
        assertThat(transitions).hasSameSizeAs(history);
        for (int i = 0; i < history.size(); i++) {
            assertThat(transitions.get(i).attributes()).containsEntry("from", history.get(i).from().name())
                    .containsEntry("to", history.get(i).to().name())
                    .containsEntry(ExecutionEventRecorder.TRANSITION_INDEX, String.valueOf(i));
        }

        // the lifecycle, in the order the engine performs it
        List<ExecutionEventType> order = List.of(ExecutionEventType.RUN_CREATED, ExecutionEventType.INGEST_STARTED,
                ExecutionEventType.INVENTORY_COMPLETED, ExecutionEventType.IDENTITY_COMPLETED,
                ExecutionEventType.GRAPH_BUILD_COMPLETED, ExecutionEventType.BASELINE_BUILD_COMPLETED,
                ExecutionEventType.BASELINE_SEALED, ExecutionEventType.DISCOVERY_STARTED, ExecutionEventType.RCA_COMPLETED,
                ExecutionEventType.BLAST_RADIUS_COMPLETED, ExecutionEventType.MIGRATION_ASSESSMENT_COMPLETED,
                ExecutionEventType.SEQUENCE_ASSESSMENT_COMPLETED, ExecutionEventType.HUMAN_ACTION_REQUIRED,
                ExecutionEventType.DECISION_RECORDED, ExecutionEventType.REMEDIATION_PLANNING_COMPLETED,
                ExecutionEventType.MUTATION_APPLIED, ExecutionEventType.FIX_VERIFICATION_COMPLETED,
                ExecutionEventType.FINAL_VALIDATION_COMPLETED, ExecutionEventType.VERDICT_COMPUTED);
        for (int i = 1; i < order.size(); i++) {
            assertThat(firstIndex(events, order.get(i))).as(order.get(i - 1) + " before " + order.get(i))
                    .isGreaterThan(firstIndex(events, order.get(i - 1)));
        }

        // no claimed mutation without the record agreeing, each authorized by a recorded decision
        List<ExecutionEvent> applied = ofType(events, ExecutionEventType.MUTATION_APPLIED);
        Set<String> appliedIds = applied.stream().map(e -> e.subjects().get(0).id()).collect(Collectors.toSet());
        Set<String> recordApplied = session.record.proposalStatus.entrySet().stream()
                .filter(e -> Set.of("APPLIED", "VALIDATED", "FAILED_VALIDATION").contains(e.getValue()))
                .map(java.util.Map.Entry::getKey).collect(Collectors.toSet());
        assertThat(appliedIds).isEqualTo(recordApplied).contains(fix.proposalId());
        for (ExecutionEvent e : applied) {
            assertThat(session.approvals.find(e.attributes().get("authorized_by"))).isPresent();
            assertThat(e.attributes().get("change_ids").split(",")).containsExactlyElementsOf(
                    session.record.proposalChanges.get(e.subjects().get(0).id()));
        }

        // every decision on disk has exactly one DECISION_RECORDED event naming it and its honest authentication
        List<ExecutionEvent> decisions = ofType(events, ExecutionEventType.DECISION_RECORDED);
        assertThat(decisions).extracting(e -> e.attributes().get("decision_id"))
                .containsExactlyInAnyOrderElementsOf(session.approvals.all().stream().map(d -> d.decisionId()).toList());
        assertThat(decisions).allSatisfy(e -> assertThat(e.attributes()).containsEntry("actor_authentication",
                "LOCALLY_ASSERTED"));

        // progress is a domain count, never more than its total; blast radius ends at every finding analysed
        List<ExecutionEvent> radii = ofType(events, ExecutionEventType.BLAST_RADIUS_COMPLETED);
        assertThat(radii).allSatisfy(e -> assertThat(e.progress().completed()).isLessThanOrEqualTo(e.progress().total()));
        ExecutionEvent lastRadius = radii.get(radii.size() - 1);
        assertThat(lastRadius.progress().completed()).isEqualTo(lastRadius.progress().total());
        JsonNode discovery = h.json(run, "discovery/security/security-discovery.json");
        assertThat(lastRadius.progress().total()).isEqualTo(discovery.path("findings").size());

        // the verdict event agrees with the verdict artifact; the executor is named as it really is
        ExecutionEvent verdict = ofType(events, ExecutionEventType.VERDICT_COMPUTED).get(0);
        assertThat(verdict.attributes().get("outcome")).isEqualTo(h.json(run, "reports/verdict.json").path("outcome").asText());
        assertThat(ofType(events, ExecutionEventType.BASELINE_BUILD_COMPLETED).get(0).component())
                .as("a simulated tool is never presented as the real one").contains("SimulatedToolchain");
        // capability-reported activity is attributed to its pack
        assertThat(ofType(events, ExecutionEventType.RCA_COMPLETED)).allSatisfy(e ->
                assertThat(e.attributes()).containsEntry("capability", "vulnerability-remediation"));
    }

    @Test
    void migrationRoundsAreReportedAsTheyRunAndMatchTheRecordedRounds(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "migration/employee-demo-sb3");
        h.employeeEnvironment();
        String run = h.analyzeEmployee().runId();
        h.engine.decideExecution(run, "MIGRATION_ONLY", "dev.lead", "owner", "Boot 3.5 reaches end of OSS support");
        h.engine.resume(run, false);

        // the rounds stopped for a human: the stream says why, from the execution's own reason
        List<ExecutionEvent> stopped = events(h.session(run));
        ExecutionEvent needsHuman = stopped.get(stopped.size() - 1);
        assertThat(needsHuman.type()).isEqualTo(ExecutionEventType.HUMAN_ACTION_REQUIRED);
        assertThat(needsHuman.humanAction().gate()).isEqualTo("NEEDS_HUMAN");
        assertThat(needsHuman.humanAction().reason()).contains("AutoConfigureTestDatabase");

        FullMigrationE2ETest.resolveAutoConfigureTestDatabase(h.engine, run);
        h.engine.resume(run, false);

        List<ExecutionEvent> events = events(h.session(run));
        JsonNode rounds = h.json(run, "plans/migration-execution.json").path("rounds");
        List<ExecutionEvent> started = ofType(events, ExecutionEventType.MIGRATION_ROUND_STARTED);
        List<ExecutionEvent> completed = ofType(events, ExecutionEventType.MIGRATION_ROUND_COMPLETED);
        // round 0 is the baseline build; every later round was announced before it ran and reported after
        assertThat(completed).hasSize(rounds.size() - 1).hasSameSizeAs(started);
        for (int i = 1; i < rounds.size(); i++) {
            ExecutionEvent done = completed.get(i - 1);
            assertThat(done.attributes()).containsEntry("round", String.valueOf(rounds.get(i).path("round").asInt()))
                    .containsEntry("outcome", rounds.get(i).path("outcome").asText());
            assertThat(started.get(i - 1).sequence()).isLessThan(done.sequence());
        }
        // every rule the rounds applied was reported as applied
        Set<String> appliedRules = ofType(events, ExecutionEventType.MIGRATION_RULE_APPLIED).stream()
                .map(e -> e.attributes().get("rule_id")).collect(Collectors.toSet());
        for (JsonNode round : rounds) {
            round.path("applied_rules").forEach(r -> {
                if (!r.asText().startsWith("(")) {
                    assertThat(appliedRules).contains(r.asText());
                }
            });
        }
        assertThat(ofType(events, ExecutionEventType.MIGRATION_EXECUTION_COMPLETED).get(ofType(events,
                ExecutionEventType.MIGRATION_EXECUTION_COMPLETED).size() - 1).attributes()).containsEntry("status", "GREEN");
    }

    @Test
    void aRestartedEngineContinuesTheSameSequence(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        long before = new FilesystemExecutionEventStore(h.session(run).layout.events()).lastSequence();
        TestHarness restarted = h.restart();
        restarted.engine.decideExecution(run, "ANALYZE_ONLY", "dev.lead", "owner", "Report only");
        restarted.engine.resume(run, false);
        List<ExecutionEvent> events = events(restarted.session(run));
        assertThat(events.get((int) before).sequence()).isEqualTo(before + 1);
        assertThat(ofType(events, ExecutionEventType.STATE_TRANSITION))
                .hasSize(restarted.session(run).record.machine.history.size());
        assertThat(events.get(events.size() - 1).type()).isIn(ExecutionEventType.RUN_COMPLETED,
                ExecutionEventType.HUMAN_ACTION_REQUIRED);
    }
}
