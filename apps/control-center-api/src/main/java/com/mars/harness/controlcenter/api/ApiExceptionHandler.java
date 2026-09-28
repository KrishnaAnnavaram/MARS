package com.mars.harness.controlcenter.api;

import com.mars.harness.controlcenter.api.dto.ApiError;
import com.mars.harness.controlcenter.config.ControlCenterPaths;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.core.outcome.OutcomeCategory;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One error shape for every failure: {@code {code, message, run_id, correlation_id, details}}.
 * Engine refusals are passed through with their own reasons (they are the domain's words), with
 * server paths redacted.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ControlCenterPaths paths;

    public ApiExceptionHandler(ControlCenterPaths paths) {
        this.paths = paths;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return respond(e.code(), e.getMessage(), e.runId(), e.details());
    }

    @ExceptionHandler(HarnessOutcomeException.class)
    public ResponseEntity<ApiError> engine(HarnessOutcomeException e, HttpServletRequest request) {
        ApiErrorCode code = e.category() == OutcomeCategory.REFUSAL || e.category() == OutcomeCategory.AUTHORIZATION_MISSING
                ? ApiErrorCode.DECISION_REFUSED : ApiErrorCode.ENGINE_FAILURE;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome_category", e.category().name());
        if (!e.details().isEmpty()) {
            details.put("reasons", e.details().stream().map(paths::redact).toList());
        }
        if (code == ApiErrorCode.ENGINE_FAILURE) {
            LOG.error("Engine failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        }
        return respond(code, e.getMessage(), runIdOf(request), details);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList();
        return respond(ApiErrorCode.VALIDATION_FAILURE, "The request is invalid", runIdOf(request),
                Map.of("errors", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(ApiErrorCode.VALIDATION_FAILURE, "The request body could not be read", runIdOf(request), Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException e, HttpServletRequest request) {
        return respond(ApiErrorCode.FORBIDDEN, "Your role does not permit this action", runIdOf(request), Map.of());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientGone(AsyncRequestNotUsableException e) {
        LOG.debug("Streaming client disconnected: {}", e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> unexpected(RuntimeException e, HttpServletRequest request) {
        LOG.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ApiErrorCode.INTERNAL_ERROR, "Unexpected server error (correlation " + MDC.get("correlation_id") + ")",
                runIdOf(request), Map.of());
    }

    private ResponseEntity<ApiError> respond(ApiErrorCode code, String message, String runId, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).body(new ApiError(code.name(), paths.redact(message), runId,
                MDC.get("correlation_id"), details));
    }

    private static String runIdOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int i = uri.indexOf("/runs/");
        if (i < 0) {
            return null;
        }
        String rest = uri.substring(i + 6);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
