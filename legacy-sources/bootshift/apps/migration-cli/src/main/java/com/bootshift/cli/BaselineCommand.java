package com.bootshift.cli;

import com.bootshift.stages.stage04.BaselineStage;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code harness baseline --repo ./src} */
@CommandLine.Command(name = "baseline", description =
        "Agent 04: observe and cryptographically seal the original application")
final class BaselineCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @CommandLine.Option(names = "--skip-tests", description =
            "Skip the test dimension. The resulting baseline explicitly cannot support E3 claims.")
    boolean skipTests;

    @CommandLine.Option(names = "--skip-runtime", description =
            "Skip the runtime dimension. Every runtime observation becomes a recorded blind spot.")
    boolean skipRuntime;

    @Override
    public Integer call() {
        return StageRunner.run(new BaselineStage(!skipTests, !skipRuntime), options.context());
    }
}
