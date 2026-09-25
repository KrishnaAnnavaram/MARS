package com.mars.harness.kernel.core.identity.observe;

/**
 * A statement or AST-level executable unit as observed.
 *
 * <p>Compound statements (if, for, try, and so on) are observed by their <em>header</em>. Their
 * bodies are separate child statements. That is what lets {@code if (x) { a(); }} keep its
 * identity when {@code a()} becomes {@code b()}.
 *
 * @param key                scan-local key: {@code symbolKey/slotPath}
 * @param parentStatementKey enclosing statement's key, or null at the top level of a body
 * @param slot               which block of the parent holds it (b0 = then, b1 = else, …)
 * @param index              position among its siblings in that slot
 * @param normalizedText     comment-free, whitespace-normalised header text, already redacted
 * @param fingerprint        hash of node kind plus normalised text
 */
public record ObservedStatement(String key, String symbolKey, String parentStatementKey, String slot,
                                int index, String nodeKind, String normalizedText, String fingerprint,
                                int lineStart, int colStart, int lineEnd, int colEnd) {

    /** Sibling group: statements are aligned only against siblings in the same group. */
    public String groupKey() {
        return (parentStatementKey == null ? symbolKey : parentStatementKey) + "|" + slot;
    }
}
