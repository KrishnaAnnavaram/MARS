package com.bootshift.ports.characterization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable execution of a {@link Scenario} against one side of the migration.
 *
 * <p>An observation records what happened, not what should have happened. The OLD observation
 * becomes the oracle by being executed first; the NEW observation is compared against it. Neither is
 * ever derived from the other, and neither is ever derived from reading the code - that is the whole
 * point of executing the scenario rather than describing it.
 */
public record ScenarioObservation(String scenarioId, Side side, boolean executed, boolean successful,
                                  Map<String, Object> captured, String rawHash, String executedAt,
                                  long durationMillis, String failureReason,
                                  Map<String, String> environment) {

    public enum Side {
        OLD, NEW
    }

    public static ScenarioObservation notExecuted(String scenarioId, Side side, String reason) {
        return new ScenarioObservation(scenarioId, side, false, false, Map.of(), null,
                java.time.Instant.now().toString(), 0, reason, Map.of());
    }

    public static ScenarioObservation of(String scenarioId, Side side, Map<String, Object> captured,
                                         long durationMillis, Map<String, String> environment) {
        String canonical = Json.canonical(captured);
        return new ScenarioObservation(scenarioId, side, true, true, captured,
                Hashing.sha256(canonical), java.time.Instant.now().toString(), durationMillis, null,
                environment);
    }

    public static ScenarioObservation failed(String scenarioId, Side side, String reason,
                                             long durationMillis) {
        return new ScenarioObservation(scenarioId, side, true, false, Map.of(), null,
                java.time.Instant.now().toString(), durationMillis, reason, Map.of());
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("scenario_id", scenarioId);
        node.put("side", side.name());
        node.put("executed", executed);
        node.put("successful", successful);
        node.set("captured", Json.toTree(captured));
        node.put("raw_hash", rawHash);
        node.put("executed_at", executedAt);
        node.put("duration_ms", durationMillis);
        node.put("failure_reason", failureReason);
        node.set("environment", Json.toTree(environment));
        return node;
    }

    public static ScenarioObservation fromNode(JsonNode node) {
        Map<String, Object> captured = new LinkedHashMap<>();
        node.path("captured").fields().forEachRemaining(e -> captured.put(e.getKey(),
                e.getValue().isNumber() ? e.getValue().numberValue()
                        : e.getValue().isBoolean() ? e.getValue().booleanValue()
                        : e.getValue().isContainerNode() ? e.getValue().toString()
                        : e.getValue().asText()));
        Map<String, String> environment = new LinkedHashMap<>();
        node.path("environment").fields()
                .forEachRemaining(e -> environment.put(e.getKey(), e.getValue().asText()));
        return new ScenarioObservation(node.path("scenario_id").asText(),
                Side.valueOf(node.path("side").asText("OLD")),
                node.path("executed").asBoolean(false), node.path("successful").asBoolean(false),
                captured, node.path("raw_hash").asText(null),
                node.path("executed_at").asText(null), node.path("duration_ms").asLong(0),
                node.path("failure_reason").asText(null), environment);
    }
}
