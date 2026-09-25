package com.bootshift.core.domain;

/**
 * Harness exit codes (spec section 45). These are deliberately not collapsed: a policy block and a
 * required human decision are different operational outcomes.
 */
public enum ExitCode {

    /** Stage or run completed and all postconditions hold. */
    SUCCESS(0),

    /** A stage failed for a technical reason (tool crash, IO error, unparseable input). */
    STAGE_FAILURE(1),

    /** The harness structurally refuses: preconditions or invariants make the request meaningless. */
    STRUCTURED_REFUSAL(2),

    /** Migration cannot proceed as currently specified under active policy. */
    POLICY_BLOCK(3),

    /** Migration can proceed only after an authorized human decision is recorded. */
    HUMAN_DECISION_REQUIRED(4);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ExitCode fromCode(int code) {
        for (ExitCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown exit code: " + code);
    }
}
