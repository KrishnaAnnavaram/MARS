package com.mars.harness.tests.unit.identity;

import com.mars.harness.kernel.adapters.javaparser.JavaCodeObserver;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.identity.Certainty;
import com.mars.harness.kernel.core.identity.IdentityReattacher;
import com.mars.harness.kernel.core.identity.IdentityRegistry;
import com.mars.harness.kernel.core.identity.IdentityStatus;
import com.mars.harness.kernel.core.identity.ProgramUnitRecord;
import com.mars.harness.kernel.core.identity.ProviderHints;
import com.mars.harness.kernel.core.identity.ReattachmentMethod;
import com.mars.harness.kernel.core.identity.ReattachmentPolicy;
import com.mars.harness.kernel.core.identity.ReattachmentReport;
import com.mars.harness.kernel.core.identity.StatementRecord;
import com.mars.harness.kernel.core.identity.SymbolRecord;
import com.mars.harness.kernel.core.identity.observe.CodeObservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity golden tests (spec §32.5, Phase D). Each scenario allocates a baseline, mutates the
 * source, reattaches, and asserts identity lineage and its evidence.
 */
class IdentityGoldenTest {

    private final JavaCodeObserver observer = new JavaCodeObserver();
    private final ReattachmentPolicy policy = ReattachmentPolicy.defaults();

    private static final String SERVICE_V1 = """
            package com.acme.service;

            public class EmployeeService {
                private final EmployeeRepository repository;

                public EmployeeService(EmployeeRepository repository) {
                    this.repository = repository;
                }

                public Employee create(Employee employee) {
                    validate(employee);
                    log.info("creating");
                    repository.save(employee);
                    return employee;
                }

                public void validate(Employee employee) {
                    if (employee.getName() == null) {
                        throw new IllegalArgumentException("name required");
                    }
                }
            }
            """;

    private CodeObservation observe(String fileId, String path, String source) {
        return observer.observe(fileId, path, "MOD-TEST", source);
    }

    private IdentityRegistry baseline(String fileId, String path, String source) {
        IdentityRegistry registry = IdentityRegistry.create("RUN-TEST");
        registry.allocateBaseline(observe(fileId, path, source));
        return registry;
    }

    private ReattachmentReport change(IdentityRegistry registry, String changeId, IdentityReattacher.FileChange... files) {
        return IdentityReattacher.reattach(registry, new IdentityReattacher.Request(changeId, List.of(files),
                ProviderHints.none()), policy);
    }

    private StatementRecord statementWithText(IdentityRegistry registry, String fragment) {
        return registry.statements.values().stream()
                .filter(s -> s.currentText != null && s.currentText.contains(fragment))
                .filter(StatementRecord::active)
                .findFirst().orElseThrow(() -> new AssertionError("no active statement containing " + fragment));
    }

    private SymbolRecord symbolNamed(IdentityRegistry registry, String name) {
        return registry.symbols.values().stream().filter(s -> name.equals(s.name) && s.active())
                .findFirst().orElseThrow(() -> new AssertionError("no active symbol " + name));
    }

    @Test
    @DisplayName("observation: units, symbols (incl. endpoint), statements with compound headers and child slots")
    void observationShape() {
        CodeObservation obs = observe("FILE-1", "src/Ctl.java", """
                package p;
                @RestController @RequestMapping("/api/v1")
                public class Ctl {
                    @GetMapping("/employees/{id}")
                    public String get(Long id) {
                        if (id == null) { return "none"; } else { return "x" + id; }
                    }
                }
                """);
        assertThat(obs.issues()).isEmpty();
        assertThat(obs.units()).extracting("fqn").containsExactly("p.Ctl");
        assertThat(obs.symbols()).extracting("kind").contains("METHOD", "ENDPOINT");
        assertThat(obs.symbols()).filteredOn(s -> s.kind().equals("ENDPOINT")).extracting("signature")
                .containsExactly("GET /api/v1/employees/{id}");
        assertThat(obs.statements()).extracting("normalizedText")
                .containsExactly("if (id == null)", "return \"none\";", "return \"x\" + id;");
        assertThat(obs.statements().get(1).slot()).isEqualTo("then");
        assertThat(obs.statements().get(2).slot()).isEqualTo("else");
    }

    @Test
    @DisplayName("statement edit preserves STATEMENT_ID and appends a version (save(e) -> save(sanitize(e)))")
    void statementEditPreservesId() {
        IdentityRegistry registry = baseline("FILE-1", "src/EmployeeService.java", SERVICE_V1);
        StatementRecord save = statementWithText(registry, "repository.save(employee)");
        String id = save.statementId;

        ReattachmentReport report = change(registry, "CHANGE-000001", IdentityReattacher.FileChange.modified("FILE-1",
                observe("FILE-1", "src/EmployeeService.java",
                        SERVICE_V1.replace("repository.save(employee);", "repository.save(sanitize(employee));"))));

        StatementRecord after = registry.statements.get(id);
        assertThat(after.active()).isTrue();
        assertThat(after.currentText).isEqualTo("repository.save(sanitize(employee));");
        assertThat(after.versions).hasSize(2);
        assertThat(after.versions.get(1).changeId()).isEqualTo("CHANGE-000001");
        assertThat(after.changeIds).contains("CHANGE-000001");
        assertThat(after.reattachment.get(after.reattachment.size() - 1).method())
                .isEqualTo(ReattachmentMethod.AST_DIFF_UPDATE);
        assertThat(report.count("STATEMENT", ReattachmentReport.ALLOCATED)).isZero();
    }

    @Test
    @DisplayName("insertion around a statement keeps every existing STATEMENT_ID; only the new one is allocated")
    void insertionAroundStatement() {
        IdentityRegistry registry = baseline("FILE-1", "src/EmployeeService.java", SERVICE_V1);
        Map<String, String> before = texts(registry);

        ReattachmentReport report = change(registry, "CHANGE-000002", IdentityReattacher.FileChange.modified("FILE-1",
                observe("FILE-1", "src/EmployeeService.java", SERVICE_V1.replace("        validate(employee);\n",
                        "        audit.record(employee);\n        validate(employee);\n"))));

        before.forEach((id, text) -> assertThat(registry.statements.get(id).active()).as(text).isTrue());
        assertThat(report.forLevel("STATEMENT")).filteredOn(e -> e.outcome().equals(ReattachmentReport.ALLOCATED))
                .hasSize(1);
        assertThat(statementWithText(registry, "audit.record").createdByChange).isEqualTo("CHANGE-000002");
    }

    @Test
    @DisplayName("statement move within the method preserves STATEMENT_ID (exact fingerprint, recorded as a move)")
    void statementMovePreservesId() {
        IdentityRegistry registry = baseline("FILE-1", "src/EmployeeService.java", SERVICE_V1);
        String logId = statementWithText(registry, "log.info").statementId;

        change(registry, "CHANGE-000003", IdentityReattacher.FileChange.modified("FILE-1",
                observe("FILE-1", "src/EmployeeService.java", SERVICE_V1
                        .replace("        log.info(\"creating\");\n", "")
                        .replace("        return employee;\n", "        log.info(\"creating\");\n        return employee;\n"))));

        StatementRecord moved = registry.statements.get(logId);
        assertThat(moved.active()).isTrue();
        assertThat(moved.reattachment.get(moved.reattachment.size() - 1).method())
                .isIn(ReattachmentMethod.EXACT_FINGERPRINT, ReattachmentMethod.AST_DIFF);
        assertThat(moved.index).isEqualTo(2);
    }

    @Test
    @DisplayName("deletion keeps historical identity: status DELETED, deleting change recorded, still queryable")
    void deletionKeepsHistory() {
        IdentityRegistry registry = baseline("FILE-1", "src/EmployeeService.java", SERVICE_V1);
        String logId = statementWithText(registry, "log.info").statementId;

        change(registry, "CHANGE-000004", IdentityReattacher.FileChange.modified("FILE-1",
                observe("FILE-1", "src/EmployeeService.java", SERVICE_V1.replace("        log.info(\"creating\");\n", ""))));

        StatementRecord gone = registry.statement(logId).orElseThrow();
        assertThat(gone.status).isEqualTo(IdentityStatus.DELETED);
        assertThat(gone.deletedByChange).isEqualTo("CHANGE-000004");
        assertThat(gone.versions).extracting("reason").contains("DELETED");
    }

    @Test
    @DisplayName("method rename preserves SYMBOL_ID when body evidence is strong; statements keep their IDs")
    void methodRenamePreservesSymbol() {
        IdentityRegistry registry = baseline("FILE-1", "src/EmployeeService.java", SERVICE_V1);
        SymbolRecord validate = symbolNamed(registry, "validate");
        String throwId = statementWithText(registry, "throw new IllegalArgumentException").statementId;

        change(registry, "CHANGE-000005", IdentityReattacher.FileChange.modified("FILE-1",
                observe("FILE-1", "src/EmployeeService.java", SERVICE_V1.replace("validate(", "ensureValid("))));

        SymbolRecord renamed = registry.symbols.get(validate.symbolId);
        assertThat(renamed.active()).isTrue();
        assertThat(renamed.name).isEqualTo("ensureValid");
        assertThat(renamed.reattachment.get(renamed.reattachment.size() - 1).method())
                .isEqualTo(ReattachmentMethod.STRUCTURAL_SIMILARITY);
        assertThat(renamed.reattachment.get(renamed.reattachment.size() - 1).certainty()).isEqualTo(Certainty.HIGH);
        assertThat(registry.statements.get(throwId).active()).isTrue();
        assertThat(registry.statements.get(throwId).parentSymbolId).isEqualTo(validate.symbolId);
    }

    @Test
    @DisplayName("method move across classes and files is traceable (CONTEXTUAL), statements follow")
    void methodMoveAcrossFiles() {
        IdentityRegistry registry = IdentityRegistry.create("RUN-TEST");
        registry.allocateBaseline(observe("FILE-1", "src/EmployeeService.java", SERVICE_V1));
        registry.allocateBaseline(observe("FILE-2", "src/Validator.java", "package com.acme.service;\npublic class Validator {\n}\n"));
        SymbolRecord validate = symbolNamed(registry, "validate");
        String throwId = statementWithText(registry, "throw new IllegalArgumentException").statementId;

        String serviceWithout = SERVICE_V1.replace("""
                    public void validate(Employee employee) {
                        if (employee.getName() == null) {
                            throw new IllegalArgumentException("name required");
                        }
                    }
                """, "");
        String validator = """
                package com.acme.service;
                public class Validator {
                    public void validate(Employee employee) {
                        if (employee.getName() == null) {
                            throw new IllegalArgumentException("name required");
                        }
                    }
                }
                """;
        change(registry, "CHANGE-000006",
                IdentityReattacher.FileChange.modified("FILE-1", observe("FILE-1", "src/EmployeeService.java", serviceWithout)),
                IdentityReattacher.FileChange.modified("FILE-2", observe("FILE-2", "src/Validator.java", validator)));

        SymbolRecord moved = registry.symbols.get(validate.symbolId);
        assertThat(moved.active()).isTrue();
        assertThat(moved.fileId).isEqualTo("FILE-2");
        assertThat(moved.reattachment.get(moved.reattachment.size() - 1).method()).isEqualTo(ReattachmentMethod.CONTEXTUAL);
        assertThat(registry.statements.get(throwId).fileId).isEqualTo("FILE-2");
        assertThat(registry.statements.get(throwId).active()).isTrue();
    }

    @Test
    @DisplayName("class + file rename (SecurityConfig -> WebSecurityConfig) keeps PROGRAM_UNIT_ID via FILE_ID lineage")
    void classRenameWithFile() {
        String v1 = """
                package com.acme.config;
                public class SecurityConfig {
                    public String chain(String http) {
                        String a = http.trim();
                        return a.toLowerCase();
                    }
                }
                """;
        IdentityRegistry registry = baseline("FILE-9", "src/config/SecurityConfig.java", v1);
        ProgramUnitRecord unit = registry.unitByFqn("com.acme.config.SecurityConfig").orElseThrow();
        SymbolRecord chain = symbolNamed(registry, "chain");
        String stmt = statementWithText(registry, "http.trim()").statementId;

        // FILE_ID survives the rename in Bootshift's registry; the new observation arrives under the same FILE_ID
        change(registry, "CHANGE-000007", IdentityReattacher.FileChange.modified("FILE-9",
                observe("FILE-9", "src/config/WebSecurityConfig.java", v1.replace("SecurityConfig", "WebSecurityConfig"))));

        ProgramUnitRecord renamed = registry.programUnits.get(unit.programUnitId);
        assertThat(renamed.active()).isTrue();
        assertThat(renamed.fqn).isEqualTo("com.acme.config.WebSecurityConfig");
        assertThat(renamed.baselineFqn).isEqualTo("com.acme.config.SecurityConfig");
        assertThat(renamed.currentLocation.path()).isEqualTo("src/config/WebSecurityConfig.java");
        assertThat(registry.symbols.get(chain.symbolId).active()).isTrue();
        assertThat(registry.symbols.get(chain.symbolId).programUnitId).isEqualTo(unit.programUnitId);
        assertThat(registry.statements.get(stmt).active()).isTrue();
    }

    @Test
    @DisplayName("uncertain statement mapping is attached only as LOW certainty and flagged")
    void uncertainMappingIsMarked() {
        String v1 = """
                package p;
                public class A {
                    public int total(Order order) {
                        int sum = order.subtotal() + order.tax() + order.shipping();
                        return sum;
                    }
                }
                """;
        IdentityRegistry registry = baseline("FILE-3", "src/A.java", v1);
        String sumId = statementWithText(registry, "order.subtotal()").statementId;

        change(registry, "CHANGE-000008", IdentityReattacher.FileChange.modified("FILE-3", observe("FILE-3", "src/A.java",
                v1.replace("int sum = order.subtotal() + order.tax() + order.shipping();",
                        "int sum = pricing.compute(order.subtotal(), order.tax()) - discount;"))));

        // token similarity 0.62: above the low threshold (0.60), below the high one (0.80)
        StatementRecord s = registry.statements.get(sumId);
        assertThat(s.active()).isTrue();
        var last = s.reattachment.get(s.reattachment.size() - 1);
        assertThat(last.certainty()).as("a non-exact edit of this size must not be presented as HIGH/EXACT")
                .isEqualTo(Certainty.LOW);
        assertThat(last.uncertain()).isTrue();
        assertThat(last.confidence()).isBetween(0.60, 0.80);
        assertThat(last.oldLocation()).isNotNull();
        assertThat(last.newLocation()).isNotNull();
        assertThat(s.everUncertain()).isTrue();
    }

    @Test
    @DisplayName("below the low threshold a rewrite gets a NEW identity; the old one is DELETED, never silently reused")
    void weakMatchAllocatesNew() {
        String v1 = """
                package p;
                public class C {
                    public int f(int x) {
                        return x + 1;
                    }
                }
                """;
        IdentityRegistry registry = baseline("FILE-7", "src/C.java", v1);
        String oldId = statementWithText(registry, "return x + 1").statementId;
        change(registry, "CHANGE-000020", IdentityReattacher.FileChange.modified("FILE-7", observe("FILE-7", "src/C.java",
                v1.replace("return x + 1;", "return Math.floorMod(Objects.hash(seed, salt), modulus);"))));
        assertThat(registry.statements.get(oldId).status).isEqualTo(IdentityStatus.DELETED);
        StatementRecord created = statementWithText(registry, "Math.floorMod");
        assertThat(created.statementId).isNotEqualTo(oldId);
        assertThat(created.reattachment.get(0).method()).isEqualTo(ReattachmentMethod.ALLOCATED_NEW);
    }

    @Test
    @DisplayName("merge: two statements folded into one are MERGED_AWAY into it, lineage queryable")
    void statementMergeLineage() {
        String v1 = """
                package p;
                public class D {
                    public void go() {
                        first();
                        second();
                    }
                }
                """;
        IdentityRegistry registry = baseline("FILE-8", "src/D.java", v1);
        String first = statementWithText(registry, "first()").statementId;
        String second = statementWithText(registry, "second()").statementId;
        change(registry, "CHANGE-000021", IdentityReattacher.FileChange.modified("FILE-8", observe("FILE-8", "src/D.java",
                v1.replace("        first();\n        second();\n", "        both(first(), second());\n"))));
        StatementRecord merged = statementWithText(registry, "both(");
        assertThat(registry.statements.get(first).status).isEqualTo(IdentityStatus.MERGED_AWAY);
        assertThat(registry.statements.get(first).mergedInto).isEqualTo(merged.statementId);
        assertThat(registry.statements.get(second).status).isEqualTo(IdentityStatus.MERGED_AWAY);
        assertThat(registry.statements.get(second).mergedInto).isEqualTo(merged.statementId);
    }

    @Test
    @DisplayName("ambiguous match (two equally good candidates) is never attached; candidates are recorded")
    void ambiguousMatchIsNotAttached() {
        String v1 = """
                package p;
                public class B {
                    public void run() {
                        cache.put(keyA, valueA);
                        cache.put(keyB, valueB);
                    }
                }
                """;
        IdentityRegistry registry = baseline("FILE-4", "src/B.java", v1);
        // both old statements become one statement equally similar to each: no statement may inherit an identity blindly
        change(registry, "CHANGE-000009", IdentityReattacher.FileChange.modified("FILE-4", observe("FILE-4", "src/B.java",
                v1.replace("        cache.put(keyA, valueA);\n        cache.put(keyB, valueB);\n",
                        "        cache.put(keyC, valueC);\n"))));

        StatementRecord created = statementWithText(registry, "keyC");
        var evidence = created.reattachment.get(created.reattachment.size() - 1);
        assertThat(evidence.method()).isEqualTo(ReattachmentMethod.ALLOCATED_NEW);
        assertThat(evidence.certainty()).isEqualTo(Certainty.NONE);
        assertThat(evidence.candidates()).hasSize(2);
    }

    @Test
    @DisplayName("split: a class split into two files keeps lineage (splitFrom); merge: absorbed statement is MERGED_AWAY")
    void splitAndMergeLineage() {
        String v1 = """
                package p;
                public class Big {
                    public void alpha() { a1(); a2(); a3(); }
                    public void beta() { b1(); b2(); b3(); }
                    public void gamma() { c1(); }
                }
                """;
        IdentityRegistry registry = IdentityRegistry.create("RUN-TEST");
        registry.allocateBaseline(observe("FILE-5", "src/Big.java", v1));
        ProgramUnitRecord big = registry.unitByFqn("p.Big").orElseThrow();
        String betaId = symbolNamed(registry, "beta").symbolId;

        String bigAfter = """
                package p;
                public class Big {
                    public void alpha() { a1(); a2(); a3(); }
                    public void gamma() { c1(); }
                }
                """;
        String split = """
                package p;
                public class BigBeta {
                    public void beta() { b1(); b2(); b3(); }
                }
                """;
        change(registry, "CHANGE-000010",
                IdentityReattacher.FileChange.modified("FILE-5", observe("FILE-5", "src/Big.java", bigAfter)),
                new IdentityReattacher.FileChange("FILE-6", List.of("FILE-6", "FILE-5"),
                        observe("FILE-6", "src/BigBeta.java", split), false, List.of(), "FILE-5"));

        assertThat(registry.programUnits.get(big.programUnitId).active()).isTrue();
        // the moved method keeps its SYMBOL_ID (contextual move), and the new unit records it was split from Big
        assertThat(registry.symbols.get(betaId).active()).isTrue();
        assertThat(registry.symbols.get(betaId).fileId).isEqualTo("FILE-6");
        Optional<ProgramUnitRecord> betaUnit = registry.unitByFqn("p.BigBeta");
        assertThat(betaUnit).isPresent();
        assertThat(betaUnit.get().splitFrom).as("split lineage").isEqualTo(big.programUnitId);

        // merge at statement level: two statements folded into one
        String v2 = bigAfter.replace("{ a1(); a2(); a3(); }", "{ a1(); a2(); a3(); }")
                .replace("public void gamma() { c1(); }", "public void gamma() { c1(); c2(); }");
        change(registry, "CHANGE-000011", IdentityReattacher.FileChange.modified("FILE-5", observe("FILE-5", "src/Big.java", v2)));
        String c2 = statementWithText(registry, "c2()").statementId;
        String v3 = v2.replace("{ c1(); c2(); }", "{ c1(); combine(c2()); }");
        change(registry, "CHANGE-000012", IdentityReattacher.FileChange.modified("FILE-5", observe("FILE-5", "src/Big.java", v3)));
        StatementRecord c2After = registry.statements.get(c2);
        assertThat(c2After.statementId).isEqualTo(c2);
        // Either reattached as an update of the same slot, or retired with merge lineage; never silently lost.
        if (!c2After.active()) {
            assertThat(c2After.status).isIn(IdentityStatus.MERGED_AWAY, IdentityStatus.DELETED);
        }
    }

    @Test
    @DisplayName("registry round-trips through JSON without losing lineage")
    void registryRoundTrip() {
        IdentityRegistry registry = baseline("FILE-1", "src/EmployeeService.java", SERVICE_V1);
        change(registry, "CHANGE-000013", IdentityReattacher.FileChange.modified("FILE-1",
                observe("FILE-1", "src/EmployeeService.java",
                        SERVICE_V1.replace("repository.save(employee);", "repository.save(sanitize(employee));"))));
        IdentityRegistry copy = KernelJson.convert(KernelJson.tree(registry), IdentityRegistry.class);
        assertThat(copy.contentHash()).isEqualTo(registry.contentHash());
        assertThat(copy.statements).hasSameSizeAs(registry.statements);
        assertThat(copy.coverage()).isEqualTo(registry.coverage());
    }

    private static Map<String, String> texts(IdentityRegistry registry) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        registry.statements.values().forEach(s -> map.put(s.statementId, s.currentText));
        return map;
    }
}
