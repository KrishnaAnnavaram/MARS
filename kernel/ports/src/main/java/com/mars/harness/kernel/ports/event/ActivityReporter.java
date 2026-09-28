package com.mars.harness.kernel.ports.event;

import com.mars.harness.kernel.core.event.ActivityStatus;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.event.ExecutionEventType;

import java.util.List;
import java.util.Map;

/**
 * How a capability pack says what it is doing, while it does it.
 *
 * <p>Reporting carries no authority. The kernel accepts only
 * {@link ExecutionEventType#capabilityReportable() capability-reportable} types and stamps the run,
 * the state, the time and the sequence itself: a capability can describe its own analysis, rounds
 * and routing, but it cannot report a decision, a mutation, a state change or a verdict.
 */
public interface ActivityReporter {

    /** For contexts with no event plane (unit tests, tools); reports go nowhere. */
    ActivityReporter NONE = report -> { };

    void report(ActivityReport report);

    /**
     * @param component the executor as it really is, for example {@code Security / RCA Analyzer}
     * @param activity  stable key pairing STARTED and COMPLETED, for example {@code security.rca}
     */
    record ActivityReport(ExecutionEventType type, ActivityStatus status, String component, String activity,
                          String title, String message, ExecutionEvent.Progress progress,
                          List<ExecutionEvent.SubjectRef> subjects, List<String> evidenceRefs,
                          List<String> artifactRefs, Map<String, String> attributes) {
        public ActivityReport {
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
