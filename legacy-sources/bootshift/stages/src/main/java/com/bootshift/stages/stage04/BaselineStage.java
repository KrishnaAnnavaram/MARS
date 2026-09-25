package com.bootshift.stages.stage04;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.MavenBuildAdapter;
import com.bootshift.adapters.build.ToolchainProbe;
import com.bootshift.adapters.runtime.SpringProcessRuntimeProbe;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.GraphEdge;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.security.SensitiveValues;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.environment.EnvironmentProvider;
import com.bootshift.ports.runtime.RuntimeProbePort;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.ValidationSupport;
import com.bootshift.stages.stage03.ApplicationGraphStage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Agent 04 - Baseline Capture and Seal (spec section 15).
 *
 * <p>Observes the original application before any migration change, then seals what it saw. The seal
 * is what makes every later claim checkable: after this point no stage may rewrite the baseline to
 * make migrated behaviour look equivalent (R30), and no mutation is legal until the seal exists (R7).
 *
 * <p>Dimensions the harness cannot observe in the current environment are recorded as blind spots
 * rather than skipped silently. A missing MongoDB is a fact about the evidence, not an excuse.
 */
public final class BaselineStage implements Stage {

    public static final String OUTPUT_DIR = "04-baseline";

    private static final String JACOCO = "org.jacoco:jacoco-maven-plugin:0.8.12";

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
        return "Observe and cryptographically seal the original application's behaviour";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.GRAPH_VERIFIED);
    }

    @Override
    public RunState postcondition() {
        return RunState.BASELINE_SEALED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("02-build/build-model.json", "03-graph/application-graph.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("baseline-build.json", "baseline-tests.json", "baseline-coverage.json",
                "baseline-configuration.json", "baseline-runtime.json", "environment-equivalence.json",
                "runtime-graph-baseline.json", "application-graph-baseline-enriched.json",
                "baseline-manifest.json", "manifest.json");
    }

    private final boolean runTests;
    private final boolean runRuntime;

    public BaselineStage() {
        this(true, true);
    }

    public BaselineStage(boolean runTests, boolean runRuntime) {
        this.runTests = runTests;
        this.runRuntime = runRuntime;
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode buildNode = StageSupport.requireUpstream(context, "02-build", "build-model.json",
                "Run: harness resolve-build --repo <path>");
        JsonNode dependencyNode = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");
        JsonNode graphNode = StageSupport.requireUpstream(context, "03-graph", "application-graph.json",
                "Run: harness graph --repo <path>");
        JsonNode registryNode = StageSupport.requireUpstream(context, "03-graph", "file-registry.json",
                "Run: harness graph --repo <path>");

        BuildSystemPort.BuildModel buildModel =
                ApplicationGraphStage.readBuildModel(buildNode, dependencyNode);
        ApplicationGraph graph = ApplicationGraph.fromNode(graphNode);
        FileRegistry registry = FileRegistry.fromNode(registryNode);

        Path original = context.run().originalWorkspace();
        if (!Files.isDirectory(original)) {
            throw HarnessException.refusal(
                    "The read-only original snapshot is missing; re-run bootstrap via inventory.");
        }
        // Building inside original/ would pollute the pristine snapshot and, because its files are
        // read-only, would even propagate that attribute into target/. The baseline is therefore
        // observed in the writable OLD workspace, which is also the side the differential stage
        // later runs against.
        Path root = context.run().runtimeOldWorkspace();
        String oldWorkspaceHash = context.scm().snapshot(original, root,
                com.bootshift.stages.bootstrap.RunBootstrap.SNAPSHOT_EXCLUDES);
        Path evidenceLogs = context.run().runWorkspace().resolve("baseline-logs");

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        // ---- environment equivalence contract, established before anything is measured ----------
        // Ask for the infrastructure the graph says the application needs. Computing the list and
        // then not asking for it is why nothing was ever provisioned.
        Map<String, String> environmentRequirements =
                new LinkedHashMap<>(requirementsFrom(graph, buildModel));
        for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
            environmentRequirements.putAll(ValidationSupport.infrastructureRequirements(
                    module.moduleId(), graph, buildModel));
        }
        EnvironmentProvider.ProvisionedEnvironment environment = context.environment()
                .provision("baseline-old", environmentRequirements);
        ObjectNode equivalence = Json.obj();
        equivalence.put("provider_mode", environment.mode().name());
        equivalence.put("provider_implementation", environment.providerImplementation());
        equivalence.put("provider_version", environment.providerVersion());
        equivalence.put("fingerprint", environment.fingerprint());
        equivalence.set("attributes", Json.toTree(environment.attributes()));
        equivalence.set("equivalence_gaps", Json.toTree(environment.equivalenceGaps()));
        equivalence.set("endpoints", Json.toTree(environment.endpoints()));
        equivalence.set("requested_infrastructure", Json.toTree(environmentRequirements));
        equivalence.put("usable", environment.usable());
        writer.write("environment-equivalence.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), equivalence));
        envelope.environmentFingerprint(environment.fingerprint());
        environment.equivalenceGaps().forEach(gap -> envelope.gap(new Envelope.Gap(
                "GAP-ENV-" + Math.abs(gap.hashCode() % 1000), "ENVIRONMENT", gap,
                "Differential dimensions depending on this attribute cannot reach E4")));

        // ---- toolchain selection -----------------------------------------------------------------
        // The original application must be observed on a JDK it supports. Building Spring Boot 2.7 on
        // JDK 21 would fail inside an old annotation processor and tell us nothing about the migration.
        ToolchainProbe toolchainProbe = new ToolchainProbe();
        List<ToolchainProbe.Jdk> availableJdks = toolchainProbe.discover();
        int requiredJava = requiredJavaMajor(buildNode, buildModel);
        java.util.Optional<ToolchainProbe.Jdk> selectedJdk =
                toolchainProbe.select(requiredJava, availableJdks);
        String lombokVersion = buildModel.dependencies().stream()
                .filter(d -> "org.projectlombok".equals(d.groupId()) && "lombok".equals(d.artifactId()))
                .map(BuildSystemPort.ResolvedDependency::version)
                .findFirst().orElse(null);

        ObjectNode toolchain = Json.obj();
        toolchain.put("required_java_major", requiredJava);
        toolchain.set("available", Json.toTree(availableJdks.stream()
                .map(j -> Map.of("major", String.valueOf(j.major()), "version", j.version(),
                        "home", normalize(j.home().toString())))
                .toList()));
        selectedJdk.ifPresent(jdk -> {
            toolchain.put("selected_major", jdk.major());
            toolchain.put("selected_version", jdk.version());
            toolchain.put("selected_home", normalize(jdk.home().toString()));
            toolchain.put("exact_match", jdk.major() == requiredJava);
        });
        selectedJdk.flatMap(jdk -> ToolchainProbe.knownHazard(jdk.major(), lombokVersion))
                .ifPresent(hazard -> {
                    toolchain.put("hazard", hazard);
                    envelope.gap(new Envelope.Gap("GAP-TOOLCHAIN-001", "TOOLCHAIN", hazard,
                            "Baseline build outcomes on this toolchain may reflect the toolchain "
                                    + "rather than the application"));
                });
        if (selectedJdk.isEmpty()) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-TOOLCHAIN-001", "TOOLCHAIN",
                    "No JDK compatible with the declared Java " + requiredJava + " level was found",
                    "Every build, test and runtime observation below is therefore unreliable"));
        }
        String javaHomeOverride = selectedJdk.map(jdk -> jdk.home().toString()).orElse(null);

        // ---- build dimension --------------------------------------------------------------------
        MavenBuildAdapter maven = new MavenBuildAdapter();
        ObjectNode buildObservation = Json.obj();
        ArrayNode moduleBuilds = Json.arr();
        int buildFailures = 0;
        for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
            Path moduleRoot = moduleRoot(root, module);
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            Map<String, String> options = new LinkedHashMap<>();
            options.put("bootshift.logSink", evidenceLogs.resolve(safe(module.moduleId()) + "-build.log").toString());
            if (javaHomeOverride != null) {
                options.put("bootshift.javaHome", javaHomeOverride);
            }
            BuildSystemPort.ExecutionResult result = maven.compile(root, moduleRoot, options);
            ObjectNode node = Json.obj();
            node.put("module", module.moduleId());
            node.put("success", result.success());
            node.put("exit_code", result.exitCode());
            node.put("duration_ms", result.duration().toMillis());
            node.put("command", result.command());
            node.set("diagnostics", Json.toTree(diagnostics(result)));
            moduleBuilds.add(node);
            if (!result.success()) {
                buildFailures++;
            }
        }
        buildObservation.put("modules_built", moduleBuilds.size());
        buildObservation.put("failures", buildFailures);
        buildObservation.set("modules", moduleBuilds);
        buildObservation.set("toolchain", toolchain);
        writer.write("baseline-build.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), buildObservation));

        // ---- test and coverage dimensions -------------------------------------------------------
        ObjectNode testObservation = Json.obj();
        ObjectNode coverageObservation = Json.obj();
        ArrayNode moduleTests = Json.arr();
        ArrayNode moduleCoverage = Json.arr();
        int testFailures = 0;
        int totalTests = 0;
        if (runTests) {
            for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
                Path moduleRoot = moduleRoot(root, module);
                if (!Files.isDirectory(moduleRoot)) {
                    continue;
                }
                ValidationSupport.TestRun run = ValidationSupport.runTests(maven, moduleRoot,
                        evidenceLogs.resolve(safe(module.moduleId()) + "-test.log"), javaHomeOverride);
                BuildSystemPort.ExecutionResult result = run.execution();
                TestOutcome outcome = parseSurefire(moduleRoot);
                totalTests += outcome.total();
                testFailures += outcome.failed() + outcome.errors();
                ObjectNode node = Json.obj();
                node.put("module", module.moduleId());
                node.put("executed", result.success());
                node.put("exit_code", result.exitCode());
                node.put("duration_ms", result.duration().toMillis());
                node.put("tests", outcome.total());
                node.put("passed", outcome.passed());
                node.put("failed", outcome.failed());
                node.put("errors", outcome.errors());
                node.put("skipped", outcome.skipped());
                node.set("cases", Json.toTree(outcome.cases()));
                moduleTests.add(node);

                node.put("retried_without_coverage", run.retriedWithoutCoverage());

                CoverageOutcome coverage = run.coverageUsable() ? parseJacoco(moduleRoot)
                        : new CoverageOutcome(false, 0, 0, 0, run.coverageUnavailableReason());
                ObjectNode coverageNode = Json.obj();
                coverageNode.put("module", module.moduleId());
                coverageNode.put("available", coverage.available());
                coverageNode.put("instruction_covered_ratio", coverage.instructionRatio());
                coverageNode.put("branch_covered_ratio", coverage.branchRatio());
                coverageNode.put("line_covered_ratio", coverage.lineRatio());
                coverageNode.put("detail", coverage.detail());
                moduleCoverage.add(coverageNode);
            }
        }
        testObservation.put("executed", runTests);
        testObservation.put("total_tests", totalTests);
        testObservation.put("failing_tests", testFailures);
        testObservation.set("modules", moduleTests);
        writer.write("baseline-tests.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), testObservation));

        coverageObservation.put("measured", runTests);
        coverageObservation.set("modules", moduleCoverage);
        writer.write("baseline-coverage.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), coverageObservation));

        // ---- configuration dimension ------------------------------------------------------------
        ObjectNode configuration = captureConfiguration(registry, root);
        writer.write("baseline-configuration.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), configuration));

        // ---- runtime dimension ------------------------------------------------------------------
        ObjectNode runtime = Json.obj();
        ArrayNode runtimeModules = Json.arr();
        List<RuntimeProbePort.ProbeResult> probeResults = new ArrayList<>();
        int started = 0;
        if (runRuntime) {
            SpringProcessRuntimeProbe probe = new SpringProcessRuntimeProbe(evidenceLogs);
            for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
                Path moduleRoot = moduleRoot(root, module);
                if (!Files.isDirectory(moduleRoot)) {
                    continue;
                }
                if (!probe.available(moduleRoot)) {
                    // Packaging is required before a runtime probe; compile alone does not produce one.
                    Map<String, String> packageOptions = new LinkedHashMap<>();
                    if (javaHomeOverride != null) {
                        packageOptions.put("bootshift.javaHome", javaHomeOverride);
                    }
                    maven.invoke(moduleRoot, List.of("-B", "-DskipTests", "package"), packageOptions);
                }
                Map<String, String> settings = new LinkedHashMap<>();
                settings.put("fingerprint", environment.fingerprint());
                if (javaHomeOverride != null) {
                    settings.put("bootshift.javaHome", javaHomeOverride);
                }
                settings.putAll(ValidationSupport.runtimeSettings(module.moduleId(), graph, buildModel));
                List<String> external =
                        ValidationSupport.externalDependencies(module.moduleId(), graph, buildModel);
                if (!external.isEmpty()) {
                    envelope.gap(new Envelope.Gap("GAP-INFRA-" + safe(module.moduleId()).toUpperCase(
                            java.util.Locale.ROOT), "INFRASTRUCTURE",
                            "Module " + module.moduleId() + " depends on external infrastructure: "
                                    + external,
                            "Dimensions requiring that infrastructure cannot be observed unless the "
                                    + "environment provider supplies it"));
                }
                RuntimeProbePort.ProbeResult result = probe.probe(root, moduleRoot, module.moduleId(), settings);
                probeResults.add(result);
                if (result.started()) {
                    started++;
                }
                runtimeModules.add(renderProbe(result));
            }
        }
        runtime.put("attempted", runRuntime);
        runtime.put("modules_started", started);
        runtime.put("modules_attempted", runtimeModules.size());
        runtime.set("modules", runtimeModules);
        writer.write("baseline-runtime.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), runtime));

        for (RuntimeProbePort.ProbeResult result : probeResults) {
            if (!result.started()) {
                envelope.blindSpot(new Envelope.BlindSpot(
                        "BS-RUNTIME-" + safe(result.module()).toUpperCase(java.util.Locale.ROOT),
                        "RUNTIME", "Module " + result.module() + " did not start during baseline capture",
                        result.failureReason() == null ? "unknown" : result.failureReason()));
            }
        }

        // ---- runtime graph enrichment (R27) -----------------------------------------------------
        ApplicationGraph enriched = ApplicationGraph.fromNode(graphNode);
        ObjectNode runtimeGraph = enrichRuntimeGraph(context, enriched, probeResults);
        enriched.label("G0_BASELINE_ENRICHED");
        writer.write("runtime-graph-baseline.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), runtimeGraph));
        writer.write("application-graph-baseline-enriched.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), enriched.toNode()));

        // ---- seal --------------------------------------------------------------------------------
        JsonNode bootstrap = StageSupport.optionalUpstream(context, "00-bootstrap", "bootstrap.json");
        String originalTreeHash = bootstrap == null ? null
                : bootstrap.path("original_snapshot_hash").asText(null);

        Map<String, String> observationHashes = new TreeMap<>();
        observationHashes.put("build", Json.canonicalHash(buildObservation));
        observationHashes.put("tests", Json.canonicalHash(testObservation));
        observationHashes.put("coverage", Json.canonicalHash(coverageObservation));
        observationHashes.put("configuration", Json.canonicalHash(configuration));
        observationHashes.put("runtime", Json.canonicalHash(runtime));
        observationHashes.put("runtime_graph", Json.canonicalHash(runtimeGraph));
        observationHashes.put("static_graph", graph.structuralHash());
        observationHashes.put("toolchain", Json.canonicalHash(toolchain));

        ObjectNode manifest = Json.obj();
        manifest.put("original_tree_hash", originalTreeHash);
        manifest.put("old_workspace_hash", oldWorkspaceHash);
        manifest.put("old_workspace_matches_original", oldWorkspaceHash.equals(originalTreeHash));
        manifest.put("source_commit", bootstrap == null ? null
                : bootstrap.path("source_provenance").path("commitSha").asText(null));
        manifest.put("file_registry_seal", registry.sealHash());
        manifest.put("sealed_environment_fingerprint", environment.fingerprint());
        manifest.put("environment_mode", environment.mode().name());
        manifest.set("observation_hashes", Json.toTree(observationHashes));
        manifest.set("tool_digests", Json.toTree(toolDigests(buildModel)));
        manifest.put("normalization_policy_hash",
                Hashing.sha256("bootshift-normalization-policy-v1"));
        manifest.put("sealed", true);
        manifest.put("sealed_at", java.time.Instant.now().toString());

        List<String> sealLines = new ArrayList<>();
        observationHashes.forEach((k, v) -> sealLines.add(k + ":" + v));
        sealLines.add("tree:" + originalTreeHash);
        sealLines.add("registry:" + registry.sealHash());
        sealLines.add("environment:" + environment.fingerprint());
        sealLines.sort(java.util.Comparator.naturalOrder());
        String baselineHash = Hashing.manifestHash(sealLines);
        manifest.put("baseline_manifest_hash", baselineHash);

        ObjectNode manifestArtifact = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR)
                        .baselineBinding(new Envelope.BaselineBinding(baselineHash, originalTreeHash, true)),
                manifest);
        StageSupport.validate(context, writer, "baseline/baseline-manifest.schema.json",
                "baseline-manifest.json", manifestArtifact);
        writer.write("baseline-manifest.json", manifestArtifact);

        StageSupport.toEvidence(context, "baseline-manifest", manifestArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Baseline artifacts failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.BASELINE_CAPTURED,
                totalTests + " tests, " + started + " module(s) started");
        context.stateMachine().recordBaselineSeal(baselineHash);
        context.stateMachine().transition(RunState.BASELINE_SEALED, "baseline sealed as " + baselineHash);
        context.runStateStore().updateState(context.run().runId(), RunState.BASELINE_SEALED,
                "baseline sealed");
        context.runStateStore().putAttribute(context.run().runId(), "baseline_seal", baselineHash);

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        if (buildFailures > 0) {
            messages.add(buildFailures + " module(s) did not compile at baseline; that pre-existing debt "
                    + "is recorded so it is never reported as a migration regression");
        }
        if (testFailures > 0) {
            messages.add(testFailures + " pre-existing test failure(s) recorded as baseline debt");
        }
        envelope.blindSpots().forEach(b -> messages.add("BLIND SPOT " + b.id() + ": " + b.description()));

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Baseline sealed as " + baselineHash.substring(0, 16) + " ("
                        + moduleBuilds.size() + " module(s) built, " + totalTests + " test(s), "
                        + started + "/" + runtimeModules.size() + " runtime probe(s) started)",
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ dimensions

    private ObjectNode captureConfiguration(FileRegistry registry, Path root) {
        ObjectNode configuration = Json.obj();
        ArrayNode files = Json.arr();
        int sensitive = 0;
        for (FileRecord record : registry.active()) {
            if (!record.getRole().isConfiguration()) {
                continue;
            }
            Path file = root.resolve(record.getCurrentPath());
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            ObjectNode node = Json.obj();
            node.put("file_id", record.getFileId());
            node.put("path", record.getCurrentPath());
            node.put("sha256", record.getCurrentSha256());
            ArrayNode properties = Json.arr();
            for (String rawLine : content.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                ObjectNode property = Json.obj();
                property.put("key", key);
                boolean isSensitive = SensitiveValues.isSensitiveKey(key)
                        || SensitiveValues.looksSensitive(value);
                property.put("sensitive", isSensitive);
                if (isSensitive) {
                    sensitive++;
                    property.set("value", SensitiveValues.describe(key, value, "PROPERTY_FILE",
                            SensitiveValues.EvidencePolicy.NEVER_STORE_PLAINTEXT, null));
                } else {
                    property.put("value", value);
                }
                properties.add(property);
            }
            node.set("properties", properties);
            files.add(node);
        }
        configuration.put("file_count", files.size());
        configuration.put("sensitive_property_count", sensitive);
        configuration.set("files", files);
        return configuration;
    }

    private ObjectNode enrichRuntimeGraph(StageContext context, ApplicationGraph graph,
                                          List<RuntimeProbePort.ProbeResult> results) {
        ObjectNode runtimeGraph = Json.obj();
        ArrayNode edges = Json.arr();
        int added = 0;
        for (RuntimeProbePort.ProbeResult result : results) {
            if (!result.started()) {
                continue;
            }
            String moduleId = "MODULE:" + result.module();
            for (RuntimeProbePort.Observation observation : result.observations()) {
                String evidenceRef = StageSupport.toEvidence(context,
                        "runtime-" + observation.dimension().name().toLowerCase(java.util.Locale.ROOT),
                        Json.canonical(Json.toTree(observation)),
                        EvidenceManifest.Classification.INTERNAL, "RAW_OBSERVATION", OUTPUT_DIR);
                if (observation.dimension() == RuntimeProbePort.Dimension.CONTEXT) {
                    GraphNode node = new GraphNode("RUNTIME:" + result.module(),
                            NodeType.EXTERNAL_SYSTEM, result.module() + " runtime");
                    graph.addNode(node);
                    if (graph.addEdge(new GraphEdge(moduleId, EdgeType.ACTIVE_UNDER_PROFILE,
                            node.getId(), "RuntimeGraph").setEvidenceRef(evidenceRef))) {
                        added++;
                        edges.add(renderRuntimeEdge(moduleId, EdgeType.ACTIVE_UNDER_PROFILE,
                                node.getId(), evidenceRef));
                    }
                }
            }
            for (RuntimeProbePort.BoundProperty property : result.boundProperties()) {
                String propertyId = "PROPERTY:" + property.canonicalKey();
                graph.addNode(new GraphNode(propertyId, NodeType.CONFIG_PROPERTY, property.canonicalKey())
                        .setFqn(property.canonicalKey()));
                if (graph.addEdge(new GraphEdge(moduleId, EdgeType.ACTUALLY_BINDS_PROPERTY, propertyId,
                        "RuntimeGraph").property("defaulted", property.defaulted()))) {
                    added++;
                    edges.add(renderRuntimeEdge(moduleId, EdgeType.ACTUALLY_BINDS_PROPERTY,
                            propertyId, null));
                }
            }
        }
        runtimeGraph.put("edge_count", added);
        runtimeGraph.put("layer", "RUNTIME_OBSERVATION");
        runtimeGraph.put("note", "Runtime edges never overwrite static relationships; both layers "
                + "coexist so a report can say whether a relationship was inferred or observed.");
        runtimeGraph.set("edges", edges);
        return runtimeGraph;
    }

    private ObjectNode renderRuntimeEdge(String from, EdgeType type, String to, String evidenceRef) {
        ObjectNode node = Json.obj();
        node.put("from", from);
        node.put("type", type.name());
        node.put("to", to);
        node.put("evidence_ref", evidenceRef);
        return node;
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
        node.set("bound_properties", Json.toTree(result.boundProperties()));
        node.set("unobservable", Json.toTree(result.unobservable()));
        return node;
    }

    // ------------------------------------------------------------------ parsing helpers

    public record TestCase(String className, String name, String outcome, double durationSeconds, String detail) {
    }

    public record TestOutcome(int total, int passed, int failed, int errors, int skipped, List<TestCase> cases) {
    }

    /**
     * Parses whichever test reports the module's build system produced.
     *
     * <p>Maven writes Surefire XML under {@code target/surefire-reports}; Gradle writes JUnit XML
     * under {@code build/test-results/test}. Reading only the Maven location meant a Gradle module
     * reported zero tests, and zero tests reads as "nothing regressed".
     */
    public static TestOutcome parseTestReports(Path moduleRoot) {
        TestOutcome maven = parseSurefire(moduleRoot);
        TestOutcome gradle = parseGradleResults(moduleRoot);
        if (gradle.total() == 0) {
            return maven;
        }
        if (maven.total() == 0) {
            return gradle;
        }
        List<TestCase> combined = new ArrayList<>(maven.cases());
        combined.addAll(gradle.cases());
        return new TestOutcome(maven.total() + gradle.total(), maven.passed() + gradle.passed(),
                maven.failed() + gradle.failed(), maven.errors() + gradle.errors(),
                maven.skipped() + gradle.skipped(), combined);
    }

    /** Parses Gradle's JUnit XML output. Same schema as Surefire, different directory. */
    public static TestOutcome parseGradleResults(Path moduleRoot) {
        for (String candidate : List.of("build/test-results/test", "build/test-results")) {
            Path reports = moduleRoot.resolve(candidate);
            if (Files.isDirectory(reports)) {
                TestOutcome outcome = parseJUnitXml(reports);
                if (outcome.total() > 0) {
                    return outcome;
                }
            }
        }
        return new TestOutcome(0, 0, 0, 0, 0, List.of());
    }

    /** Parses Surefire XML reports. Absent reports mean no tests ran, which is itself an observation. */
    public static TestOutcome parseSurefire(Path moduleRoot) {
        Path reports = moduleRoot.resolve("target/surefire-reports");
        return parseJUnitXml(reports);
    }

    private static TestOutcome parseJUnitXml(Path reports) {
        List<TestCase> cases = new ArrayList<>();
        int total = 0;
        int failed = 0;
        int errors = 0;
        int skipped = 0;
        if (!Files.isDirectory(reports)) {
            return new TestOutcome(0, 0, 0, 0, 0, cases);
        }
        try (var stream = Files.list(reports)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                String xml = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = java.util.regex.Pattern.compile(
                                "<testcase\\s+([^>]*?)(/>|>(.*?)</testcase>)",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(xml);
                while (matcher.find()) {
                    total++;
                    String attributes = matcher.group(1);
                    String body = matcher.group(3) == null ? "" : matcher.group(3);
                    String name = attribute(attributes, "name");
                    String className = attribute(attributes, "classname");
                    double time = parseDouble(attribute(attributes, "time"));
                    String outcome = "PASSED";
                    String detail = null;
                    if (body.contains("<failure")) {
                        outcome = "FAILED";
                        failed++;
                        detail = firstLine(body);
                    } else if (body.contains("<error")) {
                        outcome = "ERROR";
                        errors++;
                        detail = firstLine(body);
                    } else if (body.contains("<skipped")) {
                        outcome = "SKIPPED";
                        skipped++;
                    }
                    cases.add(new TestCase(className, name, outcome, time, detail));
                }
            }
        } catch (IOException e) {
            return new TestOutcome(total, total - failed - errors - skipped, failed, errors, skipped, cases);
        }
        return new TestOutcome(total, total - failed - errors - skipped, failed, errors, skipped, cases);
    }

    public record CoverageOutcome(boolean available, double instructionRatio, double branchRatio,
                           double lineRatio, String detail) {
    }

    /** Reads the JaCoCo XML report produced by the CLI-attached agent. */
    public static CoverageOutcome parseJacoco(Path moduleRoot) {
        Path report = moduleRoot.resolve("target/site/jacoco/jacoco.xml");
        if (!Files.isRegularFile(report)) {
            return new CoverageOutcome(false, 0, 0, 0,
                    "No JaCoCo report at target/site/jacoco/jacoco.xml");
        }
        try {
            String xml = Files.readString(report, StandardCharsets.UTF_8);
            int lastReportCounters = xml.lastIndexOf("</package>");
            String tail = lastReportCounters < 0 ? xml : xml.substring(lastReportCounters);
            return new CoverageOutcome(true,
                    ratio(tail, "INSTRUCTION"), ratio(tail, "BRANCH"), ratio(tail, "LINE"), null);
        } catch (IOException e) {
            return new CoverageOutcome(false, 0, 0, 0, "Report unreadable: " + e.getMessage());
        }
    }

    private static double ratio(String xml, String counterType) {
        var matcher = java.util.regex.Pattern.compile(
                        "<counter type=\"" + counterType + "\" missed=\"(\\d+)\" covered=\"(\\d+)\"")
                .matcher(xml);
        long missed = 0;
        long covered = 0;
        while (matcher.find()) {
            missed += Long.parseLong(matcher.group(1));
            covered += Long.parseLong(matcher.group(2));
        }
        long totalCount = missed + covered;
        return totalCount == 0 ? 0.0 : Math.round((double) covered / totalCount * 10000.0) / 10000.0;
    }

    private static String attribute(String attributes, String name) {
        var matcher = java.util.regex.Pattern.compile(name + "=\"([^\"]*)\"").matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static double parseDouble(String value) {
        try {
            return value == null ? 0.0 : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String firstLine(String body) {
        var matcher = java.util.regex.Pattern.compile("message=\"([^\"]{0,300})\"").matcher(body);
        return matcher.find() ? matcher.group(1) : body.strip().lines().findFirst().orElse("");
    }

    private List<String> diagnostics(BuildSystemPort.ExecutionResult result) {
        List<String> diagnostics = new ArrayList<>();
        result.stdoutTail().stream().filter(l -> l.contains("[ERROR]") || l.contains("[WARNING]"))
                .limit(60).forEach(diagnostics::add);
        result.stderrTail().stream().limit(20).forEach(diagnostics::add);
        return diagnostics;
    }

    private Map<String, String> toolDigests(BuildSystemPort.BuildModel model) {
        Map<String, String> digests = new TreeMap<>();
        digests.put("build_tool", String.valueOf(model.toolVersion()));
        digests.put("jdk", System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
        digests.put("harness", "bootshift 1.0.0");
        digests.put("coverage", JACOCO);
        return digests;
    }

    private Map<String, String> requirementsFrom(ApplicationGraph graph, BuildSystemPort.BuildModel model) {
        Map<String, String> requirements = new LinkedHashMap<>();
        model.dependencies().stream()
                .filter(d -> "org.springframework.boot".equals(d.groupId())
                        && "spring-boot".equals(d.artifactId()))
                .findFirst()
                .ifPresent(d -> requirements.put("spring.boot.version", d.version()));
        graph.nodesOfType(NodeType.EXTERNAL_SYSTEM).forEach(n ->
                requirements.put("infrastructure." + n.getName(), "required"));
        graph.nodesOfType(NodeType.MESSAGE_DESTINATION).forEach(n ->
                requirements.put("infrastructure." + n.getName(), "required"));
        return requirements;
    }

    /** The Java language level the application declares, defaulting to 17 when unspecified. */
    static int requiredJavaMajor(JsonNode buildNode, BuildSystemPort.BuildModel buildModel) {
        String declared = buildNode.path("frameworks").path("java").asText(null);
        if (declared == null || declared.isBlank()) {
            declared = buildModel.modules().stream()
                    .map(BuildSystemPort.ModuleModel::javaVersion)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse("17");
        }
        return ToolchainProbe.majorOf(declared);
    }

    private static String normalize(String path) {
        return path.replace((char) 92, '/');
    }

    private static Path moduleRoot(Path root, BuildSystemPort.ModuleModel module) {
        return ".".equals(module.moduleId()) ? root : root.resolve(module.moduleId());
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
