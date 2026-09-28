package com.mars.harness.kernel.adapters.bootshift;

import com.bootshift.adapters.analysis.JavaParserCodeModelAdapter;
import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.adapters.scm.GitScmAdapter;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.ports.analysis.CodeModelPort;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.scm.ScmPort;
import com.bootshift.stages.RunFactory;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageExecutor;
import com.bootshift.stages.bootstrap.RunBootstrap;
import com.bootshift.stages.stage01.InventoryStage;
import com.bootshift.stages.stage02.BuildResolverStage;
import com.bootshift.stages.stage03.ApplicationGraphStage;
import com.bootshift.stages.stage03.GraphBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.run.RunLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The adapter through which the unified kernel calls Bootshift, unchanged (ADR-U001).
 *
 * <p>Phase 0 to 2 of the unified lifecycle are Bootshift's bootstrap, inventory, build resolution
 * and application-graph stages, executed through Bootshift's own {@link StageExecutor} with its
 * own preconditions. The only thing this class decides is <em>where</em> Bootshift runs: its
 * {@code RunContext} is rooted at the unified runs directory, so Bootshift's
 * {@code original/}, {@code migration/} and checkpoint repository are the unified run's snapshot,
 * workspace and checkpoints, not copies.
 */
public final class BootshiftBridge {

    /** What one Bootshift stage reported, kept verbatim for the evidence plane. */
    public record StageSummary(String stageId, String exitCode, String summary, List<String> messages) {
    }

    public record AnalysisOutcome(List<StageSummary> stages, boolean bootstrapped, boolean inventoried,
                                  boolean buildResolved, boolean graphPublished, boolean graphVerified,
                                  FileRegistry fileRegistry, JsonNode inventory, JsonNode buildModel,
                                  JsonNode dependencyModel, ApplicationGraph graph, JsonNode graphVerification,
                                  JsonNode sourceProvenance) {
    }

    private final RunLayout layout;
    private final Path repository;
    private final Path bootshiftHome;
    private final String policyName;
    private final boolean networkEnabled;
    private StageContext context;

    public BootshiftBridge(RunLayout layout, Path repository, Path bootshiftHome, String policyName,
                           boolean networkEnabled) {
        this.layout = layout;
        this.repository = repository;
        this.bootshiftHome = bootshiftHome;
        this.policyName = policyName;
        this.networkEnabled = networkEnabled;
    }

    public StageContext context() {
        if (context == null) {
            context = RunFactory.create(new RunFactory.Options(repository, layout.runsRoot(), layout.bootshiftOutput(),
                    bootshiftHome, policyName, null, false, "managed", networkEnabled, layout.runId(), Map.of()));
        }
        return context;
    }

    /** Bootstrap, inventory, build resolution and graph: Bootshift stages 00 to 03, unchanged. */
    public AnalysisOutcome analyze() {
        return analyze(stage -> { });
    }

    /** As {@link #analyze()}, telling {@code onStage} about each stage as soon as it has run. */
    public AnalysisOutcome analyze(Consumer<StageSummary> onStage) {
        StageContext ctx = context();
        List<StageSummary> stages = new ArrayList<>();
        boolean bootstrapped = ctx.run().output().resolveLatestDir(RunBootstrap.OUTPUT_DIR) != null;
        if (!bootstrapped) {
            StageResult result = new RunBootstrap(ctx).execute();
            stages.add(summary(result));
            onStage.accept(stages.get(stages.size() - 1));
            bootstrapped = result.succeeded();
        }
        boolean inventoried = bootstrapped && run(new InventoryStage(), stages, onStage);
        boolean resolved = inventoried && run(new BuildResolverStage(), stages, onStage);
        boolean graphOk = resolved && run(new ApplicationGraphStage(), stages, onStage);

        JsonNode graphNode = ctx.run().output().readLatest("03-graph", "application-graph.json");
        JsonNode registryNode = ctx.run().output().readLatest("03-graph", "file-registry.json");
        if (registryNode == null) {
            registryNode = ctx.run().output().readLatest("01-inventory", "file-registry.json");
        }
        FileRegistry registry = registryNode == null ? null : FileRegistry.fromNode(unwrap(registryNode));
        return new AnalysisOutcome(stages, bootstrapped, inventoried, resolved, graphNode != null, graphOk, registry,
                ctx.run().output().readLatest("01-inventory", "inventory-artifact.json"),
                ctx.run().output().readLatest("02-build", "build-model.json"),
                ctx.run().output().readLatest("02-build", "dependency-model.json"),
                graphNode == null ? null : ApplicationGraph.fromNode(unwrap(graphNode)),
                ctx.run().output().readLatest("03-graph", "graph-verification-report.json"),
                ctx.run().output().readLatest("00-bootstrap", "source-provenance.json"));
    }

    private boolean run(com.bootshift.stages.Stage stage, List<StageSummary> stages, Consumer<StageSummary> onStage) {
        StageResult result = StageExecutor.run(stage, context());
        stages.add(summary(result));
        onStage.accept(stages.get(stages.size() - 1));
        return result.succeeded();
    }

    private static StageSummary summary(StageResult result) {
        return new StageSummary(result.stageId(), result.exitCode().name(), result.summary(), result.messages());
    }

    /** Bootshift artifacts are an envelope with the payload merged in; the registry and graph read their own fields. */
    private static JsonNode unwrap(JsonNode node) {
        return node;
    }

    /**
     * Rebuilds Bootshift's static application graph over a workspace, following the exact
     * sequence Bootshift's own GraphDiffStage uses: JavaParser code model per module, configuration
     * files, then {@link GraphBuilder}.
     */
    public static ApplicationGraph rebuildGraph(Path workspace, FileRegistry registry, JsonNode buildNode,
                                                JsonNode dependencyNode, String label) {
        BuildSystemPort.BuildModel buildModel = ApplicationGraphStage.readBuildModel(buildNode, dependencyNode);
        CodeModelPort codeModel = new JavaParserCodeModelAdapter();
        Map<String, CodeModelPort.AnalysisResult> analysisByModule = new LinkedHashMap<>();
        for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
            Path moduleRoot = ".".equals(module.moduleId()) ? workspace : workspace.resolve(module.moduleId());
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
            analysisByModule.put(module.moduleId(), codeModel.analyze(moduleRoot, sourceRoots, classpath,
                    module.effectiveJavaRelease(21)));
        }
        Map<String, String> configuration = new LinkedHashMap<>();
        for (FileRecord record : registry.active()) {
            if (!GraphBuilder.isConfigurationRole(record.getRole())) {
                continue;
            }
            Path file = workspace.resolve(record.getCurrentPath());
            if (Files.isRegularFile(file)) {
                try {
                    configuration.put(record.getCurrentPath(), Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    configuration.put(record.getCurrentPath(), "");
                }
            }
        }
        // Rebuilding attaches graph-build-scoped symbols to the registry; do it on a copy so the
        // kernel's registry is not polluted with them.
        FileRegistry scratch = FileRegistry.fromNode(registry.toNode());
        scratch.clearSymbols();
        return new GraphBuilder().build(new GraphBuilder.Input(scratch, buildModel, analysisByModule, configuration,
                label)).graph();
    }

    /**
     * Bootshift's single writer, wired to the unified run: the same workspace, registry, ledger,
     * patch store and checkpoint repository the kernel tracks.
     */
    public static FileMutationGateway gateway(RunLayout layout, FileRegistry registry, ChangeLedger ledger,
                                              FileMutationGateway.BaselineSealVerifier seal) {
        ScmPort scm = new GitScmAdapter();
        return new FileMutationGateway(layout.runId(), layout.workspace(), layout.checkpointGit(), layout.patches(),
                registry, ledger, scm, seal);
    }

    public static ScmPort scm() {
        return new GitScmAdapter();
    }
}
