package com.mars.harness.kernel.engine.validation;

import com.bootshift.core.ledger.ChangeLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.evidence.EvidenceLog;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.core.validation.DimensionStatus;
import com.mars.harness.kernel.core.validation.ValidationDimension;
import com.mars.harness.kernel.core.validation.ValidationResult;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.exec.WorkspaceSandbox;
import com.mars.harness.kernel.engine.ledger.LineageLedger;
import com.mars.harness.kernel.engine.mutation.MutationGateway;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.runtime.ProbeComparator;
import com.mars.harness.kernel.ports.runtime.RuntimePort;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The common validation plane (spec §21). Migration and remediation changes go through the same
 * dimensions. Capability-specific checks plug in as dimension results.
 *
 * <p>It does not treat a successful build as a successful migration, nor a fix that compiles as a
 * fixed vulnerability. Each dimension carries its own status, and one that did not run is
 * reported as not run.
 */
public final class UnifiedValidator {

    public record Inputs(boolean mutated, boolean migrationExecuted, boolean securityApplied,
                         List<ValidationResult.DimensionResult> capabilityDimensions,
                         Map<String, String> behaviourExplanations, Set<String> authorizedFileIds) {
    }

    private final RunSession session;
    private final EngineConfig config;
    private final MutationGateway gateway;

    public UnifiedValidator(RunSession session, EngineConfig config, MutationGateway gateway) {
        this.session = session;
        this.config = config;
        this.gateway = gateway;
    }

    public ValidationResult validate(Inputs inputs) {
        Map<ValidationDimension, ValidationResult.DimensionResult> dims = new LinkedHashMap<>();
        inputs.capabilityDimensions().forEach(d -> merge(dims, d));

        integrity(dims);
        bypass(dims);
        evidenceCoverage(dims);
        graphDiff(dims, inputs);
        if (inputs.mutated()) {
            finalBuildAndBehaviour(dims, inputs);
        } else {
            for (ValidationDimension d : List.of(ValidationDimension.COMPILE, ValidationDimension.TESTS,
                    ValidationDimension.BUILD_PACKAGE, ValidationDimension.RUNTIME_STARTUP,
                    ValidationDimension.BEHAVIOR_PROBES, ValidationDimension.OLD_NEW_DIFFERENTIAL)) {
                dims.putIfAbsent(d, dim(d, DimensionStatus.NOT_APPLICABLE, null,
                        "No source was mutated in this run; nothing to re-validate", List.of()));
            }
        }
        for (ValidationDimension d : ValidationDimension.values()) {
            dims.putIfAbsent(d, dim(d, DimensionStatus.NOT_RUN, null, "No producer ran this dimension", List.of()));
        }

        List<String> mandatory = new ArrayList<>(session.policy.validation().mandatoryAnalysis());
        if (inputs.migrationExecuted()) {
            session.policy.validation().mandatoryMigration().stream().filter(m -> !mandatory.contains(m)).forEach(mandatory::add);
        }
        if (inputs.securityApplied()) {
            session.policy.validation().mandatorySecurity().stream().filter(m -> !mandatory.contains(m)).forEach(mandatory::add);
        }
        ValidationResult result = new ValidationResult(HarnessIds.allocate(HarnessIds.Kind.VALIDATION),
                session.layout.runId(), inputs.migrationExecuted() && inputs.securityApplied() ? "COMPOSITE"
                : inputs.migrationExecuted() ? "MIGRATION" : inputs.securityApplied() ? "SECURITY" : "ANALYSIS",
                Instant.now().toString(), new ArrayList<>(dims.values()), mandatory);
        session.artifacts.writeJson("validation", result.validationId() + ".json", result);
        session.artifacts.writeJson("validation", "latest.json", result);
        return result;
    }

    // ------------------------------------------------------------------ kernel dimensions

    private void integrity(Map<ValidationDimension, ValidationResult.DimensionResult> dims) {
        List<String> problems = new ArrayList<>();
        ChangeLedger.Verification ledger = ChangeLedger.verify(session.layout.ledgerFile(), session.layout.ledgerHead());
        if (!ledger.valid()) {
            problems.addAll(ledger.violations());
        }
        problems.addAll(LineageLedger.verify(session.layout.lineageLedger()));
        problems.addAll(EvidenceLog.verify(session.layout.evidenceLog()));
        session.approvals.verifyIntegrity().forEach(id -> problems.add("decision " + id + " failed its integrity check"));
        String evidence = session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT,
                "Integrity: change ledger (" + ledger.verifiedEvents() + " events), lineage ledger, evidence log, decisions",
                problems.isEmpty() ? "intact" : String.join("; ", problems), "ledger/, provenance/, decisions/", List.of(),
                "hash-chain recomputation from disk", EvidenceRecord.Reliability.VERIFIED, null, null, null,
                "kernel.validation").evidenceId();
        put(dims, dim(ValidationDimension.IDENTITY_INTEGRITY, problems.isEmpty() ? DimensionStatus.PASS : DimensionStatus.FAIL,
                problems.isEmpty() ? "INTACT" : "TAMPERED", problems.isEmpty()
                        ? "Change ledger, lineage ledger, evidence log and decisions verify from disk"
                        : String.join("; ", problems), List.of(evidence)));
    }

    private void bypass(Map<ValidationDimension, ValidationResult.DimensionResult> dims) {
        List<String> violations = gateway.detectBypass();
        String evidence = session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT,
                "Bypass detection: " + violations.size() + " violation(s)", String.join(" | ", violations),
                session.layout.workspace().toString(), List.of(), "FileMutationGateway.detectBypass (on-disk hash vs registry)",
                EvidenceRecord.Reliability.VERIFIED, null, null, null, "kernel.validation").evidenceId();
        boolean earlier = session.record.notes.stream().anyMatch(n -> n.startsWith("BYPASS DETECTED"));
        put(dims, dim(ValidationDimension.MUTATION_BYPASS, violations.isEmpty() && !earlier ? DimensionStatus.PASS
                : DimensionStatus.FAIL, violations.isEmpty() && !earlier ? "NONE" : "BYPASS",
                violations.isEmpty() && !earlier ? "Every tracked file matches the gateway-recorded hash"
                        : "Writes outside the Mutation Gateway: " + violations, List.of(evidence)));
    }

    private void evidenceCoverage(Map<ValidationDimension, ValidationResult.DimensionResult> dims) {
        List<String> gaps = new ArrayList<>();
        for (Finding f : session.findings) {
            if (f.evidenceRefs().isEmpty()) {
                gaps.add("finding " + f.findingId() + " has no evidence");
            } else {
                f.evidenceRefs().stream().filter(e -> session.evidence.get(e).isEmpty())
                        .forEach(e -> gaps.add("finding " + f.findingId() + " cites unknown evidence " + e));
            }
        }
        for (Map.Entry<String, String> entry : session.record.proposalStatus.entrySet()) {
            if ("APPLIED".equals(entry.getValue()) || "VALIDATED".equals(entry.getValue())
                    || "FAILED_VALIDATION".equals(entry.getValue())) {
                boolean authorized = session.lineage.entries().stream()
                        .anyMatch(e -> entry.getKey().equals(e.proposalId()) && "CHANGE".equals(e.kind()) && e.decisionId() != null);
                if (!authorized) {
                    gaps.add("applied proposal " + entry.getKey() + " has no recorded authorizing decision");
                }
            }
        }
        Path assessment = session.layout.area("discovery").resolve("migration").resolve("migration-assessment.json");
        if (Files.isRegularFile(assessment)) {
            JsonNode node = KernelJson.read(assessment);
            if (node.path("evidence").isEmpty()) {
                gaps.add("migration assessment cites no evidence");
            }
            for (JsonNode factor : node.path("effort").path("factors")) {
                if (factor.path("known").asBoolean(false) && factor.path("evidence_refs").isEmpty()) {
                    gaps.add("effort factor " + factor.path("name").asText() + " has no evidence");
                }
            }
        }
        put(dims, dim(ValidationDimension.EVIDENCE_COVERAGE, gaps.isEmpty() ? DimensionStatus.PASS
                        : DimensionStatus.INSUFFICIENT_EVIDENCE, gaps.isEmpty() ? "SUFFICIENT" : "GAPS",
                gaps.isEmpty() ? "Every finding, assessment factor and applied change traces to evidence"
                        : String.join("; ", gaps), List.of()));
    }

    private void graphDiff(Map<ValidationDimension, ValidationResult.DimensionResult> dims, Inputs inputs) {
        if (!inputs.mutated()) {
            put(dims, dim(ValidationDimension.GRAPH_DIFF, DimensionStatus.NOT_APPLICABLE, null, "No mutation", List.of()));
            return;
        }
        Path dir = session.layout.area("graph");
        List<String> unexpected = new ArrayList<>();
        int diffs = 0;
        try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("graph-diff-")).toList()) {
                diffs++;
                JsonNode node = KernelJson.read(file);
                for (JsonNode changed : node.path("changed_file_ids")) {
                    if (!inputs.authorizedFileIds().contains(changed.asText())) {
                        unexpected.add(changed.asText() + " (" + file.getFileName() + ")");
                    }
                }
            }
        } catch (IOException e) {
            unexpected.add("graph diffs unreadable: " + e.getMessage());
        }
        if (diffs == 0) {
            put(dims, dim(ValidationDimension.GRAPH_DIFF, DimensionStatus.NOT_RUN, null,
                    "No graph diff was produced for the applied mutations", List.of()));
            return;
        }
        put(dims, dim(ValidationDimension.GRAPH_DIFF, unexpected.isEmpty() ? DimensionStatus.PASS : DimensionStatus.FAIL,
                unexpected.isEmpty() ? "EXPECTED" : "UNEXPECTED", unexpected.isEmpty()
                        ? diffs + " graph diff(s); every structural change stays inside authorized FILE_IDs"
                        : "Graph changed outside authorized scope: " + unexpected, List.of()));
    }

    private void finalBuildAndBehaviour(Map<ValidationDimension, ValidationResult.DimensionResult> dims, Inputs inputs) {
        BuildPort.BuildResult baseline = readBaselineBuild();
        if (session.record.skipBuild) {
            for (ValidationDimension d : List.of(ValidationDimension.COMPILE, ValidationDimension.TESTS,
                    ValidationDimension.BUILD_PACKAGE)) {
                put(dims, dim(d, DimensionStatus.NOT_RUN, null, "Builds were skipped for this run (--skip-build)", List.of()));
            }
        } else {
            Path exec = new WorkspaceSandbox(session.layout).prepare("final");
            BuildPort.BuildResult result = config.build().build(exec, BuildPort.Intent.PACKAGE,
                    session.layout.logs().resolve("final-build.log"));
            session.artifacts.writeJson("validation", "final-build.json", result);
            String ev = session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT,
                    "Final build (package) on the mutated workspace: " + result.outcome().legacyId(),
                    result.tests() == null ? null : result.tests().toString(), "exec/final", List.of(),
                    "project build tool is authoritative", EvidenceRecord.Reliability.VERIFIED, null,
                    "validation/final-build.json", null, "kernel.validation").evidenceId();
            if (result.outcome() == BuildPort.Outcome.TOOL_UNAVAILABLE) {
                for (ValidationDimension d : List.of(ValidationDimension.COMPILE, ValidationDimension.TESTS,
                        ValidationDimension.BUILD_PACKAGE)) {
                    put(dims, dim(d, DimensionStatus.TOOL_UNAVAILABLE, null, result.unavailableReason(), List.of(ev)));
                }
            } else {
                boolean compiled = result.outcome() != BuildPort.Outcome.COMPILE_FAILED
                        && result.outcome() != BuildPort.Outcome.DEPENDENCY_FAILED && result.outcome() != BuildPort.Outcome.TIMED_OUT;
                put(dims, dim(ValidationDimension.COMPILE, compiled ? DimensionStatus.PASS : DimensionStatus.FAIL,
                        result.outcome().legacyId(), "Final compile: " + result.outcome().legacyId(), List.of(ev)));
                ProbeComparator.TestVerdict tests = ProbeComparator.compareTests(baseline == null ? null : baseline.tests(),
                        result.tests());
                Set<String> newFailures = new LinkedHashSet<>(result.failingTests());
                if (baseline != null) {
                    baseline.failingTests().forEach(newFailures::remove);
                }
                DimensionStatus testStatus;
                if (!compiled) {
                    testStatus = DimensionStatus.NOT_RUN;
                } else if (!tests.compared()) {
                    testStatus = result.tests() == null ? DimensionStatus.NOT_RUN : DimensionStatus.NOT_COMPARED;
                } else if (tests.worse() || !newFailures.isEmpty()) {
                    testStatus = DimensionStatus.FAIL;
                } else if (result.tests() != null && result.tests().failed() > 0) {
                    testStatus = DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES;
                } else {
                    testStatus = DimensionStatus.PASS;
                }
                put(dims, dim(ValidationDimension.TESTS, testStatus, tests.verdict(),
                        tests.verdict() + (newFailures.isEmpty() ? "" : "; new failing tests: " + newFailures), List.of(ev)));
                put(dims, dim(ValidationDimension.BUILD_PACKAGE, result.passed() ? DimensionStatus.PASS
                        : testStatus == DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES ? DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES
                        : DimensionStatus.FAIL, result.outcome().legacyId(),
                        "Final package: " + result.outcome().legacyId(), List.of(ev)));
                behaviour(dims, inputs, exec, result, tests);
            }
        }
        dims.putIfAbsent(ValidationDimension.RUNTIME_STARTUP, dim(ValidationDimension.RUNTIME_STARTUP, DimensionStatus.NOT_RUN,
                null, "No runtime probe was executed", List.of()));
        dims.putIfAbsent(ValidationDimension.BEHAVIOR_PROBES, dim(ValidationDimension.BEHAVIOR_PROBES, DimensionStatus.NOT_RUN,
                null, "No behaviour probes were defined (--probes) or the application was not packaged", List.of()));
        dims.putIfAbsent(ValidationDimension.OLD_NEW_DIFFERENTIAL, dim(ValidationDimension.OLD_NEW_DIFFERENTIAL,
                DimensionStatus.NOT_COMPARED, null, "No before/after behaviour comparison was possible", List.of()));
    }

    private void behaviour(Map<ValidationDimension, ValidationResult.DimensionResult> dims, Inputs inputs, Path exec,
                           BuildPort.BuildResult build, ProbeComparator.TestVerdict tests) {
        Path specFile = session.layout.area("baseline").resolve("probes.json");
        Path baselineRun = session.layout.area("baseline").resolve("runtime-baseline.json");
        if (!Files.isRegularFile(specFile) || !Files.isRegularFile(baselineRun)) {
            return;
        }
        RuntimePort.ProbeSpec spec = KernelJson.read(specFile, RuntimePort.ProbeSpec.class);
        RuntimePort.RuntimeRun before = KernelJson.read(baselineRun, RuntimePort.RuntimeRun.class);
        if (build.outcome() != BuildPort.Outcome.PASSED && build.outcome() != BuildPort.Outcome.TESTS_FAILED) {
            put(dims, dim(ValidationDimension.RUNTIME_STARTUP, DimensionStatus.NOT_RUN, null,
                    "The final workspace did not package; the application cannot be started", List.of()));
            return;
        }
        RuntimePort.RuntimeRun after = config.runtime().run(exec, spec, "final",
                session.layout.logs().resolve("runtime-final.log"));
        session.artifacts.writeJson("validation", "runtime-final.json", after);
        String ev = session.evidence.record(EvidenceRecord.EvidenceKind.TOOL_RESULT,
                "Final runtime probes: started=" + after.started(), after.failure(), "exec/final", List.of(),
                "probe-runtime semantics (migration reference)", EvidenceRecord.Reliability.VERIFIED, null,
                "validation/runtime-final.json", null, "kernel.validation").evidenceId();
        put(dims, dim(ValidationDimension.RUNTIME_STARTUP, after.started() ? DimensionStatus.PASS
                : before.started() ? DimensionStatus.FAIL : DimensionStatus.NOT_COMPARED,
                after.started() ? "STARTED" : "DID_NOT_START", after.started() ? "Application started ("
                        + after.startupSeconds() + "s)" : "Application did not start: " + after.failure(), List.of(ev)));
        ProbeComparator.Comparison comparison = ProbeComparator.compare(before, after);
        session.artifacts.writeJson("validation", "probe-comparison.json", comparison);
        if (!comparison.compared()) {
            put(dims, dim(ValidationDimension.BEHAVIOR_PROBES, DimensionStatus.NOT_COMPARED, null, comparison.verdictText(),
                    List.of(ev)));
            put(dims, dim(ValidationDimension.OLD_NEW_DIFFERENTIAL, DimensionStatus.NOT_COMPARED, null,
                    comparison.verdictText(), List.of(ev)));
            return;
        }
        List<String> unexplained = new ArrayList<>();
        List<String> explained = new ArrayList<>();
        for (ProbeComparator.Row row : comparison.rows()) {
            if (row.same()) {
                continue;
            }
            String explanation = inputs.behaviourExplanations().get(row.name());
            if (explanation != null) {
                explained.add(row.name() + " (" + row.verdict() + "): " + explanation);
            } else {
                unexplained.add(row.name() + " (" + row.verdict() + " " + row.beforeStatus() + " -> " + row.afterStatus() + ")");
            }
        }
        DimensionStatus behaviour = !unexplained.isEmpty() ? DimensionStatus.FAIL
                : explained.isEmpty() ? DimensionStatus.PASS : DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES;
        String detail = !unexplained.isEmpty() ? "CHANGED_UNEXPLAINED" : explained.isEmpty() ? "EQUIVALENT"
                : "EQUIVALENT_WITH_EXPLAINED_DIFFS";
        put(dims, dim(ValidationDimension.BEHAVIOR_PROBES, behaviour, detail, comparison.verdictText()
                + (unexplained.isEmpty() ? "" : "; unexplained: " + unexplained)
                + (explained.isEmpty() ? "" : "; explained: " + explained), List.of(ev)));
        DimensionStatus differential = behaviour.failed() || tests.worse() ? DimensionStatus.FAIL
                : !tests.compared() ? DimensionStatus.NOT_COMPARED
                : behaviour == DimensionStatus.PASS ? DimensionStatus.PASS
                : DimensionStatus.PASS_WITH_EXPLAINED_DIFFERENCES;
        put(dims, dim(ValidationDimension.OLD_NEW_DIFFERENTIAL, differential, detail,
                "Behaviour: " + comparison.verdictText() + "; tests: " + tests.verdict(), List.of(ev)));
    }

    private BuildPort.BuildResult readBaselineBuild() {
        Path file = session.layout.area("baseline").resolve("baseline-build.json");
        return Files.isRegularFile(file) ? KernelJson.read(file, BuildPort.BuildResult.class) : null;
    }

    // ------------------------------------------------------------------ helpers

    /** Capability results merge conservatively: a FAIL from any producer wins, and PASS never overwrites a non-PASS. */
    private static void merge(Map<ValidationDimension, ValidationResult.DimensionResult> dims,
                              ValidationResult.DimensionResult incoming) {
        ValidationResult.DimensionResult existing = dims.get(incoming.dimension());
        if (existing == null || rank(incoming.status()) > rank(existing.status())) {
            dims.put(incoming.dimension(), incoming);
        }
    }

    private static void put(Map<ValidationDimension, ValidationResult.DimensionResult> dims,
                            ValidationResult.DimensionResult result) {
        merge(dims, result);
    }

    /** Higher rank is worse; merge keeps the worst reading. */
    private static int rank(DimensionStatus status) {
        return switch (status) {
            case NOT_APPLICABLE -> 0;
            case PASS -> 1;
            case PASS_WITH_EXPLAINED_DIFFERENCES -> 2;
            case NOT_RUN -> 3;
            case NOT_COMPARED -> 4;
            case TOOL_UNAVAILABLE -> 5;
            case INSUFFICIENT_EVIDENCE -> 6;
            case FAIL -> 7;
        };
    }

    private static ValidationResult.DimensionResult dim(ValidationDimension d, DimensionStatus status, String detail,
                                                        String summary, List<String> evidence) {
        return new ValidationResult.DimensionResult(d, status, detail, summary, evidence, "kernel");
    }
}
