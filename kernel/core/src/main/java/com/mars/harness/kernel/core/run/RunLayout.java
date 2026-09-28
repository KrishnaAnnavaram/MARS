package com.mars.harness.kernel.core.run;

import java.nio.file.Path;

/**
 * Canonical per-run artifact layout (spec §26).
 *
 * <pre>
 * runs/&lt;RUN_ID&gt;/
 *   manifest/            run.json, environment fingerprint
 *   original/            immutable source snapshot (Bootshift's read-only original workspace)
 *   migration/           the ONLY mutable tracked tree; written solely by the Mutation Gateway
 *   exec/&lt;label&gt;/        disposable build/runtime copies (never copied back)
 *   bootshift-output/    Bootshift stage artifacts (pointer-after-write, unchanged format)
 *   internal-checkpoint-git/  Bootshift checkpoint repository
 *   inventory/ identity/ graph/ baseline/ discovery/{migration,security}/ decisions/ plans/
 *   proposals/ mutations/ checkpoints/ validation/ findings/ reports/ provenance/ ledger/ state/
 *   events/              execution events (append-only witnesses of what ran; never authority)
 * </pre>
 *
 * <p>Bootshift's {@code RunContext} is constructed with {@code workspaceRoot = runsRoot}, so its
 * {@code runWorkspace()} is exactly this directory. Both systems share one tree and one ledger
 * rather than keeping parallel copies.
 */
public record RunLayout(Path runsRoot, String runId) {

    public Path runDir() {
        return runsRoot.resolve(runId);
    }

    public Path manifest() {
        return runDir().resolve("manifest");
    }

    public Path sourceSnapshot() {
        return runDir().resolve("original");
    }

    public Path workspace() {
        return runDir().resolve("migration");
    }

    public Path exec(String label) {
        return runDir().resolve("exec").resolve(label);
    }

    public Path bootshiftOutput() {
        return runDir().resolve("bootshift-output");
    }

    public Path checkpointGit() {
        return runDir().resolve("internal-checkpoint-git");
    }

    public Path area(String name) {
        return runDir().resolve(name);
    }

    public Path identityRegistry() {
        return area("identity").resolve("identity-registry.json");
    }

    public Path fileRegistry() {
        return area("identity").resolve("file-registry.json");
    }

    public Path canonicalGraph() {
        return area("graph").resolve("canonical-graph.json");
    }

    public Path ledgerFile() {
        return area("ledger").resolve("change-ledger.jsonl");
    }

    public Path ledgerHead() {
        return area("ledger").resolve("change-ledger-head.json");
    }

    public Path lineageLedger() {
        return area("ledger").resolve("lineage-ledger.jsonl");
    }

    public Path evidenceLog() {
        return area("provenance").resolve("evidence.jsonl");
    }

    public Path patches() {
        return area("mutations").resolve("patches");
    }

    public Path state() {
        return area("state").resolve("run-state.json");
    }

    public Path findings() {
        return area("findings").resolve("findings.json");
    }

    public Path decisions() {
        return area("decisions");
    }

    public Path proposals() {
        return area("proposals");
    }

    public Path logs() {
        return area("logs");
    }

    public Path events() {
        return area("events").resolve("events.jsonl");
    }
}
