package com.bootshift.cli;

import com.bootshift.stages.stage05.CompatibilityStage;
import com.bootshift.stages.stage06.TargetResolverStage;
import com.bootshift.stages.stage07.DocumentationStage;
import com.bootshift.stages.stage08.KnowledgeStage;
import com.bootshift.stages.stage09.AccuracyHarness;
import com.bootshift.stages.stage09.ImpactStage;
import com.bootshift.stages.stage10.CharacterizationStage;
import com.bootshift.stages.stage11.PlannerStage;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Analysis-half commands (Agents 05 to 11).
 *
 * <p>Each is independently runnable, which is what R24 requires. They are grouped in one file
 * because they are one-line delegations; the behaviour lives entirely in the stages.
 */
final class AnalysisCommands {

    private AnalysisCommands() {
    }
}

@CommandLine.Command(name = "compatibility", description =
        "Agent 05: build the Tier-1 compatibility and lifecycle registry")
final class CompatibilityCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new CompatibilityStage(), options.context());
    }
}

@CommandLine.Command(name = "resolve-target", description =
        "Agent 06: choose the landing target and the transit checkpoints that reach it")
final class ResolveTargetCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Option(names = "--target", description =
            "Landing target: a version line such as 3.4, or 'auto' for the highest safe supported "
                    + "stable GA target. Default: ${DEFAULT-VALUE}")
    String target = "auto";

    @Override
    public Integer call() {
        return StageRunner.run(new TargetResolverStage(target), options.context());
    }
}

@CommandLine.Command(name = "documentation", description =
        "Agent 07: fetch and content-address the authoritative migration documents")
final class DocumentationCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new DocumentationStage(), options.context());
    }
}

@CommandLine.Command(name = "knowledge", description =
        "Agent 08: derive verified migration facts from documentation plus artifact reality")
final class KnowledgeCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new KnowledgeStage(), options.context());
    }
}

@CommandLine.Command(name = "impact", description =
        "Agent 09: determine which parts of this repository the verified facts affect")
final class ImpactCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new ImpactStage(), options.context());
    }
}

@CommandLine.Command(name = "evaluate-impact", description =
        "Measure impact-analyzer precision and recall against held-out fixtures")
final class EvaluateImpactCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        AccuracyHarness.Result result = new AccuracyHarness()
                .evaluate(options.harnessRoot.toAbsolutePath().normalize());
        System.out.println();
        System.out.println("  Impact analyzer accuracy");
        System.out.println("  evaluated        " + result.evaluated());
        System.out.println("  held-out fixtures " + result.fixtures());
        System.out.println("  true positives   " + result.truePositives());
        System.out.println("  false positives  " + result.falsePositives());
        System.out.println("  false negatives  " + result.falseNegatives());
        System.out.println("  precision        " + result.precision());
        System.out.println("  recall           " + result.recall());
        System.out.println("  f1               " + result.f1());
        result.notes().forEach(n -> System.out.println("  ! " + n));
        System.out.println();
        return result.evaluated() ? 0 : 2;
    }
}

@CommandLine.Command(name = "characterize", description =
        "Agent 10: establish behavioural contracts before migration-sensitive code changes")
final class CharacterizeCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new CharacterizationStage(), options.context());
    }
}

@CommandLine.Command(name = "plan", description =
        "Agent 11: plan and freeze how the frozen migration path will be executed")
final class PlanCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new PlannerStage(), options.context());
    }
}
