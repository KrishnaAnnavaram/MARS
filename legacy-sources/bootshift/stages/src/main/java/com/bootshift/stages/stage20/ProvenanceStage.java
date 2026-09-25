package com.bootshift.stages.stage20;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.core.provenance.ProvenanceGraph;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 20 - Provenance Graph and Q&A (spec section 39).
 *
 * <p>Builds a queryable layer over the finished run and answers a fixed catalog of questions.
 *
 * <p>Deliberately not an LLM query interface: the answers come from validated query intents over a
 * deterministic graph, so the same question always produces the same answer and every answer can be
 * traced back to an artifact.
 */
public final class ProvenanceStage implements Stage {

    public static final String OUTPUT_DIR = "20-provenance";

    /** The fixed question catalog from spec section 39. */
    public enum Question {
        WHY_DID_THIS_FILE_CHANGE("Why did this file change?"),
        WHICH_FACT_AUTHORIZED_IT("Which migration fact authorized it?"),
        WHAT_DEPENDS_ON_THIS_FILE("What depends on this file?"),
        WHAT_IS_ITS_BLAST_RADIUS("What is its blast radius?"),
        WHICH_SYMBOLS_CHANGED("Which symbols changed?"),
        WHICH_GRAPH_EDGES_CHANGED("Which graph edges changed?"),
        WHICH_CHANGES_WERE_AI_AUTHORED("Which changes were AI-authored?"),
        WHICH_AI_PROPOSALS_WERE_REJECTED("Which AI proposals were rejected?"),
        WHICH_CHANGES_WERE_REVERTED("Which changes were reverted?"),
        WHICH_FINDINGS_REMAIN_UNRESOLVED("Which findings remain undispatched or unresolved?"),
        WHICH_VALIDATIONS_WERE_NOT_EXECUTED("Which validations were required but not executed?"),
        WHICH_DIFFERENCES_WERE_ACCEPTED("Which differences were accepted and by whom?"),
        WHICH_TESTS_WERE_FAILING_BEFORE("Which tests were failing before migration?"),
        WHICH_EVIDENCE_SUPPORTS_CLAIM("Which evidence supports claim X?"),
        WHICH_DIMENSIONS_WERE_NOT_COMPARED("Which dimensions were not compared?"),
        WHAT_CAN_THIS_RUN_NOT_SEE("What can this run NOT see?");

        private final String text;

        Question(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
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
        return "Build the provenance graph and answer the deterministic question catalog";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.EVIDENCE_SEALED);
    }

    @Override
    public RunState postcondition() {
        return RunState.MIGRATION_COMPLETE;
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("provenance-graph.json", "question-catalog.json", "blind-spots.json",
                "gaps.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        ProvenanceGraph graph = build(context);

        writer.write("provenance-graph.json", StageSupport.compose(envelope
                .stat("nodes", graph.nodeCount())
                .stat("edges", graph.edgeCount()), graph.toNode()));

        ObjectNode catalog = Json.obj();
        ArrayNode answers = Json.arr();
        for (Question question : Question.values()) {
            ObjectNode answer = Json.obj();
            answer.put("question_id", question.name());
            answer.put("question", question.text());
            answer.set("answer", answer(context, question, graph));
            answers.add(answer);
        }
        catalog.put("question_count", Question.values().length);
        catalog.put("rule", "Answers come from fixed, validated query intents over a deterministic "
                + "graph. The harness never generates a graph query from natural language and presents "
                + "the result as guaranteed truth.");
        catalog.set("answers", answers);
        writer.write("question-catalog.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), catalog));

        ObjectNode blindSpots = collect(context, "blind_spots");
        writer.write("blind-spots.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), blindSpots));
        ObjectNode gaps = collect(context, "gaps");
        writer.write("gaps.json", StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), gaps));

        String hash = StageSupport.publish(context, writer);

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                graph.nodeCount() + " provenance node(s), " + graph.edgeCount() + " relationship(s), "
                        + Question.values().length + " catalog question(s) answered; "
                        + blindSpots.path("count").asInt() + " blind spot(s), "
                        + gaps.path("count").asInt() + " gap(s)",
                List.of(), artifacts, hash);
    }

    // ------------------------------------------------------------------ graph construction

    /** Assembles the provenance graph from the published artifacts of every earlier stage. */
    public ProvenanceGraph build(StageContext context) {
        ProvenanceGraph graph = new ProvenanceGraph();
        String runId = context.run().runId();
        graph.node(runId, ProvenanceGraph.Kind.RUN, "Migration run " + runId);

        FileRegistry registry = EdgeSupport.loadRegistry(context);
        ChangeLedger ledger = EdgeSupport.openLedger(context);

        JsonNode knowledge = StageSupport.optionalUpstream(context, "08-knowledge",
                "migration-knowledge.json");
        JsonNode impact = StageSupport.optionalUpstream(context, "09-impact", "impact-report.json");
        JsonNode documents = StageSupport.optionalUpstream(context, "07-documentation",
                "document-registry.json");
        JsonNode contracts = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-contracts.json");
        JsonNode edgePlan = StageSupport.optionalUpstream(context, "11-plan", "edge-plan.json");
        JsonNode scenarios = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-scenarios.json");
        JsonNode differential = StageSupport.optionalUpstream(context, "17-differential",
                "differential-report.json");
        JsonNode approvals = StageSupport.optionalUpstream(context, "18-approval",
                "approval-report.json");
        JsonNode claims = StageSupport.optionalUpstream(context, "19-evidence", "claims.json");
        JsonNode testReport = StageSupport.optionalUpstream(context, "15-test", "test-report.json");
        JsonNode baselineTests = StageSupport.optionalUpstream(context, "04-baseline",
                "baseline-tests.json");

        // Documents
        if (documents != null) {
            for (JsonNode document : documents.path("documents")) {
                String id = document.path("document_id").asText();
                graph.node(id, ProvenanceGraph.Kind.DOCUMENT, document.path("url").asText(),
                        Map.of("trust_level", document.path("trust_level").asText(),
                                "content_hash", document.path("content_hash").asText()));
                graph.link(runId, ProvenanceGraph.Relation.PRODUCED, id, "documentation registry");
            }
        }

        // Migration facts
        if (knowledge != null) {
            for (JsonNode fact : knowledge.path("facts")) {
                String id = fact.path("knowledge_id").asText();
                graph.node(id, ProvenanceGraph.Kind.MIGRATION_FACT, fact.path("subject").asText(),
                        Map.of("type", fact.path("type").asText(),
                                "status", fact.path("status").asText(),
                                "channel", fact.path("channel").asText()));
                fact.path("document_refs").forEach(ref ->
                        graph.link(id, ProvenanceGraph.Relation.EVIDENCED_BY, ref.asText(),
                                "documentation channel"));
            }
        }

        // Impact findings
        if (impact != null) {
            for (JsonNode finding : impact.path("findings")) {
                String id = finding.path("impact_id").asText();
                graph.node(id, ProvenanceGraph.Kind.IMPACT_FINDING, finding.path("subject").asText(),
                        Map.of("classification", finding.path("classification").asText(),
                                "risk", finding.path("risk").asText("LOW")));
                String knowledgeId = finding.path("knowledge_id").asText(null);
                if (knowledgeId != null) {
                    graph.link(knowledgeId, ProvenanceGraph.Relation.AFFECTS, id, "impact analysis");
                }
                String fileId = finding.path("file_id").asText(null);
                if (fileId != null && !fileId.isBlank()) {
                    registerFile(graph, registry, fileId);
                    graph.link(id, ProvenanceGraph.Relation.AFFECTS, fileId, "located in repository");
                }
                finding.path("required_validation_dimensions").forEach(dimension -> {
                    String dimensionId = "DIMENSION:" + dimension.asText();
                    graph.node(dimensionId, ProvenanceGraph.Kind.SCENARIO, dimension.asText());
                    graph.link(id, ProvenanceGraph.Relation.REQUIRES_VALIDATION, dimensionId,
                            "impact-driven validation requirement");
                });
            }
        }

        // Characterization contracts
        if (contracts != null) {
            for (JsonNode contract : contracts.path("contracts")) {
                String id = contract.path("scenario_id").asText();
                graph.node(id, ProvenanceGraph.Kind.SCENARIO, contract.path("subject").asText(""),
                        Map.of("dimension", contract.path("dimension").asText(),
                                "state", contract.path("state").asText()));
                String impactId = contract.path("impact_id").asText(null);
                if (impactId != null && !impactId.isBlank()) {
                    graph.link(impactId, ProvenanceGraph.Relation.DISCHARGED_BY, id,
                            "characterization contract");
                }
            }
        }

        // Migration edges. Without these the provenance graph has no notion of WHERE in the
        // migration something happened, so a change, a scenario and a difference belonging to
        // different edges are indistinguishable from three things that happened together.
        if (edgePlan != null) {
            for (JsonNode edge : edgePlan.path("edges")) {
                String edgeId = edge.path("edge_id").asText();
                graph.node(edgeId, ProvenanceGraph.Kind.EDGE,
                        edge.path("source_state").asText() + " -> "
                                + edge.path("target_state").asText(),
                        Map.of("edge_class", edge.path("edge_class").asText(),
                                "validation_depth", edge.path("frozen_validation_depth").asText(),
                                "mandatory", String.valueOf(edge.path("mandatory_checkpoint").asBoolean()),
                                "edge_java", edge.path("edge_java").asText("")));
                graph.link(runId, ProvenanceGraph.Relation.PRODUCED, edgeId, "frozen migration plan");
                // The facts and impacts that are in force on THIS edge, not on the run.
                edge.path("knowledge_refs").forEach(ref ->
                        graph.link(ref.asText(), ProvenanceGraph.Relation.AUTHORIZES, edgeId,
                                "fact in force on this edge"));
                edge.path("impact_refs").forEach(ref ->
                        graph.link(ref.asText(), ProvenanceGraph.Relation.REQUIRES_VALIDATION, edgeId,
                                "impact scoped to this edge"));
                edge.path("characterization_refs").forEach(ref ->
                        graph.link(edgeId, ProvenanceGraph.Relation.REQUIRES_VALIDATION, ref.asText(),
                                "scenario required by this edge"));
            }
        }

        // Executable scenarios, with the state that says whether they are actually an oracle.
        if (scenarios != null) {
            for (JsonNode scenario : scenarios.path("scenarios")) {
                String id = scenario.path("scenario_id").asText();
                graph.node(id, ProvenanceGraph.Kind.SCENARIO,
                        scenario.path("target").asText(scenario.path("dimension").asText()),
                        Map.of("dimension", scenario.path("dimension").asText(),
                                "state", scenario.path("state").asText(),
                                "is_oracle", String.valueOf(scenario.path("is_oracle").asBoolean()),
                                "module", scenario.path("module").asText("")));
                String impactId = scenario.path("impact_id").asText(null);
                if (impactId != null && !impactId.isBlank()) {
                    graph.link(impactId, ProvenanceGraph.Relation.DISCHARGED_BY, id,
                            "executable characterization scenario");
                }
                scenario.path("knowledge_refs").forEach(ref ->
                        graph.link(ref.asText(), ProvenanceGraph.Relation.REQUIRES_VALIDATION, id,
                                "fact requiring behavioural evidence"));
            }
        }

        // Change events and files
        for (ChangeLedger.Entry entry : ledger.entries()) {
            ChangeEvent event = entry.event();
            String id = event.getChangeId();
            graph.node(id, ProvenanceGraph.Kind.CHANGE_EVENT,
                    event.getOperation() + " " + event.getPathAfter(),
                    Map.of("status", event.getStatus().name(),
                            "agent", String.valueOf(event.getAgent()),
                            "provider", event.getProvider() == null ? "unknown"
                                    : event.getProvider().type(),
                            "event_hash", entry.eventHash(),
                            "ai_authored", String.valueOf(event.getAi() != null)));
            if (event.getFileId() != null) {
                registerFile(graph, registry, event.getFileId());
                graph.link(event.getFileId(), ProvenanceGraph.Relation.CHANGED_BY, id, "change ledger");
            }
            event.getKnowledgeRefs().forEach(ref ->
                    graph.link(ref, ProvenanceGraph.Relation.AUTHORIZES, id, "verified migration fact"));
            event.getImpactRefs().forEach(ref ->
                    graph.link(ref, ProvenanceGraph.Relation.AUTHORIZES, id, "impact finding"));
            graph.link(runId, ProvenanceGraph.Relation.PRODUCED, id, "migration run");
            // The edge a change belongs to. A change ledger with no edge binding cannot answer
            // "what did this checkpoint actually do?", which is the question a reviewer asks when
            // one edge in a multi-edge migration goes wrong.
            if (event.getEdgeId() != null && graph.find(event.getEdgeId()).isPresent()) {
                graph.link(event.getEdgeId(), ProvenanceGraph.Relation.PRODUCED, id,
                        "change applied on this edge");
            }
        }

        // Test cases
        if (baselineTests != null) {
            for (JsonNode module : baselineTests.path("modules")) {
                for (JsonNode testCase : module.path("cases")) {
                    String id = "TEST:" + testCase.path("className").asText() + "#"
                            + testCase.path("name").asText();
                    graph.node(id, ProvenanceGraph.Kind.TEST_CASE, id,
                            Map.of("baseline_outcome", testCase.path("outcome").asText()));
                }
            }
        }
        if (testReport != null) {
            for (JsonNode module : testReport.path("modules")) {
                for (JsonNode testCase : module.path("cases")) {
                    String id = "TEST:" + testCase.path("test").asText();
                    graph.node(id, ProvenanceGraph.Kind.TEST_CASE, id,
                            Map.of("migrated_outcome", testCase.path("outcome").asText(),
                                    "classification", testCase.path("classification").asText()));
                    graph.link(runId, ProvenanceGraph.Relation.OBSERVED_IN, id, "test validation");
                }
            }
        }

        // Differences, aggregated across EVERY edge rather than only the one that published last.
        List<JsonNode> allComparisons = edgePlan == null ? new ArrayList<>()
                : com.bootshift.stages.stage19.EdgeEvidenceAggregator.allComparisons(context, edgePlan);
        if (allComparisons.isEmpty() && differential != null) {
            differential.path("comparisons").forEach(allComparisons::add);
        }
        {
            int index = 0;
            for (JsonNode comparison : allComparisons) {
                String id = "DIFF-" + (++index);
                String comparisonEdge = comparison.path("edge_id").asText(null);
                if (comparisonEdge != null && graph.find(comparisonEdge).isPresent()) {
                    graph.node(id, ProvenanceGraph.Kind.DIFFERENCE,
                            comparison.path("dimension").asText() + " on "
                                    + comparison.path("module").asText(),
                            Map.of("classification", comparison.path("classification").asText(),
                                    "edge_id", comparisonEdge,
                                    "scenario_id", comparison.path("scenario_id").asText(""),
                                    "comparison_unit", comparison.path("comparison_unit").asText("MODULE")));
                    graph.link(comparisonEdge, ProvenanceGraph.Relation.PRODUCED, id,
                            "comparison performed on this edge");
                    String scenarioId = comparison.path("scenario_id").asText(null);
                    if (scenarioId != null && !scenarioId.isBlank()
                            && graph.find(scenarioId).isPresent()) {
                        graph.link(scenarioId, ProvenanceGraph.Relation.COMPARED_AS, id,
                                "OLD versus NEW execution of this scenario");
                    }
                }
                graph.node(id, ProvenanceGraph.Kind.DIFFERENCE,
                        comparison.path("dimension").asText() + " on "
                                + comparison.path("module").asText(),
                        Map.of("classification", comparison.path("classification").asText(),
                                "detail", comparison.path("detail").asText("")));
                String dimensionId = "DIMENSION:" + comparison.path("dimension").asText();
                graph.node(dimensionId, ProvenanceGraph.Kind.SCENARIO,
                        comparison.path("dimension").asText());
                graph.link(dimensionId, ProvenanceGraph.Relation.COMPARED_AS, id,
                        "differential validation");
                comparison.path("explanation_refs").forEach(ref ->
                        graph.link(id, ProvenanceGraph.Relation.EXPLAINED_BY, ref.asText(),
                                "verified migration fact"));
            }
        }

        // Decisions
        if (approvals != null) {
            for (JsonNode decision : approvals.path("decisions")) {
                String id = decision.path("decisionId").asText();
                graph.node(id, ProvenanceGraph.Kind.DECISION, decision.path("gate").asText(),
                        Map.of("actor", decision.path("actor").asText(),
                                "verdict", decision.path("verdict").asText(),
                                "rationale", decision.path("rationale").asText()));
                graph.link(runId, ProvenanceGraph.Relation.SIGNED_BY, id, "approval");
            }
        }

        // Claims
        if (claims != null) {
            for (JsonNode claim : claims.path("claims")) {
                String id = claim.path("claim_id").asText();
                graph.node(id, ProvenanceGraph.Kind.CLAIM, claim.path("statement").asText(),
                        Map.of("evidence_level", claim.path("evidence_level").asText(),
                                "coverage", claim.path("coverage").asText("")));
                claim.path("evidence_refs").forEach(ref -> {
                    String evidenceId = "ARTIFACT:" + ref.asText();
                    graph.node(evidenceId, ProvenanceGraph.Kind.OBSERVATION, ref.asText());
                    graph.link(id, ProvenanceGraph.Relation.EVIDENCED_BY, evidenceId, "claim evidence");
                });
                claim.path("blind_spots").forEach(ref -> {
                    String blindSpotId = "BLINDSPOT:" + ref.asText();
                    graph.node(blindSpotId, ProvenanceGraph.Kind.BLIND_SPOT, ref.asText());
                    graph.link(id, ProvenanceGraph.Relation.NOT_VISIBLE_TO, blindSpotId,
                            "declared blind spot");
                });
            }
        }

        return graph;
    }

    private void registerFile(ProvenanceGraph graph, FileRegistry registry, String fileId) {
        registry.byId(fileId).ifPresentOrElse(
                record -> graph.node(fileId, ProvenanceGraph.Kind.FILE, record.getCurrentPath(),
                        Map.of("baseline_path", record.getBaselinePath(),
                                "status", record.getStatus().name(),
                                "module", String.valueOf(record.getModule()))),
                () -> graph.node(fileId, ProvenanceGraph.Kind.FILE, fileId));
    }

    // ------------------------------------------------------------------ question answering

    /** Answers one catalog question deterministically. */
    public JsonNode answer(StageContext context, Question question, ProvenanceGraph graph) {
        ObjectNode answer = Json.obj();
        switch (question) {
            case WHY_DID_THIS_FILE_CHANGE -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.FILE).forEach(file -> {
                    List<ProvenanceGraph.PEdge> changes = graph.outgoing(file.id()).stream()
                            .filter(e -> e.relation() == ProvenanceGraph.Relation.CHANGED_BY)
                            .toList();
                    if (changes.isEmpty()) {
                        return;
                    }
                    ObjectNode row = Json.obj();
                    row.put("file_id", file.id());
                    row.put("path", file.label());
                    ArrayNode reasons = Json.arr();
                    changes.forEach(change -> graph.find(change.to()).ifPresent(node -> {
                        ObjectNode reason = Json.obj();
                        reason.put("change_id", node.id());
                        reason.put("operation", node.label());
                        reason.put("agent", String.valueOf(node.attributes().get("agent")));
                        reason.put("status", String.valueOf(node.attributes().get("status")));
                        ArrayNode authorizedBy = Json.arr();
                        graph.incoming(node.id()).stream()
                                .filter(e -> e.relation() == ProvenanceGraph.Relation.AUTHORIZES)
                                .forEach(e -> authorizedBy.add(e.from()));
                        reason.set("authorized_by", authorizedBy);
                        reasons.add(reason);
                    }));
                    row.set("changes", reasons);
                    rows.add(row);
                });
                answer.set("files", rows);
                answer.put("count", rows.size());
            }
            case WHICH_FACT_AUTHORIZED_IT -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.CHANGE_EVENT).forEach(change -> {
                    ObjectNode row = Json.obj();
                    row.put("change_id", change.id());
                    ArrayNode facts = Json.arr();
                    graph.incoming(change.id()).stream()
                            .filter(e -> e.relation() == ProvenanceGraph.Relation.AUTHORIZES)
                            .forEach(e -> facts.add(e.from()));
                    row.set("authorizing_refs", facts);
                    row.put("unauthorized", facts.isEmpty());
                    rows.add(row);
                });
                answer.set("changes", rows);
            }
            case WHAT_DEPENDS_ON_THIS_FILE, WHAT_IS_ITS_BLAST_RADIUS -> {
                answer.put("delegated_to", "application graph");
                answer.put("command", question == Question.WHAT_DEPENDS_ON_THIS_FILE
                        ? "bootshift graph file <FILE_ID>"
                        : "bootshift graph blast-radius <FILE_ID>");
                answer.put("note", "Dependency and blast-radius questions are answered from the "
                        + "application graph, which carries the explaining path for every result");
            }
            case WHICH_SYMBOLS_CHANGED -> {
                JsonNode diff = StageSupport.optionalUpstream(context, "14-graph-diff",
                        "graph-diff.json");
                answer.set("changed_symbol_ids", diff == null ? Json.arr()
                        : diff.path("changed_symbol_ids"));
                answer.put("count", diff == null ? 0 : diff.path("changed_symbol_ids").size());
            }
            case WHICH_GRAPH_EDGES_CHANGED -> {
                JsonNode diff = StageSupport.optionalUpstream(context, "14-graph-diff",
                        "graph-diff.json");
                answer.set("edges_added", diff == null ? Json.arr() : diff.path("edges_added"));
                answer.set("edges_removed", diff == null ? Json.arr() : diff.path("edges_removed"));
            }
            case WHICH_CHANGES_WERE_AI_AUTHORED -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.CHANGE_EVENT).stream()
                        .filter(n -> "true".equals(String.valueOf(n.attributes().get("ai_authored"))))
                        .forEach(n -> rows.add(n.id()));
                answer.set("change_ids", rows);
                answer.put("count", rows.size());
                answer.put("note", "Zero AI-authored changes means the run was fully deterministic");
            }
            case WHICH_AI_PROPOSALS_WERE_REJECTED -> {
                JsonNode repair = StageSupport.optionalUpstream(context, "13-build-repair",
                        "build-report.json");
                ArrayNode rows = Json.arr();
                if (repair != null) {
                    repair.path("round_details").forEach(round ->
                            round.path("attempts").forEach(attempt -> {
                                if ("REJECTED_BEFORE_APPLY".equals(attempt.path("outcome").asText())) {
                                    rows.add(attempt);
                                }
                            }));
                }
                answer.set("rejected", rows);
                answer.put("count", rows.size());
            }
            case WHICH_CHANGES_WERE_REVERTED -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.CHANGE_EVENT).stream()
                        .filter(n -> "REVERTED".equals(String.valueOf(n.attributes().get("status"))))
                        .forEach(n -> rows.add(n.id()));
                answer.set("change_ids", rows);
                answer.put("count", rows.size());
            }
            case WHICH_FINDINGS_REMAIN_UNRESOLVED -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.IMPACT_FINDING).stream()
                        .filter(n -> graph.outgoing(n.id()).stream()
                                .noneMatch(e -> e.relation() == ProvenanceGraph.Relation.DISCHARGED_BY))
                        .forEach(n -> {
                            ObjectNode row = Json.obj();
                            row.put("impact_id", n.id());
                            row.put("subject", n.label());
                            row.put("classification",
                                    String.valueOf(n.attributes().get("classification")));
                            rows.add(row);
                        });
                answer.set("undischarged_impacts", rows);
                answer.put("count", rows.size());
            }
            case WHICH_VALIDATIONS_WERE_NOT_EXECUTED -> {
                JsonNode edgePlan = StageSupport.optionalUpstream(context, "11-plan", "edge-plan.json");
        JsonNode scenarios = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-scenarios.json");
        JsonNode differential = StageSupport.optionalUpstream(context, "17-differential",
                        "differential-report.json");
                ArrayNode rows = Json.arr();
                if (differential != null) {
                    differential.path("comparisons").forEach(comparison -> {
                        if ("NOT_COMPARED".equals(comparison.path("classification").asText())) {
                            rows.add(comparison);
                        }
                    });
                }
                answer.set("not_executed", rows);
                answer.put("count", rows.size());
            }
            case WHICH_DIFFERENCES_WERE_ACCEPTED -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.DECISION).forEach(decision -> {
                    ObjectNode row = Json.obj();
                    row.put("decision_id", decision.id());
                    row.put("gate", decision.label());
                    row.put("actor", String.valueOf(decision.attributes().get("actor")));
                    row.put("verdict", String.valueOf(decision.attributes().get("verdict")));
                    row.put("rationale", String.valueOf(decision.attributes().get("rationale")));
                    rows.add(row);
                });
                answer.set("decisions", rows);
                answer.put("count", rows.size());
            }
            case WHICH_TESTS_WERE_FAILING_BEFORE -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.TEST_CASE).stream()
                        .filter(n -> {
                            String outcome = String.valueOf(n.attributes().get("baseline_outcome"));
                            return "FAILED".equals(outcome) || "ERROR".equals(outcome);
                        })
                        .forEach(n -> rows.add(n.id()));
                answer.set("failing_at_baseline", rows);
                answer.put("count", rows.size());
                answer.put("note", "These are pre-existing failures and are never reported as "
                        + "migration regressions");
            }
            case WHICH_EVIDENCE_SUPPORTS_CLAIM -> {
                ArrayNode rows = Json.arr();
                graph.ofKind(ProvenanceGraph.Kind.CLAIM).forEach(claim -> {
                    ObjectNode row = Json.obj();
                    row.put("claim_id", claim.id());
                    row.put("evidence_level", String.valueOf(claim.attributes().get("evidence_level")));
                    row.put("coverage", String.valueOf(claim.attributes().get("coverage")));
                    ArrayNode evidence = Json.arr();
                    graph.outgoing(claim.id()).stream()
                            .filter(e -> e.relation() == ProvenanceGraph.Relation.EVIDENCED_BY)
                            .forEach(e -> evidence.add(e.to()));
                    row.set("evidence", evidence);
                    rows.add(row);
                });
                answer.set("claims", rows);
            }
            case WHICH_DIMENSIONS_WERE_NOT_COMPARED -> {
                JsonNode edgePlan = StageSupport.optionalUpstream(context, "11-plan", "edge-plan.json");
        JsonNode scenarios = StageSupport.optionalUpstream(context, "10-characterization",
                "characterization-scenarios.json");
        JsonNode differential = StageSupport.optionalUpstream(context, "17-differential",
                        "differential-report.json");
                java.util.Set<String> notCompared = new java.util.LinkedHashSet<>();
                if (differential != null) {
                    differential.path("comparisons").forEach(comparison -> {
                        if ("NOT_COMPARED".equals(comparison.path("classification").asText())) {
                            notCompared.add(comparison.path("dimension").asText());
                        }
                    });
                }
                answer.set("dimensions", Json.toTree(notCompared));
                answer.put("count", notCompared.size());
            }
            case WHAT_CAN_THIS_RUN_NOT_SEE -> {
                ObjectNode blindSpots = collect(context, "blind_spots");
                ObjectNode gaps = collect(context, "gaps");
                answer.set("blind_spots", blindSpots.path("items"));
                answer.set("gaps", gaps.path("items"));
                answer.put("blind_spot_count", blindSpots.path("count").asInt());
                answer.put("gap_count", gaps.path("count").asInt());
                answer.put("statement", "This run asserts only what it observed. Everything listed "
                        + "here was outside observed coverage.");
            }
            default -> answer.put("note", "unhandled question");
        }
        return answer;
    }

    /** Gathers blind spots or gaps declared by every stage envelope. */
    public ObjectNode collect(StageContext context, String field) {
        ObjectNode artifact = Json.obj();
        ArrayNode items = Json.arr();
        for (String stage : List.of("00-bootstrap", "01-inventory", "02-build", "03-graph",
                "04-baseline", "05-compatibility", "06-target", "07-documentation", "08-knowledge",
                "09-impact", "10-characterization", "11-plan", "12-transformation", "13-build-repair",
                "14-graph-diff", "15-test", "16-runtime", "17-differential", "18-approval",
                "19-evidence")) {
            Path dir = context.run().output().resolveLatestDir(stage);
            if (dir == null) {
                continue;
            }
            try (var stream = java.nio.file.Files.list(dir)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    if (file.getFileName().toString().equals("manifest.json")) {
                        continue;
                    }
                    JsonNode node = Json.read(file);
                    node.path(field).forEach(entry -> {
                        ObjectNode item = entry.deepCopy();
                        item.put("stage", stage);
                        item.put("artifact", file.getFileName().toString());
                        items.add(item);
                    });
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Cannot scan " + stage + " for " + field, e);
            }
        }
        artifact.put("count", items.size());
        artifact.set("items", items);
        return artifact;
    }
}
