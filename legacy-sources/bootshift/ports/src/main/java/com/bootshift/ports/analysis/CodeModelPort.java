package com.bootshift.ports.analysis;

import com.bootshift.core.graph.GraphNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Type-aware Java code model extraction (Agent 03, Agent 14).
 *
 * <p>Structural-only parsers may be used as a fallback but must report
 * {@link com.bootshift.core.graph.GraphNode.Attribution#UNRESOLVED} rather than pretending a
 * relationship was type-resolved. Agent 09 caps impact classification on unresolved attribution.
 */
public interface CodeModelPort {

    /** A declared type with everything the graph builder needs. */
    record TypeFacts(String fqn, String simpleName, String packageName, String kind, String filePath,
                     int lineStart, int lineEnd, List<String> annotations, String superType,
                     List<String> interfaces, List<MemberFacts> members, List<String> importedTypes,
                     List<String> referencedTypes, GraphNode.Attribution attribution,
                     Map<String, String> attributes) {
    }

    record MemberFacts(String name, String kind, String signature, String returnType,
                       List<String> parameterTypes, List<String> annotations, int lineStart, int lineEnd,
                       List<String> calls, List<String> usedTypes, GraphNode.Attribution attribution,
                       Map<String, String> attributes) {
    }

    record ParseIssue(String filePath, String severity, String detail) {
    }

    record AnalysisResult(List<TypeFacts> types, List<ParseIssue> issues, int filesParsed,
                          int filesFailed, double attributionRatio, String toolName, String toolVersion) {
    }

    String name();

    /**
     * Parses the given source roots. {@code classpath} entries let the adapter resolve types coming
     * from dependencies; when empty, external types resolve to UNRESOLVED and are labelled so.
     */
    AnalysisResult analyze(Path moduleRoot, List<Path> sourceRoots, List<Path> classpath);

    /**
     * Parses at the language level the module actually declares.
     *
     * <p>Parsing every repository at the newest level the parser supports is not a harmless default.
     * A construct the module's real level does not permit parses anyway, and a construct removed
     * after that level is silently accepted, so the graph describes a language the module is not
     * written in. The default delegates for adapters that cannot honour a level, which then say so
     * through {@link AnalysisResult#issues()}.
     */
    default AnalysisResult analyze(Path moduleRoot, List<Path> sourceRoots, List<Path> classpath,
                                   int javaRelease) {
        return analyze(moduleRoot, sourceRoots, classpath);
    }
}
