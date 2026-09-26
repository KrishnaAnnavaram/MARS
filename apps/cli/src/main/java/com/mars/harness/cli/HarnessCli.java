package com.mars.harness.cli;

import com.mars.harness.kernel.core.KernelJson;
import com.mars.harness.kernel.core.change.ChangeProposal;
import com.mars.harness.kernel.core.decision.Decision;
import com.mars.harness.kernel.core.outcome.HarnessOutcomeException;
import com.mars.harness.kernel.engine.EngineConfig;
import com.mars.harness.kernel.engine.HarnessEngine;
import com.mars.harness.kernel.engine.mutation.ProjectApplier;
import com.mars.harness.kernel.engine.report.LineageService;
import com.mars.harness.kernel.engine.run.RunSession;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code harness}: the single developer entry point. It is not a chatbot. Every command either
 * reads artifacts or records one explicit human decision, and nothing executes without a
 * recorded decision.
 */
@Command(name = "harness", mixinStandardHelpOptions = true, version = "harness " + HarnessEngine.HARNESS_VERSION,
        description = "MARS - Migration and Remediation System",
        subcommands = {HarnessCli.Analyze.class, HarnessCli.Status.class, HarnessCli.MigrationAssessmentCmd.class,
                HarnessCli.Findings.class, HarnessCli.Proposals.class, HarnessCli.Decide.class, HarnessCli.Approve.class,
                HarnessCli.SubmitPatch.class, HarnessCli.SubmitResearch.class, HarnessCli.Resume.class, HarnessCli.Report.class,
                HarnessCli.Lineage.class, HarnessCli.FindingCmd.class, HarnessCli.ChangeCmd.class, HarnessCli.Apply.class,
                HarnessCli.Verify.class})
public final class HarnessCli implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String... args) {
        CommandLine cli = new CommandLine(new HarnessCli());
        cli.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            if (ex instanceof HarnessOutcomeException outcome) {
                commandLine.getErr().println(outcome.getMessage());
                outcome.details().forEach(d -> commandLine.getErr().println("  - " + d));
                return outcome.category().exitCode();
            }
            commandLine.getErr().println("FAILURE: " + ex);
            return 1;
        });
        return cli.execute(args);
    }

    /** Options every command shares. */
    static class Common {
        @Option(names = "--harness-root", description = "Harness installation root (default: auto-detect / HARNESS_HOME)")
        Path harnessRoot;
        @Option(names = "--runs-root", description = "Where run artifact planes live (default: <harness-root>/runs or HARNESS_RUNS_ROOT)")
        Path runsRoot;
        @Option(names = "--policy", description = "Unified policy file (default: policies/default/unified-policy.json)")
        Path policy;
        @Option(names = "--today", description = "Evaluation date for lifecycle facts (ISO date; default: today)")
        LocalDate today;
        @Option(names = "--maven-offline", description = "Run Maven with -o")
        boolean mavenOffline;
        @Option(names = "--network", description = "Allow Bootshift's allow-listed HTTP egress (off by default)")
        boolean network;
        @Option(names = "--json", description = "Machine-readable output")
        boolean json;

        HarnessEngine engine() {
            Path root = HarnessFactory.locateHarnessRoot(harnessRoot);
            Path runs = runsRoot != null ? runsRoot : System.getenv("HARNESS_RUNS_ROOT") != null
                    ? Path.of(System.getenv("HARNESS_RUNS_ROOT")) : root.resolve("runs");
            EngineConfig config = HarnessFactory.config(root, runs, policy, today, mavenOffline, network);
            return HarnessFactory.engine(config);
        }

        void print(Object value) {
            if (json) {
                System.out.println(KernelJson.pretty(value));
            } else if (value instanceof HarnessEngine.RunSummary s) {
                System.out.println("run      : " + s.runId());
                System.out.println("phase    : " + s.phase());
                if (s.verdict() != null) {
                    System.out.println("verdict  : " + s.verdict());
                }
                if (s.waitingFor() != null) {
                    System.out.println("waiting  : " + s.waitingFor());
                }
                int width = s.highlights().keySet().stream().mapToInt(String::length).max().orElse(9);
                s.highlights().forEach((k, v) -> {
                    if (v != null && !(v instanceof Map<?, ?> m && m.isEmpty())) {
                        System.out.println(String.format("%-" + width + "s : %s", k, v));
                    }
                });
                if (!s.nextActions().isEmpty()) {
                    System.out.println("next:");
                    s.nextActions().forEach(a -> System.out.println("  " + a));
                }
            } else {
                System.out.println(KernelJson.pretty(value));
            }
        }
    }

    @Command(name = "analyze", description = "Phases 0-4: ingest, inventory + identity, graph, baseline seal, read-only discovery; stops at Human Gate A")
    static final class Analyze implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Parameters(index = "0", description = "Repository to analyze (never modified)")
        Path repository;
        @Option(names = "--findings", description = "Finding sources: VRH issue-register .xlsx, SARIF, *.advisories.json")
        List<Path> findings = new ArrayList<>();
        @Option(names = "--research", description = "ISSUE-ID=path to a 04d research analysis.json (judgement input)")
        Map<String, Path> research = new LinkedHashMap<>();
        @Option(names = "--probes", description = "Behaviour probe file (reference probes.json format)")
        Path probes;
        @Option(names = "--skip-build", description = "Do not run the baseline build (recorded as NOT_RUN, never as passed)")
        boolean skipBuild;

        @Override
        public Integer call() {
            HarnessEngine engine = common.engine();
            common.print(engine.analyze(new HarnessEngine.AnalyzeRequest(repository, findings, research, probes, skipBuild)));
            return 0;
        }
    }

    @Command(name = "status", description = "Where a run is and what it is waiting for")
    static final class Status implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            common.print(common.engine().status(run));
            return 0;
        }
    }

    @Command(name = "migration-assessment", description = "The advisory migration assessment (never an authorization)")
    static final class MigrationAssessmentCmd implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() throws Exception {
            RunSession s = common.engine().load(run);
            System.out.println(Files.readString(s.layout.area("discovery").resolve("migration").resolve("migration-assessment.json")));
            return 0;
        }
    }

    @Command(name = "findings", description = "Canonical findings with identity anchors")
    static final class Findings implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            RunSession s = common.engine().load(run);
            if (common.json) {
                common.print(s.findings);
            } else {
                s.findings.forEach(f -> System.out.println(f.findingId() + "  " + f.sourceFindingId() + "  " + String.join(",", f.cwe())
                        + "  " + f.severity() + "  anchor=" + f.anchorQuality() + (f.platformRequirement() == null ? ""
                        : "  requires " + f.platformRequirement().requiresPlatform() + " " + f.platformRequirement().requiresPlatformMinimum() + "+")
                        + "  " + f.title()));
            }
            return 0;
        }
    }

    @Command(name = "proposals", description = "Change proposals and their gate status")
    static final class Proposals implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            RunSession s = common.engine().load(run);
            if (common.json) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ChangeProposal p : s.proposals.all()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("proposal_id", p.proposalId());
                    row.put("capability", p.capability());
                    row.put("provider_type", p.providerType());
                    row.put("provider", p.provider());
                    row.put("status", s.record.proposalStatus.get(p.proposalId()));
                    row.put("strategy_only", p.strategyOnly());
                    row.put("finding_refs", p.findingRefs());
                    row.put("rule_id", p.provenance() == null ? null : p.provenance().ruleId());
                    row.put("reason", p.reason());
                    row.put("proposal_hash", p.proposalHash());
                    rows.add(row);
                }
                common.print(rows);
                return 0;
            }
            for (ChangeProposal p : s.proposals.all()) {
                System.out.println(p.proposalId() + "  " + p.capability() + "/" + p.providerType() + "  status="
                        + s.record.proposalStatus.get(p.proposalId()) + (p.strategyOnly() ? "  (strategy only)" : "")
                        + "  findings=" + p.findingRefs() + "  sha256=" + p.proposalHash().substring(0, 16));
                System.out.println("    " + p.reason());
                if (!common.json) {
                    p.edits().forEach(e -> System.out.print(indent(e.unifiedDiff())));
                }
            }
            return 0;
        }

        private static String indent(String diff) {
            return diff == null ? "" : diff.lines().map(l -> "    " + l + "\n").reduce("", String::concat);
        }
    }

    @Command(name = "decide", description = "Human Gate A (execution) and Gate A2 (post-security migration)",
            subcommands = {Decide.Execution.class, Decide.Migration.class})
    static final class Decide implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        static class Who {
            @Option(names = "--actor", required = true)
            String actor;
            @Option(names = "--role", required = true)
            String role;
            @Option(names = "--rationale", required = true)
            String rationale;
        }

        @Command(name = "execution", description = "Choose MIGRATE_FIRST | SECURITY_FIRST | MIGRATION_ONLY | SECURITY_ONLY | ANALYZE_ONLY | STOP")
        static final class Execution implements Callable<Integer> {
            @CommandLine.Mixin
            Common common;
            @CommandLine.Mixin
            Who who;
            @Option(names = "--run", required = true)
            String run;
            @Option(names = "--strategy", required = true)
            String strategy;

            @Override
            public Integer call() {
                Decision d = common.engine().decideExecution(run, strategy, who.actor, who.role, who.rationale);
                System.out.println("recorded " + d.decisionId() + ": " + d.selected() + " (harness recommended " + d.recommendation() + ")");
                System.out.println("next: harness resume --run " + run);
                return 0;
            }
        }

        @Command(name = "migration", description = "Gate A2 after security work: PROCEED | SKIP | STOP")
        static final class Migration implements Callable<Integer> {
            @CommandLine.Mixin
            Common common;
            @CommandLine.Mixin
            Who who;
            @Option(names = "--run", required = true)
            String run;
            @Option(names = "--decision", required = true)
            String decision;

            @Override
            public Integer call() {
                Decision d = common.engine().decidePostSecurityMigration(run, decision, who.actor, who.role, who.rationale);
                System.out.println("recorded " + d.decisionId() + ": " + d.selected());
                System.out.println("next: harness resume --run " + run);
                return 0;
            }
        }
    }

    @Command(name = "approve", description = "Human Gate B (remediation proposals), migration plan, apply-to-project",
            subcommands = {Approve.Remediation.class, Approve.MigrationPlan.class, Approve.ApplyDecision.class})
    static final class Approve implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        @Command(name = "remediation", description = "Approve, reject or defer one exact proposal (bound to its hash and the baseline seal)")
        static final class Remediation implements Callable<Integer> {
            @CommandLine.Mixin
            Common common;
            @CommandLine.Mixin
            Decide.Who who;
            @Option(names = "--run", required = true)
            String run;
            @Option(names = "--proposal", required = true)
            String proposal;
            @Option(names = "--verdict", defaultValue = "APPROVED", description = "APPROVED | REJECTED | DEFERRED")
            String verdict;

            @Override
            public Integer call() {
                Decision d = common.engine().decideProposal(run, proposal, verdict, who.actor, who.role, who.rationale);
                System.out.println("recorded " + d.decisionId() + ": " + d.selected() + " " + d.proposalId() + " (hash "
                        + d.proposalHash().substring(0, 16) + ", baseline " + d.baselineSeal().substring(0, 16) + ")");
                return 0;
            }
        }

        @Command(name = "migration", description = "Approve the frozen migration plan (when policy requires it)")
        static final class MigrationPlan implements Callable<Integer> {
            @CommandLine.Mixin
            Common common;
            @CommandLine.Mixin
            Decide.Who who;
            @Option(names = "--run", required = true)
            String run;
            @Option(names = "--verdict", defaultValue = "APPROVED")
            String verdict;

            @Override
            public Integer call() {
                Decision d = common.engine().decideMigrationPlan(run, verdict, who.actor, who.role, who.rationale);
                System.out.println("recorded " + d.decisionId() + ": " + d.selected() + " plan " + d.planId());
                return 0;
            }
        }

        @Command(name = "apply", description = "Authorize applying the final result to the original project (bound to the ledger head)")
        static final class ApplyDecision implements Callable<Integer> {
            @CommandLine.Mixin
            Common common;
            @CommandLine.Mixin
            Decide.Who who;
            @Option(names = "--run", required = true)
            String run;
            @Option(names = "--verdict", defaultValue = "APPROVED")
            String verdict;

            @Override
            public Integer call() {
                Decision d = common.engine().decideApply(run, verdict, who.actor, who.role, who.rationale);
                System.out.println("recorded " + d.decisionId() + ": " + d.selected() + " (ledger head " + d.ledgerHead().substring(0, 16) + ")");
                System.out.println("next: harness apply --run " + run + " --decision " + d.decisionId());
                return 0;
            }
        }
    }

    @Command(name = "submit-patch", description = "Register a human- or agent-written patch as a PROPOSAL (never applied without approval)")
    static final class SubmitPatch implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;
        @Option(names = "--finding", description = "FINDING-ID(s) the patch remediates (omit for a migration/manual patch)")
        List<String> findings = new ArrayList<>();
        @Option(names = "--file", description = "repo/relative/path=local/file/with/new/content")
        Map<String, Path> files = new LinkedHashMap<>();
        @Option(names = "--rename", description = "old/repo/path=new/repo/path (FILE_ID and sub-file identities travel with it)")
        Map<String, String> renames = new LinkedHashMap<>();
        @Option(names = "--reason", required = true)
        String reason;
        @Option(names = "--provider", defaultValue = "manual", description = "manual | llm")
        String provider;
        @Option(names = "--model")
        String model;
        @Option(names = "--model-version")
        String modelVersion;
        @Option(names = "--prompt-hash")
        String promptHash;
        @Option(names = "--context-hash")
        String contextHash;
        @Option(names = "--response-hash")
        String responseHash;

        @Override
        public Integer call() throws Exception {
            Map<String, String> contents = new LinkedHashMap<>();
            for (Map.Entry<String, Path> e : files.entrySet()) {
                contents.put(e.getKey(), Files.readString(e.getValue(), StandardCharsets.UTF_8));
            }
            boolean llm = provider.equalsIgnoreCase("llm");
            ChangeProposal.Provenance provenance = new ChangeProposal.Provenance(llm ? "llm-repair-agent" : "manual", null, null,
                    model, modelVersion, promptHash, contextHash, responseHash, List.of());
            if (contents.isEmpty() && renames.isEmpty()) {
                throw new CommandLine.ParameterException(new CommandLine(this), "give at least one --file or --rename");
            }
            ChangeProposal p = common.engine().submitPatch(run, findings, contents, renames, reason,
                    llm ? ChangeProposal.ProviderType.LLM : ChangeProposal.ProviderType.MANUAL_PATCH, provenance);
            System.out.println("registered " + p.proposalId() + " (hash " + p.proposalHash().substring(0, 16)
                    + "); it is Proposed and needs: harness approve remediation --run " + run + " --proposal " + p.proposalId());
            return 0;
        }
    }

    @Command(name = "submit-research", description = "Supply a 04d research analysis.json for a double-gap finding")
    static final class SubmitResearch implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;
        @Option(names = "--finding", required = true)
        String finding;
        @Option(names = "--analysis", required = true)
        Path analysis;

        @Override
        public Integer call() {
            common.engine().submitResearch(run, finding, analysis);
            System.out.println("research input recorded for " + finding);
            return 0;
        }
    }

    @Command(name = "resume", description = "Advance the run to its next human gate or to its verdict")
    static final class Resume implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;
        @Option(names = "--accept-pending", description = "Continue although some proposals are undecided (they stay unapproved)")
        boolean acceptPending;

        @Override
        public Integer call() {
            common.print(common.engine().resume(run, acceptPending));
            return 0;
        }
    }

    @Command(name = "report", description = "Render and print the final evidence report")
    static final class Report implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            System.out.println(common.engine().report(run));
            return 0;
        }
    }

    @Command(name = "lineage", description = "Lineage of FILE-/MOD-/PU-/SYM-/STMT-/FINDING-/CHANGE-/PROP-/DEC- identities")
    static final class Lineage implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Parameters(index = "0")
        String id;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            System.out.println(new LineageService(common.engine().load(run)).render(id));
            return 0;
        }
    }

    @Command(name = "finding", description = "A finding with its plans, proposals and decisions")
    static final class FindingCmd implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Parameters(index = "0")
        String id;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            System.out.println(new LineageService(common.engine().load(run)).render(id));
            return 0;
        }
    }

    @Command(name = "change", description = "A CHANGE_ID with its ledger events, lineage and patch")
    static final class ChangeCmd implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Parameters(index = "0")
        String id;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            System.out.println(new LineageService(common.engine().load(run)).render(id));
            return 0;
        }
    }

    @Command(name = "apply", description = "Apply the run's result to the original project (explicit, decision-bound, refused unless safe)")
    static final class Apply implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;
        @Option(names = "--decision", required = true)
        String decision;

        @Override
        public Integer call() {
            ProjectApplier.ApplyResult result = common.engine().applyToProject(run, decision);
            System.out.println("applied: " + result.written() + " written, " + result.deleted() + " deleted " + result.files());
            return 0;
        }
    }

    @Command(name = "verify", description = "Recompute every hash chain and integrity check from disk; run bypass detection")
    static final class Verify implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;
        @Option(names = "--run", required = true)
        String run;

        @Override
        public Integer call() {
            RunSession s = common.engine().load(run);
            Map<String, Object> out = new LinkedHashMap<>();
            var ledger = com.bootshift.core.ledger.ChangeLedger.verify(s.layout.ledgerFile(), s.layout.ledgerHead());
            out.put("change_ledger", ledger.valid() ? "INTACT (" + ledger.verifiedEvents() + " events)" : ledger.violations());
            List<String> lineage = com.mars.harness.kernel.engine.ledger.LineageLedger.verify(s.layout.lineageLedger());
            out.put("lineage_ledger", lineage.isEmpty() ? "INTACT" : lineage);
            List<String> evidence = com.mars.harness.kernel.core.evidence.EvidenceLog.verify(s.layout.evidenceLog());
            out.put("evidence_log", evidence.isEmpty() ? "INTACT" : evidence);
            List<String> decisions = s.approvals.verifyIntegrity();
            out.put("decisions", decisions.isEmpty() ? "INTACT" : decisions);
            common.print(out);
            boolean ok = ledger.valid() && lineage.isEmpty() && evidence.isEmpty() && decisions.isEmpty();
            return ok ? 0 : 3;
        }
    }
}
