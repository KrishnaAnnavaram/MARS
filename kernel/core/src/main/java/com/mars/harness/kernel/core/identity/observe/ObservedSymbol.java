package com.mars.harness.kernel.core.identity.observe;

import java.util.List;

/**
 * A symbol as observed: method, constructor, field, initializer or endpoint.
 *
 * @param key                   scan-local key: {@code unitKey#signature}, or {@code ENDPOINT:VERB path}
 * @param parentSymbolKey       for an endpoint, the handler method's key; null otherwise
 * @param statementFingerprints ordered fingerprints of the body statements. They are the
 *                              structural evidence for recognising a renamed or moved method.
 */
public record ObservedSymbol(String key, String unitKey, String parentSymbolKey, String kind,
                             String name, String signature, int lineStart, int lineEnd,
                             List<String> annotations, List<String> statementFingerprints) {

    public ObservedSymbol {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        statementFingerprints = statementFingerprints == null ? List.of()
                : List.copyOf(statementFingerprints);
    }
}
