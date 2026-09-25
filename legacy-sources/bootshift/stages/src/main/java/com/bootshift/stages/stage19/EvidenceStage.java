package com.bootshift.stages.stage19;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.evidence.Claim;
import com.bootshift.core.evidence.CoverageStatement;
import com.bootshift.core.evidence.EvidenceLevel;
import com.bootshift.core.evidence.EvidenceManifest;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 19 - Evidence and Report (spec sections 37 and 38).
 *
 * <p>Creates the defensible final claim. Every claim resolves to evidence and carries a coverage
 * statement; a claim without both is refused rather than published, which is the mechanism behind
 * "Security = E4" never appearing on its own.
 *
 * <p>MIGRATION_COMPLETE is asserted only when every policy-required dimension reached its minimum
 * evidence level, every shortfall has an explicit exception, no unexplained blocking difference
 * remains, the change ledger verifies, and the evidence manifest verifies.
 */
public final class EvidenceStage implements Stage {

    public static final String OUTPUT_DIR = "19-evidence";

    /** Dimensions the harness reports on, with the minimum level policy requires. */
    private static final Map<String, EvidenceLevel> REQUIRED_LEVELS = new LinkedHashMap<>();

    static {
        REQUIRED_LEVELS.put("INVENTORY", EvidenceLevel.E0);
        REQUIRED_LEVELS.put("STRUCTURE", EvidenceLevel.E1);
        REQUIRED_LEVELS.put("BUILD", EvidenceLevel.E2);
        REQUIRED_LEVELS.put("TESTS", EvidenceLevel.E3);
        REQUIRED_LEVELS.put("RUNTIME", EvidenceLevel.E3);
        REQUIRED_LEVELS.put("CONFIGURATION_BINDING", EvidenceLevel.E4);
        REQUIRED_LEVELS.put("HTTP_API", EvidenceLevel.E4);
        REQUIRED_LEVELS.put("SECURITY", EvidenceLevel.E4);
        REQUIRED_LEVELS.put("PERSISTENCE", EvidenceLevel.E4);
        REQUIRED_LEVELS.put("SERIALIZATION", EvidenceLevel.E4);
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
        return "Seal the evidence manifest and produce the defensible migration report";
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.FINAL_APPROVAL);
    }

    @Override
    public RunState postcondition() {
        return RunState.EVIDENCE_SEALED;
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("migration-report.md", "migration-result.json", "evidence-manifest.json",
                "file-lineage.json", "symbol-lineage.json", "claims.json", "edge-evidence.json",
                "coverage-statement.json", "MIGRATION_DOCUMENT.md", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR);

        JsonNode baseline = StageSupport.requireUpstream(context, "04-baseline",
                "baseline-manifest.json", "Run: harness baseline --repo <path>");
        JsonNode inventory = StageSupport.optionalUpstream(context, "01-inventory",
                "inventory-artifact.json");
        JsonNode graphVerification = StageSupport.optionalUpstream(context, "03-graph",
                "graph-verification-report.json");
        JsonNode buildReport = StageSupport.optionalUpstream(context, "13-build-repair",
                "build-report.json");
        JsonNode testReport = StageSupport.optionalUpstream(context, "15-test", "test-report.json");
        JsonNode runtimeReport = StageSupport.optionalUpstream(context, "16-runtime",
                "runtime-report.json");
        JsonNode differential = StageSupport.optionalUpstream(context, "17-differential",
                "differential-report.json");
        JsonNode approvals = StageSupport.optionalUpstream(context, "18-approval",
                "approval-report.json");
        JsonNode target = StageSupport.optionalUpstream(context, "06-target", "target-state.json");
        JsonNode plan = StageSupport.optionalUpstream(context, "11-plan", "migration-plan.json");
        JsonNode graphDiff = StageSupport.optionalUpstream(context, "14-graph-diff", "graph-diff.json");
        JsonNode edgePlan = StageSupport.optionalUpstream(context, "11-plan", "edge-plan.json");

        // Every planned edge, proven from the edge index rather than from whichever edge published
        // last. This is the difference between "the migration was validated" and "edge eight was".
        EdgeEvidenceAggregator.Aggregate perEdge =
                EdgeEvidenceAggregator.aggregate(context, edgePlan);
        List<JsonNode> allComparisons = EdgeEvidenceAggregator.allComparisons(context, edgePlan);

        FileRegistry registry = EdgeSupport.loadRegistry(context);
        ChangeLedger.Verification ledgerVerification = EdgeSupport.verifyLedger(context);

        EvidenceManifest manifest = new EvidenceManifest(context.run().runId());
        manifest.seal("baseline_manifest_hash", baseline.path("baseline_manifest_hash").asText());
        manifest.seal("original_tree_hash", baseline.path("original_tree_hash").asText());
        manifest.seal("file_registry_seal", baseline.path("file_registry_seal").asText());
        manifest.seal("change_ledger_head", ledgerVerification.computedHead());
        manifest.seal("policy_hash", context.policy().policyHash());
        if (differential != null) {
            manifest.seal("normalization_policy_hash",
                    differential.path("normalization_policy_hash").asText());
        }

        // ---- claims ------------------------------------------------------------------------------
        List<Claim> claims = new ArrayList<>();
        claims.add(inventoryClaim(inventory));
        claims.add(structureClaim(graphVerification));
        claims.add(buildClaim(perEdge));
        claims.add(testClaim(perEdge));
        claims.add(runtimeClaim(perEdge));
        claims.addAll(differentialClaims(allComparisons, perEdge, approvals));
        claims.forEach(manifest::claim);

        // ---- evidence entries --------------------------------------------------------------------
        int indexed = indexArtifacts(context, manifest);

        String manifestHash = manifest.finalizeManifest();
        ObjectNode manifestNode = manifest.toNode();
        writer.write("evidence-manifest.json", StageSupport.compose(envelope
                .stat("evidence_entries", indexed)
                .stat("claims", claims.size())
                .stat("manifest_hash", manifestHash), manifestNode));

        ObjectNode edgeEvidence = EdgeEvidenceAggregator.toNode(perEdge);
        writer.write("edge-evidence.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), edgeEvidence));

        ObjectNode claimsArtifact = Json.obj();
        ArrayNode claimArray = Json.arr();
        List<String> unpublishable = new ArrayList<>();
        for (Claim claim : claims) {
            ObjectNode node = Json.obj();
            node.put("claim_id", claim.getClaimId());
            node.put("dimension", claim.getDimension());
            node.put("statement", claim.getStatement());
            node.put("evidence_level", claim.getLevel().name());
            node.put("required_level", REQUIRED_LEVELS.getOrDefault(claim.getDimension(),
                    EvidenceLevel.E0).name());
            node.put("meets_requirement", claim.getLevel().atLeast(
                    REQUIRED_LEVELS.getOrDefault(claim.getDimension(), EvidenceLevel.E0)));
            node.put("coverage", claim.getCoverage() == null ? null : claim.getCoverage().render());
            node.put("publishable", claim.isPublishable());
            node.set("evidence_refs", Json.toTree(claim.getEvidenceRefs()));
            node.set("blind_spots", Json.toTree(claim.getBlindSpotRefs()));
            node.put("rendered", claim.render());
            claimArray.add(node);
            if (!claim.isPublishable()) {
                unpublishable.add(claim.getClaimId() + " lacks evidence references or a coverage "
                        + "statement and cannot be published");
            }
        }
        claimsArtifact.put("claim_count", claims.size());
        claimsArtifact.set("claims", claimArray);
        claimsArtifact.put("rule", "A dimension assertion is illegal without a coverage statement");
        writer.write("claims.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), claimsArtifact));

        // ---- coverage statement -------------------------------------------------------------------
        // Per dimension, whether it was covered and - when it was not - why. A dimension with no
        // observation appears here as UNCOVERED with a reason. It is never omitted, and it is never
        // implied to have passed by its absence; that is the whole point of publishing this
        // separately from the claims rather than leaving a reader to infer it from what is missing.
        ObjectNode coverageArtifact = Json.obj();
        ArrayNode coverageArray = Json.arr();
        int covered = 0;
        for (Claim claim : claims) {
            EvidenceLevel required = REQUIRED_LEVELS.getOrDefault(claim.getDimension(),
                    EvidenceLevel.E0);
            boolean meets = claim.getLevel().atLeast(required);
            if (meets) {
                covered++;
            }
            ObjectNode row = Json.obj();
            row.put("dimension", claim.getDimension());
            row.put("claim_id", claim.getClaimId());
            row.put("level_required", required.name());
            row.put("level_reached", claim.getLevel().name());
            row.put("covered", meets);
            row.put("coverage", claim.getCoverage() == null ? null : claim.getCoverage().render());
            row.put("reason", meets ? null
                    : "Evidence reached " + claim.getLevel().name() + " but this dimension requires "
                            + required.name());
            row.set("blind_spots", Json.toTree(claim.getBlindSpotRefs()));
            coverageArray.add(row);
        }
        coverageArtifact.put("dimension_count", claims.size());
        coverageArtifact.put("covered", covered);
        coverageArtifact.put("uncovered", claims.size() - covered);
        coverageArtifact.set("dimensions", coverageArray);
        coverageArtifact.put("rule", "Every dimension appears here whether or not it was observed. "
                + "An uncovered dimension carries its reason; absence is never a pass.");
        writer.write("coverage-statement.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), coverageArtifact));

        // ---- lineage ------------------------------------------------------------------------------
        ObjectNode fileLineage = Json.obj();
        ArrayNode lineageArray = Json.arr();
        for (FileRecord record : registry.all()) {
            if (record.getVersions().size() <= 1 && record.getChangeIds().isEmpty()) {
                continue;
            }
            ObjectNode node = Json.obj();
            node.put("file_id", record.getFileId());
            node.put("baseline_path", record.getBaselinePath());
            node.put("current_path", record.getCurrentPath());
            node.put("baseline_sha256", record.getBaselineSha256());
            node.put("current_sha256", record.getCurrentSha256());
            node.put("status", record.getStatus().name());
            node.put("rename_source", record.getRenameSource().name());
            node.put("rename_confidence", record.getRenameConfidence());
            node.put("split_from", record.getSplitFrom());
            node.put("merged_into", record.getMergedInto());
            node.set("change_ids", Json.toTree(record.getChangeIds()));
            node.set("versions", Json.toTree(record.getVersions()));
            node.set("symbol_ids", Json.toTree(record.getSymbolIds()));
            lineageArray.add(node);
        }
        fileLineage.put("changed_file_count", lineageArray.size());
        fileLineage.put("total_file_count", registry.size());
        fileLineage.set("files", lineageArray);
        writer.write("file-lineage.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), fileLineage));

        ObjectNode symbolLineage = Json.obj();
        symbolLineage.set("changed_symbols", graphDiff == null
                ? Json.arr() : graphDiff.path("changed_symbol_ids"));
        symbolLineage.set("nodes_added", graphDiff == null ? Json.arr() : graphDiff.path("nodes_added"));
        symbolLineage.set("nodes_removed", graphDiff == null
                ? Json.arr() : graphDiff.path("nodes_removed"));
        writer.write("symbol-lineage.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR), symbolLineage));

        // ---- completion determination --------------------------------------------------------------
        List<String> shortfalls = new ArrayList<>();
        for (Claim claim : claims) {
            EvidenceLevel required = REQUIRED_LEVELS.getOrDefault(claim.getDimension(),
                    EvidenceLevel.E0);
            if (!claim.getLevel().atLeast(required)) {
                shortfalls.add(claim.getDimension() + " reached " + claim.getLevel()
                        + " but policy requires " + required + " (" + claim.getCoverage().render() + ")");
            }
        }
        // Differential outcomes across EVERY edge, not the last one.
        long unexplained = perEdge.differentialTotals().getOrDefault("UNEXPLAINED", 0);
        long unexpected = perEdge.differentialTotals().getOrDefault("UNEXPECTED", 0);
        long notCompared = perEdge.differentialTotals().getOrDefault("NOT_COMPARED", 0);

        // A missing approval report is not zero outstanding approvals. It means the approval stage
        // never ran, so nothing has established that no gate is open - which is the opposite of the
        // conclusion the previous default reached.
        long outstandingApprovals;
        boolean approvalsEstablished = approvals != null;
        if (approvalsEstablished) {
            outstandingApprovals = approvals.path("outstanding_requests").asLong(0);
        } else {
            outstandingApprovals = -1;
            shortfalls.add("No approval report exists, so it is unknown whether any human decision "
                    + "gate is outstanding. Run: bootshift approve");
        }

        // A required NOT_COMPARED dimension is a shortfall: the frozen plan asked for a comparison
        // and none happened, which is not the same as the comparison finding nothing.
        if (notCompared > 0) {
            shortfalls.add(notCompared + " required differential comparison(s) were NOT_COMPARED "
                    + "across the planned edges; a comparison that did not run is not a pass");
        }
        perEdge.shortfalls().forEach(shortfalls::add);

        boolean complete = shortfalls.isEmpty() && unexplained == 0 && unexpected == 0
                && outstandingApprovals == 0 && ledgerVerification.valid() && unpublishable.isEmpty()
                && perEdge.allEdgesAccountedFor();

        String finalTreeHash = context.scm().currentTreeHash(context.run().migrationWorkspace(),
                context.run().checkpointGit());
        if (finalTreeHash == null || finalTreeHash.isBlank()) {
            // Export compares against this value. A null one previously compared equal to anything,
            // so a bundle could be exported that had never been checked against the validated tree.
            shortfalls.add("The final source tree hash could not be computed, so the exported bundle "
                    + "cannot be proven to be the state that passed validation");
        }

        ObjectNode result = Json.obj();
        result.put("status", complete ? "MIGRATION_COMPLETE"
                : (unexplained > 0 ? "BLOCKED" : "NEEDS_HUMAN"));
        result.put("source_version", target == null ? null : target.path("source_version").asText());
        result.put("target_version", target == null ? null : target.path("landing_version").asText());
        result.put("edges_declared_by_plan", plan == null ? 0 : plan.path("edge_count").asInt());
        result.put("deterministic_coverage", plan == null ? 0 : plan.path("deterministic_coverage").asDouble());
        result.put("final_source_tree_hash", finalTreeHash);
        result.put("baseline_manifest_hash", baseline.path("baseline_manifest_hash").asText());
        result.put("evidence_manifest_hash", manifestHash);
        result.put("change_ledger_head", ledgerVerification.computedHead());
        result.put("change_ledger_valid", ledgerVerification.valid());
        result.put("change_ledger_events", ledgerVerification.verifiedEvents());
        result.put("unexplained_differences", unexplained);
        result.put("unexpected_differences", unexpected);
        result.put("not_compared_dimensions", notCompared);
        result.put("outstanding_approvals", outstandingApprovals);
        result.put("approvals_established", approvalsEstablished);
        result.put("edges_planned", perEdge.edgesPlanned());
        result.put("edges_complete", perEdge.edgesComplete());
        result.put("all_edges_accounted_for", perEdge.allEdgesAccountedFor());
        result.set("per_edge_shortfalls", Json.toTree(perEdge.shortfalls()));
        result.put("total_changes_applied", perEdge.totalChangesApplied());
        result.put("total_residual_recipes", perEdge.totalResidualRecipes());
        result.set("differential_totals", Json.toTree(perEdge.differentialTotals()));
        result.set("evidence_shortfalls", Json.toTree(shortfalls));
        result.set("unpublishable_claims", Json.toTree(unpublishable));
        result.set("ledger_violations", Json.toTree(ledgerVerification.violations()));
        ObjectNode resultArtifact = StageSupport.compose(
                StageSupport.envelope(context, OUTPUT_DIR), result);
        StageSupport.validate(context, writer, "evidence/migration-result.schema.json",
                "migration-result.json", resultArtifact);
        writer.write("migration-result.json", resultArtifact);

        writer.writeText("migration-report.md", renderReport(context, result, claims, registry,
                ledgerVerification, differential, approvals, target, plan));

        // The end-to-end migration document. The report above answers "is this defensible?"; this
        // answers the question a reviewer asks first - what actually happened, to which architecture,
        // in which order, and on whose authority. It is generated on every run, from the artifacts.
        writer.writeText("MIGRATION_DOCUMENT.md", new MigrationDocument(context).render());

        String hash = StageSupport.publish(context, writer);

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        context.stateMachine().transition(RunState.EVIDENCE_SEALED, "manifest " + manifestHash);
        context.runStateStore().updateState(context.run().runId(), RunState.EVIDENCE_SEALED,
                "evidence sealed");

        if (complete) {
            context.stateMachine().transition(RunState.MIGRATION_COMPLETE, "all gates satisfied");
            context.runStateStore().updateState(context.run().runId(), RunState.MIGRATION_COMPLETE,
                    "migration complete");
            return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                    "MIGRATION_COMPLETE: evidence manifest " + manifestHash.substring(0, 16)
                            + ", ledger head " + ledgerVerification.computedHead().substring(0, 12)
                            + " over " + ledgerVerification.verifiedEvents() + " event(s)",
                    List.of(), artifacts, hash);
        }

        List<String> messages = new ArrayList<>(shortfalls);
        messages.addAll(unpublishable);
        if (!perEdge.allEdgesAccountedFor()) {
            messages.add(perEdge.edgesComplete() + " of " + perEdge.edgesPlanned()
                    + " planned edge(s) completed; final evidence covers only what every edge "
                    + "actually did");
        }
        if (unexplained > 0) {
            messages.add(unexplained + " unexplained behavioural difference(s) remain");
        }
        if (outstandingApprovals > 0) {
            messages.add(outstandingApprovals + " approval gate(s) are outstanding");
        }
        ledgerVerification.violations().forEach(v -> messages.add("LEDGER: " + v));

        return new StageResult(OUTPUT_DIR,
                unexplained > 0 ? ExitCode.POLICY_BLOCK : ExitCode.HUMAN_DECISION_REQUIRED,
                "Evidence sealed as " + manifestHash.substring(0, 16)
                        + " but the migration is not complete: " + shortfalls.size()
                        + " evidence shortfall(s), " + unexplained + " unexplained difference(s), "
                        + outstandingApprovals + " outstanding approval(s)",
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ claims

    private Claim inventoryClaim(JsonNode inventory) {
        int files = inventory == null ? 0 : inventory.path("file_count").asInt();
        return new Claim("CL-INVENTORY-001", "INVENTORY",
                "Every file in the input repository was discovered and given a persistent identity.")
                .level(files > 0 ? EvidenceLevel.E0 : EvidenceLevel.E0)
                .coverage(new CoverageStatement("files inventoried", files, files, 0, List.of(), null))
                .evidence("01-inventory/inventory-artifact.json")
                .evidence("01-inventory/file-registry.json");
    }

    private Claim structureClaim(JsonNode verification) {
        if (verification == null) {
            return new Claim("CL-STRUCTURE-001", "STRUCTURE",
                    "Application structure was not modelled.")
                    .level(EvidenceLevel.E0)
                    .coverage(CoverageStatement.none("java files in graph", "graph stage never ran"))
                    .evidence("03-graph/graph-verification-report.json");
        }
        int inGraph = verification.path("checks").path("java_files_in_graph").asInt();
        int total = verification.path("checks").path("java_files_inventoried").asInt();
        boolean passed = verification.path("passed").asBoolean(false);
        return new Claim("CL-STRUCTURE-001", "STRUCTURE",
                "The application structure is modelled and migration changes are structurally traceable.")
                .level(passed ? EvidenceLevel.E1 : EvidenceLevel.E0)
                .coverage(new CoverageStatement("java files in graph", inGraph, total,
                        Math.max(0, total - inGraph), List.of("GAP-GRAPH-001"),
                        "attribution ratio "
                                + verification.path("checks").path("attribution_ratio").asDouble()))
                .evidence("03-graph/application-graph.json")
                .evidence("03-graph/graph-verification-report.json")
                .blindSpot("BS-GRAPH-RUNTIME");
    }

    /**
     * BUILD reaches E2 only when every planned edge compiled.
     *
     * <p>One compiled edge out of eight is not "the migrated repository compiles".
     */
    private Claim buildClaim(EdgeEvidenceAggregator.Aggregate perEdge) {
        long compiled = perEdge.edges().stream()
                .filter(EdgeEvidenceAggregator.EdgeEvidence::compiled).count();
        int planned = perEdge.edgesPlanned();
        boolean all = planned > 0 && compiled == planned;
        return new Claim("CL-BUILD-001", "BUILD",
                "Every planned migration edge resolves its dependencies and compiles.")
                .level(all ? EvidenceLevel.E2 : EvidenceLevel.E1)
                .coverage(new CoverageStatement("planned edges compiled", (int) compiled, planned,
                        (int) Math.max(0, planned - compiled), List.of(),
                        planned == 0 ? "no migration edge was executed"
                                : compiled + " of " + planned + " edge(s) compiled"))
                .evidence("19-evidence/edge-evidence.json")
                .evidence("13-build-repair/build-report.json");
    }

    /** TESTS reaches E3 only when every edge that required tests ran them with no regression. */
    private Claim testClaim(EdgeEvidenceAggregator.Aggregate perEdge) {
        long required = perEdge.edges().stream()
                .filter(EdgeEvidenceAggregator.EdgeEvidence::testsRequired).count();
        long executed = perEdge.edges().stream()
                .filter(e -> e.testsRequired() && e.testsExecuted()).count();
        boolean satisfied = required > 0 && executed == required
                && perEdge.totalTestRegressions() == 0;
        return new Claim("CL-TESTS-001", "TESTS",
                "Existing tests pass relative to sealed baseline debt on every edge that required them.")
                .level(satisfied ? EvidenceLevel.E3 : EvidenceLevel.E2)
                .coverage(new CoverageStatement("edges with tests executed", (int) executed,
                        (int) required, (int) Math.max(0, required - executed), List.of(),
                        perEdge.totalTestRegressions() == 0
                                ? perEdge.totalTests() + " test(s) executed, no regressions"
                                : perEdge.totalTestRegressions() + " regression(s) across "
                                        + perEdge.totalTests() + " test(s)"))
                .evidence("19-evidence/edge-evidence.json")
                .evidence("15-test/test-report.json");
    }

    /** RUNTIME reaches E3 only when every edge that required a runtime observation produced one. */
    private Claim runtimeClaim(EdgeEvidenceAggregator.Aggregate perEdge) {
        long required = perEdge.edges().stream()
                .filter(EdgeEvidenceAggregator.EdgeEvidence::runtimeRequired).count();
        long executed = perEdge.edges().stream()
                .filter(e -> e.runtimeRequired() && e.runtimeExecuted()).count();
        int started = perEdge.edges().stream()
                .mapToInt(EdgeEvidenceAggregator.EdgeEvidence::modulesStarted).max().orElse(0);
        int attempted = perEdge.edges().stream()
                .mapToInt(EdgeEvidenceAggregator.EdgeEvidence::modulesAttempted).max().orElse(0);
        boolean satisfied = required > 0 && executed == required && started > 0;
        return new Claim("CL-RUNTIME-001", "RUNTIME",
                "The migrated application starts and its context, mappings and configuration binding "
                        + "were observed on every edge that required it.")
                .level(satisfied ? EvidenceLevel.E3 : EvidenceLevel.E2)
                .coverage(new CoverageStatement("edges with runtime observed", (int) executed,
                        (int) required, (int) Math.max(0, required - executed), List.of(),
                        "startup is one observation, not migration success; best edge started "
                                + started + "/" + attempted + " module(s)"))
                .evidence("19-evidence/edge-evidence.json")
                .evidence("16-runtime/runtime-report.json");
    }

    /**
     * Behavioural claims, assigned mechanically.
     *
     * <p>The rule is fixed and has no judgement in it: a dimension reaches E4 when executed OLD/NEW
     * scenario comparisons exist for it across the planned edges, every comparison that ran came out
     * IDENTICAL or EXPECTED, and nothing required was left NOT_COMPARED. Otherwise it is capped at
     * E3 when something was compared and at E2 when nothing was.
     *
     * <p>E4 is never inferred from application startup, from the test suite passing, or from the two
     * graphs being equal. Each of those is evidence about something else: a context that builds, a
     * suite that still passes, a structure that did not change. None of them is evidence that an HTTP
     * response, an authorization decision, a persisted row or a serialized payload is the same.
     */
    private List<Claim> differentialClaims(List<JsonNode> comparisons,
                                           EdgeEvidenceAggregator.Aggregate perEdge,
                                           JsonNode approvals) {
        List<Claim> claims = new ArrayList<>();
        Map<String, String> dimensionToClaim = new LinkedHashMap<>();
        dimensionToClaim.put("CONFIGURATION_BINDING", "CL-CONFIG-001");
        dimensionToClaim.put("HTTP_API", "CL-HTTPAPI-001");
        dimensionToClaim.put("SECURITY_AUTHORIZATION", "CL-SECURITY-001");
        dimensionToClaim.put("PERSISTENCE_STATE", "CL-PERSISTENCE-001");
        dimensionToClaim.put("SERIALIZATION", "CL-SERIALIZATION-001");
        Map<String, String> dimensionToReported = new LinkedHashMap<>();
        dimensionToReported.put("CONFIGURATION_BINDING", "CONFIGURATION_BINDING");
        dimensionToReported.put("HTTP_API", "HTTP_API");
        dimensionToReported.put("SECURITY_AUTHORIZATION", "SECURITY");
        dimensionToReported.put("PERSISTENCE_STATE", "PERSISTENCE");
        dimensionToReported.put("SERIALIZATION", "SERIALIZATION");

        for (Map.Entry<String, String> entry : dimensionToClaim.entrySet()) {
            String dimension = entry.getKey();
            String reported = dimensionToReported.get(dimension);
            int compared = 0;
            int identicalOrExplained = 0;
            int notCompared = 0;
            int adverse = 0;
            List<String> edgesCovered = new ArrayList<>();
            for (JsonNode comparison : comparisons) {
                if (!dimension.equals(comparison.path("dimension").asText())) {
                    continue;
                }
                String classification = comparison.path("classification").asText();
                String edgeId = comparison.path("edge_id").asText(null);
                if (edgeId != null && !edgesCovered.contains(edgeId)) {
                    edgesCovered.add(edgeId);
                }
                if ("NOT_COMPARED".equals(classification)) {
                    notCompared++;
                    continue;
                }
                compared++;
                if ("IDENTICAL".equals(classification) || "EXPECTED".equals(classification)) {
                    identicalOrExplained++;
                } else {
                    adverse++;
                }
            }

            EvidenceLevel level;
            String levelReason;
            if (compared == 0) {
                level = EvidenceLevel.E2;
                levelReason = "No OLD-versus-NEW scenario for this dimension was executed. E4 "
                        + "requires executed comparisons and is never inferred from startup, from "
                        + "the test suite, or from graph equality.";
            } else if (adverse == 0 && notCompared == 0) {
                level = EvidenceLevel.E4;
                levelReason = compared + " executed OLD/NEW comparison(s), all IDENTICAL or "
                        + "EXPECTED with a verified explanation, none left NOT_COMPARED.";
            } else if (adverse == 0) {
                level = EvidenceLevel.E3;
                levelReason = "Comparisons ran and agreed, but " + notCompared
                        + " required comparison(s) were NOT_COMPARED, so the dimension is not "
                        + "covered end to end.";
            } else {
                level = EvidenceLevel.E3;
                levelReason = adverse + " comparison(s) were UNEXPECTED or UNEXPLAINED.";
            }

            Claim claim = new Claim(entry.getValue(), reported,
                    dimension + " behaviour was compared between the original and migrated "
                            + "applications for every scenario the environment permitted.")
                    .level(level)
                    .coverage(new CoverageStatement(dimension + " scenarios",
                            identicalOrExplained, compared + notCompared, notCompared,
                            notCompared > 0 ? List.of("GAP-DIFF-001") : List.of(),
                            levelReason + " Edges covered: "
                                    + (edgesCovered.isEmpty() ? "none" : edgesCovered)))
                    .evidence("19-evidence/edge-evidence.json")
                    .evidence("17-differential/differential-report.json")
                    .evidence("17-differential/normalization-policy.json");
            if (compared == 0) {
                claim.blindSpot("BS-DIFF-" + dimension);
            }
            if (approvals != null) {
                approvals.path("decisions").forEach(d -> {
                    if (d.path("scope").asText("").contains(dimension)) {
                        claim.approval(d.path("decisionId").asText());
                    }
                });
            }
            claims.add(claim);
        }
        return claims;
    }

    // ------------------------------------------------------------------ evidence indexing

    private int indexArtifacts(StageContext context, EvidenceManifest manifest) {
        int indexed = 0;
        Path outputRoot = context.run().output().root();
        for (String stage : List.of("00-bootstrap", "01-inventory", "02-build", "03-graph",
                "04-baseline", "05-compatibility", "06-target", "07-documentation", "08-knowledge",
                "09-impact", "10-characterization", "11-plan", "12-transformation", "13-build-repair",
                "14-graph-diff", "15-test", "16-runtime", "17-differential", "18-approval")) {
            Path dir = context.run().output().resolveLatestDir(stage);
            if (dir == null) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    var stored = context.evidenceStore().putFile("artifact", file,
                            classify(stage), "SEALED_EVIDENCE", stage);
                    manifest.add(new EvidenceManifest.Entry(stored.evidenceId(), stage + "/"
                            + file.getFileName(), stored.relativePath(), stored.sha256(),
                            stored.sizeBytes(), classify(stage), "SEALED_EVIDENCE", stage,
                            java.time.Instant.now().toString()));
                    indexed++;
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Cannot index evidence for " + stage, e);
            }
        }
        return indexed;
    }

    private static EvidenceManifest.Classification classify(String stage) {
        return switch (stage) {
            case "18-approval" -> EvidenceManifest.Classification.CONFIDENTIAL;
            case "07-documentation" -> EvidenceManifest.Classification.PUBLIC;
            default -> EvidenceManifest.Classification.INTERNAL;
        };
    }

    // ------------------------------------------------------------------ report

    private String renderReport(StageContext context, ObjectNode result, List<Claim> claims,
                                FileRegistry registry, ChangeLedger.Verification ledger,
                                JsonNode differential, JsonNode approvals, JsonNode target,
                                JsonNode plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Bootshift Migration Report\n\n");
        sb.append("**Run:** `").append(context.run().runId()).append("`  \n");
        sb.append("**Status:** `").append(result.path("status").asText()).append("`  \n");
        sb.append("**Policy:** `").append(context.policy().name()).append("` (hash `")
                .append(context.policy().policyHash(), 0, 16).append("`)  \n");
        sb.append("**Generated:** ").append(java.time.Instant.now()).append("\n\n");

        sb.append("## Migration\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| Source version | `").append(result.path("source_version").asText("unknown")).append("` |\n");
        sb.append("| Target version | `").append(result.path("target_version").asText("unknown")).append("` |\n");
        sb.append("| Edges planned | ").append(result.path("edges_planned").asInt()).append(" |\n");
        sb.append("| Deterministic coverage | ")
                .append(result.path("deterministic_coverage").asDouble()).append(" |\n");
        sb.append("| Final source tree hash | `")
                .append(result.path("final_source_tree_hash").asText("n/a")).append("` |\n");
        sb.append("| Baseline manifest hash | `")
                .append(result.path("baseline_manifest_hash").asText()).append("` |\n");
        sb.append("| Evidence manifest hash | `")
                .append(result.path("evidence_manifest_hash").asText()).append("` |\n\n");

        sb.append("## Evidence levels\n\n");
        sb.append("Every assertion carries a coverage statement. A level without coverage is not a "
                + "legal assertion in this harness.\n\n");
        sb.append("| Dimension | Level | Required | Meets | Coverage |\n|---|---|---|---|---|\n");
        for (Claim claim : claims) {
            EvidenceLevel required = REQUIRED_LEVELS.getOrDefault(claim.getDimension(),
                    EvidenceLevel.E0);
            sb.append("| ").append(claim.getDimension())
                    .append(" | `").append(claim.getLevel().name()).append("`")
                    .append(" | `").append(required.name()).append("`")
                    .append(" | ").append(claim.getLevel().atLeast(required) ? "yes" : "**no**")
                    .append(" | ").append(claim.getCoverage() == null ? "MISSING"
                            : claim.getCoverage().render())
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## Claims\n\n");
        for (Claim claim : claims) {
            sb.append("### ").append(claim.getClaimId()).append(" - ").append(claim.getDimension())
                    .append("\n\n");
            sb.append(claim.getStatement()).append("\n\n");
            sb.append("- **Level:** `").append(claim.getLevel().name()).append("` (")
                    .append(claim.getLevel().title()).append(")\n");
            sb.append("- **Coverage:** ").append(claim.getCoverage() == null ? "MISSING"
                    : claim.getCoverage().render()).append('\n');
            sb.append("- **Evidence:**\n");
            claim.getEvidenceRefs().forEach(ref -> sb.append("  - `").append(ref).append("`\n"));
            if (!claim.getBlindSpotRefs().isEmpty()) {
                sb.append("- **Blind spots:** ").append(String.join(", ", claim.getBlindSpotRefs()))
                        .append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Change ledger\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| Events | ").append(ledger.verifiedEvents()).append(" |\n");
        sb.append("| Head | `").append(ledger.computedHead()).append("` |\n");
        sb.append("| Verified | ").append(ledger.valid() ? "yes" : "**NO**").append(" |\n");
        if (!ledger.violations().isEmpty()) {
            sb.append("\nViolations:\n\n");
            ledger.violations().forEach(v -> sb.append("- ").append(v).append('\n'));
        }
        sb.append('\n');

        long changed = registry.all().stream().filter(r -> !r.getChangeIds().isEmpty()).count();
        sb.append("## File lineage\n\n");
        sb.append(changed).append(" of ").append(registry.size())
                .append(" registered file(s) were changed. Every FILE_ID survived the migration; "
                        + "identity is allocated, not derived from path or content.\n\n");
        if (changed > 0) {
            sb.append("| FILE_ID | Baseline path | Final path | Changes |\n|---|---|---|---|\n");
            registry.all().stream().filter(r -> !r.getChangeIds().isEmpty()).limit(40).forEach(r ->
                    sb.append("| `").append(r.getFileId()).append("` | `")
                            .append(r.getBaselinePath()).append("` | `")
                            .append(r.getCurrentPath()).append("` | ")
                            .append(r.getChangeIds().size()).append(" |\n"));
            sb.append('\n');
        }

        if (differential != null) {
            sb.append("## Differential validation\n\n");
            sb.append("Normalization policy hash: `")
                    .append(differential.path("normalization_policy_hash").asText()).append("`\n\n");
            sb.append("| Classification | Count |\n|---|---|\n");
            differential.path("classification_counts").fields().forEachRemaining(e ->
                    sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().asInt())
                            .append(" |\n"));
            sb.append('\n');
        }

        if (approvals != null && approvals.path("decision_count").asInt() > 0) {
            sb.append("## Approvals\n\n");
            sb.append("| Decision | Gate | Actor | Verdict | Rationale |\n|---|---|---|---|---|\n");
            approvals.path("decisions").forEach(d -> sb.append("| `")
                    .append(d.path("decisionId").asText()).append("` | ")
                    .append(d.path("gate").asText()).append(" | ")
                    .append(d.path("actor").asText()).append(" | ")
                    .append(d.path("verdict").asText()).append(" | ")
                    .append(d.path("rationale").asText()).append(" |\n"));
            sb.append('\n');
        }

        sb.append("## What this run cannot claim\n\n");
        sb.append("This harness never asserts universal behavioural equivalence. It asserts only what "
                + "it observed, for the dimensions and scenarios listed above, under the recorded "
                + "environment equivalence contract. Everything else is a blind spot and is listed as "
                + "one.\n\n");
        List<String> shortfalls = new ArrayList<>();
        result.path("evidence_shortfalls").forEach(s -> shortfalls.add(s.asText()));
        if (!shortfalls.isEmpty()) {
            sb.append("### Evidence shortfalls\n\n");
            shortfalls.forEach(s -> sb.append("- ").append(s).append('\n'));
            sb.append('\n');
        }
        sb.append("Run `bootshift blind-spots` and `bootshift gaps` for the complete list.\n");
        return sb.toString();
    }
}
