package com.mars.harness.tests.controlcenter;

import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.controlcenter.events.EventIndex;
import com.mars.harness.controlcenter.observability.RunTelemetry;
import com.mars.harness.controlcenter.run.RunCoordinator;
import com.mars.harness.kernel.adapters.store.FilesystemExecutionEventStore;
import com.mars.harness.kernel.core.event.ActivityStatus;
import com.mars.harness.kernel.core.event.ExecutionEvent;
import com.mars.harness.kernel.core.event.ExecutionEventType;
import com.mars.harness.kernel.core.run.RunLayout;
import com.mars.harness.kernel.core.run.RunPhase;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Operational telemetry is derived from the run's own events and keeps metric labels bounded. */
class RunTelemetryTest {

    private static final String RUN = "RUN-01M3AAAAAAAAAAAAAAAAAAAAAA";

    static final class Collected implements SpanExporter {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    private static ExecutionEvent event(ExecutionEventType type, ActivityStatus status, String activity, Instant at) {
        return new ExecutionEvent(null, RUN, 0, at.toString(), type, null, RunPhase.DISCOVERY_RUNNING, "Security / RCA",
                activity, status, type.name(), null, null, List.of(new ExecutionEvent.SubjectRef("FINDING", "FINDING-1", "INV-1")),
                List.of(), List.of(), null, Map.of("capability", "vulnerability-remediation"));
    }

    @Test
    void jobSpansAreRebuiltFromTheRunsEventsAndMetricsCarryNoRunIdentifiers(@TempDir Path temp) {
        Path runs = temp.resolve("runs");
        ControlCenterPaths paths = new ControlCenterPaths(temp, runs, List.of());
        EventIndex index = new EventIndex(paths);
        Collected exporter = new Collected();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build();
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("otel", otel));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RunTelemetry telemetry = new RunTelemetry(meters, index, beans.getBeanProvider(OpenTelemetry.class));

        String runId = RUN;
        RunCoordinator.Job job = new RunCoordinator.Job(runId, RunCoordinator.JobKind.ADVANCE, "approver", Instant.now(), "x");
        telemetry.started(job);
        FilesystemExecutionEventStore store = new FilesystemExecutionEventStore(new RunLayout(runs, runId).events());
        Instant t0 = Instant.parse("2026-09-27T10:00:00Z");
        store.append(event(ExecutionEventType.RCA_STARTED, ActivityStatus.STARTED, "security.rca", t0));
        store.append(event(ExecutionEventType.RCA_COMPLETED, ActivityStatus.COMPLETED, "security.rca", t0.plusMillis(1500)));
        telemetry.finished(job, false, null);

        List<String> names = new ArrayList<>(exporter.spans.stream().map(SpanData::getName).toList());
        assertThat(names).containsExactlyInAnyOrder("mars.run.advance", "security.rca");
        SpanData rca = exporter.spans.stream().filter(s -> s.getName().equals("security.rca")).findFirst().orElseThrow();
        SpanData root = exporter.spans.stream().filter(s -> s.getName().equals("mars.run.advance")).findFirst().orElseThrow();
        assertThat(rca.getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(TimeUnit.NANOSECONDS.toMillis(rca.getStartEpochNanos())).isEqualTo(t0.toEpochMilli());
        assertThat(TimeUnit.NANOSECONDS.toMillis(rca.getEndEpochNanos() - rca.getStartEpochNanos())).isEqualTo(1500);
        assertThat(rca.getAttributes().asMap().toString()).contains(runId).contains("FINDING-1").contains("vulnerability-remediation");

        assertThat(meters.find("mars.execution.events").counters()).isNotEmpty();
        assertThat(meters.find("mars.control_center.jobs").timer()).isNotNull();
        for (Meter meter : meters.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertThat(tag.getValue()).as("metric %s tag %s", meter.getId().getName(), tag.getKey())
                        .doesNotStartWith("RUN-").doesNotStartWith("FINDING-").doesNotStartWith("PROP-");
            });
        }
    }
}
