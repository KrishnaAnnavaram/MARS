package com.bootshift.core.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bootshift.core.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The active policy for a run.
 *
 * <p>Thresholds live here as configuration rather than as hardcoded business logic (spec 35), and
 * the policy is hashed into evidence so a report can state which rules were in force.
 */
public final class HarnessPolicy {

    public static final String PRODUCTION = "production";
    public static final String DEVELOPMENT = "development";

    private String name = PRODUCTION;
    private String version = "1.0.0";

    // ---- coverage gate (R29) ----
    private double coverageDropBlockPercentagePoints = 5.0;
    private boolean coverageGateEnabled = true;

    // ---- residual escalation (spec 35) ----
    private double residualNoEscalationThreshold = 0.90;
    private double residualOneLevelThreshold = 0.60;

    // ---- impact analyzer accuracy floor (spec 20) ----
    private double impactRecallFloor = 0.80;

    // ---- identity ----
    private double similarityThreshold = 0.72;

    // ---- target resolution (R25) ----
    private boolean allowMilestoneTargets;
    private boolean allowEolLandingTarget;
    private int minimumSupportHorizonMonths = 6;

    // ---- Java target selection (R25) ----
    /**
     * How the application's Java target is chosen for an edge. LTS_PREFERRED is the default because
     * the alternative - taking the numerically highest installed JDK - lands production code on a
     * release with a six-month support window as a side effect of what happened to be installed.
     */
    private String javaTargetPreference = "LTS_PREFERRED";

    /** Refuse to land on a non-LTS Java release unless explicitly permitted. */
    private boolean allowNonLtsJavaLanding;

    // ---- transformation reconciliation (R26) ----
    private boolean allowCheckpointCollapse = true;

    // ---- internal components (R28) ----
    private String unknownInternalComponentAction = "BLOCK";

    // ---- AI boundary (R12) ----
    private boolean aiAllowedInProduction;
    private int aiMaxTotalAttempts = 12;
    private int aiMaxAttemptsPerRootCause = 3;
    private int aiMaxFilesPerPatch = 3;
    private int aiMaxChangedLinesPerPatch = 80;

    // ---- repair budgets (spec 28) ----
    private int repairMaxAttemptsPerRootCause = 4;
    private int repairMaxTotalRounds = 10;

    // ---- differential ----
    private boolean unexplainedDifferenceBlocks = true;

    // ---- graph verification floors (spec 14) ----
    private double graphJavaCoverageFloor = 0.98;
    private double graphAttributionFloor = 0.70;

    private LicensePolicy license = new LicensePolicy();
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public static HarnessPolicy production() {
        return new HarnessPolicy();
    }

    public static HarnessPolicy development() {
        HarnessPolicy policy = new HarnessPolicy();
        policy.name = DEVELOPMENT;
        policy.aiAllowedInProduction = true;
        policy.unknownInternalComponentAction = "WARN";
        policy.minimumSupportHorizonMonths = 0;
        policy.graphAttributionFloor = 0.50;
        policy.allowNonLtsJavaLanding = true;
        return policy;
    }

    public static HarnessPolicy load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return production();
        }
        JsonNode node = Json.read(file);
        HarnessPolicy policy = "development".equals(node.path("name").asText()) ? development() : production();
        policy.name = node.path("name").asText(policy.name);
        policy.version = node.path("version").asText(policy.version);
        policy.coverageDropBlockPercentagePoints =
                node.path("coverage_drop_block_percentage_points").asDouble(policy.coverageDropBlockPercentagePoints);
        policy.coverageGateEnabled = node.path("coverage_gate_enabled").asBoolean(policy.coverageGateEnabled);
        policy.residualNoEscalationThreshold =
                node.path("residual_no_escalation_threshold").asDouble(policy.residualNoEscalationThreshold);
        policy.residualOneLevelThreshold =
                node.path("residual_one_level_threshold").asDouble(policy.residualOneLevelThreshold);
        policy.impactRecallFloor = node.path("impact_recall_floor").asDouble(policy.impactRecallFloor);
        policy.similarityThreshold = node.path("similarity_threshold").asDouble(policy.similarityThreshold);
        policy.allowMilestoneTargets = node.path("allow_milestone_targets").asBoolean(policy.allowMilestoneTargets);
        policy.allowEolLandingTarget = node.path("allow_eol_landing_target").asBoolean(policy.allowEolLandingTarget);
        policy.minimumSupportHorizonMonths =
                node.path("minimum_support_horizon_months").asInt(policy.minimumSupportHorizonMonths);
        policy.allowCheckpointCollapse = node.path("allow_checkpoint_collapse").asBoolean(policy.allowCheckpointCollapse);
        policy.javaTargetPreference = node.path("java_target_preference").asText(policy.javaTargetPreference);
        policy.allowNonLtsJavaLanding =
                node.path("allow_non_lts_java_landing").asBoolean(policy.allowNonLtsJavaLanding);
        policy.unknownInternalComponentAction =
                node.path("unknown_internal_component_action").asText(policy.unknownInternalComponentAction);
        policy.aiAllowedInProduction = node.path("ai_allowed_in_production").asBoolean(policy.aiAllowedInProduction);
        policy.aiMaxTotalAttempts = node.path("ai_max_total_attempts").asInt(policy.aiMaxTotalAttempts);
        policy.aiMaxAttemptsPerRootCause =
                node.path("ai_max_attempts_per_root_cause").asInt(policy.aiMaxAttemptsPerRootCause);
        policy.aiMaxFilesPerPatch = node.path("ai_max_files_per_patch").asInt(policy.aiMaxFilesPerPatch);
        policy.aiMaxChangedLinesPerPatch =
                node.path("ai_max_changed_lines_per_patch").asInt(policy.aiMaxChangedLinesPerPatch);
        policy.repairMaxAttemptsPerRootCause =
                node.path("repair_max_attempts_per_root_cause").asInt(policy.repairMaxAttemptsPerRootCause);
        policy.repairMaxTotalRounds = node.path("repair_max_total_rounds").asInt(policy.repairMaxTotalRounds);
        policy.unexplainedDifferenceBlocks =
                node.path("unexplained_difference_blocks").asBoolean(policy.unexplainedDifferenceBlocks);
        policy.graphJavaCoverageFloor = node.path("graph_java_coverage_floor").asDouble(policy.graphJavaCoverageFloor);
        policy.graphAttributionFloor = node.path("graph_attribution_floor").asDouble(policy.graphAttributionFloor);
        return policy;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public double coverageDropBlockPercentagePoints() {
        return coverageDropBlockPercentagePoints;
    }

    public boolean coverageGateEnabled() {
        return coverageGateEnabled;
    }

    public double residualNoEscalationThreshold() {
        return residualNoEscalationThreshold;
    }

    public double residualOneLevelThreshold() {
        return residualOneLevelThreshold;
    }

    public double impactRecallFloor() {
        return impactRecallFloor;
    }

    public double similarityThreshold() {
        return similarityThreshold;
    }

    public boolean allowMilestoneTargets() {
        return allowMilestoneTargets;
    }

    public boolean allowEolLandingTarget() {
        return allowEolLandingTarget;
    }

    public int minimumSupportHorizonMonths() {
        return minimumSupportHorizonMonths;
    }

    public boolean allowCheckpointCollapse() {
        return allowCheckpointCollapse;
    }

    public String javaTargetPreference() {
        return javaTargetPreference;
    }

    public boolean allowNonLtsJavaLanding() {
        return allowNonLtsJavaLanding;
    }

    public String unknownInternalComponentAction() {
        return unknownInternalComponentAction;
    }

    public boolean aiAllowedInProduction() {
        return aiAllowedInProduction;
    }

    public int aiMaxTotalAttempts() {
        return aiMaxTotalAttempts;
    }

    public int aiMaxAttemptsPerRootCause() {
        return aiMaxAttemptsPerRootCause;
    }

    public int aiMaxFilesPerPatch() {
        return aiMaxFilesPerPatch;
    }

    public int aiMaxChangedLinesPerPatch() {
        return aiMaxChangedLinesPerPatch;
    }

    public int repairMaxAttemptsPerRootCause() {
        return repairMaxAttemptsPerRootCause;
    }

    public int repairMaxTotalRounds() {
        return repairMaxTotalRounds;
    }

    public boolean unexplainedDifferenceBlocks() {
        return unexplainedDifferenceBlocks;
    }

    public double graphJavaCoverageFloor() {
        return graphJavaCoverageFloor;
    }

    public double graphAttributionFloor() {
        return graphAttributionFloor;
    }

    public LicensePolicy license() {
        return license;
    }

    public HarnessPolicy license(LicensePolicy value) {
        this.license = value;
        return this;
    }

    public Map<String, Object> extras() {
        return extras;
    }

    /**
     * Residual escalation rule (spec 35). Deterministic coverage below the thresholds raises the
     * planned depth rather than quietly accepting less evidence.
     */
    public ValidationDepth escalateForResidual(ValidationDepth base, double deterministicCoverage) {
        if (deterministicCoverage >= residualNoEscalationThreshold) {
            return base;
        }
        if (deterministicCoverage >= residualOneLevelThreshold) {
            return base.escalate(1);
        }
        return ValidationDepth.FULL_DIFFERENTIAL;
    }

    public ObjectNode toNode() {
        ObjectNode node = Json.obj();
        node.put("name", name);
        node.put("version", version);
        node.put("coverage_gate_enabled", coverageGateEnabled);
        node.put("coverage_drop_block_percentage_points", coverageDropBlockPercentagePoints);
        node.put("residual_no_escalation_threshold", residualNoEscalationThreshold);
        node.put("residual_one_level_threshold", residualOneLevelThreshold);
        node.put("impact_recall_floor", impactRecallFloor);
        node.put("similarity_threshold", similarityThreshold);
        node.put("allow_milestone_targets", allowMilestoneTargets);
        node.put("allow_eol_landing_target", allowEolLandingTarget);
        node.put("minimum_support_horizon_months", minimumSupportHorizonMonths);
        node.put("allow_checkpoint_collapse", allowCheckpointCollapse);
        node.put("java_target_preference", javaTargetPreference);
        node.put("allow_non_lts_java_landing", allowNonLtsJavaLanding);
        node.put("unknown_internal_component_action", unknownInternalComponentAction);
        node.put("ai_allowed_in_production", aiAllowedInProduction);
        node.put("ai_max_total_attempts", aiMaxTotalAttempts);
        node.put("ai_max_attempts_per_root_cause", aiMaxAttemptsPerRootCause);
        node.put("ai_max_files_per_patch", aiMaxFilesPerPatch);
        node.put("ai_max_changed_lines_per_patch", aiMaxChangedLinesPerPatch);
        node.put("repair_max_attempts_per_root_cause", repairMaxAttemptsPerRootCause);
        node.put("repair_max_total_rounds", repairMaxTotalRounds);
        node.put("unexplained_difference_blocks", unexplainedDifferenceBlocks);
        node.put("graph_java_coverage_floor", graphJavaCoverageFloor);
        node.put("graph_attribution_floor", graphAttributionFloor);
        node.set("license_allowlist", Json.toTree(List.copyOf(license.allowlist())));
        node.set("license_denylist", Json.toTree(List.copyOf(license.denylist())));
        node.set("license_forbidden_artifacts", Json.toTree(List.copyOf(license.forbiddenArtifacts())));
        return node;
    }

    public String policyHash() {
        return Json.canonicalHash(toNode());
    }
}
