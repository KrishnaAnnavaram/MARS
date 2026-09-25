package com.bootshift.adapters.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.security.SensitiveValues;
import com.bootshift.core.util.Json;
import com.bootshift.ports.characterization.Scenario;
import com.bootshift.ports.characterization.ScenarioObservation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Executes characterization scenarios against a running application over HTTP.
 *
 * <p>This is what turns a contract into evidence. The same scenario object is executed against the
 * original application and against the migrated one, and the two observations are what differential
 * validation compares. Comparing Actuator metadata - which is what the harness did while no scenario
 * was ever executed - tells you the two contexts have a similar shape; it tells you nothing about
 * whether an endpoint still returns the same status, the same content type or the same body.
 *
 * <p>What is captured is deliberately structural rather than literal. Two runs of the same
 * application differ in timestamps, generated identifiers and ordering, so the observation records
 * status, content type, header names, body shape and the sorted set of field paths. A body that
 * genuinely changed shape shows up; a body whose {@code timestamp} moved does not.
 */
public final class ScenarioHttpExecutor {

    private final HttpClient http;
    private final Duration timeout;

    public ScenarioHttpExecutor() {
        this(Duration.ofSeconds(15));
    }

    public ScenarioHttpExecutor(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Never follow a redirect automatically: the redirect itself is behaviour, and a
                // migration that starts redirecting where it used to serve is exactly the kind of
                // change this comparison exists to catch.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** True when this executor can run the scenario at all. */
    public boolean supports(Scenario scenario) {
        return switch (scenario.dimension()) {
            case HTTP_API, SECURITY_AUTHORIZATION, SERIALIZATION, CONFIGURATION_BINDING,
                 SPRING_CONTEXT -> true;
            default -> false;
        };
    }

    /**
     * Executes one scenario against a running application.
     *
     * @param baseUrl the application's base URL
     * @param side    which side of the migration this observation belongs to
     */
    public ScenarioObservation execute(Scenario scenario, String baseUrl,
                                       ScenarioObservation.Side side,
                                       Map<String, String> environment) {
        if (!supports(scenario)) {
            return ScenarioObservation.notExecuted(scenario.scenarioId(), side,
                    "The HTTP executor cannot observe dimension " + scenario.dimension()
                            + "; it requires infrastructure this executor does not provision");
        }
        long start = System.currentTimeMillis();
        String path = scenario.httpPath();
        String method = scenario.httpMethod().toUpperCase(Locale.ROOT);
        String url = baseUrl + (path.startsWith("/") ? path : "/" + path);

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout);
            // Authorization scenarios differ from plain HTTP ones only in what they send: the point
            // is to observe what the application does with a caller who has, or lacks, a credential.
            Object authorization = scenario.input().get("authorization");
            if (authorization != null && !String.valueOf(authorization).isBlank()) {
                request.header("Authorization", String.valueOf(authorization));
            }
            Object accept = scenario.input().get("accept");
            request.header("Accept", accept == null ? "application/json" : String.valueOf(accept));

            Object body = scenario.input().get("body");
            if (body != null && !String.valueOf(body).isBlank()) {
                request.header("Content-Type", String.valueOf(
                        scenario.input().getOrDefault("content_type", "application/json")));
                request.method(method, HttpRequest.BodyPublishers.ofString(String.valueOf(body)));
            } else if ("GET".equals(method) || "DELETE".equals(method)) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            Map<String, Object> captured = capture(scenario, response);
            return ScenarioObservation.of(scenario.scenarioId(), side, captured,
                    System.currentTimeMillis() - start, environment);
        } catch (Exception e) {
            return ScenarioObservation.failed(scenario.scenarioId(), side,
                    e.getClass().getSimpleName() + ": " + SensitiveValues.redactLine(null,
                            String.valueOf(e.getMessage())),
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * Extracts the comparable facts from a response.
     *
     * <p>Only what the scenario asked to capture. Recording everything and normalizing it afterwards
     * is how a comparison ends up dominated by fields nobody meant to compare.
     */
    private Map<String, Object> capture(Scenario scenario, HttpResponse<String> response) {
        Map<String, Object> captured = new LinkedHashMap<>();
        List<String> wanted = scenario.capture().isEmpty()
                ? List.of("status_code", "content_type", "body_shape") : scenario.capture();

        for (String item : wanted) {
            switch (item) {
                case "status_code" -> captured.put("status_code", response.statusCode());
                case "status_class" -> captured.put("status_class", response.statusCode() / 100);
                case "content_type" -> captured.put("content_type",
                        response.headers().firstValue("content-type").orElse("<absent>"));
                case "header_names" -> captured.put("header_names", sortedHeaderNames(response));
                case "security_headers" -> captured.put("security_headers", securityHeaders(response));
                case "www_authenticate" -> captured.put("www_authenticate",
                        response.headers().firstValue("www-authenticate").orElse("<absent>"));
                case "location" -> captured.put("location",
                        response.headers().firstValue("location").map(ScenarioHttpExecutor::stripHost)
                                .orElse("<absent>"));
                case "body_shape" -> captured.put("body_shape", bodyShape(response.body()));
                case "body_field_paths" -> captured.put("body_field_paths",
                        fieldPaths(response.body()));
                case "error_payload_shape" -> captured.put("error_payload_shape",
                        response.statusCode() >= 400 ? bodyShape(response.body()) : "<not-an-error>");
                case "body_length_class" -> captured.put("body_length_class",
                        lengthClass(response.body()));
                default -> captured.put(item, "<unsupported-capture>");
            }
        }
        return captured;
    }

    private static List<String> sortedHeaderNames(HttpResponse<String> response) {
        List<String> names = new ArrayList<>(response.headers().map().keySet());
        // Headers whose presence is a per-response artefact rather than behaviour.
        names.removeIf(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.equals("date") || lower.equals("content-length")
                    || lower.startsWith(":") || lower.equals("keep-alive");
        });
        names.replaceAll(name -> name.toLowerCase(Locale.ROOT));
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Security-relevant response headers, by name and by value.
     *
     * <p>Values matter here, unlike elsewhere: a {@code Set-Cookie} that loses {@code HttpOnly} or a
     * {@code Strict-Transport-Security} that loses {@code max-age} is a security regression whose
     * whole content is in the value.
     */
    private static Map<String, String> securityHeaders(HttpResponse<String> response) {
        Map<String, String> headers = new TreeMap<>();
        List<String> interesting = List.of("www-authenticate", "strict-transport-security",
                "x-content-type-options", "x-frame-options", "content-security-policy",
                "referrer-policy", "cache-control", "pragma", "expires",
                "access-control-allow-origin", "access-control-allow-credentials");
        for (String name : interesting) {
            response.headers().firstValue(name).ifPresent(value -> headers.put(name, value));
        }
        // Cookie flags without the cookie value: the flags are the security property.
        response.headers().allValues("set-cookie").forEach(cookie -> {
            int equals = cookie.indexOf('=');
            String cookieName = equals > 0 ? cookie.substring(0, equals) : "cookie";
            List<String> flags = new ArrayList<>();
            for (String attribute : cookie.split(";")) {
                String trimmed = attribute.trim().toLowerCase(Locale.ROOT);
                if (trimmed.startsWith("httponly") || trimmed.startsWith("secure")
                        || trimmed.startsWith("samesite") || trimmed.startsWith("path")
                        || trimmed.startsWith("max-age")) {
                    flags.add(trimmed.split("=")[0]);
                }
            }
            flags.sort(String::compareTo);
            headers.put("set-cookie:" + cookieName, String.join(",", flags));
        });
        return headers;
    }

    /**
     * The structural shape of a body: types and nesting, never values.
     *
     * <p>Values are data. Shape is contract. A migration that starts returning a string where it
     * returned a number has broken every consumer, and that is visible here without the comparison
     * drowning in per-request identifiers.
     */
    static String bodyShape(String body) {
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        try {
            JsonNode node = Json.parse(body);
            return shapeOf(node, 0);
        } catch (RuntimeException e) {
            String trimmed = body.trim();
            if (trimmed.startsWith("<")) {
                return "xml-or-html";
            }
            return "text";
        }
    }

    private static String shapeOf(JsonNode node, int depth) {
        if (depth > 6) {
            return "...";
        }
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fields().forEachRemaining(e ->
                    fields.add(e.getKey() + ":" + shapeOf(e.getValue(), depth + 1)));
            fields.sort(String::compareTo);
            return "{" + String.join(",", fields) + "}";
        }
        if (node.isArray()) {
            // Element type, not element count: an array whose length varies per request is not a
            // contract change, but an array whose elements change shape is.
            return node.isEmpty() ? "[]" : "[" + shapeOf(node.get(0), depth + 1) + "]";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? "integer" : "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return node.isNull() ? "null" : "unknown";
    }

    /** Sorted dotted field paths, so a field appearing or disappearing is immediately visible. */
    static List<String> fieldPaths(String body) {
        List<String> paths = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return paths;
        }
        try {
            collectPaths(Json.parse(body), "", paths, 0);
        } catch (RuntimeException e) {
            return List.of("<non-json>");
        }
        paths.sort(String::compareTo);
        return paths;
    }

    private static void collectPaths(JsonNode node, String prefix, List<String> paths, int depth) {
        if (depth > 6 || paths.size() > 400) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectPaths(e.getValue(),
                    prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), paths, depth + 1));
        } else if (node.isArray()) {
            if (!node.isEmpty()) {
                collectPaths(node.get(0), prefix + "[]", paths, depth + 1);
            }
        } else if (!prefix.isEmpty()) {
            paths.add(prefix);
        }
    }

    /**
     * Body size bucketed rather than exact.
     *
     * <p>An exact length differs whenever a generated identifier does. The bucket still catches a
     * response that collapsed to empty or ballooned into a stack trace.
     */
    static String lengthClass(String body) {
        int length = body == null ? 0 : body.length();
        if (length == 0) {
            return "empty";
        }
        if (length < 64) {
            return "tiny";
        }
        if (length < 1024) {
            return "small";
        }
        if (length < 65536) {
            return "medium";
        }
        return "large";
    }

    /** Strips scheme and authority so a port allocated per side is not a difference. */
    private static String stripHost(String location) {
        try {
            URI uri = URI.create(location);
            return uri.getPath() == null ? location : uri.getPath()
                    + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
        } catch (RuntimeException e) {
            return location;
        }
    }
}
