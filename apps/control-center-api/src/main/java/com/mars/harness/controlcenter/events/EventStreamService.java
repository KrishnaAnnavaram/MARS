package com.mars.harness.controlcenter.events;

import com.mars.harness.controlcenter.run.RunCoordinator;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events for execution events.
 *
 * <p>Protocol (see the control-center events document):
 *
 * <ul>
 *   <li>{@code hello}: once per connection, with the run's last sequence and the replay cursor</li>
 *   <li>{@code execution-event}: one per event, {@code id:} = its sequence, in strictly increasing
 *       order; a reconnecting client sends {@code Last-Event-ID} and receives exactly the events
 *       after it, so none is lost or duplicated across reconnects or server restarts</li>
 *   <li>{@code worker}: whether this server is running an engine operation for the run (its own
 *       knowledge, not an inference about the engine)</li>
 *   <li>{@code heartbeat}: every 15 s, with the server time and the last sequence, so a client can
 *       tell a quiet run from a dead connection and detect gaps</li>
 * </ul>
 *
 * <p>Events are read from the run's event log (via {@link EventIndex}), so events appended by
 * another process, such as a CLI run, stream like any other.
 */
@Service
public class EventStreamService {

    private static final Logger LOG = LoggerFactory.getLogger(EventStreamService.class);
    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final EventIndex index;
    private final RunCoordinator coordinator;
    private final ConcurrentHashMap<String, RunChannel> channels = new ConcurrentHashMap<>();

    public EventStreamService(EventIndex index, RunCoordinator coordinator) {
        this.index = index;
        this.coordinator = coordinator;
    }

    private static final class Subscriber {
        final SseEmitter emitter;
        long cursor;
        Instant lastBeat = Instant.now();

        Subscriber(SseEmitter emitter, long cursor) {
            this.emitter = emitter;
            this.cursor = cursor;
        }
    }

    private static final class RunChannel {
        final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        String lastWorker;
    }

    public SseEmitter subscribe(String runId, long after) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        RunChannel channel = channels.computeIfAbsent(runId, k -> new RunChannel());
        synchronized (channel) {
            List<ExecutionEvent> replay = index.after(runId, after);
            long last = replay.isEmpty() ? Math.max(0, Math.min(after, index.lastSequence(runId)))
                    : replay.get(replay.size() - 1).sequence();
            Subscriber subscriber = new Subscriber(emitter, last);
            try {
                Map<String, Object> hello = new LinkedHashMap<>();
                hello.put("run_id", runId);
                hello.put("last_sequence", index.lastSequence(runId));
                hello.put("replay_after", after);
                hello.put("replayed", replay.size());
                hello.put("server_time", Instant.now().toString());
                hello.put("malformed_lines", index.malformed(runId));
                emitter.send(SseEmitter.event().name("hello").data(hello, MediaType.APPLICATION_JSON));
                sendWorker(subscriber, runId);
                for (ExecutionEvent event : replay) {
                    send(subscriber, event);
                }
            } catch (IOException | IllegalStateException e) {
                LOG.debug("SSE client for {} left during replay: {}", runId, e.getMessage());
                emitter.completeWithError(e);
                return emitter;
            }
            channel.subscribers.add(subscriber);
            if (channel.lastWorker == null) {
                channel.lastWorker = workerState(runId); // this subscriber was just told; others get changes only
            }
        }
        emitter.onCompletion(() -> channel.subscribers.removeIf(s -> s.emitter == emitter));
        emitter.onTimeout(() -> channel.subscribers.removeIf(s -> s.emitter == emitter));
        emitter.onError(e -> channel.subscribers.removeIf(s -> s.emitter == emitter));
        return emitter;
    }

    /** Tails every run that has subscribers and pushes what is new. */
    @Scheduled(fixedDelay = 400)
    void pump() {
        for (Map.Entry<String, RunChannel> entry : channels.entrySet()) {
            String runId = entry.getKey();
            RunChannel channel = entry.getValue();
            if (channel.subscribers.isEmpty()) {
                channels.remove(runId, channel);
                continue;
            }
            synchronized (channel) {
                long min = channel.subscribers.stream().mapToLong(s -> s.cursor).min().orElse(0);
                List<ExecutionEvent> fresh = index.after(runId, min);
                String worker = workerState(runId);
                boolean workerChanged = !worker.equals(channel.lastWorker);
                channel.lastWorker = worker;
                for (Subscriber s : channel.subscribers) {
                    try {
                        for (ExecutionEvent event : fresh) {
                            if (event.sequence() > s.cursor) {
                                send(s, event);
                            }
                        }
                        if (workerChanged) {
                            sendWorker(s, runId);
                        }
                        if (Duration.between(s.lastBeat, Instant.now()).compareTo(HEARTBEAT) >= 0) {
                            Map<String, Object> beat = new LinkedHashMap<>();
                            beat.put("server_time", Instant.now().toString());
                            beat.put("last_sequence", s.cursor);
                            s.emitter.send(SseEmitter.event().name("heartbeat").data(beat, MediaType.APPLICATION_JSON));
                            s.lastBeat = Instant.now();
                        }
                    } catch (IOException | IllegalStateException e) {
                        LOG.debug("SSE client for {} disconnected: {}", runId, e.getMessage());
                        channel.subscribers.remove(s);
                        s.emitter.completeWithError(e);
                    }
                }
            }
        }
    }

    public int subscribers(String runId) {
        RunChannel channel = channels.get(runId);
        return channel == null ? 0 : channel.subscribers.size();
    }

    private static void send(Subscriber s, ExecutionEvent event) throws IOException {
        s.emitter.send(SseEmitter.event().id(String.valueOf(event.sequence())).name("execution-event")
                .data(event, MediaType.APPLICATION_JSON));
        s.cursor = event.sequence();
    }

    private void sendWorker(Subscriber s, String runId) throws IOException {
        Optional<RunCoordinator.Job> job = coordinator.running(runId);
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("advancing", job.isPresent());
        job.ifPresent(j -> {
            worker.put("kind", j.kind().name());
            worker.put("started_at", j.startedAt().toString());
            worker.put("triggered_by", j.triggeredBy());
            worker.put("description", j.description());
        });
        coordinator.lastFinished(runId).ifPresent(f -> {
            worker.put("last_finished_at", f.finishedAt().toString());
            worker.put("last_failed", f.failed());
            if (f.error() != null) {
                worker.put("last_error", f.error());
            }
        });
        s.emitter.send(SseEmitter.event().name("worker").data(worker, MediaType.APPLICATION_JSON));
    }

    private String workerState(String runId) {
        return coordinator.running(runId).map(j -> j.kind() + "@" + j.startedAt()).orElse("idle")
                + coordinator.lastFinished(runId).map(f -> "|" + f.finishedAt()).orElse("");
    }
}
