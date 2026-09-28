package com.mars.harness.controlcenter.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.controlcenter.api.dto.GraphDtos;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.ports.security.RemediationCapability;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The canonical graph as an engineering tool: Bootshift's application graph (BASE) together with
 * the kernel's identity overlay (OVERLAY), queried server-side.
 *
 * <p>Graphs can have thousands of nodes, so the browser always receives a bounded view: a focus
 * neighbourhood, a search, a highlight set or a type filter, capped at {@code limit} nodes and
 * flagged {@code truncated} when capped. The parsed graph is cached per run until the artifact
 * changes on disk.
 */
@Component
public class GraphService {

    public static final int DEFAULT_LIMIT = 300;
    public static final int MAX_LIMIT = 2000;

    public record Query(String focus, int depth, String search, Set<String> nodeTypes, Set<String> edgeTypes,
                        String highlight, int limit) {
    }

    private record Parsed(String stamp, String graphSource, Map<String, GraphDtos.Node> nodes, List<GraphDtos.Edge> edges,
                          Map<String, Set<String>> adjacency, List<String> coverageNotes) {
    }

    private final ConcurrentHashMap<String, Parsed> cache = new ConcurrentHashMap<>();

    public GraphDtos.GraphView view(RunReader run, Query query) {
        Path file = run.layout().canonicalGraph();
        if (!Files.isRegularFile(file)) {
            return new GraphDtos.GraphView(null, 0, 0, Map.of(), Map.of(), List.of(), List.of(), false, query.limit(),
                    query.focus(), query.depth(), List.of("The canonical graph is built after identity is sealed"), false);
        }
        Parsed graph = parsed(run, file);
        Map<String, List<String>> highlights = highlights(run, graph);
        int limit = Math.max(1, Math.min(query.limit() <= 0 ? DEFAULT_LIMIT : query.limit(), MAX_LIMIT));

        Set<String> seeds = new LinkedHashSet<>();
        if (query.focus() != null && graph.nodes().containsKey(query.focus())) {
            seeds.add(query.focus());
        }
        if (query.search() != null && !query.search().isBlank()) {
            String q = query.search().toLowerCase(Locale.ROOT);
            graph.nodes().values().stream().filter(n -> contains(n.id(), q) || contains(n.name(), q) || contains(n.fqn(), q)
                    || contains(n.identityId(), q)).forEach(n -> seeds.add(n.id()));
        }
        if (query.highlight() != null && !query.highlight().isBlank()) {
            highlights.forEach((id, marks) -> {
                if (marks.contains(query.highlight().toUpperCase(Locale.ROOT))) {
                    seeds.add(id);
                }
            });
        }
        boolean scoped = !seeds.isEmpty() || query.focus() != null || query.search() != null && !query.search().isBlank()
                || query.highlight() != null && !query.highlight().isBlank();
        Set<String> selected = new LinkedHashSet<>();
        if (scoped) {
            selected.addAll(neighbourhood(graph, seeds, Math.max(0, Math.min(query.depth(), 4)), query.edgeTypes()));
        } else {
            selected.addAll(graph.nodes().keySet());
        }
        if (!query.nodeTypes().isEmpty()) {
            selected.removeIf(id -> !seeds.contains(id) && !query.nodeTypes().contains(graph.nodes().get(id).type()));
        }
        boolean truncated = selected.size() > limit;
        List<String> ordered = new ArrayList<>(selected);
        if (truncated) {
            // keep what the query asked for first, then highlighted nodes, then the most connected ones
            ordered.sort(Comparator.<String>comparingInt(id -> seeds.contains(id) ? 0 : 1)
                    .thenComparingInt(id -> highlights.containsKey(id) ? 0 : 1)
                    .thenComparing(Comparator.<String>comparingInt(id -> graph.nodes().get(id).degree()).reversed()));
            ordered = ordered.subList(0, limit);
        }
        Set<String> kept = new HashSet<>(ordered);
        List<GraphDtos.Node> nodes = ordered.stream().map(id -> withHighlights(graph.nodes().get(id), highlights.get(id)))
                .toList();
        List<GraphDtos.Edge> edges = graph.edges().stream().filter(e -> kept.contains(e.from()) && kept.contains(e.to()))
                .filter(e -> query.edgeTypes().isEmpty() || query.edgeTypes().contains(e.type())).toList();
        Map<String, Integer> nodeTypes = new TreeMap<>();
        graph.nodes().values().forEach(n -> nodeTypes.merge(n.type(), 1, Integer::sum));
        Map<String, Integer> edgeTypes = new TreeMap<>();
        graph.edges().forEach(e -> edgeTypes.merge(e.type(), 1, Integer::sum));
        return new GraphDtos.GraphView(graph.graphSource(), graph.nodes().size(), graph.edges().size(), nodeTypes, edgeTypes,
                nodes, edges, truncated, limit, query.focus(), query.depth(), graph.coverageNotes(), true);
    }

    private Parsed parsed(RunReader run, Path file) {
        String stamp;
        try {
            stamp = Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Parsed cached = cache.get(run.runId());
        if (cached != null && cached.stamp().equals(stamp)) {
            return cached;
        }
        Parsed fresh = parse(stamp, KernelJson.read(file));
        cache.put(run.runId(), fresh);
        return fresh;
    }

    static Parsed parse(String stamp, JsonNode g) {
        Map<String, GraphDtos.Node> nodes = new LinkedHashMap<>();
        JsonNode base = g.path("base");
        Map<String, String> bindings = new HashMap<>();
        g.path("identity_bindings").fields().forEachRemaining(e -> bindings.put(e.getKey(), e.getValue().asText()));
        for (JsonNode n : base.path("nodes")) {
            String id = n.path("id").asText();
            Map<String, Object> props = new LinkedHashMap<>();
            n.path("properties").fields().forEachRemaining(e -> props.put(e.getKey(), e.getValue().isValueNode()
                    ? e.getValue().asText() : e.getValue().toString()));
            if (n.hasNonNull("module")) {
                props.put("module", n.path("module").asText());
            }
            if (n.hasNonNull("attribution")) {
                props.put("attribution", n.path("attribution").asText());
            }
            if (n.hasNonNull("line_start")) {
                props.put("line_start", n.path("line_start").asInt());
            }
            nodes.put(id, new GraphDtos.Node(id, n.path("type").asText(), n.path("name").asText(null), "BASE",
                    bindings.get(id), n.path("file_id").asText(null), n.path("fqn").asText(null), props, List.of(), 0));
        }
        for (JsonNode n : g.path("overlay_nodes")) {
            String id = n.path("id").asText();
            Map<String, Object> props = new LinkedHashMap<>();
            n.path("properties").fields().forEachRemaining(e -> props.put(e.getKey(), e.getValue().asText()));
            nodes.put(id, new GraphDtos.Node(id, n.path("type").asText().toUpperCase(Locale.ROOT), n.path("name").asText(null),
                    "OVERLAY", n.path("identity_id").asText(null), n.path("file_id").asText(null),
                    props.get("fqn") == null ? null : String.valueOf(props.get("fqn")), props, List.of(), 0));
        }
        List<GraphDtos.Edge> edges = new ArrayList<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        Map<String, Integer> degree = new HashMap<>();
        for (JsonNode e : base.path("edges")) {
            add(edges, adjacency, degree, nodes, e.path("from").asText(), e.path("to").asText(), e.path("type").asText(), "BASE");
        }
        for (JsonNode e : g.path("overlay_edges")) {
            add(edges, adjacency, degree, nodes, e.path("from").asText(), e.path("to").asText(), e.path("type").asText(),
                    "OVERLAY");
        }
        Map<String, GraphDtos.Node> withDegree = new LinkedHashMap<>();
        nodes.forEach((id, n) -> withDegree.put(id, new GraphDtos.Node(n.id(), n.type(), n.name(), n.origin(), n.identityId(),
                n.fileId(), n.fqn(), n.properties(), n.highlights(), degree.getOrDefault(id, 0))));
        List<String> notes = new ArrayList<>();
        g.path("coverage_notes").forEach(c -> notes.add(c.asText()));
        return new Parsed(stamp, g.path("graph_source").asText(null), withDegree, edges, adjacency, notes);
    }

    private static void add(List<GraphDtos.Edge> edges, Map<String, Set<String>> adjacency, Map<String, Integer> degree,
                            Map<String, GraphDtos.Node> nodes, String from, String to, String type, String origin) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
            return; // e.g. EVIDENCED_BY edges point at evidence records, which are not graph nodes
        }
        edges.add(new GraphDtos.Edge(from, to, type, origin));
        adjacency.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to + "|" + type);
        adjacency.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from + "|" + type);
        degree.merge(from, 1, Integer::sum);
        degree.merge(to, 1, Integer::sum);
    }

    private static Set<String> neighbourhood(Parsed graph, Set<String> seeds, int depth, Set<String> edgeTypes) {
        Set<String> seen = new LinkedHashSet<>(seeds);
        Deque<String[]> queue = new ArrayDeque<>();
        seeds.forEach(s -> queue.add(new String[]{s, "0"}));
        while (!queue.isEmpty()) {
            String[] item = queue.poll();
            int d = Integer.parseInt(item[1]);
            if (d >= depth) {
                continue;
            }
            for (String neighbour : graph.adjacency().getOrDefault(item[0], Set.of())) {
                int bar = neighbour.lastIndexOf('|');
                String id = neighbour.substring(0, bar);
                String type = neighbour.substring(bar + 1);
                if (!edgeTypes.isEmpty() && !edgeTypes.contains(type)) {
                    continue;
                }
                if (seen.add(id)) {
                    queue.add(new String[]{id, String.valueOf(d + 1)});
                }
            }
        }
        return seen;
    }

    /** FINDING, CHANGED, BLAST_RADIUS and MIGRATION_ISSUE markers, from the run's own artifacts. */
    private static Map<String, List<String>> highlights(RunReader run, Parsed graph) {
        Map<String, Set<String>> marks = new HashMap<>();
        Map<String, List<String>> byIdentity = new HashMap<>();
        Map<String, List<String>> byFile = new HashMap<>();
        graph.nodes().values().forEach(n -> {
            if (n.identityId() != null) {
                byIdentity.computeIfAbsent(n.identityId(), k -> new ArrayList<>()).add(n.id());
            }
            if (n.fileId() != null && ("FILE".equals(n.type()) || n.id().startsWith("FILE:"))) {
                byFile.computeIfAbsent(n.fileId(), k -> new ArrayList<>()).add(n.id());
            }
        });
        graph.nodes().keySet().stream().filter(id -> id.startsWith("FILE:")).forEach(id ->
                byFile.computeIfAbsent(id.substring(5), k -> new ArrayList<>()).add(id));
        for (Finding f : run.findings()) {
            mark(marks, "FINDING:" + f.findingId(), "FINDING");
            for (String id : new String[]{f.statementId(), f.symbolId()}) {
                if (id != null) {
                    byIdentity.getOrDefault(id, List.of()).forEach(n -> mark(marks, n, "FINDING"));
                }
            }
        }
        run.securityDiscovery().ifPresent(d -> {
            for (RemediationCapability.BlastRadius b : d.blastRadii()) {
                b.affectedSymbolIds().forEach(s -> byIdentity.getOrDefault(s, List.of()).forEach(n -> mark(marks, n,
                        "BLAST_RADIUS")));
            }
        });
        for (LineageLedger.Entry e : run.lineage()) {
            if (!"CHANGE".equals(e.kind())) {
                continue;
            }
            if (e.fileId() != null) {
                byFile.getOrDefault(e.fileId(), List.of()).forEach(n -> mark(marks, n, "CHANGED"));
            }
            for (List<String> ids : java.util.Arrays.asList(e.symbolIds(), e.statementIdsChanged(), e.statementIdsCreated())) {
                if (ids != null) {
                    ids.forEach(s -> byIdentity.getOrDefault(s, List.of()).forEach(n -> mark(marks, n, "CHANGED")));
                }
            }
        }
        run.migrationAssessment().ifPresent(a -> {
            for (MigrationAssessment.MigrationIssue i : a.issues()) {
                for (String id : new String[]{i.statementId(), i.symbolId()}) {
                    if (id != null) {
                        byIdentity.getOrDefault(id, List.of()).forEach(n -> mark(marks, n, "MIGRATION_ISSUE"));
                    }
                }
                if (i.fileId() != null) {
                    byFile.getOrDefault(i.fileId(), List.of()).forEach(n -> mark(marks, n, "MIGRATION_ISSUE"));
                }
            }
        });
        Map<String, List<String>> result = new HashMap<>();
        marks.forEach((k, v) -> result.put(k, List.copyOf(v)));
        return result;
    }

    private static void mark(Map<String, Set<String>> marks, String node, String mark) {
        marks.computeIfAbsent(node, k -> new LinkedHashSet<>()).add(mark);
    }

    private static GraphDtos.Node withHighlights(GraphDtos.Node n, List<String> marks) {
        return marks == null ? n : new GraphDtos.Node(n.id(), n.type(), n.name(), n.origin(), n.identityId(), n.fileId(),
                n.fqn(), n.properties(), marks, n.degree());
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }
}
