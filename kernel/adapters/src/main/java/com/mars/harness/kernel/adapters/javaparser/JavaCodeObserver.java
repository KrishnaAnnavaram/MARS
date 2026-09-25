package com.mars.harness.kernel.adapters.javaparser;

import com.bootshift.core.security.SensitiveValues;
import com.bootshift.core.util.Hashing;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.configuration.DefaultConfigurationOption;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.mars.harness.kernel.core.identity.observe.CodeObservation;
import com.mars.harness.kernel.core.identity.observe.ObservedStatement;
import com.mars.harness.kernel.core.identity.observe.ObservedSymbol;
import com.mars.harness.kernel.core.identity.observe.ObservedUnit;
import com.mars.harness.kernel.ports.analysis.CodeModelPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Java observation for the identity plane, built on JavaParser (the same parser Bootshift's code
 * model uses).
 *
 * <p>What it reports, per file:
 *
 * <ul>
 *   <li>program units: classes, interfaces, enums, records and annotation types, including nested
 *       types, keyed by fully qualified name</li>
 *   <li>symbols: methods, constructors, fields and initializer blocks, plus ENDPOINT symbols
 *       derived from Spring mapping annotations</li>
 *   <li>statements: every statement in every body. Compound statements are observed by their
 *       header, and their blocks are child slots, so an edit inside a block does not change the
 *       identity-relevant text of the enclosing {@code if}. Lambda block bodies are child slots of
 *       the statement that contains them.</li>
 * </ul>
 *
 * <p>Normalisation removes comments and collapses whitespace. It then redacts credentials and
 * string literals in statements that assign secret-like names. The fingerprint is computed over
 * the redacted text, so no secret reaches the identity registry, even as a hash.
 */
public final class JavaCodeObserver implements CodeModelPort {

    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern SECRETISH = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|credential|private[_-]?key)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Map<String, String> MAPPINGS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH", "RequestMapping", "ANY");

    private final ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
            .setAttributeComments(true);
    private final DefaultPrettyPrinter printer = new DefaultPrettyPrinter(new DefaultPrinterConfiguration()
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_COMMENTS))
            .removeOption(new DefaultConfigurationOption(DefaultPrinterConfiguration.ConfigOption.PRINT_JAVADOC)));

    @Override
    public boolean supports(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".java");
    }

    @Override
    public CodeObservation observe(String fileId, String path, String moduleId, String content) {
        ParseResult<CompilationUnit> result = new JavaParser(configuration).parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().stream().limit(3).map(p -> p.getVerboseMessage())
                    .reduce((a, b) -> a + "; " + b).orElse("unparseable");
            return CodeObservation.empty(fileId, path, moduleId, "java", "PARSE_FAILED: " + problems);
        }
        CompilationUnit cu = result.getResult().get();
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Collector collector = new Collector();
        for (TypeDeclaration<?> type : cu.getTypes()) {
            observeType(collector, type, pkg, pkg.isEmpty() ? type.getNameAsString() : pkg + "." + type.getNameAsString());
        }
        return new CodeObservation(fileId, path, moduleId, "java", collector.units, collector.symbols,
                collector.statements, List.of());
    }

    @Override
    public String normalizeStatement(String statementSource) {
        try {
            Statement statement = new JavaParser(configuration).parseStatement(statementSource).getResult()
                    .orElseThrow();
            return headerText(statement);
        } catch (RuntimeException e) {
            return redact(WS.matcher(statementSource).replaceAll(" ").trim());
        }
    }

    // ------------------------------------------------------------------ units and symbols

    private static final class Collector {
        final List<ObservedUnit> units = new ArrayList<>();
        final List<ObservedSymbol> symbols = new ArrayList<>();
        final List<ObservedStatement> statements = new ArrayList<>();
    }

    private void observeType(Collector c, TypeDeclaration<?> type, String pkg, String fqn) {
        String kind = kindOf(type);
        List<String> memberSignatures = new ArrayList<>();
        List<ObservedSymbol> typeSymbols = new ArrayList<>();
        String classPath = mappingPath(type.getAnnotations()).orElse("");
        int initializer = 0;
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                observeType(c, nested, pkg, fqn + "." + nested.getNameAsString());
            } else if (member instanceof MethodDeclaration method) {
                String signature = method.getNameAsString() + "(" + params(method.getParameters()) + ")";
                String key = fqn + "#" + signature;
                List<String> fingerprints = new ArrayList<>();
                method.getBody().ifPresent(body -> observeBlock(c, key, null, "body", body.getStatements(), fingerprints));
                ObservedSymbol symbol = new ObservedSymbol(key, fqn, null, "METHOD", method.getNameAsString(), signature,
                        line(method, true), line(method, false), annotations(method.getAnnotations()), fingerprints);
                typeSymbols.add(symbol);
                memberSignatures.add("M:" + signature);
                endpointOf(fqn, classPath, method, key).ifPresent(typeSymbols::add);
            } else if (member instanceof ConstructorDeclaration ctor) {
                String signature = "<init>(" + params(ctor.getParameters()) + ")";
                String key = fqn + "#" + signature;
                List<String> fingerprints = new ArrayList<>();
                observeBlock(c, key, null, "body", ctor.getBody().getStatements(), fingerprints);
                typeSymbols.add(new ObservedSymbol(key, fqn, null, "CONSTRUCTOR", "<init>", signature,
                        line(ctor, true), line(ctor, false), annotations(ctor.getAnnotations()), fingerprints));
                memberSignatures.add("C:" + signature);
            } else if (member instanceof FieldDeclaration field) {
                for (var variable : field.getVariables()) {
                    String signature = variable.getTypeAsString() + " " + variable.getNameAsString();
                    typeSymbols.add(new ObservedSymbol(fqn + "#field:" + variable.getNameAsString(), fqn, null, "FIELD",
                            variable.getNameAsString(), signature, line(field, true), line(field, false),
                            annotations(field.getAnnotations()), List.of()));
                    memberSignatures.add("F:" + signature);
                }
            } else if (member instanceof InitializerDeclaration init) {
                String signature = (init.isStatic() ? "<clinit>" : "<init-block>") + "#" + initializer++;
                String key = fqn + "#" + signature;
                List<String> fingerprints = new ArrayList<>();
                observeBlock(c, key, null, "body", init.getBody().getStatements(), fingerprints);
                typeSymbols.add(new ObservedSymbol(key, fqn, null, "INITIALIZER", signature, signature,
                        line(init, true), line(init, false), List.of(), fingerprints));
                memberSignatures.add("I:" + signature);
            }
        }
        if (type instanceof RecordDeclaration record) {
            record.getParameters().forEach(p -> memberSignatures.add("R:" + p.getTypeAsString() + " " + p.getNameAsString()));
        }
        c.units.add(new ObservedUnit(fqn, kind, type.getNameAsString(), fqn, pkg, line(type, true), line(type, false),
                memberSignatures, annotations(type.getAnnotations())));
        c.symbols.addAll(typeSymbols);
    }

    private Optional<ObservedSymbol> endpointOf(String fqn, String classPath, MethodDeclaration method, String methodKey) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String verb = MAPPINGS.get(annotation.getNameAsString());
            if (verb == null) {
                continue;
            }
            if ("ANY".equals(verb)) {
                verb = requestMethod(annotation).orElse("ANY");
            }
            String path = joinPaths(classPath, mappingPath(List.of(annotation)).orElse(""));
            String signature = verb + " " + path;
            return Optional.of(new ObservedSymbol("ENDPOINT:" + signature + "@" + methodKey, fqn, methodKey, "ENDPOINT",
                    signature, signature, line(method, true), line(method, false), List.of(annotation.getNameAsString()),
                    List.of()));
        }
        return Optional.empty();
    }

    private static Optional<String> mappingPath(List<AnnotationExpr> annotations) {
        for (AnnotationExpr annotation : annotations) {
            if (!MAPPINGS.containsKey(annotation.getNameAsString())) {
                continue;
            }
            if (annotation instanceof SingleMemberAnnotationExpr single) {
                return Optional.of(firstString(single.getMemberValue()));
            }
            if (annotation instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals("value") || pair.getNameAsString().equals("path")) {
                        return Optional.of(firstString(pair.getValue()));
                    }
                }
            }
            return Optional.of("");
        }
        return Optional.empty();
    }

    private static Optional<String> requestMethod(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (pair.getNameAsString().equals("method")) {
                    Expression value = pair.getValue();
                    if (value instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
                        value = array.getValues().get(0);
                    }
                    if (value instanceof FieldAccessExpr access) {
                        return Optional.of(access.getNameAsString());
                    }
                    return Optional.of(value.toString().replace("RequestMethod.", ""));
                }
            }
        }
        return Optional.empty();
    }

    private static String firstString(Expression value) {
        if (value instanceof StringLiteralExpr literal) {
            return literal.asString();
        }
        if (value instanceof ArrayInitializerExpr array && !array.getValues().isEmpty()) {
            return firstString(array.getValues().get(0));
        }
        return value.toString();
    }

    private static String joinPaths(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        String joined = (left.isEmpty() ? "" : "/" + left.replaceAll("^/+|/+$", ""))
                + (right.isEmpty() ? "" : "/" + right.replaceAll("^/+", ""));
        return joined.isEmpty() ? "/" : joined;
    }

    private static String kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration c) {
            return c.isInterface() ? "INTERFACE" : "CLASS";
        }
        if (type instanceof EnumDeclaration) {
            return "ENUM";
        }
        if (type instanceof RecordDeclaration) {
            return "RECORD";
        }
        if (type instanceof AnnotationDeclaration) {
            return "ANNOTATION";
        }
        return "TYPE";
    }

    private static String params(NodeList<Parameter> parameters) {
        List<String> types = new ArrayList<>();
        for (Parameter p : parameters) {
            types.add(p.getType().asString() + (p.isVarArgs() ? "..." : ""));
        }
        return String.join(",", types);
    }

    private static List<String> annotations(NodeList<AnnotationExpr> annotations) {
        return annotations.stream().map(AnnotationExpr::getNameAsString).toList();
    }

    // ------------------------------------------------------------------ statements

    private void observeBlock(Collector c, String symbolKey, ObservedStatement parent, String slot,
                              List<Statement> statements, List<String> fingerprints) {
        int index = 0;
        for (Statement statement : statements) {
            if (statement instanceof BlockStmt nested) {
                // A bare nested block is a scoping construct, not a statement. Its statements belong
                // to the enclosing slot, under a distinct sub-slot.
                observeBlock(c, symbolKey, parent, slot + ".blk" + index, nested.getStatements(), fingerprints);
                index++;
                continue;
            }
            observeStatement(c, symbolKey, parent, slot, index++, statement, fingerprints);
        }
    }

    private void observeStatement(Collector c, String symbolKey, ObservedStatement parent, String slot, int index,
                                  Statement statement, List<String> fingerprints) {
        String kind = statement.getClass().getSimpleName();
        String text = headerText(statement);
        String fingerprint = Hashing.sha256(kind + ":" + text).substring(0, 16);
        String key = (parent == null ? symbolKey : parent.key()) + "/" + slot + "." + index;
        Range range = statement.getRange().orElse(null);
        ObservedStatement observed = new ObservedStatement(key, symbolKey, parent == null ? null : parent.key(), slot,
                index, kind, text, fingerprint, range == null ? 0 : range.begin.line,
                range == null ? 0 : range.begin.column, range == null ? 0 : range.end.line,
                range == null ? 0 : range.end.column);
        c.statements.add(observed);
        fingerprints.add(fingerprint);

        for (Map.Entry<String, List<Statement>> child : childBlocks(statement).entrySet()) {
            observeBlock(c, symbolKey, observed, child.getKey(), child.getValue(), new ArrayList<>());
        }
        // Lambda block bodies inside this statement's own expressions are child slots of it.
        int lambda = 0;
        for (LambdaExpr expr : ownLambdas(statement)) {
            if (expr.getBody() instanceof BlockStmt body) {
                observeBlock(c, symbolKey, observed, "lambda" + lambda, body.getStatements(), new ArrayList<>());
            }
            lambda++;
        }
    }

    /** The sub-blocks of a compound statement, by slot name. */
    private static Map<String, List<Statement>> childBlocks(Statement statement) {
        Map<String, List<Statement>> blocks = new LinkedHashMap<>();
        if (statement instanceof IfStmt s) {
            blocks.put("then", asList(s.getThenStmt()));
            s.getElseStmt().ifPresent(e -> blocks.put("else", asList(e)));
        } else if (statement instanceof ForStmt s) {
            blocks.put("body", asList(s.getBody()));
        } else if (statement instanceof ForEachStmt s) {
            blocks.put("body", asList(s.getBody()));
        } else if (statement instanceof WhileStmt s) {
            blocks.put("body", asList(s.getBody()));
        } else if (statement instanceof DoStmt s) {
            blocks.put("body", asList(s.getBody()));
        } else if (statement instanceof SynchronizedStmt s) {
            blocks.put("body", s.getBody().getStatements());
        } else if (statement instanceof LabeledStmt s) {
            blocks.put("body", asList(s.getStatement()));
        } else if (statement instanceof TryStmt s) {
            blocks.put("try", s.getTryBlock().getStatements());
            int i = 0;
            for (CatchClause clause : s.getCatchClauses()) {
                blocks.put("catch" + i++ + "(" + clause.getParameter().getTypeAsString() + ")",
                        clause.getBody().getStatements());
            }
            s.getFinallyBlock().ifPresent(f -> blocks.put("finally", f.getStatements()));
        } else if (statement instanceof SwitchStmt s) {
            int i = 0;
            for (SwitchEntry entry : s.getEntries()) {
                String labels = entry.getLabels().isEmpty() ? "default"
                        : entry.getLabels().stream().map(Node::toString).reduce((a, b) -> a + "," + b).orElse("");
                blocks.put("case" + i++ + "(" + labels + ")", entry.getStatements());
            }
        }
        return blocks;
    }

    private static List<Statement> asList(Statement statement) {
        return statement instanceof BlockStmt block ? block.getStatements() : List.of(statement);
    }

    /** Lambdas whose nearest enclosing statement is {@code statement}. */
    private static List<LambdaExpr> ownLambdas(Statement statement) {
        if (isCompound(statement) || statement instanceof LocalClassDeclarationStmt) {
            return headerLambdas(statement);
        }
        return statement.findAll(LambdaExpr.class, l -> nearestStatement(l) == statement);
    }

    private static List<LambdaExpr> headerLambdas(Statement statement) {
        List<LambdaExpr> lambdas = new ArrayList<>();
        for (Expression header : headerExpressions(statement)) {
            lambdas.addAll(header.findAll(LambdaExpr.class, l -> nearestStatement(l) == statement));
            if (header instanceof LambdaExpr l && nearestStatement(l) == statement) {
                lambdas.add(0, l);
            }
        }
        return lambdas;
    }

    private static Statement nearestStatement(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof Statement s && !(current instanceof BlockStmt)) {
                return s;
            }
            current = current.getParentNode().orElse(null);
        }
        return null;
    }

    private static boolean isCompound(Statement s) {
        return s instanceof IfStmt || s instanceof ForStmt || s instanceof ForEachStmt || s instanceof WhileStmt
                || s instanceof DoStmt || s instanceof TryStmt || s instanceof SwitchStmt
                || s instanceof SynchronizedStmt || s instanceof LabeledStmt;
    }

    private static List<Expression> headerExpressions(Statement s) {
        List<Expression> exprs = new ArrayList<>();
        if (s instanceof IfStmt i) {
            exprs.add(i.getCondition());
        } else if (s instanceof WhileStmt w) {
            exprs.add(w.getCondition());
        } else if (s instanceof DoStmt d) {
            exprs.add(d.getCondition());
        } else if (s instanceof ForStmt f) {
            exprs.addAll(f.getInitialization());
            f.getCompare().ifPresent(exprs::add);
            exprs.addAll(f.getUpdate());
        } else if (s instanceof ForEachStmt f) {
            exprs.add(f.getIterable());
        } else if (s instanceof SwitchStmt sw) {
            exprs.add(sw.getSelector());
        } else if (s instanceof SynchronizedStmt sy) {
            exprs.add(sy.getExpression());
        } else if (s instanceof TryStmt t) {
            exprs.addAll(t.getResources());
        }
        return exprs;
    }

    /** Normalised, comment-free, redacted text: header only for compound statements. */
    String headerText(Statement statement) {
        String raw;
        if (statement instanceof IfStmt s) {
            raw = "if (" + print(s.getCondition()) + ")";
        } else if (statement instanceof WhileStmt s) {
            raw = "while (" + print(s.getCondition()) + ")";
        } else if (statement instanceof DoStmt s) {
            raw = "do while (" + print(s.getCondition()) + ")";
        } else if (statement instanceof ForStmt s) {
            raw = "for (" + join(s.getInitialization()) + "; " + s.getCompare().map(this::print).orElse("")
                    + "; " + join(s.getUpdate()) + ")";
        } else if (statement instanceof ForEachStmt s) {
            raw = "for (" + print(s.getVariable()) + " : " + print(s.getIterable()) + ")";
        } else if (statement instanceof TryStmt s) {
            raw = s.getResources().isEmpty() ? "try" : "try (" + join(s.getResources()) + ")";
        } else if (statement instanceof SwitchStmt s) {
            raw = "switch (" + print(s.getSelector()) + ")";
        } else if (statement instanceof SynchronizedStmt s) {
            raw = "synchronized (" + print(s.getExpression()) + ")";
        } else if (statement instanceof LabeledStmt s) {
            raw = s.getLabel().asString() + ":";
        } else {
            Statement copy = statement.clone();
            copy.findAll(LambdaExpr.class).forEach(l -> {
                if (l.getBody() instanceof BlockStmt) {
                    l.setBody(new BlockStmt());
                }
            });
            raw = print(copy);
        }
        return redact(WS.matcher(raw).replaceAll(" ").trim());
    }

    private String print(Node node) {
        return printer.print(node);
    }

    private String join(NodeList<? extends Node> nodes) {
        List<String> parts = new ArrayList<>();
        nodes.forEach(n -> parts.add(print(n)));
        return String.join(", ", parts);
    }

    static String redact(String text) {
        String result = SensitiveValues.redactLine(null, text);
        if (SECRETISH.matcher(result).find()) {
            result = STRING_LITERAL.matcher(result).replaceAll("\"<redacted>\"");
        }
        return result;
    }

    private static int line(Node node, boolean begin) {
        return node.getRange().map(r -> begin ? r.begin.line : r.end.line).orElse(0);
    }
}
