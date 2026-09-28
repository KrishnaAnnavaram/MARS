package com.mars.harness.controlcenter.api;

import java.util.Map;

/** A domain-level API error with a stable code. Messages must not carry absolute server paths. */
public final class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final String runId;
    private final transient Map<String, Object> details;

    public ApiException(ApiErrorCode code, String runId, String message) {
        this(code, runId, message, Map.of());
    }

    public ApiException(ApiErrorCode code, String runId, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.runId = runId;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApiErrorCode code() {
        return code;
    }

    public String runId() {
        return runId;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException runNotFound(String runId) {
        return new ApiException(ApiErrorCode.RUN_NOT_FOUND, runId, "No run " + runId);
    }
}
