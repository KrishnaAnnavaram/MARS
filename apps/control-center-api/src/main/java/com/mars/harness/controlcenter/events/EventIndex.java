package com.mars.harness.controlcenter.events;

import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.kernel.adapters.store.FilesystemExecutionEventStore;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.run.RunLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory, append-only mirror of each run's {@code events/events.jsonl}, refreshed by
 * reading only the bytes appended since the last refresh.
 *
 * <p>The file is the authority. The index is a cache of it: it can be dropped at any time and is
 * rebuilt from the file on the next read, which is also what happens after a server restart.
 * Events appended by another process (a CLI run) appear here like any other.
 */
@Component
public class EventIndex {

    private static final Logger LOG = LoggerFactory.getLogger(EventIndex.class);
    private static final long IDLE_EVICTION_MS = 30 * 60 * 1000L;

    private final ControlCenterPaths paths;
    private final ConcurrentHashMap<String, RunEvents> runs = new ConcurrentHashMap<>();

    public EventIndex(ControlCenterPaths paths) {
        this.paths = paths;
    }

    /** Every event of the run so far, refreshed from the file. */
    public List<ExecutionEvent> all(String runId) {
        return entry(runId).refresh();
    }

    /** Events with sequence greater than {@code afterSequence}. */
    public List<ExecutionEvent> after(String runId, long afterSequence) {
        List<ExecutionEvent> all = all(runId);
        int index = Collections.binarySearch(all.stream().map(ExecutionEvent::sequence).toList(), afterSequence + 1);
        int from = index >= 0 ? index : -index - 1;
        return List.copyOf(all.subList(from, all.size()));
    }

    public long lastSequence(String runId) {
        List<ExecutionEvent> all = all(runId);
        return all.isEmpty() ? 0 : all.get(all.size() - 1).sequence();
    }

    /** Malformed lines seen so far (a torn write, or a file edited by hand). */
    public int malformed(String runId) {
        return entry(runId).malformed;
    }

    private RunEvents entry(String runId) {
        return runs.computeIfAbsent(runId, id -> new RunEvents(new FilesystemExecutionEventStore(
                new RunLayout(paths.runsRoot(), id).events())));
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void evictIdle() {
        long now = System.currentTimeMillis();
        runs.entrySet().removeIf(e -> now - e.getValue().lastAccess > IDLE_EVICTION_MS);
    }

    private static final class RunEvents {
        private final FilesystemExecutionEventStore store;
        private final List<ExecutionEvent> events = new ArrayList<>();
        private long offset;
        private int malformed;
        private long lastSequence;
        private volatile long lastAccess = System.currentTimeMillis();

        RunEvents(FilesystemExecutionEventStore store) {
            this.store = store;
        }

        synchronized List<ExecutionEvent> refresh() {
            lastAccess = System.currentTimeMillis();
            FilesystemExecutionEventStore.Chunk chunk = store.readFrom(offset);
            if (chunk.nextOffset() < offset) {
                // the file was replaced or truncated: never trust the mirror over the file
                events.clear();
                lastSequence = 0;
                malformed = 0;
                chunk = store.readFrom(0);
            }
            for (ExecutionEvent event : chunk.events()) {
                if (event.sequence() > lastSequence) {
                    events.add(event);
                    lastSequence = event.sequence();
                } else {
                    LOG.warn("Out-of-order event {} (sequence {} after {}) in {}; ignored for streaming", event.eventId(),
                            event.sequence(), lastSequence, store.file());
                }
            }
            if (chunk.malformedLines() > 0) {
                malformed += chunk.malformedLines();
                LOG.warn("{} malformed line(s) in {}", chunk.malformedLines(), store.file());
            }
            offset = chunk.nextOffset();
            return List.copyOf(events);
        }
    }
}
