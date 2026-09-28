package com.mars.harness.controlcenter.api;

import org.springframework.http.HttpStatus;

/** Every error the Control Center API returns, with its HTTP status. */
public enum ApiErrorCode {
    RUN_NOT_FOUND(HttpStatus.NOT_FOUND),
    FINDING_NOT_FOUND(HttpStatus.NOT_FOUND),
    PROPOSAL_NOT_FOUND(HttpStatus.NOT_FOUND),
    EVIDENCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** The run is not in a state where this command applies. */
    INVALID_STATE(HttpStatus.CONFLICT),
    /** The decision was prepared against an assessment that has since changed. */
    STALE_ASSESSMENT(HttpStatus.CONFLICT),
    /** The proposal no longer hashes to what the reviewer saw. */
    PROPOSAL_HASH_MISMATCH(HttpStatus.CONFLICT),
    /** The migration plan no longer hashes to what the reviewer saw. */
    PLAN_HASH_MISMATCH(HttpStatus.CONFLICT),
    /** The proposal is no longer awaiting a decision (applied, rejected, stale, …). */
    STALE_PROPOSAL(HttpStatus.CONFLICT),
    /** A decision for this gate or proposal already exists; superseding it must name it. */
    DECISION_ALREADY_RECORDED(HttpStatus.CONFLICT),
    /** Another command is being executed for this run, or the run is being advanced. */
    RUN_BUSY(HttpStatus.CONFLICT),
    /** The engine refused the decision (for example a reserved machine actor, or an empty rationale). */
    DECISION_REFUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    VALIDATION_FAILURE(HttpStatus.UNPROCESSABLE_ENTITY),
    /** A repository or input path outside every configured repository root. */
    PATH_NOT_ALLOWED(HttpStatus.FORBIDDEN),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    INTEGRITY_FAILURE(HttpStatus.CONFLICT),
    ENGINE_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ApiErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
