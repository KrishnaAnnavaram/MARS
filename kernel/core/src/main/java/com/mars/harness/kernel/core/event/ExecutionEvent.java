package com.mars.harness.kernel.core.event;

import com.mars.harness.kernel.core.run.RunPhase;

import java.util.List;
import java.util.Map;

/**
 * One thing the harness did, as it did it: the domain execution event (control-center ADR-U008).
 *
 * <p>Events are witnesses, not authority. The run record, the ledgers, the decisions and the
 * artifacts remain the source of truth; an event says <em>that</em> and <em>when</em> an
 * operation happened and points at the artifacts and evidence that prove it. A missing event
 * never makes something untrue, and an event never makes something true.
 *
 * <p>Events are persisted append-only in {@code events/events.jsonl} with a per-run, strictly
 * increasing {@code sequence}, so a reader can replay from any point and detect gaps.
 *
 * @param eventId      {@code EVT-<ULID>}, allocated by the kernel
 * @param sequence     1-based, strictly increasing within a run, assigned by the event store
 * @param timestamp    ISO-8601 instant at which the operation was observed
 * @param category     always {@code type.category()}
 * @param phase        the run state when the event was emitted (for transitions, the target)
 * @param component    the actual executor, for example {@code Kernel / Mutation Gateway} or
 *                     {@code Security / Blast Radius Analyzer}; never an invented agent
 * @param activity     stable activity key, for example {@code security.blast-radius}, used to pair
 *                     STARTED and COMPLETED events
 * @param progress     domain progress when the executor knows it; null otherwise
 * @param subjects     identities the event is about (findings, proposals, files, decisions)
 * @param humanAction  set only on events that stop the run for a human
 * @param attributes   type-specific scalar facts (for example a transition's from/to state or a
 *                     build outcome). Values are strings; structured data belongs in artifacts.
 */
public record ExecutionEvent(String eventId, String runId, long sequence, String timestamp, ExecutionEventType type,
                             ExecutionEventType.Category category, RunPhase phase, String component, String activity,
                             ActivityStatus status, String title, String message, Progress progress,
                             List<SubjectRef> subjects, List<String> evidenceRefs, List<String> artifactRefs,
                             HumanAction humanAction, Map<String, String> attributes) {

    public ExecutionEvent {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (type != null && category == null) {
            category = type.category();
        }
    }

    /** The same event with the identity fields the store assigns on append. */
    public ExecutionEvent sealed(String newEventId, long newSequence) {
        return new ExecutionEvent(newEventId, runId, newSequence, timestamp, type, category, phase, component, activity,
                status, title, message, progress, subjects, evidenceRefs, artifactRefs, humanAction, attributes);
    }

    /**
     * Domain progress, only as the executor actually knows it.
     *
     * @param completed units done; null when unknown
     * @param total     units in total; null when the total is not known (INDETERMINATE)
     * @param unit      what is counted, for example {@code findings} or {@code files}
     */
    public record Progress(ProgressMode mode, Integer completed, Integer total, String unit) {

        public static Progress of(int completed, int total, String unit) {
            return new Progress(ProgressMode.DETERMINATE, completed, total, unit);
        }

        public static Progress indeterminate(Integer completed, String unit) {
            return new Progress(ProgressMode.INDETERMINATE, completed, null, unit);
        }
    }

    /** DETERMINATE only when both counts are known; never inferred from elapsed time. */
    public enum ProgressMode { DETERMINATE, INDETERMINATE }

    /**
     * An identity the event concerns.
     *
     * @param kind  FINDING, PROPOSAL, DECISION, FILE, SYMBOL, PLAN, CHECKPOINT, CHANGE, STAGE, ROUND
     * @param id    the harness identifier
     * @param label a short human label (a path, a CWE, a rule id); informational only
     */
    public record SubjectRef(String kind, String id, String label) {
    }

    /**
     * Why the run stopped for a human, and what the human can do.
     *
     * @param gate         GATE_A, GATE_A2, GATE_B, MIGRATION_PLAN or NEEDS_HUMAN
     * @param decisionType the {@code Decision.DecisionType} a human records to continue, if any
     * @param reason       the harness's stated reason, from the run's own facts
     * @param options      the choices the domain accepts (for example the execution strategies)
     */
    public record HumanAction(String gate, String decisionType, String reason, List<String> options) {
        public HumanAction {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
