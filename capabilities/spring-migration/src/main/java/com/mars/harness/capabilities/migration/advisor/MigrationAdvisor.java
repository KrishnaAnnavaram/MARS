package com.mars.harness.capabilities.migration.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.capabilities.migration.pack.ReferencePack;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.ports.build.BuildModelView;
import com.mars.harness.kernel.ports.capability.CapabilityContext;
import com.mars.harness.kernel.ports.identity.IdentityView;
import com.mars.harness.kernel.ports.lifecycle.LifecyclePort;
import com.mars.harness.kernel.ports.migration.MigrationCapability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The read-only migration advisor (spec §11).
 *
 * <p>It answers the grounding questions for every conclusion: what was observed, where, which
 * rule, lifecycle fact or reference supports it, how fresh and reliable that evidence is, and
 * what is unknown. It never authorizes anything. RED names the objective and the reason, and
 * UNKNOWN is never coerced into GREEN.
 */
public final class MigrationAdvisor {

    public record Detected(String framework, String version, String line, String javaVersion, BuildModelView.ModuleBuild module,
                           String evidenceRef) {
    }

    public MigrationAssessment assess(CapabilityContext context, List<MigrationCapability.Objective> objectives) {
        UnifiedPolicy policy = context.policy();
        List<String> evidence = new ArrayList<>();
        List<MigrationAssessment.Unknown> unknowns = new ArrayList<>();
        List<MigrationAssessment.Blocker> blockers = new ArrayList<>();
        List<String> confidenceBasis = new ArrayList<>();
        BuildModelView model = context.buildModel();

        Optional<Detected> detected = detect(context, model);
        detected.ifPresent(d -> evidence.add(d.evidenceRef()));
        if (detected.isEmpty()) {
            String ev = context.evidence().record(EvidenceRecord.EvidenceKind.UNKNOWN, "No Spring Boot platform version could be "
                            + "observed in the build model", null, null, List.of(), "parent, BOM and dependency declarations",
                    EvidenceRecord.Reliability.UNKNOWN, null, null, null, "migration.advisor").evidenceId();
            unknowns.add(new MigrationAssessment.Unknown("platform", "Which Spring Boot version does the project use?",
                    "migration need cannot be classified", List.of(ev)));
            evidence.add(ev);
        }

        // lifecycle of the current line (Bootshift LifecycleSource)
        Optional<LifecyclePort.LineFacts> current = detected.flatMap(d -> context.lifecycle().line("spring-boot", d.line(),
                context.today()));
        String lifecycleRef = null;
        if (current.isPresent()) {
            LifecyclePort.LineFacts f = current.get();
            lifecycleRef = context.evidence().record(EvidenceRecord.EvidenceKind.LIFECYCLE_FACT, "Spring Boot " + f.line()
                            + " open-source support " + (f.supportEnds() == null ? "end unknown" : (f.endOfLife(context.today())
                            ? "ended " : "ends ") + f.supportEnds()), "quality " + f.quality() + ", Java " + f.supportedJavaMajors(),
                    "Bootshift LifecycleSource", List.of(), f.source(), reliability(f.quality()), f.asOf(), null, null,
                    "migration.advisor").evidenceId();
            evidence.add(lifecycleRef);
            if (f.tableStale()) {
                unknowns.add(new MigrationAssessment.Unknown("lifecycle", "The curated lifecycle table is past its as-of date "
                        + f.asOf(), "lifecycle facts are advisory, not verified", List.of(lifecycleRef)));
            }
        } else if (detected.isPresent()) {
            String ev = context.evidence().record(EvidenceRecord.EvidenceKind.UNKNOWN, "No lifecycle evidence for Spring Boot "
                    + detected.get().line(), null, null, List.of(), "Bootshift LifecycleSource", EvidenceRecord.Reliability.UNKNOWN,
                    null, null, null, "migration.advisor").evidenceId();
            unknowns.add(new MigrationAssessment.Unknown("lifecycle", "Is Spring Boot " + detected.get().line() + " supported?",
                    "urgency cannot be established", List.of(ev)));
            evidence.add(ev);
        }

        // reference pack (the migration knowledge)
        List<ReferencePack> packs = ReferencePack.loadAll(context.harnessRoot());
        Optional<ReferencePack> pack = detected.flatMap(d -> packs.stream()
                .filter(p -> d.version().startsWith(p.frontMatter().from() + "."))
                .filter(p -> p.matchedOn(model).isPresent()).findFirst());
        String packRef = null;
        if (pack.isPresent()) {
            ReferencePack p = pack.get();
            packRef = context.evidence().record(EvidenceRecord.EvidenceKind.REFERENCE_RULE, "Reference pack " + p.frontMatter().id()
                            + " matches (" + p.matchedOn(model).orElse("") + ")", p.frontMatter().title(), p.relativePackPath(),
                    List.of(), "detect: " + p.frontMatter().detect(), p.rulesCurrent() ? EvidenceRecord.Reliability.VERIFIED
                            : EvidenceRecord.Reliability.UNKNOWN, null, p.relativePackPath(), p.packSha256(), "migration.advisor")
                    .evidenceId();
            evidence.add(packRef);
            if (!p.rulesCurrent()) {
                blockers.add(new MigrationAssessment.Blocker("BLK-PACK-DRIFT", "The reference pack text changed after its "
                        + "machine rules were derived; rules must be re-derived before migration can execute", "KNOWLEDGE",
                        List.of(packRef)));
            }
        } else if (detected.isPresent()) {
            String ev = context.evidence().record(EvidenceRecord.EvidenceKind.UNKNOWN, "No reference pack matches Spring Boot "
                            + detected.get().version(), null, null, List.of(), "available packs: "
                            + packs.stream().map(p -> p.frontMatter().id()).toList(), EvidenceRecord.Reliability.UNKNOWN, null, null,
                    null, "migration.advisor").evidenceId();
            blockers.add(new MigrationAssessment.Blocker("BLK-NO-PACK", "No matching reference pack: migration cannot be "
                    + "executed (a stop condition, not a licence to improvise)", "KNOWLEDGE", List.of(ev)));
            evidence.add(ev);
        }

        // recommended target
        MigrationAssessment.RecommendedTarget target = target(context, pack, detected, evidence);

        // observed issues
        List<MigrationAssessment.MigrationIssue> issues = pack.map(p -> scanIssues(context, p, detected.orElse(null)))
                .orElse(List.of());

        // objectives
        List<MigrationAssessment.ObjectiveImpact> impacts = new ArrayList<>();
        for (MigrationCapability.Objective objective : objectives) {
            Finding.PlatformRequirement req = objective.requirement();
            boolean requires = req != null && "spring-boot".equals(req.requiresPlatform()) && detected.isPresent()
                    && compare(detected.get().version(), req.requiresPlatformMinimum()) < 0;
            String why = requires ? "Remediating " + objective.description() + " needs " + req.component() + " >= "
                    + req.minimumFixedVersion() + ", which requires Spring Boot " + req.requiresPlatformMinimum() + "+ ("
                    + req.basis() + "); the project is on " + detected.get().version() : null;
            List<String> refs = new ArrayList<>(req == null ? List.of() : req.evidenceRefs());
            detected.ifPresent(d -> refs.add(d.evidenceRef()));
            impacts.add(new MigrationAssessment.ObjectiveImpact(objective.objectiveId(), objective.description(), requires,
                    requires ? "spring-boot " + req.requiresPlatformMinimum() + "+" : null, why, refs));
        }
        List<MigrationAssessment.ObjectiveImpact> prerequisites = impacts.stream()
                .filter(MigrationAssessment.ObjectiveImpact::requiresMigration).toList();

        // evidence confidence (not model confidence)
        MigrationAssessment.EvidenceConfidence confidence = confidence(detected, current, pack, model, confidenceBasis, unknowns);

        // traffic light
        MigrationAssessment.TrafficLight light;
        String rationale;
        if (detected.isEmpty() || confidence == MigrationAssessment.EvidenceConfidence.INSUFFICIENT) {
            light = MigrationAssessment.TrafficLight.UNKNOWN;
            rationale = "Insufficient trustworthy evidence to classify migration need: " + confidenceBasis;
        } else if (!prerequisites.isEmpty()) {
            light = MigrationAssessment.TrafficLight.RED;
            rationale = "Migration is a prerequisite for " + prerequisites.size() + " requested objective(s): "
                    + prerequisites.stream().map(MigrationAssessment.ObjectiveImpact::why).toList();
        } else if (current.isEmpty() || "UNKNOWN".equals(current.get().quality())) {
            light = MigrationAssessment.TrafficLight.UNKNOWN;
            rationale = "No lifecycle evidence for Spring Boot " + detected.get().line() + "; urgency cannot be classified";
        } else if (current.get().endOfLife(context.today())) {
            light = MigrationAssessment.TrafficLight.YELLOW;
            rationale = "Spring Boot " + detected.get().line() + " open-source support ended " + current.get().supportEnds() + " ("
                    + current.get().quality() + ", as of " + current.get().asOf() + "); migrating is recommended but no "
                    + "requested objective requires it";
        } else if (current.get().horizonMonths(context.today()) != null
                && current.get().horizonMonths(context.today()) < policy.migration().supportHorizonWarningMonths()) {
            light = MigrationAssessment.TrafficLight.YELLOW;
            rationale = "Spring Boot " + detected.get().line() + " support ends " + current.get().supportEnds() + " (in "
                    + current.get().horizonMonths(context.today()) + " months, under the policy horizon of "
                    + policy.migration().supportHorizonWarningMonths() + "); recommended, not required";
        } else {
            light = MigrationAssessment.TrafficLight.GREEN;
            rationale = "Spring Boot " + detected.get().line() + " is supported until " + current.get().supportEnds() + " ("
                    + current.get().quality() + ") and no requested objective requires a newer platform";
        }
        MigrationAssessment.MigrationNeed need = switch (light) {
            case RED -> MigrationAssessment.MigrationNeed.PREREQUISITE;
            case YELLOW -> MigrationAssessment.MigrationNeed.RECOMMENDED;
            case GREEN -> MigrationAssessment.MigrationNeed.NOT_REQUIRED;
            case UNKNOWN -> MigrationAssessment.MigrationNeed.UNKNOWN;
        };
        MigrationAssessment.Priority priority = priority(light, prerequisites, objectives, current, context);

        EffortModel.Result effort = new EffortModel(policy).compute(context, detected.orElse(null), target, issues, lifecycleRef,
                evidence);
        MigrationAssessment.Complexity complexity = detected.isEmpty() || confidence == MigrationAssessment.EvidenceConfidence.INSUFFICIENT
                ? MigrationAssessment.Complexity.UNKNOWN : EffortModel.complexity(effort.score().migrationEffortScore(),
                policy.migration().complexityThresholds(), blockers);
        unknowns.addAll(effort.unknowns());

        MigrationAssessment.CurrentPlatform platform = new MigrationAssessment.CurrentPlatform("spring-boot",
                detected.map(Detected::version).orElse(null), detected.map(Detected::line).orElse(null),
                detected.map(Detected::javaVersion).orElse(null), model == null ? "UNKNOWN" : model.buildSystem(),
                model != null && model.authoritative(), current.map(LifecyclePort.LineFacts::quality).orElse("UNKNOWN"),
                current.map(f -> String.valueOf(f.supportEnds())).orElse(null), current.map(f -> f.endOfLife(context.today()))
                .orElse(null), current.map(f -> f.horizonMonths(context.today())).orElse(null),
                detected.map(d -> List.of(d.evidenceRef())).orElse(List.of()));
        return new MigrationAssessment(HarnessIds.allocate(HarnessIds.Kind.ASSESSMENT), context.runId(), policy.policyVersion(),
                Instant.now().toString(), light, rationale, need, priority, complexity, effort.score(), confidence, confidenceBasis,
                platform, target, prerequisites.isEmpty() ? "NO_SEQUENCE_REQUIRED" : "MIGRATE_FIRST", impacts, issues,
                dedupe(evidence), unknowns, blockers, pack.map(p -> p.frontMatter().id()).orElse(null),
                pack.map(ReferencePack::packSha256).orElse(null));
    }

    // ------------------------------------------------------------------ detection

    public static Optional<Detected> detect(CapabilityContext context, BuildModelView model) {
        if (model == null) {
            return Optional.empty();
        }
        for (BuildModelView.ModuleBuild module : model.modules()) {
            String version = null;
            String how = null;
            int line = 0;
            if (module.parent() != null && "org.springframework.boot".equals(module.parent().groupId())
                    && module.parent().version() != null) {
                version = module.parent().version();
                how = "parent " + module.parent().ga();
                line = lineOf(context, module.buildFile(), "<artifactId>" + module.parent().artifactId() + "</artifactId>");
            } else {
                for (BuildModelView.Dependency d : module.dependencies()) {
                    if ("org.springframework.boot".equals(d.groupId()) && d.effectiveVersion() != null
                            && !d.effectiveVersion().contains("${")) {
                        version = d.effectiveVersion();
                        how = "dependency " + d.ga();
                        line = d.line();
                        break;
                    }
                }
            }
            if (version == null) {
                continue;
            }
            String[] parts = version.split("\\.");
            String lineName = parts.length >= 2 ? parts[0] + "." + parts[1] : version;
            String java = module.javaVersion();
            String where = module.buildFile() + ":" + line;
            List<String> subjects = context.identity().fileByPath(module.buildFile()).map(f -> List.of(f.fileId())).orElse(List.of());
            String ev = context.evidence().record(EvidenceRecord.EvidenceKind.OBSERVATION, "Spring Boot " + version + " ("
                            + how + "), Java " + java + " (" + module.javaVersionSource() + ")", version, where, subjects,
                    "declared in the build descriptor" + (model.authoritative() ? " and resolved by the build tool"
                            : "; not confirmed by the build tool"), model.authoritative() ? EvidenceRecord.Reliability.VERIFIED
                            : EvidenceRecord.Reliability.DECLARED, null, module.buildFile(), null, "migration.advisor").evidenceId();
            return Optional.of(new Detected("spring-boot", version, lineName, java, module, ev));
        }
        return Optional.empty();
    }

    private static int lineOf(CapabilityContext context, String path, String needle) {
        Optional<String> text = context.readWorkspaceFile(path);
        if (text.isEmpty()) {
            return 0;
        }
        int index = text.get().indexOf(needle);
        if (index < 0) {
            return 0;
        }
        return (int) text.get().substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    // ------------------------------------------------------------------ target

    private MigrationAssessment.RecommendedTarget target(CapabilityContext context, Optional<ReferencePack> pack,
                                                         Optional<Detected> detected, List<String> evidence) {
        List<LifecyclePort.LineFacts> lines = context.lifecycle().lines("spring-boot", context.today());
        if (pack.isPresent()) {
            ReferencePack p = pack.get();
            String pinned = p.rules().path("pinned_target").asText();
            String line = pinned.substring(0, pinned.lastIndexOf('.'));
            Optional<LifecyclePort.LineFacts> facts = lines.stream().filter(f -> f.line().equals(line)).findFirst();
            String ev = context.evidence().record(EvidenceRecord.EvidenceKind.REFERENCE_RULE, "Target Spring Boot " + pinned
                            + " (reference pack " + p.rules().path("pinned_target_section").asText() + ")",
                    facts.map(f -> "line " + line + " supported until " + f.supportEnds() + " (" + f.quality() + ")")
                            .orElse("no lifecycle facts for line " + line), p.relativePackPath(), List.of(),
                    "reference pack pins an exact target; lifecycle confirms it is a supported landing",
                    facts.map(f -> reliability(f.quality())).orElse(EvidenceRecord.Reliability.UNKNOWN), facts.map(
                            LifecyclePort.LineFacts::asOf).orElse(null), p.relativePackPath(), p.packSha256(), "migration.advisor")
                    .evidenceId();
            evidence.add(ev);
            return new MigrationAssessment.RecommendedTarget("spring-boot", pinned, line, p.frontMatter().languageTo(),
                    "reference pack " + p.frontMatter().id() + " §1.1 pins " + pinned + facts.map(f -> "; lifecycle "
                            + f.quality() + " support until " + f.supportEnds()).orElse("; lifecycle UNKNOWN"), List.of(ev));
        }
        Optional<LifecyclePort.LineFacts> newest = lines.stream()
                .filter(f -> "VERIFIED".equals(f.quality()) && !f.endOfLife(context.today()))
                .reduce((a, b) -> b);
        if (newest.isEmpty() || detected.isEmpty()) {
            return new MigrationAssessment.RecommendedTarget("spring-boot", null, null, null,
                    "no verified supported line and no reference pack", List.of());
        }
        return new MigrationAssessment.RecommendedTarget("spring-boot", newest.get().latestPatch(), newest.get().line(),
                String.valueOf(newest.get().supportedJavaMajors().stream().filter(j -> j >= 17).findFirst().orElse(null)),
                "newest VERIFIED supported line (no reference pack: the target is informational only)", List.of());
    }

    // ------------------------------------------------------------------ issues

    private List<MigrationAssessment.MigrationIssue> scanIssues(CapabilityContext context, ReferencePack pack, Detected detected) {
        List<MigrationAssessment.MigrationIssue> issues = new ArrayList<>();
        int n = 0;
        for (JsonNode rule : pack.symptomRules()) {
            List<String> patterns = new ArrayList<>();
            rule.path("scan").forEach(s -> patterns.add(s.asText()));
            List<String> pomPatterns = new ArrayList<>();
            rule.path("scan_pom").forEach(s -> pomPatterns.add(s.asText()));
            for (IdentityView.FileInfo file : context.identity().activeFiles()) {
                boolean java = file.path().endsWith(".java");
                boolean pom = file.path().endsWith("pom.xml");
                List<String> active = java ? patterns : pom ? pomPatterns : List.of();
                if (active.isEmpty()) {
                    continue;
                }
                String text = context.readWorkspaceFile(file.path()).orElse("");
                String[] lines = text.split("\\r?\\n");
                for (int i = 0; i < lines.length; i++) {
                    for (String pattern : active) {
                        if (lines[i].contains(pattern)) {
                            issues.add(issue(context, ++n, rule, file, i + 1, lines[i].trim()));
                        }
                    }
                }
            }
        }
        for (JsonNode rule : pack.buildFileRules()) {
            for (IdentityView.FileInfo file : context.identity().activeFiles()) {
                String text = context.readWorkspaceFile(file.path()).orElse("");
                String kind = rule.path("kind").asText();
                String subject = null;
                if (file.path().endsWith("pom.xml")) {
                    subject = switch (kind) {
                        case "PARENT_VERSION" -> text.contains("<artifactId>" + rule.path("artifact").asText() + "</artifactId>")
                                && detected != null && detected.version().startsWith(pack.frontMatter().from() + ".")
                                ? "parent " + rule.path("artifact").asText() + " " + detected.version() : null;
                        case "JAVA_LEVEL" -> detected != null && detected.javaVersion() != null
                                && compare(detected.javaVersion(), pack.frontMatter().languageTo()) < 0
                                ? "Java " + detected.javaVersion() + " -> " + pack.frontMatter().languageTo() : null;
                        case "RENAME_DEPENDENCY" -> text.contains("<artifactId>" + rule.path("from").asText().split(":")[1]
                                + "</artifactId>") ? rule.path("from").asText() : null;
                        case "BUMP_EXPLICIT_VERSION" -> {
                            String ga = rule.path("ga").asText();
                            yield text.contains("<artifactId>" + ga.split(":")[1] + "</artifactId>") ? ga + " (explicit version)" : null;
                        }
                        default -> null;
                    };
                } else if ("DOCKER_BASE".equals(kind) && file.path().endsWith("Dockerfile")
                        && text.contains(rule.path("pattern").asText())) {
                    subject = rule.path("pattern").asText();
                }
                if (subject != null) {
                    issues.add(issue(context, ++n, rule, file, lineIndex(text, subject), subject));
                }
            }
        }
        return issues;
    }

    private static int lineIndex(String text, String subject) {
        if (subject.startsWith("Java ")) {
            for (String marker : List.of("<java.version>", "<maven.compiler.release>", "<maven.compiler.source>", "<source>")) {
                int at = text.indexOf(marker);
                if (at >= 0) {
                    return (int) text.substring(0, at).lines().count() + 1;
                }
            }
        }
        String needle = subject.contains(":") ? subject.split(":")[1].split(" ")[0] : subject.split(" ")[0];
        int index = text.indexOf(needle);
        return index < 0 ? 0 : (int) text.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private MigrationAssessment.MigrationIssue issue(CapabilityContext context, int n, JsonNode rule, IdentityView.FileInfo file,
                                                     int line, String subject) {
        var registry = context.identity().registry();
        String symbol = registry.symbolAt(file.fileId(), line).map(s -> s.symbolId).orElse(null);
        String statement = registry.statementAt(file.fileId(), line).map(s -> s.statementId).orElse(null);
        List<String> subjects = new ArrayList<>(List.of(file.fileId()));
        if (symbol != null) {
            subjects.add(symbol);
        }
        if (statement != null) {
            subjects.add(statement);
        }
        String ev = context.evidence().record(EvidenceRecord.EvidenceKind.OBSERVATION, rule.path("id").asText() + " "
                        + rule.path("section").asText() + ": " + subject, rule.path("description").asText(),
                file.path() + ":" + line, subjects, "reference pack " + rule.path("section").asText(),
                EvidenceRecord.Reliability.VERIFIED, null, file.path(), file.sha256(), "migration.advisor").evidenceId();
        return new MigrationAssessment.MigrationIssue(String.format("MIG-%04d", n), rule.path("id").asText(),
                rule.path("section").asText(), rule.path("mandatory").asBoolean(true),
                rule.path("api").asBoolean(false) ? "REMOVED_OR_RELOCATED_API" : "BUILD_OR_DEPENDENCY", subject, file.fileId(),
                symbol, statement, file.path() + ":" + line, List.of(ev));
    }

    // ------------------------------------------------------------------ confidence, priority

    private MigrationAssessment.EvidenceConfidence confidence(Optional<Detected> detected,
                                                              Optional<LifecyclePort.LineFacts> lifecycle,
                                                              Optional<ReferencePack> pack, BuildModelView model,
                                                              List<String> basis, List<MigrationAssessment.Unknown> unknowns) {
        if (detected.isEmpty()) {
            basis.add("platform version not observed");
            return MigrationAssessment.EvidenceConfidence.INSUFFICIENT;
        }
        boolean verifiedLifecycle = lifecycle.map(f -> "VERIFIED".equals(f.quality())).orElse(false);
        boolean authoritative = model != null && model.authoritative();
        boolean packCurrent = pack.map(ReferencePack::rulesCurrent).orElse(false);
        basis.add("framework version observed (" + (authoritative ? "resolved by build tool" : "declared only") + ")");
        basis.add("lifecycle " + lifecycle.map(LifecyclePort.LineFacts::quality).orElse("UNKNOWN"));
        basis.add(pack.isPresent() ? "reference pack " + pack.get().frontMatter().id() + (packCurrent ? " (rules current)"
                : " (rules STALE)") : "no reference pack");
        basis.add("Java level " + (detected.get().javaVersion() == null ? "unknown" : "observed"));
        if (!authoritative) {
            unknowns.add(new MigrationAssessment.Unknown("build-model", "Resolved dependency versions (the build tool did not "
                    + "resolve the model)", "dependency-level facts are declared, not resolved", List.of()));
        }
        if (verifiedLifecycle && packCurrent && authoritative && detected.get().javaVersion() != null) {
            return MigrationAssessment.EvidenceConfidence.HIGH;
        }
        if (verifiedLifecycle && (packCurrent || authoritative)) {
            return MigrationAssessment.EvidenceConfidence.MEDIUM;
        }
        return MigrationAssessment.EvidenceConfidence.LOW;
    }

    private static MigrationAssessment.Priority priority(MigrationAssessment.TrafficLight light,
                                                         List<MigrationAssessment.ObjectiveImpact> prerequisites,
                                                         List<MigrationCapability.Objective> objectives,
                                                         Optional<LifecyclePort.LineFacts> current, CapabilityContext context) {
        return switch (light) {
            case RED -> {
                Set<String> ids = new LinkedHashSet<>();
                prerequisites.forEach(p -> ids.add(p.objectiveId()));
                boolean critical = objectives.stream().filter(o -> ids.contains(o.objectiveId()))
                        .anyMatch(o -> o.severity() == Finding.Severity.CRITICAL);
                boolean high = objectives.stream().filter(o -> ids.contains(o.objectiveId()))
                        .anyMatch(o -> o.severity() == Finding.Severity.HIGH);
                yield critical ? MigrationAssessment.Priority.CRITICAL : high ? MigrationAssessment.Priority.HIGH
                        : MigrationAssessment.Priority.MEDIUM;
            }
            case YELLOW -> current.map(f -> f.endOfLife(context.today())).orElse(false)
                    ? MigrationAssessment.Priority.MEDIUM : MigrationAssessment.Priority.LOW;
            case GREEN -> MigrationAssessment.Priority.NONE;
            case UNKNOWN -> MigrationAssessment.Priority.UNKNOWN;
        };
    }

    static EvidenceRecord.Reliability reliability(String quality) {
        return switch (quality == null ? "UNKNOWN" : quality) {
            case "VERIFIED" -> EvidenceRecord.Reliability.VERIFIED;
            case "ADVISORY" -> EvidenceRecord.Reliability.ADVISORY;
            case "ESTIMATED" -> EvidenceRecord.Reliability.ESTIMATED;
            default -> EvidenceRecord.Reliability.UNKNOWN;
        };
    }

    public static int compare(String a, String b) {
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) {
                return Integer.compare(xi, yi);
            }
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> dedupe(List<String> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }
}
