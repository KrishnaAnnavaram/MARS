package com.bootshift.core.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Queryable provenance layer over a finished run (Agent 20).
 *
 * <p>Answers are produced by fixed, validated query intents rather than by generating graph queries
 * from natural language, so an answer is always reproducible.
 */
public final class ProvenanceGraph {

    public enum Kind {
        RUN, MODULE, FILE, SYMBOL, LIBRARY, DOCUMENT, MIGRATION_FACT, IMPACT_FINDING, SCENARIO,
        PATCH, CHANGE_EVENT, BUILD, TEST_CASE, OBSERVATION, DIFFERENCE, DECISION, CLAIM, GAP,
        BLIND_SPOT, EDGE
    }

    public enum Relation {
        EVIDENCED_BY, AUTHORIZES, AFFECTS, REQUIRES_VALIDATION, DISCHARGED_BY, CHANGED_BY,
        PRODUCED, OBSERVED_IN, COMPARED_AS, EXPLAINED_BY, SIGNED_BY, SUPPORTS, NOT_VISIBLE_TO
    }

    public record PNode(String id, Kind kind, String label, Map<String, Object> attributes) {
    }

    public record PEdge(String from, Relation relation, String to, String note) {
    }

    private final Map<String, PNode> nodes = new LinkedHashMap<>();
    private final List<PEdge> edges = new ArrayList<>();
    private final Set<String> edgeKeys = new LinkedHashSet<>();
    private final Map<String, List<PEdge>> out = new LinkedHashMap<>();
    private final Map<String, List<PEdge>> in = new LinkedHashMap<>();

    public PNode node(String id, Kind kind, String label) {
        return nodes.computeIfAbsent(id, k -> new PNode(id, kind, label, new LinkedHashMap<>()));
    }

    public PNode node(String id, Kind kind, String label, Map<String, Object> attributes) {
        PNode existing = nodes.get(id);
        if (existing != null) {
            existing.attributes().putAll(attributes);
            return existing;
        }
        PNode created = new PNode(id, kind, label, new LinkedHashMap<>(attributes));
        nodes.put(id, created);
        return created;
    }

    public boolean link(String from, Relation relation, String to, String note) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
            return false;
        }
        String key = from + "|" + relation + "|" + to;
        if (!edgeKeys.add(key)) {
            return false;
        }
        PEdge edge = new PEdge(from, relation, to, note);
        edges.add(edge);
        out.computeIfAbsent(from, k -> new ArrayList<>()).add(edge);
        in.computeIfAbsent(to, k -> new ArrayList<>()).add(edge);
        return true;
    }

    public List<PEdge> outgoing(String id) {
        return out.getOrDefault(id, List.of());
    }

    public List<PEdge> incoming(String id) {
        return in.getOrDefault(id, List.of());
    }

    public java.util.Optional<PNode> find(String id) {
        return java.util.Optional.ofNullable(nodes.get(id));
    }

    public List<PNode> ofKind(Kind kind) {
        return nodes.values().stream().filter(n -> n.kind() == kind).toList();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    /** Traverses backwards along the relations that constitute an explanation. */
    public List<PEdge> explain(String id, Set<Relation> relations, int maxDepth) {
        List<PEdge> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        walk(id, relations, maxDepth, 0, visited, result);
        return result;
    }

    private void walk(String id, Set<Relation> relations, int maxDepth, int depth,
                      Set<String> visited, List<PEdge> result) {
        if (depth >= maxDepth || !visited.add(id)) {
            return;
        }
        for (PEdge edge : outgoing(id)) {
            if (relations.isEmpty() || relations.contains(edge.relation())) {
                result.add(edge);
                walk(edge.to(), relations, maxDepth, depth + 1, visited, result);
            }
        }
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("node_count", nodes.size());
        node.put("edge_count", edges.size());
        node.set("nodes", Json.toTree(nodes.values()));
        node.set("edges", Json.toTree(edges));
        return node;
    }

    public static ProvenanceGraph fromNode(JsonNode node) {
        ProvenanceGraph graph = new ProvenanceGraph();
        for (JsonNode n : node.path("nodes")) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            n.path("attributes").fields().forEachRemaining(e -> attrs.put(e.getKey(), e.getValue().asText()));
            graph.node(n.path("id").asText(), Kind.valueOf(n.path("kind").asText()),
                    n.path("label").asText(), attrs);
        }
        for (JsonNode e : node.path("edges")) {
            graph.link(e.path("from").asText(), Relation.valueOf(e.path("relation").asText()),
                    e.path("to").asText(), e.path("note").asText(null));
        }
        return graph;
    }
}
