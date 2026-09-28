package com.mars.harness.controlcenter.run;

import com.mars.harness.controlcenter.api.ApiErrorCode;
import com.mars.harness.controlcenter.api.ApiException;
import com.mars.harness.controlcenter.config.ControlCenterProperties;
import com.mars.harness.controlcenter.config.ControlCenterPaths;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Coordinates every engine operation the Control Center triggers, so that at most one operation
 * touches a run at a time.
 *
 * <p>Each run has a single permit. A synchronous command (recording a decision) and an
 * asynchronous job (analysis, advancement) both need it. A request that finds it taken gets
 * {@code RUN_BUSY} and nothing happens: two tabs, two approvers, a refresh or a duplicate request
 * can never interleave engine calls on one run. The permit is not owned by a thread, so a command
 * can hand it to the job it starts (record a Gate A decision, then advance) with no gap for
 * another request to slip into.
 *
 * <p>What this class knows is the Control Center's own activity. It never infers what the engine
 * did; the run's artifacts and events say that. It does not coordinate with a CLI process
 * advancing the same run at the same time (see the control-center security document).
 */
@Component
public class RunCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(RunCoordinator.class);

    public enum JobKind { ANALYZE, ADVANCE }

    /** An engine operation this server is currently running for a run. */
    public record Job(String runId, JobKind kind, String triggeredBy, Instant startedAt, String description) {
    }

    /** The outcome of the last job this server ran for a run (kept in memory only). */
    public record Finished(String runId, JobKind kind, Instant startedAt, Instant finishedAt, boolean failed, String error) {
    }

    private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Job> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Finished> finished = new ConcurrentHashMap<>();
    private final ExecutorService workers;
    private final ControlCenterPaths paths;
    private final List<RunJobListener> listeners;

    public RunCoordinator(ControlCenterProperties properties, ControlCenterPaths paths, List<RunJobListener> listeners) {
        this.paths = paths;
        this.listeners = List.copyOf(listeners);
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(properties.maxConcurrentRuns(), r -> {
            Thread t = new Thread(r, "mars-run-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs {@code command} holding the run's permit; RUN_BUSY when another operation holds it. */
    public <T> T exclusive(String runId, Supplier<T> command) {
        Semaphore permit = permit(runId);
        if (!permit.tryAcquire()) {
            throw busyError(runId);
        }
        try {
            return command.get();
        } finally {
            permit.release();
        }
    }

    /**
     * Runs {@code command} holding the permit, then hands the permit to an asynchronous job, so the
     * job starts exactly where the command left the run.
     */
    public <T> T exclusiveThen(String runId, Supplier<T> command, JobKind kind, String triggeredBy, String description,
                               Runnable job) {
        Semaphore permit = permit(runId);
        if (!permit.tryAcquire()) {
            throw busyError(runId);
        }
        T result;
        try {
            result = command.get();
        } catch (RuntimeException e) {
            permit.release();
            throw e;
        }
        startHolding(runId, permit, kind, triggeredBy, description, job);
        return result;
    }

    /** Starts an asynchronous job for a run; RUN_BUSY when another operation holds the permit. */
    public Job start(String runId, JobKind kind, String triggeredBy, String description, Runnable job) {
        Semaphore permit = permit(runId);
        if (!permit.tryAcquire()) {
            throw busyError(runId);
        }
        return startHolding(runId, permit, kind, triggeredBy, description, job);
    }

    private Job startHolding(String runId, Semaphore permit, JobKind kind, String triggeredBy, String description,
                             Runnable job) {
        Job descriptor = new Job(runId, kind, triggeredBy, Instant.now(), description);
        running.put(runId, descriptor);
        String correlation = MDC.get("correlation_id");
        try {
            workers.submit(() -> {
                MDC.put("mars.run.id", runId);
                if (correlation != null) {
                    MDC.put("correlation_id", correlation);
                }
                boolean failed = false;
                String error = null;
                notify(l -> l.started(descriptor));
                try {
                    job.run();
                } catch (RuntimeException e) {
                    failed = true;
                    error = paths.redact(e.getMessage());
                    LOG.warn("{} of {} ended with an error: {}", kind, runId, error);
                } finally {
                    running.remove(runId);
                    finished.put(runId, new Finished(runId, kind, descriptor.startedAt(), Instant.now(), failed, error));
                    permit.release();
                    boolean failedFinal = failed;
                    String errorFinal = error;
                    notify(l -> l.finished(descriptor, failedFinal, errorFinal));
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            running.remove(runId);
            permit.release();
            throw new ApiException(ApiErrorCode.RUN_BUSY, runId, "The Control Center is shutting down");
        }
        return descriptor;
    }

    /** Telemetry must never break a run: a failing listener is logged and skipped. */
    private void notify(java.util.function.Consumer<RunJobListener> call) {
        for (RunJobListener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                LOG.warn("Run job listener {} failed: {}", listener.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public Optional<Job> running(String runId) {
        return Optional.ofNullable(running.get(runId));
    }

    public Optional<Finished> lastFinished(String runId) {
        return Optional.ofNullable(finished.get(runId));
    }

    public Map<String, Job> allRunning() {
        return Map.copyOf(running);
    }

    public boolean busy(String runId) {
        Semaphore permit = permits.get(runId);
        return permit != null && permit.availablePermits() == 0;
    }

    private Semaphore permit(String runId) {
        return permits.computeIfAbsent(runId, k -> new Semaphore(1));
    }

    private ApiException busyError(String runId) {
        Job job = running.get(runId);
        return new ApiException(ApiErrorCode.RUN_BUSY, runId, job == null
                ? "Another command is being recorded for " + runId + "; retry when it completes"
                : "MARS is " + (job.kind() == JobKind.ANALYZE ? "analyzing" : "advancing") + " " + runId + " (since "
                + job.startedAt() + "); wait for it to stop", job == null ? Map.of()
                : Map.of("job", job.kind().name(), "started_at", job.startedAt().toString()));
    }

    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }
}
