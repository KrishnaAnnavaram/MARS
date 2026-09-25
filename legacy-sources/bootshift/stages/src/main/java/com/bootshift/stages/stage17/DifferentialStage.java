package com.bootshift.stages.stage17;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.approval.ApprovalPort;
import com.bootshift.ports.approval.DecisionStore;
import com.bootshift.ports.characterization.Scenario;
import com.bootshift.ports.characterization.ScenarioObservation;
import com.bootshift.ports.differential.DifferentialPort;
import com.bootshift.ports.environment.EnvironmentProvider;
import com.bootshift.stages.EdgeSupport;
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

/**
 * Agent 17 - Differential Validation (spec section 33).
 *
 * <p>Runs the same characterized scenario against the original and migrated applications and
 * classifies each difference. The classification is the whole point:
 *
 * <ul>
 *   <li>EXPECTED requires a VERIFIED migration fact or a signed approval;</li>
 *   <li>UNEXPECTED is a migration defect;</li>
 *   <li>UNEXPLAINED blocks (R21).</li>
 * </ul>
 *
 * <p>A dimension whose environment equivalence contract is not satisfied is NOT_COMPARED rather than
 * passed. Comparing two sides that differ in a MUST_MATCH attribute produces a number, not evidence.
 */
public final class DifferentialStage implements Stage {

    public static final String OUTPUT_DIR = "17-differential";

    private final String edgeId;

    public DifferentialStage(String edgeId) {
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
        return "Compare original and migrated behaviour for the required dimensions";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.EDGE_RUNTIME_GRAPH_ENRICHED);
    }

    @Override
    public RunState postcondition() {
        return RunState.EDGE_DIFFERENTIAL_VALIDATED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("11-plan/edge-plan.json", "10-characterization/characterization-contracts.json",
                "04-baseline/baseline-runtime.json", "16-runtime/runtime-report.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("differential-report.json", "normalization-policy.json",
                "environment-equivalence-check.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode edgePlanArtifact = StageSupport.requireUpstream(context, "11-plan", "edge-plan.json",
                "Run: harness plan");
        JsonNode edgePlan = EdgeSupport.findEdge(edgePlanArtifact, edgeId);
        JsonNode contracts = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-contracts.json");
        JsonNode baselineRuntime = StageSupport.requireUpstream(context, "04-baseline",
                "baseline-runtime.json", "Run: harness baseline --repo <path>");
        JsonNode baselineEquivalence = StageSupport.requireUpstream(context, "04-baseline",
                "environment-equivalence.json", "Run: harness baseline --repo <path>");
        JsonNode migratedRuntime = StageSupport.optionalUpstream(context, "16-runtime",
                "runtime-report.json");
        JsonNode silentlyIgnored = StageSupport.optionalUpstream(context, "16-runtime",
                "silently-ignored-properties.json");
        JsonNode knowledge = StageSupport.optionalUpstream(context, "08-knowledge",
                "migration-knowledge.json");
        // Decisions come from the store, not from Agent 18's published report. Reading the report
        // made validation depend on the stage that runs after it and derives its gates from
        // validation's own output, so a legitimately filed decision could not affect the comparison
        // it was filed for until the whole run was repeated.
        DecisionStore decisionStore = context.decisions();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId);

        DifferentialPort.NormalizationPolicy policy = Normalizer.policy();
        ObjectNode policyArtifact = Json.obj();
        policyArtifact.put("version", policy.version());
        policyArtifact.put("policy_hash", policy.policyHash());
        policyArtifact.put("rule", "Normalization is explicit, versioned and hashed. A dynamic field "
                + "is never silently ignored; every rule carries its rationale.");
        policyArtifact.set("rules", Json.toTree(policy.rules()));
        writer.write("normalization-policy.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        policyArtifact));

        if (!edgePlan.path("differential_required").asBoolean(false)) {
            String hash = StageSupport.publishForEdge(context, writer, edgeId, OUTPUT_DIR,
                com.bootshift.stages.EdgeIndex.Phase.DIFFERENTIAL_VALIDATED, "published");
            context.stateMachine().transition(RunState.EDGE_DIFFERENTIAL_VALIDATED, "not required");
            return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                    "Differential validation not required at depth "
                            + edgePlan.path("frozen_validation_depth").asText(),
                    List.of(), Map.of(), hash);
        }

        // ---- environment equivalence gate --------------------------------------------------------
        EnvironmentProvider.ProvisionedEnvironment oldEnv = context.environment()
                .provision("runtime-old-" + edgeId, Map.of());
        EnvironmentProvider.ProvisionedEnvironment newEnv = context.environment()
                .provision("runtime-new-" + edgeId, Map.of());
        List<EnvironmentProvider.EquivalenceCheck> checks = context.environment()
                .compare(oldEnv, newEnv);
        List<String> unsatisfied = checks.stream()
                .filter(c -> !c.satisfied())
                .map(c -> c.attribute() + ": " + c.detail())
                .toList();

        ObjectNode equivalenceArtifact = Json.obj();
        equivalenceArtifact.put("provider_mode", context.environment().mode().name());
        equivalenceArtifact.put("provider_implementation", context.environment().implementationName());
        equivalenceArtifact.put("provider_version", context.environment().version());
        equivalenceArtifact.put("old_fingerprint", oldEnv.fingerprint());
        equivalenceArtifact.put("new_fingerprint", newEnv.fingerprint());
        equivalenceArtifact.put("baseline_fingerprint", baselineEquivalence.path("fingerprint").asText());
        equivalenceArtifact.set("checks", Json.toTree(checks));
        equivalenceArtifact.set("unsatisfied", Json.toTree(unsatisfied));
        equivalenceArtifact.set("provider_gaps", Json.toTree(newEnv.equivalenceGaps()));
        writer.write("environment-equivalence-check.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        equivalenceArtifact));

        Set<String> requiredDimensions = new LinkedHashSet<>();
        edgePlan.path("differential_dimensions").forEach(d -> requiredDimensions.add(d.asText()));
        if (requiredDimensions.isEmpty()) {
            requiredDimensions.add("CONTEXT_CAPABILITY");
        }

        // ---- scenario comparison ------------------------------------------------------------------
        //
        // The unit of comparison is the characterization scenario: the same request, executed against
        // the original application and against the migrated one, captured identically. Comparing
        // "module plus Actuator dimension" - which is what this stage did while no scenario was ever
        // executed - compares the shape of two contexts. It cannot see that an endpoint changed
        // status, that an unauthenticated caller is now let through, or that a response lost a field.
        List<ObjectNode> scenarioComparisons = new ArrayList<>();
        ScenarioComparison scenarioOutcome = compareScenarios(context, requiredDimensions,
                decisionStore, knowledge, unsatisfied, policy, scenarioComparisons);

        // ---- residual module-level comparison -----------------------------------------------------
        // Kept for the dimensions no scenario covered, so a dimension the plan required is still
        // accounted for rather than silently absent.
        Map<String, JsonNode> oldByModule = index(baselineRuntime);
        Map<String, JsonNode> newByModule = migratedRuntime == null ? Map.of() : index(migratedRuntime);
        Set<String> modules = new LinkedHashSet<>(oldByModule.keySet());
        modules.addAll(newByModule.keySet());

        List<ObjectNode> comparisons = new ArrayList<>();
        Map<String, Integer> counts = new java.util.TreeMap<>();
        List<String> blocking = new ArrayList<>();

        for (String dimension : requiredDimensions) {
            // A dimension already covered by executed scenarios does not need the weaker
            // module-level fallback; adding it would dilute a real comparison with a metadata one.
            if (scenarioOutcome.dimensionsCovered().contains(dimension)) {
                continue;
            }
            boolean environmentBlocks = !unsatisfied.isEmpty() && dimensionNeedsEquivalence(dimension);
            for (String module : modules) {
                JsonNode oldSide = oldByModule.get(module);
                JsonNode newSide = newByModule.get(module);
                ObjectNode comparison = compare(context, dimension, module, oldSide, newSide, policy,
                        knowledge, decisionStore, silentlyIgnored, environmentBlocks, unsatisfied);
                comparisons.add(comparison);
                counts.merge(comparison.path("classification").asText(), 1, Integer::sum);
                String classification = comparison.path("classification").asText();
                if ("UNEXPLAINED".equals(classification) || "UNEXPECTED".equals(classification)) {
                    blocking.add(classification + " " + dimension + " on " + module + ": "
                            + comparison.path("detail").asText());
                }
            }
        }

        // Scenario results come first in the report: they are the strong evidence.
        comparisons.addAll(0, scenarioComparisons);
        scenarioComparisons.forEach(c ->
                counts.merge(c.path("classification").asText(), 1, Integer::sum));
        scenarioComparisons.forEach(c -> {
            String classification = c.path("classification").asText();
            if ("UNEXPLAINED".equals(classification) || "UNEXPECTED".equals(classification)) {
                blocking.add(classification + " " + c.path("dimension").asText() + " scenario "
                        + c.path("scenario_id").asText() + ": " + c.path("detail").asText());
            }
        });

        long notCompared = counts.getOrDefault("NOT_COMPARED", 0);
        if (notCompared > 0) {
            envelope.gap(new Envelope.Gap("GAP-DIFF-001", "DIFFERENTIAL",
                    notCompared + " dimension/module pair(s) could not be compared",
                    "Those dimensions cannot reach E4 for this edge"));
        }

        ObjectNode report = Json.obj();
        report.put("comparison_count", comparisons.size());
        report.put("scenario_comparison_count", scenarioComparisons.size());
        report.put("module_level_comparison_count", comparisons.size() - scenarioComparisons.size());
        report.set("dimensions_covered_by_scenarios", Json.toTree(scenarioOutcome.dimensionsCovered()));
        report.set("dimensions_compared_beyond_plan",
                Json.toTree(scenarioOutcome.dimensionsBeyondPlan()));
        report.put("scenario_selection_rule", "Every scenario holding a successful observation on "
                + "both sides is compared. The edge plan's required dimensions decide which "
                + "dimensions must be accounted for, not which measurements are looked at. A "
                + "difference is blocking wherever it is found: an unexplained behavioural change in "
                + "a dimension the plan did not anticipate is exactly the change least likely to "
                + "have been anticipated.");
        report.set("scenario_execution_notes", Json.toTree(scenarioOutcome.notes()));
        report.put("comparison_unit", "characterization scenario; module-level Actuator comparison is "
                + "a fallback for dimensions no scenario covered and is explicitly weaker evidence");
        report.set("required_dimensions", Json.toTree(requiredDimensions));
        report.set("classification_counts", Json.toTree(counts));
        report.put("normalization_policy_hash", policy.policyHash());
        report.put("environment_mode", context.environment().mode().name());
        report.put("blocking_rule", "UNEXPLAINED > 0 blocks unless an authorized human decision "
                + "resolves it with evidence (R21)");
        report.set("comparisons", Json.toTree(comparisons));
        ObjectNode reportArtifact = StageSupport.compose(envelope
                .stat("comparisons", comparisons.size())
                .stat("unexplained", counts.getOrDefault("UNEXPLAINED", 0)), report);
        StageSupport.validate(context, writer, "differential/differential-report.schema.json",
                "differential-report.json", reportArtifact);
        writer.write("differential-report.json", reportArtifact);

        StageSupport.toEvidence(context, "differential-report", reportArtifact,
                EvidenceManifest.Classification.INTERNAL, "SEALED_EVIDENCE", OUTPUT_DIR);

        String hash = StageSupport.publishForEdge(context, writer, edgeId, OUTPUT_DIR,
                com.bootshift.stages.EdgeIndex.Phase.DIFFERENTIAL_VALIDATED, "published");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        long unexplained = counts.getOrDefault("UNEXPLAINED", 0);
        if (unexplained > 0 && context.policy().unexplainedDifferenceBlocks()) {
            context.stateMachine().transition(RunState.BLOCKED,
                    unexplained + " unexplained difference(s)");
            context.runStateStore().updateState(context.run().runId(), RunState.BLOCKED,
                    "unexplained differential findings");
            return new StageResult(OUTPUT_DIR, ExitCode.POLICY_BLOCK,
                    "Edge " + edgeId + ": " + unexplained + " unexplained behavioural difference(s)",
                    blocking, artifacts, hash);
        }

        context.stateMachine().transition(RunState.EDGE_DIFFERENTIAL_VALIDATED,
                comparisons.size() + " comparison(s)");
        context.runStateStore().updateState(context.run().runId(),
                RunState.EDGE_DIFFERENTIAL_VALIDATED, "differential validated for " + edgeId);
        EdgeSupport.checkpoint(context, edgeId, "differential-validated",
                "Edge " + edgeId + " differential validated");

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Edge " + edgeId + ": " + comparisons.size() + " comparison(s) across "
                        + requiredDimensions.size() + " dimension(s); " + counts,
                blocking, artifacts, hash);
    }

    // ------------------------------------------------------------------ comparison

    private ObjectNode compare(StageContext context, String dimension, String module,
                               JsonNode oldSide, JsonNode newSide,
                               DifferentialPort.NormalizationPolicy policy, JsonNode knowledge,
                               DecisionStore decisionStore, JsonNode silentlyIgnored,
                               boolean environmentBlocks, List<String> unsatisfied) {
        ObjectNode comparison = Json.obj();
        comparison.put("dimension", dimension);
        comparison.put("module", module);
        comparison.put("normalization_policy_hash", policy.policyHash());

        if (environmentBlocks) {
            comparison.put("classification", "NOT_COMPARED");
            comparison.put("detail", "Environment equivalence is not satisfied for a MUST_MATCH "
                    + "attribute, so a comparison of this dimension would produce a number rather "
                    + "than evidence: " + unsatisfied);
            return comparison;
        }
        if (oldSide == null || newSide == null) {
            comparison.put("classification", "NOT_COMPARED");
            comparison.put("detail", oldSide == null
                    ? "No baseline observation exists for module " + module
                    : "No migrated observation exists for module " + module);
            return comparison;
        }
        boolean oldStarted = oldSide.path("started").asBoolean(false);
        boolean newStarted = newSide.path("started").asBoolean(false);
        if (!oldStarted && !newStarted) {
            comparison.put("classification", "NOT_COMPARED");
            comparison.put("detail", "Neither side started, so there is nothing to compare");
            return comparison;
        }
        if (oldStarted != newStarted) {
            String detail = oldStarted
                    ? "The original application started but the migrated one did not: "
                      + newSide.path("failure_reason").asText("unknown")
                    : "The migrated application starts but the original did not, so this is not a "
                      + "regression; it is an improvement relative to sealed baseline debt";
            comparison.put("classification", oldStarted ? "UNEXPECTED" : "EXPECTED");
            comparison.put("detail", detail);
            if (!oldStarted) {
                comparison.put("explanation", "Baseline debt recorded in the sealed baseline");
            }
            return comparison;
        }

        // Both sides ran. Compare the dimension-specific observation.
        JsonNode oldObservation = extract(oldSide, dimension);
        JsonNode newObservation = extract(newSide, dimension);
        Normalizer.Applied oldNormalized = Normalizer.normalize(oldObservation, dimension);
        Normalizer.Applied newNormalized = Normalizer.normalize(newObservation, dimension);
        comparison.set("normalization_rules_applied",
                Json.toTree(new LinkedHashSet<>(oldNormalized.rulesApplied())));
        comparison.set("demoted_to_diagnostic", Json.toTree(oldNormalized.demoted()));

        List<ObjectNode> differences = diff("", oldNormalized.normalized(), newNormalized.normalized());
        comparison.put("difference_count", differences.size());
        comparison.set("differences", Json.toTree(differences.stream().limit(40).toList()));

        if (differences.isEmpty()) {
            comparison.put("classification", "IDENTICAL");
            comparison.put("detail", "Normalized observations are identical on both sides");
            return comparison;
        }

        // CONFIGURATION_BINDING has a first-class explanation source.
        if ("CONFIGURATION_BINDING".equals(dimension) && silentlyIgnored != null
                && silentlyIgnored.path("count").asInt() > 0) {
            comparison.put("classification", "UNEXPECTED");
            comparison.put("detail", silentlyIgnored.path("count").asInt()
                    + " property(ies) stopped binding after migration");
            comparison.set("explanation_refs",
                    Json.toTree(List.of("16-runtime/silently-ignored-properties.json")));
            return comparison;
        }

        List<String> explanations = explain(differences, knowledge);
        DecisionStore.StoredDecision approval = approvalFor(decisionStore, dimension, module);
        if (approval != null) {
            comparison.put("classification", "EXPECTED");
            comparison.put("approval_ref", approval.decision().decisionId());
            comparison.put("approval_actor", approval.decision().actor());
            comparison.put("approval_actor_authentication", approval.authentication().name());
            comparison.put("approval_rationale", approval.decision().rationale());
            comparison.put("detail", "Difference is covered by a recorded human intentional-change "
                    + "decision (" + approval.decision().actor() + ", "
                    + approval.authentication().name() + ")");
            return comparison;
        }
        if (!explanations.isEmpty() && explanations.size() >= differences.size()) {
            comparison.put("classification", "EXPECTED");
            comparison.set("explanation_refs", Json.toTree(explanations));
            comparison.put("detail", "Every difference is explained by a verified migration fact");
            return comparison;
        }
        if (!explanations.isEmpty()) {
            comparison.put("classification", "UNEXPLAINED");
            comparison.set("explanation_refs", Json.toTree(explanations));
            comparison.put("detail", explanations.size() + " of " + differences.size()
                    + " difference(s) are explained by verified facts; the remainder are not");
            return comparison;
        }
        comparison.put("classification", "UNEXPLAINED");
        comparison.put("detail", differences.size() + " difference(s) with no verified migration fact "
                + "and no signed approval to explain them");
        return comparison;
    }

    /** Recursive structural diff over normalized observations. */
    static List<ObjectNode> diff(String path, JsonNode left, JsonNode right) {
        List<ObjectNode> differences = new ArrayList<>();
        if (left == null && right == null) {
            return differences;
        }
        if (left == null || right == null || left.getNodeType() != right.getNodeType()) {
            differences.add(difference(path, render(left), render(right), "shape differs"));
            return differences;
        }
        if (left.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            left.fieldNames().forEachRemaining(keys::add);
            right.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                differences.addAll(diff(path.isEmpty() ? key : path + "." + key,
                        left.get(key), right.get(key)));
            }
            return differences;
        }
        if (left.isArray()) {
            if (left.size() != right.size()) {
                differences.add(difference(path, String.valueOf(left.size()),
                        String.valueOf(right.size()), "array length differs"));
            }
            int common = Math.min(left.size(), right.size());
            for (int i = 0; i < common; i++) {
                differences.addAll(diff(path + "[" + i + "]", left.get(i), right.get(i)));
            }
            return differences;
        }
        if (!left.equals(right)) {
            differences.add(difference(path, render(left), render(right), "value differs"));
        }
        return differences;
    }

    private static ObjectNode difference(String path, String oldValue, String newValue, String detail) {
        ObjectNode node = Json.obj();
        node.put("path", path);
        node.put("old", oldValue);
        node.put("new", newValue);
        node.put("detail", detail);
        return node;
    }

    private static String render(JsonNode node) {
        return node == null ? null : (node.isValueNode() ? node.asText() : node.toString());
    }

    /** Pulls the dimension-relevant part of a runtime observation. */
    private static JsonNode extract(JsonNode side, String dimension) {
        String wanted = switch (dimension) {
            case "HTTP_API" -> "REQUEST_MAPPINGS";
            case "CONFIGURATION_BINDING" -> "CONFIG_BINDING";
            case "CONTEXT_CAPABILITY" -> "BEANS";
            case "SECURITY_AUTHORIZATION" -> "SECURITY_FILTER_CHAIN";
            case "PERSISTENCE_STATE", "QUERY_RESULT", "TRANSACTION_EFFECT" -> "PERSISTENCE";
            case "EVENT_MESSAGE" -> "MESSAGING";
            case "EXTERNAL_INTEGRATION" -> "EXTERNAL_CLIENT";
            case "SERIALIZATION" -> "SERIALIZATION";
            default -> "CONTEXT";
        };
        ObjectNode extracted = Json.obj();
        ArrayNode matching = Json.arr();
        for (JsonNode observation : side.path("observations")) {
            if (wanted.equals(observation.path("dimension").asText())) {
                ObjectNode copy = Json.obj();
                copy.put("subject", observation.path("subject").asText());
                copy.put("detail", observation.path("detail").asText());
                copy.set("data", observation.path("data"));
                copy.put("successful", observation.path("successful").asBoolean());
                matching.add(copy);
            }
        }
        extracted.put("dimension", dimension);
        extracted.put("mapped_observation", wanted);
        extracted.set("observations", matching);
        extracted.put("observation_count", matching.size());
        return extracted;
    }

    /** Finds verified migration facts that explain a difference. */
    private static List<String> explain(List<ObjectNode> differences, JsonNode knowledge) {
        List<String> explanations = new ArrayList<>();
        if (knowledge == null) {
            return explanations;
        }
        for (ObjectNode difference : differences) {
            String haystack = difference.path("path").asText() + " "
                    + difference.path("old").asText("") + " " + difference.path("new").asText("");
            for (JsonNode fact : knowledge.path("facts")) {
                if (!fact.path("authorizes_transformation").asBoolean(false)) {
                    continue;
                }
                String subject = fact.path("subject").asText("");
                if (subject.length() > 6 && haystack.contains(subject)) {
                    explanations.add(fact.path("knowledge_id").asText());
                    break;
                }
            }
        }
        return explanations;
    }

    /**
     * Finds a recorded human decision that covers this dimension and module.
     *
     * <p>Only an APPROVED verdict on one of the intentional-change gates counts. A decision on an
     * unrelated gate, or a DEFERRED one, explains nothing - treating it as an explanation would let
     * any decision anywhere in the run silence any difference.
     */
    private static DecisionStore.StoredDecision approvalFor(DecisionStore store, String dimension,
                                                            String module) {
        if (store == null) {
            return null;
        }
        for (DecisionStore.StoredDecision stored : store.all()) {
            ApprovalPort.Decision decision = stored.decision();
            if (decision.verdict() != ApprovalPort.Verdict.APPROVED) {
                continue;
            }
            boolean intentionalChangeGate = switch (decision.gate()) {
                case INTENTIONAL_SECURITY_CHANGE, PERSISTENCE_SCHEMA_CHANGE, BUSINESS_OUTCOME_CHANGE,
                     TEST_EXPECTATION_CHANGE -> true;
                default -> false;
            };
            if (!intentionalChangeGate) {
                continue;
            }
            String scope = decision.scope() == null ? "" : decision.scope();
            if (scope.contains(dimension) && (scope.contains(module) || scope.contains("*"))) {
                return stored;
            }
        }
        return null;
    }

    private static boolean dimensionNeedsEquivalence(String dimension) {
        return switch (dimension) {
            case "PERSISTENCE_STATE", "QUERY_RESULT", "TRANSACTION_EFFECT", "EVENT_MESSAGE",
                 "EXTERNAL_INTEGRATION", "BATCH_RESULT" -> true;
            default -> false;
        };
    }

    private static Map<String, JsonNode> index(JsonNode artifact) {
        Map<String, JsonNode> byModule = new LinkedHashMap<>();
        artifact.path("modules").forEach(m -> byModule.put(m.path("module").asText(), m));
        return byModule;
    }

    // ------------------------------------------------------------------ scenario comparison

    /**
     * What the scenario comparison produced.
     *
     * @param dimensionsCovered dimensions in which at least one scenario was actually compared
     * @param dimensionsBeyondPlan dimensions compared that the edge plan did not require - free
     *        evidence, kept because the observations already exist on both sides
     * @param notes execution notes
     */
    record ScenarioComparison(Set<String> dimensionsCovered, Set<String> dimensionsBeyondPlan,
                              List<String> notes) {
    }

    /**
     * Compares OLD and NEW observations of the same scenario.
     *
     * <p>Every classification here is reachable and each means something distinct:
     *
     * <ul>
     *   <li>IDENTICAL - the normalized captures agree;</li>
     *   <li>EXPECTED - they differ, and either a verified migration fact explains the difference or a
     *       recorded human decision covers it;</li>
     *   <li>UNEXPECTED - they differ in a way that is a migration defect;</li>
     *   <li>UNEXPLAINED - they differ and nothing accounts for it;</li>
     *   <li>NOT_COMPARED - one side did not execute, so there is no comparison, and saying so is the
     *       only honest option available.</li>
     * </ul>
     */
    private ScenarioComparison compareScenarios(StageContext context, Set<String> requiredDimensions,
                                                DecisionStore decisionStore, JsonNode knowledge,
                                                List<String> unsatisfied,
                                                DifferentialPort.NormalizationPolicy policy,
                                                List<ObjectNode> out) {
        Set<String> covered = new LinkedHashSet<>();
        Set<String> beyondPlan = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();

        JsonNode scenarioSource = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-scenarios.json");
        JsonNode oldObservations = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-old-observations.json");
        JsonNode newObservations = StageSupport.optionalUpstream(context, "16-runtime",
                "scenario-observations-new.json");

        if (scenarioSource == null) {
            notes.add("No executable scenarios exist, so no scenario-level comparison was possible");
            return new ScenarioComparison(covered, beyondPlan, notes);
        }

        Map<String, ScenarioObservation> oldByScenario = new LinkedHashMap<>();
        if (oldObservations != null) {
            for (JsonNode node : oldObservations.path("observations")) {
                ScenarioObservation observation = ScenarioObservation.fromNode(node);
                oldByScenario.put(observation.scenarioId(), observation);
            }
        }
        Map<String, ScenarioObservation> newByScenario = new LinkedHashMap<>();
        if (newObservations != null) {
            for (JsonNode node : newObservations.path("observations")) {
                ScenarioObservation observation = ScenarioObservation.fromNode(node);
                newByScenario.put(observation.scenarioId(), observation);
            }
        }

        for (JsonNode node : scenarioSource.path("scenarios")) {
            Scenario scenario = Scenario.fromNode(node);
            String dimension = com.bootshift.stages.stage10.ScenarioBuilder
                    .differentialDimension(scenario.dimension());

            // Every scenario holding an observation on both sides is compared, whether or not this
            // edge's plan named its dimension.
            //
            // The plan's dimension list is derived from the impact set, and the impact set is a
            // statement about what the harness expects to change - not about what was measured. When
            // it named only CONTEXT_CAPABILITY, this loop skipped scenarios whose OLD observation had
            // been frozen against the original application and whose NEW observation had already been
            // executed against the migrated one: HTTP status codes, security headers, response shapes,
            // both sides captured, sitting on disk, thrown away. Roughly half the executed oracles
            // produced no comparison at all, and the dimensions they covered were then reported as
            // "no comparison executed".
            //
            // The frozen dimension list still means something - it says which dimensions this edge is
            // *required* to account for, and it still drives the module-level fallback and the
            // coverage statement. It does not decide which measurements are allowed to be looked at.
            boolean planRequired = requiredDimensions.isEmpty() || requiredDimensions.contains(dimension);

            ObjectNode comparison = Json.obj();
            comparison.put("scenario_id", scenario.scenarioId());
            comparison.put("dimension", dimension);
            comparison.put("plan_required_dimension", planRequired);
            comparison.put("scenario_dimension", scenario.dimension().name());
            comparison.put("module", scenario.module());
            comparison.put("target", scenario.target());
            comparison.put("scenario_state", scenario.state().name());
            comparison.put("normalization_policy_hash", policy.policyHash());
            comparison.put("comparison_unit", "SCENARIO");
            comparison.put("impact_id", scenario.impactId());
            comparison.set("knowledge_refs", Json.toTree(scenario.knowledgeRefs()));

            ScenarioObservation oldSide = oldByScenario.get(scenario.scenarioId());
            ScenarioObservation newSide = newByScenario.get(scenario.scenarioId());
            comparison.put("old_evidence_ref", oldSide == null ? null : oldSide.rawHash());
            comparison.put("new_evidence_ref", newSide == null ? null : newSide.rawHash());

            if (!scenario.state().isOracle()) {
                comparison.put("classification", "NOT_COMPARED");
                comparison.put("detail", "The scenario never became an oracle (" + scenario.state()
                        + "): " + scenario.reason());
                out.add(comparison);
                continue;
            }
            if (oldSide == null || !oldSide.successful()) {
                comparison.put("classification", "NOT_COMPARED");
                comparison.put("detail", "No successful observation of the original application: "
                        + (oldSide == null ? "never executed" : oldSide.failureReason()));
                out.add(comparison);
                continue;
            }
            if (newSide == null || !newSide.successful()) {
                comparison.put("classification", "NOT_COMPARED");
                comparison.put("detail", "No successful observation of the migrated application: "
                        + (newSide == null ? "never executed" : newSide.failureReason()));
                out.add(comparison);
                continue;
            }
            if (!unsatisfied.isEmpty() && scenario.dimension().requiresExternalInfrastructure()) {
                comparison.put("classification", "NOT_COMPARED");
                comparison.put("detail", "Environment equivalence is not satisfied for a MUST_MATCH "
                        + "attribute: " + unsatisfied);
                out.add(comparison);
                continue;
            }

            covered.add(dimension);
            if (!planRequired) {
                beyondPlan.add(dimension);
            }

            Normalizer.Applied oldNormalized = Normalizer.normalize(
                    Json.toTree(oldSide.captured()), dimension);
            Normalizer.Applied newNormalized = Normalizer.normalize(
                    Json.toTree(newSide.captured()), dimension);
            comparison.set("normalization_rules_applied",
                    Json.toTree(new LinkedHashSet<>(oldNormalized.rulesApplied())));

            List<ObjectNode> differences = diff("", oldNormalized.normalized(),
                    newNormalized.normalized());
            comparison.put("difference_count", differences.size());
            comparison.set("differences", Json.toTree(differences.stream().limit(40).toList()));

            if (differences.isEmpty()) {
                comparison.put("classification", "IDENTICAL");
                comparison.put("detail", "The normalized observations of this scenario are identical "
                        + "on both sides");
                out.add(comparison);
                continue;
            }

            DecisionStore.StoredDecision approval = approvalFor(decisionStore, dimension,
                    scenario.module());
            if (approval != null) {
                comparison.put("classification", "EXPECTED");
                comparison.put("approval_ref", approval.decision().decisionId());
                comparison.put("approval_actor", approval.decision().actor());
                comparison.put("approval_actor_authentication", approval.authentication().name());
                comparison.put("detail", "Covered by a recorded human intentional-change decision");
                out.add(comparison);
                continue;
            }

            List<String> explanations = explain(differences, knowledge);
            if (!explanations.isEmpty() && explanations.size() >= differences.size()) {
                comparison.put("classification", "EXPECTED");
                comparison.set("explanation_refs", Json.toTree(explanations));
                comparison.put("detail", "Every difference is explained by a verified migration fact");
                out.add(comparison);
                continue;
            }

            // A security scenario that differs is a defect until something explains it, not merely
            // an unexplained curiosity: the difference IS the authorization decision changing.
            boolean securityRelevant = "SECURITY_AUTHORIZATION".equals(dimension);
            comparison.put("classification", securityRelevant ? "UNEXPECTED" : "UNEXPLAINED");
            comparison.set("explanation_refs", Json.toTree(explanations));
            comparison.put("detail", differences.size() + " difference(s) in " + scenario.target()
                    + (explanations.isEmpty() ? " with no verified migration fact and no recorded "
                            + "decision to explain them"
                            : ", of which " + explanations.size() + " are explained")
                    + (securityRelevant ? ". A change in what an unauthenticated or invalid-credential "
                            + "caller receives is an authorization change." : ""));
            out.add(comparison);
        }

        notes.add(out.size() + " scenario comparison(s) across " + covered.size() + " dimension(s)");
        if (newObservations == null) {
            notes.add("The migrated side produced no scenario observations, so every scenario is "
                    + "NOT_COMPARED");
        }
        return new ScenarioComparison(covered, beyondPlan, notes);
    }
}
