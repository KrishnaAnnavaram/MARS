package com.bootshift.stages.stage10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Ids;
import com.bootshift.core.util.Json;
import com.bootshift.adapters.runtime.SpringProcessRuntimeProbe;
import com.bootshift.ports.build.BuildModelCodec;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.characterization.Scenario;
import com.bootshift.ports.characterization.ScenarioObservation;
import com.bootshift.stages.EdgeToolchain;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 10 - Characterization Engine (spec section 21).
 *
 * <p>Protects migration-sensitive behaviour <em>before</em> it is changed. For each important impact
 * the question is only ever "does adequate protection already exist?", and when it does not, a
 * harness-owned probe is scaffolded.
 *
 * <p>The critical rule: expected behaviour comes from observed original behaviour, an authoritative
 * specification, or an explicit human decision - never from invention. A probe whose expectation
 * cannot be filled from the sealed baseline is created in state
 * {@code AWAITING_OLD_OBSERVATION} and cannot be used as an oracle until it is executed against OLD.
 *
 * <p>Contracts are framework-neutral: they describe a scenario and its observed outcome, not a JUnit
 * test. That is what lets the test-infrastructure edge migrate without destroying the oracle.
 */
public final class CharacterizationStage implements Stage {

    public static final String OUTPUT_DIR = "10-characterization";

    /** Lifecycle of a characterization contract. */
    public enum ContractState {
        /** An existing application test already covers the behaviour. */
        MAPPED_TO_EXISTING_TEST,
        /** A probe exists but has not yet been executed against the original application. */
        AWAITING_OLD_OBSERVATION,
        /** Executed against OLD and the observed behaviour is frozen as the oracle. */
        FROZEN,
        /** Executed against OLD and rejected because it was not valid there. */
        REJECTED,
        /** The behaviour cannot be observed in this environment at all. */
        UNOBSERVABLE
    }

    private final AtomicLong sequence = new AtomicLong();

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
        return "Establish behavioural contracts for migration-sensitive impacts before they change";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.IMPACT_ANALYZED);
    }

    @Override
    public RunState postcondition() {
        return RunState.CHARACTERIZATION_COMPLETE;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("09-impact/impact-report.json", "03-graph/application-graph.json",
                "04-baseline/baseline-runtime.json", "04-baseline/baseline-tests.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("characterization-report.json", "characterization-contracts.json",
                "characterization-scenarios.json", "characterization-old-observations.json",
                "characterization-gaps.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode impact = StageSupport.requireUpstream(context, "09-impact", "impact-report.json",
                "Run: harness impact");
        JsonNode graphNode = StageSupport.requireUpstream(context, "03-graph", "application-graph.json",
                "Run: harness graph --repo <path>");
        JsonNode baselineRuntime = StageSupport.optionalUpstream(context, "04-baseline",
                "baseline-runtime.json");
        JsonNode baselineTests = StageSupport.optionalUpstream(context, "04-baseline",
                "baseline-tests.json");

        ApplicationGraph graph = ApplicationGraph.fromNode(graphNode);
        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        Set<String> passingTestClasses = passingTestClasses(baselineTests);
        Map<String, JsonNode> runtimeByModule = runtimeByModule(baselineRuntime);

        List<ObjectNode> contracts = new ArrayList<>();
        List<ObjectNode> gaps = new ArrayList<>();
        Map<String, Integer> byState = new java.util.TreeMap<>();

        for (JsonNode finding : impact.path("findings")) {
            String classification = finding.path("classification").asText();
            if ("UNAFFECTED_WITHIN_OBSERVED_COVERAGE".equals(classification)) {
                continue;
            }
            for (JsonNode dimensionNode : finding.path("required_validation_dimensions")) {
                String dimension = dimensionNode.asText();
                ObjectNode contract = characterize(context, finding, dimension, graph,
                        passingTestClasses, runtimeByModule);
                contracts.add(contract);
                byState.merge(contract.path("state").asText(), 1, Integer::sum);
                if (ContractState.UNOBSERVABLE.name().equals(contract.path("state").asText())) {
                    ObjectNode gap = Json.obj();
                    gap.put("gap_id", "GAP-CHAR-" + contract.path("scenario_id").asText());
                    gap.put("dimension", dimension);
                    gap.put("impact_id", finding.path("impact_id").asText());
                    gap.put("reason", contract.path("reason").asText());
                    gaps.add(gap);
                }
            }
        }

        // ---------------------------------------------------------------- executable scenarios
        //
        // The contracts above describe what should be protected. These are the executable form, and
        // they are executed here, against the ORIGINAL application, before anything is migrated.
        // A contract that is never executed protects nothing: it sits in AWAITING_OLD_OBSERVATION
        // for the life of the run while the report counts it as coverage.
        Set<String> modulesThatStart = new LinkedHashSet<>();
        runtimeByModule.forEach((module, node) -> {
            if (node.path("started").asBoolean(false)) {
                modulesThatStart.add(module);
            }
        });
        boolean externalInfrastructure = environmentSuppliesInfrastructure(context);

        ScenarioBuilder builder = new ScenarioBuilder();
        List<Scenario> scenarios = new ArrayList<>(
                builder.fromGraph(graph, modulesThatStart, externalInfrastructure));
        scenarios.addAll(builder.fromImpacts(impact, modulesThatStart, externalInfrastructure));

        ScenarioFreeze freeze = freezeAgainstOriginal(context, scenarios, modulesThatStart);
        scenarios = freeze.scenarios();

        Map<String, Integer> scenarioStates = new java.util.TreeMap<>();
        scenarios.forEach(scenario -> scenarioStates.merge(scenario.state().name(), 1, Integer::sum));

        long scenariosFrozen = scenarioStates.getOrDefault(Scenario.State.FROZEN.name(), 0);
        long scenariosUnobservable =
                scenarioStates.getOrDefault(Scenario.State.UNOBSERVABLE_WITH_EXPLICIT_GAP.name(), 0);
        long scenariosAwaiting =
                scenarioStates.getOrDefault(Scenario.State.AWAITING_OLD_OBSERVATION.name(), 0)
                        + scenarioStates.getOrDefault(Scenario.State.DRAFT.name(), 0);
        long scenariosRejected = scenarioStates.getOrDefault(Scenario.State.REJECTED.name(), 0);

        long frozen = byState.getOrDefault(ContractState.FROZEN.name(), 0);
        long awaiting = byState.getOrDefault(ContractState.AWAITING_OLD_OBSERVATION.name(), 0);
        long unobservable = byState.getOrDefault(ContractState.UNOBSERVABLE.name(), 0);
        long mapped = byState.getOrDefault(ContractState.MAPPED_TO_EXISTING_TEST.name(), 0);

        envelope.stat("scenarios", scenarios.size())
                .stat("scenarios_frozen", scenariosFrozen)
                .stat("scenarios_unobservable", scenariosUnobservable)
                .stat("scenarios_awaiting_old", scenariosAwaiting)
                .stat("contracts", contracts.size())
                .stat("frozen", frozen)
                .stat("awaiting_old_observation", awaiting)
                .stat("mapped_to_existing_tests", mapped)
                .stat("unobservable", unobservable);
        if (unobservable > 0) {
            envelope.blindSpot(new Envelope.BlindSpot("BS-CHAR-001", "CHARACTERIZATION",
                    unobservable + " behaviour(s) could not be characterized in this environment",
                    "Differential validation cannot reach E4 for those dimensions"));
        }

        ObjectNode report = Json.obj();
        report.put("contract_count", contracts.size());
        report.put("scenario_count", scenarios.size());
        report.set("scenarios_by_state", Json.toTree(scenarioStates));
        report.put("scenarios_executed_against_old", freeze.observations().size());
        report.put("scenario_execution_rule", "Scenarios are executed against the ORIGINAL "
                + "application here, before any migration change exists. That is what makes the "
                + "observed behaviour an oracle rather than an assertion.");
        report.set("by_state", Json.toTree(byState));
        report.put("oracle_rule", "Expected behaviour comes from observed original behaviour, an "
                + "authoritative specification, or an explicit human decision. Never from invention.");
        report.put("framework_neutrality", "Contracts describe scenarios and observed outcomes rather "
                + "than JUnit tests, so a test-infrastructure migration cannot destroy the oracle.");
        ObjectNode reportArtifact = StageSupport.compose(envelope, report);
        StageSupport.validate(context, writer, "characterization/characterization-report.schema.json",
                "characterization-report.json", reportArtifact);
        writer.write("characterization-report.json", reportArtifact);

        ObjectNode contractArtifact = Json.obj();
        contractArtifact.put("count", contracts.size());
        contractArtifact.set("contracts", Json.toTree(contracts));
        writer.write("characterization-contracts.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), contractArtifact));

        ObjectNode scenarioArtifact = Json.obj();
        scenarioArtifact.put("count", scenarios.size());
        scenarioArtifact.set("by_state", Json.toTree(scenarioStates));
        scenarioArtifact.put("frozen", scenariosFrozen);
        scenarioArtifact.put("unobservable", scenariosUnobservable);
        scenarioArtifact.put("awaiting_old_observation", scenariosAwaiting);
        scenarioArtifact.put("rejected", scenariosRejected);
        scenarioArtifact.put("external_infrastructure_available", externalInfrastructure);
        scenarioArtifact.put("oracle_rule", "A scenario becomes an oracle by being EXECUTED against "
                + "the original application. Expected values are never derived by reading migrated "
                + "code, and AWAITING_OLD_OBSERVATION is not a protected state.");
        scenarioArtifact.set("scenarios",
                Json.toTree(scenarios.stream().map(Scenario::toNode).toList()));
        ObjectNode scenarioPublished = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), scenarioArtifact);
        StageSupport.validate(context, writer,
                "characterization/characterization-scenarios.schema.json",
                "characterization-scenarios.json", scenarioPublished);
        writer.write("characterization-scenarios.json", scenarioPublished);

        ObjectNode observationArtifact = Json.obj();
        observationArtifact.put("count", freeze.observations().size());
        observationArtifact.put("side", "OLD");
        observationArtifact.put("modules_executed", freeze.modulesExecuted());
        observationArtifact.set("execution_failures", Json.toTree(freeze.failures()));
        observationArtifact.set("observations",
                Json.toTree(freeze.observations().stream()
                        .map(ScenarioObservation::toNode).toList()));
        writer.write("characterization-old-observations.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), observationArtifact));

        ObjectNode gapArtifact = Json.obj();
        gapArtifact.put("count", gaps.size());
        // "gaps" is a reserved envelope key, so the payload names this collection explicitly.
        gapArtifact.set("characterization_gaps", Json.toTree(gaps));
        writer.write("characterization-gaps.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), gapArtifact));

        StageSupport.toEvidence(context, "characterization", reportArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        if (!writer.validationErrors().isEmpty()) {
            return StageResult.failure(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Characterization artifacts failed schema validation", writer.validationErrors());
        }

        String hash = StageSupport.publish(context, writer);
        context.stateMachine().transition(RunState.CHARACTERIZATION_COMPLETE,
                contracts.size() + " contract(s)");
        context.runStateStore().updateState(context.run().runId(),
                RunState.CHARACTERIZATION_COMPLETE, "characterization complete");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        if (scenariosAwaiting > 0) {
            messages.add(scenariosAwaiting + " scenario(s) could not be executed against the original "
                    + "application and are NOT protection; they cannot act as an oracle");
        }
        if (scenariosUnobservable > 0) {
            messages.add(scenariosUnobservable + " scenario(s) are unobservable in this environment "
                    + "and are recorded as explicit gaps");
        }
        freeze.failures().forEach(f -> messages.add("scenario execution: " + f));
        if (awaiting > 0) {
            messages.add(awaiting + " probe(s) await execution against the original application; "
                    + "they cannot act as an oracle until then");
        }
        if (unobservable > 0) {
            messages.add(unobservable + " behaviour(s) are unobservable in this environment");
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                scenarios.size() + " executable scenario(s): " + scenariosFrozen + " frozen against "
                        + "OLD, " + scenariosUnobservable + " unobservable, " + scenariosAwaiting
                        + " unexecuted; "
                        + contracts.size() + " characterization contract(s): " + frozen + " frozen, "
                        + mapped + " mapped to existing tests, " + awaiting + " awaiting OLD, "
                        + unobservable + " unobservable",
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ contract construction

    private ObjectNode characterize(StageContext context, JsonNode finding, String dimension,
                                    ApplicationGraph graph, Set<String> passingTestClasses,
                                    Map<String, JsonNode> runtimeByModule) {
        ObjectNode contract = Json.obj();
        String scenarioId = Ids.scenarioId(sequence.incrementAndGet());
        contract.put("scenario_id", scenarioId);
        contract.put("impact_id", finding.path("impact_id").asText());
        contract.put("knowledge_id", finding.path("knowledge_id").asText());
        contract.put("dimension", dimension);
        contract.put("file_id", finding.path("file_id").asText(null));
        contract.put("node_id", finding.path("node_id").asText(null));
        contract.put("subject", finding.path("subject").asText());
        contract.put("module", finding.path("module").asText(null));

        // 1. does adequate protection already exist?
        ArrayNode covering = Json.arr();
        finding.path("covering_tests").forEach(t -> {
            if (passingTestClasses.contains(t.asText())) {
                covering.add(t.asText());
            }
        });
        if (covering.size() > 0) {
            contract.set("existing_tests", covering);
            contract.put("state", ContractState.MAPPED_TO_EXISTING_TEST.name());
            contract.put("oracle_source", "EXISTING_APPLICATION_TEST");
            contract.put("reason", "Behaviour is already protected by " + covering.size()
                    + " passing baseline test(s)");
            return contract;
        }

        // 2. can the behaviour be observed at all in this environment?
        String module = finding.path("module").asText(null);
        JsonNode runtime = module == null ? null : runtimeByModule.get(module);
        boolean runtimeObservable = runtime != null && runtime.path("started").asBoolean(false);

        boolean needsRuntime = switch (dimension) {
            case "HTTP_API", "SECURITY_AUTHORIZATION", "SERIALIZATION", "CONFIGURATION_BINDING",
                 "PERSISTENCE_STATE", "QUERY_RESULT", "TRANSACTION_EFFECT", "EVENT_MESSAGE",
                 "EXTERNAL_INTEGRATION", "BATCH_RESULT" -> true;
            default -> false;
        };

        if (needsRuntime && !runtimeObservable) {
            contract.put("state", ContractState.UNOBSERVABLE.name());
            contract.put("oracle_source", "NONE");
            contract.put("reason", "Dimension " + dimension + " requires a running application, and "
                    + (runtime == null ? "no runtime probe was attempted for module " + module
                    : "the module did not start: " + runtime.path("failure_reason").asText("unknown")));
            contract.set("probe", scaffold(dimension, finding, graph));
            return contract;
        }

        // 3. scaffold a probe whose expectation must still come from OLD.
        contract.put("state", ContractState.AWAITING_OLD_OBSERVATION.name());
        contract.put("oracle_source", "OBSERVED_ORIGINAL_BEHAVIOUR_PENDING");
        contract.put("reason", "No existing test covers this behaviour. A harness-owned probe was "
                + "scaffolded; its expected value will be frozen from the original application, never "
                + "invented.");
        contract.set("probe", scaffold(dimension, finding, graph));
        contract.put("ai_used", false);
        if (context.ai().enabled()) {
            contract.put("ai_used", true);
            contract.put("ai_role", "scaffolding only; the expected value still comes from OLD");
        }
        return contract;
    }

    /** Builds an executable probe description. Framework-neutral by construction. */
    private ObjectNode scaffold(String dimension, JsonNode finding, ApplicationGraph graph) {
        ObjectNode probe = Json.obj();
        probe.put("kind", dimension);
        probe.put("harness_owned", true);
        switch (dimension) {
            case "HTTP_API", "SECURITY_AUTHORIZATION", "SERIALIZATION" -> {
                probe.put("transport", "HTTP");
                probe.put("method", "GET");
                probe.put("path", "/actuator/health");
                probe.set("capture", Json.toTree(List.of("status_code", "headers", "body_shape")));
            }
            case "CONFIGURATION_BINDING" -> {
                probe.put("transport", "ACTUATOR");
                probe.put("path", "/actuator/configprops");
                probe.set("capture", Json.toTree(List.of("canonical_key", "bound", "defaulted",
                        "target_type", "source_type")));
            }
            case "PERSISTENCE_STATE", "QUERY_RESULT", "TRANSACTION_EFFECT" -> {
                probe.put("transport", "REPOSITORY_INVOCATION");
                probe.set("capture", Json.toTree(List.of("returned_entities", "database_state",
                        "transaction_outcome")));
                probe.put("note", "SQL text is diagnostic evidence only; the contract compares "
                        + "business-relevant semantics");
            }
            case "CONTEXT_CAPABILITY" -> {
                probe.put("transport", "ACTUATOR");
                probe.put("path", "/actuator/beans");
                probe.set("capture", Json.toTree(List.of("bean_names", "condition_evaluation")));
            }
            case "EVENT_MESSAGE" -> {
                probe.put("transport", "BROKER");
                probe.set("capture", Json.toTree(List.of("destination", "payload_shape", "headers")));
            }
            default -> {
                probe.put("transport", "IN_PROCESS");
                probe.set("capture", Json.toTree(List.of("observed_outcome")));
            }
        }
        graph.node(finding.path("node_id").asText("")).ifPresent(node -> {
            probe.put("target", node.getFqn() == null ? node.getName() : node.getFqn());
            if (node.getType() == NodeType.ENDPOINT) {
                probe.put("method", String.valueOf(node.getProperties().get("http_method")));
                probe.put("path", String.valueOf(node.getProperties().get("path")));
            }
        });
        return probe;
    }

    private ObjectNode endpointContract(GraphNode endpoint, Map<String, JsonNode> runtimeByModule) {
        ObjectNode contract = Json.obj();
        contract.put("scenario_id", Ids.scenarioId(sequence.incrementAndGet()));
        contract.put("dimension", "HTTP_API");
        contract.put("node_id", endpoint.getId());
        contract.put("module", endpoint.getModule());
        contract.put("subject", endpoint.getName());
        contract.put("file_id", endpoint.getFileId());

        JsonNode runtime = runtimeByModule.get(endpoint.getModule());
        boolean observable = runtime != null && runtime.path("started").asBoolean(false);
        ObjectNode probe = Json.obj();
        probe.put("kind", "HTTP_API");
        probe.put("harness_owned", true);
        probe.put("transport", "HTTP");
        probe.put("method", String.valueOf(endpoint.getProperties().get("http_method")));
        probe.put("path", String.valueOf(endpoint.getProperties().get("path")));
        probe.put("handler", String.valueOf(endpoint.getProperties().get("handler")));
        probe.set("capture", Json.toTree(List.of("status_code", "content_type", "body_shape",
                "error_payload_shape")));
        contract.set("probe", probe);

        if (observable) {
            contract.put("state", ContractState.AWAITING_OLD_OBSERVATION.name());
            contract.put("oracle_source", "OBSERVED_ORIGINAL_BEHAVIOUR_PENDING");
            contract.put("reason", "Endpoint is reachable in the baseline environment; its response "
                    + "shape will be frozen from the original application");
        } else {
            contract.put("state", ContractState.UNOBSERVABLE.name());
            contract.put("oracle_source", "NONE");
            contract.put("reason", "Module " + endpoint.getModule()
                    + " did not start during baseline capture, so this endpoint has no observed "
                    + "original behaviour to freeze");
        }
        return contract;
    }

    // ------------------------------------------------------------------ baseline lookups

    private Set<String> passingTestClasses(JsonNode baselineTests) {
        Set<String> classes = new LinkedHashSet<>();
        if (baselineTests == null) {
            return classes;
        }
        for (JsonNode module : baselineTests.path("modules")) {
            for (JsonNode testCase : module.path("cases")) {
                if ("PASSED".equals(testCase.path("outcome").asText())) {
                    classes.add(testCase.path("className").asText());
                }
            }
        }
        return classes;
    }

    private Map<String, JsonNode> runtimeByModule(JsonNode baselineRuntime) {
        Map<String, JsonNode> byModule = new LinkedHashMap<>();
        if (baselineRuntime == null) {
            return byModule;
        }
        for (JsonNode module : baselineRuntime.path("modules")) {
            byModule.put(module.path("module").asText(), module);
        }
        return byModule;
    }

    // ------------------------------------------------------------------ scenario execution

    /** Result of executing the scenario set against the original application. */
    record ScenarioFreeze(List<Scenario> scenarios, List<ScenarioObservation> observations,
                          List<String> failures, int modulesExecuted) {
    }

    /**
     * Executes every executable scenario against the ORIGINAL application and freezes what it saw.
     *
     * <p>The OLD workspace was built and packaged by Agent 04, so the artifacts already exist. Each
     * module is started once and every scenario for that module runs against that instance, because
     * restarting per scenario would multiply a ninety-second startup by the scenario count and would
     * also make the observations less comparable, not more.
     */
    private ScenarioFreeze freezeAgainstOriginal(StageContext context, List<Scenario> scenarios,
                                                 Set<String> modulesThatStart) {
        List<ScenarioObservation> observations = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, Scenario> byId = new LinkedHashMap<>();
        scenarios.forEach(scenario -> byId.put(scenario.scenarioId(), scenario));

        Path oldWorkspace = context.run().runtimeOldWorkspace();
        if (!java.nio.file.Files.isDirectory(oldWorkspace)) {
            failures.add("The OLD workspace does not exist; run the baseline stage first");
            return new ScenarioFreeze(markUnexecuted(scenarios, "No OLD workspace to execute against"),
                    observations, failures, 0);
        }

        JsonNode buildNode = StageSupport.optionalUpstream(context, "02-build", "build-model.json");
        JsonNode dependencyNode = StageSupport.optionalUpstream(context, "02-build",
                "dependency-model.json");
        BuildSystemPort.BuildModel buildModel = buildNode == null ? null
                : BuildModelCodec.decode(buildNode, dependencyNode);
        ApplicationGraph graph = null;

        SpringProcessRuntimeProbe probe = new SpringProcessRuntimeProbe(
                context.run().runWorkspace().resolve("characterization-logs"));
        int modulesExecuted = 0;

        // The original application is built for the level it declares, so the OLD side runs on a JDK
        // that supports that level rather than on whatever is newest.
        com.bootshift.adapters.build.ToolchainProbe toolchainProbe =
                new com.bootshift.adapters.build.ToolchainProbe();
        List<com.bootshift.adapters.build.ToolchainProbe.Jdk> jdks = toolchainProbe.discover();

        for (String moduleId : modulesThatStart) {
            List<Scenario> forModule = scenarios.stream()
                    .filter(scenario -> moduleId.equals(scenario.module()))
                    .filter(scenario -> scenario.state() == Scenario.State.DRAFT)
                    .toList();
            if (forModule.isEmpty()) {
                continue;
            }
            Path moduleRoot = ".".equals(moduleId) ? oldWorkspace : oldWorkspace.resolve(moduleId);
            if (!java.nio.file.Files.isDirectory(moduleRoot)) {
                failures.add("Module " + moduleId + " is absent from the OLD workspace");
                continue;
            }

            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("fingerprint", "characterization-old");
            int declaredJava = declaredJavaFor(buildModel, moduleId);
            toolchainProbe.select(declaredJava, jdks).ifPresent(jdk -> {
                settings.put("bootshift.javaHome", jdk.home().toString());
                com.bootshift.adapters.build.JavaTargetSelector
                        .javaExecutable(jdk.home().toString())
                        .ifPresent(exe -> settings.put("bootshift.javaExecutable", exe.toString()));
            });
            if (buildModel != null) {
                if (graph == null) {
                    JsonNode graphNode = StageSupport.optionalUpstream(context, "03-graph",
                            "application-graph.json");
                    graph = graphNode == null ? new ApplicationGraph()
                            : ApplicationGraph.fromNode(graphNode);
                }
                settings.putAll(com.bootshift.stages.ValidationSupport
                        .runtimeSettings(moduleId, graph, buildModel));
            }

            SpringProcessRuntimeProbe.ScenarioRun run = probe.runScenarios(moduleRoot, moduleId,
                    forModule, ScenarioObservation.Side.OLD, settings);
            observations.addAll(run.observations());
            if (!run.started()) {
                failures.add("Module " + moduleId + " could not be started for characterization: "
                        + run.failureReason());
            } else {
                modulesExecuted++;
            }
        }

        // Freeze what actually executed successfully; everything else keeps a state that says so.
        Map<String, ScenarioObservation> successful = new LinkedHashMap<>();
        observations.stream().filter(ScenarioObservation::successful)
                .forEach(observation -> successful.put(observation.scenarioId(), observation));

        List<Scenario> resolved = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            if (scenario.state() != Scenario.State.DRAFT) {
                resolved.add(scenario);
                continue;
            }
            ScenarioObservation observation = successful.get(scenario.scenarioId());
            if (observation != null) {
                resolved.add(scenario.withOldObservation(observation.rawHash()));
                continue;
            }
            ScenarioObservation attempted = observations.stream()
                    .filter(o -> o.scenarioId().equals(scenario.scenarioId()))
                    .findFirst().orElse(null);
            if (attempted != null) {
                // Attempted and failed is a finished question, not a pending one.
                //
                // AWAITING_OLD_OBSERVATION means "not executed yet" - a state a later stage could
                // still resolve. A scenario whose module was running and whose request came back
                // unusable will never be resolved by anything in this run, and leaving it pending
                // kept it out of the declared-gap count: it protected nothing and was not counted as
                // an admitted blind spot either, which is the one combination the evidence rules do
                // not allow. The failure reason travels with the state so the gap says why.
                resolved.add(scenario.withState(Scenario.State.UNOBSERVABLE_WITH_EXPLICIT_GAP,
                        "Execution against the original application did not succeed, so this "
                                + "behaviour is unobserved and is declared as a gap rather than left "
                                + "pending: " + attempted.failureReason()));
            } else {
                resolved.add(scenario.withState(Scenario.State.AWAITING_OLD_OBSERVATION,
                        "Never attempted against the original application"));
            }
        }
        return new ScenarioFreeze(resolved, observations, failures, modulesExecuted);
    }

    private static List<Scenario> markUnexecuted(List<Scenario> scenarios, String reason) {
        List<Scenario> marked = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            marked.add(scenario.state() == Scenario.State.DRAFT
                    ? scenario.withState(Scenario.State.AWAITING_OLD_OBSERVATION, reason)
                    : scenario);
        }
        return marked;
    }

    private static int declaredJavaFor(BuildSystemPort.BuildModel model, String moduleId) {
        if (model == null) {
            return 17;
        }
        return model.modules().stream()
                .filter(m -> m.moduleId().equals(moduleId))
                .findFirst()
                .map(m -> m.effectiveJavaRelease(17))
                .orElse(17);
    }

    /**
     * Whether the environment provider can actually supply the datastores and brokers the
     * infrastructure-dependent dimensions need.
     *
     * <p>Asked rather than assumed. Claiming a persistence comparison happened when no database was
     * ever provisioned is the failure mode this question exists to prevent.
     */
    private static boolean environmentSuppliesInfrastructure(StageContext context) {
        try {
            var provisioned = context.environment().provision("characterization-probe",
                    Map.of("purpose", "infrastructure-availability-probe"));
            return provisioned.usable() && !provisioned.endpoints().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
