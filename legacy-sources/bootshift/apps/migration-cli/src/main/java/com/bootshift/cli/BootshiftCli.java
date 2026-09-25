package com.bootshift.cli;

import picocli.CommandLine;

/**
 * Bootshift command line entry point.
 *
 * <p>The CLI contains no migration semantics. It parses options, builds a run context and delegates
 * to stages, exactly as R24 requires: deleting this class would not delete any stage logic.
 */
@CommandLine.Command(
        name = "bootshift",
        aliases = {"bsh"},
        mixinStandardHelpOptions = true,
        version = "Bootshift 1.0.0",
        description = "Evidence-driven, strict-OSS Spring Boot migration harness.",
        subcommands = {
                InventoryCommand.class,
                ResolveBuildCommand.class,
                GraphCommand.class,
                BaselineCommand.class,
                CompatibilityCommand.class,
                ResolveTargetCommand.class,
                DocumentationCommand.class,
                KnowledgeCommand.class,
                ImpactCommand.class,
                EvaluateImpactCommand.class,
                CharacterizeCommand.class,
                PlanCommand.class,
                MigrateCommand.class,
                ValidateCommand.class,
                ApproveCommand.class,
                ReportCommand.class,
                ExportCommand.class,
                RunCommand.class,
                VerifyCommand.class,
                StagesCommand.class,
                LineageCommand.class,
                ExplainCommand.class,
                EvidenceCommand.class,
                GapsCommand.class,
                BlindSpotsCommand.class,
                CommandLine.HelpCommand.class
        })
public final class BootshiftCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BootshiftCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler(new CliExceptionHandler())
                .execute(args);
        System.exit(exitCode);
    }
}
