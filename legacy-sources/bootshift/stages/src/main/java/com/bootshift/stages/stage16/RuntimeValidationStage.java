package com.bootshift.stages.stage16;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.MavenBuildAdapter;
import com.bootshift.adapters.build.ToolchainProbe;
import com.bootshift.adapters.runtime.SpringProcessRuntimeProbe;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.GraphEdge;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.environment.EnvironmentProvider;
import com.bootshift.ports.characterization.Scenario;
import com.bootshift.ports.characterization.ScenarioObservation;
import com.bootshift.ports.runtime.RuntimeProbePort;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.EdgeToolchain;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.ValidationSupport;
import com.bootshift.stages.stage03.ApplicationGraphStage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 16 - Runtime Validation and Runtime Graph Enrichment (spec section 32).
 *
 * <p>Starts the migrated application and observes it. The most valuable output is not "it started" -
 * that is one observation, not success (R19) - but the bound-property provenance, which is what makes
 * {@code PROPERTY_SILENTLY_IGNORED} detectable at all.
 *
 * <p>Runtime observations become an edge-scoped runtime graph. Those edges are a distinct evidence
 * layer from the static graph (R27) and each one references the observation that produced it.
 */
public final class RuntimeValidationStage implements Stage {

    public static final String OUTPUT_DIR = "16-runtime";

    private final String edgeId;

    public RuntimeValidationStage(String edgeId) {
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
        return "Start the migrated application, observe runtime behaviour and enrich the runtime graph";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.EDGE_SCOPE_VERIFIED);
    }

    @Override
    public RunState postcondition() {
        return RunState.EDGE_RUNTIME_GRAPH_ENRICHED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("11-plan/edge-plan.json", "14-graph-diff/application-graph-current.json",
                "04-baseline/baseline-runtime.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("runtime-report.json", "scenario-observations-new.json", "configuration-binding.json",
                "runtime-graph-current.json", "application-graph-current-enriched.json",
                "silently-ignored-properties.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode edgePlanArtifact = StageSupport.requireUpstream(context, "11-plan", "edge-plan.json",
                "Run: harness plan");
        JsonNode edgePlan = EdgeSupport.findEdge(edgePlanArtifact, edgeId);
        JsonNode graphNode = StageSupport.requireUpstream(context, "14-graph-diff",
                "application-graph-current.json", "Run: harness migrate");
        JsonNode baselineRuntime = StageSupport.optionalUpstream(context, "04-baseline",
                "baseline-runtime.json");
        JsonNode buildNode = StageSupport.requireUpstream(context, "02-build", "build-model.json",
                "Run: harness resolve-build --repo <path>");
        JsonNode dependencyNode = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");

        if (!edgePlan.path("runtime_required").asBoolean(true)) {
            context.stateMachine().transition(RunState.EDGE_RUNTIME_VALIDATED, "not required");
            context.stateMachine().transition(RunState.EDGE_RUNTIME_GRAPH_ENRICHED, "not required");
            return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                    "Runtime validation not required at depth "
                            + edgePlan.path("frozen_validation_depth").asText(),
                    List.of(), Map.of(), null);
        }

        BuildSystemPort.BuildModel buildModel =
                ApplicationGraphStage.readBuildModel(buildNode, dependencyNode);
        ApplicationGraph graph = ApplicationGraph.fromNode(graphNode);
        Path workspace = context.run().migrationWorkspace();
        Path logs = context.run().runWorkspace().resolve("runtime-logs").resolve(safe(edgeId));

        EnvironmentProvider.ProvisionedEnvironment environment = context.environment()
                .provision("runtime-new-" + edgeId, requirements(buildModel));

        // The frozen edge toolchain, not a hardcoded 17 and not whatever java is on PATH. The
        // application must be packaged and started on the JDK the edge selected, or the runtime
        // observation is about a different toolchain than the one being migrated to.
        EdgeToolchain toolchain = EdgeToolchain.forEdge(edgePlan, buildModel);
        EdgeToolchain.Verification toolchainVerification = toolchain.verify();
        String javaHome = toolchain.resolved().javaHome();
        MavenBuildAdapter maven = new MavenBuildAdapter();
        SpringProcessRuntimeProbe probe = new SpringProcessRuntimeProbe(logs);

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId)
                .environmentFingerprint(environment.fingerprint());
        environment.equivalenceGaps().forEach(gap -> envelope.gap(new Envelope.Gap(
                "GAP-ENV-" + Math.abs(gap.hashCode() % 1000), "ENVIRONMENT", gap,
                "Runtime observations affected by this attribute are weaker evidence")));

        List<RuntimeProbePort.ProbeResult> results = new ArrayList<>();
        ArrayNode moduleNodes = Json.arr();
        int started = 0;

        for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
            Path moduleRoot = ".".equals(module.moduleId()) ? workspace
                    : workspace.resolve(module.moduleId());
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            Map<String, String> packageOptions = new LinkedHashMap<>();
            packageOptions.putAll(toolchain.buildOptions(null));
            maven.invoke(moduleRoot, List.of("-B", "-DskipTests", "package"), packageOptions);

            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("fingerprint", environment.fingerprint());
            settings.putAll(toolchain.buildOptions(null));
            settings.putAll(ValidationSupport.runtimeSettings(module.moduleId(), graph, buildModel));

            RuntimeProbePort.ProbeResult result =
                    probe.probe(workspace, moduleRoot, module.moduleId(), settings);
            results.add(result);
            if (result.started()) {
                started++;
            } else {
                envelope.blindSpot(new Envelope.BlindSpot(
                        "BS-RUNTIME-" + safe(module.moduleId()).toUpperCase(java.util.Locale.ROOT),
                        "RUNTIME", "Module " + module.moduleId() + " did not start after migration",
                        result.failureReason() == null ? "unknown" : result.failureReason()));
            }
            moduleNodes.add(renderProbe(result));
        }

        // ---- configuration binding provenance (spec section 32) ---------------------------------
        ObjectNode binding = Json.obj();
        ArrayNode boundProperties = Json.arr();
        Map<String, Set<String>> boundByModule = new LinkedHashMap<>();
        for (RuntimeProbePort.ProbeResult result : results) {
            Set<String> keys = new LinkedHashSet<>();
            for (RuntimeProbePort.BoundProperty property : result.boundProperties()) {
                keys.add(property.canonicalKey());
                ObjectNode node = Json.obj();
                node.put("module", result.module());
                node.put("canonical_key", property.canonicalKey());
                node.put("source_file", property.sourceFile());
                node.put("source_type", property.sourceType());
                node.put("target_type", property.targetType());
                node.put("target_field", property.targetField());
                node.put("bound", property.bound());
                node.put("defaulted", property.defaulted());
                node.put("deprecated", property.deprecated());
                node.put("replacement", property.replacement());
                node.put("sensitive", property.sensitive());
                boundProperties.add(node);
            }
            boundByModule.put(result.module(), keys);
        }
        binding.put("bound_property_count", boundProperties.size());
        binding.put("rule", "Values alone are not enough: the harness records the canonical key, the "
                + "source, the target field, and whether the value was bound or defaulted");
        binding.set("properties", boundProperties);
        writer.write("configuration-binding.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId), binding));

        // ---- silently ignored properties ---------------------------------------------------------
        ObjectNode silentlyIgnored = detectSilentlyIgnored(context, graph, boundByModule,
                baselineRuntime, results);
        writer.write("silently-ignored-properties.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        silentlyIgnored));
        int ignoredCount = silentlyIgnored.path("count").asInt();
        if (ignoredCount > 0) {
            envelope.gap(new Envelope.Gap("GAP-RUNTIME-001", "CONFIGURATION_BINDING",
                    ignoredCount + " configured property(ies) are present in configuration but no "
                            + "longer bound after migration",
                    "Each one is a first-class PROPERTY_SILENTLY_IGNORED observation for the "
                            + "differential stage"));
        }

        // ---- runtime graph enrichment ------------------------------------------------------------
        ApplicationGraph enriched = ApplicationGraph.fromNode(graphNode);
        ObjectNode runtimeGraph = enrich(context, enriched, results);
        enriched.label("G_EDGE_ENRICHED:" + edgeId);
        writer.write("runtime-graph-current.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        runtimeGraph));
        writer.write("application-graph-current-enriched.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        enriched.toNode()));

        ObjectNode report = Json.obj();
        report.put("modules_started", started);
        report.put("modules_attempted", moduleNodes.size());
        report.put("environment_mode", environment.mode().name());
        report.put("environment_provider", environment.providerImplementation());
        report.put("environment_fingerprint_value", environment.fingerprint());
        report.set("equivalence_gaps", Json.toTree(environment.equivalenceGaps()));
        report.set("modules", moduleNodes);
        report.put("note", "Successful context startup is one runtime observation, not migration "
                + "success (R19)");
        ObjectNode reportArtifact = StageSupport.compose(envelope
                .stat("modules_started", started)
                .stat("silently_ignored_properties", ignoredCount), report);
        StageSupport.validate(context, writer, "validation/runtime-report.schema.json",
                "runtime-report.json", reportArtifact);
        writer.write("runtime-report.json", reportArtifact);

        StageSupport.toEvidence(context, "runtime-report", reportArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        // ---------------------------------------------------------------- frozen scenarios on NEW
        //
        // The same scenario objects that were executed against the original application, executed
        // now against the migrated one. This is what differential validation compares; without it
        // the comparison has nothing to work from but Actuator metadata, which describes the shape
        // of a context rather than the behaviour of an endpoint.
        ObjectNode scenarioArtifact = executeFrozenScenarios(context, edgePlan, buildModel, graph,
                toolchain, envelope);
        writer.write("scenario-observations-new.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        scenarioArtifact));

        String hash = StageSupport.publishForEdge(context, writer, edgeId, OUTPUT_DIR,
                com.bootshift.stages.EdgeIndex.Phase.RUNTIME_VALIDATED, "published");
        context.stateMachine().transition(RunState.EDGE_RUNTIME_VALIDATED,
                started + " module(s) started");
        context.stateMachine().transition(RunState.EDGE_RUNTIME_GRAPH_ENRICHED,
                runtimeGraph.path("edge_count").asInt() + " runtime edge(s)");
        context.runStateStore().updateState(context.run().runId(),
                RunState.EDGE_RUNTIME_GRAPH_ENRICHED, "runtime validated for " + edgeId);
        EdgeSupport.checkpoint(context, edgeId, "runtime-validated",
                "Edge " + edgeId + " runtime validated");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        results.stream().filter(r -> !r.started()).forEach(r ->
                messages.add("did not start: " + r.module() + " - " + r.failureReason()));
        if (ignoredCount > 0) {
            messages.add(ignoredCount + " property(ies) stopped binding after migration");
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Edge " + edgeId + ": " + started + "/" + moduleNodes.size()
                        + " module(s) started, " + boundProperties.size() + " bound property(ies), "
                        + runtimeGraph.path("edge_count").asInt() + " runtime graph edge(s)",
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Detects properties that are configured but no longer bound.
     *
     * <p>This is the runtime half of PROPERTY_SILENTLY_IGNORED: the configuration metadata diff shows
     * deprecation, but only a running application shows that a key stopped taking effect.
     */
    private ObjectNode detectSilentlyIgnored(StageContext context, ApplicationGraph graph,
                                             Map<String, Set<String>> boundByModule,
                                             JsonNode baselineRuntime,
                                             List<RuntimeProbePort.ProbeResult> results) {
        ObjectNode artifact = Json.obj();
        ArrayNode findings = Json.arr();

        Map<String, Set<String>> baselineBound = new LinkedHashMap<>();
        if (baselineRuntime != null) {
            for (JsonNode module : baselineRuntime.path("modules")) {
                Set<String> keys = new LinkedHashSet<>();
                module.path("bound_properties")
                        .forEach(p -> keys.add(p.path("canonicalKey").asText()));
                baselineBound.put(module.path("module").asText(), keys);
            }
        }

        for (RuntimeProbePort.ProbeResult result : results) {
            if (!result.started()) {
                continue;
            }
            Set<String> nowBound = boundByModule.getOrDefault(result.module(), Set.of());
            Set<String> wasBound = baselineBound.getOrDefault(result.module(), Set.of());

            // Configured keys the module declares, from the static configuration graph.
            for (GraphNode property : graph.nodesOfType(NodeType.CONFIG_PROPERTY)) {
                if (!result.module().equals(property.getModule())) {
                    continue;
                }
                String key = property.getFqn() == null ? property.getName() : property.getFqn();
                boolean configured = key != null;
                boolean boundNow = nowBound.stream().anyMatch(b -> b.equals(key) || b.startsWith(key));
                boolean boundBefore = wasBound.stream().anyMatch(b -> b.equals(key) || b.startsWith(key));
                if (configured && boundBefore && !boundNow) {
                    ObjectNode finding = Json.obj();
                    finding.put("module", result.module());
                    finding.put("canonical_key", key);
                    finding.put("bound_at_baseline", true);
                    finding.put("bound_after_migration", false);
                    finding.put("classification", "PROPERTY_SILENTLY_IGNORED");
                    finding.put("detail", "The key is still present in configuration but the migrated "
                            + "application no longer binds it. Nothing failed; the value simply stopped "
                            + "taking effect.");
                    findings.add(finding);
                }
            }
        }
        artifact.put("count", findings.size());
        artifact.put("method", "static configuration graph x runtime bound-property set, compared "
                + "against the sealed baseline");
        artifact.set("findings", findings);
        return artifact;
    }

    /** Converts validated runtime observations into runtime-layer graph edges. */
    private ObjectNode enrich(StageContext context, ApplicationGraph graph,
                              List<RuntimeProbePort.ProbeResult> results) {
        ObjectNode artifact = Json.obj();
        ArrayNode edges = Json.arr();
        int added = 0;

        for (RuntimeProbePort.ProbeResult result : results) {
            if (!result.started()) {
                continue;
            }
            String moduleId = "MODULE:" + result.module();
            if (graph.node(moduleId).isEmpty()) {
                continue;
            }
            for (RuntimeProbePort.Observation observation : result.observations()) {
                String evidenceRef = StageSupport.toEvidence(context,
                        "runtime-" + observation.dimension().name().toLowerCase(java.util.Locale.ROOT),
                        Json.canonical(Json.toTree(observation)),
                        EvidenceManifest.Classification.INTERNAL, "RAW_OBSERVATION", OUTPUT_DIR);
                EdgeType type = switch (observation.dimension()) {
                    case REQUEST_MAPPINGS -> EdgeType.ACTUALLY_HANDLES_ENDPOINT;
                    case BEANS -> EdgeType.ACTUALLY_INJECTED;
                    case PROFILES, CONTEXT -> EdgeType.ACTIVE_UNDER_PROFILE;
                    case EXTERNAL_CLIENT -> EdgeType.ACTUALLY_CALLS_EXTERNAL;
                    case MESSAGING -> EdgeType.ACTUALLY_PUBLISHES_TO;
                    default -> null;
                };
                if (type == null) {
                    continue;
                }
                String observationId = "RUNTIME_OBSERVATION:" + result.module() + ":"
                        + observation.dimension().name();
                graph.addNode(new GraphNode(observationId, NodeType.EXTERNAL_SYSTEM,
                        observation.dimension().name()).setModule(result.module())
                        .property("evidence_ref", evidenceRef)
                        .property("layer", "RUNTIME_OBSERVATION"));
                if (graph.addEdge(new GraphEdge(moduleId, type, observationId, "RuntimeGraph")
                        .setEvidenceRef(evidenceRef))) {
                    added++;
                    ObjectNode edgeNode = Json.obj();
                    edgeNode.put("from", moduleId);
                    edgeNode.put("type", type.name());
                    edgeNode.put("to", observationId);
                    edgeNode.put("evidence_ref", evidenceRef);
                    edges.add(edgeNode);
                }
            }
            for (RuntimeProbePort.BoundProperty property : result.boundProperties()) {
                String propertyId = "PROPERTY:" + property.canonicalKey();
                graph.addNode(new GraphNode(propertyId, NodeType.CONFIG_PROPERTY,
                        property.canonicalKey()).setFqn(property.canonicalKey()));
                if (graph.addEdge(new GraphEdge(moduleId, EdgeType.ACTUALLY_BINDS_PROPERTY,
                        propertyId, "RuntimeGraph").property("defaulted", property.defaulted()))) {
                    added++;
                }
            }
        }

        artifact.put("layer", "RUNTIME_OBSERVATION");
        artifact.put("edge_count", added);
        artifact.put("rule", "Runtime edges never overwrite static relationships. Both layers coexist "
                + "so a report can say whether a relationship was inferred or observed (R27).");
        artifact.set("edges", edges);
        return artifact;
    }

    private ObjectNode renderProbe(RuntimeProbePort.ProbeResult result) {
        ObjectNode node = Json.obj();
        node.put("module", result.module());
        node.put("started", result.started());
        node.put("failure_reason", result.failureReason());
        node.put("duration_ms", result.durationMillis());
        node.put("observation_count", result.observations().size());
        node.put("bound_property_count", result.boundProperties().size());
        node.set("observations", Json.toTree(result.observations()));
        node.set("unobservable", Json.toTree(result.unobservable()));
        return node;
    }

    private Map<String, String> requirements(BuildSystemPort.BuildModel buildModel) {
        Map<String, String> requirements = new LinkedHashMap<>();
        buildModel.dependencies().stream()
                .filter(d -> "org.springframework.boot".equals(d.groupId())
                        && "spring-boot".equals(d.artifactId()))
                .findFirst()
                .ifPresent(d -> requirements.put("spring.boot.version", d.version()));
        return requirements;
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------ scenario execution on NEW

    /**
     * Executes every frozen scenario against the migrated application.
     *
     * <p>Only scenarios that are actually oracles are executed: a scenario still in
     * AWAITING_OLD_OBSERVATION has no expected value, so running it against NEW would produce a
     * number with nothing to compare it to, and recording that as evidence would be worse than
     * recording nothing.
     */
    private ObjectNode executeFrozenScenarios(StageContext context, JsonNode edgePlan,
                                              BuildSystemPort.BuildModel buildModel,
                                              ApplicationGraph graph, EdgeToolchain toolchain,
                                              Envelope envelope) {
        ObjectNode artifact = Json.obj();
        JsonNode scenarioSource = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-scenarios.json");
        if (scenarioSource == null) {
            artifact.put("count", 0);
            artifact.put("reason", "No executable scenarios were produced by characterization");
            artifact.set("observations", Json.arr());
            envelope.blindSpot(new Envelope.BlindSpot("BS-SCENARIO-NONE", "CHARACTERIZATION",
                    "No executable scenarios exist for this run",
                    "Differential validation has no behavioural contract to compare, so no "
                            + "dimension can reach E4"));
            return artifact;
        }

        List<Scenario> executable = new ArrayList<>();
        int notOracles = 0;
        for (JsonNode node : scenarioSource.path("scenarios")) {
            Scenario scenario = Scenario.fromNode(node);
            if (scenario.state().isOracle()) {
                executable.add(scenario);
            } else {
                notOracles++;
            }
        }

        List<ScenarioObservation> observations = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<String> modules = new LinkedHashSet<>();
        executable.forEach(scenario -> modules.add(scenario.module()));

        SpringProcessRuntimeProbe probe = new SpringProcessRuntimeProbe(
                context.run().runWorkspace().resolve("scenario-logs"));
        Path workspace = context.run().migrationWorkspace();
        int modulesExecuted = 0;

        for (String moduleId : modules) {
            List<Scenario> forModule = executable.stream()
                    .filter(scenario -> moduleId.equals(scenario.module()))
                    .toList();
            if (forModule.isEmpty()) {
                continue;
            }
            Path moduleRoot = ".".equals(moduleId) ? workspace : workspace.resolve(moduleId);
            if (!Files.isDirectory(moduleRoot)) {
                forModule.forEach(scenario -> observations.add(ScenarioObservation.notExecuted(
                        scenario.scenarioId(), ScenarioObservation.Side.NEW,
                        "Module " + moduleId + " is absent from the migration workspace")));
                continue;
            }
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("fingerprint", "scenario-new");
            settings.putAll(toolchain.buildOptions(null));
            settings.putAll(ValidationSupport.runtimeSettings(moduleId, graph, buildModel));

            SpringProcessRuntimeProbe.ScenarioRun run = probe.runScenarios(moduleRoot, moduleId,
                    forModule, ScenarioObservation.Side.NEW, settings);
            observations.addAll(run.observations());
            if (run.started()) {
                modulesExecuted++;
            } else {
                failures.add("Module " + moduleId + ": " + run.failureReason());
            }
        }

        long executed = observations.stream().filter(ScenarioObservation::successful).count();
        artifact.put("count", observations.size());
        artifact.put("side", "NEW");
        artifact.put("oracles_available", executable.size());
        artifact.put("scenarios_without_an_oracle", notOracles);
        artifact.put("successfully_executed", executed);
        artifact.put("modules_executed", modulesExecuted);
        artifact.set("failures", Json.toTree(failures));
        artifact.put("rule", "Only scenarios that were executed against the original application are "
                + "executed here. A scenario with no oracle produces a number with nothing to "
                + "compare it against.");
        artifact.set("observations",
                Json.toTree(observations.stream().map(ScenarioObservation::toNode).toList()));

        if (notOracles > 0) {
            envelope.gap(new Envelope.Gap("GAP-SCENARIO-ORACLE", "CHARACTERIZATION",
                    notOracles + " scenario(s) have no frozen oracle and were not executed against "
                            + "the migrated application",
                    "Those behaviours are NOT_COMPARED and cannot contribute to E4"));
        }
        if (!failures.isEmpty()) {
            envelope.gap(new Envelope.Gap("GAP-SCENARIO-EXEC", "RUNTIME",
                    failures.size() + " module(s) could not run their scenarios on the migrated side",
                    "Every scenario for those modules is NOT_COMPARED"));
        }
        return artifact;
    }
}
