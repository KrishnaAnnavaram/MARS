package com.bootshift.ports.characterization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An executable characterization contract.
 *
 * <p>Stage 10 previously produced a <em>description</em> of a probe: a transport, a path and a list
 * of things to capture. Nothing executed it. Every contract therefore sat in
 * {@code AWAITING_OLD_OBSERVATION} for the life of the run while the report counted it as
 * protection, and differential validation - having no scenario to run - fell back to comparing
 * Actuator metadata between the two sides.
 *
 * <p>A scenario is executable: it names exactly what to do, what to record, and how to normalize the
 * result, and it carries the OLD observation once one exists. Until a scenario has been executed
 * against the original application it is not an oracle, and {@link State#FROZEN} is the only state
 * that says it is. Deriving the expected value by reading migrated code is not a path this model
 * offers.
 */
public record Scenario(String scenarioId, String edgeId, String impactId, Dimension dimension,
                       String module, String target, Map<String, Object> setup,
                       Map<String, Object> input, List<String> capture, String normalizer,
                       String oldObservationRef, State state, String reason,
                       List<String> knowledgeRefs, boolean critical) {

    /** Scenario families. Mirrors the differential dimensions so a scenario maps to one comparison. */
    public enum Dimension {
        HTTP_API,
        SECURITY_AUTHORIZATION,
        SERIALIZATION,
        CONFIGURATION_BINDING,
        SPRING_CONTEXT,
        PERSISTENCE_STATE,
        QUERY_RESULT,
        TRANSACTION_EFFECT,
        EVENT_MESSAGE,
        EXTERNAL_INTEGRATION,
        BATCH_RESULT,
        BUSINESS_RULE_OUTCOME;

        /** True when only a running application can produce this observation. */
        public boolean requiresRunningApplication() {
            return this != SPRING_CONTEXT || true;
        }

        /** True when the observation additionally needs provisioned external infrastructure. */
        public boolean requiresExternalInfrastructure() {
            return switch (this) {
                case PERSISTENCE_STATE, QUERY_RESULT, TRANSACTION_EFFECT, EVENT_MESSAGE,
                     EXTERNAL_INTEGRATION, BATCH_RESULT -> true;
                default -> false;
            };
        }
    }

    /**
     * Lifecycle.
     *
     * <p>{@code AWAITING_OLD_OBSERVATION} is deliberately not a protected state. It means a probe
     * was written and never run, which protects nothing; the planner refuses to freeze a plan whose
     * critical scenarios are all sitting in it.
     */
    public enum State {
        /** Constructed but not yet attempted. */
        DRAFT,
        /** Written, not yet executed against OLD. Not protection. */
        AWAITING_OLD_OBSERVATION,
        /** Executed against OLD; the observed behaviour is the oracle. */
        FROZEN,
        /** An existing application test already covers this and passed at baseline. */
        MAPPED_TO_EXISTING_VERIFIED_TEST,
        /** Cannot be observed here, and the gap is recorded explicitly. */
        UNOBSERVABLE_WITH_EXPLICIT_GAP,
        /** Only a human decision can let the migration proceed without this evidence. */
        HUMAN_EXCEPTION_REQUIRED,
        /** Executed against OLD and found not to hold there. */
        REJECTED;

        /** States that count as this scenario being genuinely accounted for before PLAN_FROZEN. */
        public boolean accountedFor() {
            return this == FROZEN || this == MAPPED_TO_EXISTING_VERIFIED_TEST
                    || this == UNOBSERVABLE_WITH_EXPLICIT_GAP || this == HUMAN_EXCEPTION_REQUIRED;
        }

        /** True when this scenario may be used as an expected value. */
        public boolean isOracle() {
            return this == FROZEN || this == MAPPED_TO_EXISTING_VERIFIED_TEST;
        }
    }

    public Scenario withState(State newState, String newReason) {
        return new Scenario(scenarioId, edgeId, impactId, dimension, module, target, setup, input,
                capture, normalizer, oldObservationRef, newState, newReason, knowledgeRefs, critical);
    }

    public Scenario withOldObservation(String ref) {
        return new Scenario(scenarioId, edgeId, impactId, dimension, module, target, setup, input,
                capture, normalizer, ref, State.FROZEN,
                "Executed against the original application; the observed behaviour is the oracle",
                knowledgeRefs, critical);
    }

    public Scenario withEdge(String newEdgeId) {
        return new Scenario(scenarioId, newEdgeId, impactId, dimension, module, target, setup, input,
                capture, normalizer, oldObservationRef, state, reason, knowledgeRefs, critical);
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("scenario_id", scenarioId);
        node.put("edge_id", edgeId);
        node.put("impact_id", impactId);
        node.put("dimension", dimension.name());
        node.put("module", module);
        node.put("target", target);
        node.set("setup", Json.toTree(setup));
        node.set("input", Json.toTree(input));
        node.set("capture", Json.toTree(capture));
        node.put("normalizer", normalizer);
        node.put("old_observation_ref", oldObservationRef);
        node.put("state", state.name());
        node.put("reason", reason);
        node.put("critical", critical);
        node.put("is_oracle", state.isOracle());
        node.put("accounted_for", state.accountedFor());
        node.set("knowledge_refs", Json.toTree(knowledgeRefs));
        node.put("requires_external_infrastructure", dimension.requiresExternalInfrastructure());
        return node;
    }

    public static Scenario fromNode(JsonNode node) {
        Map<String, Object> setup = new LinkedHashMap<>();
        node.path("setup").fields().forEachRemaining(e -> setup.put(e.getKey(), asPlain(e.getValue())));
        Map<String, Object> input = new LinkedHashMap<>();
        node.path("input").fields().forEachRemaining(e -> input.put(e.getKey(), asPlain(e.getValue())));
        List<String> capture = new ArrayList<>();
        node.path("capture").forEach(n -> capture.add(n.asText()));
        List<String> knowledge = new ArrayList<>();
        node.path("knowledge_refs").forEach(n -> knowledge.add(n.asText()));
        return new Scenario(node.path("scenario_id").asText(),
                node.path("edge_id").asText(null), node.path("impact_id").asText(null),
                Dimension.valueOf(node.path("dimension").asText("HTTP_API")),
                node.path("module").asText(null), node.path("target").asText(null),
                setup, input, capture, node.path("normalizer").asText("default"),
                node.path("old_observation_ref").asText(null),
                State.valueOf(node.path("state").asText("DRAFT")),
                node.path("reason").asText(null), knowledge,
                node.path("critical").asBoolean(false));
    }

    private static Object asPlain(JsonNode value) {
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        return value.asText();
    }

    /** Convenience accessor for HTTP scenarios. */
    public String httpMethod() {
        Object method = input.get("method");
        return method == null ? "GET" : String.valueOf(method);
    }

    public String httpPath() {
        Object path = input.get("path");
        return path == null ? "/" : String.valueOf(path);
    }
}
