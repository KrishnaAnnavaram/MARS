package com.bootshift.stages;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one place a stage is entered from.
 *
 * <p>Every stage declares preconditions, required input artifacts and a postcondition, and until now
 * nothing checked any of it: {@code stage.execute(context)} was called directly by the orchestrator
 * and by every CLI command. A stage run out of order therefore failed somewhere in the middle with
 * a message about a missing JSON field rather than refusing at the door with a message about what to
 * run first.
 *
 * <p>Checks, in order: the declared states have been reached, the declared input artifacts exist and
 * parse, and - for an edge-scoped stage - the edge is in a state the stage can legally act on. Only
 * then does {@code execute()} run.
 */
public final class StageExecutor {

    /** Why a stage was refused, with the remediation an operator can act on. */
    public record Precondition(boolean satisfied, List<String> violations, List<String> remediation) {
    }

    private StageExecutor() {
    }

    /** Runs a stage after verifying it is legal to run. */
    public static StageResult run(Stage stage, StageContext context) {
        Precondition check = verify(stage, context);
        if (!check.satisfied()) {
            return StageResult.failure(stage.id(), ExitCode.STRUCTURED_REFUSAL,
                    "Stage " + stage.id() + " cannot run yet: " + check.violations().size()
                            + " precondition(s) unsatisfied",
                    concat(check.violations(), check.remediation()));
        }
        return stage.execute(context);
    }

    /**
     * Verifies a stage's declared preconditions without running it.
     *
     * <p>State is reconstructed from the artifact plane, never from a stored cursor: a stage that
     * published is treated as having happened regardless of what any state file claims (R23).
     */
    public static Precondition verify(Stage stage, StageContext context) {
        List<String> violations = new ArrayList<>();
        List<String> remediation = new ArrayList<>();

        RunState current = context.stateMachine().current();
        for (RunState required : stage.preconditions()) {
            if (!hasReached(context, required)) {
                violations.add("Required state " + required + " has not been reached (current: "
                        + current + ")");
                remediation.add(remediationFor(required));
            }
        }

        for (String artifact : stage.inputArtifacts()) {
            int slash = artifact.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            String stageDirectory = artifact.substring(0, slash);
            String name = artifact.substring(slash + 1);
            Path path = context.run().output().latestArtifactPath(stageDirectory, name);
            if (path == null || !Files.isRegularFile(path)) {
                violations.add("Required input artifact " + artifact + " has not been published");
                remediation.add("Run the stage that publishes " + stageDirectory);
                continue;
            }
            // Integrity: a published artifact that no longer parses is worse than a missing one,
            // because every downstream default would silently apply.
            try {
                JsonNode node = Json.read(path);
                if (node == null || node.isMissingNode()) {
                    violations.add("Required input artifact " + artifact + " is empty");
                    remediation.add("Re-run " + stageDirectory);
                }
            } catch (RuntimeException e) {
                violations.add("Required input artifact " + artifact + " is unreadable: "
                        + e.getMessage());
                remediation.add("Re-run " + stageDirectory);
            }
        }

        return new Precondition(violations.isEmpty(), violations, remediation);
    }

    /**
     * The artifact that proves an analysis-half state was reached.
     *
     * <p>R23 says the artifact plane is the source of truth for what is true, and the state machine
     * only says where a run is. Preconditions were nevertheless answered by comparing state ordinals,
     * which is a claim about <em>position</em> - and position moves on. Once the edge loop began, the
     * cursor sat at an edge state and every analysis-half precondition evaluated false even though
     * the artifact proving it was sitting in {@code output/}. That is what refused every edge after
     * the first: {@code PLAN_FROZEN} was long since satisfied, the plan was on disk, and
     * {@code EDGE_COMPLETE >= PLAN_FROZEN} answered a question nobody had asked.
     *
     * <p>Answering from the published artifact makes the check monotonic, which is what a
     * precondition has to be: a plan that exists does not stop existing because the run moved past
     * it, and a resumed run reconstructs the same answer with no cursor at all.
     */
    private static final Map<RunState, String> PROOF_ARTIFACT = Map.ofEntries(
            Map.entry(RunState.WORKSPACE_READY, "00-bootstrap/bootstrap.json"),
            Map.entry(RunState.OSS_POLICY_VERIFIED, "00-bootstrap/oss-license-gate.json"),
            Map.entry(RunState.INVENTORY_COMPLETE, "01-inventory/inventory-artifact.json"),
            Map.entry(RunState.FILE_REGISTRY_SEALED, "01-inventory/file-registry.json"),
            Map.entry(RunState.BUILD_RESOLVED, "02-build/build-model.json"),
            Map.entry(RunState.APPLICATION_GRAPH_BUILT, "03-graph/application-graph.json"),
            Map.entry(RunState.GRAPH_VERIFIED, "03-graph/graph-verification-report.json"),
            Map.entry(RunState.BASELINE_CAPTURED, "04-baseline/baseline-build.json"),
            Map.entry(RunState.BASELINE_SEALED, "04-baseline/baseline-manifest.json"),
            Map.entry(RunState.COMPATIBILITY_REGISTRY_READY, "05-compatibility/compatibility-registry.json"),
            Map.entry(RunState.TARGET_RESOLVED, "06-target/target-state.json"),
            Map.entry(RunState.TARGET_FROZEN, "06-target/migration-path.json"),
            Map.entry(RunState.DOCUMENTATION_RETRIEVED, "07-documentation/document-registry.json"),
            Map.entry(RunState.KNOWLEDGE_VERIFIED, "08-knowledge/migration-knowledge.json"),
            Map.entry(RunState.IMPACT_ANALYZED, "09-impact/impact-report.json"),
            Map.entry(RunState.CHARACTERIZATION_COMPLETE,
                    "10-characterization/characterization-scenarios.json"),
            Map.entry(RunState.PLAN_FROZEN, "11-plan/edge-plan.json"));

    /**
     * True when the run has reached a state.
     *
     * <p>Three kinds of state, three different questions.
     *
     * <ul>
     *   <li><b>Analysis-half states</b> are proven by a published artifact ({@link #PROOF_ARTIFACT}).
     *       Once proven they stay proven, whatever the cursor does afterwards.</li>
     *   <li><b>Edge-loop states</b> are not points on the linear pipeline at all - the same stage
     *       runs once per edge - so they are answered from the edge index, which records what each
     *       edge actually did.</li>
     *   <li>Anything else falls back to the cursor.</li>
     * </ul>
     */
    private static boolean hasReached(StageContext context, RunState required) {
        RunState current = context.stateMachine().current();
        if (current == required) {
            return true;
        }
        if (PROOF_ARTIFACT.containsKey(required)) {
            return analysisStateSatisfied(required, current,
                    artifact -> publishedAndReadable(context, artifact));
        }
        if (required.isMutating()) {
            // Any edge having reached the state is enough for the stage to be legal; the stage
            // itself then checks the specific edge it was asked to act on.
            EdgeIndex index = EdgeIndex.open(context);
            return switch (required) {
                case EDGE_TRANSFORMED -> anyReached(index, EdgeIndex.Phase.TRANSFORMED);
                case EDGE_COMPILED -> anyReached(index, EdgeIndex.Phase.COMPILED);
                case EDGE_SCOPE_VERIFIED, EDGE_GRAPH_REBUILT ->
                        anyReached(index, EdgeIndex.Phase.GRAPH_VERIFIED);
                case EDGE_TESTED -> anyReached(index, EdgeIndex.Phase.TESTED);
                case EDGE_RUNTIME_VALIDATED, EDGE_RUNTIME_GRAPH_ENRICHED ->
                        anyReached(index, EdgeIndex.Phase.RUNTIME_VALIDATED);
                case EDGE_DIFFERENTIAL_VALIDATED ->
                        anyReached(index, EdgeIndex.Phase.DIFFERENTIAL_VALIDATED);
                case EDGE_COMPLETE -> index.edges().stream().anyMatch(EdgeIndex.EdgeRecord::complete);
                default -> current.ordinal() >= required.ordinal();
            };
        }
        // Linear analysis state with no declared proof: declaration order is pipeline order.
        return !current.isMutating() && current.ordinal() >= required.ordinal();
    }

    /**
     * Decides an analysis-half precondition from the artifact plane, with the cursor as fallback.
     *
     * <p>Separated from {@link #hasReached} so the rule can be exercised without standing up a run:
     * the property that matters is that a state proven by a published artifact stays proven once the
     * edge loop has moved the cursor past it.
     *
     * @param required the state the stage declared it needs
     * @param current where the run's cursor currently sits
     * @param artifactPublished answers whether a {@code stage/artifact.json} pair is on disk and
     *        readable
     */
    public static boolean analysisStateSatisfied(RunState required, RunState current,
                                                 java.util.function.Predicate<String> artifactPublished) {
        if (current == required) {
            return true;
        }
        String proof = PROOF_ARTIFACT.get(required);
        if (proof != null && artifactPublished.test(proof)) {
            return true;
        }
        // No artifact on this disk. The state may still have been reached legitimately by a run whose
        // output directory is elsewhere, so the cursor is consulted as a fallback rather than as the
        // primary answer.
        return !current.isMutating() && current.ordinal() >= required.ordinal();
    }

    /** The artifact that proves a state, or {@code null} when the state has no artifact proof. */
    public static String proofArtifactFor(RunState state) {
        return PROOF_ARTIFACT.get(state);
    }

    /** True when the artifact exists and still parses. An unreadable proof proves nothing. */
    private static boolean publishedAndReadable(StageContext context, String artifact) {
        int slash = artifact.indexOf('/');
        Path path = context.run().output()
                .latestArtifactPath(artifact.substring(0, slash), artifact.substring(slash + 1));
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        try {
            JsonNode node = Json.read(path);
            return node != null && !node.isMissingNode();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean anyReached(EdgeIndex index, EdgeIndex.Phase phase) {
        return index.edges().stream().anyMatch(record -> record.reached(phase));
    }

    private static String remediationFor(RunState required) {
        return switch (required) {
            case OSS_POLICY_VERIFIED -> "Run: bootshift inventory --repo <path>";
            case FILE_REGISTRY_SEALED -> "Run: bootshift inventory --repo <path>";
            case BUILD_RESOLVED -> "Run: bootshift resolve-build --repo <path>";
            case GRAPH_VERIFIED, APPLICATION_GRAPH_BUILT -> "Run: bootshift graph --repo <path>";
            case BASELINE_SEALED, BASELINE_CAPTURED -> "Run: bootshift baseline --repo <path>";
            case COMPATIBILITY_REGISTRY_READY -> "Run: bootshift compatibility";
            case TARGET_FROZEN, TARGET_RESOLVED -> "Run: bootshift resolve-target --target auto";
            case DOCUMENTATION_RETRIEVED -> "Run: bootshift documentation";
            case KNOWLEDGE_VERIFIED -> "Run: bootshift knowledge";
            case IMPACT_ANALYZED -> "Run: bootshift impact";
            case CHARACTERIZATION_COMPLETE -> "Run: bootshift characterize";
            case PLAN_FROZEN -> "Run: bootshift plan";
            case EDGE_TRANSFORMED -> "Run: bootshift migrate --edge <edge>";
            case EDGE_COMPILED -> "Run: bootshift migrate --edge <edge>";
            case EDGE_SCOPE_VERIFIED, EDGE_GRAPH_REBUILT, EDGE_TESTED, EDGE_RUNTIME_VALIDATED,
                 EDGE_RUNTIME_GRAPH_ENRICHED, EDGE_DIFFERENTIAL_VALIDATED ->
                    "Run: bootshift validate --edge <edge>";
            case EDGE_COMPLETE -> "Complete every planned edge: bootshift migrate";
            case FINAL_APPROVAL -> "Run: bootshift approve";
            case EVIDENCE_SEALED -> "Run: bootshift report";
            default -> "Run the stage that reaches " + required;
        };
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        second.stream().distinct().forEach(r -> all.add("remediation: " + r));
        return all;
    }

    /** Throws rather than returning, for callers that treat a refusal as fatal. */
    public static void requireLegal(Stage stage, StageContext context) {
        Precondition check = verify(stage, context);
        if (!check.satisfied()) {
            throw HarnessException.refusal("Stage " + stage.id() + " cannot run yet: "
                    + check.violations() + " " + check.remediation());
        }
    }
}
