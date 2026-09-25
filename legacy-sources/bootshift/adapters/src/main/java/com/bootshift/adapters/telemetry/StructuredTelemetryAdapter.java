package com.bootshift.adapters.telemetry;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;
import com.bootshift.ports.telemetry.TelemetryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured, OpenTelemetry-shaped telemetry without an SDK dependency.
 *
 * <p>Emits W3C-format trace and span identifiers and OTel-compatible attribute names to a JSONL
 * sink, so records can be shipped to a collector by any log pipeline. Correlation ids from spec
 * section 51 (run, edge, stage, file, symbol, change, impact) are first-class attributes.
 *
 * <p>This is operational telemetry. It is deliberately separate from the evidence plane (R22): a
 * span is not proof of anything.
 */
public final class StructuredTelemetryAdapter implements TelemetryPort {

    private static final Logger LOG = LoggerFactory.getLogger("bootshift.telemetry");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path sink;
    private final String runId;
    private final String traceId;

    public StructuredTelemetryAdapter(Path sink, String runId) {
        this.sink = sink;
        this.runId = runId;
        this.traceId = randomHex(16);
        if (sink != null) {
            try {
                Files.createDirectories(sink.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot create telemetry sink " + sink, e);
            }
        }
    }

    public String traceId() {
        return traceId;
    }

    @Override
    public Span startSpan(String name, Map<String, Object> attributes) {
        return new JsonSpan(name, attributes);
    }

    @Override
    public void counter(String name, long delta, Map<String, Object> attributes) {
        emit("metric", Map.of("metric.name", name, "metric.type", "counter",
                "metric.value", delta), attributes, null, null);
    }

    @Override
    public void gauge(String name, double value, Map<String, Object> attributes) {
        emit("metric", Map.of("metric.name", name, "metric.type", "gauge",
                "metric.value", value), attributes, null, null);
    }

    private void emit(String kind, Map<String, Object> core, Map<String, Object> attributes,
                      String spanId, String status) {
        ObjectNode node = Json.obj();
        node.put("timestamp", Instant.now().toString());
        node.put("kind", kind);
        node.put("trace_id", traceId);
        node.put("span_id", spanId);
        node.put("run_id", runId);
        node.put("status", status);
        Map<String, Object> merged = new LinkedHashMap<>(core);
        if (attributes != null) {
            merged.putAll(attributes);
        }
        node.set("attributes", Json.toTree(merged));
        String line = Json.canonical(node);
        if (sink != null) {
            try {
                Files.writeString(sink, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.warn("Cannot append telemetry record: {}", e.getMessage());
            }
        }
        LOG.debug("{}", line);
    }

    private final class JsonSpan implements Span {

        private final String name;
        private final String spanId = randomHex(8);
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final long startNanos = System.nanoTime();
        private String status = "OK";

        private JsonSpan(String name, Map<String, Object> initial) {
            this.name = name;
            if (initial != null) {
                attributes.putAll(initial);
            }
            emit("span.start", Map.of("span.name", name), attributes, spanId, null);
        }

        @Override
        public Span attribute(String key, Object value) {
            attributes.put(key, value);
            return this;
        }

        @Override
        public Span event(String eventName, Map<String, Object> eventAttributes) {
            emit("span.event", Map.of("span.name", name, "event.name", eventName),
                    eventAttributes, spanId, null);
            return this;
        }

        @Override
        public void error(Throwable error) {
            status = "ERROR";
            attributes.put("exception.type", error.getClass().getName());
            attributes.put("exception.message", String.valueOf(error.getMessage()));
        }

        @Override
        public String traceId() {
            return traceId;
        }

        @Override
        public String spanId() {
            return spanId;
        }

        @Override
        public void close() {
            attributes.put("duration.ms", (System.nanoTime() - startNanos) / 1_000_000);
            emit("span.end", Map.of("span.name", name), attributes, spanId, status);
        }
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
