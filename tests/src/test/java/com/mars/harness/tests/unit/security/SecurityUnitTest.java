package com.mars.harness.tests.unit.security;

import com.mars.harness.capabilities.security.fix.Fixers;
import com.mars.harness.capabilities.security.knowledge.VrhKnowledge;
import com.mars.harness.capabilities.security.routing.CweRouter;
import com.mars.harness.capabilities.security.scan.VersionRange;
import com.mars.harness.capabilities.security.verify.MergeArbiter;
import com.mars.harness.kernel.adapters.excel.IssueRegister;
import com.mars.harness.kernel.adapters.excel.IssueRegisterNormalizer;
import com.mars.harness.kernel.adapters.sarif.SarifNormalizer;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.policy.UnifiedPolicy;
import com.mars.harness.kernel.engine.capability.KernelCapabilityContext;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.build.BuildPort;
import com.mars.harness.kernel.ports.runtime.ProbeComparator;
import com.mars.harness.kernel.ports.runtime.RuntimePort;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §32.1: finding normalization, routing, fixers, arbiter, probe comparison. */
class SecurityUnitTest {

    private static VrhKnowledge knowledge;
    private static final String REPO = "composite/inventory-service/src/main/java/com/acme/inventory/";

    @BeforeAll
    static void load() {
        UnifiedPolicy policy = UnifiedPolicy.load(TestHarness.harnessRoot().resolve("policies/default/unified-policy.json"));
        knowledge = VrhKnowledge.load(TestHarness.harnessRoot(), policy);
    }

    // ------------------------------------------------------------------ routing (VRH 04a/04c/04d detect-gap)

    @Test
    void routesFollowTheCatalogThenKbThenResearchOrder() {
        assertThat(CweRouter.route("CWE-89 SQL injection", knowledge).route()).isEqualTo(CweRouter.Route.CATALOG);
        assertThat(CweRouter.route("CWE-22 path traversal", knowledge).route()).isEqualTo(CweRouter.Route.KB);
        assertThat(CweRouter.route("CWE-502 unsafe deserialization", knowledge).route()).isEqualTo(CweRouter.Route.RESEARCH);
        assertThat(CweRouter.route("something is wrong", knowledge).route()).isEqualTo(CweRouter.Route.UNCLASSIFIED);
        // first-appearance order and de-duplication, as detectCweMentions does
        assertThat(CweRouter.detectCweMentions("cwe-22, CWE-89 and again CWE-22")).containsExactly("CWE-22", "CWE-89");
    }

    // ------------------------------------------------------------------ fixers

    @Test
    void parameterizeSqlBindsTheConcatenatedValue() throws Exception {
        String source = Files.readString(TestHarness.fixture(REPO + "repo/ProductRepository.java"));
        Optional<Fixers.Edit> edit = Fixers.parameterizeSql(source, 20);
        assertThat(edit).isPresent();
        assertThat(edit.get().newContent()).contains("WHERE name = ?").contains("queryForList(sql, name)")
                .doesNotContain("+ name +");
        // unrelated statements are left alone
        assertThat(edit.get().newContent()).contains("queryForMap(\"SELECT id, name, price FROM product WHERE id = ?\", id)");
        assertThat(Fixers.parameterizeSql(source, 24)).as("already parameterized").isEmpty();
    }

    @Test
    void containPathNormalizesAndChecksTheBase() throws Exception {
        String source = Files.readString(TestHarness.fixture(REPO + "report/ReportExporter.java"));
        Optional<Fixers.Edit> edit = Fixers.containPath(source, 29);
        assertThat(edit).isPresent();
        assertThat(edit.get().patternId()).contains("HF-PATH-001");
        assertThat(edit.get().newContent()).contains("normalize()").contains("startsWith");
    }

    @Test
    void bumpDependencyOnlyTouchesTheNamedCoordinate() throws Exception {
        String pom = Files.readString(TestHarness.fixture("composite/inventory-service/pom.xml"));
        Optional<Fixers.Edit> edit = Fixers.bumpDependency(pom, "org.springdoc", "springdoc-openapi-starter-webmvc-ui", "3.1.0");
        assertThat(edit).isPresent();
        assertThat(edit.get().newContent()).contains("<version>3.1.0</version>").contains("<version>3.5.0</version>");
        assertThat(VersionRange.contains("<3.0.0", "2.8.9")).isTrue();
        assertThat(VersionRange.contains("<3.0.0", "3.1.0")).isFalse();
    }

    // ------------------------------------------------------------------ 07a arbiter (scoring.json unchanged)

    @Test
    void theArbiterAppliesVrhScoringAndHardGates() {
        VrhKnowledge.Scoring scoring = knowledge.scoring();
        MergeArbiter.Result clean = MergeArbiter.arbitrate(scoring, "High", "FIXED", "NO_BYPASS_FOUND", "BEHAVIOR_PRESERVED",
                "Passed", "Passed", null);
        assertThat(clean.decision()).isEqualTo("Cleared");
        assertThat(clean.score()).isEqualTo(100);
        MergeArbiter.Result still = MergeArbiter.arbitrate(scoring, "High", "STILL_VULNERABLE", "NO_BYPASS_FOUND",
                "BEHAVIOR_PRESERVED", "Passed", "Passed", null);
        assertThat(still.decision()).isEqualTo("Blocked");
        assertThat(still.gatesTriggered()).anyMatch(g -> g.contains("STILL_VULNERABLE"));
        MergeArbiter.Result build = MergeArbiter.arbitrate(scoring, "Low", "FIXED", "NO_BYPASS_FOUND", "BEHAVIOR_PRESERVED",
                "Passed", "Failed", null);
        assertThat(build.decision()).isEqualTo("Blocked");
        MergeArbiter.Result weak = MergeArbiter.arbitrate(scoring, "Critical", "FIXED", "INCONCLUSIVE", "BEHAVIOR_CHANGED",
                "Passed", "Passed", null);
        assertThat(weak.score()).isLessThan(weak.threshold());
        assertThat(weak.decision()).isEqualTo("Blocked");
    }

    // ------------------------------------------------------------------ probe comparison (reference compareProbes)

    private static RuntimePort.ProbeObservation probe(String name, int status, String hash) {
        return new RuntimePort.ProbeObservation(name, "GET", "/" + name, false, 1, true, status, "application/json", 1, hash, "", null);
    }

    private static RuntimePort.RuntimeRun run(RuntimePort.ProbeObservation... probes) {
        return new RuntimePort.RuntimeRun("x", true, null, 1.0, "a.jar", "21", 200, 1L, List.of(probes), "");
    }

    @Test
    void probeComparisonDistinguishesBodyAndStatusChanges() {
        ProbeComparator.Comparison c = ProbeComparator.compare(run(probe("a", 200, "h1"), probe("b", 200, "h2"), probe("c", 404, "h3")),
                run(probe("a", 200, "h1"), probe("b", 200, "hX"), probe("c", 500, "h3")));
        assertThat(c.rows()).extracting(ProbeComparator.Row::verdict).containsExactly("identical", "body-differs", "status-differs");
        assertThat(c.matched()).isEqualTo(1);
        assertThat(c.total()).isEqualTo(3);
        ProbeComparator.TestVerdict worse = ProbeComparator.compareTests(new BuildPort.TestSummary(10, 1, 0, 0),
                new BuildPort.TestSummary(10, 3, 0, 0));
        assertThat(worse.worse()).isTrue();
        assertThat(ProbeComparator.compareTests(null, new BuildPort.TestSummary(10, 0, 0, 0)).worse()).isFalse();
    }

    // ------------------------------------------------------------------ normalization (Excel register, SARIF)

    @Test
    void registerAndSarifNormalizeToTheSameIdentityAnchors(@TempDir Path temp) throws Exception {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String runId = h.analyzeComposite(true).runId();
        RunSession session = h.session(runId);
        KernelCapabilityContext context = new KernelCapabilityContext(session, h.config);

        // the VRH register contract: column names, list splitting, synthesized body
        List<IssueRegister.Issue> issues = IssueRegister.list(TestHarness.fixture("composite/inputs/issue-register.xlsx"));
        assertThat(issues).extracting(IssueRegister.Issue::id).containsExactly("INV-101", "INV-102", "INV-103", "INV-104");
        assertThat(issues.get(0).body()).startsWith("# INV-101 — SQL injection in product search").contains("## Data Flow (source → sink)");

        List<Finding> fromRegister = new IssueRegisterNormalizer().normalize(
                TestHarness.fixture("composite/inputs/issue-register.xlsx"), context.identity(), session.evidence, runId);
        Finding sqli = fromRegister.stream().filter(f -> f.sourceFindingId().equals("INV-101")).findFirst().orElseThrow();
        assertThat(sqli.cwe()).containsExactly("CWE-89");
        assertThat(sqli.severity()).isEqualTo(Finding.Severity.HIGH);
        assertThat(sqli.fileId()).isEqualTo(session.fileRegistry.byPath("src/main/java/com/acme/inventory/repo/ProductRepository.java")
                .orElseThrow().getFileId());
        assertThat(sqli.statementId()).startsWith("STMT-");

        Path sarif = temp.resolve("scan.sarif");
        Files.writeString(sarif, """
                {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"semgrep","rules":[
                  {"id":"java.sqli","properties":{"tags":["CWE-89: SQL Injection"]}}]}},
                  "results":[{"ruleId":"java.sqli","level":"error","message":{"text":"Tainted SQL string"},
                    "locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/main/java/com/acme/inventory/repo/ProductRepository.java"},
                    "region":{"startLine":20}}}]}]}]}
                """);
        List<Finding> fromSarif = new SarifNormalizer().normalize(sarif, context.identity(), session.evidence, runId);
        assertThat(fromSarif).hasSize(1);
        assertThat(fromSarif.get(0).cwe()).contains("CWE-89");
        assertThat(fromSarif.get(0).fileId()).isEqualTo(sqli.fileId());
        assertThat(fromSarif.get(0).symbolId()).isEqualTo(sqli.symbolId());
        // an unresolvable reference is recorded as unknown, never guessed
        Path bad = temp.resolve("bad.sarif");
        Files.writeString(bad, """
                {"version":"2.1.0","runs":[{"tool":{"driver":{"name":"x","rules":[]}},"results":[{"ruleId":"r",
                 "message":{"text":"m"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":"src/Nope.java"},
                 "region":{"startLine":3}}}]}]}]}
                """);
        Finding unresolved = new SarifNormalizer().normalize(bad, context.identity(), session.evidence, runId).get(0);
        assertThat(unresolved.fileId()).isNull();
        assertThat(unresolved.anchorQuality()).isNotEqualTo("STATEMENT");
    }
}
