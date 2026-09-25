package com.mars.harness.kernel.engine.graph;

import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.GraphDiff;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.graph.CanonicalGraph;
import com.mars.harness.kernel.core.identity.IdentityRegistry;
import com.mars.harness.kernel.core.identity.ModuleRecord;
import com.mars.harness.kernel.core.identity.ProgramUnitRecord;
import com.mars.harness.kernel.core.identity.StatementRecord;
import com.mars.harness.kernel.core.identity.SymbolRecord;
import com.mars.harness.kernel.core.identity.TrackedIdentity;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the canonical graph: Bootshift's application graph plus the identity and finding overlay
 * (see {@link CanonicalGraph}).
 */
public final class CanonicalGraphBuilder {

    private CanonicalGraphBuilder() {
    }

    public static CanonicalGraph build(String runId, String repositoryId, ApplicationGraph base, IdentityRegistry identity,
                                       FileRegistry files, List<Finding> findings) {
        CanonicalGraph graph = CanonicalGraph.over(runId, base);
        String repoNode = "REPOSITORY:" + repositoryId;
        graph.node(repoNode, "Repository", repositoryId, repositoryId, null, Map.of());

        // modules and their files
        for (ModuleRecord module : identity.modules.values()) {
            String moduleNode = "MODULE:" + module.moduleId;
            graph.node(moduleNode, "Module", module.name, module.moduleId, null,
                    Map.of("path", module.path, "build_file", String.valueOf(module.buildFile)));
            graph.edge(repoNode, "CONTAINS", moduleNode, Map.of());
        }
        if (files != null) {
            for (FileRecord file : files.all()) {
                String fileNode = "FILE:" + file.getFileId();
                if (base == null || base.node(fileNode).isEmpty()) {
                    graph.node(fileNode, "File", file.getCurrentPath(), file.getFileId(), file.getFileId(),
                            Map.of("status", file.getStatus().name()));
                }
                identity.moduleForPath(file.getCurrentPath()).ifPresent(m ->
                        graph.edge("MODULE:" + m.moduleId, "CONTAINS", fileNode, Map.of()));
            }
        }

        // identity bindings: Bootshift TYPE and member nodes to persistent PROGRAM_UNIT_ID / SYMBOL_ID
        Map<String, ProgramUnitRecord> unitsByFqn = identity.programUnits.values().stream()
                .filter(TrackedIdentity::active)
                .collect(Collectors.toMap(u -> u.fqn, u -> u, (a, b) -> a, LinkedHashMap::new));
        if (base != null) {
            for (GraphNode node : base.nodes()) {
                if (node.getType() != null && node.getType().isTypeDeclaration() && node.getFqn() != null) {
                    ProgramUnitRecord unit = unitsByFqn.get(node.getFqn());
                    if (unit != null) {
                        graph.identityBindings.put(node.getId(), unit.programUnitId);
                    }
                } else if (isMember(node) && node.getId().startsWith("TYPE:")) {
                    bindMember(graph, identity, unitsByFqn, node);
                }
            }
        }
        // units and symbols not present in the base graph (for example, when Bootshift's graph was
        // unavailable) still appear, so coverage never depends on a single builder
        for (ProgramUnitRecord unit : identity.programUnits.values()) {
            if (!unit.active() || graph.baseNodeFor(unit.programUnitId).isPresent()) {
                continue;
            }
            graph.node("PROGRAM_UNIT:" + unit.programUnitId, "ProgramUnit", unit.fqn, unit.programUnitId, unit.fileId,
                    Map.of("kind", unit.kind));
            graph.edge("FILE:" + unit.fileId, "DECLARES", "PROGRAM_UNIT:" + unit.programUnitId, Map.of());
        }
        for (SymbolRecord symbol : identity.symbols.values()) {
            if (!symbol.active() || graph.baseNodeFor(symbol.symbolId).isPresent()) {
                continue;
            }
            String node = "SYMBOL:" + symbol.symbolId;
            graph.node(node, "ENDPOINT".equals(symbol.kind) ? "Endpoint" : kindName(symbol.kind), symbol.signature,
                    symbol.symbolId, symbol.fileId, Map.of("fqn", String.valueOf(symbol.fqn)));
            String parent = symbol.parentSymbolId != null ? nodeFor(graph, symbol.parentSymbolId)
                    : nodeFor(graph, symbol.programUnitId);
            graph.edge(parent, "ENDPOINT".equals(symbol.kind) ? "EXPOSES" : "DECLARES", node, Map.of());
        }
        // statements
        for (StatementRecord statement : identity.statements.values()) {
            if (!statement.active()) {
                continue;
            }
            String node = "STATEMENT:" + statement.statementId;
            graph.node(node, "Statement", statement.nodeKind, statement.statementId, statement.fileId,
                    Map.of("line", statement.currentLocation == null ? 0 : statement.currentLocation.lineStart(),
                            "fingerprint", String.valueOf(statement.currentFingerprint)));
            String parent = statement.parentStatementId != null ? "STATEMENT:" + statement.parentStatementId
                    : nodeFor(graph, statement.parentSymbolId);
            graph.edge(parent, statement.parentStatementId != null ? "CONTAINS" : "DECLARES", node,
                    Map.of("slot", String.valueOf(statement.slot)));
        }
        attachFindings(graph, findings);
        graph.coverageNotes.add("identity: " + identity.coverage());
        return graph;
    }

    public static void attachFindings(CanonicalGraph graph, List<Finding> findings) {
        graph.overlayNodes.removeIf(n -> "Finding".equals(n.type()));
        graph.overlayEdges.removeIf(e -> e.from().startsWith("FINDING:"));
        for (Finding finding : findings) {
            String node = "FINDING:" + finding.findingId();
            graph.node(node, "Finding", finding.title(), finding.findingId(), finding.fileId(),
                    Map.of("cwe", String.join(",", finding.cwe()), "severity", finding.severity().name(),
                            "anchor", String.valueOf(finding.anchorQuality())));
            String target = finding.statementId() != null ? "STATEMENT:" + finding.statementId()
                    : finding.symbolId() != null ? nodeFor(graph, finding.symbolId())
                    : finding.fileId() != null ? "FILE:" + finding.fileId() : null;
            if (target != null) {
                graph.edge(node, "AFFECTS", target, Map.of());
            }
            for (String ev : finding.evidenceRefs()) {
                graph.edge(node, "EVIDENCED_BY", "EVIDENCE:" + ev, Map.of());
            }
        }
    }

    private static void bindMember(CanonicalGraph graph, IdentityRegistry identity, Map<String, ProgramUnitRecord> unitsByFqn,
                                   GraphNode node) {
        String id = node.getId().substring("TYPE:".length());
        int hash = id.indexOf('#');
        if (hash < 0) {
            return;
        }
        ProgramUnitRecord unit = unitsByFqn.get(id.substring(0, hash));
        if (unit == null) {
            return;
        }
        String kind = node.getType() == NodeType.FIELD ? "FIELD" : node.getType() == NodeType.CONSTRUCTOR ? "CONSTRUCTOR" : "METHOD";
        String name = "CONSTRUCTOR".equals(kind) ? "<init>" : node.getName();
        Optional<SymbolRecord> symbol = identity.activeSymbolsInUnit(unit.programUnitId).stream()
                .filter(s -> kind.equals(s.kind) && Objects.equals(name, s.name))
                .filter(s -> node.getLineStart() == null || s.currentLocation == null
                        || s.currentLocation.overlaps(node.getLineStart(), node.getLineEnd() == null ? node.getLineStart() : node.getLineEnd()))
                .findFirst();
        symbol.ifPresent(s -> graph.identityBindings.put(node.getId(), s.symbolId));
    }

    private static boolean isMember(GraphNode node) {
        return node.getType() == NodeType.METHOD || node.getType() == NodeType.CONSTRUCTOR || node.getType() == NodeType.FIELD;
    }

    private static String nodeFor(CanonicalGraph graph, String identityId) {
        if (identityId == null) {
            return "UNKNOWN";
        }
        return graph.baseNodeFor(identityId).orElseGet(() -> identityId.startsWith("PU-") ? "PROGRAM_UNIT:" + identityId
                : "SYMBOL:" + identityId);
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "METHOD" -> "Method";
            case "CONSTRUCTOR" -> "Constructor";
            case "FIELD" -> "Field";
            default -> "Symbol";
        };
    }

    /** Structural diff between two canonical graphs: Bootshift's GraphDiff plus statement-level overlay changes. */
    public static Map<String, Object> diff(CanonicalGraph before, CanonicalGraph after) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (before.baseGraph().isPresent() && after.baseGraph().isPresent()) {
            GraphDiff diff = GraphDiff.between(before.baseGraph().get(), after.baseGraph().get());
            result.put("base_nodes_added", diff.nodesAdded().size());
            result.put("base_nodes_removed", diff.nodesRemoved().size());
            result.put("base_nodes_changed", diff.nodesChanged().size());
            result.put("base_edges_added", diff.edgesAdded().size());
            result.put("base_edges_removed", diff.edgesRemoved().size());
            result.put("changed_file_ids", new ArrayList<>(diff.changedFileIds()));
            result.put("base_diff", diff.toNode());
        } else {
            result.put("base_diff", "NOT_COMPARED: a base graph is missing on one side");
        }
        Set<String> beforeStatements = before.overlayNodes.stream().filter(n -> "Statement".equals(n.type()))
                .map(CanonicalGraph.OverlayNode::id).collect(Collectors.toSet());
        Set<String> afterStatements = after.overlayNodes.stream().filter(n -> "Statement".equals(n.type()))
                .map(CanonicalGraph.OverlayNode::id).collect(Collectors.toSet());
        result.put("statements_added", afterStatements.stream().filter(s -> !beforeStatements.contains(s)).sorted().toList());
        result.put("statements_removed", beforeStatements.stream().filter(s -> !afterStatements.contains(s)).sorted().toList());
        return result;
    }
}
