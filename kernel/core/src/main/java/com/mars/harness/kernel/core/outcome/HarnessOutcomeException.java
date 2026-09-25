package com.mars.harness.kernel.core.outcome;

import java.util.List;

/** A workflow condition with an explicit category. Never used for programming errors. */
public class HarnessOutcomeException extends RuntimeException {

    private final OutcomeCategory category;
    private final List<String> details;

    public HarnessOutcomeException(OutcomeCategory category, String message) {
        this(category, message, List.of());
    }

    public HarnessOutcomeException(OutcomeCategory category, String message, List<String> details) {
        super(category + ": " + message);
        this.category = category;
        this.details = List.copyOf(details);
    }

    public OutcomeCategory category() {
        return category;
    }

    public List<String> details() {
        return details;
    }
}
