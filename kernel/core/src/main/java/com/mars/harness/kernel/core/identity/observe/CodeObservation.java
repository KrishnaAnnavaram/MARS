package com.mars.harness.kernel.core.identity.observe;

import java.util.List;

/**
 * Everything a code-model adapter observed in one file, before any identity is decided.
 *
 * <p>This is language-neutral. A Java adapter reports classes, methods and statements; a COBOL or
 * PL/SQL adapter would report programs, paragraphs and statements through the same shape.
 * Observation keys are positional and scan-local. They are never persisted as identities.
 */
public record CodeObservation(String fileId, String path, String moduleId, String language,
                              List<ObservedUnit> units, List<ObservedSymbol> symbols,
                              List<ObservedStatement> statements, List<String> issues) {

    public CodeObservation {
        units = units == null ? List.of() : List.copyOf(units);
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        statements = statements == null ? List.of() : List.copyOf(statements);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static CodeObservation empty(String fileId, String path, String moduleId, String language,
                                        String issue) {
        return new CodeObservation(fileId, path, moduleId, language, List.of(), List.of(), List.of(),
                issue == null ? List.of() : List.of(issue));
    }
}
