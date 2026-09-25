package com.bootshift.stages.stage13;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.adapters.build.MavenBuildAdapter;
import com.bootshift.adapters.build.ToolchainProbe;
import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.adapters.transform.JakartaNamespaceTransformer;
import com.bootshift.core.domain.Envelope;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.OutputLayout;
import com.bootshift.core.domain.StageResult;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileRegistry;
import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import com.bootshift.core.state.RunState;
import com.bootshift.core.util.Hashing;
import com.bootshift.core.util.Json;
import com.bootshift.ports.ai.AIProvider;
import com.bootshift.ports.build.BuildSystemPort;
import com.bootshift.ports.mutation.MutationPort;
import com.bootshift.ports.transformation.TransformationPort;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.EdgeToolchain;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.stage03.ApplicationGraphStage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 13 - Build and Repair (spec section 28).
 *
 * <p>Compiles after transformation and repairs bounded residual failures. The repair order is fixed:
 * a verified deterministic rule first, then a knowledge-grounded template, then - only if AI is
 * enabled and the budget allows - a bounded AI proposal that must pass verification before it is
 * offered to the gateway.
 *
 * <p>Every budget is explicit. No progress against a root cause means NEEDS_HUMAN, not another
 * attempt.
 */
public final class BuildRepairStage implements Stage {

    public static final String OUTPUT_DIR = "13-build-repair";

    private final String edgeId;

    public BuildRepairStage(String edgeId) {
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
        return "Compile the migrated state and repair bounded residual compile failures";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public boolean aiAssisted() {
        return true;
    }

    @Override
    public List<RunState> preconditions() {
        return List.of(RunState.EDGE_TRANSFORMED);
    }

    @Override
    public RunState postcondition() {
        return RunState.EDGE_COMPILED;
    }

    @Override
    public List<String> inputArtifacts() {
        return List.of("11-plan/edge-plan.json", "08-knowledge/migration-knowledge.json",
                "02-build/build-model.json");
    }

    @Override
    public List<String> outputArtifacts() {
        return List.of("build-report.json", "repair-report.json", "diagnostics.json", "manifest.json");
    }

    @Override
    public StageResult execute(StageContext context) {
        JsonNode edgePlanArtifact = StageSupport.requireUpstream(context, "11-plan", "edge-plan.json",
                "Run: harness plan");
        JsonNode edgePlan = EdgeSupport.findEdge(edgePlanArtifact, edgeId);
        JsonNode buildNode = StageSupport.requireUpstream(context, "02-build", "build-model.json",
                "Run: harness resolve-build --repo <path>");
        JsonNode dependencyNode = StageSupport.requireUpstream(context, "02-build",
                "dependency-model.json", "Run: harness resolve-build --repo <path>");
        JsonNode knowledge = StageSupport.optionalUpstream(context, "08-knowledge",
                "migration-knowledge.json");

        BuildSystemPort.BuildModel buildModel =
                ApplicationGraphStage.readBuildModel(buildNode, dependencyNode);
        FileRegistry registry = EdgeSupport.loadRegistry(context);
        ChangeLedger ledger = EdgeSupport.openLedger(context);
        FileMutationGateway gateway = EdgeSupport.gateway(context, registry, ledger);

        Path workspace = context.run().migrationWorkspace();
        Path logs = context.run().runWorkspace().resolve("repair-logs").resolve(safe(edgeId));

        // The toolchain this edge froze, resolved to a concrete JDK and then verified by asking the
        // binary what it is. Re-deriving a JDK here is what let the plan say one thing and the
        // compile happen on another.
        EdgeToolchain toolchain = EdgeToolchain.forEdge(edgePlan, buildModel);
        EdgeToolchain.Verification toolchainVerification = toolchain.verify();
        String javaHome = toolchain.resolved().javaHome();
        int requiredJava = toolchain.resolved().frozenMajor();
        OutputLayout.StageWriter writer = context.run().output().open(OUTPUT_DIR);
        Envelope envelope = StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId).mutating(true);

        List<ObjectNode> rounds = new ArrayList<>();
        Map<String, Integer> attemptsPerCause = new LinkedHashMap<>();
        Set<String> appliedPatchHashes = new LinkedHashSet<>();
        int aiAttempts = 0;
        boolean compiled = false;
        int round = 0;
        List<CompilerDiagnostics.Cluster> lastClusters = List.of();
        int previousErrorCount = Integer.MAX_VALUE;

        while (round < context.policy().repairMaxTotalRounds()) {
            round++;
            ObjectNode roundNode = Json.obj();
            roundNode.put("round", round);

            List<CompilerDiagnostics.Diagnostic> diagnostics = new ArrayList<>();
            int failedModules = 0;
            for (BuildSystemPort.ModuleModel module : buildModel.modules()) {
                Path moduleRoot = moduleRoot(workspace, module);
                if (!Files.isDirectory(moduleRoot)) {
                    continue;
                }
                Map<String, String> options = new LinkedHashMap<>();
                options.put("bootshift.logSink",
                        logs.resolve("round" + round + "-" + safe(module.moduleId()) + ".log").toString());
                options.putAll(toolchain.buildOptions(null));
                BuildSystemPort.ExecutionResult result = toolchain.providerFor(module, moduleRoot)
                        .compile(workspace, moduleRoot, options);
                if (!result.success()) {
                    failedModules++;
                    List<String> output = new ArrayList<>(result.stdoutTail());
                    output.addAll(result.stderrTail());
                    diagnostics.addAll(CompilerDiagnostics.parse(output));
                }
            }
            roundNode.put("failed_modules", failedModules);
            roundNode.put("diagnostic_count", diagnostics.size());

            if (failedModules == 0) {
                compiled = true;
                roundNode.put("outcome", "COMPILED");
                rounds.add(roundNode);
                break;
            }

            lastClusters = CompilerDiagnostics.cluster(diagnostics);
            roundNode.set("clusters", Json.toTree(lastClusters.stream().map(c -> Map.of(
                    "root_cause", c.rootCause().name(),
                    "signature", c.signature(),
                    "diagnostic_count", c.size(),
                    "explanation", c.explanation())).toList()));

            // Progress requirement: if the error count did not fall, another round is waste.
            if (diagnostics.size() >= previousErrorCount && round > 1) {
                roundNode.put("outcome", "NO_PROGRESS");
                rounds.add(roundNode);
                break;
            }
            previousErrorCount = diagnostics.size();

            List<TransformationPort.ProposedChange> repairs = new ArrayList<>();
            List<ObjectNode> attempted = new ArrayList<>();

            for (CompilerDiagnostics.Cluster cluster : lastClusters) {
                if (CompilerDiagnostics.isEnvironmental(cluster.rootCause())) {
                    ObjectNode note = Json.obj();
                    note.put("signature", cluster.signature());
                    note.put("root_cause", cluster.rootCause().name());
                    note.put("action", "NOT_REPAIRABLE_BY_SOURCE_EDIT");
                    note.put("detail", cluster.explanation());
                    attempted.add(note);
                    continue;
                }
                int used = attemptsPerCause.getOrDefault(cluster.signature(), 0);
                if (used >= context.policy().repairMaxAttemptsPerRootCause()) {
                    ObjectNode note = Json.obj();
                    note.put("signature", cluster.signature());
                    note.put("action", "BUDGET_EXHAUSTED");
                    note.put("attempts", used);
                    attempted.add(note);
                    continue;
                }
                attemptsPerCause.put(cluster.signature(), used + 1);

                // 1. verified deterministic rule
                Optional<TransformationPort.ProposedChange> deterministic =
                        deterministicRepair(cluster, workspace, registry);
                if (deterministic.isPresent()) {
                    repairs.add(deterministic.get());
                    attempted.add(attempt(cluster, "DETERMINISTIC_RULE", deterministic.get().recipeId()));
                    continue;
                }

                // 2. knowledge-grounded template
                Optional<TransformationPort.ProposedChange> template =
                        knowledgeGroundedRepair(cluster, knowledge, workspace, registry);
                if (template.isPresent()) {
                    repairs.add(template.get());
                    attempted.add(attempt(cluster, "KNOWLEDGE_TEMPLATE", template.get().recipeId()));
                    continue;
                }

                // 3. bounded AI proposal
                if (context.run().aiEnabled() && context.ai().enabled()
                        && aiAttempts < context.policy().aiMaxTotalAttempts()) {
                    aiAttempts++;
                    ObjectNode aiNote = attemptAiRepair(context, cluster, workspace, registry, repairs);
                    attempted.add(aiNote);
                    continue;
                }

                attempted.add(attempt(cluster, "NO_REPAIR_AVAILABLE", null));
            }

            roundNode.set("attempts", Json.toTree(attempted));

            if (repairs.isEmpty()) {
                roundNode.put("outcome", "NO_REPAIR_AVAILABLE");
                rounds.add(roundNode);
                break;
            }

            // Duplicate patch detection: proposing the same bytes twice is a loop, not a repair.
            List<TransformationPort.ProposedChange> fresh = new ArrayList<>();
            for (TransformationPort.ProposedChange repair : repairs) {
                String signature = Hashing.sha256(repair.path() + "|" + repair.newContent());
                if (appliedPatchHashes.add(signature)) {
                    fresh.add(repair);
                }
            }
            if (fresh.isEmpty()) {
                roundNode.put("outcome", "REPEATED_PATCH_DETECTED");
                rounds.add(roundNode);
                break;
            }

            Set<String> authorizedFileIds = new LinkedHashSet<>();
            edgePlan.path("affected_file_ids").forEach(n -> authorizedFileIds.add(n.asText()));
            fresh.forEach(r -> registry.byPath(r.path())
                    .ifPresent(record -> authorizedFileIds.add(record.getFileId())));

            MutationPort.Authorization authorization = new MutationPort.Authorization(
                    edgeId, OUTPUT_DIR, authorizedFileIds, Set.of(), List.of(), List.of(),
                    context.policy().aiMaxFilesPerPatch() * 4, 0, false, false, false);
            MutationPort.BatchOutcome batch = gateway.apply(authorization, fresh,
                    new ChangeEvent.Provider("BOOTSHIFT_REPAIR", "bootshift-compile-repair", "1.0.0"));
            roundNode.put("repairs_applied", batch.applied());
            roundNode.put("repairs_rejected", batch.rejected());
            roundNode.put("outcome", "REPAIRED");
            rounds.add(roundNode);
            EdgeSupport.persistRegistry(context, registry);
        }

        EdgeSupport.persistRegistry(context, registry);
        if (compiled) {
            EdgeSupport.checkpoint(context, edgeId, "compiled", "Edge " + edgeId + " compiled");
        }

        ObjectNode buildReport = Json.obj();
        buildReport.put("compiled", compiled);
        buildReport.put("rounds", rounds.size());
        buildReport.put("selected_jdk", javaHome);
        buildReport.set("toolchain", toolchain.toNode(toolchainVerification));
        buildReport.put("required_java_major", requiredJava);
        buildReport.set("round_details", Json.toTree(rounds));
        ObjectNode buildArtifact = StageSupport.compose(envelope
                .stat("compiled", compiled)
                .stat("repair_rounds", rounds.size())
                .stat("ai_attempts", aiAttempts), buildReport);
        writer.write("build-report.json", buildArtifact);

        ObjectNode repairReport = Json.obj();
        repairReport.put("ai_enabled", context.ai().enabled());
        repairReport.put("ai_attempts_used", aiAttempts);
        repairReport.put("ai_attempt_budget", context.policy().aiMaxTotalAttempts());
        repairReport.put("attempts_per_root_cause_budget",
                context.policy().repairMaxAttemptsPerRootCause());
        repairReport.set("attempts_per_root_cause", Json.toTree(attemptsPerCause));
        repairReport.put("repair_order", "verified deterministic rule -> knowledge-grounded template "
                + "-> optional bounded AI proposal, each verified before the gateway sees it");
        repairReport.put("ledger_head", ledger.head());
        writer.write("repair-report.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        repairReport));

        ObjectNode diagnosticsArtifact = Json.obj();
        ArrayNode clusterArray = Json.arr();
        for (CompilerDiagnostics.Cluster cluster : lastClusters) {
            ObjectNode node = Json.obj();
            node.put("root_cause", cluster.rootCause().name());
            node.put("signature", cluster.signature());
            node.put("diagnostic_count", cluster.size());
            node.put("explanation", cluster.explanation());
            node.put("suggested_rule", cluster.suggestedRule());
            ArrayNode samples = Json.arr();
            cluster.diagnostics().stream().limit(8).forEach(d -> {
                ObjectNode sample = Json.obj();
                sample.put("file", d.file());
                sample.put("line", d.line());
                sample.put("message", d.message());
                samples.add(sample);
            });
            node.set("samples", samples);
            clusterArray.add(node);
        }
        diagnosticsArtifact.put("cluster_count", lastClusters.size());
        diagnosticsArtifact.set("clusters", clusterArray);
        writer.write("diagnostics.json",
                StageSupport.compose(StageSupport.envelope(context, OUTPUT_DIR).edgeId(edgeId),
                        diagnosticsArtifact));

        String hash = StageSupport.publishForEdge(context, writer, edgeId, OUTPUT_DIR,
                com.bootshift.stages.EdgeIndex.Phase.COMPILED, "published");

        Map<String, Path> artifacts = new LinkedHashMap<>();
        outputArtifacts().forEach(name -> artifacts.put(name, writer.dir().resolve(name)));

        if (!compiled) {
            context.stateMachine().transition(RunState.NEEDS_HUMAN,
                    "compile repair made no further progress on " + edgeId);
            context.runStateStore().updateState(context.run().runId(), RunState.NEEDS_HUMAN,
                    "compile repair exhausted");
            List<String> messages = new ArrayList<>();
            lastClusters.stream().limit(6).forEach(c -> messages.add(
                    c.rootCause() + " (" + c.size() + " diagnostic(s)): " + c.explanation()));
            return new StageResult(OUTPUT_DIR, ExitCode.HUMAN_DECISION_REQUIRED,
                    "Edge " + edgeId + " does not compile after " + rounds.size()
                            + " repair round(s); " + lastClusters.size() + " root cause cluster(s) remain",
                    messages, artifacts, hash);
        }

        context.stateMachine().transition(RunState.EDGE_COMPILED, "edge " + edgeId + " compiled");
        context.runStateStore().updateState(context.run().runId(), RunState.EDGE_COMPILED,
                "edge " + edgeId + " compiled");

        return new StageResult(OUTPUT_DIR, ExitCode.SUCCESS,
                "Edge " + edgeId + " compiles after " + rounds.size() + " round(s); "
                        + aiAttempts + " AI attempt(s) used of " + context.policy().aiMaxTotalAttempts(),
                List.of(), artifacts, hash);
    }

    // ------------------------------------------------------------------ repair strategies

    /** Repairs that follow from a rule the harness already trusts, needing no inference. */
    private Optional<TransformationPort.ProposedChange> deterministicRepair(
            CompilerDiagnostics.Cluster cluster, Path workspace, FileRegistry registry) {
        if (cluster.rootCause() != CompilerDiagnostics.RootCause.NAMESPACE_MIGRATION) {
            return Optional.empty();
        }
        for (CompilerDiagnostics.Diagnostic diagnostic : cluster.diagnostics()) {
            Optional<FileRecord> record = locate(diagnostic, workspace, registry);
            if (record.isEmpty()) {
                continue;
            }
            Path file = workspace.resolve(record.get().getCurrentPath());
            String content = read(file);
            if (content == null) {
                continue;
            }
            JakartaNamespaceTransformer.Rewrite rewrite = JakartaNamespaceTransformer.rewrite(content);
            if (rewrite.changed() == 0) {
                continue;
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("repair_strategy", "DETERMINISTIC_RULE");
            attributes.put("root_cause", cluster.rootCause().name());
            return Optional.of(new TransformationPort.ProposedChange(
                    record.get().getCurrentPath(), null, "MODIFY", rewrite.content(),
                    "Compile repair: " + cluster.explanation(),
                    JakartaNamespaceTransformer.RECIPE, List.of(), List.of(), attributes));
        }
        return Optional.empty();
    }

    /**
     * Repairs grounded in a verified migration fact. The replacement comes from knowledge, not from
     * pattern-matching the error message.
     */
    private Optional<TransformationPort.ProposedChange> knowledgeGroundedRepair(
            CompilerDiagnostics.Cluster cluster, JsonNode knowledge, Path workspace,
            FileRegistry registry) {
        if (knowledge == null
                || cluster.rootCause() != CompilerDiagnostics.RootCause.REMOVED_OR_RENAMED_API) {
            return Optional.empty();
        }
        String subject = cluster.signature().replace("removed-api:", "");
        for (JsonNode fact : knowledge.path("facts")) {
            if (!fact.path("authorizes_transformation").asBoolean(false)) {
                continue;
            }
            String from = fact.path("from").asText(null);
            String to = fact.path("to").asText(null);
            if (from == null || to == null || !fact.path("subject").asText().contains(subject)) {
                continue;
            }
            for (CompilerDiagnostics.Diagnostic diagnostic : cluster.diagnostics()) {
                Optional<FileRecord> record = locate(diagnostic, workspace, registry);
                if (record.isEmpty()) {
                    continue;
                }
                Path file = workspace.resolve(record.get().getCurrentPath());
                String content = read(file);
                if (content == null || !content.contains(from)) {
                    continue;
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("repair_strategy", "KNOWLEDGE_TEMPLATE");
                attributes.put("knowledge_id", fact.path("knowledge_id").asText());
                return Optional.of(new TransformationPort.ProposedChange(
                        record.get().getCurrentPath(), null, "MODIFY", content.replace(from, to),
                        "Compile repair grounded in verified fact "
                                + fact.path("knowledge_id").asText(),
                        "repair.knowledge-template",
                        List.of(fact.path("knowledge_id").asText()), List.of(), attributes));
            }
        }
        return Optional.empty();
    }

    /**
     * Bounded AI repair.
     *
     * <p>Every verification in spec section 28 runs before the proposal is offered to the gateway, and
     * the AI provenance is attached to the change either way, so a rejected proposal stays visible.
     */
    private ObjectNode attemptAiRepair(StageContext context, CompilerDiagnostics.Cluster cluster,
                                       Path workspace, FileRegistry registry,
                                       List<TransformationPort.ProposedChange> repairs) {
        ObjectNode note = attempt(cluster, "AI_PROPOSAL", null);
        Optional<CompilerDiagnostics.Diagnostic> anchor = cluster.diagnostics().stream().findFirst();
        if (anchor.isEmpty()) {
            note.put("outcome", "NO_ANCHOR");
            return note;
        }
        Optional<FileRecord> record = locate(anchor.get(), workspace, registry);
        if (record.isEmpty()) {
            note.put("outcome", "FILE_NOT_IN_SCOPE");
            return note;
        }
        Path file = workspace.resolve(record.get().getCurrentPath());
        String content = read(file);
        if (content == null) {
            note.put("outcome", "FILE_UNREADABLE");
            return note;
        }

        Map<String, String> aiContext = new LinkedHashMap<>();
        aiContext.put("file", record.get().getCurrentPath());
        aiContext.put("root_cause", cluster.rootCause().name());
        aiContext.put("diagnostics", cluster.diagnostics().stream().limit(5)
                .map(CompilerDiagnostics.Diagnostic::message).reduce((a, b) -> a + "\n" + b).orElse(""));
        aiContext.put("source", content);

        Optional<AIProvider.Proposal> proposal = context.ai().propose(
                AIProvider.Task.RESIDUAL_PATCH_PROPOSAL,
                "Repair the compile error. Return only the complete corrected file content.",
                aiContext, List.of());
        if (proposal.isEmpty()) {
            note.put("outcome", "NO_PROPOSAL");
            return note;
        }

        String candidate = proposal.get().content();
        List<String> verificationFailures = verifyAiProposal(context, content, candidate, record.get());
        note.put("proposal_id", proposal.get().proposalId());
        note.put("prompt_hash", proposal.get().promptHash());
        note.put("response_hash", proposal.get().responseHash());
        note.set("model", Json.toTree(proposal.get().model()));
        note.set("verification_failures", Json.toTree(verificationFailures));

        if (!verificationFailures.isEmpty()) {
            note.put("outcome", "REJECTED_BEFORE_APPLY");
            return note;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("repair_strategy", "AI_PROPOSAL");
        attributes.put("ai_proposal_id", proposal.get().proposalId());
        repairs.add(new TransformationPort.ProposedChange(record.get().getCurrentPath(), null,
                "MODIFY", candidate, "Bounded AI repair for " + cluster.signature(),
                "repair.ai-proposal", List.of(), List.of(), attributes));
        note.put("outcome", "ACCEPTED_FOR_GATEWAY");
        return note;
    }

    /**
     * Deterministic verification of an AI proposal (spec section 28).
     *
     * <p>These checks are the reason AI can never authorize a change: each one is a property the
     * harness can decide without asking the model anything.
     */
    public static List<String> verifyAiProposal(StageContext context, String before, String after,
                                                FileRecord record) {
        List<String> failures = new ArrayList<>();
        if (after == null || after.isBlank()) {
            failures.add("Proposal is empty");
            return failures;
        }
        if (after.equals(before)) {
            failures.add("Proposal is identical to the current content");
        }
        int changed = changedLines(before, after);
        if (changed > context.policy().aiMaxChangedLinesPerPatch()) {
            failures.add("Proposal changes " + changed + " lines, exceeding the AI budget of "
                    + context.policy().aiMaxChangedLinesPerPatch());
        }
        if (after.length() < before.length() / 2) {
            failures.add("Proposal removes more than half the file, which is not a targeted repair");
        }
        // Never weaken tests.
        for (String forbidden : List.of("@Disabled", "@Ignore", "assumeTrue(false)",
                "fail(\"disabled\")")) {
            if (!before.contains(forbidden) && after.contains(forbidden)) {
                failures.add("Proposal introduces " + forbidden + ", which weakens a test");
            }
        }
        long assertionsBefore = countOccurrences(before, "assert");
        long assertionsAfter = countOccurrences(after, "assert");
        if (assertionsAfter < assertionsBefore) {
            failures.add("Proposal removes " + (assertionsBefore - assertionsAfter)
                    + " assertion(s)");
        }
        // Never remove security or transaction behaviour.
        for (String annotation : List.of("@PreAuthorize", "@PostAuthorize", "@Secured",
                "@RolesAllowed", "@Transactional")) {
            if (countOccurrences(before, annotation) > countOccurrences(after, annotation)) {
                failures.add("Proposal removes " + annotation + ", changing security or transaction "
                        + "behaviour");
            }
        }
        // Never introduce a forbidden dependency.
        for (String forbidden : List.of("org.openrewrite.java.spring", "io.moderne")) {
            if (after.contains(forbidden)) {
                failures.add("Proposal introduces the forbidden component " + forbidden);
            }
        }
        // Stay inside the authorized file.
        if (record.getRole() == com.bootshift.core.identity.FileRole.MAVEN_BUILD
                && !after.contains("<project")) {
            failures.add("Proposal does not look like a Maven descriptor for a descriptor file");
        }
        return failures;
    }

    private static long countOccurrences(String text, String needle) {
        long count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static int changedLines(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int max = Math.max(a.length, b.length);
        int changed = 0;
        for (int i = 0; i < max; i++) {
            String left = i < a.length ? a[i] : null;
            String right = i < b.length ? b[i] : null;
            if (left == null || !left.equals(right)) {
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ helpers

    private ObjectNode attempt(CompilerDiagnostics.Cluster cluster, String strategy, String recipeId) {
        ObjectNode node = Json.obj();
        node.put("signature", cluster.signature());
        node.put("root_cause", cluster.rootCause().name());
        node.put("strategy", strategy);
        node.put("recipe_id", recipeId);
        node.put("diagnostic_count", cluster.size());
        return node;
    }

    private Optional<FileRecord> locate(CompilerDiagnostics.Diagnostic diagnostic, Path workspace,
                                        FileRegistry registry) {
        if (diagnostic.file() == null) {
            return Optional.empty();
        }
        String normalized = FileRegistry.normalize(diagnostic.file());
        Optional<FileRecord> exact = registry.byPath(normalized);
        if (exact.isPresent()) {
            return exact;
        }
        return registry.active().stream()
                .filter(r -> normalized.endsWith(r.getCurrentPath()))
                .findFirst();
    }

    private static int requiredJavaFor(JsonNode edgePlan) {
        String targetState = edgePlan.path("target_state").asText("3.0.0");
        return targetState.startsWith("2.") ? 17 : 17;
    }

    private static Path moduleRoot(Path workspace, BuildSystemPort.ModuleModel module) {
        return ".".equals(module.moduleId()) ? workspace : workspace.resolve(module.moduleId());
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
