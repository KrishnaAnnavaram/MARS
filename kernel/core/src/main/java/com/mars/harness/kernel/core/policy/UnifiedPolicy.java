package com.mars.harness.kernel.core.policy;

import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.identity.ReattachmentPolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned harness policy ({@code policies/default/unified-policy.json}).
 *
 * <p>Every threshold and weight the harness decides with lives here, so that a decision can be
 * reproduced from the policy version recorded next to it. The code holds no magic numbers.
 */
public record UnifiedPolicy(String policyId, String policyVersion, List<String> reservedMachineActors,
                            Identity identity, Migration migration, Mutation mutation, Validation validation,
                            Security security) {

    public record Identity(double statementHigh, double statementLow, double symbolHigh, double symbolLow,
                           double unitSimilarity, double ambiguityMargin) {
        public ReattachmentPolicy toReattachmentPolicy() {
            return new ReattachmentPolicy(statementHigh, statementLow, symbolHigh, symbolLow, unitSimilarity,
                    ambiguityMargin);
        }
    }

    /**
     * @param effortWeights       points per factor; the weights must sum to 100
     * @param effortSaturation    raw value at which a factor reaches its full weight
     * @param complexityThresholds effort score upper bounds for TRIVIAL, LOW, MODERATE, HIGH
     */
    public record Migration(Map<String, Double> effortWeights, Map<String, Double> effortSaturation,
                            Map<String, Integer> complexityThresholds, int supportHorizonWarningMonths,
                            boolean planRequiresApproval, int maxRounds, int roundTimeoutSeconds,
                            List<String> knownPublicGroupPrefixes) {
    }

    public record Mutation(int maxFilesPerBatch, int maxChangedLinesPerFile) {
    }

    public record Validation(List<String> mandatoryMigration, List<String> mandatorySecurity,
                             List<String> mandatoryAnalysis) {
    }

    /** @param scoringFile VRH merge-arbiter scoring config, read unchanged from legacy-sources */
    public record Security(String catalogFile, String supplementalCatalogFile, String kbFile, String scoringFile,
                           String rankingWeightsFile, Map<String, String> urgentSeverities) {
    }

    public Set<String> reservedActors() {
        return Set.copyOf(reservedMachineActors);
    }

    public static UnifiedPolicy load(Path file) {
        UnifiedPolicy policy = KernelJson.read(file, UnifiedPolicy.class);
        double sum = policy.migration().effortWeights().values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 100.0) > 0.001) {
            throw new IllegalStateException("Policy " + policy.policyVersion()
                    + ": migration effort weights must sum to 100, found " + sum);
        }
        return policy;
    }
}
