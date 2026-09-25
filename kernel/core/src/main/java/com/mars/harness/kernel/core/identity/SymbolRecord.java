package com.mars.harness.kernel.core.identity;

import java.util.ArrayList;
import java.util.List;

/**
 * SYMBOL_ID: a method, constructor, field, initializer or endpoint (spec §7.2, §7.5).
 *
 * <p>Unlike the graph-build-scoped symbol IDs Bootshift's GraphBuilder allocates on every build,
 * this identity persists across re-indexing. Name, FQN, signature and path are attributes.
 */
public final class SymbolRecord extends TrackedIdentity {

    public String symbolId;
    public String programUnitId;
    /** For an ENDPOINT, the handler method's SYMBOL_ID. */
    public String parentSymbolId;
    public String moduleId;
    public String kind;
    public String name;
    public String signature;
    public String fqn;
    public String baselineSignature;
    public List<String> annotations = new ArrayList<>();
    /** Current ordered body fingerprints; structural evidence for the next reattachment. */
    public List<String> statementFingerprints = new ArrayList<>();

    @Override
    public String id() {
        return symbolId;
    }
}
