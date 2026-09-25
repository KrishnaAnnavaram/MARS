package com.bootshift.stages.stage03;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.analysis.JavaParserCodeModelAdapter;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.identity.FileRole;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.analysis.CodeModelPort;
import com.bootshift.ports.build.BuildModelCodec;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Agent 03 - Application Graph / Code Model (spec section 13) and Graph Verification (section 14).
 *
 * <p>Builds the static/semantic graph. Runtime facts are explicitly not present here: this is
 * {@code G0_BASELINE_STATIC}, and Agent 04 enriches it with observations afterwards (R27).
 *
 * <p>Verification is independent by construction: it cross-checks the graph against the inventory
 * and the build model rather than against the graph's own output, and it fails the run when
 * integrity falls below the policy floor for critical modules.
 */
public final class ApplicationGraphStage implements Stage {

    public static final String OUTPUT_DIR = "03-graph";

    private final String graphLabel;

    public ApplicationGraphStage() {
        this("G0_BASELINE_STATIC");
    }

    public ApplicationGraphStage(String graphLabel) {
        this.graphLabel = graphLabel;
    }

    @Override
    public String id() {
        return OUTPUT_DIR;
    }

    @Override
    public String outputDirectory() {
        return OUTPUT_DIR;
    }

    @Override
    public String purpose() {
        return "Build and verify the type-aware, multi-view static application graph";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.BUILD_RESOLVED);
    }

    @Override
    public RunState postcondition() {
        return RunState.GRAPH_VERIFIED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("01-inventory/file-registry.json", "02-build/build-model.json",
                "02-build/dependency-model.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("application-graph.json", "file-registry.json", "symbol-registry.json",
                "module-graph.json", "file-graph.json", "symbol-graph.json", "dependency-graph.json",
                "spring-graph.json", "configuration-graph.json", "persistence-graph.json",
                "endpoint-graph.json", "test-graph.json", "integration-graph.json",
                "graph-summary.json", "graph-issues.json", "graph-verification-report.json",
                "graph-verification-report.md", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode registryNode = StageSupport.requireUpstream(context, "01-inventory",
                "file-registry.json", "Run: harness inventory --repo <path>");
        JsonNode buildNode = StageSupport.requireUpstream(context, "02-build", "build-model.json",
                "Run: harness resolve-build --repo <path>");
        JsonNode dependencyNode = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");

        FileRegistry registry = FileRegistry.fromNode(registryNode);
        registry.clearSymbols();
        BuildSystemPort.BuildModel buildModel = readBuildModel(buildNode, dependencyNode);

        Path root = context.run().originalWorkspace();
        if (!Files.isDirectory(root)) {
            root = context.run().sourceRoot();
        }

        CodeModelPort codeModel = new JavaParserCodeModelAdapter();
        Map<String, CodeModelPort.AnalysisResult> analysisByModule = new LinkedHashMap<>();
        Map<String, Integer> javaReleaseByModule = new java.util.TreeMap<>();
        List<CodeModelPort.ParseIssue> unmodelledSources = new ArrayList<>();
        for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
            Path moduleRoot = ".".equals(module.moduleId()) ? root : root.resolve(module.moduleId());
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            // Java roots go to the Java parser. Kotlin and Groovy roots do NOT: handing them to a
            // Java-only parser produced failures that read as parse errors while the graph silently
            // lost whatever those files declared, and the coverage number counted only Java files so
            // nothing showed it.
            List<Path> sourceRoots = new ArrayList<>();
            for (String candidate : List.of("src/main/java", "src/test/java")) {
                Path source = moduleRoot.resolve(candidate);
                if (Files.isDirectory(source)) {
                    sourceRoots.add(source);
                }
            }
            List<Path> unmodelledRoots = new ArrayList<>();
            for (String candidate : List.of("src/main/kotlin", "src/test/kotlin",
                    "src/main/groovy", "src/test/groovy", "src/main/scala")) {
                Path source = moduleRoot.resolve(candidate);
                if (Files.isDirectory(source)) {
                    unmodelledRoots.add(source);
                }
            }
            unmodelledSources.addAll(
                    JavaParserCodeModelAdapter.unmodelledSources(unmodelledRoots));
            if (sourceRoots.isEmpty()) {
                continue;
            }
            // The level the module declares, not the newest the parser supports.
            int javaRelease = module.effectiveJavaRelease(21);
            javaReleaseByModule.put(module.moduleId(), javaRelease);
            analysisByModule.put(module.moduleId(),
                    codeModel.analyze(moduleRoot, sourceRoots, classpathFor(moduleRoot, module),
                            javaRelease));
        }

        Map<String, String> configurationFiles = readConfiguration(registry, root);

        GraphBuilder.Result built = new GraphBuilder().build(new GraphBuilder.Input(
                registry, buildModel, analysisByModule, configurationFiles, graphLabel));
        ApplicationGraph graph = built.graph();

        Verification verification = verify(context, graph, registry, buildModel, built);

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR)
                .stat("nodes", graph.nodeCount())
                .stat("edges", graph.edgeCount())
                .stat("symbols", built.symbols().size())
                .stat("types_modelled", built.typesModelled())
                .stat("attribution_ratio", built.attributionRatio())
                .stat("structural_hash", graph.structuralHash())
                .stat("content_hash", graph.contentHash())
                .stat("graph_label", graph.label());

        if (built.attributionRatio() < context.policy().graphAttributionFloor()) {
            envelope.gap(new Envelope.Gap("GAP-GRAPH-001", "TYPE_ATTRIBUTION",
                    "Type attribution ratio " + round(built.attributionRatio())
                            + " is below the policy floor " + context.policy().graphAttributionFloor(),
                    "Impact classification is capped at POSSIBLY_AFFECTED for unresolved relations"));
        }
        if (!unmodelledSources.isEmpty()) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-GRAPH-LANGUAGE", "STATIC_GRAPH",
                    unmodelledSources.size() + " source file(s) are in a language this analyser does "
                            + "not model (Kotlin, Groovy or Scala)",
                    "Types, endpoints and beans declared in those files are absent from the graph. "
                            + "Impact findings that would depend on them do not exist, and the "
                            + "coverage statement counts only the Java sources that were analysed."));
        }
        envelope.blindSpot(new Envelope.BlindSpot("BS-GRAPH-RUNTIME", "RUNTIME_GRAPH",
                "This graph contains no runtime observations",
                "Static analysis cannot see conditional bean activation, actual injection or "
                        + "actually-bound properties; Agent 04 and Agent 16 add those separately (R27)"));

        ObjectNode graphArtifact = StageSupport.compose(envelope, graph.toNode());
        StageSupport.validate(context, writer, "graph/application-graph.schema.json",
                "application-graph.json", graphArtifact);
        writer.write("application-graph.json", graphArtifact);

        writer.write("file-registry.json", StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), registry.toNode()));

        ObjectNode symbolRegistry = Json.obj();
        symbolRegistry.put("symbol_count", built.symbols().size());
        symbolRegistry.set("symbols", Json.toTree(built.symbols()));
        StageSupport.validate(context, writer, "symbol-registry/symbol-registry.schema.json",
                "symbol-registry.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), symbolRegistry));
        writer.write("symbol-registry.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), symbolRegistry));

        writeView(context, writer, graph, "module-graph.json", "ModuleGraph",
                Set.of(EdgeType.CONTAINS, EdgeType.DEPENDS_ON));
        writeView(context, writer, graph, "file-graph.json", "FileGraph",
                Set.of(EdgeType.CONTAINS, EdgeType.DECLARES));
        writeView(context, writer, graph, "symbol-graph.json", "SymbolGraph",
                Set.of(EdgeType.DECLARES, EdgeType.CALLS, EdgeType.EXTENDS, EdgeType.IMPLEMENTS,
                        EdgeType.USES_TYPE, EdgeType.IMPORTS));
        writeView(context, writer, graph, "dependency-graph.json", "LibraryDependencyGraph",
                Set.of(EdgeType.DEPENDS_ON_LIBRARY));
        writeView(context, writer, graph, "spring-graph.json", "SpringGraph",
                Set.of(EdgeType.INJECTS, EdgeType.DECLARES_BEAN, EdgeType.CALLS_SERVICE,
                        EdgeType.CALLS_REPOSITORY));
        writeView(context, writer, graph, "configuration-graph.json", "ConfigurationGraph",
                Set.of(EdgeType.CONFIGURES, EdgeType.USES_CONFIG_PROPERTY, EdgeType.ACTIVATED_BY_PROFILE));
        writeView(context, writer, graph, "persistence-graph.json", "PersistenceGraph",
                Set.of(EdgeType.MANAGES_ENTITY, EdgeType.MAPS_TO_TABLE));
        writeView(context, writer, graph, "endpoint-graph.json", "EndpointGraph",
                Set.of(EdgeType.HANDLES_ENDPOINT));
        writeView(context, writer, graph, "test-graph.json", "TestGraph",
                Set.of(EdgeType.COVERED_BY_TEST));
        writeView(context, writer, graph, "integration-graph.json", "IntegrationGraph",
                Set.of(EdgeType.CALLS_EXTERNAL_SERVICE, EdgeType.REGISTERS_WITH_DISCOVERY,
                        EdgeType.READS_FROM_CONFIG_SERVER, EdgeType.PUBLISHES_TO, EdgeType.CONSUMES_FROM));

        ObjectNode summary = Json.obj();
        summary.set("node_counts", Json.toTree(countByNodeType(graph)));
        summary.set("edge_counts", Json.toTree(countByEdgeType(graph)));
        summary.put("attribution_ratio", built.attributionRatio());
        summary.put("structural_hash", graph.structuralHash());
        summary.put("content_hash", graph.contentHash());
        summary.put("hash_note", "structural_hash includes run-scoped FILE_IDs and is comparable only within a run; content_hash excludes them and is what two runs should be compared on");
        summary.set("modules_analysed", Json.toTree(analysisByModule.keySet()));
        summary.set("java_release_by_module", Json.toTree(javaReleaseByModule));
        summary.put("language_level_rule", "Each module is parsed at the Java release it declares, "
                + "not at the newest level the parser supports.");
        summary.put("unmodelled_source_count", unmodelledSources.size());
        summary.set("unmodelled_sources", Json.toTree(unmodelledSources.stream().limit(100).toList()));
        writer.write("graph-summary.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), summary));

        ObjectNode issues = Json.obj();
        issues.put("issue_count", built.issues().size());
        issues.set("issues", Json.toTree(built.issues()));
        issues.put("unmodelled_source_count", unmodelledSources.size());
        issues.set("unmodelled_sources", Json.toTree(unmodelledSources));
        writer.write("graph-issues.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), issues));

        ObjectNode verificationNode = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), verification.toNode());
        writer.write("graph-verification-report.json", verificationNode);
        writer.writeText("graph-verification-report.md", verification.toMarkdown());

        StageSupport.toEvidence(context, "application-graph", graphArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Graph artifacts failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);

        if (!verification.passed()) {
            context.stateMachine().transition(RunState.APPLICATION_GRAPH_BUILT, "graph built");
            return StageResult.failure(OUTPUT_DIR, ExitCode.POLICY_BLOCK,
                    "Graph verification failed policy floors; baseline capture is blocked",
                    verification.failures());
        }

        context.stateMachine().transition(RunState.APPLICATION_GRAPH_BUILT,
                graph.nodeCount() + " nodes");
        context.stateMachine().transition(RunState.GRAPH_VERIFIED, "verification passed");
        context.runStateStore().updateState(context.run().runId(), RunState.GRAPH_VERIFIED,
                "graph verified");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));
        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                graph.nodeCount() + " nodes / " + graph.edgeCount() + " edges, "
                        + built.symbols().size() + " symbols, attribution "
                        + round(built.attributionRatio()) + ", content hash "
                        + graph.contentHash().substring(0, 12),
                verification.warnings(), artifacts, hash);
    }

    // ------------------------------------------------------------------ verification

    /** Independent graph verification (spec section 14). */
    public record Verification(boolean passed, Map<String, Object> checks, List<String> failures,
                               List<String> warnings, List<ObjectNode> spotChecks) {

        ObjectNode toNode() {
            ObjectNode node = Json.obj();
            node.put("passed", passed);
            node.set("checks", Json.toTree(checks));
            node.set("failures", Json.toTree(failures));
            node.set("warnings", Json.toTree(warnings));
            node.set("edge_spot_checks", Json.toTree(spotChecks));
            return node;
        }

        String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# Application Graph Verification Report\n\n");
            sb.append("**Result:** ").append(passed ? "PASSED" : "FAILED").append("\n\n");
            sb.append("| Check | Value |\n|---|---|\n");
            checks.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
            if (!failures.isEmpty()) {
                sb.append("\n## Failures\n\n");
                failures.forEach(f -> sb.append("- ").append(f).append('\n'));
            }
            if (!warnings.isEmpty()) {
                sb.append("\n## Warnings\n\n");
                warnings.forEach(w -> sb.append("- ").append(w).append('\n'));
            }
            if (!spotChecks.isEmpty()) {
                sb.append("\n## Representative edge spot checks\n\n");
                sb.append("| Edge | Evidence |\n|---|---|\n");
                spotChecks.forEach(s -> sb.append("| ").append(s.path("edge").asText())
                        .append(" | ").append(s.path("evidence").asText()).append(" |\n"));
            }
            return sb.toString();
        }
    }

    private Verification verify(StageContext context, ApplicationGraph graph, FileRegistry registry,
                                BuildSystemPort.BuildModel buildModel, GraphBuilder.Result built) {
        Map<String, Object> checks = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ObjectNode> spotChecks = new ArrayList<>();

        // 1. Java file coverage, cross-checked against the inventory rather than the graph.
        long javaFiles = registry.active().stream().filter(r -> r.getRole().isJavaSource()).count();
        long javaFilesInGraph = registry.active().stream()
                .filter(r -> r.getRole().isJavaSource())
                .filter(r -> !graph.nodesForFile(r.getFileId()).isEmpty())
                .count();
        double javaCoverage = javaFiles == 0 ? 1.0 : (double) javaFilesInGraph / javaFiles;
        checks.put("java_files_inventoried", javaFiles);
        checks.put("java_files_in_graph", javaFilesInGraph);
        checks.put("java_file_coverage", round(javaCoverage));
        if (javaCoverage < context.policy().graphJavaCoverageFloor()) {
            failures.add("Java file coverage " + round(javaCoverage) + " is below the policy floor "
                    + context.policy().graphJavaCoverageFloor() + " (" + (javaFiles - javaFilesInGraph)
                    + " file(s) produced no graph node)");
        }

        // 2. module coverage against the build model.
        long modulesInGraph = graph.nodesOfType(NodeType.MODULE).size();
        checks.put("modules_in_build_model", buildModel.modules().size());
        checks.put("modules_in_graph", modulesInGraph);
        if (modulesInGraph < buildModel.modules().size()) {
            failures.add("Graph holds " + modulesInGraph + " module node(s) but the build model "
                    + "declares " + buildModel.modules().size());
        }

        // 3. type coverage.
        long typeNodes = graph.nodesMatching(n -> n.getType().isTypeDeclaration()).size();
        checks.put("types_modelled", built.typesModelled());
        checks.put("type_nodes_in_graph", typeNodes);
        if (typeNodes < built.typesModelled()) {
            warnings.add("Some parsed types did not become graph nodes: " + built.typesModelled()
                    + " parsed vs " + typeNodes + " in graph (nested types are modelled by their parent)");
        }

        // 4. endpoint coverage.
        long controllers = graph.nodesOfType(NodeType.CONTROLLER).size();
        long endpoints = graph.nodesOfType(NodeType.ENDPOINT).size();
        checks.put("controllers", controllers);
        checks.put("endpoints", endpoints);
        if (controllers > 0 && endpoints == 0) {
            failures.add(controllers + " controller(s) produced zero endpoint nodes, which means "
                    + "request mapping extraction is broken");
        }

        // 5. Spring component coverage.
        long springComponents = graph.nodesMatching(n ->
                n.getType() == NodeType.SERVICE || n.getType() == NodeType.REPOSITORY
                        || n.getType() == NodeType.CONTROLLER || n.getType() == NodeType.SPRING_BEAN
                        || n.getType() == NodeType.CONFIGURATION_CLASS).size();
        checks.put("spring_components", springComponents);

        // 6. resolved dependency coverage.
        long distinctLibraries = buildModel.dependencies().stream().map(BuildSystemPort.ResolvedDependency::ga)
                .distinct().count();
        long libraryNodes = graph.nodesOfType(NodeType.LIBRARY).size();
        checks.put("distinct_libraries_in_build_model", distinctLibraries);
        checks.put("library_nodes_in_graph", libraryNodes);
        if (libraryNodes < distinctLibraries) {
            failures.add("Graph holds " + libraryNodes + " library node(s) for " + distinctLibraries
                    + " distinct resolved coordinates");
        }

        // 7. representative edge spot checks with file and line evidence.
        graph.edges().stream()
                .filter(e -> e.getType() == EdgeType.HANDLES_ENDPOINT
                        || e.getType() == EdgeType.INJECTS
                        || e.getType() == EdgeType.MANAGES_ENTITY
                        || e.getType() == EdgeType.DEPENDS_ON_LIBRARY)
                .limit(12)
                .forEach(e -> {
                    ObjectNode check = Json.obj();
                    check.put("edge", e.key());
                    graph.node(e.getFrom()).ifPresent(from -> check.put("evidence",
                            (from.getFqn() == null ? from.getName() : from.getFqn())
                                    + (from.getLineStart() == null ? ""
                                    : " lines " + from.getLineStart() + "-" + from.getLineEnd())));
                    spotChecks.add(check);
                });

        // 8. no harness-source contamination.
        long harnessNodes = graph.nodesMatching(n -> n.getFqn() != null
                && n.getFqn().startsWith("com.bootshift")).size();
        checks.put("harness_nodes_in_graph", harnessNodes);
        if (harnessNodes > 0) {
            failures.add("Graph contains " + harnessNodes + " node(s) from the harness itself; the "
                    + "application graph must only describe the application under analysis");
        }

        // 9. attribution ratios.
        checks.put("attribution_ratio", round(built.attributionRatio()));
        long unresolvedNodes = graph.nodesMatching(n ->
                n.getAttribution() != com.bootshift.core.graph.GraphNode.Attribution.RESOLVED).size();
        checks.put("unresolved_or_ambiguous_nodes", unresolvedNodes);
        if (built.attributionRatio() < context.policy().graphAttributionFloor()) {
            warnings.add("Attribution ratio " + round(built.attributionRatio())
                    + " is below the policy floor " + context.policy().graphAttributionFloor()
                    + "; impact findings from unresolved relations are capped at POSSIBLY_AFFECTED");
        }

        // 10. graph query integrity.
        // Probe a node that genuinely participates in dependency relationships. A bare FILE node has
        // only a CONTAINS parent, so using one would make a working traversal look broken.
        boolean queriesWork = true;
        String queryDetail = "no application type nodes to query";
        var probes = graph.nodesMatching(n -> n.getType() == NodeType.SERVICE
                || n.getType() == NodeType.REPOSITORY || n.getType() == NodeType.CONTROLLER);
        if (probes.isEmpty()) {
            probes = graph.nodesMatching(n -> n.getType().isTypeDeclaration());
        }
        if (!probes.isEmpty()) {
            var probe = probes.stream()
                    .max(java.util.Comparator.comparingInt(n -> graph.incoming(n.getId()).size()))
                    .orElse(probes.get(0));
            var dependents = graph.blastRadius(probe.getId(), 4);
            var dependencies = graph.transitiveDependencies(probe.getId(), 4);
            queriesWork = !dependents.isEmpty() || !dependencies.isEmpty();
            queryDetail = "blast radius from " + probe.getId() + " returned " + dependents.size()
                    + " node(s); transitive dependencies returned " + dependencies.size();
            boolean pathsExplained = dependents.stream().allMatch(r -> !r.path().isEmpty());
            if (!pathsExplained) {
                failures.add("Blast radius returned a node without an explaining path");
            }
            if (!queriesWork) {
                failures.add("Graph traversal returned nothing from " + probe.getId()
                        + ", which means the relationship index is not wired");
            }
        }
        checks.put("graph_query_integrity", queriesWork ? "OK" : "FAILED");
        checks.put("graph_query_detail", queryDetail);

        return new Verification(failures.isEmpty(), checks, failures, warnings, spotChecks);
    }

    // ------------------------------------------------------------------ helpers

    private void writeView(StageContext context, OutputLayout.StageWriter writer,
                           ApplicationGraph graph, String artifactName, String viewName,
                           Set<EdgeType> types) {
        writer.write(artifactName, StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), graph.viewNode(viewName, types)));
    }

    private Map<String, Integer> countByNodeType(ApplicationGraph graph) {
        Map<String, Integer> counts = new TreeMap<>();
        graph.nodes().forEach(n -> counts.merge(n.getType().name(), 1, Integer::sum));
        return counts;
    }

    private Map<String, Integer> countByEdgeType(ApplicationGraph graph) {
        Map<String, Integer> counts = new TreeMap<>();
        graph.edges().forEach(e -> counts.merge(e.getType().name(), 1, Integer::sum));
        return counts;
    }

    private Map<String, String> readConfiguration(FileRegistry registry, Path root) {
        Map<String, String> files = new LinkedHashMap<>();
        for (FileRecord record : registry.active()) {
            if (!GraphBuilder.isConfigurationRole(record.getRole())) {
                continue;
            }
            Path file = root.resolve(record.getCurrentPath());
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                files.put(record.getCurrentPath(), Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                files.put(record.getCurrentPath(), "");
            }
        }
        return files;
    }

    /**
     * The classpath the symbol solver gets. Preference order is the build tool's resolved classpath,
     * then any jars a previous build copied into {@code target/dependency}. An empty classpath is not
     * an error: it lowers the attribution ratio, which is reported and which caps impact
     * classification downstream.
     */
    private List<Path> classpathFor(Path moduleRoot, BuildSystemPort.ModuleModel module) {
        List<Path> classpath = new ArrayList<>();
        for (String entry : module.classpath()) {
            Path jar = Path.of(entry);
            if (Files.isRegularFile(jar)) {
                classpath.add(jar);
            }
        }
        Path dependencyDir = moduleRoot.resolve("target/dependency");
        if (Files.isDirectory(dependencyDir)) {
            try (var stream = Files.list(dependencyDir)) {
                stream.filter(p -> p.toString().endsWith(".jar")).forEach(classpath::add);
            } catch (IOException e) {
                // best effort: absent jars simply lower the attribution ratio, which is reported
            }
        }
        return classpath;
    }

    /**
     * Rehydrates the build model from the published artifacts.
     *
     * <p>Delegates to {@link BuildModelCodec} rather than rebuilding a partial copy. The hand-written
     * version this replaced dropped managed versions, plugins, repositories and resolution issues on
     * the floor, so every stage downstream of Stage 02 believed nothing was managed by a BOM.
     */
    public static BuildSystemPort.BuildModel readBuildModel(JsonNode buildNode, JsonNode dependencyNode) {
        return BuildModelCodec.decode(buildNode, dependencyNode);
    }

    public static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** Roles the graph treats as configuration input. */
    public static boolean isConfiguration(FileRole role) {
        return GraphBuilder.isConfigurationRole(role);
    }
}
