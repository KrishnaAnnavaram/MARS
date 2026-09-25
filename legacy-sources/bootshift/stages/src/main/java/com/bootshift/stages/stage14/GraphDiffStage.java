package com.bootshift.stages.stage14;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.analysis.JavaParserCodeModelAdapter;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.GraphDiff;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.analysis.CodeModelPort;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.stage03.ApplicationGraphStage;
import com.bootshift.stages.stage03.GraphBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 14 - Graph Rebuild and Graph Diff (spec section 30).
 *
 * <p>Runs immediately after the edge compiles and <em>before</em> expensive testing, so an
 * out-of-scope structural change blocks in seconds instead of after an hour of validation.
 *
 * <p>V1 always performs a full rebuild (ADR-005). Incremental graph mutation is a correctness risk
 * that is only worth taking once a graph-equivalence test proves the two agree.
 *
 * <p>When the edge did not compile, the graph is explicitly {@code PARTIAL}: the harness says so
 * rather than presenting a degraded graph as complete.
 */
public final class GraphDiffStage implements Stage {

    public static final String OUTPUT_DIR = "14-graph-diff";

    private final String edgeId;

    public GraphDiffStage(String edgeId) {
        this.edgeId = edgeId;
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
        return "Rebuild the static graph and assert that every change stayed inside authorized scope";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.EDGE_COMPILED);
    }

    @Override
    public RunState postcondition() {
        return RunState.EDGE_SCOPE_VERIFIED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("13-build-repair/build-report.json", "11-plan/edge-plan.json",
                "02-build/build-model.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("application-graph-current.json", "graph-diff.json", "scope-assertion.json",
                "last-good-graph.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode edgePlanArtifact = StageSupport.requireUpstream(context, "11-plan", "edge-plan.json",
                "Run: harness plan");
        JsonNode edgePlan = EdgeSupport.findEdge(edgePlanArtifact, edgeId);
        JsonNode buildReport = StageSupport.optionalUpstream(context, "13-build-repair",
                "build-report.json");
        JsonNode buildNode = StageSupport.requireUpstream(context, "02-build", "build-model.json",
                "Run: harness resolve-build --repo <path>");
        JsonNode dependencyNode = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");

        boolean compiled = buildReport != null && buildReport.path("compiled").asBoolean(false);
        BuildSystemPort.BuildModel buildModel =
                ApplicationGraphStage.readBuildModel(buildNode, dependencyNode);
        FileRegistry registry = EdgeSupport.loadRegistry(context);
        Path workspace = context.run().migrationWorkspace();

        // Full rebuild from the migrated workspace.
        CodeModelPort codeModel = new JavaParserCodeModelAdapter();
        Map<String, CodeModelPort.AnalysisResult> analysisByModule = new LinkedHashMap<>();
        for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
            Path moduleRoot = ".".equals(module.moduleId()) ? workspace
                    : workspace.resolve(module.moduleId());
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            List<Path> sourceRoots = new ArrayList<>();
            for (String candidate : List.of("src/main/java", "src/test/java")) {
                Path source = moduleRoot.resolve(candidate);
                if (Files.isDirectory(source)) {
                    sourceRoots.add(source);
                }
            }
            if (sourceRoots.isEmpty()) {
                continue;
            }
            List<Path> classpath = new ArrayList<>();
            module.classpath().forEach(entry -> {
                Path jar = Path.of(entry);
                if (Files.isRegularFile(jar)) {
                    classpath.add(jar);
                }
            });
            // Same language level as the baseline graph, so a diff cannot be produced by the two
            // sides having been parsed as different languages.
            analysisByModule.put(module.moduleId(),
                    codeModel.analyze(moduleRoot, sourceRoots, classpath,
                            module.effectiveJavaRelease(21)));
        }

        Map<String, String> configuration = new LinkedHashMap<>();
        for (FileRecord record : registry.active()) {
            if (!record.getRole().isConfiguration()) {
                continue;
            }
            Path file = workspace.resolve(record.getCurrentPath());
            if (Files.isRegularFile(file)) {
                try {
                    configuration.put(record.getCurrentPath(),
                            Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    configuration.put(record.getCurrentPath(), "");
                }
            }
        }

        GraphBuilder.Result built = new GraphBuilder().build(new GraphBuilder.Input(
                registry, buildModel, analysisByModule, configuration, "G_EDGE_STATIC:" + edgeId));
        ApplicationGraph current = built.graph();
        if (!compiled) {
            current.status("PARTIAL");
        }

        ApplicationGraph lastGood = EdgeSupport.lastGoodGraph(context);
        GraphDiff diff = GraphDiff.between(lastGood, current);

        // ---- scope assertion --------------------------------------------------------------------
        Set<String> authorizedFileIds = new LinkedHashSet<>();
        edgePlan.path("affected_file_ids").forEach(n -> authorizedFileIds.add(n.asText()));
        registry.active().stream()
                .filter(r -> r.getRole().isBuildDescriptor())
                .forEach(r -> authorizedFileIds.add(r.getFileId()));

        // Files the ledger actually recorded a change for are authorized by construction: the
        // gateway already refused anything outside scope, so this catches consequences rather than
        // re-litigating the same check.
        Set<String> changedByLedger = new LinkedHashSet<>();
        EdgeSupport.openLedger(context).entries().stream()
                .filter(e -> e.event().getStatus()
                        == com.bootshift.core.ledger.ChangeEvent.Status.APPLIED)
                .forEach(e -> changedByLedger.add(e.event().getFileId()));

        List<String> violations = new ArrayList<>();
        List<String> expectedConsequences = new ArrayList<>();
        for (String fileId : diff.changedFileIds()) {
            if (fileId == null) {
                continue;
            }
            if (authorizedFileIds.contains(fileId) || changedByLedger.contains(fileId)) {
                continue;
            }
            String path = registry.byId(fileId).map(FileRecord::getCurrentPath).orElse(fileId);
            // A graph change in an unmutated file is normal when a type it references changed.
            if (referencesChangedFile(current, fileId, changedByLedger)) {
                expectedConsequences.add(path + " changed as a consequence of a mutated dependency");
            } else {
                violations.add("SCOPE VIOLATION: graph facts changed in " + path
                        + " but nothing authorized or explains a change there");
            }
        }

        boolean scopeOk = violations.isEmpty();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId)
                .stat("graph_status", current.status())
                .stat("nodes", current.nodeCount())
                .stat("edges", current.edgeCount())
                .stat("scope_ok", scopeOk);
        if (!compiled) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-GRAPH-PARTIAL", "STATIC_GRAPH",
                    "The edge did not compile, so full type attribution could not be rebuilt",
                    "Graph diff is computed against a partial graph and cannot be treated as complete"));
        }

        writer.write("application-graph-current.json",
                StageSupport.compose(envelope, current.toNode()));

        ObjectNode diffArtifact = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId), diff.toNode());
        StageSupport.validate(context, writer, "graph/graph-diff.schema.json",
                "graph-diff.json", diffArtifact);
        writer.write("graph-diff.json", diffArtifact);

        ObjectNode scope = Json.obj();
        scope.put("authorized_file_count", authorizedFileIds.size());
        scope.put("ledger_changed_file_count", changedByLedger.size());
        scope.put("graph_changed_file_count", diff.changedFileIds().size());
        scope.put("scope_ok", scopeOk);
        scope.set("violations", Json.toTree(violations));
        scope.set("expected_consequences", Json.toTree(expectedConsequences));
        scope.put("rule", "Unexpected graph change equals scope violation; review or block per policy");
        writer.write("scope-assertion.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId), scope));

        if (scopeOk && compiled) {
            writer.write("last-good-graph.json", current.toNode());
        } else {
            writer.write("last-good-graph.json", lastGood.toNode());
        }

        String hash = StageSupport.publishForEdge(context, writer, edgeId, OUTPUT_DIR,
                com.bootshift.stages.EdgeIndex.Phase.GRAPH_VERIFIED, "published");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        if (!scopeOk) {
            context.stateMachine().transition(RunState.EDGE_GRAPH_REBUILT, "graph rebuilt");
            context.stateMachine().transition(RunState.BLOCKED, "scope violation on " + edgeId);
            context.runStateStore().updateState(context.run().runId(), RunState.BLOCKED,
                    "scope violation");
            return new StageResult(OUTPUT_DIR, ExitCode.POLICY_BLOCK,
                    "Edge " + edgeId + " changed graph facts outside the authorized scope",
                    violations, artifacts, hash);
        }

        context.stateMachine().transition(RunState.EDGE_GRAPH_REBUILT,
                current.nodeCount() + " nodes rebuilt");
        context.stateMachine().transition(RunState.EDGE_SCOPE_VERIFIED, "scope verified");
        context.runStateStore().updateState(context.run().runId(), RunState.EDGE_SCOPE_VERIFIED,
                "scope verified for " + edgeId);
        EdgeSupport.checkpoint(context, edgeId, "graph-verified",
                "Edge " + edgeId + " graph rebuilt and scope verified");

        List<String> messages = new ArrayList<>(expectedConsequences);
        if (!compiled) {
            messages.add("GRAPH_STATUS=PARTIAL: the edge did not compile");
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Edge " + edgeId + " graph " + current.status() + ": +"
                        + diff.nodesAdded().size() + "/-" + diff.nodesRemoved().size() + " nodes, +"
                        + diff.edgesAdded().size() + "/-" + diff.edgesRemoved().size() + " edges, "
                        + diff.changedSymbolIds().size() + " symbol(s) changed; scope OK",
                messages, artifacts, hash);
    }

    /**
     * True when the node's file depends on a file the ledger actually changed, which makes a graph
     * difference an expected consequence rather than an unexplained change.
     */
    private boolean referencesChangedFile(ApplicationGraph graph, String fileId,
                                          Set<String> changedFileIds) {
        for (var node : graph.nodesForFile(fileId)) {
            for (String dependency : graph.dependencies(node.getId())) {
                String targetFile = graph.node(dependency)
                        .map(com.bootshift.core.graph.GraphNode::getFileId).orElse(null);
                if (targetFile != null && changedFileIds.contains(targetFile)) {
                    return true;
                }
            }
        }
        return false;
    }
}
