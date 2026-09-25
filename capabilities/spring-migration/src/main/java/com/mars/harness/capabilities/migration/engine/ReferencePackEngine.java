package com.mars.harness.capabilities.migration.engine;

import com.bootshift.core.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.capabilities.migration.pack.ReferencePack;
import com.mars.harness.capabilities.migration.transform.JavaMigrationTransformer;
import com.mars.harness.capabilities.migration.transform.PomEditor;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.change.UnifiedDiff;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.identity.ProviderHints;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.validation.DimensionStatus;
import com.mars.harness.kernel.core.validation.ValidationDimension;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.ports.build.BuildModelView;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.capability.CapabilityContext;
import com.mars.harness.kernel.ports.identity.IdentityView;
import com.mars.harness.kernel.ports.migration.MigrationCapability;
import com.mars.harness.kernel.ports.mutation.ProposalSink;
import com.mars.harness.kernel.ports.runtime.ProbeComparator;
import com.mars.harness.kernel.ports.runtime.RuntimePort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The reference-pack migration workflow ({@code 04d-version-migration}), deterministic, over the
 * unified kernel.
 *
 * <p>Preserved rules, quoted from the SKILL:
 *
 * <ul>
 *   <li>"The project directory is never edited." Every edit is a proposal the kernel applies to
 *       its own isolated workspace. Builds run in disposable copies.</li>
 *   <li>Round 0, the build and baseline probes, runs before any version is changed. Here it is
 *       the sealed baseline. "A project that does not build before a migration cannot be
 *       migrated." Pre-existing test failures are recorded, never fixed.</li>
 *   <li>The build-file section of the pack is applied up front. "Do not pre-emptively rewrite
 *       source code in this step."</li>
 *   <li>"The compiler is the authority; the reference pack is the map." A source rule is applied
 *       only because a round failed with that rule's quoted symptom, and only to the files the
 *       errors name. An error no rule matches stops the run for a human: "never guess an import
 *       path".</li>
 *   <li>Every round is kept. "Behaviour is the acceptance criterion, not compilation."</li>
 * </ul>
 */
public final class ReferencePackEngine {

    private static final String BASELINE_LABEL = "Pre-migration reference build";

    // ================================================================== plan

    public MigrationCapability.MigrationPlan plan(CapabilityContext context, MigrationAssessment assessment) {
        List<String> stops = new ArrayList<>();
        List<String> evidence = new ArrayList<>(assessment.evidence());
        Optional<ReferencePack> pack = ReferencePack.loadAll(context.harnessRoot()).stream()
                .filter(p -> p.frontMatter().id().equals(assessment.referencePackId())).findFirst();
        if (pack.isEmpty()) {
            stops.add("No matching reference pack is a stop condition, not a licence to improvise (write a pack for this "
                    + "jump before migrating; do not migrate from memory)");
        } else if (!pack.get().rulesCurrent()) {
            stops.add("The reference pack text changed after its rules were derived (sha " + pack.get().packSha256()
                    + " != " + pack.get().rules().path("pack_sha256").asText() + "); re-derive the rules first");
        }
        Optional<BuildPort.BuildResult> baseline = baselineBuild(context);
        if (baseline.isEmpty()) {
            stops.add("Round 0 (the pre-migration reference build) did not run; it must run before any version is changed");
        } else if (baseline.get().outcome() == BuildPort.Outcome.TOOL_UNAVAILABLE) {
            stops.add("Round 0 could not run: " + baseline.get().unavailableReason());
        } else if (baseline.get().outcome() == BuildPort.Outcome.COMPILE_FAILED
                || baseline.get().outcome() == BuildPort.Outcome.DEPENDENCY_FAILED) {
            stops.add("It does not compile. A project that does not build before a migration cannot be migrated (round 0 "
                    + baseline.get().outcome().legacyId() + ")");
        }
        assessment.blockers().forEach(b -> {
            if (!b.blockerId().equals("BLK-NO-PACK")) {
                stops.add(b.description());
            }
        });
        List<MigrationCapability.PlannedRule> buildRules = new ArrayList<>();
        List<MigrationCapability.PlannedRule> symptomRules = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        pack.ifPresent(p -> {
            for (JsonNode r : p.buildFileRules()) {
                buildRules.add(new MigrationCapability.PlannedRule(r.path("id").asText(), r.path("section").asText(), "BUILD_FILE",
                        r.path("description").asText(), List.of()));
                allowed.add(r.path("id").asText());
            }
            for (JsonNode r : p.symptomRules()) {
                List<String> symptoms = new ArrayList<>();
                r.path("symptoms").forEach(s -> symptoms.add(s.asText()));
                r.path("test_symptoms").forEach(s -> symptoms.add(s.asText()));
                symptomRules.add(new MigrationCapability.PlannedRule(r.path("id").asText(), r.path("section").asText(), "SYMPTOM",
                        r.path("description").asText(), symptoms));
                allowed.add(r.path("id").asText());
            }
        });
        MigrationAssessment.RecommendedTarget target = assessment.recommendedTarget();
        return new MigrationCapability.MigrationPlan(HarnessIds.allocate(HarnessIds.Kind.PLAN), context.runId(),
                "reference-pack", pack.map(p -> p.frontMatter().id()).orElse(null), pack.map(ReferencePack::packSha256).orElse(null),
                "spring-boot", assessment.current().frameworkVersion(), target == null ? null : target.version(),
                assessment.current().javaVersion(), pack.map(p -> p.frontMatter().languageTo()).orElse(null), buildRules,
                symptomRules, allowed, "PACKAGE", context.policy().migration().maxRounds(), evidence, stops,
                Instant.now().toString());
    }

    // ================================================================== execute

    public MigrationCapability.MigrationExecution execute(CapabilityContext context, MigrationCapability.MigrationPlan plan,
                                                          ProposalSink sink, MigrationCapability.MigrationExecution previous) {
        ReferencePack pack = ReferencePack.loadAll(context.harnessRoot()).stream()
                .filter(p -> p.frontMatter().id().equals(plan.referencePackId())).findFirst().orElseThrow();
        List<MigrationCapability.RoundRecord> rounds = new ArrayList<>(previous == null ? List.of() : previous.rounds());
        List<String> proposalIds = new ArrayList<>(previous == null ? List.of() : previous.proposalIds());
        List<String> changeIds = new ArrayList<>(previous == null ? List.of() : previous.changeIds());
        List<String> evidence = new ArrayList<>(previous == null ? List.of() : previous.evidenceRefs());
        BuildPort.BuildResult round0 = baselineBuild(context).orElseThrow();

        if (rounds.isEmpty()) {
            rounds.add(roundZero(context, round0, plan, evidence));
        }
        List<String> pendingRules = new ArrayList<>();
        List<String> pendingProposals = new ArrayList<>();
        List<String> pendingChanges = new ArrayList<>();
        if (rounds.size() == 1) {
            // Step 5: the pack's build-file section, applied up front. No source is touched here.
            for (JsonNode rule : pack.buildFileRules()) {
                for (Change change : buildFileChanges(context, rule, plan)) {
                    Submission s = submit(context, sink, change, plan, rule);
                    if (s.applied()) {
                        pendingRules.add(rule.path("id").asText());
                        pendingProposals.add(s.proposalId());
                        pendingChanges.addAll(s.changeIds());
                    } else if (s.status() != null) {
                        return needsHuman(plan, rounds, proposalIds, changeIds, evidence, List.of(),
                                "Build-file rule " + rule.path("id").asText() + " was " + s.status() + ": " + s.reason());
                    }
                }
            }
        }
        BuildPort.Intent intent = rounds.size() == 1 ? BuildPort.Intent.TEST_COMPILE : nextIntent(rounds);
        MigrationCapability.ExecutionStatus status = MigrationCapability.ExecutionStatus.ROUND_LIMIT;
        List<String> unmatched = new ArrayList<>();
        String humanReason = null;
        while (rounds.size() <= plan.maxRounds()) {
            int number = rounds.size();
            Path exec = context.sandbox().prepare(String.format("round-%02d", number));
            Path log = exec.resolveSibling(String.format("round-%02d.log", number));
            BuildPort.BuildResult result = context.build().build(exec, intent, log);
            proposalIds.addAll(pendingProposals);
            changeIds.addAll(pendingChanges);
            String ev = context.evidence().record(EvidenceRecord.EvidenceKind.TOOL_RESULT, String.format("Migration round %02d (%s): %s",
                            number, intent.name().toLowerCase(Locale.ROOT).replace('_', '-'), result.outcome().legacyId()),
                    result.errorsByCategory().toString(), "exec/" + exec.getFileName(), pendingChanges,
                    "reference workflow round; the build tool is authoritative", EvidenceRecord.Reliability.VERIFIED, null,
                    "exec/" + log.getFileName(), null, "migration.rounds").evidenceId();
            evidence.add(ev);
            MigrationCapability.RoundRecord round = new MigrationCapability.RoundRecord(number,
                    pendingRules.isEmpty() ? "No change (re-run)" : "After " + String.join(", ", pendingRules), legacy(intent),
                    result.outcome().legacyId(), result.exitCode(), result.durationMs(), result.jdk(), result.errorsByCategory(),
                    result.errors().size(), result.errors(), result.errors().size() >= 200, result.tests(), pendingRules,
                    pendingProposals, pendingChanges, diagnose(result, pack), "exec/" + log.getFileName(), List.of(ev));
            rounds.add(round);
            pendingRules = new ArrayList<>();
            pendingProposals = new ArrayList<>();
            pendingChanges = new ArrayList<>();
            if (result.outcome() == BuildPort.Outcome.TOOL_UNAVAILABLE) {
                status = MigrationCapability.ExecutionStatus.TOOL_UNAVAILABLE;
                humanReason = result.unavailableReason();
                break;
            }
            if (result.passed()) {
                if (intent == BuildPort.Intent.TEST_COMPILE) {
                    intent = BuildPort.Intent.PACKAGE;
                    continue;
                }
                status = MigrationCapability.ExecutionStatus.GREEN;
                break;
            }
            // tests-failed with no compile errors and no failure beyond round 0's: the same pre-existing failures.
            if (result.outcome() == BuildPort.Outcome.TESTS_FAILED && intent == BuildPort.Intent.PACKAGE
                    && newFailures(round0, result).isEmpty() && testSymptomRules(pack, result).isEmpty()) {
                intent = BuildPort.Intent.PACKAGE_SKIP_TESTS;
                pendingRules.add("(none: the remaining test failures are round 0's pre-existing ones; runnable jar next)");
                continue;
            }
            // map failures to pack rules
            Map<JsonNode, Set<String>> matched = new LinkedHashMap<>();
            unmatched.clear();
            for (BuildPort.BuildError error : result.errors()) {
                Optional<JsonNode> rule = ruleFor(pack, error);
                if (rule.isPresent()) {
                    matched.computeIfAbsent(rule.get(), k -> new LinkedHashSet<>()).add(error.file() == null ? "" : error.file());
                } else if (!isPreExisting(error, round0)) {
                    unmatched.add(format(error));
                }
            }
            if (matched.isEmpty()) {
                status = MigrationCapability.ExecutionStatus.NEEDS_HUMAN;
                humanReason = "No reference-pack rule matches the remaining failure(s): " + unmatched.stream().limit(5).toList()
                        + " — resolve it from the actual dependency (never guess an import path), then submit the change as a "
                        + "patch for approval";
                break;
            }
            List<String> humanNotes = new ArrayList<>();
            for (Map.Entry<JsonNode, Set<String>> entry : matched.entrySet()) {
                JsonNode rule = entry.getKey();
                for (Change change : symptomChanges(context, rule, entry.getValue(), plan, humanNotes)) {
                    Submission s = submit(context, sink, change, plan, rule);
                    if (s.applied()) {
                        if (!pendingRules.contains(rule.path("id").asText())) {
                            pendingRules.add(rule.path("id").asText());
                        }
                        pendingProposals.add(s.proposalId());
                        pendingChanges.addAll(s.changeIds());
                    } else if (s.status() != null) {
                        humanNotes.add(rule.path("id").asText() + " proposal " + s.status() + ": " + s.reason());
                    }
                }
            }
            if (pendingChanges.isEmpty()) {
                status = MigrationCapability.ExecutionStatus.NEEDS_HUMAN;
                humanReason = "Matched rule(s) " + matched.keySet().stream().map(r -> r.path("id").asText()).toList()
                        + " produced no applicable change" + (humanNotes.isEmpty() ? "" : ": " + humanNotes);
                break;
            }
            if (!humanNotes.isEmpty()) {
                evidence.add(context.evidence().record(EvidenceRecord.EvidenceKind.UNKNOWN, "Part of round " + number
                                + "'s failures need a human", String.join("; ", humanNotes), null, List.of(),
                        "the pack rule does not cover this code shape mechanically", EvidenceRecord.Reliability.UNKNOWN, null,
                        null, null, "migration.rounds").evidenceId());
            }
            intent = BuildPort.Intent.TEST_COMPILE;
        }
        MigrationCapability.RuntimeComparison behaviour = null;
        MigrationCapability.TestComparison tests = testComparison(rounds);
        if (status == MigrationCapability.ExecutionStatus.GREEN) {
            behaviour = finalBehaviour(context, pack, rounds, evidence);
        }
        MigrationCapability.MigrationExecution execution = new MigrationCapability.MigrationExecution(plan.planId(), status,
                rounds, proposalIds, changeIds, unmatched, humanReason, behaviour, tests, null, null, evidence);
        String report = MigrationReportRenderer.render(context, plan, execution, pack);
        String slug = plan.referencePackId();
        Path reportPath = context.artifacts().writeText("reports/migration", "migration_" + slug + ".md", report);
        return new MigrationCapability.MigrationExecution(plan.planId(), status, rounds, proposalIds, changeIds, unmatched,
                humanReason, behaviour, tests, reportPath.toString(), null, evidence);
    }

    private MigrationCapability.MigrationExecution needsHuman(MigrationCapability.MigrationPlan plan,
                                                             List<MigrationCapability.RoundRecord> rounds, List<String> proposalIds,
                                                             List<String> changeIds, List<String> evidence, List<String> unmatched,
                                                             String reason) {
        return new MigrationCapability.MigrationExecution(plan.planId(), MigrationCapability.ExecutionStatus.NEEDS_HUMAN, rounds,
                proposalIds, changeIds, unmatched, reason, null, null, null, null, evidence);
    }

    private MigrationCapability.RoundRecord roundZero(CapabilityContext context, BuildPort.BuildResult round0,
                                                      MigrationCapability.MigrationPlan plan, List<String> evidence) {
        String running = String.valueOf(Runtime.version().feature());
        if (plan.fromJava() != null && !plan.fromJava().equals(running)) {
            evidence.add(context.evidence().record(EvidenceRecord.EvidenceKind.UNKNOWN, "Round 0 ran on JDK " + running
                            + ", not the project's declared Java " + plan.fromJava(), "JDK " + plan.fromJava() + " is not installed; "
                            + "the build compiled at the declared language level", null, List.of(),
                    "reference workflow: a missing from-JDK is a warning, not an error", EvidenceRecord.Reliability.UNKNOWN,
                    null, null, null, "migration.rounds").evidenceId());
        }
        return new MigrationCapability.RoundRecord(0, BASELINE_LABEL, legacy(round0.intent()), round0.outcome().legacyId(),
                round0.exitCode(), round0.durationMs(), round0.jdk(), round0.errorsByCategory(), round0.errors().size(),
                round0.errors(), false, round0.tests(), List.of(), List.of(), List.of(),
                round0.passed() ? "Green before any change." : "Pre-existing failures recorded, not fixed: " + round0.failingTests(),
                "baseline/baseline-build.json", List.of());
    }

    // ------------------------------------------------------------------ rules -> changes

    private record Change(String path, String newContent, String description, Map<String, String> symbolRenames) {
    }

    private List<Change> buildFileChanges(CapabilityContext context, JsonNode rule, MigrationCapability.MigrationPlan plan) {
        List<Change> changes = new ArrayList<>();
        String kind = rule.path("kind").asText();
        for (IdentityView.FileInfo file : context.identity().activeFiles()) {
            String path = file.path();
            boolean pom = path.equals("pom.xml") || path.endsWith("/pom.xml");
            Optional<String> text = context.readWorkspaceFile(path);
            if (text.isEmpty()) {
                continue;
            }
            Optional<String> updated = switch (kind) {
                case "PARENT_VERSION" -> pom ? PomEditor.setParentVersion(text.get(), rule.path("group").asText(),
                        rule.path("artifact").asText(), plan.toVersion()) : Optional.empty();
                case "JAVA_LEVEL" -> pom && plan.toJava() != null ? PomEditor.setJavaLevel(text.get(), plan.toJava()) : Optional.empty();
                case "RENAME_DEPENDENCY" -> pom ? PomEditor.renameDependency(text.get(), rule.path("from").asText(),
                        rule.path("to").asText()) : Optional.empty();
                case "BUMP_EXPLICIT_VERSION" -> {
                    if (!pom) {
                        yield Optional.empty();
                    }
                    Optional<String> version = PomEditor.explicitVersion(text.get(), rule.path("ga").asText());
                    boolean below = version.filter(v -> !v.startsWith("${")).map(v -> major(v) < rule.path("below_major").asInt())
                            .orElse(false);
                    yield below ? PomEditor.setDependencyVersion(text.get(), rule.path("ga").asText(), rule.path("to").asText())
                            : Optional.empty();
                }
                case "DOCKER_BASE" -> path.endsWith("Dockerfile") && text.get().contains(rule.path("pattern").asText())
                        ? Optional.of(text.get().replace(rule.path("pattern").asText(), rule.path("replacement").asText()))
                        : Optional.empty();
                default -> Optional.empty();
            };
            updated.ifPresent(u -> changes.add(new Change(path, u, rule.path("description").asText(), Map.of())));
        }
        return changes;
    }

    private List<Change> symptomChanges(CapabilityContext context, JsonNode rule, Set<String> errorFiles,
                                        MigrationCapability.MigrationPlan plan, List<String> humanNotes) {
        List<Change> changes = new ArrayList<>();
        String kind = rule.path("kind").asText();
        switch (kind) {
            case "REPOINT_IMPORTS" -> {
                for (String file : errorFiles) {
                    if (!file.endsWith(".java")) {
                        continue;
                    }
                    context.readWorkspaceFile(file).flatMap(t -> JavaMigrationTransformer.repointImports(t,
                                    map(rule.path("imports")), map(rule.path("annotations")), map(rule.path("types"))))
                            .ifPresent(r -> changes.add(new Change(file, r.content(), String.join("; ", r.changes()), Map.of())));
                }
                if (rule.has("add_dependency")) {
                    for (String pom : pomsFor(context, errorFiles)) {
                        context.readWorkspaceFile(pom).flatMap(t -> PomEditor.addDependency(t,
                                rule.path("add_dependency").path("ga").asText(), rule.path("add_dependency").path("scope").asText(null)))
                                .ifPresent(u -> changes.add(new Change(pom, u, "add " + rule.path("add_dependency").path("ga").asText()
                                        + " (" + rule.path("section").asText() + ")", Map.of())));
                    }
                }
            }
            case "JACKSON3" -> {
                for (String file : errorFiles) {
                    if (!file.endsWith(".java")) {
                        continue;
                    }
                    Optional<String> text = context.readWorkspaceFile(file);
                    String fqn = context.identity().fileByPath(file).flatMap(f -> context.identity().registry()
                            .activeUnitsInFile(f.fileId()).stream().findFirst()).map(u -> u.fqn).orElse(file);
                    Optional<JavaMigrationTransformer.Result> result = text.flatMap(t -> JavaMigrationTransformer.jackson3(t, fqn));
                    if (result.isPresent() && result.get().unmatchedReason() != null) {
                        humanNotes.add(file + ": " + result.get().unmatchedReason());
                    } else {
                        result.ifPresent(r -> changes.add(new Change(file, r.content(), String.join("; ", r.changes()),
                                r.symbolRenames())));
                    }
                }
            }
            case "REPLACE_DEPENDENCY" -> {
                for (IdentityView.FileInfo pom : context.identity().activeFiles()) {
                    if (!pom.path().endsWith("pom.xml")) {
                        continue;
                    }
                    context.readWorkspaceFile(pom.path()).flatMap(t -> PomEditor.replaceDependency(t, rule.path("from").asText(),
                                    rule.path("to").asText(), rule.path("scope").asText(null)))
                            .ifPresent(u -> changes.add(new Change(pom.path(), u, rule.path("description").asText(), Map.of())));
                }
            }
            default -> humanNotes.add("rule kind " + kind + " has no transformer");
        }
        return changes;
    }

    private List<String> pomsFor(CapabilityContext context, Set<String> files) {
        Set<String> poms = new LinkedHashSet<>();
        BuildModelView model = context.buildModel();
        if (model == null) {
            return List.of();
        }
        for (String file : files) {
            BuildModelView.ModuleBuild best = null;
            for (BuildModelView.ModuleBuild m : model.modules()) {
                if (".".equals(m.path()) || file.startsWith(m.path() + "/")) {
                    if (best == null || m.path().length() > best.path().length() && !".".equals(m.path())) {
                        best = m;
                    }
                }
            }
            if (best != null) {
                poms.add(best.buildFile());
            }
        }
        return new ArrayList<>(poms);
    }

    private record Submission(boolean applied, String proposalId, List<String> changeIds, ProposalSink.Status status,
                              String reason) {
    }

    private Submission submit(CapabilityContext context, ProposalSink sink, Change change, MigrationCapability.MigrationPlan plan,
                              JsonNode rule) {
        IdentityView.FileInfo file = context.identity().fileByPath(change.path()).orElse(null);
        if (file == null) {
            return new Submission(false, null, List.of(), ProposalSink.Status.REJECTED, "no FILE_ID for " + change.path());
        }
        String before = context.readWorkspaceFile(change.path()).orElse("");
        List<String> symbols = new ArrayList<>();
        Map<String, String> renames = change.symbolRenames();
        if (!renames.isEmpty()) {
            context.identity().registry().activeSymbolsInFile(file.fileId()).stream()
                    .filter(s -> renames.keySet().stream().anyMatch(k -> k.endsWith("#" + s.signature)))
                    .forEach(s -> symbols.add(s.symbolId));
            context.identity().registry().activeUnitsInFile(file.fileId()).forEach(u -> symbols.add(u.programUnitId));
        } else if (change.path().endsWith(".java")) {
            // import / annotation / type repointing touches declarations inside the file's types
            context.identity().registry().activeUnitsInFile(file.fileId()).forEach(u -> symbols.add(u.programUnitId));
        }
        ChangeProposal proposal = new ChangeProposal(HarnessIds.allocate(HarnessIds.Kind.PROPOSAL), context.runId(),
                ChangeProposal.Capability.MIGRATION, "reference-pack:" + plan.referencePackId(),
                ChangeProposal.ProviderType.DETERMINISTIC_RULE, plan.referencePackSha256() == null ? "1"
                : plan.referencePackSha256().substring(0, 12), change.description(), List.of(), List.of(plan.planId()),
                plan.evidenceRefs().stream().limit(5).toList(), List.of(plan.referencePackId() + " " + rule.path("section").asText()),
                List.of(file.fileId()), symbols, List.of(),
                List.of(new ChangeProposal.FileEdit(file.fileId(), change.path(), null, "MODIFY", change.newContent(),
                        UnifiedDiff.of(change.path(), change.path(), before, change.newContent(), 3), null, List.of())),
                "Reference-pack rule " + rule.path("id").asText() + " applied", ChangeProposal.Risk.LOW,
                Map.of(file.fileId(), Hashing.sha256(before)), context.baselineSeal(), Instant.now().toString(),
                new ChangeProposal.Provenance("reference-pack-engine", rule.path("id").asText(), rule.path("section").asText(),
                        null, null, null, null, null, List.of("pack sha " + plan.referencePackSha256())), false,
                new ProviderHints(Map.of(), renames, Map.of()), null);
        ProposalSink.Result result = sink.submit(List.of(proposal)).get(0);
        return new Submission(result.status() == ProposalSink.Status.APPLIED, proposal.proposalId(), result.changeIds(),
                result.status() == ProposalSink.Status.APPLIED ? null : result.status(), result.reason());
    }

    private Optional<JsonNode> ruleFor(ReferencePack pack, BuildPort.BuildError error) {
        String message = (error.message() + " " + error.raw()).toLowerCase(Locale.ROOT);
        for (JsonNode rule : pack.symptomRules()) {
            for (JsonNode symptom : rule.path("symptoms")) {
                if (message.contains(symptom.asText().toLowerCase(Locale.ROOT))) {
                    return Optional.of(rule);
                }
            }
            for (JsonNode symptom : rule.path("test_symptoms")) {
                if (message.contains(symptom.asText().toLowerCase(Locale.ROOT))) {
                    return Optional.of(rule);
                }
            }
        }
        return Optional.empty();
    }

    private List<JsonNode> testSymptomRules(ReferencePack pack, BuildPort.BuildResult result) {
        List<JsonNode> rules = new ArrayList<>();
        for (BuildPort.BuildError e : result.errors()) {
            ruleFor(pack, e).filter(r -> r.has("test_symptoms")).ifPresent(rules::add);
        }
        return rules;
    }

    private static boolean isPreExisting(BuildPort.BuildError error, BuildPort.BuildResult round0) {
        return round0.errors().stream().anyMatch(e -> e.message().equals(error.message()));
    }

    private static List<String> newFailures(BuildPort.BuildResult round0, BuildPort.BuildResult current) {
        return current.failingTests().stream().filter(t -> !round0.failingTests().contains(t)).toList();
    }

    private static String diagnose(BuildPort.BuildResult result, ReferencePack pack) {
        if (result.passed()) {
            return "Green.";
        }
        Map<String, Integer> byCategory = result.errorsByCategory();
        return result.outcome().legacyId() + ": " + byCategory + (result.tests() == null ? "" : "; tests " + result.tests());
    }

    private static BuildPort.Intent nextIntent(List<MigrationCapability.RoundRecord> rounds) {
        MigrationCapability.RoundRecord last = rounds.get(rounds.size() - 1);
        return "test-compile".equals(last.intent()) && "passed".equals(last.outcome()) ? BuildPort.Intent.PACKAGE
                : BuildPort.Intent.TEST_COMPILE;
    }

    private static String legacy(BuildPort.Intent intent) {
        return intent.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String format(BuildPort.BuildError e) {
        return (e.file() == null ? "" : e.file() + (e.line() == null ? "" : ":" + e.line()) + " ") + e.message();
    }

    private static int major(String version) {
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Map<String, String> map(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return map;
    }

    // ------------------------------------------------------------------ behaviour

    private MigrationCapability.RuntimeComparison finalBehaviour(CapabilityContext context, ReferencePack pack,
                                                                 List<MigrationCapability.RoundRecord> rounds, List<String> evidence) {
        Path spec = context.artifacts().path("baseline", "probes.json");
        Path baseline = context.artifacts().path("baseline", "runtime-baseline.json");
        if (!Files.isRegularFile(spec) || !Files.isRegularFile(baseline)) {
            return new MigrationCapability.RuntimeComparison("⚠️ Not probed", "not-compared", List.of(), false, false, null, null,
                    List.of("No behaviour probes were defined for this run; behaviour is not compared (the migration cannot be "
                            + "called behaviour-neutral)"));
        }
        RuntimePort.ProbeSpec probes = KernelJson.read(spec, RuntimePort.ProbeSpec.class);
        RuntimePort.RuntimeRun before = KernelJson.read(baseline, RuntimePort.RuntimeRun.class);
        MigrationCapability.RoundRecord last = rounds.get(rounds.size() - 1);
        Path exec = context.sandbox().prepare(String.format("round-%02d", last.round()));
        RuntimePort.RuntimeRun after = context.runtime().run(exec, probes, "final",
                exec.resolveSibling("runtime-migration-final.log"));
        Path finalRef = context.artifacts().writeJson("plans/migration-runtime", "final.json", after);
        ProbeComparator.Comparison comparison = ProbeComparator.compare(before, after);
        List<MigrationCapability.ProbeDiff> diffs = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        for (ProbeComparator.Row row : comparison.rows()) {
            String note = null;
            if (!row.same()) {
                note = explain(pack, row, probes);
                if (note != null) {
                    explanations.add(row.name() + ": " + note);
                }
            }
            diffs.add(new MigrationCapability.ProbeDiff(row.name(), row.verdict(), parse(row.beforeStatus()), parse(row.afterStatus()),
                    row.beforeHash(), row.afterHash(), note));
        }
        evidence.add(context.evidence().record(EvidenceRecord.EvidenceKind.TOOL_RESULT, "Final runtime probes: "
                        + comparison.verdictText(), String.join("; ", explanations), "exec/" + exec.getFileName(), List.of(),
                "probe comparison against round 0 (reference compareProbes)", EvidenceRecord.Reliability.VERIFIED, null,
                finalRef.toString(), null, "migration.probes").evidenceId());
        String verdict = !comparison.compared() ? "not-compared" : comparison.matched() == comparison.total() ? "unchanged" : "changed";
        return new MigrationCapability.RuntimeComparison(comparison.verdictText(), verdict, diffs, before.started(), after.started(),
                baseline.toString(), finalRef.toString(), explanations);
    }

    /** §13 expected differences: explained only for body changes on the paths or statuses the pack names. */
    private static String explain(ReferencePack pack, ProbeComparator.Row row, RuntimePort.ProbeSpec spec) {
        if (!"body-differs".equals(row.verdict())) {
            return null;
        }
        String path = spec.requests().stream().filter(r -> r.name().equals(row.name())).map(RuntimePort.ProbeRequest::path)
                .findFirst().orElse("");
        Integer status = parse(row.beforeStatus());
        for (JsonNode d : pack.rules().path("expected_runtime_differences")) {
            if (d.has("path_prefix") && path.startsWith(d.path("path_prefix").asText())) {
                return d.path("explanation").asText();
            }
            if (d.has("status_min") && status != null && status >= d.path("status_min").asInt()) {
                return d.path("explanation").asText();
            }
        }
        return null;
    }

    private static Integer parse(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static MigrationCapability.TestComparison testComparison(List<MigrationCapability.RoundRecord> rounds) {
        Set<String> testIntents = Set.of("test", "package", "verify");
        MigrationCapability.RoundRecord before = rounds.stream().filter(r -> r.round() == 0 && r.tests() != null).findFirst()
                .orElse(null);
        MigrationCapability.RoundRecord after = null;
        for (int i = rounds.size() - 1; i >= 1; i--) {
            if (testIntents.contains(rounds.get(i).intent()) && rounds.get(i).tests() != null) {
                after = rounds.get(i);
                break;
            }
        }
        ProbeComparator.TestVerdict verdict = ProbeComparator.compareTests(before == null ? null : before.tests(),
                after == null ? null : after.tests());
        return new MigrationCapability.TestComparison(verdict.verdict(), verdict.before(), verdict.after(), List.of(), List.of());
    }

    // ================================================================== validate

    public MigrationCapability.CapabilityValidation validate(CapabilityContext context, MigrationCapability.MigrationPlan plan,
                                                             MigrationCapability.MigrationExecution execution) {
        List<ValidationResult.DimensionResult> dims = new ArrayList<>();
        boolean green = execution.status() == MigrationCapability.ExecutionStatus.GREEN;
        MigrationCapability.RoundRecord last = execution.rounds().isEmpty() ? null : execution.rounds().get(execution.rounds().size() - 1);
        dims.add(dim(ValidationDimension.COMPILE, green ? DimensionStatus.PASS : DimensionStatus.FAIL,
                last == null ? null : last.outcome(), "Migration rounds ended " + execution.status() + " after "
                        + execution.rounds().size() + " round(s)", execution.evidenceRefs()));
        boolean skipTests = last != null && "package-skip-tests".equals(last.intent());
        dims.add(dim(ValidationDimension.BUILD_PACKAGE, green ? (skipTests ? DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES
                        : DimensionStatus.PASS) : DimensionStatus.FAIL, last == null ? null : last.outcome(),
                green ? (skipTests ? "Packaged with tests skipped: remaining test failures are round 0's pre-existing ones"
                        : "Packaged green") : "No green package round", execution.evidenceRefs()));
        MigrationCapability.TestComparison tests = execution.tests();
        if (tests == null || tests.before() == null || tests.after() == null) {
            dims.add(dim(ValidationDimension.TESTS, DimensionStatus.NOT_COMPARED, null,
                    tests == null ? "no test comparison" : tests.verdict(), List.of()));
        } else {
            int badBefore = tests.before().failures() + tests.before().errors();
            int badAfter = tests.after().failures() + tests.after().errors();
            dims.add(dim(ValidationDimension.TESTS, badAfter > badBefore ? DimensionStatus.FAIL : badAfter > 0
                    ? DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES : DimensionStatus.PASS, tests.verdict(), tests.verdict(), List.of()));
        }
        MigrationCapability.RuntimeComparison behaviour = execution.behaviour();
        if (behaviour == null || "not-compared".equals(behaviour.verdict())) {
            dims.add(dim(ValidationDimension.BEHAVIOR_PROBES, DimensionStatus.NOT_COMPARED, "not-compared",
                    behaviour == null ? "migration did not reach a green round" : behaviour.overall(), List.of()));
        } else {
            long unexplained = behaviour.probes().stream().filter(p -> !"identical".equals(p.verdict()) && p.note() == null).count();
            dims.add(dim(ValidationDimension.RUNTIME_STARTUP, behaviour.finalStarted() ? DimensionStatus.PASS : DimensionStatus.FAIL,
                    behaviour.finalStarted() ? "STARTED" : "DID_NOT_START", behaviour.overall(), List.of()));
            dims.add(dim(ValidationDimension.BEHAVIOR_PROBES, unexplained > 0 ? DimensionStatus.FAIL : "unchanged".equals(
                    behaviour.verdict()) ? DimensionStatus.PASS : DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES,
                    unexplained > 0 ? "CHANGED_UNEXPLAINED" : "unchanged".equals(behaviour.verdict()) ? "EQUIVALENT"
                            : "EQUIVALENT_WITH_EXPLAINED_DIFFS", behaviour.overall() + "; " + behaviour.explanations(), List.of()));
        }
        return new MigrationCapability.CapabilityValidation(dims, List.of());
    }

    private static ValidationResult.DimensionResult dim(ValidationDimension d, DimensionStatus s, String detail, String summary,
                                                        List<String> evidence) {
        return new ValidationResult.DimensionResult(d, s, detail, summary, evidence, "migration:reference-pack");
    }

    static Optional<BuildPort.BuildResult> baselineBuild(CapabilityContext context) {
        Path file = context.artifacts().path("baseline", "baseline-build.json");
        return Files.isRegularFile(file) ? Optional.of(KernelJson.read(file, BuildPort.BuildResult.class)) : Optional.empty();
    }
}
