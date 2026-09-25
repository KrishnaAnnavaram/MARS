package com.mars.harness.kernel.core.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One validation pass: the explicit per-dimension picture (spec §21).
 *
 * <pre>
 * Build: PASS   Tests: PASS   Original exploit: NO_LONGER_REPRODUCES   Security re-scan: CLEARED
 * Runtime behavior: EQUIVALENT_WITH_EXPLAINED_DIFFS   Graph diff: EXPECTED   Evidence coverage: SUFFICIENT
 * </pre>
 */
public record ValidationResult(String validationId, String runId, String scope, String generatedAt,
                               List<DimensionResult> dimensions, List<String> mandatory) {

    public ValidationResult {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        mandatory = mandatory == null ? List.of() : List.copyOf(mandatory);
    }

    /**
     * @param detail capability-specific reading, for example {@code EQUIVALENT_WITH_EXPLAINED_DIFFS}
     *               or {@code STILL_VULNERABLE}; informational only, never a substitute for status
     */
    public record DimensionResult(ValidationDimension dimension, DimensionStatus status, String detail,
                                  String summary, List<String> evidenceRefs, String producer) {
        public DimensionResult {
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        }
    }

    public Optional<DimensionResult> dimension(ValidationDimension d) {
        return dimensions.stream().filter(r -> r.dimension() == d).findFirst();
    }

    public DimensionStatus status(ValidationDimension d) {
        return dimension(d).map(DimensionResult::status).orElse(DimensionStatus.NOT_RUN);
    }

    public List<ValidationDimension> mandatoryFailed() {
        List<ValidationDimension> failed = new ArrayList<>();
        for (String m : mandatory) {
            ValidationDimension d = ValidationDimension.valueOf(m);
            if (status(d).failed()) {
                failed.add(d);
            }
        }
        return failed;
    }

    public List<ValidationDimension> mandatoryUnknown() {
        List<ValidationDimension> unknown = new ArrayList<>();
        for (String m : mandatory) {
            ValidationDimension d = ValidationDimension.valueOf(m);
            if (status(d).unknown()) {
                unknown.add(d);
            }
        }
        return unknown;
    }
}
