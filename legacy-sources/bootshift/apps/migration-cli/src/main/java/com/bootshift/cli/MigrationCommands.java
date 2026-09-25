package com.bootshift.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.StageResult;
import com.bootshift.stages.EdgeSupport;
import com.bootshift.stages.PipelineOrchestrator;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.StageSupport;
import com.bootshift.stages.stage18.ApprovalStage;
import com.bootshift.stages.stage19.BundleExporter;
import com.bootshift.stages.stage19.EvidenceStage;
import com.bootshift.stages.stage20.ProvenanceStage;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** Mutating and reporting commands (Agents 12 to 20) plus the whole-pipeline convenience command. */
final class MigrationCommands {

    private MigrationCommands() {
    }
}

@CommandLine.Command(name = "migrate", description =
        "Agents 12-17: execute the frozen migration edges through the mutation gateway")
final class MigrateCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Option(names = "--edge", description =
            "Run one specific edge instead of every edge in the frozen plan")
    String edge;

    @Override
    public Integer call() {
        StageContext context = options.context();
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(context,
                result -> StageRunner.print(result, context));
        if (edge != null) {
            for (Stage stage : orchestrator.edgeStages(edge)) {
                StageResult result = com.bootshift.stages.StageExecutor.run(stage, context);
                StageRunner.print(result, context);
                if (!result.succeeded()) {
                    return result.exitCode().code();
                }
            }
            // A single-edge migration reaches EDGE_COMPLETE exactly as the orchestrated route does.
            // It previously did not, so driving a run one edge at a time left it permanently short
            // of the state approval requires, with no way to finish.
            PipelineOrchestrator.completeEdge(context, edge);
            System.out.println("  edge " + edge + " complete");
            return ExitCode.SUCCESS.code();
        }
        PipelineOrchestrator.RunOutcome outcome = orchestrator.runEdges();
        System.out.println("  " + outcome.summary());
        return outcome.exitCode().code();
    }
}

@CommandLine.Command(name = "validate", description =
        "Re-run the validation stages for an edge without re-transforming")
final class ValidateCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Option(names = "--edge", required = true, description = "Edge id to validate")
    String edge;

    @Override
    public Integer call() {
        StageContext context = options.context();
        List<Stage> stages = List.of(
                new com.bootshift.stages.stage14.GraphDiffStage(edge),
                new com.bootshift.stages.stage15.TestValidationStage(edge),
                new com.bootshift.stages.stage16.RuntimeValidationStage(edge),
                new com.bootshift.stages.stage17.DifferentialStage(edge));
        for (Stage stage : stages) {
            StageResult result = com.bootshift.stages.StageExecutor.run(stage, context);
            StageRunner.print(result, context);
            if (!result.succeeded()) {
                return result.exitCode().code();
            }
        }
        // Re-validating an edge that already transformed brings it back to EDGE_COMPLETE, so a run
        // recovered after a validation failure can be finished without re-transforming.
        PipelineOrchestrator.completeEdge(context, edge);
        return ExitCode.SUCCESS.code();
    }
}

@CommandLine.Command(name = "approve", description =
        "Agent 18: raise approval gates, or record an authorized decision")
final class ApproveCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Option(names = "--request", description = "Request id to decide")
    String request;

    @CommandLine.Option(names = "--actor", description = "Name of the person deciding")
    String actor;

    @CommandLine.Option(names = "--role", description = "Role of the person deciding")
    String role;

    @CommandLine.Option(names = "--verdict", description =
            "APPROVED, REJECTED or DEFERRED. Default: ${DEFAULT-VALUE}")
    String verdict = "APPROVED";

    @CommandLine.Option(names = "--rationale", description =
            "Why this decision is being made. An empty rationale is refused.")
    String rationale;

    @Override
    public Integer call() {
        StageContext context = options.context();
        ApprovalStage approval = new ApprovalStage();
        if (request == null) {
            return StageRunner.run(approval, context);
        }
        approval.bind(context);
        var decision = approval.record(request, actor, role,
                com.bootshift.ports.approval.ApprovalPort.Verdict.valueOf(
                        verdict.toUpperCase(java.util.Locale.ROOT)),
                rationale, List.of(), context.policy().version());
        System.out.println();
        System.out.println("  Recorded " + decision.decisionId() + " for " + request);
        System.out.println("  verdict   " + decision.verdict());
        System.out.println("  actor     " + decision.actor() + " (" + decision.role() + ")");
        System.out.println("  integrity " + decision.integrityHash());
        System.out.println("  note      integrity_hash detects modification of this record; it is not");
        System.out.println("            a signature and does not authenticate the actor.");
        System.out.println();
        return StageRunner.run(new ApprovalStage(), context);
    }
}

@CommandLine.Command(name = "report", description =
        "Agents 19-20: seal the evidence manifest, write the report and build the provenance graph")
final class ReportCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        StageContext context = options.context();
        StageResult evidence = com.bootshift.stages.StageExecutor.run(new EvidenceStage(), context);
        StageRunner.print(evidence, context);
        if (evidence.exitCode() == ExitCode.STAGE_FAILURE) {
            return evidence.exitCode().code();
        }
        StageResult provenance = com.bootshift.stages.StageExecutor.run(new ProvenanceStage(), context);
        StageRunner.print(provenance, context);
        return evidence.succeeded() ? provenance.exitCode().code() : evidence.exitCode().code();
    }
}

@CommandLine.Command(name = "export", description =
        "Export the validated migration bundle as a repository or a patch series")
final class ExportCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Option(names = "--format", description =
            "repo or patch. Default: ${DEFAULT-VALUE}")
    String format = "repo";

    @CommandLine.Option(names = "--to", description =
            "Destination directory. Default: <output>/validated-migration")
    Path destination;

    @CommandLine.Option(names = "--diagnostic", description =
            "Export an incomplete run for inspection. The bundle is labelled NOT VALIDATED. "
                    + "Without this flag, export refuses anything but MIGRATION_COMPLETE.")
    boolean diagnostic;

    @Override
    public Integer call() {
        StageContext context = options.context();
        Path target = destination == null
                ? options.outputRoot().resolve("validated-migration") : destination;
        BundleExporter.Bundle bundle = new BundleExporter(context).export(target, format,
                diagnostic ? BundleExporter.Mode.DIAGNOSTIC : BundleExporter.Mode.VALIDATED);
        System.out.println();
        System.out.println(bundle.validated()
                ? "  Validated migration bundle"
                : "  DIAGNOSTIC bundle - NOT a validated migration (status "
                        + bundle.status() + ")");
        System.out.println("  location        " + bundle.root());
        System.out.println("  exported tree   " + bundle.exportedTreeHash());
        System.out.println("  recorded tree   " + bundle.recordedTreeHash());
        System.out.println("  hashes match    " + bundle.hashesMatch());
        System.out.println("  patch series    " + bundle.patchCount() + " patch(es)");
        System.out.println("  SBOM components " + bundle.sbomComponents());
        System.out.println("  final build fp  " + bundle.finalBuildModelFingerprint());
        bundle.licenseFindings().forEach(f -> System.out.println("  license         " + f));
        System.out.println();
        return bundle.hashesMatch() ? 0 : ExitCode.POLICY_BLOCK.code();
    }
}

@CommandLine.Command(name = "run", description =
        "Run the complete pipeline: analysis, migration edges, approval, evidence and provenance")
final class RunCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Option(names = "--target", description =
            "Landing target or 'auto'. Default: ${DEFAULT-VALUE}")
    String target = "auto";

    @CommandLine.Option(names = "--analysis-only", description =
            "Stop after the frozen plan. Nothing is mutated.")
    boolean analysisOnly;

    @Override
    public Integer call() {
        StageContext context = options.context();
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(context,
                result -> StageRunner.print(result, context));
        PipelineOrchestrator.RunOutcome outcome = orchestrator.runAll(target, !analysisOnly);
        System.out.println("  " + outcome.summary());
        System.out.println();
        return outcome.exitCode().code();
    }
}

@CommandLine.Command(name = "stages", description = "List the stage catalog and its contracts")
final class StagesCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println();
        System.out.printf("  %-20s %-9s %-4s %s%n", "STAGE", "MUTATING", "AI", "PURPOSE");
        for (Stage stage : PipelineOrchestrator.catalog()) {
            System.out.printf("  %-20s %-9s %-4s %s%n", stage.id(),
                    stage.mutating() ? "yes" : "no", stage.aiAssisted() ? "opt" : "no",
                    stage.purpose());
        }
        System.out.println();
        System.out.println("  Only 12-transformation and 13-build-repair may mutate application source,");
        System.out.println("  and both do so exclusively through the FileMutationGateway.");
        System.out.println();
        return 0;
    }
}

@CommandLine.Command(name = "verify", description =
        "Verify the change ledger and the evidence manifest of a run")
final class VerifyCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        StageContext context = options.context();
        var ledger = EdgeSupport.verifyLedger(context);
        System.out.println();
        System.out.println("  Change ledger");
        System.out.println("  events    " + ledger.verifiedEvents());
        System.out.println("  head      " + ledger.computedHead());
        System.out.println("  recorded  " + ledger.recordedHead());
        System.out.println("  valid     " + ledger.valid());
        ledger.violations().forEach(v -> System.out.println("  ! " + v));

        Path manifest = context.run().output().latestArtifactPath("19-evidence",
                "evidence-manifest.json");
        if (manifest == null) {
            System.out.println();
            System.out.println("  No evidence manifest yet. Run: bootshift report");
            System.out.println();
            return ledger.valid() ? 0 : ExitCode.POLICY_BLOCK.code();
        }
        var verification = com.bootshift.core.evidence.EvidenceManifest.verify(manifest,
                context.evidenceStore().root());
        System.out.println();
        System.out.println("  Evidence manifest");
        System.out.println("  computed  " + verification.computedHash());
        System.out.println("  recorded  " + verification.recordedHash());
        System.out.println("  valid     " + verification.valid());
        long verified = verification.entryResults().stream()
                .filter(r -> "VERIFIED".equals(r.outcome())).count();
        long archived = verification.entryResults().stream()
                .filter(r -> "ARCHIVED".equals(r.outcome())).count();
        long tampered = verification.entryResults().stream()
                .filter(r -> "TAMPERED".equals(r.outcome())).count();
        System.out.println("  entries   " + verified + " verified, " + archived + " archived, "
                + tampered + " tampered");
        verification.violations().forEach(v -> System.out.println("  ! " + v));
        System.out.println();
        return ledger.valid() && verification.valid() ? 0 : ExitCode.POLICY_BLOCK.code();
    }
}
