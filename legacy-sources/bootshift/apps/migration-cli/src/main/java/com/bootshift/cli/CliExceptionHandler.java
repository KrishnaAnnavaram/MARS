package com.bootshift.cli;

import com.bootshift.core.domain.ExitCode;
import com.bootshift.core.domain.HarnessException;
import picocli.CommandLine;

/**
 * Maps harness exceptions onto the documented exit codes (spec section 45).
 *
 * <p>Exit code 3 (policy block) and exit code 4 (human decision required) are deliberately distinct:
 * one says the migration cannot proceed as specified, the other says it can proceed once an
 * authorized decision exists.
 */
public final class CliExceptionHandler implements CommandLine.IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception exception, CommandLine commandLine,
                                        CommandLine.ParseResult parseResult) {
        if (exception instanceof HarnessException harnessException) {
            String label = switch (harnessException.exitCode()) {
                case POLICY_BLOCK -> "POLICY BLOCK";
                case HUMAN_DECISION_REQUIRED -> "HUMAN DECISION REQUIRED";
                case STRUCTURED_REFUSAL -> "REFUSED";
                default -> "FAILED";
            };
            commandLine.getErr().println(label + ": " + harnessException.getMessage());
            return harnessException.exitCode().code();
        }
        commandLine.getErr().println("FAILED: " + exception);
        if (System.getenv("BOOTSHIFT_DEBUG") != null) {
            exception.printStackTrace(commandLine.getErr());
        }
        return ExitCode.STAGE_FAILURE.code();
    }
}
