package com.bootshift.core.domain;

/** Carries a structured exit code out of a stage so the CLI never has to guess. */
public class HarnessException extends RuntimeException {

    private final ExitCode exitCode;

    public HarnessException(ExitCode exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public HarnessException(ExitCode exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public ExitCode exitCode() {
        return exitCode;
    }

    public static HarnessException refusal(String message) {
        return new HarnessException(ExitCode.STRUCTURED_REFUSAL, message);
    }

    public static HarnessException block(String message) {
        return new HarnessException(ExitCode.POLICY_BLOCK, message);
    }

    public static HarnessException needsHuman(String message) {
        return new HarnessException(ExitCode.HUMAN_DECISION_REQUIRED, message);
    }

    public static HarnessException stageFailure(String message, Throwable cause) {
        return new HarnessException(ExitCode.STAGE_FAILURE, message, cause);
    }
}
