package com.mars.harness.kernel.engine.report;

import com.bootshift.adapters.mutation.FileMutationGateway;
import com.bootshift.core.identity.FileRecord;
import com.bootshift.core.identity.FileStatus;
import com.bootshift.core.ledger.ChangeEvent;
import com.bootshift.core.ledger.ChangeLedger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.evidence.EvidenceRecord;
import com.mars.harness.kernel.core.findings.Finding;
import com.mars.harness.kernel.core.identity.StatementRecord;
import com.mars.harness.kernel.core.identity.SymbolRecord;
import com.mars.harness.kernel.core.run.RunStateMachine;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.run.RunSession;
import com.mars.harness.kernel.ports.security.RemediationCapability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Human- and machine-readable reports (spec §37). Every factual statement in a report is read
 * from an artifact and carries the artifact or evidence reference it came from. A report is a
 * view: nothing ever reads a report back as state.
 */
public final class ReportRenderer {

    private final RunSession session;

    public ReportRenderer(RunSession session, EngineConfig config) {
        this.session = session;
    }

    // ================================================================== analysis report (Gate A)

    public String renderAnalysis() {
        StringBuilder md = new StringBuilder();
        md.append("# Analysis Report — ").append(session.layout.runId()).append("\n\n");
        md.append("> Read-only analysis. No source was changed. The harness **recommends**; the developer **decides** "
                + "(Human Gate A).\n\n");
        runIdentity(md);
        inventoryAndIdentity(md);
        baseline(md);
        migrationAssessment(md);
        securityFindings(md);
        combined(md);
        String text = md.toString();
        session.artifacts.writeText("reports", "analysis-report.md", text);
        return text;
    }

    // ================================================================== final report

    public String renderFinal() {
        StringBuilder md = new StringBuilder();
        md.append("# Final Evidence Report — ").append(session.layout.runId()).append("\n\n");
        JsonNode verdict = read("reports", "verdict.json");
        md.append("**Final verdict: ").append(verdict == null ? "NOT YET COMPUTED" : verdict.path("outcome").asText())
                .append("**  \n");
        md.append("Run state: `").append(session.record.machine.current).append("` · Strategy: `")
                .append(session.record.strategy).append("`\n\n");
        runIdentity(md);
        inventoryAndIdentity(md);
        baseline(md);
        migrationAssessment(md);
        securityFindings(md);
        decisions(md);
        executionSequence(md);
        changes(md);
        lineage(md);
        validation(md);
        verdictSection(md, verdict);
        gaps(md);
        flowDiagram(md);
        String text = md.toString();
        session.artifacts.writeText("reports", "final-report.md", text);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("run_id", session.layout.runId());
        json.put("state", session.record.machine.current.name());
        json.put("strategy", session.record.strategy);
        json.put("verdict", verdict);
        json.put("decisions", session.approvals.all());
        json.put("proposal_status", session.record.proposalStatus);
        json.put("validation", read("validation", "latest.json"));
        json.put("identity_coverage", session.identity == null ? null : session.identity.coverage());
        json.put("ledger_head", session.ledger.head());
        json.put("lineage_head", session.lineage.head());
        json.put("evidence_count", session.evidence.all().size());
        session.artifacts.writeJson("reports", "final-report.json", json);
        writeCumulativePatch();
        return text;
    }

    private void runIdentity(StringBuilder md) {
        md.append("## Run identity\n\n| Field | Value |\n|---|---|\n");
        row(md, "RUN_ID", session.layout.runId());
        row(md, "REPOSITORY_ID", session.record.repositoryId);
        row(md, "Repository", session.record.repository);
        JsonNode provenance = read("manifest", "source-provenance.json");
        if (provenance != null) {
            JsonNode p = provenance.path("source_provenance").isMissingNode() ? provenance : provenance.path("source_provenance");
            row(md, "Source kind", p.path("kind").asText(null));
            row(md, "Branch", p.path("branch").asText(null));
            row(md, "Commit", p.path("commitSha").asText(p.path("commit_sha").asText(null)));
            row(md, "Content manifest hash", p.path("contentManifestHash").asText(p.path("content_manifest_hash").asText(null)));
        }
        JsonNode env = read("manifest", "environment.json");
        if (env != null) {
            row(md, "JDK", env.path("java_version").asText() + " (" + env.path("java_vendor").asText() + ")");
            row(md, "OS", env.path("os").asText());
            row(md, "Build tool", env.path("build_tool").asText() + (env.path("build_tool_available").asBoolean()
                    ? " at " + env.path("build_tool_executable").asText() : " UNAVAILABLE: " + env.path("build_tool_unavailable_reason").asText()));
            row(md, "Harness / policy", env.path("harness_version").asText() + " / " + env.path("policy_version").asText());
        }
        row(md, "Evaluation date (lifecycle facts)", session.record.today);
        md.append("\n");
    }

    private void inventoryAndIdentity(StringBuilder md) {
        md.append("## Inventory, identity and graph\n\n");
        if (session.fileRegistry != null) {
            long active = session.fileRegistry.active().size();
            md.append("- Files with persistent `FILE_ID`: ").append(session.fileRegistry.size()).append(" (").append(active)
                    .append(" active) — Bootshift File Registry, `identity/file-registry.json`\n");
        }
        if (session.identity != null) {
            var c = session.identity.coverage();
            md.append("- Identity coverage: ").append(c.modules()).append(" MODULE_ID, ").append(c.programUnits())
                    .append(" PROGRAM_UNIT_ID, ").append(c.symbols()).append(" SYMBOL_ID, ").append(c.statements())
                    .append(" STATEMENT_ID (").append(c.activeStatements()).append(" active, ").append(c.deleted())
                    .append(" retired, ").append(c.uncertainAttachments()).append(" uncertain attachments) — `identity/identity-registry.json`\n");
        }
        if (session.graph != null) {
            md.append("- Canonical graph: ").append(session.graph.nodeCount()).append(" nodes / ").append(session.graph.edgeCount())
                    .append(" edges, source `").append(session.graph.graphSource).append("`, ")
                    .append(session.graph.identityBindings.size()).append(" Bootshift nodes bound to persistent identity — `graph/canonical-graph.json`\n");
            session.graph.coverageNotes.forEach(n -> md.append("  - coverage: ").append(n).append("\n"));
        }
        md.append("\n");
    }

    private void baseline(StringBuilder md) {
        JsonNode manifest = read("baseline", "baseline-manifest.json");
        md.append("## Baseline\n\n");
        if (manifest == null) {
            md.append("_Baseline not sealed._\n\n");
            return;
        }
        md.append("| Component | Value |\n|---|---|\n");
        manifest.fields().forEachRemaining(e -> row(md, e.getKey(), e.getValue().isValueNode() ? e.getValue().asText()
                : KernelJson.canonical(e.getValue())));
        md.append("\nPre-existing failures are recorded, never fixed or hidden; later validation compares against them.\n\n");
    }

    private void migrationAssessment(StringBuilder md) {
        JsonNode a = read("discovery/migration", "migration-assessment.json");
        md.append("## Migration assessment (advisory — never an authorization)\n\n");
        if (a == null) {
            md.append("_Not assessed._\n\n");
            return;
        }
        md.append("| Dimension | Value |\n|---|---|\n");
        row(md, "Traffic light", a.path("traffic_light").asText());
        row(md, "Why", a.path("traffic_light_rationale").asText());
        row(md, "Migration need / priority", a.path("need").asText() + " / " + a.path("priority").asText());
        row(md, "Complexity", a.path("complexity").asText());
        row(md, "Effort score (0-100, not a probability)", a.path("effort").path("migration_effort_score").asText()
                + " (weights " + a.path("effort").path("weights_version").asText() + ")");
        row(md, "Evidence confidence", a.path("evidence_confidence").asText());
        row(md, "Current platform", a.path("current").path("framework").asText() + " "
                + a.path("current").path("framework_version").asText() + " / Java " + a.path("current").path("java_version").asText()
                + " (lifecycle " + a.path("current").path("lifecycle_quality").asText() + ", support ends "
                + a.path("current").path("support_ends").asText() + ")");
        row(md, "Recommended target", a.path("recommended_target").path("framework").asText() + " "
                + a.path("recommended_target").path("version").asText() + " / Java "
                + a.path("recommended_target").path("java_version").asText() + " — " + a.path("recommended_target").path("basis").asText());
        row(md, "Recommended sequence", a.path("recommended_sequence").asText());
        md.append("\n**Effort factors** (contribution = weight × normalized):\n\n| Factor | Raw | Contribution | Evidence |\n|---|---|---|---|\n");
        for (JsonNode f : a.path("effort").path("factors")) {
            md.append("| ").append(f.path("name").asText()).append(" | ").append(f.path("known").asBoolean()
                    ? f.path("raw_value").asText() : "UNKNOWN").append(" | ").append(f.path("contribution").asText()).append(" | ")
                    .append(join(f.path("evidence_refs"))).append(" |\n");
        }
        if (a.path("objectives").size() > 0) {
            md.append("\n**Objectives**:\n\n");
            for (JsonNode o : a.path("objectives")) {
                md.append("- ").append(o.path("requires_migration").asBoolean() ? "**REQUIRES MIGRATION** " : "")
                        .append(o.path("description").asText()).append(o.path("why").isMissingNode() || o.path("why").isNull() ? ""
                                : " — " + o.path("why").asText()).append("\n");
            }
        }
        if (a.path("issues").size() > 0) {
            md.append("\n**Observed migration issues** (").append(a.path("issues").size()).append("):\n\n");
            for (JsonNode i : a.path("issues")) {
                md.append("- `").append(i.path("rule_id").asText()).append("` ").append(i.path("reference_section").asText())
                        .append(i.path("mandatory").asBoolean() ? " (mandatory)" : " (optional)").append(": ")
                        .append(i.path("subject").asText()).append(" at ").append(i.path("location").asText()).append("\n");
            }
        }
        if (a.path("unknowns").size() > 0) {
            md.append("\n**Unknowns** (kept unknown, never coerced):\n\n");
            a.path("unknowns").forEach(u -> md.append("- ").append(u.path("dimension").asText()).append(": ")
                    .append(u.path("question").asText()).append("\n"));
        }
        if (a.path("blockers").size() > 0) {
            md.append("\n**Blockers**:\n\n");
            a.path("blockers").forEach(b -> md.append("- ").append(b.path("description").asText()).append("\n"));
        }
        md.append("\n");
    }

    private void securityFindings(StringBuilder md) {
        md.append("## Security findings\n\n");
        if (session.findings.isEmpty()) {
            md.append("_No findings from the provided inputs or the harness scanner._\n\n");
            return;
        }
        md.append("| Finding | Source | CWE | Severity | Anchor | Platform requirement |\n|---|---|---|---|---|---|\n");
        for (Finding f : session.findings) {
            md.append("| ").append(f.findingId()).append(" (").append(f.sourceFindingId()).append(") | ").append(f.source())
                    .append(" | ").append(String.join(",", f.cwe())).append(" | ").append(f.severity()).append(" | ")
                    .append(f.anchorQuality()).append(" ").append(f.statementId() != null ? f.statementId()
                            : f.symbolId() != null ? f.symbolId() : f.fileId() == null ? "" : f.fileId()).append(" | ")
                    .append(f.platformRequirement() == null ? "—" : f.platformRequirement().requiresPlatform() + " "
                            + f.platformRequirement().requiresPlatformMinimum() + "+").append(" |\n");
        }
        md.append("\n");
    }

    private void combined(StringBuilder md) {
        JsonNode c = read("discovery", "combined-assessment.json");
        if (c == null) {
            return;
        }
        md.append("## Combined assessment and Human Gate A\n\n");
        md.append("- Sequence advice: **").append(c.path("sequence").path("sequence").asText()).append("** — ")
                .append(c.path("sequence").path("rationale").asText()).append("\n");
        md.append("- Harness recommendation: `").append(c.path("recommended_strategy").asText())
                .append("` (advice only; the developer's decision is authoritative)\n");
        c.path("interactions").forEach(i -> md.append("- Interaction ").append(i.path("kind").asText()).append(": ")
                .append(i.path("description").asText()).append("\n"));
        md.append("\nChoose one of: ").append(join(c.path("offered_strategies"))).append("\n\n");
    }

    private void decisions(StringBuilder md) {
        md.append("## Developer decisions (machine-authoritative)\n\n| Decision | Type | Selected | Actor (auth) | Rationale |\n|---|---|---|---|---|\n");
        for (Decision d : session.approvals.all()) {
            md.append("| ").append(d.decisionId()).append(" | ").append(d.type()).append(" | ").append(d.selected())
                    .append(d.proposalId() == null ? "" : " " + d.proposalId()).append(" | ").append(d.actor()).append(" / ")
                    .append(d.role()).append(" (").append(d.actorAuthentication()).append(") | ")
                    .append(escape(d.rationale())).append(" |\n");
        }
        md.append("\n");
    }

    private void executionSequence(StringBuilder md) {
        md.append("## Execution sequence\n\n");
        for (RunStateMachine.Transition t : session.record.machine.history) {
            md.append("1. `").append(t.from()).append("` → `").append(t.to()).append("` — ").append(escape(t.reason())).append("\n");
        }
        md.append("\n");
    }

    private void changes(StringBuilder md) {
        md.append("## Changes (every attempt, including refusals)\n\n| Proposal | Capability | Provider | Rule | Status | Changes |\n|---|---|---|---|---|---|\n");
        for (ChangeProposal p : session.proposals.all()) {
            md.append("| ").append(p.proposalId()).append(" | ").append(p.capability()).append(" | ").append(p.providerType())
                    .append(" | ").append(p.provenance() == null ? "" : String.valueOf(p.provenance().ruleId())).append(" | ")
                    .append(session.record.proposalStatus.get(p.proposalId())).append(" | ")
                    .append(String.join(", ", session.record.proposalChanges.getOrDefault(p.proposalId(), List.of()))).append(" |\n");
        }
        md.append("\n**Rejected / refused attempts**:\n\n");
        for (ChangeLedger.Entry entry : session.ledger.entries()) {
            if (entry.event().getStatus() == ChangeEvent.Status.REJECTED) {
                md.append("- ").append(entry.event().getChangeId()).append(" ").append(entry.event().getEdgeId()).append(": ")
                        .append(escape(entry.event().getRejectionReason())).append("\n");
            }
        }
        md.append("\nChange ledger head `").append(session.ledger.head()).append("` (").append(session.ledger.size())
                .append(" events); lineage ledger head `").append(session.lineage.head()).append("`\n\n");
    }

    private void lineage(StringBuilder md) {
        md.append("## File / symbol / statement lineage\n\n");
        if (session.fileRegistry != null) {
            for (FileRecord f : session.fileRegistry.all()) {
                if (f.getChangeIds().isEmpty()) {
                    continue;
                }
                md.append("- `").append(f.getFileId()).append("` ").append(f.getBaselinePath()).append(f.getBaselinePath()
                        .equals(f.getCurrentPath()) ? "" : " → " + f.getCurrentPath()).append(" [").append(f.getStatus())
                        .append("] changes ").append(f.getChangeIds()).append("\n");
            }
        }
        if (session.identity != null) {
            for (SymbolRecord s : session.identity.symbols.values()) {
                if (s.changeIds.isEmpty() || s.createdByChange != null && s.changeIds.size() == 1) {
                    continue;
                }
                md.append("  - `").append(s.symbolId).append("` ").append(s.baselineSignature).append(s.baselineSignature != null
                        && !s.baselineSignature.equals(s.signature) ? " → " + s.signature : "").append(" [").append(s.status)
                        .append("]\n");
            }
            long edited = session.identity.statements.values().stream().filter(s -> s.versions.size() > 1 && s.active()).count();
            long created = session.identity.statements.values().stream().filter(s -> s.createdByChange != null).count();
            long retired = session.identity.statements.values().stream().filter(s -> !s.active()).count();
            md.append("- Statements: ").append(edited).append(" edited with identity preserved, ").append(created)
                    .append(" newly allocated, ").append(retired).append(" retired (history kept)\n");
            for (StatementRecord s : session.identity.statements.values()) {
                if (s.active() && s.versions.size() > 1 && s.versions.size() <= 4) {
                    md.append("  - `").append(s.statementId).append("`: `").append(escape(s.versions.get(0).label()))
                            .append("` → `").append(escape(s.currentText)).append("`").append(s.everUncertain() ? " (UNCERTAIN)" : "")
                            .append("\n");
                }
            }
        }
        md.append("\nQuery any identity: `harness lineage <FILE-|PU-|SYM-|STMT-|FINDING-|CHANGE-> --run ")
                .append(session.layout.runId()).append("`\n\n");
    }

    private void validation(StringBuilder md) {
        JsonNode v = read("validation", "latest.json");
        md.append("## Validation dimensions\n\n");
        if (v == null) {
            md.append("_Not validated yet._\n\n");
            return;
        }
        md.append("| Dimension | Status | Reading | Summary | Mandatory |\n|---|---|---|---|---|\n");
        List<String> mandatory = new ArrayList<>();
        v.path("mandatory").forEach(m -> mandatory.add(m.asText()));
        for (JsonNode d : v.path("dimensions")) {
            md.append("| ").append(d.path("dimension").asText()).append(" | **").append(d.path("status").asText()).append("** | ")
                    .append(d.path("detail").asText("")).append(" | ").append(escape(d.path("summary").asText(""))).append(" | ")
                    .append(mandatory.contains(d.path("dimension").asText()) ? "yes" : "").append(" |\n");
        }
        md.append("\nUnexecuted dimensions are shown as NOT_RUN / NOT_COMPARED / TOOL_UNAVAILABLE and are never counted as PASS.\n\n");
    }

    private void verdictSection(StringBuilder md, JsonNode verdict) {
        md.append("## Verdict\n\n");
        if (verdict == null) {
            md.append("_Not computed._\n\n");
            return;
        }
        md.append("**").append(verdict.path("outcome").asText()).append("**\n\n");
        verdict.path("reasons").forEach(r -> md.append("- ").append(r.asText()).append("\n"));
        md.append("\n| Item | Status | Capability verdict | Reason |\n|---|---|---|---|\n");
        for (JsonNode item : verdict.path("items")) {
            md.append("| ").append(item.path("item_id").asText()).append(" | **").append(item.path("status").asText())
                    .append("** | ").append(escape(item.path("legacy_verdict").asText(""))).append(" | ")
                    .append(escape(item.path("reason").asText(""))).append(" |\n");
        }
        md.append("\n");
    }

    private void gaps(StringBuilder md) {
        md.append("## Known gaps, unknowns and residual risk\n\n");
        for (EvidenceRecord e : session.evidence.all()) {
            if (e.kind() == EvidenceRecord.EvidenceKind.UNKNOWN) {
                md.append("- UNKNOWN (").append(e.evidenceId()).append("): ").append(escape(e.summary()))
                        .append(e.observed() == null ? "" : " — " + escape(e.observed())).append("\n");
            }
        }
        session.record.notes.forEach(n -> md.append("- note: ").append(escape(n)).append("\n"));
        md.append("- Actor identity on decisions is LOCALLY_ASSERTED: integrity is checkable (HMAC), identity is not authenticated.\n\n");
    }

    private void flowDiagram(StringBuilder md) {
        md.append("## Executed flow\n\n```mermaid\nflowchart LR\n");
        RunStateMachine machine = session.record.machine;
        int i = 0;
        for (RunStateMachine.Transition t : machine.history) {
            md.append("    S").append(i).append("[").append(t.from()).append("] --> S").append(i + 1).append("[")
                    .append(t.to()).append("]\n");
            i++;
        }
        md.append("```\n");
    }

    /** Cumulative patch: snapshot vs workspace for every file the gateway changed (reference: {@code git diff baseline}). */
    private void writeCumulativePatch() {
        if (session.fileRegistry == null) {
            return;
        }
        StringBuilder patch = new StringBuilder();
        for (FileRecord f : session.fileRegistry.all()) {
            if (f.getChangeIds().isEmpty()) {
                continue;
            }
            String before = text(session.layout.sourceSnapshot().resolve(f.getBaselinePath()), f.getBaselineSha256() != null);
            String after = f.getStatus() == FileStatus.ACTIVE ? text(session.layout.workspace().resolve(f.getCurrentPath()), true) : null;
            patch.append(FileMutationGateway.unifiedDiff(f.getBaselineSha256() == null ? null : f.getBaselinePath(),
                    after == null ? null : f.getCurrentPath(), before, after, 3));
        }
        session.artifacts.writeText("reports", "cumulative.patch", patch.toString());
    }

    // ================================================================== VRH-compatible plan markdown

    /**
     * Renders {@code reports/legacy/04-remediation/fix_plan_<ISSUE>.md} in the VRH "At a glance"
     * format. The Status cell is rendered from the machine decision and never read back. Editing it
     * changes nothing.
     */
    public void renderLegacyPlans() {
        JsonNode node = read("plans", "remediation-plans.json");
        if (node == null) {
            return;
        }
        List<RemediationCapability.RemediationPlan> plans = KernelJson.mapper().convertValue(node,
                new TypeReference<List<RemediationCapability.RemediationPlan>>() { });
        for (RemediationCapability.RemediationPlan p : plans) {
            String status = "Proposed";
            if (p.proposal() != null) {
                Optional<Decision> d = session.approvals.latestForProposal(p.proposal().proposalId());
                if (d.isPresent()) {
                    status = switch (d.get().selected()) {
                        case "APPROVED" -> "Approved";
                        case "REJECTED" -> "Rejected";
                        default -> "Proposed (deferred)";
                    };
                }
            }
            StringBuilder md = new StringBuilder();
            md.append("# Fix Plan — ").append(p.issueId()).append("\n\n");
            md.append("> Compatibility rendering of the machine plan `plans/remediation-plans.json`. The authoritative "
                    + "approval is a decision artifact under `decisions/`; **editing this Status cell has no effect**.\n\n");
            md.append("## At a glance\n\n| | |\n|---|---|\n");
            md.append("| **Status** | ").append(status).append(" |\n");
            md.append("| **CWE** | ").append(p.cwe() == null ? "—" : p.cwe()).append(p.catalogTitle() == null
                    ? " — **catalog gap, see Open Questions**" : " — " + p.catalogTitle()).append(" |\n");
            md.append("| **OWASP** | ").append(p.owasp() == null ? "n/a" : p.owasp()).append(" |\n");
            md.append("| **Confidence** | ").append(p.confidence()).append(" |\n");
            md.append("| **Route** | ").append(p.route()).append(" |\n");
            if (p.researchStatus() != null) {
                md.append("| **Research status** | ").append("insufficient_evidence".equals(p.researchStatus())
                        ? "⚠ EVIDENCE GAP — needs investigation" : p.researchStatus()).append(" |\n");
            }
            md.append("| **Proposal** | ").append(p.proposal() == null ? "—" : p.proposal().proposalId()
                    + (p.proposal().strategyOnly() ? " (strategy only)" : "")).append(" |\n");
            if (p.blockedByPlatform()) {
                md.append("| **Blocked by platform** | requires ").append(p.platformRequirement().requiresPlatform()).append(" ")
                        .append(p.platformRequirement().requiresPlatformMinimum()).append("+ |\n");
            }
            md.append("\n## Summary\n\n").append(p.plainSummary()).append("\n\n## Approach\n\n").append(p.approach()).append("\n\n");
            section(md, "Alternatives considered", p.alternatives());
            section(md, "Risks", p.riskNotes());
            section(md, "Verification plan", p.verificationPlan());
            section(md, "Open questions", p.openQuestions());
            session.artifacts.writeText("reports/legacy/04-remediation", "fix_plan_" + p.issueId() + ".md", md.toString());
        }
    }

    private static void section(StringBuilder md, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        md.append("## ").append(title).append("\n\n");
        items.forEach(i -> md.append("- ").append(i).append("\n"));
        md.append("\n");
    }

    // ================================================================== helpers

    private JsonNode read(String area, String name) {
        Path file = session.layout.area(area).resolve(name);
        return Files.isRegularFile(file) ? KernelJson.read(file) : null;
    }

    private static String text(Path file, boolean exists) {
        try {
            return exists && Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void row(StringBuilder md, String key, String value) {
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            md.append("| ").append(key).append(" | ").append(escape(value)).append(" |\n");
        }
    }

    private static String join(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return String.join(", ", values);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
