package com.mars.harness.tests.e2e;

import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.identity.ProgramUnitRecord;
import com.mars.harness.kernel.core.identity.StatementRecord;
import com.mars.harness.kernel.core.identity.SymbolRecord;
import com.mars.harness.kernel.engine.report.LineageService;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.tests.support.TestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario 9: {@code SecurityConfig.java} is renamed inside a migrating run. FILE_ID survives;
 * symbols and statements keep their lineage when confidently mapped; findings stay linked through
 * identity.
 */
class RenameDuringMigrationE2ETest {

    private static final String OLD = "src/main/java/com/acme/inventory/config/SecurityConfig.java";
    private static final String NEW = "src/main/java/com/acme/inventory/config/ApiKeyConfig.java";

    @Test
    void renamedFileKeepsItsIdentityAndItsFinding(@TempDir Path temp) {
        TestHarness h = TestHarness.over(temp, "composite/inventory-service");
        String run = h.analyzeComposite(true).runId();
        h.engine.decideExecution(run, "MIGRATE_FIRST", "dev.lead", "owner", "Upgrade, then fix");
        h.engine.resume(run, false);
        RunSession session = h.session(run);
        assertThat(h.json(run, "plans/migration-execution.json").path("status").asText()).isEqualTo("GREEN");

        Finding hardcoded = h.finding(run, "INV-104");
        String fileId = session.fileRegistry.byPath(OLD).orElseThrow().getFileId();
        assertThat(hardcoded.fileId()).isEqualTo(fileId);
        // the register names SecurityConfig.apiKeyCheck; the corroborating scanner hit anchors the finding on the literal
        SymbolRecord anchored = session.identity.symbols.get(hardcoded.symbolId());
        assertThat(anchored.name).isEqualTo("API_KEY");
        ProgramUnitRecord unit = session.identity.programUnits.get(anchored.programUnitId);
        SymbolRecord apiKeyCheck = session.identity.symbols.values().stream()
                .filter(s -> unit.programUnitId.equals(s.programUnitId) && "apiKeyCheck".equals(s.name)).findFirst().orElseThrow();
        List<String> statementsBefore = session.identity.statements.values().stream()
                .filter(s -> s.active() && apiKeyCheck.symbolId.equals(s.parentSymbolId)).map(StatementRecord::id).sorted().toList();
        assertThat(statementsBefore).isNotEmpty();

        // a developer renames the class and its file; the patch is a proposal like any other
        String renamed = TestHarness.read(session.layout.workspace().resolve(OLD)).replace("class SecurityConfig", "class ApiKeyConfig");
        ChangeProposal rename = h.engine.submitPatch(run, List.of(hardcoded.findingId()), Map.of(OLD, renamed), Map.of(OLD, NEW),
                "Rename SecurityConfig to ApiKeyConfig (it only holds the API key check)", ChangeProposal.ProviderType.MANUAL_PATCH,
                new ChangeProposal.Provenance("manual", null, null, null, null, null, null, null, List.of()));
        assertThat(rename.edits().get(0).operation()).isEqualTo("RENAME");
        assertThat(Files.exists(h.session(run).layout.workspace().resolve(NEW))).isFalse();   // not applied before approval
        h.approve(run, rename);
        h.engine.resume(run, true);

        session = h.session(run);
        assertThat(session.record.proposalStatus.get(rename.proposalId())).isIn("APPLIED", "VALIDATED", "FAILED_VALIDATION");
        assertThat(Files.exists(session.layout.workspace().resolve(NEW))).isTrue();
        assertThat(Files.exists(session.layout.workspace().resolve(OLD))).isFalse();
        // FILE_ID survives the rename
        assertThat(session.fileRegistry.byPath(NEW).orElseThrow().getFileId()).isEqualTo(fileId);
        // the program unit keeps its PROGRAM_UNIT_ID across the class rename
        ProgramUnitRecord unitAfter = session.identity.programUnits.get(unit.programUnitId);
        assertThat(unitAfter.active()).isTrue();
        assertThat(unitAfter.fqn).isEqualTo("com.acme.inventory.config.ApiKeyConfig");
        // the symbols keep their SYMBOL_IDs and now live in the renamed file
        assertThat(session.identity.symbols.get(anchored.symbolId).currentLocation.path()).isEqualTo(NEW);
        SymbolRecord after = session.identity.symbols.get(apiKeyCheck.symbolId);
        assertThat(after.active()).isTrue();
        assertThat(after.currentLocation.path()).isEqualTo(NEW);
        assertThat(after.fileId).isEqualTo(fileId);
        // its statements keep their STATEMENT_IDs (the method body did not change)
        List<String> statementsAfter = session.identity.statements.values().stream()
                .filter(s -> s.active() && apiKeyCheck.symbolId.equals(s.parentSymbolId)).map(StatementRecord::id).sorted().toList();
        assertThat(statementsAfter).isEqualTo(statementsBefore);
        // the finding is still linked, through identity, to the renamed location
        Finding linked = h.finding(run, "INV-104");
        assertThat(linked.symbolId()).isEqualTo(anchored.symbolId);
        assertThat(session.identity.symbols.get(linked.symbolId()).currentLocation.path()).isEqualTo(NEW);
        // lineage answers "what happened to this file"
        Map<String, Object> lineage = new LineageService(session).lineage(fileId);
        assertThat(KernelJson.mapper().valueToTree(lineage).toString()).contains(OLD).contains(NEW);
    }
}
