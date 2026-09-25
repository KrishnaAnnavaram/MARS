package com.bootshift.cli;

import com.bootshift.core.domain.StageResult;
import com.bootshift.stages.Stage;
import com.bootshift.stages.StageContext;

import java.nio.file.Path;
import java.util.Map;

/**
 * Renders a stage result for humans while the artifacts stay rich JSON (spec section 54).
 */
final class StageRunner {

    private StageRunner() {
    }

    static int run(Stage stage, StageContext context) {
        StageResult result = stage.execute(context);
        print(result, context);
        return result.exitCode().code();
    }

    static void print(StageResult result, StageContext context) {
        System.out.println();
        System.out.println("  " + result.stageId() + "  [" + result.exitCode().name() + "]");
        System.out.println("  run: " + context.run().runId());
        System.out.println("  " + result.summary());
        if (!result.messages().isEmpty()) {
            System.out.println();
            result.messages().forEach(m -> System.out.println("  ! " + m));
        }
        if (!result.artifacts().isEmpty()) {
            System.out.println();
            System.out.println("  artifacts:");
            for (Map.Entry<String, Path> entry : result.artifacts().entrySet()) {
                System.out.println("    " + entry.getValue());
            }
        }
        System.out.println();
    }
}
