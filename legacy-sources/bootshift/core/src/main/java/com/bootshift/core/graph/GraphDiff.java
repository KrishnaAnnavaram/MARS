package com.bootshift.core.graph;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static/semantic graph difference between the last known-good graph and the current graph
 * (Agent 14).
 *
 * <p>This diff is computed <em>before</em> expensive test/runtime/differential work so an
 * out-of-scope structural change blocks early rather than after an hour of validation.
 */
public final class GraphDiff {

    public record NodeChange(String nodeId, NodeType type, String name, String fileId, String detail) {
    }

    public record EdgeChange(String from, EdgeType type, String to, String view) {
    }

    private final List<NodeChange> nodesAdded = new ArrayList<>();
    private final List<NodeChange> nodesRemoved = new ArrayList<>();
    private final List<NodeChange> nodesChanged = new ArrayList<>();
    private final List<EdgeChange> edgesAdded = new ArrayList<>();
    private final List<EdgeChange> edgesRemoved = new ArrayList<>();
    private final Set<String> changedSymbolIds = new LinkedHashSet<>();
    private final Set<String> changedFileIds = new LinkedHashSet<>();
    private final Map<String, Integer> countsByCategory = new LinkedHashMap<>();
    private String beforeLabel;
    private String afterLabel;
    private String beforeHash;
    private String afterHash;
    private boolean partial;

    public static GraphDiff between(ApplicationGraph before, ApplicationGraph after) {
        GraphDiff diff = new GraphDiff();
        diff.beforeLabel = before.label();
        diff.afterLabel = after.label();
        diff.beforeHash = before.structuralHash();
        diff.afterHash = after.structuralHash();
        diff.partial = "PARTIAL".equals(before.status()) || "PARTIAL".equals(after.status());

        Map<String, GraphNode> beforeNodes = new LinkedHashMap<>();
        before.nodes().forEach(n -> beforeNodes.put(n.getId(), n));
        Map<String, GraphNode> afterNodes = new LinkedHashMap<>();
        after.nodes().forEach(n -> afterNodes.put(n.getId(), n));

        for (GraphNode node : afterNodes.values()) {
            GraphNode previous = beforeNodes.get(node.getId());
            if (previous == null) {
                diff.nodesAdded.add(describe(node, "ADDED"));
                diff.track(node);
            } else if (!previous.fingerprint().equals(node.fingerprint())) {
                diff.nodesChanged.add(describe(node,
                        "fingerprint " + previous.fingerprint() + " -> " + node.fingerprint()));
                diff.track(node);
            }
        }
        for (GraphNode node : beforeNodes.values()) {
            if (!afterNodes.containsKey(node.getId())) {
                diff.nodesRemoved.add(describe(node, "REMOVED"));
                diff.track(node);
            }
        }

        Set<String> beforeEdges = new LinkedHashSet<>();
        Map<String, GraphEdge> beforeEdgeIndex = new LinkedHashMap<>();
        before.edges().forEach(e -> {
            beforeEdges.add(e.key());
            beforeEdgeIndex.put(e.key(), e);
        });
        Set<String> afterEdges = new LinkedHashSet<>();
        Map<String, GraphEdge> afterEdgeIndex = new LinkedHashMap<>();
        after.edges().forEach(e -> {
            afterEdges.add(e.key());
            afterEdgeIndex.put(e.key(), e);
        });

        for (String key : afterEdges) {
            if (!beforeEdges.contains(key)) {
                GraphEdge edge = afterEdgeIndex.get(key);
                diff.edgesAdded.add(new EdgeChange(edge.getFrom(), edge.getType(), edge.getTo(), edge.getView()));
                after.node(edge.getFrom()).ifPresent(diff::track);
            }
        }
        for (String key : beforeEdges) {
            if (!afterEdges.contains(key)) {
                GraphEdge edge = beforeEdgeIndex.get(key);
                diff.edgesRemoved.add(new EdgeChange(edge.getFrom(), edge.getType(), edge.getTo(), edge.getView()));
                before.node(edge.getFrom()).ifPresent(diff::track);
            }
        }

        diff.countsByCategory.put("nodes_added", diff.nodesAdded.size());
        diff.countsByCategory.put("nodes_removed", diff.nodesRemoved.size());
        diff.countsByCategory.put("nodes_changed", diff.nodesChanged.size());
        diff.countsByCategory.put("edges_added", diff.edgesAdded.size());
        diff.countsByCategory.put("edges_removed", diff.edgesRemoved.size());
        return diff;
    }

    private static NodeChange describe(GraphNode node, String detail) {
        return new NodeChange(node.getId(), node.getType(),
                node.getFqn() == null ? node.getName() : node.getFqn(), node.getFileId(), detail);
    }

    private void track(GraphNode node) {
        if (node.getSymbolId() != null) {
            changedSymbolIds.add(node.getSymbolId());
        }
        if (node.getFileId() != null) {
            changedFileIds.add(node.getFileId());
        }
    }

    public boolean isEmpty() {
        return nodesAdded.isEmpty() && nodesRemoved.isEmpty() && nodesChanged.isEmpty()
                && edgesAdded.isEmpty() && edgesRemoved.isEmpty();
    }

    public Set<String> changedFileIds() {
        return changedFileIds;
    }

    public Set<String> changedSymbolIds() {
        return changedSymbolIds;
    }

    public List<NodeChange> nodesAdded() {
        return nodesAdded;
    }

    public List<NodeChange> nodesRemoved() {
        return nodesRemoved;
    }

    public List<NodeChange> nodesChanged() {
        return nodesChanged;
    }

    public List<EdgeChange> edgesAdded() {
        return edgesAdded;
    }

    public List<EdgeChange> edgesRemoved() {
        return edgesRemoved;
    }

    public boolean partial() {
        return partial;
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("before_label", beforeLabel);
        node.put("after_label", afterLabel);
        node.put("before_structural_hash", beforeHash);
        node.put("after_structural_hash", afterHash);
        node.put("partial", partial);
        node.set("counts", Json.toTree(countsByCategory));
        node.set("nodes_added", Json.toTree(nodesAdded));
        node.set("nodes_removed", Json.toTree(nodesRemoved));
        node.set("nodes_changed", Json.toTree(nodesChanged));
        node.set("edges_added", Json.toTree(edgesAdded));
        node.set("edges_removed", Json.toTree(edgesRemoved));
        node.set("changed_file_ids", Json.toTree(changedFileIds));
        node.set("changed_symbol_ids", Json.toTree(changedSymbolIds));
        return node;
    }
}
