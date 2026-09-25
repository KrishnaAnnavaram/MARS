package com.bootshift.cli;

import com.bootshift.core.domain.StageResult;
import com.bootshift.stages.StageContext;
import com.bootshift.stages.bootstrap.RunBootstrap;
import com.bootshift.stages.stage01.InventoryStage;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * {@code harness inventory --repo ./src}
 *
 * <p>Runs bootstrap first when the run has no workspace yet. Bootstrap is infrastructure preflight,
 * not an agent, so it is invisible in the agent catalog but always precedes Agent 01.
 */
@CommandLine.Command(name = "inventory", description =
        "Agent 01: discover the repository and allocate permanent file identities")
final class InventoryCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        StageContext context = options.context();
        if (context.run().output().resolveLatestDir(RunBootstrap.OUTPUT_DIR) == null) {
            StageResult bootstrap = new RunBootstrap(context).execute();
            StageRunner.print(bootstrap, context);
            if (!bootstrap.succeeded()) {
                return bootstrap.exitCode().code();
            }
        }
        return StageRunner.run(new InventoryStage(), context);
    }
}
