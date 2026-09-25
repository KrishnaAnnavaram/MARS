package com.mars.harness.capabilities.migration.transform;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source rules from the reference pack, applied only to files a failing round named.
 *
 * <ul>
 *   <li>{@link #repointImports}: package relocations (§2.2 customizer, §3 health, §4.1 mockito,
 *       §4.3 test slices), with annotation and type renames where the pack names them</li>
 *   <li>{@link #jackson3}: §2.2, rewriting a canonical {@code ObjectMapper} bean into a
 *       {@code JsonMapperBuilderCustomizer}, and §2.3, turning an injected {@code ObjectMapper}
 *       into a {@code JsonMapper}. A mapper bean with any statement the pack does not describe is
 *       <em>not</em> rewritten; it is returned as unmatched, for a human.</li>
 * </ul>
 */
public final class JavaMigrationTransformer {

    /** A source rewrite plus the identity hint for the renamed bean method, if any. */
    public record Result(String content, List<String> changes, Map<String, String> symbolRenames, String unmatchedReason) {
    }

    private static final ParserConfiguration CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private JavaMigrationTransformer() {
    }

    public static Optional<Result> repointImports(String source, Map<String, String> imports, Map<String, String> annotations,
                                                  Map<String, String> types) {
        String result = source;
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : imports.entrySet()) {
            String from = entry.getKey();
            String to = entry.getValue();
            Pattern p = Pattern.compile("(?m)^(\\s*import\\s+(?:static\\s+)?)" + Pattern.quote(from) + (from.endsWith(".") ? "" : "\\b"));
            Matcher m = p.matcher(result);
            if (m.find()) {
                result = m.replaceAll("$1" + Matcher.quoteReplacement(to));
                changes.add("import " + from + "* -> " + to + "*");
            }
        }
        for (Map.Entry<String, String> entry : annotations.entrySet()) {
            Pattern p = Pattern.compile("@" + Pattern.quote(entry.getKey()) + "\\b");
            if (p.matcher(result).find()) {
                result = p.matcher(result).replaceAll("@" + entry.getValue());
                changes.add("@" + entry.getKey() + " -> @" + entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : types.entrySet()) {
            Pattern p = Pattern.compile("(?<![\\w.])" + Pattern.quote(entry.getKey()) + "\\b");
            if (p.matcher(result).find()) {
                result = p.matcher(result).replaceAll(entry.getValue());
                changes.add("type " + entry.getKey() + " -> " + entry.getValue());
            }
        }
        return result.equals(source) ? Optional.empty() : Optional.of(new Result(result, changes, Map.of(), null));
    }

    /** §2.1 to §2.3 in one pass, per file. */
    public static Optional<Result> jackson3(String source, String typeFqn) {
        ParseResult<CompilationUnit> parsed = new JavaParser(CONFIG).parse(source);
        if (parsed.getResult().isEmpty() || !parsed.isSuccessful()) {
            return Optional.empty();
        }
        CompilationUnit cu = parsed.getResult().get();
        Optional<MethodDeclaration> bean = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getAnnotationByName("Bean").isPresent())
                .filter(m -> m.getType().asString().equals("ObjectMapper"))
                .findFirst();
        if (bean.isPresent()) {
            return rewriteBean(cu, bean.get(), typeFqn);
        }
        // §2.3 injected mappers: type and import swap; call sites keep their names
        String result = source;
        List<String> changes = new ArrayList<>();
        if (result.contains("import com.fasterxml.jackson.databind.ObjectMapper;")) {
            result = result.replace("import com.fasterxml.jackson.databind.ObjectMapper;", "import tools.jackson.databind.json.JsonMapper;");
            result = Pattern.compile("(?<![\\w.])ObjectMapper\\b").matcher(result).replaceAll("JsonMapper");
            changes.add("ObjectMapper -> JsonMapper (§2.3)");
        }
        if (result.contains("import com.fasterxml.jackson.databind.SerializationFeature;")) {
            result = result.replace("import com.fasterxml.jackson.databind.SerializationFeature;",
                    "import tools.jackson.databind.SerializationFeature;");
            changes.add("SerializationFeature -> tools.jackson.databind.SerializationFeature (§2.1)");
        }
        if (result.contains("JsonProcessingException")) {
            return Optional.of(new Result(source, List.of(), Map.of(), "JsonProcessingException handling (§2.3: Jackson 3 "
                    + "exceptions are unchecked) needs a judgement about the catch block; not rewritten mechanically"));
        }
        return result.equals(source) ? Optional.empty() : Optional.of(new Result(result, changes, Map.of(), null));
    }

    private static Optional<Result> rewriteBean(CompilationUnit cu, MethodDeclaration bean, String typeFqn) {
        List<Statement> statements = bean.getBody().map(b -> b.getStatements().stream().toList()).orElse(List.of());
        if (statements.size() < 2) {
            return Optional.of(unmatched(cu, "ObjectMapper bean body is not the canonical shape of §2.2"));
        }
        String var;
        if (statements.get(0) instanceof ExpressionStmt first
                && first.getExpression().isVariableDeclarationExpr()
                && first.getExpression().asVariableDeclarationExpr().getVariables().size() == 1
                && first.getExpression().asVariableDeclarationExpr().getVariable(0).getInitializer()
                .filter(i -> i instanceof ObjectCreationExpr oc && oc.getType().getNameAsString().equals("ObjectMapper")
                        && oc.getArguments().isEmpty()).isPresent()) {
            var = first.getExpression().asVariableDeclarationExpr().getVariable(0).getNameAsString();
        } else {
            return Optional.of(unmatched(cu, "the bean does not start with `new ObjectMapper()`"));
        }
        if (!(statements.get(statements.size() - 1) instanceof ReturnStmt ret) || ret.getExpression().isEmpty()
                || !ret.getExpression().get().toString().equals(var)) {
            return Optional.of(unmatched(cu, "the bean does not end with `return " + var + ";`"));
        }
        List<String> chain = new ArrayList<>();
        Set<String> imports = new LinkedHashSet<>();
        for (Statement s : statements.subList(1, statements.size() - 1)) {
            if (!(s instanceof ExpressionStmt es) || !(es.getExpression() instanceof MethodCallExpr call)
                    || call.getScope().isEmpty() || !call.getScope().get().toString().equals(var)) {
                return Optional.of(unmatched(cu, "statement `" + s + "` is not a mapper configuration call the pack describes"));
            }
            String name = call.getNameAsString();
            String arg = call.getArguments().size() == 1 ? call.getArgument(0).toString() : null;
            switch (name) {
                case "registerModule" -> {
                    if (arg == null || !arg.contains("JavaTimeModule")) {
                        return Optional.of(unmatched(cu, "registerModule(" + arg + ") is not the JavaTimeModule case of §2.1"));
                    }
                    // §2.1: java.time support is built in to Jackson 3 - the registration is dropped
                }
                case "disable", "enable" -> {
                    if (arg == null) {
                        return Optional.of(unmatched(cu, name + "() with " + call.getArguments().size() + " arguments"));
                    }
                    if (arg.equals("SerializationFeature.WRITE_DATES_AS_TIMESTAMPS")) {
                        chain.add("." + name + "(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)");
                        imports.add("tools.jackson.databind.cfg.DateTimeFeature");
                    } else if (arg.startsWith("SerializationFeature.")) {
                        chain.add("." + name + "(" + arg + ")");
                        imports.add("tools.jackson.databind.SerializationFeature");
                    } else {
                        return Optional.of(unmatched(cu, name + "(" + arg + ") is not covered by §2.2"));
                    }
                }
                case "setDefaultPropertyInclusion", "setSerializationInclusion" -> {
                    if (arg == null) {
                        return Optional.of(unmatched(cu, name + "() without a single inclusion argument"));
                    }
                    chain.add(".changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(" + arg + "))");
                }
                default -> {
                    return Optional.of(unmatched(cu, "mapper call `" + name + "` is not covered by §2.2"));
                }
            }
        }
        String method = "@Bean\n    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {\n        return builder -> builder"
                + String.join("", chain.stream().map(c -> "\n                " + c).toList()) + ";\n    }";
        MethodDeclaration replacement = new JavaParser(CONFIG).parseBodyDeclaration(method).getResult().orElseThrow()
                .asMethodDeclaration();
        LexicalPreservingPrinter.setup(cu);
        MethodDeclaration target = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(bean.getNameAsString())).findFirst().orElseThrow();
        target.replace(replacement);
        List<ImportDeclaration> remove = new ArrayList<>();
        for (ImportDeclaration i : cu.getImports()) {
            String name = i.getNameAsString();
            if (name.equals("com.fasterxml.jackson.databind.ObjectMapper") || name.equals("com.fasterxml.jackson.databind.SerializationFeature")
                    || name.equals("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule")) {
                remove.add(i);
            }
        }
        remove.forEach(ImportDeclaration::remove);
        cu.addImport("org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer");
        imports.forEach(cu::addImport);
        return Optional.of(new Result(LexicalPreservingPrinter.print(cu), List.of("ObjectMapper @Bean " + bean.getNameAsString()
                + "() -> JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() (§2.2)"),
                Map.of(typeFqn + "#" + bean.getNameAsString() + "()", "jsonMapperBuilderCustomizer()"), null));
    }

    private static Result unmatched(CompilationUnit cu, String reason) {
        return new Result(null, List.of(), Map.of(), reason);
    }
}
