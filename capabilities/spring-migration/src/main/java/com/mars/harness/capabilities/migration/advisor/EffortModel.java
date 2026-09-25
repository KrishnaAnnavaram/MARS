package com.mars.harness.capabilities.migration.advisor;

import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.migration.MigrationAssessment;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.ports.build.BuildModelView;
import com.mars.harness.kernel.ports.capability.CapabilityContext;
import com.mars.harness.kernel.ports.identity.IdentityView;
import com.mars.harness.kernel.ports.lifecycle.LifecyclePort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic migration effort score (spec §11.5): 0 to 100, <b>not a probability</b>.
 *
 * <pre>
 * contribution_i = round(weight_i * min(raw_i / saturation_i, 1))
 * score          = Σ contribution_i
 * </pre>
 *
 * <p>Weights and saturation points come from the versioned policy. The weights sum to 100, so a
 * score is always explainable factor by factor. A factor the harness cannot measure is
 * {@code known=false}, contributes 0, and is listed as an unknown. It is never estimated.
 */
public final class EffortModel {

    public record Result(MigrationAssessment.EffortScore score, List<MigrationAssessment.Unknown> unknowns) {
    }

    private final UnifiedPolicy policy;

    public EffortModel(UnifiedPolicy policy) {
        this.policy = policy;
    }

    public Result compute(CapabilityContext context, MigrationAdvisor.Detected detected,
                          MigrationAssessment.RecommendedTarget target, List<MigrationAssessment.MigrationIssue> issues,
                          String lifecycleRef, List<String> evidence) {
        Map<String, Double> weights = policy.migration().effortWeights();
        Map<String, Double> saturation = policy.migration().effortSaturation();
        List<MigrationAssessment.EffortFactor> factors = new ArrayList<>();
        List<MigrationAssessment.Unknown> unknowns = new ArrayList<>();
        BuildModelView model = context.buildModel();

        // platform_version_distance: supported lines crossed, from the lifecycle table
        if (detected != null && target != null && target.line() != null) {
            List<LifecyclePort.LineFacts> lines = context.lifecycle().lines("spring-boot", context.today());
            long crossed = lines.stream().filter(l -> MigrationAdvisor.compare(l.line(), detected.line()) > 0
                    && MigrationAdvisor.compare(l.line(), target.line()) <= 0).count();
            factors.add(factor("platform_version_distance", crossed, weights, saturation, true,
                    lifecycleRef == null ? List.of() : List.of(lifecycleRef), detected.line() + " -> " + target.line() + ": "
                            + crossed + " release line(s)"));
        } else {
            factors.add(unknown("platform_version_distance", weights, saturation, "current or target line unknown", unknowns));
        }
        // java_version_jump
        Integer from = detected == null ? null : parse(detected.javaVersion());
        Integer to = target == null ? null : parse(target.javaVersion());
        if (from != null && to != null) {
            factors.add(factor("java_version_jump", Math.max(0, to - from), weights, saturation, true,
                    List.of(detected.evidenceRef()), "Java " + from + " -> " + to));
        } else {
            factors.add(unknown("java_version_jump", weights, saturation, "declared Java level or target Java unknown", unknowns));
        }
        // issue-derived factors
        Set<String> mandatoryFiles = new LinkedHashSet<>();
        List<String> mandatoryRefs = new ArrayList<>();
        List<String> apiRefs = new ArrayList<>();
        List<String> depRefs = new ArrayList<>();
        int api = 0;
        int deps = 0;
        for (MigrationAssessment.MigrationIssue issue : issues) {
            if (issue.mandatory()) {
                mandatoryFiles.add(issue.fileId());
                mandatoryRefs.addAll(issue.evidenceRefs());
            }
            if ("REMOVED_OR_RELOCATED_API".equals(issue.category())) {
                api++;
                apiRefs.addAll(issue.evidenceRefs());
            } else if (issue.ruleId().contains("STARTER") || issue.ruleId().contains("SPRINGDOC")
                    || issue.ruleId().contains("SECURITY-TEST")) {
                deps++;
                depRefs.addAll(issue.evidenceRefs());
            }
        }
        boolean packKnown = !issues.isEmpty() || target != null && target.basis() != null && target.basis().startsWith("reference pack");
        if (packKnown) {
            // a count of zero is an observation too: it rests on the pack scan having covered every active file
            String scanRef = context.evidence().record(EvidenceRecord.EvidenceKind.OBSERVATION, "Reference-pack scan over "
                            + context.identity().activeFiles().size() + " active file(s): " + issues.size() + " issue(s), "
                            + api + " API, " + deps + " dependency", null, null, List.of(),
                    "every rule's scan patterns applied to every active file", EvidenceRecord.Reliability.VERIFIED, null, null,
                    null, "migration.effort").evidenceId();
            mandatoryRefs.add(scanRef);
            apiRefs.add(scanRef);
            depRefs.add(scanRef);
            factors.add(factor("mandatory_migration_issue_count", mandatoryFiles.size(), weights, saturation, true, mandatoryRefs,
                    mandatoryFiles.size() + " file(s) with mandatory changes per the reference pack"));
            factors.add(factor("dependency_incompatibility_count", deps, weights, saturation, true, depRefs,
                    deps + " dependency rename/bump/replacement(s)"));
            factors.add(factor("removed_deprecated_api_count", api, weights, saturation, true, apiRefs,
                    api + " removed or relocated API usage(s)"));
        } else {
            factors.add(unknown("mandatory_migration_issue_count", weights, saturation, "no reference pack: issues cannot be enumerated", unknowns));
            factors.add(unknown("dependency_incompatibility_count", weights, saturation, "no reference pack", unknowns));
            factors.add(unknown("removed_deprecated_api_count", weights, saturation, "no reference pack", unknowns));
        }
        // configuration_breakage_count: the pack names no property list for this jump (§7) - unknown, never guessed
        factors.add(unknown("configuration_breakage_count", weights, saturation,
                "the reference pack lists no removed/renamed property set for this jump (§7: 'fail silently at startup'); "
                        + "only the runtime properties migrator can tell", unknowns));
        // module_service_breadth
        int modules = model == null ? 0 : model.modules().size();
        String modulesRef = context.evidence().record(EvidenceRecord.EvidenceKind.OBSERVATION, modules + " build module(s)", null,
                null, List.of(), "build model", model != null && model.authoritative() ? EvidenceRecord.Reliability.VERIFIED
                        : EvidenceRecord.Reliability.DECLARED, null, "baseline/build-model-view.json", null,
                "migration.effort").evidenceId();
        factors.add(factor("module_service_breadth", modules, weights, saturation, model != null, List.of(modulesRef),
                modules + " module(s)"));
        // runtime_test_coverage_gap
        long main = context.identity().activeFiles().stream().filter(f -> f.path().contains("/src/main/java/")).count();
        long tests = context.identity().activeFiles().stream().filter(f -> f.path().contains("/src/test/java/")
                || f.path().startsWith("src/test/java/")).count();
        long mainFiles = main + context.identity().activeFiles().stream().filter(f -> f.path().startsWith("src/main/java/")).count();
        double gap = mainFiles == 0 ? 1.0 : Math.max(0, 1.0 - Math.min(1.0, (double) tests / mainFiles));
        String coverageRef = context.evidence().record(EvidenceRecord.EvidenceKind.OBSERVATION, tests + " test file(s) for "
                        + mainFiles + " main file(s)", null, null, List.of(), "inventory", EvidenceRecord.Reliability.VERIFIED,
                null, null, null, "migration.effort").evidenceId();
        factors.add(factor("runtime_test_coverage_gap", round2(gap), weights, saturation, true, List.of(coverageRef),
                "test-to-main file ratio gap " + round2(gap)));
        // unknown_internal_components
        List<String> prefixes = policy.migration().knownPublicGroupPrefixes();
        Set<String> ownGroups = new LinkedHashSet<>();
        if (model != null) {
            for (BuildModelView.ModuleBuild m : model.modules()) {
                String g = m.properties().get("project.groupId");
                if (g != null) {
                    ownGroups.add(g);
                }
            }
        }
        List<String> internal = model == null ? List.of() : model.allDependencies().stream()
                .map(BuildModelView.Dependency::groupId)
                .filter(g -> prefixes.stream().noneMatch(g::startsWith) && !ownGroups.contains(g)).distinct().toList();
        String internalRef = context.evidence().record(EvidenceRecord.EvidenceKind.OBSERVATION, internal.size()
                        + " dependency group(s) not recognised as public: " + internal, null, null, List.of(),
                "policy known_public_group_prefixes; unknown components never default to compatible (Bootshift R28)",
                EvidenceRecord.Reliability.DERIVED, null, null, null, "migration.effort").evidenceId();
        factors.add(factor("unknown_internal_components", internal.size(), weights, saturation, model != null,
                List.of(internalRef), String.join(", ", internal)));
        // ecosystem_constraints: explicitly pinned Spring-integrating libraries (§1.4)
        List<String> pinned = model == null ? List.of() : model.allDependencies().stream()
                .filter(d -> !d.managed() && d.declaredVersion() != null)
                .filter(d -> d.groupId().contains("spring") && !d.groupId().equals("org.springframework.boot")
                        || d.artifactId().contains("spring"))
                .map(BuildModelView.Dependency::ga).distinct().toList();
        String pinnedRef = context.evidence().record(EvidenceRecord.EvidenceKind.OBSERVATION, pinned.size()
                        + " explicitly versioned Spring-integrating librar(ies): " + pinned, null, null, List.of(),
                "reference pack §1.4: explicitly pinned integrations risk NoSuchMethodError", EvidenceRecord.Reliability.DERIVED,
                null, null, null, "migration.effort").evidenceId();
        factors.add(factor("ecosystem_constraints", pinned.size(), weights, saturation, model != null, List.of(pinnedRef),
                String.join(", ", pinned)));

        int score = factors.stream().mapToInt(MigrationAssessment.EffortFactor::contribution).sum();
        evidence.add(modulesRef);
        evidence.add(coverageRef);
        return new Result(new MigrationAssessment.EffortScore(score, policy.policyVersion(), factors,
                "Σ round(weight × min(raw/saturation, 1)); weights and saturation from policy " + policy.policyVersion()
                        + "; unknown factors contribute 0 and are listed as unknowns"), unknowns);
    }

    static MigrationAssessment.Complexity complexity(int score, Map<String, Integer> thresholds,
                                                     List<MigrationAssessment.Blocker> blockers) {
        if (score <= thresholds.getOrDefault("TRIVIAL", 10)) {
            return MigrationAssessment.Complexity.TRIVIAL;
        }
        if (score <= thresholds.getOrDefault("LOW", 30)) {
            return MigrationAssessment.Complexity.LOW;
        }
        if (score <= thresholds.getOrDefault("MODERATE", 55)) {
            return MigrationAssessment.Complexity.MODERATE;
        }
        if (score <= thresholds.getOrDefault("HIGH", 80)) {
            return MigrationAssessment.Complexity.HIGH;
        }
        return MigrationAssessment.Complexity.REARCHITECTURE;
    }

    private static MigrationAssessment.EffortFactor factor(String name, double raw, Map<String, Double> weights,
                                                           Map<String, Double> saturation, boolean known, List<String> refs,
                                                           String note) {
        double weight = weights.getOrDefault(name, 0.0);
        double sat = saturation.getOrDefault(name, 1.0);
        double normalized = known ? Math.min(raw / sat, 1.0) : 0.0;
        return new MigrationAssessment.EffortFactor(name, raw, sat, round2(normalized), weight,
                (int) Math.round(weight * normalized), known, refs, note);
    }

    private static MigrationAssessment.EffortFactor unknown(String name, Map<String, Double> weights,
                                                            Map<String, Double> saturation, String why,
                                                            List<MigrationAssessment.Unknown> unknowns) {
        unknowns.add(new MigrationAssessment.Unknown("effort:" + name, why, "contributes 0 to the effort score; true effort may be higher",
                List.of()));
        return new MigrationAssessment.EffortFactor(name, 0, saturation.getOrDefault(name, 1.0), 0,
                weights.getOrDefault(name, 0.0), 0, false, List.of(), "UNKNOWN: " + why);
    }

    private static Integer parse(String value) {
        try {
            return value == null ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
