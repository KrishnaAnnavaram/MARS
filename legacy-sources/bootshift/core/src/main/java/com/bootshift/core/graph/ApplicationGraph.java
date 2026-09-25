package com.bootshift.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The canonical application graph.
 *
 * <p>The source of truth is this portable in-memory/JSON structure; a graph database is optional and
 * never required for correctness (spec section 13).
 *
 * <p>Views (module, file, symbol, spring, configuration, persistence, endpoint, test, integration,
 * dependency) are projections over one node/edge set rather than separate graphs, so a blast-radius
 * traversal can cross from a config property to a bean to an endpoint without stitching.
 */
public final class ApplicationGraph {

    /** One hop in an explanation path. */
    public record PathStep(String fromId, EdgeType edge, String toId) {
    }

    /** A reachable node plus the path that explains why it is reachable. */
    public record Reached(String nodeId, int distance, List<PathStep> path) {
    }

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, List<GraphEdge>> outgoing = new LinkedHashMap<>();
    private final Map<String, List<GraphEdge>> incoming = new LinkedHashMap<>();
    private final Set<String> edgeKeys = new LinkedHashSet<>();
    private String label = "G0_BASELINE_STATIC";
    private String status = "COMPLETE";

    public String label() {
        return label;
    }

    public ApplicationGraph label(String value) {
        this.label = value;
        return this;
    }

    public String status() {
        return status;
    }

    /** COMPLETE or PARTIAL. PARTIAL means type attribution could not be fully rebuilt (spec 30). */
    public ApplicationGraph status(String value) {
        this.status = value;
        return this;
    }

    public GraphNode addNode(GraphNode node) {
        GraphNode existing = nodes.get(node.getId());
        if (existing != null) {
            return existing;
        }
        nodes.put(node.getId(), node);
        return node;
    }

    public boolean addEdge(GraphEdge edge) {
        if (!nodes.containsKey(edge.getFrom()) || !nodes.containsKey(edge.getTo())) {
            return false;
        }
        if (!edgeKeys.add(edge.key())) {
            return false;
        }
        edges.add(edge);
        outgoing.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
        incoming.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge);
        return true;
    }

    public Optional<GraphNode> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public List<GraphNode> nodes() {
        return new ArrayList<>(nodes.values());
    }

    public List<GraphEdge> edges() {
        return new ArrayList<>(edges);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public List<GraphNode> nodesOfType(NodeType type) {
        return nodes.values().stream().filter(n -> n.getType() == type).toList();
    }

    public List<GraphNode> nodesMatching(Predicate<GraphNode> predicate) {
        return nodes.values().stream().filter(predicate).toList();
    }

    public List<GraphEdge> outgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    public List<GraphEdge> incoming(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    /** All nodes that carry the given FILE_ID. */
    public List<GraphNode> nodesForFile(String fileId) {
        return nodes.values().stream()
                .filter(n -> fileId.equals(n.getFileId()))
                .toList();
    }

    public Optional<GraphNode> fileNode(String fileId) {
        return nodes.values().stream()
                .filter(n -> n.getType() == NodeType.FILE && fileId.equals(n.getFileId()))
                .findFirst();
    }

    // ---------------------------------------------------------------- traversals

    /** Direct dependencies: what this node points at. */
    public List<String> dependencies(String nodeId) {
        return outgoing(nodeId).stream().map(GraphEdge::getTo).distinct().toList();
    }

    /** Direct dependents: what points at this node. */
    public List<String> dependents(String nodeId) {
        return incoming(nodeId).stream().map(GraphEdge::getFrom).distinct().toList();
    }

    public List<Reached> transitiveDependencies(String nodeId, int maxDepth) {
        return traverse(nodeId, maxDepth, true, e -> true);
    }

    public List<Reached> transitiveDependents(String nodeId, int maxDepth) {
        return traverse(nodeId, maxDepth, false, e -> true);
    }

    /**
     * Blast radius: everything reachable backwards through relationships that can propagate a
     * behavioural change, each with the path that explains its inclusion.
     */
    public List<Reached> blastRadius(String nodeId, int maxDepth) {
        return traverse(nodeId, maxDepth, false, e -> e.getType() != EdgeType.CONTAINS);
    }

    private List<Reached> traverse(String startId, int maxDepth, boolean forward,
                                   Predicate<GraphEdge> edgeFilter) {
        if (!nodes.containsKey(startId)) {
            return List.of();
        }
        Map<String, Reached> seen = new LinkedHashMap<>();
        Deque<Reached> queue = new ArrayDeque<>();
        queue.add(new Reached(startId, 0, List.of()));
        seen.put(startId, queue.peek());
        List<Reached> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            Reached current = queue.poll();
            if (current.distance() >= maxDepth) {
                continue;
            }
            List<GraphEdge> next = forward ? outgoing(current.nodeId()) : incoming(current.nodeId());
            for (GraphEdge edge : next) {
                if (!edgeFilter.test(edge)) {
                    continue;
                }
                String neighbour = forward ? edge.getTo() : edge.getFrom();
                if (seen.containsKey(neighbour)) {
                    continue;
                }
                List<PathStep> path = new ArrayList<>(current.path());
                path.add(new PathStep(edge.getFrom(), edge.getType(), edge.getTo()));
                Reached reached = new Reached(neighbour, current.distance() + 1, List.copyOf(path));
                seen.put(neighbour, reached);
                result.add(reached);
                queue.add(reached);
            }
        }
        result.sort(Comparator.comparingInt(Reached::distance).thenComparing(Reached::nodeId));
        return result;
    }

    /** Typed traversal used by the controller-to-service and service-to-repository queries. */
    public List<Reached> pathsThrough(String startId, List<EdgeType> allowed, int maxDepth) {
        Set<EdgeType> allowedSet = Set.copyOf(allowed);
        return traverse(startId, maxDepth, true, e -> allowedSet.contains(e.getType()));
    }

    public List<GraphNode> callers(String symbolNodeId) {
        return incoming(symbolNodeId).stream()
                .filter(e -> e.getType() == EdgeType.CALLS)
                .map(e -> nodes.get(e.getFrom()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<GraphNode> callees(String symbolNodeId) {
        return outgoing(symbolNodeId).stream()
                .filter(e -> e.getType() == EdgeType.CALLS)
                .map(e -> nodes.get(e.getTo()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<GraphNode> implementors(String interfaceNodeId) {
        return incoming(interfaceNodeId).stream()
                .filter(e -> e.getType() == EdgeType.IMPLEMENTS)
                .map(e -> nodes.get(e.getFrom()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<GraphNode> subclasses(String classNodeId) {
        return incoming(classNodeId).stream()
                .filter(e -> e.getType() == EdgeType.EXTENDS)
                .map(e -> nodes.get(e.getFrom()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<GraphNode> testsCovering(String nodeId) {
        return incoming(nodeId).stream()
                .filter(e -> e.getType() == EdgeType.COVERED_BY_TEST)
                .map(e -> nodes.get(e.getFrom()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // ---------------------------------------------------------------- serialization

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("label", label);
        node.put("status", status);
        node.put("node_count", nodes.size());
        node.put("edge_count", edges.size());
        node.put("structural_hash", structuralHash());
        node.put("content_hash", contentHash());
        node.set("nodes", Json.toTree(nodes.values()));
        node.set("edges", Json.toTree(edges));
        return node;
    }

    /** A view projection: only edges of the given kinds plus their endpoint nodes. */
    public ObjectNode viewNode(String viewName, Set<EdgeType> types) {
        List<GraphEdge> viewEdges = edges.stream().filter(e -> types.contains(e.getType())).toList();
        Set<String> ids = new LinkedHashSet<>();
        viewEdges.forEach(e -> {
            ids.add(e.getFrom());
            ids.add(e.getTo());
        });
        ObjectNode node = Json.obj();
        node.put("view", viewName);
        node.put("node_count", ids.size());
        node.put("edge_count", viewEdges.size());
        node.set("nodes", Json.toTree(ids.stream().map(nodes::get).filter(java.util.Objects::nonNull).toList()));
        node.set("edges", Json.toTree(viewEdges));
        return node;
    }

    /**
     * Order-independent hash over this graph's nodes and edges <em>including their identities</em>.
     *
     * <p>Two graphs built in the same run hash identically when they mean the same thing. Two graphs
     * built in <em>different</em> runs do not, because FILE_IDs - and therefore node ids - are
     * freshly allocated per run. That is deliberate: this hash is what detects identity churn within
     * a run. For comparing one run against another, use {@link #contentHash()}.
     */
    public String structuralHash() {
        List<String> fingerprints = new ArrayList<>();
        nodes.values().forEach(n -> fingerprints.add("N:" + n.fingerprint()));
        edges.forEach(e -> fingerprints.add("E:" + e.key()));
        fingerprints.sort(java.util.Comparator.naturalOrder());
        return Hashing.manifestHash(fingerprints);
    }

    /**
     * Order-independent hash over what the graph <em>says</em>, with every run-scoped identifier
     * removed. Endpoints are named by fully qualified name rather than by node id, so two runs over
     * a byte-identical repository produce the same value and a difference means the application
     * actually changed.
     */
    public String contentHash() {
        List<String> fingerprints = new ArrayList<>();
        nodes.values().forEach(n -> fingerprints.add("N:" + n.contentFingerprint()));
        for (GraphEdge edge : edges) {
            fingerprints.add("E:" + endpointName(edge.getFrom()) + "-[" + edge.getType() + "]->"
                    + endpointName(edge.getTo()));
        }
        fingerprints.sort(java.util.Comparator.naturalOrder());
        return Hashing.manifestHash(fingerprints);
    }

    /**
     * Names an edge endpoint without using its id.
     *
     * <p>An endpoint the graph does not contain keeps its raw id: dropping it would silently merge
     * distinct dangling edges, and a hash that hides a difference is worse than one that shows an
     * uninteresting one.
     */
    private String endpointName(String nodeId) {
        GraphNode node = nodes.get(nodeId);
        if (node == null) {
            return nodeId;
        }
        return node.getType() + ":" + (node.getFqn() == null ? node.getName() : node.getFqn());
    }

    public static ApplicationGraph fromNode(JsonNode node) {
        ApplicationGraph graph = new ApplicationGraph();
        graph.label = node.path("label").asText("UNKNOWN");
        graph.status = node.path("status").asText("COMPLETE");
        for (JsonNode n : node.path("nodes")) {
            graph.addNode(Json.convert(n, GraphNode.class));
        }
        for (JsonNode e : node.path("edges")) {
            graph.addEdge(Json.convert(e, GraphEdge.class));
        }
        return graph;
    }

    public static ApplicationGraph load(Path path) {
        return fromNode(Json.read(path));
    }
}
