package com.bootshift.stages.stage12;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.adapters.transform.ConfigurationPropertyTransformer;
import com.bootshift.adapters.transform.JakartaNamespaceTransformer;
import com.bootshift.adapters.transform.MavenPomTransformer;
import com.bootshift.adapters.transform.RemovedAnnotationTransformer;
import com.bootshift.adapters.transform.OpenRewriteCoreProvider;
import com.bootshift.adapters.transform.TestFrameworkTransformer;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Json;
import com.bootshift.ports.mutation.MutationPort;
import com.bootshift.ports.transformation.TransformationPort;
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
 * Agent 12 - Transformation (spec section 24).
 *
 * <p>Applies only the deterministic transformations the frozen plan authorized for the current edge.
 * The stage itself never writes a file: it produces proposals and hands them to the
 * {@link FileMutationGateway}, which is the only writer in the system (R13).
 *
 * <p>Forbidden by construction: source-available Spring recipe packs, proprietary engines, direct
 * source writes, broad reformatting of unrelated files, and unverified dependency substitution.
 */
public final class TransformationStage implements Stage {

    public static final String OUTPUT_DIR = "12-transformation";

    private final String edgeId;

    public TransformationStage(String edgeId) {
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
        return "Apply authorized deterministic transformations for the current migration edge";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.PLAN_FROZEN);
    }

    @Override
    public RunState postcondition() {
        return RunState.EDGE_TRANSFORMED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("11-plan/edge-plan.json", "06-target/target-state.json",
                "03-graph/file-registry.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("transformation-report.json", "proposed-changes.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        if (!context.stateMachine().baselineSealed()) {
            throw HarnessException.block(
                    "Transformation refused: the baseline is not sealed. No source mutation is legal "
                            + "before Agent 04 has captured and sealed the baseline (R7).");
        }

        JsonNode edgePlanArtifact = StageSupport.requireUpstream(context, "11-plan", "edge-plan.json",
                "Run: harness plan");
        JsonNode target = StageSupport.requireUpstream(context, "06-target", "target-state.json",
                "Run: harness resolve-target --target auto");
        JsonNode edgePlan = EdgeSupport.findEdge(edgePlanArtifact, edgeId);

        FileRegistry registry = EdgeSupport.loadRegistry(context);
        ChangeLedger ledger = EdgeSupport.openLedger(context);
        FileMutationGateway gateway = EdgeSupport.gateway(context, registry, ledger);

        Path workspace = context.run().migrationWorkspace();
        EdgeSupport.checkpoint(context, edgeId, "start", "Edge " + edgeId + " start");

        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId).mutating(true);

        List<TransformationPort> candidates = List.of(
                new MavenPomTransformer(),
                new JakartaNamespaceTransformer(),
                new TestFrameworkTransformer(),
                new RemovedAnnotationTransformer(),
                ConfigurationPropertyTransformer.fromRuleFile(context.migrationRules()
                        .resolve("generated-properties/property-migration-rules.json")),
                new OpenRewriteCoreProvider());
        Map<String, TransformationPort> providers = providersFor(edgePlan, candidates);

        Set<String> authorizedFileIds = new LinkedHashSet<>();
        edgePlan.path("affected_file_ids").forEach(n -> authorizedFileIds.add(n.asText()));
        List<String> knowledgeRefs = new ArrayList<>();
        edgePlan.path("knowledge_refs").forEach(n -> knowledgeRefs.add(n.asText()));
        List<String> impactRefs = new ArrayList<>();
        edgePlan.path("impact_refs").forEach(n -> impactRefs.add(n.asText()));

        // Build descriptors are always in scope for a version edge even if no impact finding named
        // them: the edge is by definition a change of managed versions.
        Set<String> descriptorPrefixes = new LinkedHashSet<>();
        for (FileRecord record : registry.active()) {
            if (record.getRole().isBuildDescriptor()) {
                authorizedFileIds.add(record.getFileId());
                descriptorPrefixes.add(record.getCurrentPath());
            }
        }

        MutationPort.Authorization authorization = new MutationPort.Authorization(
                edgeId, OUTPUT_DIR, authorizedFileIds, descriptorPrefixes, knowledgeRefs, impactRefs,
                0, 0, false, false, false);

        List<ObjectNode> results = new ArrayList<>();
        List<TransformationPort.ProposedChange> allProposals = new ArrayList<>();
        List<String> unhandled = new ArrayList<>();
        List<MutationPort.MutationOutcome> allOutcomes = new ArrayList<>();
        int applied = 0;
        int rejected = 0;
        int failed = 0;
        String lastCheckpoint = null;
        ChangeEvent.Provider deterministicProvider =
                new ChangeEvent.Provider("BOOTSHIFT_DETERMINISTIC", "bootshift-transformers", "1.0.0");

        for (JsonNode transformation : edgePlan.path("ordered_transformations")) {
            String recipeId = transformation.path("recipe_id").asText();
            TransformationPort provider = providers.get(recipeId);
            ObjectNode result = Json.obj();
            result.put("recipe_id", recipeId);
            result.put("provider", provider == null ? null : provider.providerName());
            result.put("why", transformation.path("why").asText(null));
            result.set("parameters", transformation.path("parameters"));
            if (provider == null) {
                result.put("status", "NO_PROVIDER");
                result.put("detail", "No registered transformer handles " + recipeId
                        + "; recorded as residual");
                unhandled.add(recipeId);
                results.add(result);
                continue;
            }

            // Parameters the planner froze for THIS scheduled entry win over the stage's own
            // defaults: the plan derived them from verified facts, and the stage improvising them
            // at apply time is how a recipe ends up running with a value nobody authorized.
            Map<String, String> parameters =
                    parametersFor(recipeId, edgePlan, target, knowledgeRefs, impactRefs);
            transformation.path("parameters").fields().forEachRemaining(entry ->
                    parameters.put(entry.getKey(), entry.getValue().asText()));
            TransformationPort.TransformationRequest request =
                    new TransformationPort.TransformationRequest(workspace, edgeId,
                            edgePlan.path("source_state").asText(),
                            edgePlan.path("target_state").asText(),
                            targetPathsFor(recipeId, registry),
                            parameters);

            TransformationPort.TransformationOutcome outcome = provider.apply(recipeId, request);
            result.put("status", outcome.success() ? "APPLIED" : "PARTIAL");
            result.put("proposed_changes", outcome.changes().size());
            result.set("messages", Json.toTree(outcome.messages()));
            result.set("unhandled_fact_types", Json.toTree(outcome.unhandledFactTypes()));
            unhandled.addAll(outcome.unhandledFactTypes());
            allProposals.addAll(outcome.changes());

            // Apply this recipe before running the next one. Recipes are not independent: two of
            // them routinely target the same pom.xml, and a transformer reads the file from disk.
            // Batching every recipe and writing at the end would hand the gateway a set of
            // proposals all derived from the pre-edge tree, and the last write for a given file
            // would silently discard the others while the ledger still recorded them as applied.
            MutationPort.BatchOutcome recipeBatch =
                    gateway.apply(authorization, outcome.changes(), deterministicProvider);
            allOutcomes.addAll(recipeBatch.outcomes());
            applied += recipeBatch.applied();
            rejected += recipeBatch.rejected();
            failed += recipeBatch.failed();
            if (recipeBatch.checkpointRef() != null) {
                lastCheckpoint = recipeBatch.checkpointRef();
            }
            result.put("applied", recipeBatch.applied());
            result.put("rejected", recipeBatch.rejected());
            result.put("failed", recipeBatch.failed());
            results.add(result);
        }

        // Nothing above touches the filesystem; every write went through the gateway.
        MutationPort.BatchOutcome batch = new MutationPort.BatchOutcome(
                allOutcomes, applied, rejected, failed, lastCheckpoint);

        // Verify the claim rather than asserting it. The architecture test proves no OTHER code in
        // this repository can write; it cannot prove that nothing did. Re-hashing the tree against
        // what the registry believes is current is the only check that covers a write from outside
        // the JVM - a build plugin, a stray script, a developer's editor.
        //
        // A content mismatch on a registered file is an integrity failure and fails the stage. An
        // untracked or missing path is reported as a gap: build output and tool scratch files land
        // in the workspace legitimately, and failing on those would train operators to ignore this.
        List<String> bypass = gateway.detectBypass();
        List<String> integrityViolations = bypass.stream()
                .filter(v -> v.startsWith("BYPASS:") || v.startsWith("SCAN_FAILED:"))
                .toList();
        List<String> anomalies = bypass.stream()
                .filter(v -> !v.startsWith("BYPASS:") && !v.startsWith("SCAN_FAILED:"))
                .toList();
        envelope.stat("bypass_detections", integrityViolations.size());
        envelope.stat("workspace_anomalies", anomalies.size());
        if (!anomalies.isEmpty()) {
            envelope.gap(new Envelope.Gap("GAP-MUTATION-001", "WORKSPACE_ANOMALY",
                    anomalies.size() + " path(s) in the migration workspace are untracked or "
                            + "missing relative to the file registry",
                    "Expected for build output and tool scratch files; a source file here would "
                            + "mean the registry no longer describes the tree"));
        }

        EdgeSupport.persistRegistry(context, registry);

        ObjectNode report = Json.obj();
        report.put("proposals", allProposals.size());
        report.put("applied", batch.applied());
        report.put("rejected", batch.rejected());
        report.put("failed", batch.failed());
        report.put("checkpoint", batch.checkpointRef());
        report.put("ledger_head", ledger.head());
        report.set("recipes", Json.toTree(results));
        report.set("residual_recipes", Json.toTree(unhandled));
        report.put("writer_rule", "Agent 12 produces proposals only; FileMutationGateway is the sole "
                + "writer of application source (R13)");
        ObjectNode reportArtifact = StageSupport.compose(envelope
                .stat("applied", batch.applied())
                .stat("rejected", batch.rejected()), report);
        writer.write("transformation-report.json", reportArtifact);

        ObjectNode proposals = Json.obj();
        ArrayNode proposalArray = Json.arr();
        for (TransformationPort.ProposedChange proposal : allProposals) {
            ObjectNode node = Json.obj();
            node.put("path", proposal.path());
            node.put("new_path", proposal.newPath());
            node.put("operation", proposal.operation());
            node.put("recipe_id", proposal.recipeId());
            node.put("rationale", proposal.rationale());
            node.put("content_length", proposal.newContent() == null ? 0 : proposal.newContent().length());
            node.set("attributes", Json.toTree(proposal.attributes()));
            proposalArray.add(node);
        }
        proposals.put("count", allProposals.size());
        proposals.set("proposals", proposalArray);
        ArrayNode outcomes = Json.arr();
        batch.outcomes().forEach(o -> {
            ObjectNode node = Json.obj();
            node.put("change_id", o.changeId());
            node.put("file_id", o.fileId());
            node.put("status", o.status().name());
            node.put("reason", o.reason());
            node.put("before_sha256", o.beforeSha256());
            node.put("after_sha256", o.afterSha256());
            node.put("patch_ref", o.patchRef());
            outcomes.add(node);
        });
        proposals.set("outcomes", outcomes);
        writer.write("proposed-changes.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId), proposals));

        String hash = StageSupport.publishForEdge(context, writer, edgeId, OUTPUT_DIR,
                com.bootshift.stages.EdgeIndex.Phase.TRANSFORMED, "published");
        context.stateMachine().transition(RunState.EDGE_TRANSFORMED,
                batch.applied() + " change(s) applied on " + edgeId);
        context.runStateStore().updateState(context.run().runId(), RunState.EDGE_TRANSFORMED,
                "edge " + edgeId + " transformed");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        List<String> messages = new ArrayList<>();
        batch.outcomes().stream()
                .filter(o -> o.status() == ChangeEvent.Status.REJECTED)
                .forEach(o -> messages.add("rejected " + o.fileId() + ": " + o.reason()));
        if (!unhandled.isEmpty()) {
            messages.add("residual after deterministic transformation: " + unhandled);
        }

        if (!integrityViolations.isEmpty()) {
            return new StageResult(OUTPUT_DIR, ExitCode.STAGE_FAILURE,
                    "Mutation integrity violation on " + edgeId + ": " + integrityViolations.size()
                            + " file(s) differ from the gateway-recorded content. Something wrote "
                            + "to the migration workspace without going through the single writer.",
                    integrityViolations, artifacts, null);
        }

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Edge " + edgeId + ": " + batch.applied() + " applied, " + batch.rejected()
                        + " rejected, " + batch.failed() + " failed; ledger head "
                        + ledger.head().substring(0, 12),
                messages, artifacts, hash);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Maps each recipe the frozen plan schedules to the transformer that handles it.
     *
     * <p>The recipe ids come from the plan rather than from a literal list, because a literal list
     * is a third place to keep in sync and forgetting it fails quietly: the stage records
     * {@code NO_PROVIDER}, reports SUCCESS, and the recipe is simply never applied. That happened to
     * {@code java.remove-annotation} — planned, implemented, constructed, and never run, while the
     * edge failed to compile on the exact annotation it was written to delete.
     */
    private static Map<String, TransformationPort> providersFor(JsonNode edgePlan,
                                                                List<TransformationPort> candidates) {
        Map<String, TransformationPort> providers = new LinkedHashMap<>();
        for (JsonNode transformation : edgePlan.path("ordered_transformations")) {
            String recipeId = transformation.path("recipe_id").asText(null);
            if (recipeId == null || providers.containsKey(recipeId)) {
                continue;
            }
            for (TransformationPort candidate : candidates) {
                if (candidate.handles(recipeId)) {
                    providers.put(recipeId, candidate);
                    break;
                }
            }
        }
        return providers;
    }

    /** The files a recipe is allowed to look at. Narrow by construction. */
    private List<String> targetPathsFor(String recipeId, FileRegistry registry) {
        List<String> paths = new ArrayList<>();
        for (FileRecord record : registry.active()) {
            boolean relevant = switch (recipeId) {
                case MavenPomTransformer.RECIPE_PARENT_VERSION, MavenPomTransformer.RECIPE_PROPERTY,
                     MavenPomTransformer.RECIPE_MANAGED_VERSION,
                     MavenPomTransformer.RECIPE_DEPENDENCY_COORDINATE,
                     MavenPomTransformer.RECIPE_ADD_DEPENDENCY,
                     MavenPomTransformer.RECIPE_REMOVE_DEPENDENCY ->
                        record.getRole() == com.bootshift.core.identity.FileRole.MAVEN_BUILD;
                case JakartaNamespaceTransformer.RECIPE,
                     RemovedAnnotationTransformer.RECIPE_REMOVE_ANNOTATION,
                     OpenRewriteCoreProvider.RECIPE_CHANGE_PACKAGE,
                     OpenRewriteCoreProvider.RECIPE_REMOVE_ANNOTATION ->
                        record.getRole().isJavaSource();
                case OpenRewriteCoreProvider.RECIPE_CHANGE_PARENT_POM,
                     OpenRewriteCoreProvider.RECIPE_CHANGE_MAVEN_PROPERTY ->
                        record.getRole() == com.bootshift.core.identity.FileRole.MAVEN_BUILD;
                case TestFrameworkTransformer.RECIPE_JUNIT4_TO_JUPITER,
                     TestFrameworkTransformer.RECIPE_MOCKBEAN ->
                        record.getRole() == com.bootshift.core.identity.FileRole.JAVA_TEST;
                case ConfigurationPropertyTransformer.RECIPE -> record.getRole().isConfiguration();
                default -> false;
            };
            if (relevant) {
                paths.add(record.getCurrentPath());
            }
        }
        return paths;
    }

    private static int majorOf(String version) {
        try {
            return Integer.parseInt(version.split("\\.")[0].replaceAll("[^0-9]", ""));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Parameters a recipe needs, derived from the frozen plan rather than improvised. */
    private Map<String, String> parametersFor(String recipeId, JsonNode edgePlan, JsonNode target,
                                              List<String> knowledgeRefs, List<String> impactRefs) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("knowledge_refs", String.join(",", knowledgeRefs));
        parameters.put("impact_refs", String.join(",", impactRefs));
        String toVersion = edgePlan.path("target_state").asText();
        switch (recipeId) {
            case MavenPomTransformer.RECIPE_PARENT_VERSION -> {
                parameters.put("artifactId", "spring-boot-starter-parent");
                parameters.put("version", toVersion);
            }
            case MavenPomTransformer.RECIPE_PROPERTY -> {
                parameters.put("key", "java.version");
                // The Java baseline moves at the major boundary, not before it. Raising it on a
                // patch edge would change the compiler target for a reason the edge does not carry.
                parameters.put("value", edgePlan.path("edge_java")
                        .asText(edgePlan.path("current_java").asText("17")));
            }
            case MavenPomTransformer.RECIPE_MANAGED_VERSION -> {
                String train = edgePlan.path("spring_cloud_train").asText(null);
                if (train == null || train.isBlank()) {
                    // The planner should have omitted this recipe; refuse rather than guess.
                    break;
                }
                parameters.put("groupId", "org.springframework.cloud");
                parameters.put("artifactId", "spring-cloud-dependencies");
                parameters.put("version", train);
            }
            default -> {
                // recipes that need no extra parameters
            }
        }
        return parameters;
    }
}
