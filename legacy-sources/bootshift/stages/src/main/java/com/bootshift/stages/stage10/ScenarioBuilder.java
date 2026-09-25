package com.bootshift.stages.stage10;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.util.Ids;
import com.bootshift.ports.characterization.Scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds executable characterization scenarios from the graph and the impact set.
 *
 * <p>A scenario is only worth creating if it can be executed. This builder therefore emits concrete
 * requests - a method, a path with its variables substituted, the headers that make the observation
 * meaningful, and the exact facts to capture - rather than a description of a probe that something
 * else would have to interpret.
 *
 * <p>Dimensions that need infrastructure the environment cannot supply are still emitted, in state
 * {@link Scenario.State#UNOBSERVABLE_WITH_EXPLICIT_GAP}. Leaving them out entirely would make the
 * coverage statement describe a smaller migration than the one being performed.
 */
public final class ScenarioBuilder {

    /** Deterministic substitutions for path variables, so both sides send byte-identical requests. */
    private static final Map<String, String> PATH_VARIABLE_VALUES = Map.of(
            "id", "bootshift-characterization-id",
            "name", "bootshift-characterization-name",
            "code", "bootshift-characterization-code",
            "type", "bootshift-characterization-type");

    private static final String DEFAULT_PATH_VALUE = "bootshift-characterization-value";

    private final AtomicLong sequence = new AtomicLong();

    /** Scenarios derived from the application's own observable surface. */
    public List<Scenario> fromGraph(ApplicationGraph graph, Set<String> modulesThatStart,
                                    boolean externalInfrastructureAvailable) {
        List<Scenario> scenarios = new ArrayList<>();
        Set<String> seenTargets = new LinkedHashSet<>();

        for (GraphNode endpoint : graph.nodesOfType(NodeType.ENDPOINT)) {
            String module = endpoint.getModule();
            String method = String.valueOf(endpoint.getProperties().getOrDefault("http_method", "GET"));
            String rawPath = String.valueOf(endpoint.getProperties().getOrDefault("path", "/"));
            String handler = String.valueOf(endpoint.getProperties().getOrDefault("handler", ""));
            String concretePath = substituteVariables(rawPath);
            String target = method + " " + concretePath;
            if (!seenTargets.add(module + "|" + target)) {
                continue;
            }
            boolean observable = modulesThatStart.contains(module);

            // 1. The endpoint's own contract: status, media type and body shape.
            scenarios.add(http(module, endpoint, method, concretePath, handler, observable,
                    Scenario.Dimension.HTTP_API,
                    List.of("status_code", "content_type", "body_shape", "error_payload_shape",
                            "body_length_class", "location"),
                    Map.of()));

            // 2. The same endpoint called without a credential. What the application does with an
            //    unauthenticated caller is the authorization contract, and it is the single most
            //    commonly broken thing in a Spring Security 5 to 6 migration.
            scenarios.add(http(module, endpoint, method, concretePath, handler, observable,
                    Scenario.Dimension.SECURITY_AUTHORIZATION,
                    List.of("status_code", "www_authenticate", "security_headers", "location",
                            "header_names"),
                    Map.of("authorization", "")));

            // 3. And with a credential the application cannot possibly accept. A chain that used to
            //    reject this and now ignores it has changed meaning.
            scenarios.add(http(module, endpoint, method, concretePath, handler, observable,
                    Scenario.Dimension.SECURITY_AUTHORIZATION,
                    List.of("status_code", "www_authenticate", "security_headers"),
                    Map.of("authorization", "Bearer bootshift-invalid-token")));

            // 4. Serialization: the exact field paths the response carries.
            if (!"DELETE".equalsIgnoreCase(method)) {
                scenarios.add(http(module, endpoint, method, concretePath, handler, observable,
                        Scenario.Dimension.SERIALIZATION,
                        List.of("content_type", "body_field_paths", "body_shape"),
                        Map.of()));
            }
        }

        // 5. Configuration binding and context capability, per module that starts.
        for (String module : modulesThatStart) {
            scenarios.add(actuator(module, "/actuator/configprops",
                    Scenario.Dimension.CONFIGURATION_BINDING,
                    List.of("status_code", "body_field_paths"),
                    "Which configuration properties the application actually binds, by canonical key"));
            scenarios.add(actuator(module, "/actuator/env",
                    Scenario.Dimension.CONFIGURATION_BINDING,
                    List.of("status_code", "body_field_paths"),
                    "Property sources and their precedence as the application resolved them"));
            scenarios.add(actuator(module, "/actuator/beans",
                    Scenario.Dimension.SPRING_CONTEXT,
                    List.of("status_code", "body_field_paths"),
                    "The bean definitions the context actually created"));
            scenarios.add(actuator(module, "/actuator/conditions",
                    Scenario.Dimension.SPRING_CONTEXT,
                    List.of("status_code", "body_field_paths"),
                    "Which auto-configurations matched and which did not"));
            scenarios.add(actuator(module, "/actuator/mappings",
                    Scenario.Dimension.HTTP_API,
                    List.of("status_code", "body_field_paths"),
                    "The request mappings the running application registered"));
            scenarios.add(actuator(module, "/actuator/health",
                    Scenario.Dimension.SPRING_CONTEXT,
                    List.of("status_code", "body_shape"),
                    "Health contributors and their reported shape"));
        }

        // 6. Dimensions that need provisioned infrastructure. Emitted so the gap is counted.
        if (!externalInfrastructureAvailable) {
            for (String module : modulesThatStart) {
                for (Scenario.Dimension dimension : List.of(Scenario.Dimension.PERSISTENCE_STATE,
                        Scenario.Dimension.QUERY_RESULT, Scenario.Dimension.TRANSACTION_EFFECT)) {
                    scenarios.add(unobservable(module, dimension,
                            "This dimension requires a provisioned datastore with equivalent data on "
                                    + "both sides. The environment provider could not supply one, so "
                                    + "the behaviour is NOT_COMPARED rather than assumed unchanged."));
                }
            }
        }
        return scenarios;
    }

    /** Scenarios required by specific impact findings, bound to the fact that produced them. */
    public List<Scenario> fromImpacts(JsonNode impactReport, Set<String> modulesThatStart,
                                      boolean externalInfrastructureAvailable) {
        List<Scenario> scenarios = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode finding : impactReport.path("findings")) {
            if ("UNAFFECTED_WITHIN_OBSERVED_COVERAGE".equals(finding.path("classification").asText())) {
                continue;
            }
            String module = finding.path("module").asText(".");
            String impactId = finding.path("impact_id").asText();
            String knowledgeId = finding.path("knowledge_id").asText(null);
            for (JsonNode dimensionNode : finding.path("required_validation_dimensions")) {
                Scenario.Dimension dimension = parseDimension(dimensionNode.asText());
                if (dimension == null) {
                    continue;
                }
                // Deduplicated by module and dimension, not by subject. Every impact on the same
                // dimension in the same module resolves to the same executable request, so keying on
                // the subject produced hundreds of identical /actuator/beans calls - each counted as
                // a separate scenario, inflating both the coverage denominator and the run time
                // without observing anything new. The subjects are still carried as knowledge refs.
                String key = module + "|" + dimension;
                if (!seen.add(key)) {
                    continue;
                }
                boolean needsInfrastructure = dimension.requiresExternalInfrastructure();
                boolean observable = modulesThatStart.contains(module)
                        && (!needsInfrastructure || externalInfrastructureAvailable);
                Scenario.State state = observable
                        ? Scenario.State.DRAFT : Scenario.State.UNOBSERVABLE_WITH_EXPLICIT_GAP;
                String reason = observable
                        ? "Awaiting execution against the original application"
                        : (needsInfrastructure
                                ? "Requires provisioned external infrastructure that this environment "
                                        + "could not supply"
                                : "Module " + module + " did not start during baseline capture");

                Map<String, Object> input = new LinkedHashMap<>();
                input.put("method", "GET");
                input.put("path", defaultPathFor(dimension));
                scenarios.add(new Scenario(Ids.scenarioId(sequence.incrementAndGet()), null, impactId,
                        dimension, module, finding.path("subject").asText(null),
                        Map.of("origin", "impact"), input, captureFor(dimension), "default", null,
                        state, reason,
                        knowledgeId == null ? List.of() : List.of(knowledgeId), true));
            }
        }
        return scenarios;
    }

    // ------------------------------------------------------------------ construction helpers

    private Scenario http(String module, GraphNode endpoint, String method, String path,
                          String handler, boolean observable, Scenario.Dimension dimension,
                          List<String> capture, Map<String, Object> extraInput) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("method", "ANY".equalsIgnoreCase(method) ? "GET" : method);
        input.put("path", path);
        input.putAll(extraInput);
        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("origin", "endpoint-graph");
        setup.put("handler", handler);
        setup.put("declared_path", String.valueOf(endpoint.getProperties().get("path")));
        return new Scenario(Ids.scenarioId(sequence.incrementAndGet()), null, null, dimension,
                module, method + " " + path, setup, input, capture, "default", null,
                observable ? Scenario.State.DRAFT : Scenario.State.UNOBSERVABLE_WITH_EXPLICIT_GAP,
                observable ? "Awaiting execution against the original application"
                        : "Module " + module + " did not start during baseline capture, so this "
                                + "endpoint has no observable original behaviour to freeze",
                List.of(), true);
    }

    private Scenario actuator(String module, String path, Scenario.Dimension dimension,
                              List<String> capture, String purpose) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("method", "GET");
        input.put("path", path);
        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("origin", "actuator");
        setup.put("purpose", purpose);
        return new Scenario(Ids.scenarioId(sequence.incrementAndGet()), null, null, dimension,
                module, path, setup, input, capture, "default", null, Scenario.State.DRAFT,
                "Awaiting execution against the original application", List.of(), true);
    }

    private Scenario unobservable(String module, Scenario.Dimension dimension, String reason) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("method", "GET");
        input.put("path", defaultPathFor(dimension));
        return new Scenario(Ids.scenarioId(sequence.incrementAndGet()), null, null, dimension,
                module, dimension.name(), Map.of("origin", "infrastructure-gap"), input,
                captureFor(dimension), "default", null,
                Scenario.State.UNOBSERVABLE_WITH_EXPLICIT_GAP, reason, List.of(), true);
    }

    /** Replaces {@code {var}} segments with a fixed value so both sides send the same request. */
    static String substituteVariables(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < path.length()) {
            int open = path.indexOf('{', index);
            if (open < 0) {
                result.append(path, index, path.length());
                break;
            }
            int close = path.indexOf('}', open);
            if (close < 0) {
                result.append(path, index, path.length());
                break;
            }
            result.append(path, index, open);
            String variable = path.substring(open + 1, close).toLowerCase(Locale.ROOT);
            // A ":regex" suffix is a Spring path-variable constraint, not part of the name.
            int colon = variable.indexOf(':');
            if (colon > 0) {
                variable = variable.substring(0, colon);
            }
            result.append(PATH_VARIABLE_VALUES.getOrDefault(variable, DEFAULT_PATH_VALUE));
            index = close + 1;
        }
        String path0 = result.toString();
        return path0.startsWith("/") ? path0 : "/" + path0;
    }

    private static List<String> captureFor(Scenario.Dimension dimension) {
        return switch (dimension) {
            case SECURITY_AUTHORIZATION ->
                    List.of("status_code", "www_authenticate", "security_headers", "header_names");
            case SERIALIZATION -> List.of("content_type", "body_field_paths", "body_shape");
            case CONFIGURATION_BINDING -> List.of("status_code", "body_field_paths");
            case SPRING_CONTEXT -> List.of("status_code", "body_field_paths");
            default -> List.of("status_code", "content_type", "body_shape");
        };
    }

    private static String defaultPathFor(Scenario.Dimension dimension) {
        return switch (dimension) {
            case CONFIGURATION_BINDING -> "/actuator/configprops";
            case SPRING_CONTEXT -> "/actuator/beans";
            case SECURITY_AUTHORIZATION -> "/actuator/health";
            default -> "/actuator/health";
        };
    }

    private static Scenario.Dimension parseDimension(String raw) {
        try {
            return Scenario.Dimension.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // CONTEXT_CAPABILITY is the differential name for what scenarios call SPRING_CONTEXT.
            return "CONTEXT_CAPABILITY".equals(raw) ? Scenario.Dimension.SPRING_CONTEXT : null;
        }
    }

    /** The differential dimension name a scenario dimension maps onto. */
    public static String differentialDimension(Scenario.Dimension dimension) {
        return dimension == Scenario.Dimension.SPRING_CONTEXT ? "CONTEXT_CAPABILITY" : dimension.name();
    }
}
