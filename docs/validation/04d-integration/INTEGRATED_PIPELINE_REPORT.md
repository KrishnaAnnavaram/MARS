# Integrated Pipeline Report — Round C (01 → 04 → 04D → 05 → 06 → 07)

**Verdict:** Agent 04 selected 04D during the actual run, on its own routing rules. 04D migrated the
application (Spring Boot 3.5.0 / Java 17 → 4.1.1 / Java 21), produced the standard `fix_<id>.md` /
`.diff` handoff, the detailed migration report and the per-run summary, and agents 05, 06 and 07
consumed the result. The pipeline terminated cleanly with a **Blocked** verdict. The block comes from
the build hard gate: two pre-existing test failures, which also fail on the untouched 3.5.0 baseline.
The migration did not cause the block.

| | |
|---|---|
| Repository | `E:/mv/R`, a scratch git repository. The app is `src/spring-boot-migration-demo`, a verbatim export of `Udaradg/sample-java-project@6978c4a` (Spring Boot 3.5.0, Java 17). The harness is a byte-for-byte copy of branch `validate/04d-integration` (`.claude/`, `.github/`), plus MARS `.gitattributes` |
| Issue | `ISSUE-001` (register row; validation fixture): the service runs on Spring Boot 3.5.0 / Java 17, a line past its OSS support window, and the platform standard is 4.1.1 / Java 21. Detection notes are the end-of-support markers |
| Invocation model | Sequential agent-by-name. Each agent ran as a subagent whose system instructions are the repository's own `.claude/agents/NN_*.agent.md`, which is what Claude Code loads when it invokes the agent by name. **No agent prompt mentioned 04D or migration routing.** |
| Approval | **APPROVAL_MODE=TEST_AUTO_APPROVED**: *"Approval was programmatically granted for controlled pipeline validation; this does not replace the production human approval requirement."* A validation-only helper outside the harness (`E:/mv/tools/test-approve.js`) refuses unless that env var is set, edits Status Proposed → Approved, and records an **Approved by** cell that the renderer preserves. The production gate is unchanged: no harness script can approve a plan. |
| Toolchain | Temurin JDK 17.0.20.1 (source, `MIGRATION_JDK_17`), Microsoft JDK 21.0.11 (target, `MIGRATION_JDK_21`), Maven 3.9.9 (`MIGRATION_MVN`; the app has no wrapper), Node 22.14.0 |
| Infrastructure | Docker Desktop 27.3.1, started for this round and shut down afterwards. Only Testcontainers' `postgres:16` and Ryuk 0.8.1 ran, and Ryuk reaped them. Runtime probes ran on in-memory H2 (`--spring.datasource.url=jdbc:h2:mem:probedb`, identical before and after). Neo4j: **NOT EXECUTED — OPTIONAL DEPENDENCY NOT CONFIGURED** |

## Live trace (condensed from the agents' own trace files)

Full, unedited traces: [`evidence/roundC/trace-01.log` … `trace-07.log`](./evidence/roundC/) (164 lines).
In the block below:
- the `[01]`–`[04D]` lines are taken from those files, some shortened;
- the `[05]`–`[07]` result lines summarise several trace lines each;
- the `[RUN]` and `[APPROVAL]` markers come from the orchestrator and `evidence/roundC/approval.log`.

```text
[RUN] round-c / E:/mv/R @ c4a1537..2ac6da2 (validate/04d-integration harness)

[01] Architect started (full)
[01] $ scan.js -> 1 module, 17 files, 17 types; artifacts.json written
[01] $ list-context-workload.js -> 39 of ~144 nodes need description
[01] Architect finished.
[02] Root cause started
[02] $ collect-evidence.js --all -> ISSUE-001 evidence collected; Neo4j NOT EXECUTED — OPTIONAL DEPENDENCY NOT CONFIGURED
[02] $ list-issues.js -> ISSUE-001 report written (1 of 1)
[03] Blast radius started
[03] $ collect-impact.js --all -> 1 broken svc, 0 reach / 7 hosted endpoints, 0 jobs
[03] Blast radius finished: Assessed 1 of 1 root causes

[04] Fix generation started (run 1)
[04][ROUTER] ISSUE-001 Fix Type=VERSION_MIGRATION -> 04d-version-migration (evidence: strategy recorded migration intent
             Spring Boot 3.5.0 -> 4.1.1, Java 17 -> 21; pom.xml declares spring-boot-starter-parent 3.5.0 as parent - source
             platform proven; pom.xml declares Java 17; major generation 3.x -> 4.x; Java target 17 -> 21)
             -- NOT DISPATCHED: Status=Proposed, not Approved
[APPROVAL] fix_plan_ISSUE-001.md: Proposed -> Approved by APPROVAL_MODE=TEST_AUTO_APPROVED
[04] Fix generation started (run 2)
[04][ROUTER] ISSUE-001 Fix Type=VERSION_MIGRATION -> 04d-version-migration (...) -- Status=Approved (Approved by TEST_AUTO_APPROVED) -> DISPATCHED
[04D] $ detect-baseline.js --issue ISSUE-001 -> exit 0; plan re-checked (Status Approved, Fix Type VERSION_MIGRATION,
      approval TEST_AUTO_APPROVED); project src/spring-boot-migration-demo, Boot parent 3.5.0 -> 4.1.1, Java 17 -> 21; slug issue-001
[04D] baseline: pack spring-boot-3-to-4 eligible; migration path gate SUPPORTED (3.5.0 -> 4.1.1, preparation line 3.5.x on it)
[04D] $ prepare-workspace.js --slug issue-001 -> sandbox, 29 files, baseline commit d24d154165
[04D][BUILD] round 0: mvn -B clean package on JDK 17.0.20.1 -> TESTS-FAILED; Tests run 19, Failures 2 (pre-existing @WebMvcTest actuator 404s)
[04D][RUNTIME] baseline probe on JDK 17.0.20.1: started 13.551s, readiness 200; 19/19 answered; all expect_status met
[04D] Step 5: migration-plan.json (13 impacts, 8 constraints, 1 deterministic candidate boot4-curated); --check-plan valid
[04D][TRANSFORM] rewrite-00 dry-run PREVIEWED; rewrite-maven-plugin 6.46.1, rewrite-spring 6.37.1; 7 files proposed
[04D][TRANSFORM] REJECT ErrorResponse.java (would change the error timestamp contract); REJECT pom hunk un-pinning Testcontainers
[04D][TRANSFORM] rewrite-01 APPLIED; 6 files changed, all inside the inspected preview; ErrorResponse.java restored
[04D][BUILD] round 1: test-compile on JDK 21.0.11 -> PASSED (8.6s)
[04D] residual edit (harness-residual): restore Testcontainers 1.20.1 pin (evidence: dependency:tree mixed 2.0.5 / 1.20.1)
[04D][BUILD] round 2: package on JDK 21.0.11 -> TESTS-FAILED; 19 run / 2 failed — identical to round 0; no new failures
[04D][BUILD] round 3: package -DskipTests on JDK 21.0.11 -> PASSED (runnable jar)
[04D][RUNTIME] final probe on JDK 21.0.11: started 13.981s, readiness 200; 19/19 statuses identical
[04D] $ render-migration-report.js --slug issue-001 -> migration_issue-001.md/.diff, fix_ISSUE-001.md (Compiled,
      Migration Status PARTIAL PASS) + fix_ISSUE-001.diff, MIGRATION_SUMMARY.md [PARTIAL PASS]

[05] Consuming 1 fix: ISSUE-001 (Status: Compiled, CWE-1104, Fix Type: VERSION_MIGRATION; Migration Status PARTIAL PASS)
[05] (run 2) collectors: worktree applied in all three; Version migration context present in every briefing
[05] rescan FIXED · redteam NO_BYPASS_FOUND · behavior BEHAVIOR_CHANGED
[06] Consuming fix ISSUE-001: Status Compiled, Fix Type VERSION_MIGRATION, Target Java 21
[06] QA gate PASS on JDK 21.0.11 (MIGRATION_JDK_21) · build gate FAIL (19 run / 2 failed — the round-0 failures)
[07] Consuming 1 fix: ISSUE-001 — Fix Type VERSION_MIGRATION, Migration Status PARTIAL PASS
[07] compute-score -> 70/100 (threshold 75) -> Blocked; HARD GATE build-gatekeeper; migration gate evaluated: clear
[07] No branch/push/PR (no explicit request; verdict Blocked).
[RUN] Completed
```

## Stage results

| Stage | Output | Result |
|---|---|---|
| 01 | `01-architecture/architecture.md`, `function-reference.md`; 39/39 nodes described | ✅ (Graph Forge not executed, optional) |
| 02 | `02-root-cause/root_cause_ISSUE-001.md` | ✅ 3.5.0 parent pin + Java 17 in pom (×2) and Dockerfile; confidence High |
| 03 | `03-blast-radius/blast_radius_ISSUE-001.md` | ✅ P3, endpoint scope, 0/7 endpoints down |
| 04 Stage 1 | `04-remediation/fix_plan_ISSUE-001.md`: **Fix Type `VERSION_MIGRATION` → `04d-version-migration`**, Routing decision with 5 evidence bullets, machine-readable migration request | ✅ classified by the agent from the catalog's CWE-1104 anti-pattern; verified by `routing.js` against the pom |
| 04 Stage 2 → 04D | `fix_ISSUE-001.md` (**Compiled**, Migration Status **PARTIAL PASS**), `fix_ISSUE-001.diff` (6 files, applies to HEAD), `migration_issue-001.md/.diff`, [`MIGRATION_SUMMARY.md`](./migration-runs/issue-001-20261001T033403Z/MIGRATION_SUMMARY.md) | ✅ |
| 05 | `rescan` **FIXED** · `redteam` **NO_BYPASS_FOUND** · `behavior` **BEHAVIOR_CHANGED** | ✅ consumed the migration fix, and validated it independently (see below) |
| 06 | `qa` **Passed** (new `MigratedRuntimeContractTest`, 7 tests, on JDK 21) · `build` **Failed** (`mvn verify` on JDK 21: 19 run / 2 failed) | ✅ consumed; FAIL is script-decided and pre-existing |
| 07 | `verdict` **Blocked** (70/75; build hard gate; migration gate clear) · `pr` (BLOCKED banner) · `audit` (§5 Migration (04D)) | ✅ consumed; audit cites the migration report and summary |

**The PARTIAL PASS is real.** One open blocking condition: a human must accept the framework-owned
response changes, which are the 401 timestamp format, the `WWW-Authenticate` charset, the
ProblemDetail without `type`, and the health document shape.

**05 validated independently.** It went beyond 04D's own probes. It found by static analysis and
classpath checks that unknown JSON properties are now accepted, that trailing tokens are now
rejected, and that `/h2-console` is no longer served on Boot 4.1.1. None of these came from 04D.

## Migration facts (from the per-run summary)

| | Before | After |
|---|---|---|
| Spring Boot (declared / resolved) | 3.5.0 | **4.1.1** (Framework 7.0.9, Security 7.1.1, Hibernate 7.4.5, Jackson 3.1.5, JUnit 6.0.3) |
| Java | 17 (`java.version`, `<source>/<target>17`, `eclipse-temurin:17-jre`) | **21** (`java.version`, `<release>${java.version}</release>`, `eclipse-temurin:21-jre`) |
| Build | R0 `package` JDK 17: tests-failed (19/2) | R1 `test-compile` passed · R2 `package` 19/2 (same tests) · R3 `package-skip-tests` passed |
| Runtime | started 13.6 s, 19 probes | started 14.0 s, 19 probes, **0 status changes**, security boundary preserved |
| Files | — | `pom.xml`, `Dockerfile`, `JacksonConfig.java`, `DatabaseHealthIndicator.java`, `EmployeeControllerTest.java`, `EmployeeRepositoryIntegrationTest.java` |

## Defects the integrated run surfaced, and how each was handled

Each was found by an agent in this run and fixed on `validate/04d-integration`. The fix is in the
corresponding commit in `E:/mv/R`, and the affected stage was re-run where its output depended on it.

| # | Found by | Defect | Fix | Re-run |
|---|---|---|---|---|
| F1 | 04 run 1 | My mirror sync left a `'.github'` data-dir literal in the `.claude` copy of 04a's `plans.js` | Sync swaps quoted literals too; literal restored | Stage 1 context moved to `.claude/.pipeline-context` before run 2 |
| F2 | 04 run 2 | Handoff read `summary.approval_mode` instead of `summary.metadata.approval_mode`, so it showed "Approval n/a" | Key corrected | Handoff regenerated by `finalize-run.js` |
| F3 | 04 run 2 | Handoff hard-coded "Matches plan: yes" | Compared with the plan's Planned changes (it now reports the 1 extra test file) | same |
| F4 | 05 run 1 | The handoff patch carried CRLF from the sandbox's working-copy source and did not apply to HEAD (LF in the index). All three collectors recorded `applied: false`, and re-scan came out INCONCLUSIVE | The handoff verifies the patch against the repository index (`git apply --cached --check`). It hands over the LF-normalised form when only that applies, and marks the fix Compile Failed if neither applies | 05 re-run: applied in all three collectors; run-1 reports archived in [`evidence/roundC/05-run1/`](./evidence/roundC/05-run1/) |
| F5 | 07 | The verdict renderer had no row for the migration gate or the Migration Status | Rows added | Verdict re-rendered by `render-verdict.js`; the decision is unchanged (archived pre-fix copy) |
| F6 | 07 | `finalize-run.js` (run by 07) also rewrote `fix_<id>.md` in 04's folder (content identical) | The summary refresh never rewrites the handoff | Verified: hash and mtime unchanged on refresh |

**Open, not fixed (outside the minimal integration, pre-existing):**
- 06b and 04c run `mvn -q dependency:tree`, so the gate's dependency diff is empty. For a Boot 3 → 4 jump it says "no changes".
- 06a fails multi-module fixes.
- The 04D report badge says "Passed" while the stricter summary says PARTIAL PASS, because the report ignores open blocking conditions.
- 01d `generate-docs.js` hard-codes MARS prose.
- 03's root-cause parser returned null fields for this report layout.
- Probe records do not keep `--arg` values.

## Repository safety

- Main checkout `E:/Virtusa Projects/MARS`: no tracked file changed; still on branch `feature/04d-version-migration-v2` @ `5b8008b`.
  Three untracked paths (`.claude/scripts/telemetry/`, `docs/mission-control/`, `mission-control/`) appeared there
  between 23:13 and 23:22 local time. They are not from this validation: no validation agent was tasked with them,
  and their content (a "Mission Control" proposal, a Next.js app, telemetry hooks) is unrelated. They were left untouched.
- Nothing was committed to `main` or `master`, nothing was pushed, and no PR was opened. 07 stopped before publication.
- `apply-migration.js --to-project` was never run; the project in R's working tree is unchanged.
