package com.mars.harness.kernel.core.identity;

/**
 * STATEMENT_ID: an allocated, stable identity for a statement or AST-level executable unit
 * (spec §7.3).
 *
 * <p>The line number is not the identity and neither is the content hash. A statement can change
 * its contents and keep its identity, in which case a new version is appended:
 *
 * <pre>
 * STMT-001  v1  repository.save(employee);
 *           v2  repository.save(sanitize(employee));
 * </pre>
 */
public final class StatementRecord extends TrackedIdentity {

    public String statementId;
    public String parentSymbolId;
    /** Enclosing statement, or null at the top level of the body. */
    public String parentStatementId;
    public String slot;
    public int index;
    public String nodeKind;
    public String baselineFingerprint;
    public String currentFingerprint;
    /** Normalised, redacted text of the current version. */
    public String currentText;

    @Override
    public String id() {
        return statementId;
    }
}
