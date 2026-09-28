package com.mars.harness.tests.unit.event;

import com.mars.harness.kernel.adapters.store.FilesystemExecutionEventStore;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.event.ActivityStatus;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.event.ExecutionEventType;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.run.RunStateMachine;
import com.mars.harness.kernel.engine.event.ExecutionEventRecorder;
import com.mars.harness.kernel.ports.event.ActivityReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The execution event log: sequencing, concurrency, replay, torn writes, serialization. */
class ExecutionEventStoreTest {

    private static ExecutionEvent draft(ExecutionEventType type, String title) {
        return new ExecutionEvent(null, "RUN-TEST", 0, "2026-09-27T10:00:00Z", type, null, RunPhase.DISCOVERY_RUNNING,
                "Kernel / Test", "test.activity", ActivityStatus.STARTED, title, null, ExecutionEvent.Progress.of(1, 3,
                "findings"), List.of(new ExecutionEvent.SubjectRef("FINDING", "FINDING-X", "INV-1")), List.of("EVID-1"),
                List.of("findings/findings.json"), null, Map.of("k", "v"));
    }

    @Test
    void appendsAreSequencedFromOneAndCarryAllocatedIds(@TempDir Path temp) {
        FilesystemExecutionEventStore store = new FilesystemExecutionEventStore(temp.resolve("events/events.jsonl"));
        assertThat(store.lastSequence()).isZero();
        ExecutionEvent first = store.append(draft(ExecutionEventType.RCA_STARTED, "one"));
        ExecutionEvent second = store.append(draft(ExecutionEventType.RCA_COMPLETED, "two"));
        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(HarnessIds.isKind(first.eventId(), HarnessIds.Kind.EVENT)).isTrue();
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.category()).isEqualTo(ExecutionEventType.Category.SECURITY);
        assertThat(store.lastSequence()).isEqualTo(2);
    }

    @Test
    void anEventSurvivesTheRoundTripUnchanged(@TempDir Path temp) {
        FilesystemExecutionEventStore store = new FilesystemExecutionEventStore(temp.resolve("events.jsonl"));
        ExecutionEvent stored = store.append(draft(ExecutionEventType.BLAST_RADIUS_COMPLETED, "round trip"));
        ExecutionEvent read = store.readAfter(0, 10).get(0);
        assertThat(read).isEqualTo(stored);
        // snake_case on disk, like every kernel artifact
        String line = TestFiles.firstLine(store.file());
        assertThat(line).contains("\"event_id\"").contains("\"evidence_refs\"").doesNotContain("eventId");
        assertThat(KernelJson.parse(line).path("progress").path("mode").asText()).isEqualTo("DETERMINATE");
    }

    @Test
    void concurrentAppendsFromSeveralInstancesFormOneGapFreeSequence(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("events.jsonl");
        // two store instances over one file, as when two RunSessions of the same run are open
        List<FilesystemExecutionEventStore> stores = List.of(new FilesystemExecutionEventStore(file),
                new FilesystemExecutionEventStore(file));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            FilesystemExecutionEventStore store = stores.get(i % 2);
            int n = i;
            futures.add(pool.submit(() -> store.append(draft(ExecutionEventType.RCA_STARTED, "e" + n)).sequence()));
        }
        Set<Long> sequences = new TreeSet<>();
        for (Future<Long> f : futures) {
            sequences.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(sequences).hasSize(400);
        assertThat(sequences).first().isEqualTo(1L);
        assertThat(sequences).last().isEqualTo(400L);
        FilesystemExecutionEventStore.Chunk chunk = new FilesystemExecutionEventStore(file).readFrom(0);
        assertThat(chunk.malformedLines()).isZero();
        assertThat(chunk.events()).extracting(ExecutionEvent::sequence).isSorted().hasSize(400);
    }

    @Test
    void replayReturnsOnlyEventsAfterTheCursor(@TempDir Path temp) {
        FilesystemExecutionEventStore store = new FilesystemExecutionEventStore(temp.resolve("events.jsonl"));
        for (int i = 0; i < 10; i++) {
            store.append(draft(ExecutionEventType.RCA_STARTED, "e" + i));
        }
        assertThat(store.readAfter(7, 100)).extracting(ExecutionEvent::sequence).containsExactly(8L, 9L, 10L);
        assertThat(store.readAfter(0, 3)).extracting(ExecutionEvent::sequence).containsExactly(1L, 2L, 3L);
        assertThat(store.readAfter(10, 100)).isEmpty();
    }

    @Test
    void aTornWriteIsReportedNotMisreadAndTheSequenceContinues(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("events.jsonl");
        FilesystemExecutionEventStore store = new FilesystemExecutionEventStore(file);
        store.append(draft(ExecutionEventType.RCA_STARTED, "before the crash"));
        // a crash mid-append leaves an unterminated fragment
        Files.writeString(file, "{\"event_id\":\"EVT-TORN\",\"seq", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        FilesystemExecutionEventStore.Chunk beforeRepair = store.readFrom(0);
        assertThat(beforeRepair.events()).hasSize(1);
        assertThat(beforeRepair.nextOffset()).as("a partial line is never consumed").isLessThan(Files.size(file));

        // a fresh process appends: the fragment is terminated, reported as malformed, and the sequence continues
        ExecutionEvent next = new FilesystemExecutionEventStore(file).append(draft(ExecutionEventType.RCA_COMPLETED, "after"));
        assertThat(next.sequence()).isEqualTo(2);
        FilesystemExecutionEventStore.Chunk after = store.readFrom(0);
        assertThat(after.malformedLines()).isEqualTo(1);
        assertThat(after.events()).extracting(ExecutionEvent::title).containsExactly("before the crash", "after");
    }

    @Test
    void tailingFromAnOffsetReadsEachEventExactlyOnce(@TempDir Path temp) {
        FilesystemExecutionEventStore store = new FilesystemExecutionEventStore(temp.resolve("events.jsonl"));
        store.append(draft(ExecutionEventType.RCA_STARTED, "a"));
        FilesystemExecutionEventStore.Chunk first = store.readFrom(0);
        store.append(draft(ExecutionEventType.RCA_STARTED, "b"));
        store.append(draft(ExecutionEventType.RCA_STARTED, "c"));
        FilesystemExecutionEventStore.Chunk second = store.readFrom(first.nextOffset());
        assertThat(first.events()).extracting(ExecutionEvent::title).containsExactly("a");
        assertThat(second.events()).extracting(ExecutionEvent::title).containsExactly("b", "c");
        assertThat(store.readFrom(second.nextOffset()).events()).isEmpty();
    }

    @Test
    void capabilitiesMayReportOnlyTheirOwnActivity(@TempDir Path temp) {
        FilesystemExecutionEventStore store = new FilesystemExecutionEventStore(temp.resolve("events.jsonl"));
        ExecutionEventRecorder recorder = new ExecutionEventRecorder("RUN-TEST", store, () -> RunPhase.DISCOVERY_RUNNING);
        ActivityReporter reporter = recorder.reporterFor("vulnerability-remediation");
        reporter.report(new ActivityReporter.ActivityReport(ExecutionEventType.RCA_COMPLETED, ActivityStatus.COMPLETED,
                "Security / RCA", "security.rca", "root cause", null, null, List.of(), List.of(), List.of(), Map.of()));
        for (ExecutionEventType kernelOnly : List.of(ExecutionEventType.DECISION_RECORDED, ExecutionEventType.MUTATION_APPLIED,
                ExecutionEventType.STATE_TRANSITION, ExecutionEventType.VERDICT_COMPUTED,
                ExecutionEventType.HUMAN_ACTION_REQUIRED)) {
            assertThatThrownBy(() -> reporter.report(new ActivityReporter.ActivityReport(kernelOnly, ActivityStatus.COMPLETED,
                    "Security / RCA", "x", "forged", null, null, List.of(), List.of(), List.of(), Map.of())))
                    .as(kernelOnly.name()).isInstanceOf(IllegalArgumentException.class);
        }
        List<ExecutionEvent> events = store.readAfter(0, 10);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).attributes()).containsEntry("capability", "vulnerability-remediation");
        assertThat(events.get(0).phase()).isEqualTo(RunPhase.DISCOVERY_RUNNING);
    }

    @Test
    void transitionsAreEmittedOnceEachAndCaughtUpAfterAGap(@TempDir Path temp) {
        Path file = temp.resolve("events.jsonl");
        RunStateMachine machine = new RunStateMachine();
        machine.transition(RunPhase.SOURCE_SNAPSHOTTED, "snapshot");
        machine.transition(RunPhase.INVENTORY_READY, "inventory");
        ExecutionEventRecorder recorder = new ExecutionEventRecorder("RUN-TEST", new FilesystemExecutionEventStore(file),
                () -> machine.current);
        recorder.recordTransitions(machine.history);
        recorder.recordTransitions(machine.history);
        machine.transition(RunPhase.IDENTITY_SEALED, "identity");
        // a new recorder (a new process after a crash) catches up from what is on disk, without duplicates
        new ExecutionEventRecorder("RUN-TEST", new FilesystemExecutionEventStore(file), () -> machine.current)
                .recordTransitions(machine.history);
        List<ExecutionEvent> events = new FilesystemExecutionEventStore(file).readAfter(0, 100);
        assertThat(events).extracting(e -> e.attributes().get("to"))
                .containsExactly("SOURCE_SNAPSHOTTED", "INVENTORY_READY", "IDENTITY_SEALED");
        assertThat(events).extracting(e -> e.attributes().get(ExecutionEventRecorder.TRANSITION_INDEX))
                .containsExactly("0", "1", "2");
        assertThat(events.get(0).timestamp()).as("a transition event carries the transition's own time")
                .isEqualTo(machine.history.get(0).at());
    }

    @Test
    void aFailingEventPlaneNeverFailsTheRun(@TempDir Path temp) throws Exception {
        Path blocker = temp.resolve("events");
        Files.writeString(blocker, "a file where the directory should be");
        ExecutionEventRecorder recorder = new ExecutionEventRecorder("RUN-TEST",
                new FilesystemExecutionEventStore(blocker.resolve("events.jsonl")), () -> RunPhase.CREATED);
        assertThat(recorder.event(ExecutionEventType.RUN_CREATED).title("x").emit()).isEmpty();
        assertThat(recorder.failure()).as("the degradation is kept, not hidden").isNotBlank();
    }

    /** Small file helpers for assertions (tests may read and write files freely). */
    static final class TestFiles {
        static String firstLine(Path file) {
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
