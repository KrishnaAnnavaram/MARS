package com.mars.harness.tests.controlcenter;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A browser-like HTTP client for the Control Center: a cookie jar, the SPA CSRF convention
 * (XSRF-TOKEN cookie echoed as X-XSRF-TOKEN) and a minimal SSE reader.
 */
final class ControlCenterClient {

    record Response(int status, JsonNode body, Map<String, List<String>> headers) {
        String code() {
            return body == null ? null : body.path("code").asText(null);
        }
    }

    record SseEvent(String id, String name, JsonNode data) {
    }

    private final String base;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;

    ControlCenterClient(int port) {
        this.base = "http://127.0.0.1:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
    }

    ControlCenterClient login(String user) {
        get("/api/v1/session"); // obtains the XSRF-TOKEN cookie
        Response r = post("/api/v1/session/login", Map.of("username", user, "password", "mars-dev"), null);
        if (r.status() != 200) {
            throw new AssertionError("login " + user + " failed: " + r.status() + " " + r.body());
        }
        return this;
    }

    Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET().header("Accept", "application/json"));
    }

    Response post(String path, Object body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : KernelJson.pretty(body)))
                .header("Content-Type", "application/json").header("Accept", "application/json");
        csrf().ifPresent(t -> b.header("X-XSRF-TOKEN", t));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return send(b);
    }

    /** Posts without the CSRF header, as a forged cross-site request would. */
    Response postWithoutCsrf(String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(
                KernelJson.pretty(body))).header("Content-Type", "application/json"));
    }

    private java.util.Optional<String> csrf() {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue).findFirst();
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String text = response.body();
            JsonNode body = text == null || text.isBlank() ? null : text.trim().startsWith("{") || text.trim().startsWith("[")
                    ? KernelJson.parse(text) : KernelJson.mapper().getNodeFactory().textNode(text);
            return new Response(response.statusCode(), body, response.headers().map());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    JsonNode ok(Response r) {
        if (r.status() / 100 != 2) {
            throw new AssertionError("expected 2xx, got " + r.status() + ": " + r.body());
        }
        return r.body();
    }

    /** Polls the snapshot until the run is in one of {@code phases} and no server job is running. */
    JsonNode awaitPhase(String runId, Set<String> phases, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            Response r = get("/api/v1/runs/" + runId);
            if (r.status() == 200) {
                last = r.body();
                boolean advancing = "ADVANCING".equals(last.path("liveness").path("state").asText());
                if (phases.contains(last.path("phase").asText()) && !advancing) {
                    return last;
                }
            }
            sleep(250);
        }
        throw new AssertionError("run " + runId + " did not reach " + phases + " in " + timeout + "; last: "
                + (last == null ? null : last.path("phase") + " " + last.path("liveness")));
    }

    /** Reads SSE events until {@code done} accepts the list or the timeout passes. */
    List<SseEvent> sse(String path, String lastEventId, Predicate<List<SseEvent>> done, Duration timeout) {
        List<SseEvent> events = new ArrayList<>();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET().header("Accept", "text/event-stream");
        if (lastEventId != null) {
            b.header("Last-Event-ID", lastEventId);
        }
        Thread reader = new Thread(() -> {
            try {
                HttpResponse<java.io.InputStream> response = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String id = null;
                    String name = null;
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (data.length() > 0) {
                                synchronized (events) {
                                    events.add(new SseEvent(id, name, KernelJson.parse(data.toString())));
                                    if (done.test(events)) {
                                        return;
                                    }
                                }
                            }
                            id = null;
                            name = null;
                            data.setLength(0);
                        } else if (line.startsWith("id:")) {
                            id = line.substring(3).trim();
                        } else if (line.startsWith("event:")) {
                            name = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            data.append(line.substring(5));
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        reader.interrupt();
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
