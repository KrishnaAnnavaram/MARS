package com.mars.harness.controlcenter.observability;

import com.mars.harness.controlcenter.events.EventIndex;
import com.mars.harness.controlcenter.run.RunCoordinator;
import com.mars.harness.controlcenter.run.RunJobListener;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.event.ActivityStatus;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Operational telemetry for the Control Center: metrics and traces about the engine operations
 * it runs. This is system observability, not the domain record: the UI never reads it, and a run
 * is fully described by its artifacts and execution events without it.
 *
 * <p>Traces: one span per engine job ({@code mars.run.analyze} / {@code mars.run.advance}); when
 * the job ends, child spans are reconstructed from the run's own STARTED/COMPLETED execution events
 * with their recorded timestamps, so the kernel needs no tracing dependency.
 *
 * <p>Metrics carry only bounded labels (job kind, outcome, event category and type, decision
 * type). Run, finding and proposal IDs appear on spans, never on metrics.
 */
@Component
public class RunTelemetry implements RunJobListener {

    private static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("mars.run.id");
    private static final AttributeKey<String> STATE = AttributeKey.stringKey("mars.state");
    private static final AttributeKey<String> ACTIVITY = AttributeKey.stringKey("mars.activity");
    private static final AttributeKey<String> COMPONENT = AttributeKey.stringKey("mars.component");
    private static final AttributeKey<String> CAPABILITY = AttributeKey.stringKey("mars.capability");
    private static final AttributeKey<String> RESULT = AttributeKey.stringKey("mars.result");
    private static final AttributeKey<String> FINDING = AttributeKey.stringKey("mars.finding.id");
    private static final AttributeKey<String> PROPOSAL = AttributeKey.stringKey("mars.proposal.id");
    private static final AttributeKey<Long> ROUND = AttributeKey.longKey("mars.round");

    private final MeterRegistry meters;
    private final EventIndex events;
    private final Tracer tracer;
    private final AtomicInteger active = new AtomicInteger();
    private final Map<String, Open> open = new ConcurrentHashMap<>();

    private record Open(Span span, long startSequence, Instant startedAt) {
    }

    public RunTelemetry(MeterRegistry meters, EventIndex events, ObjectProvider<OpenTelemetry> openTelemetry) {
        this.meters = meters;
        this.events = events;
        this.tracer = openTelemetry.getIfAvailable(OpenTelemetry::noop).getTracer("mars-control-center");
        meters.gauge("mars.control_center.jobs.active", active);
    }

    @Override
    public void started(RunCoordinator.Job job) {
        active.incrementAndGet();
        Span span = tracer.spanBuilder("mars.run." + job.kind().name().toLowerCase(java.util.Locale.ROOT))
                .setSpanKind(SpanKind.INTERNAL).setNoParent().setAttribute(RUN_ID, job.runId()).startSpan();
        open.put(job.runId(), new Open(span, events.lastSequence(job.runId()), Instant.now()));
    }

    @Override
    public void finished(RunCoordinator.Job job, boolean failed, String error) {
        active.decrementAndGet();
        String outcome = failed ? "error" : "ok";
        Timer.builder("mars.control_center.jobs").tag("kind", job.kind().name()).tag("outcome", outcome)
                .description("Engine operations run by the Control Center").register(meters)
                .record(Duration.between(job.startedAt(), Instant.now()));
        Open o = open.remove(job.runId());
        if (o == null) {
            return;
        }
        List<ExecutionEvent> during = events.after(job.runId(), o.startSequence());
        Map<String, ExecutionEvent> started = new HashMap<>();
        Context parent = Context.root().with(o.span());
        for (ExecutionEvent e : during) {
            Counter.builder("mars.execution.events").tag("category", e.category().name()).tag("type", e.type().name())
                    .tag("status", e.status().name()).register(meters).increment();
            if (e.activity() == null) {
                continue;
            }
            if (e.status() == ActivityStatus.STARTED) {
                started.put(e.activity(), e);
            } else if (started.containsKey(e.activity()) && (e.status() == ActivityStatus.COMPLETED
                    || e.status() == ActivityStatus.FAILED || e.status() == ActivityStatus.SKIPPED
                    || e.status() == ActivityStatus.WAITING)) {
                span(parent, started.remove(e.activity()), e);
            }
            if (e.phase() != null) {
                o.span().setAttribute(STATE, e.phase().name());
            }
        }
        o.span().setAttribute(RESULT, outcome);
        if (failed) {
            o.span().setStatus(StatusCode.ERROR, error == null ? "failed" : error);
        }
        o.span().end();
    }

    private void span(Context parent, ExecutionEvent start, ExecutionEvent end) {
        Instant from = Instant.parse(start.timestamp());
        Instant to = Instant.parse(end.timestamp());
        var builder = tracer.spanBuilder(start.activity()).setParent(parent).setSpanKind(SpanKind.INTERNAL)
                .setStartTimestamp(from.toEpochMilli(), TimeUnit.MILLISECONDS)
                .setAttribute(RUN_ID, start.runId()).setAttribute(ACTIVITY, start.activity())
                .setAttribute(RESULT, end.status().name());
        if (start.component() != null) {
            builder.setAttribute(COMPONENT, start.component());
        }
        if (start.phase() != null) {
            builder.setAttribute(STATE, start.phase().name());
        }
        String capability = start.attributes().get("capability");
        if (capability != null) {
            builder.setAttribute(CAPABILITY, capability);
        }
        String round = start.attributes().get("round");
        if (round != null && round.matches("\\d+")) {
            builder.setAttribute(ROUND, Long.parseLong(round));
        }
        start.subjects().forEach(s -> {
            if ("FINDING".equals(s.kind())) {
                builder.setAttribute(FINDING, s.id());
            } else if ("PROPOSAL".equals(s.kind())) {
                builder.setAttribute(PROPOSAL, s.id());
            }
        });
        Span span = builder.startSpan();
        if (end.status() == ActivityStatus.FAILED) {
            span.setStatus(StatusCode.ERROR, end.title() == null ? "failed" : end.title());
        }
        span.end(to.toEpochMilli(), TimeUnit.MILLISECONDS);
    }

    /** A decision recorded through the Control Center. */
    public void decisionRecorded(Decision decision) {
        Counter.builder("mars.control_center.decisions").tag("type", decision.type().name())
                .tag("selected", decision.selected()).description("Human decisions recorded through the Control Center")
                .register(meters).increment();
    }
}
