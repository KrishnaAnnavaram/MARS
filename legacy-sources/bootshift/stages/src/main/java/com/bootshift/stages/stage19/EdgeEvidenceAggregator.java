package com.bootshift.stages.stage19;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;
import com.bootshift.stages.EdgeIndex;
import com.bootshift.stages.StageContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregates evidence across every planned migration edge.
 *
 * <p>Final evidence used to read {@code output/13-build-repair/latest.json},
 * {@code output/15-test/latest.json} and so on. Each of those pointers names one directory: the one
 * the stage published most recently. In a run with eight planned edges that is the eighth edge, and
 * the report described it as though it described the migration. An edge that failed to compile
 * halfway through was simply absent from the evidence.
 *
 * <p>This class walks the edge index instead, which records which directory each stage published
 * into for each edge, and proves - per edge - that it was planned, transformed, compiled, graph
 * verified, scope verified, tested where required, run where required, compared where required, and
 * that its residuals are accounted for.
 */
public final class EdgeEvidenceAggregator {

    /** One edge's complete evidence picture. */
    public record EdgeEvidence(String edgeId, String edgeClass, String sourceState, String targetState,
                               boolean planned, boolean transformed, boolean compiled,
                               boolean graphVerified, boolean scopeVerified,
                               boolean testsRequired, boolean testsExecuted,
                               boolean runtimeRequired, boolean runtimeExecuted,
                               boolean differentialRequired, boolean differentialExecuted,
                               boolean checkpointExists, boolean complete,
                               int changesApplied, int changesRejected, int residualRecipes,
                               int totalTests, long testRegressions,
                               int modulesStarted, int modulesAttempted,
                               Map<String, Integer> differentialCounts,
                               List<String> shortfalls) {

        /** True when this edge did everything its own frozen plan required of it. */
        public boolean satisfiesPlan() {
            return shortfalls.isEmpty();
        }
    }

    /** The whole migration's per-edge picture. */
    public record Aggregate(List<EdgeEvidence> edges, List<String> shortfalls,
                            Map<String, Integer> differentialTotals,
                            int totalChangesApplied, int totalResidualRecipes,
                            int totalTests, long totalTestRegressions,
                            int edgesPlanned, int edgesComplete) {

        public boolean allEdgesAccountedFor() {
            return edgesPlanned > 0 && edgesComplete == edgesPlanned && shortfalls.isEmpty();
        }
    }

    private EdgeEvidenceAggregator() {
    }

    /**
     * Builds the aggregate.
     *
     * <p>The frozen plan is the authority on what each edge had to do; the edge index is the
     * authority on what it did. A requirement in the plan with no matching artifact in the index is a
     * shortfall, never a pass by omission.
     */
    public static Aggregate aggregate(StageContext context, JsonNode edgePlanArtifact) {
        EdgeIndex index = EdgeIndex.open(context);
        List<EdgeEvidence> edges = new ArrayList<>();
        List<String> shortfalls = new ArrayList<>();
        Map<String, Integer> differentialTotals = new java.util.TreeMap<>();
        int totalApplied = 0;
        int totalResidual = 0;
        int totalTests = 0;
        long totalRegressions = 0;
        int complete = 0;

        List<JsonNode> plannedEdges = new ArrayList<>();
        if (edgePlanArtifact != null) {
            edgePlanArtifact.path("edges").forEach(plannedEdges::add);
        }

        for (JsonNode plan : plannedEdges) {
            String edgeId = plan.path("edge_id").asText();
            Optional<EdgeIndex.EdgeRecord> record = index.edge(edgeId);
            List<String> edgeShortfalls = new ArrayList<>();

            boolean testsRequired = plan.path("tests_required").asBoolean(false);
            boolean runtimeRequired = plan.path("runtime_required").asBoolean(false);
            boolean differentialRequired = plan.path("differential_required").asBoolean(false);

            JsonNode transformation = artifact(context, index, edgeId, "12-transformation",
                    "transformation-report.json");
            JsonNode build = artifact(context, index, edgeId, "13-build-repair", "build-report.json");
            JsonNode scope = artifact(context, index, edgeId, "14-graph-diff", "scope-assertion.json");
            JsonNode tests = artifact(context, index, edgeId, "15-test", "test-report.json");
            JsonNode runtime = artifact(context, index, edgeId, "16-runtime", "runtime-report.json");
            JsonNode differential = artifact(context, index, edgeId, "17-differential",
                    "differential-report.json");

            boolean transformed = transformation != null;
            boolean compiled = build != null && build.path("compiled").asBoolean(false);
            boolean graphVerified = scope != null;
            boolean scopeVerified = scope != null && scope.path("scope_ok").asBoolean(false);
            boolean testsExecuted = tests != null;
            boolean runtimeExecuted = runtime != null;
            boolean differentialExecuted = differential != null;
            boolean checkpointExists = record.isPresent()
                    && !record.get().stageDirectories().isEmpty();
            boolean edgeComplete = record.map(EdgeIndex.EdgeRecord::complete).orElse(false);

            if (!transformed) {
                edgeShortfalls.add("no transformation artifact");
            }
            if (!compiled) {
                edgeShortfalls.add("did not compile");
            }
            if (!graphVerified) {
                edgeShortfalls.add("no graph rebuild or scope assertion");
            } else if (!scopeVerified) {
                edgeShortfalls.add("scope assertion did not pass");
            }
            if (testsRequired && !testsExecuted) {
                edgeShortfalls.add("tests required by the frozen depth but no test report exists");
            }
            if (runtimeRequired && !runtimeExecuted) {
                edgeShortfalls.add("runtime required by the frozen depth but no runtime report exists");
            }
            if (differentialRequired && !differentialExecuted) {
                edgeShortfalls.add("differential required by the frozen depth but no differential "
                        + "report exists");
            }
            if (!edgeComplete) {
                edgeShortfalls.add("edge never reached EDGE_COMPLETE");
            }

            int applied = transformation == null ? 0 : transformation.path("applied").asInt();
            int rejected = transformation == null ? 0 : transformation.path("rejected").asInt();
            int residual = 0;
            if (transformation != null) {
                residual = transformation.path("residual_recipes").size();
            }
            int edgeTests = tests == null ? 0 : tests.path("total_tests").asInt();
            long regressions = tests == null ? 0
                    : tests.path("classification_counts").path("EDGE_LOCAL_REGRESSION").asLong(0)
                            + tests.path("classification_counts").path("CUMULATIVE_REGRESSION").asLong(0);
            int started = runtime == null ? 0 : runtime.path("modules_started").asInt();
            int attempted = runtime == null ? 0 : runtime.path("modules_attempted").asInt();

            Map<String, Integer> counts = new java.util.TreeMap<>();
            if (differential != null) {
                differential.path("classification_counts").fields().forEachRemaining(e -> {
                    counts.put(e.getKey(), e.getValue().asInt());
                    differentialTotals.merge(e.getKey(), e.getValue().asInt(), Integer::sum);
                });
            }

            totalApplied += applied;
            totalResidual += residual;
            totalTests += edgeTests;
            totalRegressions += regressions;
            if (edgeComplete) {
                complete++;
            }
            edgeShortfalls.forEach(sf -> shortfalls.add(edgeId + ": " + sf));

            edges.add(new EdgeEvidence(edgeId, plan.path("edge_class").asText(),
                    plan.path("source_state").asText(), plan.path("target_state").asText(),
                    true, transformed, compiled, graphVerified, scopeVerified,
                    testsRequired, testsExecuted, runtimeRequired, runtimeExecuted,
                    differentialRequired, differentialExecuted, checkpointExists, edgeComplete,
                    applied, rejected, residual, edgeTests, regressions, started, attempted,
                    counts, edgeShortfalls));
        }

        return new Aggregate(edges, shortfalls, differentialTotals, totalApplied, totalResidual,
                totalTests, totalRegressions, plannedEdges.size(), complete);
    }

    /**
     * Reads an edge's artifact through the index, falling back to the stage pointer only when the
     * plan holds exactly one edge.
     *
     * <p>The fallback exists so a single-edge migration still works if the index was not written;
     * with more than one edge it is deliberately absent, because that is precisely the case where
     * the pointer would return another edge's artifact and it would look like an answer.
     */
    private static JsonNode artifact(StageContext context, EdgeIndex index, String edgeId,
                                     String stageDirectory, String artifactName) {
        Optional<JsonNode> found = index.artifact(context, edgeId, stageDirectory, artifactName);
        if (found.isPresent()) {
            return found.get();
        }
        if (index.edges().size() > 1) {
            return null;
        }
        JsonNode latest = context.run().output().readLatest(stageDirectory, artifactName);
        if (latest == null) {
            return null;
        }
        // Even in the single-edge case, only accept it when it names this edge.
        String recorded = latest.path("edge_id").asText(null);
        return recorded == null || recorded.equals(edgeId) ? latest : null;
    }

    /** Renders the per-edge table that goes into the evidence artifact. */
    public static ObjectNode toNode(Aggregate aggregate) {
        ObjectNode node = Json.obj();
        node.put("edges_planned", aggregate.edgesPlanned());
        node.put("edges_complete", aggregate.edgesComplete());
        node.put("all_edges_accounted_for", aggregate.allEdgesAccountedFor());
        node.put("total_changes_applied", aggregate.totalChangesApplied());
        node.put("total_residual_recipes", aggregate.totalResidualRecipes());
        node.put("total_tests", aggregate.totalTests());
        node.put("total_test_regressions", aggregate.totalTestRegressions());
        node.set("differential_totals", Json.toTree(aggregate.differentialTotals()));
        node.set("shortfalls", Json.toTree(aggregate.shortfalls()));
        node.put("aggregation_rule", "Evidence is gathered per edge through the edge index. A stage "
                + "pointer names only the edge that ran last, so reading one would describe a single "
                + "edge and claim it for the migration.");

        List<ObjectNode> rows = new ArrayList<>();
        for (EdgeEvidence edge : aggregate.edges()) {
            ObjectNode row = Json.obj();
            row.put("edge_id", edge.edgeId());
            row.put("edge_class", edge.edgeClass());
            row.put("source_state", edge.sourceState());
            row.put("target_state", edge.targetState());
            row.put("planned", edge.planned());
            row.put("transformed", edge.transformed());
            row.put("compiled", edge.compiled());
            row.put("graph_verified", edge.graphVerified());
            row.put("scope_verified", edge.scopeVerified());
            row.put("tests_required", edge.testsRequired());
            row.put("tests_executed", edge.testsExecuted());
            row.put("runtime_required", edge.runtimeRequired());
            row.put("runtime_executed", edge.runtimeExecuted());
            row.put("differential_required", edge.differentialRequired());
            row.put("differential_executed", edge.differentialExecuted());
            row.put("checkpoint_exists", edge.checkpointExists());
            row.put("complete", edge.complete());
            row.put("changes_applied", edge.changesApplied());
            row.put("changes_rejected", edge.changesRejected());
            row.put("residual_recipes", edge.residualRecipes());
            row.put("tests", edge.totalTests());
            row.put("test_regressions", edge.testRegressions());
            row.put("modules_started", edge.modulesStarted());
            row.put("modules_attempted", edge.modulesAttempted());
            row.set("differential_counts", Json.toTree(edge.differentialCounts()));
            row.set("shortfalls", Json.toTree(edge.shortfalls()));
            rows.add(row);
        }
        node.set("edges", Json.toTree(rows));
        return node;
    }

    /** Every differential comparison across every edge, for mechanical evidence-level assignment. */
    public static List<JsonNode> allComparisons(StageContext context, JsonNode edgePlanArtifact) {
        EdgeIndex index = EdgeIndex.open(context);
        List<JsonNode> comparisons = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> edgeIds = new ArrayList<>();
        if (edgePlanArtifact != null) {
            edgePlanArtifact.path("edges").forEach(e -> edgeIds.add(e.path("edge_id").asText()));
        }
        for (String edgeId : edgeIds) {
            JsonNode report = artifact(context, index, edgeId, "17-differential",
                    "differential-report.json");
            if (report == null) {
                continue;
            }
            for (JsonNode comparison : report.path("comparisons")) {
                ObjectNode copy = comparison.deepCopy();
                copy.put("edge_id", edgeId);
                String key = edgeId + "|" + comparison.path("dimension").asText() + "|"
                        + comparison.path("module").asText() + "|"
                        + comparison.path("scenario_id").asText("");
                if (seen.add(key)) {
                    comparisons.add(copy);
                }
            }
        }
        return comparisons;
    }

    /** Scenario observation counts across every edge, used to decide whether E4 is reachable. */
    public static Map<String, Integer> scenarioExecutionCounts(StageContext context,
                                                               JsonNode edgePlanArtifact) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode comparison : allComparisons(context, edgePlanArtifact)) {
            String dimension = comparison.path("dimension").asText();
            String classification = comparison.path("classification").asText();
            counts.merge(dimension + "|" + classification, 1, Integer::sum);
            counts.merge(dimension + "|TOTAL", 1, Integer::sum);
        }
        return counts;
    }
}
