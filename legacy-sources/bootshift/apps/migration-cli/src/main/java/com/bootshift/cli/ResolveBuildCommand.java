package com.bootshift.cli;

import com.bootshift.stages.stage02.BuildResolverStage;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/** {@code harness resolve-build --repo ./src} */
@CommandLine.Command(name = "resolve-build", description =
        "Agent 02: obtain the authoritative effective build model from Maven or Gradle")
final class ResolveBuildCommand implements Callable<Integer> {

    @CommandLine.Mixin
    CommonOptions options = new CommonOptions();

    @Override
    public Integer call() {
        return StageRunner.run(new BuildResolverStage(), options.context());
    }
}
