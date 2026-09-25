package com.bootshift.adapters.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.bootshift.core.graph.GraphNode;
import com.bootshift.ports.analysis.CodeModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JavaParser-based code model extraction with optional symbol solving.
 *
 * <p>Attribution is reported honestly. When a dependency jar is on the supplied classpath the symbol
 * solver resolves the call target and the fact is RESOLVED; when it is not, the syntactic fact is
 * still recorded but marked UNRESOLVED so Agent 09 caps its classification at POSSIBLY_AFFECTED
 * instead of asserting a definite impact from a guess.
 */
public final class JavaParserCodeModelAdapter implements CodeModelPort {

    private static final Logger LOG = LoggerFactory.getLogger(JavaParserCodeModelAdapter.class);

    private static final String TOOL = "javaparser-symbol-solver";
    private static final String VERSION = "3.26.2";

    @Override
    public String name() {
        return TOOL;
    }

    @Override
    public AnalysisResult analyze(Path moduleRoot, List<Path> sourceRoots, List<Path> classpath) {
        // 21 only as a fallback for callers that cannot say. Every pipeline caller does say.
        return analyze(moduleRoot, sourceRoots, classpath, 21);
    }

    @Override
    public AnalysisResult analyze(Path moduleRoot, List<Path> sourceRoots, List<Path> classpath,
                                  int javaRelease) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        for (Path sourceRoot : sourceRoots) {
            if (Files.isDirectory(sourceRoot)) {
                typeSolver.add(new JavaParserTypeSolver(sourceRoot));
            }
        }
        int jarsAttached = 0;
        for (Path jar : classpath) {
            try {
                if (Files.isRegularFile(jar) && jar.toString().endsWith(".jar")) {
                    typeSolver.add(new JarTypeSolver(jar));
                    jarsAttached++;
                }
            } catch (IOException e) {
                LOG.debug("Cannot attach {} to the type solver: {}", jar, e.getMessage());
            }
        }

        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(languageLevelFor(javaRelease))
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(configuration);

        List<TypeFacts> types = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>();
        int parsed = 0;
        int failed = 0;
        int resolvedFacts = 0;
        int totalFacts = 0;

        for (Path sourceRoot : sourceRoots) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            List<Path> javaFiles;
            try (var stream = Files.walk(sourceRoot)) {
                javaFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                issues.add(new ParseIssue(sourceRoot.toString(), "ERROR",
                        "Cannot enumerate sources: " + e.getMessage()));
                continue;
            }

            for (Path file : javaFiles) {
                try {
                    ParseResult<CompilationUnit> result = parser.parse(file);
                    if (!result.isSuccessful() || result.getResult().isEmpty()) {
                        failed++;
                        result.getProblems().stream().limit(3).forEach(p ->
                                issues.add(new ParseIssue(file.toString(), "ERROR", p.getMessage())));
                        continue;
                    }
                    parsed++;
                    CompilationUnit unit = result.getResult().get();
                    String packageName = unit.getPackageDeclaration()
                            .map(p -> p.getNameAsString()).orElse("");
                    List<String> imports = unit.getImports().stream()
                            .map(i -> i.getNameAsString() + (i.isAsterisk() ? ".*" : ""))
                            .toList();

                    for (TypeDeclaration<?> declaration : unit.getTypes()) {
                        Extraction extraction = extractType(declaration, packageName, file, imports);
                        types.add(extraction.facts());
                        resolvedFacts += extraction.resolved();
                        totalFacts += extraction.total();
                    }
                } catch (IOException e) {
                    failed++;
                    issues.add(new ParseIssue(file.toString(), "ERROR", "IO failure: " + e.getMessage()));
                } catch (RuntimeException e) {
                    failed++;
                    issues.add(new ParseIssue(file.toString(), "ERROR",
                            "Parser failure: " + e.getClass().getSimpleName() + " " + e.getMessage()));
                }
            }
        }

        double ratio = totalFacts == 0 ? 1.0 : (double) resolvedFacts / totalFacts;
        LOG.debug("Analyzed {} ({} files, {} jars on solver classpath, attribution {})",
                moduleRoot, parsed, jarsAttached, ratio);
        return new AnalysisResult(types, issues, parsed, failed, ratio, TOOL, VERSION);
    }

    private record Extraction(TypeFacts facts, int resolved, int total) {
    }

    private Extraction extractType(TypeDeclaration<?> declaration, String packageName, Path file,
                                   List<String> imports) {
        String simpleName = declaration.getNameAsString();
        String fqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        String kind = kindOf(declaration);

        List<String> annotations = declaration.getAnnotations().stream()
                .map(this::renderAnnotation).toList();

        String superType = null;
        List<String> interfaces = new ArrayList<>();
        if (declaration instanceof ClassOrInterfaceDeclaration cid) {
            if (!cid.getExtendedTypes().isEmpty()) {
                superType = cid.getExtendedTypes().get(0).getNameAsString();
            }
            cid.getImplementedTypes().forEach(t -> interfaces.add(t.getNameAsString()));
            if (cid.isInterface() && !cid.getExtendedTypes().isEmpty()) {
                cid.getExtendedTypes().forEach(t -> interfaces.add(t.getNameAsString()));
                superType = null;
            }
        } else if (declaration instanceof EnumDeclaration ed) {
            ed.getImplementedTypes().forEach(t -> interfaces.add(t.getNameAsString()));
        } else if (declaration instanceof RecordDeclaration rd) {
            rd.getImplementedTypes().forEach(t -> interfaces.add(t.getNameAsString()));
        }

        Set<String> referencedTypes = new LinkedHashSet<>();
        declaration.findAll(ClassOrInterfaceType.class)
                .forEach(t -> referencedTypes.add(t.getNameAsString()));

        List<MemberFacts> members = new ArrayList<>();
        int resolved = 0;
        int total = 0;

        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof MethodDeclaration method) {
                MemberExtraction extracted = extractMethod(method);
                members.add(extracted.facts());
                resolved += extracted.resolved();
                total += extracted.total();
            } else if (member instanceof ConstructorDeclaration constructor) {
                members.add(new MemberFacts(constructor.getNameAsString(), "CONSTRUCTOR",
                        constructor.getDeclarationAsString(false, false, true), null,
                        constructor.getParameters().stream().map(p -> p.getTypeAsString()).toList(),
                        constructor.getAnnotations().stream().map(this::renderAnnotation).toList(),
                        line(constructor.getBegin().map(p -> p.line).orElse(0)),
                        line(constructor.getEnd().map(p -> p.line).orElse(0)),
                        List.of(), List.of(), GraphNode.Attribution.RESOLVED, Map.of()));
            } else if (member instanceof FieldDeclaration field) {
                field.getVariables().forEach(variable -> members.add(new MemberFacts(
                        variable.getNameAsString(), "FIELD", variable.getTypeAsString(),
                        variable.getTypeAsString(), List.of(),
                        field.getAnnotations().stream().map(this::renderAnnotation).toList(),
                        line(field.getBegin().map(p -> p.line).orElse(0)),
                        line(field.getEnd().map(p -> p.line).orElse(0)),
                        List.of(), List.of(variable.getTypeAsString()),
                        GraphNode.Attribution.RESOLVED, Map.of())));
            }
        }

        GraphNode.Attribution attribution = total == 0 || resolved == total
                ? GraphNode.Attribution.RESOLVED
                : (resolved == 0 ? GraphNode.Attribution.UNRESOLVED : GraphNode.Attribution.AMBIGUOUS);

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("declared_annotations", String.join(",", annotations));

        TypeFacts facts = new TypeFacts(fqn, simpleName, packageName, kind,
                file.toString().replace((char) 92, '/'),
                declaration.getBegin().map(p -> p.line).orElse(0),
                declaration.getEnd().map(p -> p.line).orElse(0),
                annotations, superType, interfaces, members, imports,
                new ArrayList<>(referencedTypes), attribution, attributes);
        return new Extraction(facts, resolved, total);
    }

    private record MemberExtraction(MemberFacts facts, int resolved, int total) {
    }

    private MemberExtraction extractMethod(MethodDeclaration method) {
        List<String> calls = new ArrayList<>();
        int resolved = 0;
        int total = 0;

        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            total++;
            Optional<String> target = resolveCall(call);
            if (target.isPresent()) {
                resolved++;
                calls.add(target.get());
            } else {
                // Syntactic fallback: recorded, but the type is unknown so it is prefixed.
                calls.add("?." + call.getNameAsString());
            }
        }
        for (ObjectCreationExpr creation : method.findAll(ObjectCreationExpr.class)) {
            calls.add(creation.getTypeAsString() + ".<init>");
        }

        Set<String> usedTypes = new LinkedHashSet<>();
        method.findAll(ClassOrInterfaceType.class).forEach(t -> usedTypes.add(t.getNameAsString()));

        GraphNode.Attribution attribution = total == 0 ? GraphNode.Attribution.RESOLVED
                : (resolved == total ? GraphNode.Attribution.RESOLVED
                : (resolved == 0 ? GraphNode.Attribution.UNRESOLVED : GraphNode.Attribution.AMBIGUOUS));

        MemberFacts facts = new MemberFacts(method.getNameAsString(), "METHOD",
                method.getDeclarationAsString(false, false, true), method.getTypeAsString(),
                method.getParameters().stream().map(p -> p.getTypeAsString()).toList(),
                method.getAnnotations().stream().map(this::renderAnnotation).toList(),
                line(method.getBegin().map(p -> p.line).orElse(0)),
                line(method.getEnd().map(p -> p.line).orElse(0)),
                calls, new ArrayList<>(usedTypes), attribution, Map.of());
        return new MemberExtraction(facts, resolved, total);
    }

    private Optional<String> resolveCall(MethodCallExpr call) {
        try {
            ResolvedMethodDeclaration declaration = call.resolve();
            return Optional.of(declaration.declaringType().getQualifiedName() + "." + declaration.getName());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String renderAnnotation(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        if (annotation.isMarkerAnnotationExpr()) {
            return name;
        }
        return name + "(" + annotation.getChildNodes().stream()
                .skip(1)
                .map(Object::toString)
                .reduce((a, b) -> a + "," + b)
                .orElse("") + ")";
    }

    private static String kindOf(TypeDeclaration<?> declaration) {
        if (declaration instanceof EnumDeclaration) {
            return "ENUM";
        }
        if (declaration instanceof RecordDeclaration) {
            return "RECORD";
        }
        if (declaration instanceof AnnotationDeclaration) {
            return "INTERFACE";
        }
        if (declaration instanceof ClassOrInterfaceDeclaration cid) {
            return cid.isInterface() ? "INTERFACE" : "CLASS";
        }
        return "CLASS";
    }

    private static int line(int value) {
        return Math.max(value, 0);
    }

    /**
     * Maps a Java release to the parser's language level.
     *
     * <p>An unrecognised release falls back to the newest level the parser knows and the caller is
     * told, rather than the adapter silently choosing one.
     */
    static ParserConfiguration.LanguageLevel languageLevelFor(int javaRelease) {
        return switch (javaRelease) {
            case 8 -> ParserConfiguration.LanguageLevel.JAVA_8;
            case 9 -> ParserConfiguration.LanguageLevel.JAVA_9;
            case 10 -> ParserConfiguration.LanguageLevel.JAVA_10;
            case 11 -> ParserConfiguration.LanguageLevel.JAVA_11;
            case 12 -> ParserConfiguration.LanguageLevel.JAVA_12;
            case 13 -> ParserConfiguration.LanguageLevel.JAVA_13;
            case 14 -> ParserConfiguration.LanguageLevel.JAVA_14;
            case 15 -> ParserConfiguration.LanguageLevel.JAVA_15;
            case 16 -> ParserConfiguration.LanguageLevel.JAVA_16;
            case 17 -> ParserConfiguration.LanguageLevel.JAVA_17;
            case 18 -> ParserConfiguration.LanguageLevel.JAVA_18;
            case 19 -> ParserConfiguration.LanguageLevel.JAVA_19;
            case 20 -> ParserConfiguration.LanguageLevel.JAVA_20;
            default -> ParserConfiguration.LanguageLevel.JAVA_21;
        };
    }

    /**
     * Source files this adapter cannot model.
     *
     * <p>Kotlin, Groovy and Scala roots used to be handed to a Java-only parser, which failed on them
     * and left the graph silently short of whatever they declared while the coverage number counted
     * only Java files. Reporting them as unmodelled is the difference between a known blind spot and
     * an unknown one.
     */
    public static List<ParseIssue> unmodelledSources(List<Path> sourceRoots) {
        List<ParseIssue> issues = new java.util.ArrayList<>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    String name = file.getFileName().toString();
                    String language = name.endsWith(".kt") || name.endsWith(".kts") ? "Kotlin"
                            : name.endsWith(".groovy") ? "Groovy"
                            : name.endsWith(".scala") ? "Scala" : null;
                    if (language != null) {
                        issues.add(new ParseIssue(file.toString().replace((char) 92, '/'),
                                "UNMODELLED",
                                language + " source is not analysed by this adapter. It is recorded "
                                        + "as unmodelled rather than parsed, so impact findings that "
                                        + "would depend on it are absent and this is a declared "
                                        + "blind spot rather than an unknown one."));
                    }
                });
            } catch (java.io.IOException e) {
                issues.add(new ParseIssue(root.toString(), "UNMODELLED",
                        "Cannot enumerate " + root + ": " + e.getMessage()));
            }
        }
        return issues;
    }
}
