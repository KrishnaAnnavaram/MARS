package com.mars.harness.kernel.core.identity.observe;

import java.util.List;

/**
 * A program unit as observed: a Java class, interface, record, enum or annotation type. Other
 * languages report their own kinds (COBOL program, PL/SQL package).
 *
 * @param key               scan-local key, the fully qualified name
 * @param memberSignatures  member signatures, used as structural evidence when the name changed
 */
public record ObservedUnit(String key, String kind, String name, String fqn, String packageName,
                           int lineStart, int lineEnd, List<String> memberSignatures,
                           List<String> annotations) {

    public ObservedUnit {
        memberSignatures = memberSignatures == null ? List.of() : List.copyOf(memberSignatures);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
