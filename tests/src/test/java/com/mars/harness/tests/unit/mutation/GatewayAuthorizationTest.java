package com.mars.harness.tests.unit.mutation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.change.UnifiedDiff;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.ids.HarnessIds;
import com.mars.harness.kernel.engine.identity.IdentitySynchronizer;
import com.mars.harness.kernel.engine.mutation.MutationGateway;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.mutation.MutationPort;
import com.mars.harness.kernel.ports.mutation.ProposalSink;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §32.1 authorization and §32.3 "mutation requires authorization": the Mutation Gateway's
 * refusal matrix, against a real sealed run. Every refused case leaves the workspace unchanged.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayAuthorizationTest {

    private static final String REPO_JAVA = "src/main/java/com/acme/inventory/repo/ProductRepository.java";

    @TempDir
    static Path temp;
    private TestHarness h;
    private String run;
    private Finding sqli;

    @BeforeAll
    void setUp() {
        h = TestHarness.over(temp, "composite/inventory-service");
        run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "SECURITY_ONLY", "dev.lead", "owner", "Security only");
        h.engine.resume(run, false);
        sqli = h.finding(run, "INV-101");
    }

    private RunSession session() {
        return h.session(run);
    }

    private MutationGateway gateway(RunSession session) {
        return new MutationGateway(session, new IdentitySynchronizer(session, h.config.codeModel()), (b, f) -> { });
    }

    private String current() {
        return TestHarness.read(session().layout.workspace().resolve(REPO_JAVA));
    }

    private String fixed() {
        return current().replace("WHERE name = '\" + name + \"'\"", "WHERE name = ?\"").replace("queryForList(sql)", "queryForList(sql, name)");
    }

    private ChangeProposal proposal(ChangeProposal.Capability capability, ChangeProposal.ProviderType type, String path,
                                    String content, String baseHash, List<String> symbols, String ruleId, String op) {
        RunSession s = session();
        String fileId = s.fileRegistry.byPath(REPO_JAVA).orElseThrow().getFileId();
        return new ChangeProposal(HarnessIds.allocate(HarnessIds.Kind.PROPOSAL), run, capability, "test", type, "1", "test",
                List.of(sqli.findingId()), List.of(), List.of(), List.of(), List.of(fileId), symbols, List.of(),
                List.of(new ChangeProposal.FileEdit(fileId, path, null, op, content, UnifiedDiff.of(path, path, current(), content, 3),
                        null, List.of())), "x", ChangeProposal.Risk.LOW, Map.of(fileId, baseHash), s.baselineSeal(),
                Instant.now().toString(), new ChangeProposal.Provenance("test", ruleId, null, null, null, null, null, null,
                List.of()), false, null, null);
    }

    private ChangeProposal fix() {
        return proposal(ChangeProposal.Capability.SECURITY, ChangeProposal.ProviderType.SECURITY_FIXER, REPO_JAVA, fixed(),
                session().fileRegistry.byPath(REPO_JAVA).orElseThrow().getCurrentSha256(), List.of(sqli.symbolId()), "CWE-89",
                "MODIFY");
    }

    private MutationPort.Outcome apply(ChangeProposal p, MutationPort.Authorization auth) {
        RunSession s = session();
        return gateway(s).apply(auth, List.of(p)).get(0);
    }

    private static MutationPort.Authorization none() {
        return new MutationPort.Authorization("test", null, List.of(), Set.of(), null, null);
    }

    @Test
    void theRefusalMatrix() throws Exception {
        String before = current();

        // 1. no decision: awaiting approval, nothing written
        ChangeProposal p1 = fix();
        MutationPort.Outcome o1 = apply(p1, none());
        assertThat(o1.status()).isEqualTo(ProposalSink.Status.AWAITING_APPROVAL);
        assertThat(o1.reason()).contains("a missing decision is not approval");
        assertThat(current()).isEqualTo(before);

        // 2. rejected by a human
        ChangeProposal p2 = fix();
        gateway(session()).register(p2);
        h.engine.decideProposal(run, p2.proposalId(), "REJECTED", "dev.lead", "owner", "Not this way");
        assertThat(apply(p2, none()).reason()).startsWith("REJECTED_BY_DECISION");

        // 3. approved, then the proposal file is edited on disk: the approval no longer covers it
        ChangeProposal p3 = fix();
        gateway(session()).register(p3);
        h.approve(run, p3);
        Path file = session().layout.proposals().resolve(p3.proposalId() + ".json");
        ObjectNode node = (ObjectNode) KernelJson.read(file);
        ((ObjectNode) node.path("edits").get(0)).put("new_content", fixed() + "\n// sneaky extra line\n");
        Files.writeString(file, KernelJson.pretty(node));
        ChangeProposal tampered = session().proposals.find(p3.proposalId()).orElseThrow();
        MutationPort.Outcome o3 = apply(tampered, none());
        assertThat(o3.status()).isIn(ProposalSink.Status.STALE, ProposalSink.Status.REJECTED);
        assertThat(o3.reason()).contains("STALE_APPROVAL");
        assertThat(current()).isEqualTo(before);

        // 4. computed from different content (stale base hash)
        ChangeProposal p4 = proposal(ChangeProposal.Capability.SECURITY, ChangeProposal.ProviderType.SECURITY_FIXER, REPO_JAVA,
                fixed(), "0".repeat(64), List.of(sqli.symbolId()), "CWE-89", "MODIFY");
        assertThat(apply(p4, none()).reason()).startsWith("STALE_PROPOSAL");

        // 5. path traversal
        ChangeProposal p5 = proposal(ChangeProposal.Capability.SECURITY, ChangeProposal.ProviderType.SECURITY_FIXER,
                "../../outside/ProductRepository.java", fixed(), session().fileRegistry.byPath(REPO_JAVA).orElseThrow()
                        .getCurrentSha256(), List.of(sqli.symbolId()), "CWE-89", "MODIFY");
        assertThat(apply(p5, none()).reason()).startsWith("PATH_REFUSED");

        // 6. a change outside the declared symbol scope
        String otherMethod = current().replace("ORDER BY id LIMIT ?", "ORDER BY name LIMIT ?");
        ChangeProposal p6 = proposal(ChangeProposal.Capability.SECURITY, ChangeProposal.ProviderType.SECURITY_FIXER, REPO_JAVA,
                otherMethod, session().fileRegistry.byPath(REPO_JAVA).orElseThrow().getCurrentSha256(), List.of(sqli.symbolId()),
                "CWE-89", "MODIFY");
        assertThat(apply(p6, none()).reason()).startsWith("SCOPE_VIOLATION");

        // 7. a deterministic migration rule, but the human chose SECURITY_ONLY: no execution-level authority
        Decision securityOnly = session().approvals.find(session().record.executionDecisionId).orElseThrow();
        String unitId = session().identity.symbols.get(sqli.symbolId()).programUnitId;
        ChangeProposal p7 = proposal(ChangeProposal.Capability.MIGRATION, ChangeProposal.ProviderType.DETERMINISTIC_RULE, REPO_JAVA,
                fixed(), session().fileRegistry.byPath(REPO_JAVA).orElseThrow().getCurrentSha256(), List.of(unitId), "SB4-JACKSON3",
                "MODIFY");
        MutationPort.Outcome o7 = apply(p7, new MutationPort.Authorization("migration-plan", securityOnly, List.of(),
                Set.of("SB4-JACKSON3"), "PLAN-x", "h"));
        assertThat(o7.reason()).contains("does not authorize migration");

        // 8. an LLM-authored migration change is never covered by execution-level authority
        ChangeProposal p8 = proposal(ChangeProposal.Capability.MIGRATION, ChangeProposal.ProviderType.LLM, REPO_JAVA, fixed(),
                session().fileRegistry.byPath(REPO_JAVA).orElseThrow().getCurrentSha256(), List.of(unitId), "SB4-JACKSON3", "MODIFY");
        MutationPort.Outcome o8 = apply(p8, new MutationPort.Authorization("migration-plan", securityOnly, List.of(),
                Set.of("SB4-JACKSON3"), "PLAN-x", "h"));
        assertThat(o8.status()).isIn(ProposalSink.Status.REJECTED, ProposalSink.Status.AWAITING_APPROVAL);
        assertThat(o8.reason()).containsAnyOf("PROVENANCE_MISSING", "requires a human approval");

        // 9. a migration Java edit with no declared symbol scope
        ChangeProposal p9 = proposal(ChangeProposal.Capability.MIGRATION, ChangeProposal.ProviderType.DETERMINISTIC_RULE, REPO_JAVA,
                fixed(), session().fileRegistry.byPath(REPO_JAVA).orElseThrow().getCurrentSha256(), List.of(), "SB4-JACKSON3",
                "MODIFY");
        assertThat(apply(p9, none()).reason()).contains("declares no affected symbols");

        // 10. a strategy-only proposal can be approved but never applied
        ChangeProposal strategy = h.proposalFor(run, "INV-103");
        assertThat(strategy.strategyOnly()).isTrue();
        assertThat(apply(strategy, none()).status()).isEqualTo(ProposalSink.Status.REJECTED);

        assertThat(current()).isEqualTo(before);
        assertThat(session().record.proposalChanges).isEmpty();

        // and the positive control: the exact approved proposal is applied, once
        ChangeProposal good = fix();
        gateway(session()).register(good);
        h.approve(run, good);
        MutationPort.Outcome ok = apply(good, none());
        assertThat(ok.status()).isEqualTo(ProposalSink.Status.APPLIED);
        assertThat(current()).contains("queryForList(sql, name)");
        MutationPort.Outcome again = apply(good, none());
        assertThat(again.reason()).contains("not re-applied");
        assertThat(session().record.proposalChanges.get(good.proposalId())).hasSize(1);
    }
}
