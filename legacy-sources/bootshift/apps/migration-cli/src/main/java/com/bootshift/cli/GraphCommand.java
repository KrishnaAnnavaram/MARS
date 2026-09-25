package com.bootshift.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.stage03.ApplicationGraphStage;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code harness graph} builds the graph; the sub-verbs query it.
 *
 * <p>Every blast-radius answer prints the path that explains why a node is included, because an
 * unexplained impact list is not usable evidence.
 */
@CommandLine.Command(name = "graph", description =
        "Agent 03: build and verify the application graph, or query an existing one",
        subcommands = {GraphCommand.FileQuery.class, GraphCommand.BlastRadius.class,
                GraphCommand.SymbolQuery.class})
final class GraphCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new ApplicationGraphStage(), options.context());
    }

    static ApplicationGraph loadGraph(StageContext context) {
        JsonNode node = context.run().output().readLatest("14-graph-diff", "application-graph-current.json");
        if (node == null) {
            node = context.run().output().readLatest("03-graph", "application-graph.json");
        }
        if (node == null) {
            throw HarnessException.refusal(
                    "No application graph has been built. Run: bootshift graph --repo <path>");
        }
        return ApplicationGraph.fromNode(node);
    }

    static FileRegistry loadRegistry(StageContext context) {
        JsonNode node = context.run().output().readLatest("03-graph", "file-registry.json");
        if (node == null) {
            node = context.run().output().readLatest("01-inventory", "file-registry.json");
        }
        if (node == null) {
            throw HarnessException.refusal("No file registry exists. Run: bootshift inventory");
        }
        return FileRegistry.fromNode(node);
    }

    @CommandLine.Command(name = "file", description = "Show graph facts for one FILE_ID")
    static final class FileQuery implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @CommandLine.Parameters(index = "0", description = "FILE_ID or path")
        String fileRef;

        @Override
        public Integer call() {
            StageContext context = options.context();
            ApplicationGraph graph = loadGraph(context);
            FileRegistry registry = loadRegistry(context);
            String fileId = resolveFileId(registry, fileRef);

            List<GraphNode> nodes = graph.nodesForFile(fileId);
            System.out.println();
            System.out.println("  FILE_ID " + fileId);
            registry.byId(fileId).ifPresent(record -> {
                System.out.println("  path      " + record.getCurrentPath());
                System.out.println("  baseline  " + record.getBaselinePath());
                System.out.println("  role      " + record.getRole());
                System.out.println("  sha256    " + record.getCurrentSha256());
                System.out.println("  symbols   " + record.getSymbolIds().size());
            });
            System.out.println("  graph nodes " + nodes.size());
            nodes.stream().limit(40).forEach(n -> System.out.println(
                    "    " + n.getType() + "  " + (n.getFqn() == null ? n.getName() : n.getFqn())));

            graph.fileNode(fileId).ifPresent(node -> {
                System.out.println();
                System.out.println("  direct dependencies: " + graph.dependencies(node.getId()).size());
                System.out.println("  direct dependents:   " + graph.dependents(node.getId()).size());
            });
            System.out.println();
            return 0;
        }
    }

    @CommandLine.Command(name = "blast-radius", description =
            "Show everything a change to this file could reach, with the explaining path")
    static final class BlastRadius implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @CommandLine.Parameters(index = "0", description = "FILE_ID or path")
        String fileRef;

        @CommandLine.Option(names = "--depth", description = "Maximum traversal depth. Default: ${DEFAULT-VALUE}")
        int depth = 5;

        @CommandLine.Option(names = "--limit", description = "Maximum rows to print. Default: ${DEFAULT-VALUE}")
        int limit = 40;

        @Override
        public Integer call() {
            StageContext context = options.context();
            ApplicationGraph graph = loadGraph(context);
            FileRegistry registry = loadRegistry(context);
            String fileId = resolveFileId(registry, fileRef);

            System.out.println();
            System.out.println("  Blast radius for FILE_ID " + fileId + " (depth " + depth + ")");
            registry.byId(fileId).ifPresent(r -> System.out.println("  " + r.getCurrentPath()));
            System.out.println();

            int total = 0;
            for (GraphNode start : graph.nodesForFile(fileId)) {
                List<ApplicationGraph.Reached> reached = graph.blastRadius(start.getId(), depth);
                for (ApplicationGraph.Reached hit : reached) {
                    if (total++ >= limit) {
                        break;
                    }
                    GraphNode node = graph.node(hit.nodeId()).orElse(null);
                    String label = node == null ? hit.nodeId()
                            : node.getType() + " " + (node.getFqn() == null ? node.getName() : node.getFqn());
                    System.out.println("    [" + hit.distance() + "] " + label);
                    System.out.println("        why: " + renderPath(hit.path()));
                }
            }
            if (total == 0) {
                System.out.println("    (nothing depends on this file within the traversal depth)");
            }
            System.out.println();
            System.out.println("  " + total + " reachable node(s) shown");
            System.out.println();
            return 0;
        }
    }

    @CommandLine.Command(name = "symbol", description = "Show callers and callees of a symbol")
    static final class SymbolQuery implements Callable<Integer> {

        @CommandLine.Mixin
        CommonOptions options = new CommonOptions();

        @CommandLine.Parameters(index = "0", description = "Fully qualified symbol name or node id")
        String symbolRef;

        @Override
        public Integer call() {
            StageContext context = options.context();
            ApplicationGraph graph = loadGraph(context);
            GraphNode node = graph.node(symbolRef).orElseGet(() -> graph.nodesMatching(
                            n -> symbolRef.equals(n.getFqn()) || symbolRef.equals(n.getSymbolId()))
                    .stream().findFirst().orElse(null));
            if (node == null) {
                System.out.println("  No symbol matched " + symbolRef);
                return 2;
            }
            System.out.println();
            System.out.println("  " + node.getType() + " " + node.getFqn());
            System.out.println("  symbol id   " + node.getSymbolId());
            System.out.println("  attribution " + node.getAttribution());
            System.out.println("  file id     " + node.getFileId());
            System.out.println("  lines       " + node.getLineStart() + "-" + node.getLineEnd());
            System.out.println();
            System.out.println("  callers:");
            graph.callers(node.getId()).forEach(c -> System.out.println("    " + c.getFqn()));
            System.out.println("  callees:");
            graph.callees(node.getId()).forEach(c -> System.out.println("    " + c.getFqn()));
            System.out.println("  implementors:");
            graph.implementors(node.getId()).forEach(c -> System.out.println("    " + c.getFqn()));
            System.out.println();
            return 0;
        }
    }

    static String resolveFileId(FileRegistry registry, String reference) {
        if (registry.byId(reference).isPresent()) {
            return reference;
        }
        return registry.byPath(reference)
                .map(com.bootshift.core.identity.FileRecord::getFileId)
                .orElseGet(() -> registry.all().stream()
                        .filter(r -> r.getCurrentPath().endsWith(reference))
                        .findFirst()
                        .map(com.bootshift.core.identity.FileRecord::getFileId)
                        .orElseThrow(() -> HarnessException.refusal(
                                "No file matched " + reference + " in the registry")));
    }

    static String renderPath(List<ApplicationGraph.PathStep> path) {
        StringBuilder sb = new StringBuilder();
        for (ApplicationGraph.PathStep step : path) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(step.fromId()).append(" -[").append(step.edge()).append("]-> ").append(step.toId());
        }
        return sb.length() == 0 ? "(origin)" : sb.toString();
    }
}
