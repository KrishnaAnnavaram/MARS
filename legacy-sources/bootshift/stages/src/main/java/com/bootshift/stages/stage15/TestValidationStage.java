package com.bootshift.stages.stage15;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.MavenBuildAdapter;
import com.bootshift.adapters.build.ToolchainProbe;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.approval.ApprovalPort;
import com.bootshift.ports.approval.DecisionStore;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.EdgeToolchain;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.ValidationSupport;
import com.bootshift.stages.stage03.ApplicationGraphStage;
import com.bootshift.stages.stage04.BaselineStage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Agent 15 - Test Validation (spec section 31).
 *
 * <p>Runs the application's own tests and classifies every outcome against <em>two</em> baselines:
 * the sealed original, and the previous successful edge. Without both, a pre-existing failure and a
 * regression introduced three edges ago look identical.
 *
 * <p>Coverage is compared the same way, and an unexplained material drop is a gated signal. The
 * harness never manipulates instrumentation, excludes modules or disables tests to make a number
 * look better - the forbidden actions in spec section 31 are absent from this code by construction.
 */
public final class TestValidationStage implements Stage {

    public static final String OUTPUT_DIR = "15-test";

    /** Outcome classes from spec section 31. */
    public enum Classification {
        PASSED,
        PRE_EXISTING_FAILURE,
        EDGE_LOCAL_REGRESSION,
        CUMULATIVE_REGRESSION,
        EXPECTED_FRAMEWORK_CHANGE,
        INTENTIONALLY_CHANGED_CONTRACT,
        FLAKY,
        UNEXPLAINED
    }

    private final String edgeId;

    public TestValidationStage(String edgeId) {
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
        return "Run the application test suite and classify results against baseline and previous edge";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.EDGE_SCOPE_VERIFIED);
    }

    @Override
    public RunState postcondition() {
        return RunState.EDGE_TESTED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("04-baseline/baseline-tests.json", "04-baseline/baseline-coverage.json",
                "11-plan/edge-plan.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("test-report.json", "coverage-report.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode edgePlanArtifact = StageSupport.requireUpstream(context, "11-plan", "edge-plan.json",
                "Run: harness plan");
        JsonNode edgePlan = EdgeSupport.findEdge(edgePlanArtifact, edgeId);
        JsonNode baselineTests = StageSupport.requireUpstream(context, "04-baseline",
                "baseline-tests.json", "Run: harness baseline --repo <path>");
        JsonNode baselineCoverage = StageSupport.optionalUpstream(context, "04-baseline",
                "baseline-coverage.json");
        JsonNode previousEdgeTests = StageSupport.optionalUpstream(context, OUTPUT_DIR, "test-report.json");
        JsonNode buildNode = StageSupport.requireUpstream(context, "02-build", "build-model.json",
                "Run: harness resolve-build --repo <path>");
        JsonNode dependencyNode = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");
        JsonNode knowledge = StageSupport.optionalUpstream(context, "08-knowledge",
                "migration-knowledge.json");
        // Decisions from the store, for the same reason the differential stage reads them there:
        // a test-expectation decision must be able to influence the validation it was filed for.
        DecisionStore decisionStore = context.decisions();

        if (!edgePlan.path("tests_required").asBoolean(true)) {
            return skip(context, "The frozen plan for " + edgeId + " does not require tests at depth "
                    + edgePlan.path("frozen_validation_depth").asText());
        }

        BuildSystemPort.BuildModel buildModel =
                ApplicationGraphStage.readBuildModel(buildNode, dependencyNode);
        Path workspace = context.run().migrationWorkspace();
        Path logs = context.run().runWorkspace().resolve("test-logs").resolve(safe(edgeId));

        // Java 17 was hardcoded here. An edge that froze a different major therefore ran its tests
        // on a JDK the plan did not select, and the resulting pass or fail described that JDK.
        EdgeToolchain toolchain = EdgeToolchain.forEdge(edgePlan, buildModel);
        EdgeToolchain.Verification toolchainVerification = toolchain.verify();
        String javaHome = toolchain.resolved().javaHome();
        MavenBuildAdapter maven = new MavenBuildAdapter();

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId);

        Map<String, JsonNode> baselineByModule = index(baselineTests);
        Map<String, JsonNode> previousByModule = previousEdgeTests == null
                ? Map.of() : index(previousEdgeTests);
        Map<String, JsonNode> baselineCoverageByModule = baselineCoverage == null
                ? Map.of() : index(baselineCoverage);

        ArrayNode moduleResults = Json.arr();
        ArrayNode coverageResults = Json.arr();
        Map<String, Integer> classificationCounts = new java.util.TreeMap<>();
        List<String> blockingFindings = new ArrayList<>();
        int totalTests = 0;

        for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
            Path moduleRoot = ".".equals(module.moduleId()) ? workspace
                    : workspace.resolve(module.moduleId());
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            ValidationSupport.TestRun run = ValidationSupport.runTests(maven, moduleRoot,
                    logs.resolve(safe(module.moduleId()) + "-test.log"), javaHome);
            BaselineStage.TestOutcome outcome = BaselineStage.parseTestReports(moduleRoot);
            totalTests += outcome.total();

            JsonNode baselineModule = baselineByModule.get(module.moduleId());
            JsonNode previousModule = previousByModule.get(module.moduleId());
            Map<String, String> baselineOutcomes = caseOutcomes(baselineModule);
            Map<String, String> previousOutcomes = caseOutcomes(previousModule);

            ObjectNode moduleNode = Json.obj();
            moduleNode.put("module", module.moduleId());
            moduleNode.put("executed", run.execution().success() || outcome.total() > 0);
            moduleNode.put("tests", outcome.total());
            moduleNode.put("passed", outcome.passed());
            moduleNode.put("failed", outcome.failed());
            moduleNode.put("errors", outcome.errors());
            moduleNode.put("skipped", outcome.skipped());
            moduleNode.put("retried_without_coverage", run.retriedWithoutCoverage());

            // Flaky detection. A test that fails once and passes on an identical re-run has not
            // been shown to be a regression, and classifying it as one blocks a migration for a
            // reason that has nothing to do with the migration. The re-run is only performed for
            // tests that actually failed, and only once: a test that needs three attempts is not
            // flaky, it is broken.
            Set<String> flakyTests = detectFlaky(maven, moduleRoot, outcome,
                    logs.resolve(safe(module.moduleId()) + "-flaky-rerun.log"), toolchain);
            moduleNode.put("flaky_rerun_performed", !flakyTests.isEmpty()
                    || outcome.failed() + outcome.errors() > 0);
            moduleNode.set("flaky_tests", Json.toTree(flakyTests));

            ArrayNode cases = Json.arr();
            for (BaselineStage.TestCase testCase : outcome.cases()) {
                String key = testCase.className() + "#" + testCase.name();
                String baselineOutcome = baselineOutcomes.get(key);
                String previousOutcome = previousOutcomes.get(key);
                Classification classification = flakyTests.contains(key)
                        ? Classification.FLAKY
                        : classify(testCase, baselineOutcome, previousOutcome, knowledge,
                                decisionStore);
                classificationCounts.merge(classification.name(), 1, Integer::sum);

                ObjectNode caseNode = Json.obj();
                caseNode.put("test", key);
                caseNode.put("outcome", testCase.outcome());
                caseNode.put("baseline_outcome", baselineOutcome);
                caseNode.put("previous_edge_outcome", previousOutcome);
                caseNode.put("classification", classification.name());
                caseNode.put("duration_seconds", testCase.durationSeconds());
                caseNode.put("detail", testCase.detail());
                String probableCause = probableCause(testCase.detail());
                caseNode.put("probable_cause", probableCause);
                if (classification == Classification.UNEXPLAINED
                        || classification == Classification.EDGE_LOCAL_REGRESSION
                        || classification == Classification.CUMULATIVE_REGRESSION) {
                    blockingFindings.add(classification + ": " + key
                            + (probableCause == null ? "" : " [" + probableCause + "]")
                            + (testCase.detail() == null ? ""
                            : " - " + abbreviate(testCase.detail())));
                }
                cases.add(caseNode);
            }
            moduleNode.set("cases", cases);
            moduleResults.add(moduleNode);

            // ---- coverage comparison against both baselines
            BaselineStage.CoverageOutcome coverage = run.coverageUsable()
                    ? BaselineStage.parseJacoco(moduleRoot)
                    : new BaselineStage.CoverageOutcome(false, 0, 0, 0, run.coverageUnavailableReason());
            JsonNode baselineCoverageNode = baselineCoverageByModule.get(module.moduleId());
            double baselineInstruction = baselineCoverageNode == null ? 0
                    : baselineCoverageNode.path("instruction_covered_ratio").asDouble(0);
            boolean baselineAvailable = baselineCoverageNode != null
                    && baselineCoverageNode.path("available").asBoolean(false);

            ObjectNode coverageNode = Json.obj();
            coverageNode.put("module", module.moduleId());
            coverageNode.put("available", coverage.available());
            coverageNode.put("baseline_available", baselineAvailable);
            coverageNode.put("instruction_covered_ratio", coverage.instructionRatio());
            coverageNode.put("baseline_instruction_covered_ratio", baselineInstruction);
            coverageNode.put("branch_covered_ratio", coverage.branchRatio());
            coverageNode.put("detail", coverage.detail());

            if (coverage.available() && baselineAvailable) {
                double dropPoints = (baselineInstruction - coverage.instructionRatio()) * 100.0;
                coverageNode.put("percentage_point_change",
                        Math.round(-dropPoints * 100.0) / 100.0);
                boolean gated = context.policy().coverageGateEnabled()
                        && dropPoints > context.policy().coverageDropBlockPercentagePoints();
                coverageNode.put("gate_triggered", gated);
                if (gated) {
                    blockingFindings.add("COVERAGE_REGRESSION: " + module.moduleId() + " dropped "
                            + Math.round(dropPoints * 100.0) / 100.0 + " percentage points, exceeding "
                            + "the policy threshold of "
                            + context.policy().coverageDropBlockPercentagePoints());
                }
            } else {
                coverageNode.put("gate_triggered", false);
                coverageNode.put("gate_note", "Coverage could not be compared for this module; the "
                        + "gate is reported as not evaluated rather than as passed");
                envelope.gap(new Envelope.Gap("GAP-COV-" + safe(module.moduleId()), "COVERAGE",
                        "Coverage comparison unavailable for " + module.moduleId(),
                        coverage.detail() == null ? "no baseline coverage" : coverage.detail()));
            }
            coverageResults.add(coverageNode);
        }

        if (!toolchainVerification.verified()) {
            blockingFindings.add("TOOLCHAIN_MISMATCH: " + toolchainVerification.detail());
        }

        long unexplained = classificationCounts.getOrDefault(Classification.UNEXPLAINED.name(), 0);
        long regressions = classificationCounts.getOrDefault(
                Classification.EDGE_LOCAL_REGRESSION.name(), 0)
                + classificationCounts.getOrDefault(Classification.CUMULATIVE_REGRESSION.name(), 0);

        envelope.stat("tests", totalTests)
                .stat("unexplained", unexplained)
                .stat("regressions", regressions);

        ObjectNode report = Json.obj();
        report.set("toolchain", toolchain.toNode(toolchainVerification));
        report.put("total_tests", totalTests);
        report.set("classification_counts", Json.toTree(classificationCounts));
        report.set("modules", moduleResults);
        report.put("comparison_rule", "Every result is classified against both the sealed original "
                + "baseline and the previous successful edge");
        report.put("forbidden_actions", "The harness never disables tests, deletes tests, weakens "
                + "assertions, excludes modules or alters coverage scope to make a result pass");
        ObjectNode reportArtifact = StageSupport.compose(envelope, report);
        StageSupport.validate(context, writer, "validation/test-report.schema.json",
                "test-report.json", reportArtifact);
        writer.write("test-report.json", reportArtifact);

        ObjectNode coverageReport = Json.obj();
        coverageReport.put("gate_enabled", context.policy().coverageGateEnabled());
        coverageReport.put("threshold_percentage_points",
                context.policy().coverageDropBlockPercentagePoints());
        coverageReport.put("note", "Preserved coverage is not proof of preserved behaviour; an "
                + "unexplained material drop is nonetheless a migration-regression signal");
        coverageReport.set("modules", coverageResults);
        writer.write("coverage-report.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        coverageReport));

        String hash = StageSupport.publishForEdge(context, writer, edgeId, OUTPUT_DIR,
                com.bootshift.stages.EdgeIndex.Phase.TESTED, "published");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        if (!blockingFindings.isEmpty()) {
            context.stateMachine().transition(RunState.BLOCKED,
                    blockingFindings.size() + " blocking test finding(s)");
            context.runStateStore().updateState(context.run().runId(), RunState.BLOCKED,
                    "test validation blocked");
            return new StageResult(OUTPUT_DIR, ExitCode.POLICY_BLOCK,
                    "Edge " + edgeId + ": " + blockingFindings.size()
                            + " blocking test or coverage finding(s)",
                    blockingFindings, artifacts, hash);
        }

        context.stateMachine().transition(RunState.EDGE_TESTED, totalTests + " test(s)");
        context.runStateStore().updateState(context.run().runId(), RunState.EDGE_TESTED,
                "edge " + edgeId + " tested");
        EdgeSupport.checkpoint(context, edgeId, "tested", "Edge " + edgeId + " tests validated");

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Edge " + edgeId + ": " + totalTests + " test(s), " + classificationCounts,
                List.of(), artifacts, hash);
    }

    // ------------------------------------------------------------------ classification

    /**
     * Classifies one test outcome.
     *
     * <p>An EXPECTED_FRAMEWORK_CHANGE must reference verified migration evidence and an
     * INTENTIONALLY_CHANGED_CONTRACT must reference a signed approval. Without one of those, a new
     * failure is a regression or UNEXPLAINED - never quietly accepted.
     */
    static Classification classify(BaselineStage.TestCase testCase, String baselineOutcome,
                                   String previousOutcome, JsonNode knowledge,
                                   DecisionStore decisionStore) {
        boolean failingNow = "FAILED".equals(testCase.outcome()) || "ERROR".equals(testCase.outcome());
        if (!failingNow) {
            return Classification.PASSED;
        }
        boolean failedAtBaseline = baselineOutcome != null
                && (baselineOutcome.equals("FAILED") || baselineOutcome.equals("ERROR"));
        if (failedAtBaseline) {
            return Classification.PRE_EXISTING_FAILURE;
        }
        if (baselineOutcome == null) {
            // A test that did not exist at baseline cannot be compared to it.
            return Classification.UNEXPLAINED;
        }

        String detail = testCase.detail() == null ? "" : testCase.detail();
        if (hasRecordedApproval(decisionStore, testCase)) {
            return Classification.INTENTIONALLY_CHANGED_CONTRACT;
        }
        if (explainedByVerifiedFact(knowledge, detail)) {
            return Classification.EXPECTED_FRAMEWORK_CHANGE;
        }
        boolean failedPreviousEdge = previousOutcome != null
                && (previousOutcome.equals("FAILED") || previousOutcome.equals("ERROR"));
        return failedPreviousEdge
                ? Classification.CUMULATIVE_REGRESSION : Classification.EDGE_LOCAL_REGRESSION;
    }

    /**
     * Names the probable cause of a failure when the message points at absent infrastructure.
     *
     * <p>This does not change the classification: a test that cannot reach a database still blocks if
     * the baseline never showed the same failure. It changes what an operator reads first, which is
     * the difference between "the migration broke persistence" and "MongoDB is not running".
     */
    static String probableCause(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("mongosocketopenexception") || lower.contains("mongotimeoutexception")
                || lower.contains("27017")) {
            return "INFRASTRUCTURE_UNAVAILABLE: MongoDB";
        }
        if (lower.contains("connection refused") && lower.contains("5432")) {
            return "INFRASTRUCTURE_UNAVAILABLE: PostgreSQL";
        }
        if (lower.contains("connection refused") && lower.contains("6379")) {
            return "INFRASTRUCTURE_UNAVAILABLE: Redis";
        }
        if (lower.contains("connection refused") && lower.contains("9092")) {
            return "INFRASTRUCTURE_UNAVAILABLE: Kafka";
        }
        if (lower.contains("connection refused") || lower.contains("unknownhostexception")
                || lower.contains("sockettimeoutexception")) {
            return "INFRASTRUCTURE_UNAVAILABLE: an external endpoint was unreachable";
        }
        if (lower.contains("classnotfoundexception") || lower.contains("noclassdeffounderror")) {
            return "MISSING_TYPE: a class the test needs is absent at the target version";
        }
        if (lower.contains("nosuchmethoderror")) {
            return "REMOVED_API: a method the test calls no longer exists";
        }
        if (lower.contains("beancreationexception") || lower.contains("nosuchbeandefinition")) {
            return "CONTEXT_CAPABILITY: the application context could not be built";
        }
        return null;
    }

    private static String abbreviate(String detail) {
        String collapsed = detail.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "...";
    }

    private static boolean explainedByVerifiedFact(JsonNode knowledge, String detail) {
        if (knowledge == null || detail.isBlank()) {
            return false;
        }
        for (JsonNode fact : knowledge.path("facts")) {
            if (!fact.path("authorizes_transformation").asBoolean(false)) {
                continue;
            }
            String subject = fact.path("subject").asText("");
            if (subject.length() > 6 && detail.contains(subject)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a human recorded that this test's expectation changed intentionally.
     *
     * <p>The scope has to name the test. A blanket decision cannot reclassify an arbitrary failure,
     * because a gate that can be satisfied without naming what it covers is not a gate.
     */
    private static boolean hasRecordedApproval(DecisionStore store, BaselineStage.TestCase testCase) {
        if (store == null) {
            return false;
        }
        String key = testCase.className() + "#" + testCase.name();
        for (DecisionStore.StoredDecision stored : store.matching(
                ApprovalPort.Gate.TEST_EXPECTATION_CHANGE, key)) {
            if (stored.decision().verdict() == ApprovalPort.Verdict.APPROVED
                    && stored.decision().scope() != null
                    && stored.decision().scope().contains(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private StageResult skip(StageContext context, String reason) {
        context.stateMachine().transition(RunState.EDGE_TESTED, "skipped: " + reason);
        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS, reason, List.of(), Map.of(), null);
    }

    private static Map<String, JsonNode> index(JsonNode artifact) {
        Map<String, JsonNode> byModule = new LinkedHashMap<>();
        artifact.path("modules").forEach(m -> byModule.put(m.path("module").asText(), m));
        return byModule;
    }

    private static Map<String, String> caseOutcomes(JsonNode moduleNode) {
        Map<String, String> outcomes = new LinkedHashMap<>();
        if (moduleNode == null) {
            return outcomes;
        }
        for (JsonNode testCase : moduleNode.path("cases")) {
            String key = testCase.has("test") ? testCase.path("test").asText()
                    : testCase.path("className").asText() + "#" + testCase.path("name").asText();
            outcomes.put(key, testCase.path("outcome").asText());
        }
        return outcomes;
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ------------------------------------------------------------------ flaky detection

    /**
     * Re-runs the tests that failed and reports which of them passed the second time.
     *
     * <p>{@code FLAKY} was a declared classification that nothing could ever produce, so a test that
     * failed intermittently was classified as a regression and blocked the edge. A single identical
     * re-run is the smallest thing that can distinguish the two, and it is deliberately not repeated:
     * a test that needs three attempts to pass is not flaky in any sense that should let a migration
     * proceed.
     *
     * <p>The re-run uses the same toolchain and the same command as the original, so a pass on the
     * second attempt is a statement about the test rather than about the environment changing.
     */
    private Set<String> detectFlaky(MavenBuildAdapter maven, Path moduleRoot,
                                    BaselineStage.TestOutcome outcome, Path logSink,
                                    EdgeToolchain toolchain) {
        List<String> failing = new ArrayList<>();
        for (BaselineStage.TestCase testCase : outcome.cases()) {
            if ("FAILED".equals(testCase.outcome()) || "ERROR".equals(testCase.outcome())) {
                failing.add(testCase.className() + "#" + testCase.name());
            }
        }
        if (failing.isEmpty() || failing.size() > 40) {
            // A whole suite failing is not flakiness; it is a broken build, and re-running it costs
            // as much as the original run for no information.
            return Set.of();
        }

        Map<String, String> options = new LinkedHashMap<>(toolchain.buildOptions(logSink));
        String selection = String.join(",", failing.stream()
                .map(test -> test.replace('#', '#'))
                .toList());
        BuildSystemPort.ExecutionResult rerun = maven.invoke(moduleRoot,
                List.of("-B", "test", "-Dtest=" + selection, "-DfailIfNoSpecifiedTests=false"),
                options);
        if (rerun.exitCode() == -1) {
            return Set.of();
        }

        BaselineStage.TestOutcome second = BaselineStage.parseTestReports(moduleRoot);
        Set<String> passedOnRerun = new java.util.LinkedHashSet<>();
        for (BaselineStage.TestCase testCase : second.cases()) {
            String key = testCase.className() + "#" + testCase.name();
            if (failing.contains(key) && "PASSED".equals(testCase.outcome())) {
                passedOnRerun.add(key);
            }
        }
        return passedOnRerun;
    }
}
