package com.mars.harness.kernel.engine.event;

import com.mars.harness.kernel.core.event.ActivityStatus;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.event.ExecutionEventType;
import com.mars.harness.kernel.core.run.RunPhase;
import com.mars.harness.kernel.core.run.RunStateMachine;
import com.mars.harness.kernel.ports.event.ActivityReporter;
import com.mars.harness.kernel.ports.event.ExecutionEventStore;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The kernel's single way of emitting execution events for one run.
 *
 * <p>It stamps what only the kernel may know (run, current state, time) and appends through the
 * {@link ExecutionEventStore}. Events are witnesses of work that already happened or is about to
 * start; emitting one never changes run state, and failing to emit one never fails the run. A
 * failure is logged and kept in {@link #failure()} so the run can say its event plane is
 * incomplete rather than pretend otherwise.
 *
 * <p>State transitions are not emitted by hand. {@link #recordTransitions} publishes every
 * transition in the persisted history that has no event yet, after the run record was written,
 * so a transition event always describes a transition that is on disk. A crash between the two
 * writes is caught up at the next save; readers de-duplicate on {@code transition_index}.
 */
public final class ExecutionEventRecorder {

    private static final System.Logger LOG = System.getLogger(ExecutionEventRecorder.class.getName());
    public static final String TRANSITION_INDEX = "transition_index";

    private final String runId;
    private final ExecutionEventStore store;
    private final Supplier<RunPhase> phase;
    private int emittedTransitions = -1;
    private String failure;

    public ExecutionEventRecorder(String runId, ExecutionEventStore store, Supplier<RunPhase> phase) {
        this.runId = runId;
        this.store = store;
        this.phase = phase;
    }

    public Builder event(ExecutionEventType type) {
        return new Builder(type);
    }

    /** The first reason the event plane could not be written, or null while it is complete. */
    public String failure() {
        return failure;
    }

    /**
     * A reporter for one capability pack. It accepts only capability-reportable types; anything
     * else is a programming error in the capability and is refused.
     */
    public ActivityReporter reporterFor(String capabilityId) {
        return report -> {
            if (report.type() == null || !report.type().capabilityReportable()) {
                throw new IllegalArgumentException("Capability " + capabilityId + " may not report " + report.type()
                        + ": decisions, mutations, state and verdicts are reported by the kernel only");
            }
            Builder builder = event(report.type()).status(report.status()).component(report.component())
                    .activity(report.activity()).title(report.title()).message(report.message())
                    .progress(report.progress()).evidence(report.evidenceRefs()).artifacts(report.artifactRefs())
                    .attribute("capability", capabilityId);
            report.subjects().forEach(s -> builder.subject(s.kind(), s.id(), s.label()));
            report.attributes().forEach(builder::attribute);
            builder.emit();
        };
    }

    /** Publishes every persisted transition that has no STATE_TRANSITION event yet. */
    public void recordTransitions(List<RunStateMachine.Transition> history) {
        if (emittedTransitions < 0) {
            emittedTransitions = emittedTransitionCount();
        }
        for (int i = emittedTransitions; i < history.size(); i++) {
            RunStateMachine.Transition t = history.get(i);
            ActivityStatus status = t.to() == RunPhase.FAILED ? ActivityStatus.FAILED
                    : t.to().waitingForHuman() ? ActivityStatus.WAITING
                    : t.to().terminal() ? ActivityStatus.COMPLETED : ActivityStatus.INFO;
            Optional<ExecutionEvent> emitted = new Builder(ExecutionEventType.STATE_TRANSITION).at(t.at()).phase(t.to())
                    .status(status).component("Kernel / Run State Machine").activity("run.state")
                    .title(t.from() + " → " + t.to()).message(t.reason())
                    .attribute("from", t.from().name()).attribute("to", t.to().name())
                    .attribute(TRANSITION_INDEX, String.valueOf(i)).emit();
            if (emitted.isEmpty()) {
                return; // the event plane is failing; the next save retries from here
            }
            emittedTransitions = i + 1;
        }
    }

    private int emittedTransitionCount() {
        try {
            int max = -1;
            List<ExecutionEvent> batch;
            long after = 0;
            do {
                batch = store.readAfter(after, 10_000);
                for (ExecutionEvent e : batch) {
                    after = e.sequence();
                    String index = e.attributes().get(TRANSITION_INDEX);
                    if (e.type() == ExecutionEventType.STATE_TRANSITION && index != null) {
                        max = Math.max(max, Integer.parseInt(index));
                    }
                }
            } while (batch.size() == 10_000);
            return max + 1;
        } catch (UncheckedIOException | NumberFormatException e) {
            degrade(e);
            return Integer.MAX_VALUE; // cannot tell what was emitted; do not flood duplicates
        }
    }

    private void degrade(RuntimeException e) {
        if (failure == null) {
            failure = e.getMessage();
        }
        LOG.log(System.Logger.Level.WARNING, "Execution event plane degraded for " + runId + ": " + e.getMessage(), e);
    }

    /** Fluent draft of one event. {@link #emit()} appends it. */
    public final class Builder {

        private final ExecutionEventType type;
        private String at;
        private RunPhase eventPhase;
        private ActivityStatus status = ActivityStatus.INFO;
        private String component;
        private String activity;
        private String title;
        private String message;
        private ExecutionEvent.Progress progress;
        private final List<ExecutionEvent.SubjectRef> subjects = new ArrayList<>();
        private final List<String> evidence = new ArrayList<>();
        private final List<String> artifacts = new ArrayList<>();
        private ExecutionEvent.HumanAction humanAction;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(ExecutionEventType type) {
            this.type = type;
        }

        public Builder at(String timestamp) {
            this.at = timestamp;
            return this;
        }

        public Builder phase(RunPhase value) {
            this.eventPhase = value;
            return this;
        }

        public Builder status(ActivityStatus value) {
            this.status = value;
            return this;
        }

        public Builder component(String value) {
            this.component = value;
            return this;
        }

        public Builder activity(String value) {
            this.activity = value;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder progress(ExecutionEvent.Progress value) {
            this.progress = value;
            return this;
        }

        public Builder progress(int completed, int total, String unit) {
            this.progress = ExecutionEvent.Progress.of(completed, total, unit);
            return this;
        }

        public Builder subject(String kind, String id, String label) {
            if (id != null) {
                subjects.add(new ExecutionEvent.SubjectRef(kind, id, label));
            }
            return this;
        }

        public Builder evidence(String ref) {
            if (ref != null) {
                evidence.add(ref);
            }
            return this;
        }

        public Builder evidence(List<String> refs) {
            if (refs != null) {
                refs.stream().filter(r -> r != null && !evidence.contains(r)).forEach(evidence::add);
            }
            return this;
        }

        public Builder artifact(String ref) {
            if (ref != null) {
                artifacts.add(ref);
            }
            return this;
        }

        public Builder artifacts(List<String> refs) {
            if (refs != null) {
                refs.stream().filter(r -> r != null && !artifacts.contains(r)).forEach(artifacts::add);
            }
            return this;
        }

        public Builder humanAction(ExecutionEvent.HumanAction value) {
            this.humanAction = value;
            return this;
        }

        public Builder attribute(String key, Object value) {
            if (key != null && value != null) {
                attributes.put(key, String.valueOf(value));
            }
            return this;
        }

        /** Appends the event; empty when the event plane could not be written (already logged). */
        public Optional<ExecutionEvent> emit() {
            ExecutionEvent draft = new ExecutionEvent(null, runId, 0, at == null ? Instant.now().toString() : at, type,
                    type.category(), eventPhase == null ? phase.get() : eventPhase, component, activity, status, title,
                    message, progress, subjects, evidence, artifacts, humanAction, attributes);
            try {
                return Optional.of(store.append(draft));
            } catch (UncheckedIOException e) {
                degrade(e);
                return Optional.empty();
            }
        }
    }
}
