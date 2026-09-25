package com.mars.harness.kernel.core.graph;

import com.bootshift.core.graph.ApplicationGraph;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The canonical application graph (spec §8).
 *
 * <p>Bootshift's {@link ApplicationGraph} is the structural core: files, types, members, endpoints,
 * dependencies, configuration and persistence, with their semantic edges. This class does not
 * build a second graph beside it. It adds an overlay in the same artifact:
 *
 * <ul>
 *   <li><b>identity bindings</b>: each Bootshift TYPE or member node to its persistent
 *       PROGRAM_UNIT_ID or SYMBOL_ID (Bootshift's own symbol IDs are graph-build-scoped)</li>
 *   <li><b>overlay nodes</b> for kinds Bootshift does not model: Repository, Module (with
 *       MODULE_ID), Statement and Finding</li>
 *   <li><b>overlay edges</b>: CONTAINS, DECLARES (symbol to statement), AFFECTS (finding to
 *       anchor), CHANGED_BY, EVIDENCED_BY</li>
 * </ul>
 *
 * <p>A Neo4j projection may be derived from this artifact, but it is never a second source of
 * truth. If Bootshift's graph could not be built, {@code graphSource} says so
 * ({@code IDENTITY_ONLY}), and coverage is reported rather than implied.
 */
public final class CanonicalGraph {

    public static final String SOURCE_BOOTSHIFT = "BOOTSHIFT_APPLICATION_GRAPH";
    public static final String SOURCE_IDENTITY_ONLY = "IDENTITY_ONLY";

    public record OverlayNode(String id, String type, String name, String identityId, String fileId,
                              Map<String, Object> properties) {
        public OverlayNode {
            properties = clean(properties);
        }
    }

    public record OverlayEdge(String from, String type, String to, Map<String, Object> properties) {
        public OverlayEdge {
            properties = clean(properties);
        }
    }

    private static Map<String, Object> clean(Map<String, Object> properties) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (properties != null) {
            properties.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, v);
                }
            });
        }
        return java.util.Collections.unmodifiableMap(copy);
    }

    public String runId;
    public String graphSource;
    public String baseStructuralHash;
    public String baseContentHash;
    public List<String> coverageNotes = new ArrayList<>();
    /** Bootshift graph node ID to persistent PROGRAM_UNIT_ID or SYMBOL_ID. */
    public Map<String, String> identityBindings = new LinkedHashMap<>();
    public List<OverlayNode> overlayNodes = new ArrayList<>();
    public List<OverlayEdge> overlayEdges = new ArrayList<>();
    /** Bootshift's serialized ApplicationGraph, embedded unchanged. */
    public JsonNode base;

    @JsonIgnore
    private transient ApplicationGraph baseGraph;

    public CanonicalGraph() {
    }

    public static CanonicalGraph over(String runId, ApplicationGraph baseGraph) {
        CanonicalGraph graph = new CanonicalGraph();
        graph.runId = runId;
        graph.baseGraph = baseGraph;
        if (baseGraph != null) {
            graph.graphSource = SOURCE_BOOTSHIFT;
            graph.base = baseGraph.toNode();
            graph.baseStructuralHash = baseGraph.structuralHash();
            graph.baseContentHash = baseGraph.contentHash();
        } else {
            graph.graphSource = SOURCE_IDENTITY_ONLY;
            graph.coverageNotes.add("Bootshift application graph unavailable; structural edges (calls, "
                    + "injection, endpoints, persistence) are not represented");
        }
        return graph;
    }

    @JsonIgnore
    public Optional<ApplicationGraph> baseGraph() {
        if (baseGraph == null && base != null && !base.isNull() && !base.isMissingNode()) {
            baseGraph = ApplicationGraph.fromNode(base);
        }
        return Optional.ofNullable(baseGraph);
    }

    public void node(String id, String type, String name, String identityId, String fileId,
                     Map<String, Object> properties) {
        overlayNodes.removeIf(n -> n.id().equals(id));
        overlayNodes.add(new OverlayNode(id, type, name, identityId, fileId, properties));
    }

    public void edge(String from, String type, String to, Map<String, Object> properties) {
        OverlayEdge edge = new OverlayEdge(from, type, to, properties);
        boolean exists = overlayEdges.stream().anyMatch(e -> e.from().equals(from) && e.type().equals(type)
                && e.to().equals(to));
        if (!exists) {
            overlayEdges.add(edge);
        }
    }

    public List<OverlayEdge> overlayOutgoing(String id) {
        return overlayEdges.stream().filter(e -> e.from().equals(id)).toList();
    }

    public List<OverlayEdge> overlayIncoming(String id) {
        return overlayEdges.stream().filter(e -> e.to().equals(id)).toList();
    }

    /** Bootshift node ID bound to a persistent identity, if the graph contains it. */
    public Optional<String> baseNodeFor(String identityId) {
        return identityBindings.entrySet().stream().filter(e -> e.getValue().equals(identityId))
                .map(Map.Entry::getKey).findFirst();
    }

    @JsonIgnore
    public String contentHash() {
        return KernelJson.hash(this);
    }

    public int nodeCount() {
        return overlayNodes.size() + baseGraph().map(ApplicationGraph::nodeCount).orElse(0);
    }

    public int edgeCount() {
        return overlayEdges.size() + baseGraph().map(ApplicationGraph::edgeCount).orElse(0);
    }
}
