package com.mars.harness.kernel.core.identity;

import java.util.List;
import java.util.Map;

/**
 * Explicit node mappings asserted by a transformation provider (spec §7.4 step 1).
 *
 * <p>A provider that renamed a class, changed a method signature or rewrote a statement knows which
 * old node became which new node, and says so here. The reattacher still verifies that the named
 * target exists in the new observation. A hint pointing at nothing is ignored and reported, never
 * trusted blindly.
 *
 * @param unitRenames      old FQN to new FQN
 * @param symbolRenames    {@code oldFqn#oldSignature} to new signature
 * @param statementRewrites STATEMENT_ID to the new normalised text the provider produced for it
 */
public record ProviderHints(Map<String, String> unitRenames, Map<String, String> symbolRenames,
                            Map<String, String> statementRewrites) {

    public ProviderHints {
        unitRenames = unitRenames == null ? Map.of() : Map.copyOf(unitRenames);
        symbolRenames = symbolRenames == null ? Map.of() : Map.copyOf(symbolRenames);
        statementRewrites = statementRewrites == null ? Map.of() : Map.copyOf(statementRewrites);
    }

    public static ProviderHints none() {
        return new ProviderHints(Map.of(), Map.of(), Map.of());
    }

    public static ProviderHints merge(List<ProviderHints> hints) {
        java.util.Map<String, String> units = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> syms = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> stmts = new java.util.LinkedHashMap<>();
        for (ProviderHints h : hints) {
            if (h != null) {
                units.putAll(h.unitRenames());
                syms.putAll(h.symbolRenames());
                stmts.putAll(h.statementRewrites());
            }
        }
        return new ProviderHints(units, syms, stmts);
    }
}
