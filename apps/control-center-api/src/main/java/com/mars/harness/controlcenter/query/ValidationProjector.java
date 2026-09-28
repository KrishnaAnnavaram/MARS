package com.mars.harness.controlcenter.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.controlcenter.api.dto.FindingDtos;
import com.mars.harness.controlcenter.api.dto.MigrationDtos;
import com.mars.harness.controlcenter.api.dto.RunDtos;
import com.mars.harness.controlcenter.api.dto.ValidationDtos;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.validation.ValidationDimension;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.core.verdict.Verdict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validation and verdict views. Every status is the validator's or the calculator's own; the API
 * never promotes an unknown or unexecuted dimension, and never computes a verdict.
 */
public final class ValidationProjector {

    private ValidationProjector() {
    }

    public static RunDtos.ValidationSummary summary(RunReader run) {
        Optional<ValidationResult> v = run.validation();
        if (v.isEmpty()) {
            return new RunDtos.ValidationSummary("NOT_RUN", 0, 0, 0, 0, List.of(), List.of());
        }
        ValidationResult r = v.get();
        int passed = (int) r.dimensions().stream().filter(d -> d.status().passed()).count();
        int failed = (int) r.dimensions().stream().filter(d -> d.status().failed()).count();
        int unknown = (int) r.dimensions().stream().filter(d -> d.status().unknown()).count();
        List<String> mandatoryFailed = r.mandatoryFailed().stream().map(Enum::name).toList();
        List<String> mandatoryUnknown = r.mandatoryUnknown().stream().map(Enum::name).toList();
        String status = !mandatoryFailed.isEmpty() ? "FAILED" : !mandatoryUnknown.isEmpty() ? "INCOMPLETE" : "PASSED";
        return new RunDtos.ValidationSummary(status, r.dimensions().size(), passed, failed, unknown, mandatoryFailed,
                mandatoryUnknown);
    }

    public static ValidationDtos.ValidationView view(RunReader run) {
        Optional<ValidationResult> v = run.validation();
        List<MigrationDtos.DimensionView> dims = v.map(r -> r.dimensions().stream()
                .map(d -> MigrationProjector.dimension(d, r.mandatory().contains(d.dimension().name()))).toList())
                .orElse(List.of());
        List<String> notRun = v.map(r -> Arrays.stream(ValidationDimension.values())
                .filter(d -> r.dimension(d).isEmpty()).map(Enum::name).toList()).orElse(List.of());
        List<MigrationDtos.DimensionView> migration = run.migrationValidation().map(m -> m.dimensions().stream()
                .map(d -> MigrationProjector.dimension(d, false)).toList()).orElse(List.of());
        List<FindingDtos.VerificationView> security = new ArrayList<>();
        run.record().verification.keySet().forEach(planId -> run.verification(planId)
                .ifPresent(r -> security.add(FindingProjector.verification(r))));
        List<ValidationDtos.ProposalValidation> proposals = new ArrayList<>();
        for (ChangeProposal p : run.proposals()) {
            String status = run.proposalStatus(p.proposalId());
            if ("VALIDATED".equals(status) || "FAILED_VALIDATION".equals(status) || "APPLIED".equals(status)) {
                proposals.add(new ValidationDtos.ProposalValidation(p.proposalId(), "APPLIED".equals(status)
                        ? "NOT_YET_VALIDATED" : status, p.capability().name(), p.findingRefs()));
            }
        }
        return new ValidationDtos.ValidationView(summary(run).status(), v.map(ValidationResult::validationId).orElse(null),
                v.map(ValidationResult::scope).orElse(null), v.map(ValidationResult::generatedAt).orElse(null), dims,
                notRun, migration, security, proposals, baseline(run));
    }

    private static ValidationDtos.BaselineView baseline(RunReader run) {
        Optional<JsonNode> manifest = run.baselineManifest();
        if (manifest.isEmpty()) {
            return null;
        }
        JsonNode m = manifest.get();
        List<String> failing = new ArrayList<>();
        m.path("baseline_failing_tests").forEach(t -> failing.add(t.asText()));
        Object tests = m.hasNonNull("baseline_tests") ? m.path("baseline_tests") : null;
        return new ValidationDtos.BaselineView(m.path("baseline_manifest_hash").asText(null), m.path("sealed_at").asText(null),
                m.path("baseline_build_outcome").asText(null), tests, failing,
                m.hasNonNull("baseline_runtime_started") ? m.path("baseline_runtime_started").asBoolean() : null);
    }

    public static ValidationDtos.VerdictView verdict(RunReader run) {
        Optional<Verdict> v = run.verdict();
        Map<String, Integer> proposals = ProposalProjector.summary(run).byStatus();
        if (v.isEmpty()) {
            return new ValidationDtos.VerdictView(false, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                    Map.of(), proposals, List.of(), null, List.of());
        }
        Verdict verdict = v.get();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        List<ValidationDtos.VerdictItemView> items = new ArrayList<>();
        for (Verdict.ItemResult i : verdict.items()) {
            byStatus.merge(i.status().name(), 1, Integer::sum);
            String label = "MIGRATION".equals(i.itemId()) ? "Migration"
                    : run.finding(i.itemId()).map(f -> f.sourceFindingId() + " " + String.join(",", f.cwe()) + " — " + f.title())
                    .orElse(i.itemId());
            items.add(new ValidationDtos.VerdictItemView(i.itemId(), label, i.kind(), i.status().name(), i.legacyVerdict(),
                    i.reason(), i.evidenceRefs()));
        }
        List<String> reports = new ArrayList<>();
        for (String r : List.of("reports/final-report.md", "reports/final-report.json", "reports/verdict.json",
                "reports/cumulative.patch", "reports/analysis-report.md")) {
            run.file(r).ifPresent(f -> reports.add(r));
        }
        return new ValidationDtos.VerdictView(true, verdict.outcome().name(), verdict.generatedAt(), verdict.reasons(),
                verdict.hardFailures(), verdict.unknownDimensions(), verdict.pendingDecisions(), items, byStatus, proposals,
                reports, verdict.policyVersion(), verdict.evidenceRefs());
    }

    /** Label for a finding id, for places that only have the id. */
    static String findingLabel(RunReader run, String findingId) {
        return run.finding(findingId).map(Finding::sourceFindingId).orElse(findingId);
    }
}
