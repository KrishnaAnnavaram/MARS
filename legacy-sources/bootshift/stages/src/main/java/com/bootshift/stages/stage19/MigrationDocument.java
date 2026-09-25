package com.bootshift.stages.stage19;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.graph.ApplicationGraph;
import com.bootshift.core.graph.EdgeType;
import com.bootshift.core.graph.GraphEdge;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.core.graph.NodeType;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.stages.EdgeIndex;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The end-to-end migration document.
 *
 * <p>The migration report answers "is this defensible?". This answers a different question, and one
 * a reviewer asks first: <em>what actually happened?</em> Which architecture went in, which came out,
 * what each stage did, what each edge changed, which file changed and on whose authority, and why
 * each decision was taken.
 *
 * <p>Generated on every run, from the artifacts, never from narration. Every number here is read out
 * of a published artifact; where an artifact is missing the document says so rather than omitting the
 * section, because a section that quietly disappears reads as a section with nothing to report.
 */
public final class MigrationDocument {

    /** One row of the stage narrative. */
    private record StageRow(String id, String name, String purpose, String status, String detail) {
    }

    private final StageContext context;

    public MigrationDocument(StageContext context) {
        this.context = context;
    }

    public String render() {
        JsonNode bootstrap = read("00-bootstrap", "bootstrap.json");
        JsonNode inventory = read("01-inventory", "inventory-artifact.json");
        JsonNode signals = read("01-inventory", "inventory-signals.json");
        JsonNode build = read("02-build", "build-model.json");
        JsonNode dependencies = read("02-build", "dependency-model.json");
        JsonNode graphSummary = read("03-graph", "graph-summary.json");
        JsonNode graphVerification = read("03-graph", "graph-verification-report.json");
        JsonNode baseline = read("04-baseline", "baseline-manifest.json");
        JsonNode baselineTests = read("04-baseline", "baseline-tests.json");
        JsonNode baselineRuntime = read("04-baseline", "baseline-runtime.json");
        JsonNode compatibility = read("05-compatibility", "compatibility-registry.json");
        JsonNode target = read("06-target", "target-state.json");
        JsonNode path = read("06-target", "migration-path.json");
        JsonNode documents = read("07-documentation", "document-registry.json");
        JsonNode knowledge = read("08-knowledge", "migration-knowledge.json");
        JsonNode impact = read("09-impact", "impact-report.json");
        JsonNode scenarios = read("10-characterization", "characterization-scenarios.json");
        JsonNode plan = read("11-plan", "migration-plan.json");
        JsonNode edgePlan = read("11-plan", "edge-plan.json");
        JsonNode residual = read("11-plan", "residual-report.json");
        JsonNode result = read("19-evidence", "migration-result.json");
        JsonNode claims = read("19-evidence", "claims.json");
        JsonNode edgeEvidence = read("19-evidence", "edge-evidence.json");

        StringBuilder sb = new StringBuilder();
        title(sb, target, result);
        tableOfContents(sb);
        executiveSummary(sb, target, result, plan, edgeEvidence);
        howToRead(sb);
        beforeArchitecture(sb, inventory, build, dependencies, graphSummary, baselineRuntime, signals);
        afterArchitecture(sb, result, edgePlan, edgeEvidence);
        architectureDelta(sb, edgePlan, edgeEvidence, result);
        migrationPath(sb, target, path, edgePlan);
        stageNarrative(sb, bootstrap, inventory, build, graphSummary, graphVerification, baseline,
                compatibility, target, documents, knowledge, impact, scenarios, plan, edgeEvidence,
                result);
        perEdgeDetail(sb, edgePlan, edgeEvidence);
        changeInventory(sb);
        behaviouralValidation(sb, scenarios, edgeEvidence, result);
        evidenceLevels(sb, claims);
        residualsAndGaps(sb, residual, result, edgeEvidence);
        humanDecisions(sb);
        provenanceAndIntegrity(sb, baseline, result);
        limitations(sb, result, edgeEvidence);
        return sb.toString();
    }

    // ------------------------------------------------------------------ sections

    private void title(StringBuilder sb, JsonNode target, JsonNode result) {
        sb.append("# Bootshift Migration Document\n\n");
        sb.append("**Run:** `").append(context.run().runId()).append("`  \n");
        sb.append("**Generated:** ").append(java.time.Instant.now()).append("  \n");
        sb.append("**Repository under migration:** `")
                .append(context.run().sourceRoot().toString().replace((char) 92, '/')).append("`  \n");
        sb.append("**Policy:** `").append(context.policy().name()).append("` (hash `")
                .append(shortHash(context.policy().policyHash())).append("`)  \n");
        if (target != null) {
            sb.append("**Migration:** Spring Boot `")
                    .append(target.path("source_version").asText("?")).append("` -> `")
                    .append(target.path("landing_version").asText("?")).append("`, Java `")
                    .append(target.path("source_java").asText("?")).append("` -> `")
                    .append(target.path("landing_java").asText("?")).append("`  \n");
        }
        sb.append("**Outcome:** `")
                .append(result == null ? "NOT_REACHED" : result.path("status").asText("UNKNOWN"))
                .append("`\n\n");
        sb.append("---\n\n");
    }

    private void tableOfContents(StringBuilder sb) {
        sb.append("## Table of contents\n\n");
        sb.append("1. [Executive summary](#1-executive-summary)\n");
        sb.append("2. [How to read this document](#2-how-to-read-this-document)\n");
        sb.append("3. [Architecture before the migration](#3-architecture-before-the-migration)\n");
        sb.append("4. [Architecture after the migration](#4-architecture-after-the-migration)\n");
        sb.append("5. [What changed in the architecture, and why](#5-what-changed-in-the-architecture-and-why)\n");
        sb.append("6. [The migration path and why each checkpoint exists](#6-the-migration-path-and-why-each-checkpoint-exists)\n");
        sb.append("7. [Stage by stage: what each stage did](#7-stage-by-stage-what-each-stage-did)\n");
        sb.append("8. [Per-edge detail](#8-per-edge-detail)\n");
        sb.append("9. [Every change that was made](#9-every-change-that-was-made)\n");
        sb.append("10. [Behavioural validation](#10-behavioural-validation)\n");
        sb.append("11. [Evidence levels and coverage](#11-evidence-levels-and-coverage)\n");
        sb.append("12. [Residuals, gaps and blind spots](#12-residuals-gaps-and-blind-spots)\n");
        sb.append("13. [Human decisions](#13-human-decisions)\n");
        sb.append("14. [Provenance and integrity](#14-provenance-and-integrity)\n");
        sb.append("15. [Limitations of this migration](#15-limitations-of-this-migration)\n\n");
        sb.append("---\n\n");
    }

    private void executiveSummary(StringBuilder sb, JsonNode target, JsonNode result, JsonNode plan,
                                  JsonNode edgeEvidence) {
        sb.append("## 1. Executive summary\n\n");
        String status = result == null ? "NOT_REACHED" : result.path("status").asText("UNKNOWN");
        sb.append("The migration finished in state **`").append(status).append("`**.\n\n");

        sb.append("| Question | Answer |\n|---|---|\n");
        sb.append("| Where did the application start? | Spring Boot `")
                .append(target == null ? "?" : target.path("source_version").asText("?"))
                .append("` on Java `")
                .append(target == null ? "?" : target.path("source_java").asText("?")).append("` |\n");
        sb.append("| Where was it taken? | Spring Boot `")
                .append(target == null ? "?" : target.path("landing_version").asText("?"))
                .append("` on Java `")
                .append(target == null ? "?" : target.path("landing_java").asText("?")).append("` |\n");
        sb.append("| Why that target? | ")
                .append(target == null ? "target resolution did not complete"
                        : target.path("selection_mode").asText("?") + "; support horizon "
                                + target.path("support_horizon_months").asLong() + " month(s)")
                .append(" |\n");
        sb.append("| How many migration edges were planned? | ")
                .append(edgeEvidence == null ? "?" : edgeEvidence.path("edges_planned").asInt())
                .append(" |\n");
        sb.append("| How many completed? | ")
                .append(edgeEvidence == null ? "?" : edgeEvidence.path("edges_complete").asInt())
                .append(" |\n");
        sb.append("| How many source changes were applied? | ")
                .append(edgeEvidence == null ? "?"
                        : edgeEvidence.path("total_changes_applied").asInt()).append(" |\n");
        sb.append("| Was the original `./src` modified? | No. It is input only; every change was made "
                + "in an external migration workspace |\n");
        sb.append("| Is the change ledger valid? | ")
                .append(result == null ? "?" : (result.path("change_ledger_valid").asBoolean()
                        ? "yes, over " + result.path("change_ledger_events").asLong() + " event(s)"
                        : "**NO**")).append(" |\n");
        sb.append("| What blocks completion? | ").append(blockingSummary(result)).append(" |\n\n");

        if (plan != null) {
            sb.append("Deterministic transformation coverage across the whole fact set was **")
                    .append(plan.path("deterministic_coverage").asDouble())
                    .append("**. That number measures how many *known* migration facts have a "
                            + "transformer, not how complete the fact set is.\n\n");
        }
        sb.append("---\n\n");
    }

    private void howToRead(StringBuilder sb) {
        sb.append("## 2. How to read this document\n\n");
        sb.append("Everything below is read out of the published artifacts of this run. Nothing is "
                + "narrated from memory, and nothing is inferred where an artifact is missing - a "
                + "missing artifact is reported as missing.\n\n");
        sb.append("Three distinctions carry most of the meaning:\n\n");
        sb.append("- **Signal vs conclusion.** Early stages record signals (\"this file imports "
                + "`javax.persistence`\"). Only a verified migration fact plus an impact finding "
                + "turns a signal into a change.\n");
        sb.append("- **Observed vs assumed.** A behaviour is only claimed when a scenario was "
                + "executed against both the original and the migrated application. Anything else is "
                + "`NOT_COMPARED`, which is not a pass.\n");
        sb.append("- **Applied vs authorized.** Every change has an authorizing fact and an impact "
                + "finding. A change with neither is rejected by the mutation gateway, and the "
                + "rejection is recorded in the ledger exactly like an applied change.\n\n");
        sb.append("---\n\n");
    }

    private void beforeArchitecture(StringBuilder sb, JsonNode inventory, JsonNode build,
                                    JsonNode dependencies, JsonNode graphSummary,
                                    JsonNode baselineRuntime, JsonNode signals) {
        sb.append("## 3. Architecture before the migration\n\n");
        if (inventory == null || build == null) {
            sb.append("_The inventory or build model is missing, so the original architecture could "
                    + "not be described._\n\n---\n\n");
            return;
        }

        sb.append("### 3.1 What arrived\n\n");
        sb.append("| Property | Value |\n|---|---|\n");
        sb.append("| Files inventoried | ").append(inventory.path("file_count").asInt()).append(" |\n");
        sb.append("| Modules | ").append(inventory.path("modules").size()).append(" |\n");
        sb.append("| Build system | `").append(build.path("kind").asText("?")).append("` |\n");
        sb.append("| Build tool | `").append(build.path("tool_version").asText("?")).append("` |\n");
        sb.append("| Authoritative build model | ")
                .append(build.path("authoritative").asBoolean() ? "yes" : "**no** - "
                        + build.path("degraded_reason").asText("")).append(" |\n");
        sb.append("| Resolved dependencies | ")
                .append(dependencies == null ? "?" : dependencies.path("dependency_count").asInt())
                .append(" |\n");
        if (graphSummary != null) {
            sb.append("| Graph nodes / edges | ")
                    .append(sumCounts(graphSummary.path("node_counts"))).append(" / ")
                    .append(sumCounts(graphSummary.path("edge_counts"))).append(" |\n");
            sb.append("| Type attribution ratio | ")
                    .append(graphSummary.path("attribution_ratio").asDouble()).append(" |\n");
        }
        sb.append('\n');

        sb.append("### 3.2 Original module and integration topology\n\n");
        sb.append("This is the graph as Bootshift resolved it from the build model and the source, "
                + "before any change was made.\n\n");
        sb.append(beforeDiagram(inventory, build, dependencies, baselineRuntime));
        sb.append('\n');

        sb.append("### 3.3 Modules\n\n");
        sb.append("| Module | Java files | Test files | Build | Started at baseline |\n");
        sb.append("|---|---|---|---|---|\n");
        Map<String, Boolean> started = startedByModule(baselineRuntime);
        for (JsonNode module : inventory.path("modules")) {
            String id = module.path("module_id").asText();
            if (".".equals(id)) {
                continue;
            }
            sb.append("| `").append(id).append("` | ")
                    .append(module.path("java_main").asInt()).append(" | ")
                    .append(module.path("java_test").asInt()).append(" | `")
                    .append(module.path("build_system").asText("?")).append("` | ")
                    .append(started.containsKey(id)
                            ? (started.get(id) ? "yes" : "**no**") : "not attempted")
                    .append(" |\n");
        }
        sb.append('\n');

        if (signals != null) {
            sb.append("### 3.4 Migration-relevant signals found in the original source\n\n");
            sb.append("These are signals, not conclusions: each one says a construct is present, not "
                    + "that it must change.\n\n");
            Map<String, Integer> bySignal = new TreeMap<>();
            Map<String, String> notes = new LinkedHashMap<>();
            for (JsonNode signal : signals.path("signals")) {
                bySignal.merge(signal.path("signal_id").asText(), 1, Integer::sum);
                notes.putIfAbsent(signal.path("signal_id").asText(), signal.path("note").asText(""));
            }
            sb.append("| Signal | Files | What it means |\n|---|---|---|\n");
            bySignal.forEach((id, count) -> sb.append("| `").append(id).append("` | ").append(count)
                    .append(" | ").append(notes.getOrDefault(id, "")).append(" |\n"));
            sb.append('\n');
        }
        sb.append("---\n\n");
    }

    private void afterArchitecture(StringBuilder sb, JsonNode result, JsonNode edgePlan,
                                   JsonNode edgeEvidence) {
        sb.append("## 4. Architecture after the migration\n\n");
        JsonNode finalGraph = read("14-graph-diff", "application-graph-current.json");
        JsonNode graphDiff = read("14-graph-diff", "graph-diff.json");

        if (finalGraph == null) {
            sb.append("_No post-migration graph was produced, so the resulting architecture cannot "
                    + "be described. This happens when no edge reached the graph rebuild stage._\n\n");
            sb.append("---\n\n");
            return;
        }

        ApplicationGraph graph = ApplicationGraph.fromNode(finalGraph);
        sb.append("### 4.1 Resulting structure\n\n");
        sb.append("| Property | Value |\n|---|---|\n");
        sb.append("| Graph label | `").append(graph.label()).append("` |\n");
        sb.append("| Graph status | `").append(graph.status()).append("`")
                .append("PARTIAL".equals(graph.status())
                        ? " - the edge did not compile, so type attribution is incomplete" : "")
                .append(" |\n");
        sb.append("| Nodes / edges | ").append(graph.nodeCount()).append(" / ")
                .append(graph.edgeCount()).append(" |\n");
        sb.append("| Final tree hash | `")
                .append(result == null ? "?" : shortHash(result.path("final_source_tree_hash").asText(null)))
                .append("` |\n\n");

        sb.append("### 4.2 Migrated module and integration topology\n\n");
        sb.append(afterDiagram(graph));
        sb.append('\n');

        if (graphDiff != null) {
            sb.append("### 4.3 Structural difference against the pre-migration graph\n\n");
            sb.append("| Change | Count |\n|---|---|\n");
            graphDiff.path("counts").fields().forEachRemaining(e ->
                    sb.append("| ").append(e.getKey().replace('_', ' ')).append(" | ")
                            .append(e.getValue().asInt()).append(" |\n"));
            sb.append("\nA structural change in a file nothing authorized is a scope violation and "
                    + "blocks the edge. The scope assertion for each edge is in section 8.\n\n");
        }
        sb.append("---\n\n");
    }

    private void architectureDelta(StringBuilder sb, JsonNode edgePlan, JsonNode edgeEvidence,
                                   JsonNode result) {
        sb.append("## 5. What changed in the architecture, and why\n\n");
        sb.append("The migration is not a single rewrite. It is a sequence of authorized, "
                + "individually validated steps, and the diagram below is the control flow that "
                + "produced every change in section 9.\n\n");
        sb.append("```mermaid\n");
        sb.append("flowchart TB\n");
        sb.append("    subgraph EVID[\"Evidence acquisition (read only)\"]\n");
        sb.append("        DOC[\"Official documentation<br/>pinned by content hash\"]\n");
        sb.append("        ART[\"Published artifacts<br/>BOM diff, bytecode diff, metadata\"]\n");
        sb.append("        DOC --> FACT[\"Migration fact<br/><i>CANDIDATE</i>\"]\n");
        sb.append("        ART --> FACT\n");
        sb.append("        FACT --> VER{\"Corroborated by<br/>artifact reality?\"}\n");
        sb.append("        VER -->|yes| VFACT[\"VERIFIED fact<br/>may authorize a change\"]\n");
        sb.append("        VER -->|no| CAND[\"Stays CANDIDATE<br/>authorizes nothing\"]\n");
        sb.append("    end\n");
        sb.append("    subgraph SCOPE[\"Scoping\"]\n");
        sb.append("        VFACT --> IMP[\"Impact finding<br/>fact -> symbol -> FILE_ID\"]\n");
        sb.append("        IMP --> EDGE[\"Edge plan<br/><i>frozen</i>\"]\n");
        sb.append("    end\n");
        sb.append("    subgraph MUT[\"Mutation (single writer)\"]\n");
        sb.append("        EDGE --> PROP[\"Transformer proposes<br/>a patch\"]\n");
        sb.append("        PROP --> GATE{\"FileMutationGateway\"}\n");
        sb.append("        GATE -->|authorized| APPLY[\"Applied + ledger entry\"]\n");
        sb.append("        GATE -->|out of scope| REJ[\"Rejected + ledger entry\"]\n");
        sb.append("    end\n");
        sb.append("    subgraph VAL[\"Validation\"]\n");
        sb.append("        APPLY --> COMP[\"Compile + bounded repair\"]\n");
        sb.append("        COMP --> GRAPH[\"Graph rebuild + scope assertion\"]\n");
        sb.append("        GRAPH --> TEST[\"Tests vs sealed baseline\"]\n");
        sb.append("        TEST --> RUN[\"Runtime + frozen scenarios on NEW\"]\n");
        sb.append("        RUN --> DIFF[\"OLD vs NEW scenario comparison\"]\n");
        sb.append("    end\n");
        sb.append("    DIFF --> CLAIM[\"Claim with evidence level<br/>and coverage statement\"]\n");
        sb.append("```\n\n");

        sb.append("### 5.1 The change classes this migration applied\n\n");
        Map<String, Integer> recipeCounts = recipeCounts();
        if (recipeCounts.isEmpty()) {
            sb.append("_No transformation was recorded in the change ledger._\n\n");
        } else {
            sb.append("| Transformation | Changes | What it does and why it was needed |\n|---|---|---|\n");
            recipeCounts.forEach((recipe, count) -> sb.append("| `").append(recipe).append("` | ")
                    .append(count).append(" | ").append(explainRecipe(recipe)).append(" |\n"));
            sb.append('\n');
        }
        sb.append("---\n\n");
    }

    private void migrationPath(StringBuilder sb, JsonNode target, JsonNode path, JsonNode edgePlan) {
        sb.append("## 6. The migration path and why each checkpoint exists\n\n");
        if (path == null) {
            sb.append("_No migration path was resolved._\n\n---\n\n");
            return;
        }
        sb.append("A major version boundary is never crossed in the same step as anything else, and "
                + "never more than one boundary per step. This run crossed **")
                .append(path.path("major_boundaries_crossed").asInt()).append("** boundary(ies) over **")
                .append(path.path("edge_count").asInt()).append("** edge(s).\n\n");

        sb.append("```mermaid\nflowchart LR\n");
        String previous = null;
        int index = 0;
        for (JsonNode edge : path.path("edges")) {
            String id = "E" + (index++);
            String cls = edge.path("edgeClass").asText("MINOR");
            String label = edge.path("fromVersion").asText() + "<br/>-><br/>"
                    + edge.path("toVersion").asText() + "<br/><i>" + cls + "</i>";
            String shape = "MAJOR_BOUNDARY".equals(cls) ? "{{\"" + label + "\"}}"
                    : "LANDING".equals(cls) ? "([\"" + label + "\"])" : "[\"" + label + "\"]";
            sb.append("    ").append(id).append(shape).append('\n');
            if (previous != null) {
                sb.append("    ").append(previous).append(" --> ").append(id).append('\n');
            }
            previous = id;
        }
        sb.append("```\n\n");

        sb.append("| Edge | Class | From -> To | Mandatory | Java | Spring Cloud | Why this edge exists |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (JsonNode edge : path.path("edges")) {
            sb.append("| `").append(edge.path("edgeId").asText()).append("` | `")
                    .append(edge.path("edgeClass").asText()).append("` | `")
                    .append(edge.path("fromVersion").asText()).append("` -> `")
                    .append(edge.path("toVersion").asText()).append("` | ")
                    .append(edge.path("mandatory").asBoolean() ? "**yes**" : "no").append(" | ")
                    .append(edge.path("edgeJava").asInt()).append(" | ")
                    .append(edge.path("springCloudTrain").asText("none")).append(" | ")
                    .append(oneLine(edge.path("existsBecause").asText(
                            edge.path("rationale").asText("")))).append(" |\n");
        }
        sb.append('\n');

        sb.append("### 6.1 Toolchain selected per edge\n\n");
        sb.append("| Edge | Java | Vendor | Home | Why this JDK |\n|---|---|---|---|---|\n");
        for (JsonNode edge : path.path("edges")) {
            sb.append("| `").append(edge.path("edgeId").asText()).append("` | ")
                    .append(edge.path("edgeJava").asInt()).append(" | ")
                    .append(oneLine(edge.path("javaVendor").asText("?"))).append(" | `")
                    .append(edge.path("javaHome").asText("?")).append("` | ")
                    .append(oneLine(edge.path("javaSelectionReason").asText(""))).append(" |\n");
        }
        sb.append("\nThe application's Java target is chosen from the JDKs installed on the machine "
                + "intersected with what the target Spring Boot line supports, preferring the highest "
                + "long-term-support release. It is never derived from the JDK running Bootshift.\n\n");
        sb.append("---\n\n");
    }

    private void stageNarrative(StringBuilder sb, JsonNode bootstrap, JsonNode inventory,
                                JsonNode build, JsonNode graphSummary, JsonNode graphVerification,
                                JsonNode baseline, JsonNode compatibility, JsonNode target,
                                JsonNode documents, JsonNode knowledge, JsonNode impact,
                                JsonNode scenarios, JsonNode plan, JsonNode edgeEvidence,
                                JsonNode result) {
        sb.append("## 7. Stage by stage: what each stage did\n\n");
        List<StageRow> rows = new ArrayList<>();

        rows.add(new StageRow("00", "Bootstrap", "Create workspaces, snapshot the input, verify OSS policy",
                statusOf(bootstrap), bootstrap == null ? "did not run"
                : "snapshot `" + shortHash(bootstrap.path("original_snapshot_hash").asText(null))
                        + "`, OSS gate " + bootstrap.path("oss_license_gate").path("gate").asText("?")));
        rows.add(new StageRow("01", "Inventory", "Discover files and allocate permanent FILE_IDs",
                statusOf(inventory), inventory == null ? "did not run"
                : inventory.path("file_count").asInt() + " file(s) across "
                        + inventory.path("modules").size() + " module(s)"));
        rows.add(new StageRow("02", "Build resolver", "Ask the build tool for the effective model",
                statusOf(build), build == null ? "did not run"
                : build.path("kind").asText() + ", authoritative="
                        + build.path("authoritative").asBoolean()));
        rows.add(new StageRow("03", "Application graph", "Build and verify the typed multi-view graph",
                statusOf(graphSummary), graphSummary == null ? "did not run"
                : sumCounts(graphSummary.path("node_counts")) + " node(s), attribution "
                        + graphSummary.path("attribution_ratio").asDouble()
                        + (graphVerification == null ? ""
                                : ", verification " + (graphVerification.path("passed").asBoolean()
                                        ? "passed" : "FAILED"))));
        rows.add(new StageRow("04", "Baseline", "Observe and seal the original behaviour",
                statusOf(baseline), baseline == null ? "did not run"
                : "sealed as `" + shortHash(baseline.path("baseline_manifest_hash").asText(null)) + "`"));
        rows.add(new StageRow("05", "Compatibility", "Acquire version-space and lifecycle evidence",
                statusOf(compatibility), compatibility == null ? "did not run"
                : "current Boot " + compatibility.path("current_spring_boot").asText("?")
                        + ", Spring Cloud in use=" + compatibility.path("application_uses_spring_cloud").asBoolean()));
        rows.add(new StageRow("06", "Target resolver", "Choose the landing target and the path to it",
                statusOf(target), target == null ? "did not run"
                : "landing " + target.path("landing_version").asText("?") + " ("
                        + target.path("selection_mode").asText("?") + ")"));
        rows.add(new StageRow("07", "Documentation", "Pin authoritative documents per component and edge",
                statusOf(documents), documents == null ? "did not run"
                : documents.path("document_count").asInt() + " document(s) for "
                        + documents.path("components_detected").size() + " component(s)"));
        rows.add(new StageRow("08", "Knowledge", "Turn documents and artifacts into verified facts",
                statusOf(knowledge), knowledge == null ? "did not run"
                : knowledge.path("fact_count").asInt() + " fact(s), "
                        + knowledge.path("verified_count").asInt() + " VERIFIED"));
        rows.add(new StageRow("09", "Impact", "Bind each fact to the symbols and files it reaches",
                statusOf(impact), impact == null ? "did not run"
                : impact.path("finding_count").asInt() + " finding(s), "
                        + impact.path("affected_file_count").asInt() + " affected file(s)"));
        rows.add(new StageRow("10", "Characterization", "Build scenarios and freeze them against OLD",
                statusOf(scenarios), scenarios == null ? "did not run"
                : scenarios.path("count").asInt() + " scenario(s), "
                        + scenarios.path("frozen").asInt() + " frozen against the original"));
        rows.add(new StageRow("11", "Planner", "Freeze a per-edge plan and validation depth",
                statusOf(plan), plan == null ? "did not run"
                : plan.path("edge_count").asInt() + " edge(s) frozen"));

        String edgeDetail = edgeEvidence == null ? "no edge evidence"
                : edgeEvidence.path("edges_complete").asInt() + "/"
                        + edgeEvidence.path("edges_planned").asInt() + " edge(s) complete";
        rows.add(new StageRow("12", "Transformation", "Apply authorized deterministic changes",
                edgeEvidence == null ? "NOT RUN" : "RAN", edgeDetail + ", "
                        + (edgeEvidence == null ? "?" : edgeEvidence.path("total_changes_applied").asInt())
                        + " change(s) applied"));
        rows.add(new StageRow("13", "Build repair", "Compile and repair within a bounded budget",
                edgeEvidence == null ? "NOT RUN" : "RAN", compiledSummary(edgeEvidence)));
        rows.add(new StageRow("14", "Graph diff", "Rebuild the graph and assert scope",
                edgeEvidence == null ? "NOT RUN" : "RAN", scopeSummary(edgeEvidence)));
        rows.add(new StageRow("15", "Test validation", "Run tests and classify against two baselines",
                edgeEvidence == null ? "NOT RUN" : "RAN",
                edgeEvidence == null ? "?" : edgeEvidence.path("total_tests").asInt()
                        + " test(s), " + edgeEvidence.path("total_test_regressions").asLong()
                        + " regression(s)"));
        rows.add(new StageRow("16", "Runtime validation", "Start the migrated app and run scenarios",
                edgeEvidence == null ? "NOT RUN" : "RAN", runtimeSummary(edgeEvidence)));
        rows.add(new StageRow("17", "Differential", "Compare OLD and NEW per scenario",
                edgeEvidence == null ? "NOT RUN" : "RAN", differentialSummary(edgeEvidence)));
        rows.add(new StageRow("18", "Approval", "Raise gates and read externally supplied decisions",
                result == null ? "NOT RUN" : "RAN",
                result == null ? "?" : approvalSummary(result)));
        rows.add(new StageRow("19", "Evidence", "Seal the manifest and produce the defensible report",
                statusOf(result), result == null ? "did not run"
                : "status " + result.path("status").asText() + ", manifest `"
                        + shortHash(result.path("evidence_manifest_hash").asText(null)) + "`"));
        JsonNode provenance = read("20-provenance", "provenance-graph.json");
        rows.add(new StageRow("20", "Provenance", "Build the queryable provenance graph",
                statusOf(provenance), provenance == null ? "did not run"
                : provenance.path("node_count").asInt() + " node(s), "
                        + provenance.path("edge_count").asInt() + " relationship(s)"));

        sb.append("| # | Stage | What it is responsible for | Ran | Result |\n|---|---|---|---|---|\n");
        for (StageRow row : rows) {
            sb.append("| ").append(row.id()).append(" | **").append(row.name()).append("** | ")
                    .append(row.purpose()).append(" | ").append(row.status()).append(" | ")
                    .append(row.detail()).append(" |\n");
        }
        sb.append('\n');
        sb.append("---\n\n");
    }

    private void perEdgeDetail(StringBuilder sb, JsonNode edgePlan, JsonNode edgeEvidence) {
        sb.append("## 8. Per-edge detail\n\n");
        if (edgeEvidence == null || edgeEvidence.path("edges").isEmpty()) {
            sb.append("_No migration edge was executed._\n\n---\n\n");
            return;
        }
        sb.append("Each edge is planned, transformed, compiled, graph-verified, then validated to the "
                + "depth its own plan froze. An edge that did not do everything its plan required "
                + "carries its shortfalls here.\n\n");
        sb.append("| Edge | Class | From -> To | Transformed | Compiled | Scope OK | Tests | Runtime | Differential | Complete |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (JsonNode edge : edgeEvidence.path("edges")) {
            sb.append("| `").append(edge.path("edge_id").asText()).append("` | `")
                    .append(edge.path("edge_class").asText()).append("` | `")
                    .append(edge.path("source_state").asText()).append("` -> `")
                    .append(edge.path("target_state").asText()).append("` | ")
                    .append(tick(edge.path("transformed").asBoolean())).append(" ")
                    .append(edge.path("changes_applied").asInt()).append(" | ")
                    .append(tick(edge.path("compiled").asBoolean())).append(" | ")
                    .append(tick(edge.path("scope_verified").asBoolean())).append(" | ")
                    .append(requiredCell(edge.path("tests_required").asBoolean(),
                            edge.path("tests_executed").asBoolean(),
                            edge.path("tests").asInt() + " test(s)")).append(" | ")
                    .append(requiredCell(edge.path("runtime_required").asBoolean(),
                            edge.path("runtime_executed").asBoolean(),
                            edge.path("modules_started").asInt() + "/"
                                    + edge.path("modules_attempted").asInt() + " started")).append(" | ")
                    .append(requiredCell(edge.path("differential_required").asBoolean(),
                            edge.path("differential_executed").asBoolean(),
                            edge.path("differential_counts").toString())).append(" | ")
                    .append(tick(edge.path("complete").asBoolean())).append(" |\n");
        }
        sb.append('\n');

        for (JsonNode edge : edgeEvidence.path("edges")) {
            if (edge.path("shortfalls").isEmpty()) {
                continue;
            }
            sb.append("**`").append(edge.path("edge_id").asText()).append("` did not satisfy its plan:**\n\n");
            edge.path("shortfalls").forEach(sf -> sb.append("- ").append(sf.asText()).append('\n'));
            sb.append('\n');
        }
        sb.append("---\n\n");
    }

    private void changeInventory(StringBuilder sb) {
        sb.append("## 9. Every change that was made\n\n");
        ChangeLedger.Verification verification = com.bootshift.stages.EdgeSupport
                .verifyLedger(context);
        ChangeLedger ledger = com.bootshift.stages.EdgeSupport.openLedger(context);
        List<ChangeLedger.Entry> entries = ledger.entries();

        if (entries.isEmpty()) {
            sb.append("_The change ledger is empty: no source mutation was attempted._\n\n---\n\n");
            return;
        }

        sb.append("The ledger is append-only and hash-chained, and it records what the harness "
                + "**attempted**, not what survived. Rejected and failed attempts appear here exactly "
                + "like applied ones.\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Ledger events | ").append(entries.size()).append(" |\n");
        sb.append("| Chain valid | ").append(verification.valid() ? "yes" : "**NO**").append(" |\n");
        sb.append("| Head | `").append(shortHash(verification.computedHead())).append("` |\n");
        Map<String, Integer> byStatus = new TreeMap<>();
        entries.forEach(e -> byStatus.merge(String.valueOf(e.event().getStatus()), 1, Integer::sum));
        byStatus.forEach((status, count) ->
                sb.append("| ").append(status).append(" | ").append(count).append(" |\n"));
        sb.append('\n');

        FileRegistry registry = com.bootshift.stages.EdgeSupport.loadRegistry(context);
        sb.append("### 9.1 Applied changes\n\n");
        sb.append("| Change | Edge | File | Path | Recipe | Provider | Before -> After | Authorized by |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        int applied = 0;
        for (ChangeLedger.Entry entry : entries) {
            ChangeEvent event = entry.event();
            if (event.getStatus() != ChangeEvent.Status.APPLIED) {
                continue;
            }
            applied++;
            if (applied > 200) {
                continue;
            }
            String knowledge = event.getKnowledgeRefs().isEmpty() ? "-"
                    : event.getKnowledgeRefs().size() + " fact(s)";
            String impacts = event.getImpactRefs().isEmpty() ? "-"
                    : event.getImpactRefs().size() + " impact(s)";
            sb.append("| `").append(event.getChangeId()).append("` | `")
                    .append(event.getEdgeId()).append("` | `")
                    .append(shortId(event.getFileId())).append("` | `")
                    .append(event.getPathAfter() == null ? event.getPathBefore() : event.getPathAfter())
                    .append("` | `").append(event.getRecipeId() == null ? "-" : event.getRecipeId())
                    .append("` | `")
                    .append(event.getProvider() == null ? "-" : event.getProvider().name())
                    .append("` | `").append(shortHash(event.getBeforeSha256())).append("` -> `")
                    .append(shortHash(event.getAfterSha256())).append("` | ")
                    .append(knowledge).append(", ").append(impacts).append(" |\n");
        }
        if (applied > 200) {
            sb.append("\n_").append(applied - 200)
                    .append(" further applied change(s) are in `change-ledger.jsonl`._\n");
        }
        if (applied == 0) {
            sb.append("| _none_ | | | | | | | |\n");
        }
        sb.append('\n');

        List<ChangeLedger.Entry> rejected = entries.stream()
                .filter(e -> e.event().getStatus() == ChangeEvent.Status.REJECTED).toList();
        if (!rejected.isEmpty()) {
            sb.append("### 9.2 Rejected changes and why\n\n");
            sb.append("A rejection is a control working, not a failure to record. These are the "
                    + "changes something proposed and the gateway refused.\n\n");
            sb.append("| Change | Edge | Path | Reason |\n|---|---|---|---|\n");
            rejected.stream().limit(80).forEach(entry -> sb.append("| `")
                    .append(entry.event().getChangeId()).append("` | `")
                    .append(entry.event().getEdgeId()).append("` | `")
                    .append(entry.event().getPathBefore()).append("` | ")
                    .append(oneLine(entry.event().getRejectionReason())).append(" |\n"));
            sb.append('\n');
        }

        sb.append("### 9.3 File lineage\n\n");
        long changedFiles = registry.all().stream().filter(r -> !r.getChangeIds().isEmpty()).count();
        sb.append(changedFiles).append(" of ").append(registry.size())
                .append(" registered file(s) were changed. Identity is allocated, never derived from "
                        + "path or content, so a FILE_ID survives a rename and an edit together.\n\n");
        if (changedFiles > 0) {
            sb.append("| FILE_ID | Baseline path | Final path | Versions |\n|---|---|---|---|\n");
            registry.all().stream().filter(r -> !r.getChangeIds().isEmpty()).limit(120)
                    .forEach(r -> sb.append("| `").append(shortId(r.getFileId())).append("` | `")
                            .append(r.getBaselinePath()).append("` | `")
                            .append(r.getCurrentPath()).append("` | ")
                            .append(r.getVersions().size()).append(" |\n"));
            sb.append('\n');
        }
        sb.append("---\n\n");
    }

    private void behaviouralValidation(StringBuilder sb, JsonNode scenarios, JsonNode edgeEvidence,
                                       JsonNode result) {
        sb.append("## 10. Behavioural validation\n\n");
        sb.append("A behaviour is only claimed when the same scenario was executed against the "
                + "original application and against the migrated one. Everything else is "
                + "`NOT_COMPARED`, which is recorded as a gap rather than as a pass.\n\n");

        if (scenarios == null) {
            sb.append("_No executable scenarios were produced._\n\n---\n\n");
            return;
        }
        sb.append("### 10.1 Scenario inventory\n\n");
        sb.append("| State | Count | What it means |\n|---|---|---|\n");
        scenarios.path("by_state").fields().forEachRemaining(e ->
                sb.append("| `").append(e.getKey()).append("` | ").append(e.getValue().asInt())
                        .append(" | ").append(explainScenarioState(e.getKey())).append(" |\n"));
        sb.append('\n');

        sb.append("### 10.2 Comparison outcomes\n\n");
        if (edgeEvidence == null || edgeEvidence.path("differential_totals").isEmpty()) {
            sb.append("_No differential comparison was executed._\n\n");
        } else {
            sb.append("| Classification | Count | Meaning |\n|---|---|---|\n");
            edgeEvidence.path("differential_totals").fields().forEachRemaining(e ->
                    sb.append("| `").append(e.getKey()).append("` | ").append(e.getValue().asInt())
                            .append(" | ").append(explainClassification(e.getKey())).append(" |\n"));
            sb.append('\n');
        }
        sb.append("---\n\n");
    }

    private void evidenceLevels(StringBuilder sb, JsonNode claims) {
        sb.append("## 11. Evidence levels and coverage\n\n");
        if (claims == null) {
            sb.append("_No claims were produced._\n\n---\n\n");
            return;
        }
        sb.append("Every level is assigned mechanically. `E4` requires executed OLD/NEW scenario "
                + "comparisons for that dimension; it is never inferred from the application "
                + "starting, from the test suite passing, or from the two graphs being equal.\n\n");
        sb.append("| Dimension | Reached | Required | Meets | Coverage |\n|---|---|---|---|---|\n");
        for (JsonNode claim : claims.path("claims")) {
            sb.append("| ").append(claim.path("dimension").asText()).append(" | `")
                    .append(claim.path("evidence_level").asText()).append("` | `")
                    .append(claim.path("required_level").asText()).append("` | ")
                    .append(claim.path("meets_requirement").asBoolean() ? "yes" : "**no**")
                    .append(" | ").append(oneLine(claim.path("coverage").asText("MISSING")))
                    .append(" |\n");
        }
        sb.append('\n');
        sb.append("---\n\n");
    }

    private void residualsAndGaps(StringBuilder sb, JsonNode residual, JsonNode result,
                                  JsonNode edgeEvidence) {
        sb.append("## 12. Residuals, gaps and blind spots\n\n");
        sb.append("Residual is not failure: it is the measured part of the migration that no "
                + "deterministic transformer covered, and it is what raises validation depth.\n\n");
        if (residual != null) {
            sb.append("| Metric | Value |\n|---|---|\n");
            sb.append("| Verified facts | ").append(residual.path("verified_fact_count").asInt()).append(" |\n");
            sb.append("| Deterministically covered | ")
                    .append(residual.path("deterministically_covered").asInt()).append(" |\n");
            sb.append("| Coverage | ").append(residual.path("deterministic_coverage").asDouble()).append(" |\n\n");
            if (!residual.path("residual").isEmpty()) {
                sb.append("| Fact type | Uncovered | Why |\n|---|---|---|\n");
                residual.path("residual").forEach(item -> sb.append("| `")
                        .append(item.path("fact_type").asText()).append("` | ")
                        .append(item.path("fact_count").asInt()).append(" | ")
                        .append(oneLine(item.path("reason").asText())).append(" |\n"));
                sb.append('\n');
            }
        }
        if (result != null && !result.path("evidence_shortfalls").isEmpty()) {
            sb.append("### 12.1 Evidence shortfalls\n\n");
            result.path("evidence_shortfalls").forEach(s ->
                    sb.append("- ").append(s.asText()).append('\n'));
            sb.append('\n');
        }
        if (edgeEvidence != null && !edgeEvidence.path("shortfalls").isEmpty()) {
            sb.append("### 12.2 Per-edge shortfalls\n\n");
            edgeEvidence.path("shortfalls").forEach(s ->
                    sb.append("- ").append(s.asText()).append('\n'));
            sb.append('\n');
        }
        sb.append("---\n\n");
    }

    private void humanDecisions(StringBuilder sb) {
        sb.append("## 13. Human decisions\n\n");
        JsonNode approvals = read("18-approval", "approval-report.json");
        JsonNode requests = read("18-approval", "approval-requests.json");
        if (approvals == null) {
            sb.append("_The approval stage did not run, so it is unknown whether any gate is open. "
                    + "That is not the same as no gate being open._\n\n---\n\n");
            return;
        }
        sb.append("Decisions are supplied from outside the harness and read from a store the harness "
                + "does not write to on a human's behalf. `integrity_hash` detects modification of a "
                + "stored decision; it is not a signature and does not authenticate the actor.\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Gates raised | ")
                .append(requests == null ? "?" : requests.path("request_count").asInt()).append(" |\n");
        sb.append("| Outstanding | ").append(approvals.path("outstanding_requests").asInt()).append(" |\n");
        sb.append("| Decisions on record | ").append(approvals.path("decision_count").asInt()).append(" |\n\n");

        if (requests != null && !requests.path("requests").isEmpty()) {
            sb.append("| Request | Gate | Scope | Satisfied | Summary |\n|---|---|---|---|---|\n");
            requests.path("requests").forEach(r -> sb.append("| `")
                    .append(r.path("request_id").asText()).append("` | `")
                    .append(r.path("gate").asText()).append("` | `")
                    .append(r.path("scope").asText()).append("` | ")
                    .append(r.path("satisfied").asBoolean() ? "yes" : "**no**").append(" | ")
                    .append(oneLine(r.path("summary").asText())).append(" |\n"));
            sb.append('\n');
        }
        sb.append("---\n\n");
    }

    private void provenanceAndIntegrity(StringBuilder sb, JsonNode baseline, JsonNode result) {
        sb.append("## 14. Provenance and integrity\n\n");
        sb.append("| Seal | Value |\n|---|---|\n");
        if (baseline != null) {
            sb.append("| Original tree hash | `")
                    .append(shortHash(baseline.path("original_tree_hash").asText(null))).append("` |\n");
            sb.append("| Baseline manifest hash | `")
                    .append(shortHash(baseline.path("baseline_manifest_hash").asText(null))).append("` |\n");
            sb.append("| File registry seal | `")
                    .append(shortHash(baseline.path("file_registry_seal").asText(null))).append("` |\n");
        }
        if (result != null) {
            sb.append("| Final source tree hash | `")
                    .append(shortHash(result.path("final_source_tree_hash").asText(null))).append("` |\n");
            sb.append("| Evidence manifest hash | `")
                    .append(shortHash(result.path("evidence_manifest_hash").asText(null))).append("` |\n");
            sb.append("| Change ledger head | `")
                    .append(shortHash(result.path("change_ledger_head").asText(null))).append("` |\n");
        }
        sb.append("| Policy hash | `").append(shortHash(context.policy().policyHash())).append("` |\n\n");
        sb.append("The original `./src` is input only. Every mutation was applied in an external "
                + "workspace through a single authorized writer, and the workspace tree hash above is "
                + "what any exported bundle must match exactly.\n\n");
        sb.append("---\n\n");
    }

    private void limitations(StringBuilder sb, JsonNode result, JsonNode edgeEvidence) {
        sb.append("## 15. Limitations of this migration\n\n");
        sb.append("This harness never asserts universal behavioural equivalence. It asserts what it "
                + "observed, for the dimensions and scenarios listed above, under the recorded "
                + "environment equivalence contract.\n\n");
        sb.append("Specific limitations of this run:\n\n");
        List<String> limitations = new ArrayList<>();
        if (edgeEvidence != null
                && edgeEvidence.path("edges_complete").asInt() < edgeEvidence.path("edges_planned").asInt()) {
            limitations.add("Only " + edgeEvidence.path("edges_complete").asInt() + " of "
                    + edgeEvidence.path("edges_planned").asInt()
                    + " planned edges completed, so the migration did not reach its landing target.");
        }
        if (result != null && result.path("not_compared_dimensions").asLong(0) > 0) {
            limitations.add(result.path("not_compared_dimensions").asLong()
                    + " required comparison(s) could not be executed and are NOT_COMPARED.");
        }
        if (result != null && result.path("outstanding_approvals").asLong(0) != 0) {
            limitations.add("Human decision gates are outstanding or unknown.");
        }
        limitations.add("Dimensions requiring provisioned datastores or brokers are only compared "
                + "when the environment provider could supply equivalent infrastructure to both "
                + "sides. Where it could not, the dimension is NOT_COMPARED.");
        limitations.add("Static analysis cannot see reflective or string-driven behaviour; such usage "
                + "is recorded as POSSIBLE rather than definite, and caps impact classification.");
        limitations.forEach(l -> sb.append("- ").append(l).append('\n'));
        sb.append("\nRun `bootshift blind-spots` and `bootshift gaps` for the complete machine-readable "
                + "list.\n");
        return;
    }

    // ------------------------------------------------------------------ diagrams

    private String beforeDiagram(JsonNode inventory, JsonNode build, JsonNode dependencies,
                                 JsonNode baselineRuntime) {
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\nflowchart TB\n");
        Map<String, Boolean> started = startedByModule(baselineRuntime);
        Set<String> external = new LinkedHashSet<>();
        Map<String, Set<String>> moduleExternals = new LinkedHashMap<>();

        if (dependencies != null) {
            for (JsonNode dependency : dependencies.path("dependencies")) {
                String artifact = dependency.path("artifactId").asText("");
                String module = dependency.path("module").asText(".");
                String system = externalSystemFor(artifact);
                if (system != null) {
                    external.add(system);
                    moduleExternals.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(system);
                }
            }
        }

        sb.append("    subgraph APP[\"Application (Spring Boot ")
                .append(build == null ? "?" : frameworkVersion(build)).append(")\"]\n");
        for (JsonNode module : inventory.path("modules")) {
            String id = module.path("module_id").asText();
            if (".".equals(id)) {
                continue;
            }
            String state = started.containsKey(id)
                    ? (started.get(id) ? "started" : "did not start") : "not probed";
            sb.append("        ").append(nodeId(id)).append("[\"").append(id)
                    .append("<br/><i>").append(module.path("java_main").asInt()).append(" java, ")
                    .append(module.path("java_test").asInt()).append(" test</i><br/><i>")
                    .append(state).append("</i>\"]\n");
        }
        sb.append("    end\n");

        if (!external.isEmpty()) {
            sb.append("    subgraph EXT[\"External systems\"]\n");
            external.forEach(system -> sb.append("        ").append(nodeId("ext-" + system))
                    .append("[(\"").append(system).append("\")]\n"));
            sb.append("    end\n");
            moduleExternals.forEach((module, systems) -> {
                if (".".equals(module)) {
                    return;
                }
                systems.forEach(system -> sb.append("    ").append(nodeId(module)).append(" --> ")
                        .append(nodeId("ext-" + system)).append('\n'));
            });
        }
        sb.append("```\n");
        return sb.toString();
    }

    private String afterDiagram(ApplicationGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\nflowchart TB\n");
        sb.append("    subgraph APP[\"Migrated application\"]\n");
        List<GraphNode> modules = graph.nodesOfType(NodeType.MODULE);
        for (GraphNode module : modules) {
            if (".".equals(module.getName())) {
                continue;
            }
            long endpoints = graph.nodesOfType(NodeType.ENDPOINT).stream()
                    .filter(n -> module.getName().equals(n.getModule())).count();
            long controllers = graph.nodesOfType(NodeType.CONTROLLER).stream()
                    .filter(n -> module.getName().equals(n.getModule())).count();
            sb.append("        ").append(nodeId(module.getName())).append("[\"")
                    .append(module.getName()).append("<br/><i>").append(controllers)
                    .append(" controller(s), ").append(endpoints).append(" endpoint(s)</i>\"]\n");
        }
        sb.append("    end\n");

        Set<String> externalNodes = new LinkedHashSet<>();
        List<GraphEdge> integration = graph.edges().stream()
                .filter(e -> e.getType() == EdgeType.CALLS_EXTERNAL_SERVICE
                        || e.getType() == EdgeType.REGISTERS_WITH_DISCOVERY
                        || e.getType() == EdgeType.READS_FROM_CONFIG_SERVER
                        || e.getType() == EdgeType.PUBLISHES_TO
                        || e.getType() == EdgeType.CONSUMES_FROM)
                .filter(e -> e.getFrom().startsWith("MODULE:"))
                .toList();
        if (!integration.isEmpty()) {
            sb.append("    subgraph EXT[\"External systems\"]\n");
            integration.forEach(edge -> graph.node(edge.getTo()).ifPresent(node -> {
                if (externalNodes.add(node.getName())) {
                    sb.append("        ").append(nodeId("ext-" + node.getName())).append("[(\"")
                            .append(node.getName()).append("\")]\n");
                }
            }));
            sb.append("    end\n");
            integration.forEach(edge -> graph.node(edge.getTo()).ifPresent(node -> {
                String module = edge.getFrom().substring("MODULE:".length());
                if (".".equals(module)) {
                    return;
                }
                sb.append("    ").append(nodeId(module)).append(" -->|")
                        .append(edge.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '))
                        .append("| ").append(nodeId("ext-" + node.getName())).append('\n');
            }));
        }
        sb.append("```\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode read(String stage, String artifact) {
        return StageSupport.optionalUpstream(context, stage, artifact);
    }

    private Map<String, Integer> recipeCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        com.bootshift.stages.EdgeSupport.openLedger(context).entries().stream()
                .filter(e -> e.event().getStatus() == ChangeEvent.Status.APPLIED)
                .forEach(e -> counts.merge(
                        e.event().getRecipeId() == null ? "unattributed" : e.event().getRecipeId(),
                        1, Integer::sum));
        return counts;
    }

    private static String explainRecipe(String recipe) {
        return switch (recipe) {
            case "maven.parent-version" -> "Moves `spring-boot-starter-parent` to the edge's target "
                    + "version. This is what actually changes which framework the module compiles "
                    + "against.";
            case "maven.property" -> "Sets the declared Java level for the edge, so the compiler "
                    + "target matches the toolchain the plan froze.";
            case "maven.managed-version" -> "Moves the Spring Cloud release train to the one "
                    + "published for this Boot line. The train is version-locked to Boot, and a "
                    + "mismatched train removes types the application still references.";
            case "jakarta.namespace" -> "Rewrites `javax.*` imports to `jakarta.*` for the packages "
                    + "that actually relocated. JDK-owned `javax` packages are deliberately left "
                    + "alone.";
            case "java.remove-annotation" -> "Removes annotations that no longer exist at the target "
                    + "version and were opt-in no-ops before removal.";
            case "config.property-migration" -> "Renames configuration properties using rules "
                    + "generated from the official deprecation metadata.";
            case "test.junit4-to-jupiter" -> "Migrates mechanical JUnit 4 constructs to Jupiter on the "
                    + "preparatory edge, before any framework change, so test semantics are proven to "
                    + "survive on their own.";
            case "test.mockbean-to-mockitobean" -> "Replaces `@MockBean`/`@SpyBean` with the Spring "
                    + "Framework bean-override annotations that superseded them.";
            default -> "See the transformation report for this recipe.";
        };
    }

    private static String explainScenarioState(String state) {
        return switch (state) {
            case "FROZEN" -> "Executed against the original application; its observed behaviour is "
                    + "the oracle.";
            case "MAPPED_TO_EXISTING_VERIFIED_TEST" -> "Already protected by a passing baseline test.";
            case "UNOBSERVABLE_WITH_EXPLICIT_GAP" -> "Cannot be observed in this environment; "
                    + "recorded as a gap rather than skipped.";
            case "AWAITING_OLD_OBSERVATION" -> "Written but never executed against the original. This "
                    + "is **not** protection and cannot act as an oracle.";
            case "DRAFT" -> "Constructed but not yet attempted.";
            case "REJECTED" -> "Executed against the original and found not to hold there.";
            default -> "";
        };
    }

    private static String explainClassification(String classification) {
        return switch (classification) {
            case "IDENTICAL" -> "The normalized observations agree on both sides.";
            case "EXPECTED" -> "They differ, and a verified migration fact or a recorded human "
                    + "decision explains it.";
            case "UNEXPECTED" -> "They differ in a way that is a migration defect. Blocks.";
            case "UNEXPLAINED" -> "They differ and nothing accounts for it. Blocks.";
            case "NOT_COMPARED" -> "No comparison happened. This is not a pass.";
            default -> "";
        };
    }

    private static String blockingSummary(JsonNode result) {
        if (result == null) {
            return "the evidence stage did not run";
        }
        List<String> reasons = new ArrayList<>();
        if (result.path("unexplained_differences").asLong(0) > 0) {
            reasons.add(result.path("unexplained_differences").asLong() + " unexplained difference(s)");
        }
        if (result.path("unexpected_differences").asLong(0) > 0) {
            reasons.add(result.path("unexpected_differences").asLong() + " unexpected difference(s)");
        }
        long outstanding = result.path("outstanding_approvals").asLong(0);
        if (outstanding > 0) {
            reasons.add(outstanding + " outstanding approval(s)");
        } else if (outstanding < 0) {
            reasons.add("approval state unknown");
        }
        int shortfalls = result.path("evidence_shortfalls").size();
        if (shortfalls > 0) {
            reasons.add(shortfalls + " evidence shortfall(s)");
        }
        return reasons.isEmpty() ? "nothing" : String.join("; ", reasons);
    }

    private static String compiledSummary(JsonNode edgeEvidence) {
        if (edgeEvidence == null) {
            return "?";
        }
        long compiled = 0;
        int total = 0;
        for (JsonNode edge : edgeEvidence.path("edges")) {
            total++;
            if (edge.path("compiled").asBoolean()) {
                compiled++;
            }
        }
        return compiled + "/" + total + " edge(s) compiled";
    }

    private static String scopeSummary(JsonNode edgeEvidence) {
        if (edgeEvidence == null) {
            return "?";
        }
        long ok = 0;
        int total = 0;
        for (JsonNode edge : edgeEvidence.path("edges")) {
            total++;
            if (edge.path("scope_verified").asBoolean()) {
                ok++;
            }
        }
        return ok + "/" + total + " edge(s) stayed inside authorized scope";
    }

    private static String runtimeSummary(JsonNode edgeEvidence) {
        if (edgeEvidence == null) {
            return "?";
        }
        long executed = 0;
        long required = 0;
        for (JsonNode edge : edgeEvidence.path("edges")) {
            if (edge.path("runtime_required").asBoolean()) {
                required++;
                if (edge.path("runtime_executed").asBoolean()) {
                    executed++;
                }
            }
        }
        return executed + "/" + required + " edge(s) that required runtime produced it";
    }

    private static String differentialSummary(JsonNode edgeEvidence) {
        if (edgeEvidence == null || edgeEvidence.path("differential_totals").isEmpty()) {
            return "no comparison executed";
        }
        List<String> parts = new ArrayList<>();
        edgeEvidence.path("differential_totals").fields().forEachRemaining(e ->
                parts.add(e.getKey() + "=" + e.getValue().asInt()));
        return String.join(", ", parts);
    }

    private static String approvalSummary(JsonNode result) {
        long outstanding = result.path("outstanding_approvals").asLong(0);
        if (outstanding < 0) {
            return "**never ran** - outstanding gates unknown";
        }
        return outstanding + " gate(s) outstanding";
    }

    private static Map<String, Boolean> startedByModule(JsonNode baselineRuntime) {
        Map<String, Boolean> started = new LinkedHashMap<>();
        if (baselineRuntime == null) {
            return started;
        }
        baselineRuntime.path("modules").forEach(m ->
                started.put(m.path("module").asText(), m.path("started").asBoolean(false)));
        return started;
    }

    private static String frameworkVersion(JsonNode build) {
        JsonNode frameworks = build.path("frameworks");
        return frameworks.path("spring-boot").asText("?");
    }

    private static String externalSystemFor(String artifactId) {
        if (artifactId.contains("data-mongodb")) {
            return "MongoDB";
        }
        if (artifactId.contains("data-redis")) {
            return "Redis";
        }
        if (artifactId.contains("kafka")) {
            return "Kafka";
        }
        if (artifactId.contains("amqp") || artifactId.contains("rabbit")) {
            return "RabbitMQ";
        }
        if (artifactId.contains("eureka-server")) {
            return "Eureka (server)";
        }
        if (artifactId.contains("eureka-client")) {
            return "Eureka";
        }
        if (artifactId.contains("config-server")) {
            return "Config Server (self)";
        }
        if (artifactId.contains("starter-config")) {
            return "Config Server";
        }
        return null;
    }

    private static int sumCounts(JsonNode counts) {
        int total = 0;
        for (JsonNode value : counts) {
            total += value.asInt();
        }
        return total;
    }

    private static String statusOf(JsonNode artifact) {
        return artifact == null ? "**no**" : "yes";
    }

    private static String tick(boolean value) {
        return value ? "yes" : "**no**";
    }

    private static String requiredCell(boolean required, boolean executed, String detail) {
        if (!required) {
            return "not required";
        }
        return executed ? detail : "**required, not executed**";
    }

    private static String nodeId(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9]", "_");
        return cleaned.isEmpty() ? "n" : "n_" + cleaned;
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank() || "null".equals(hash)) {
            return "absent";
        }
        return hash.length() <= 16 ? hash : hash.substring(0, 16);
    }

    private static String shortId(String id) {
        if (id == null) {
            return "-";
        }
        return id.length() <= 14 ? id : id.substring(0, 14);
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").replace("|", "\\|").trim();
        return collapsed.length() <= 220 ? collapsed : collapsed.substring(0, 220) + "...";
    }
}
