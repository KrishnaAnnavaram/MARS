package com.mars.harness.controlcenter.api.dto;

import java.util.List;
import java.util.Map;

/** Canonical graph views, filtered server-side so the browser never receives the whole graph blindly. */
public final class GraphDtos {

    private GraphDtos() {
    }

    /**
     * @param origin    BASE (Bootshift's application graph) or OVERLAY (the kernel's identity overlay)
     * @param highlights FINDING, CHANGED, BLAST_RADIUS, MIGRATION_ISSUE markers derived from run artifacts
     */
    public record Node(String id, String type, String name, String origin, String identityId, String fileId, String fqn,
                       Map<String, Object> properties, List<String> highlights, int degree) {
    }

    public record Edge(String from, String to, String type, String origin) {
    }

    /**
     * @param truncated true when the filter matched more nodes than {@code limit}; the view is partial
     */
    public record GraphView(String graphSource, int totalNodes, int totalEdges, Map<String, Integer> nodeTypes,
                            Map<String, Integer> edgeTypes, List<Node> nodes, List<Edge> edges, boolean truncated,
                            int limit, String focus, int depth, List<String> coverageNotes, boolean available) {
    }
}
