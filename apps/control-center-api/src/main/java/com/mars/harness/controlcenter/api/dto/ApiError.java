package com.mars.harness.controlcenter.api.dto;

import java.util.Map;

/** The one error shape of the API. {@code code} is stable; {@code message} is for people. */
public record ApiError(String code, String message, String runId, String correlationId, Map<String, Object> details) {
}
