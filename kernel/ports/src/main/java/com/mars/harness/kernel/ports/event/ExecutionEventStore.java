package com.mars.harness.kernel.ports.event;

import com.mars.harness.kernel.core.event.ExecutionEvent;

import java.util.List;

/**
 * The per-run execution event log: append-only, strictly sequenced, replayable.
 *
 * <p>Only the kernel appends. Readers (the CLI, the Control Center) replay from a sequence and
 * never write. Appends are safe across threads and across processes on the same run directory,
 * so a CLI-driven run and a Control Center reading it see one consistent sequence.
 */
public interface ExecutionEventStore {

    /**
     * Assigns the next sequence and an {@code EVT-} identifier, persists the event and returns the
     * stored form. The draft's {@code eventId} and {@code sequence} are ignored.
     */
    ExecutionEvent append(ExecutionEvent draft);

    /** Events with {@code sequence > afterSequence}, in order, at most {@code limit} of them. */
    List<ExecutionEvent> readAfter(long afterSequence, int limit);

    /** The sequence of the last persisted event; 0 when the log is empty. */
    long lastSequence();
}
