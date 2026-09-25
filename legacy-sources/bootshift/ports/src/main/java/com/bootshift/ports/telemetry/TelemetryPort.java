package com.bootshift.ports.telemetry;

import java.util.Map;

/**
 * Structured, OpenTelemetry-compatible correlation (spec section 51).
 *
 * <p>Logs help operate the harness. Evidence proves what happened. This port produces the former and
 * must never be used as a substitute for the latter (R22).
 */
public interface TelemetryPort {

    interface Span extends AutoCloseable {
        Span attribute(String key, Object value);

        Span event(String name, Map<String, Object> attributes);

        void error(Throwable error);

        String traceId();

        String spanId();

        @Override
        void close();
    }

    Span startSpan(String name, Map<String, Object> attributes);

    void counter(String name, long delta, Map<String, Object> attributes);

    void gauge(String name, double value, Map<String, Object> attributes);
}
