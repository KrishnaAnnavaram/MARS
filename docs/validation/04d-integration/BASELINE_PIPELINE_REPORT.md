# Baseline Pipeline Report — Round A (current architecture, unmodified)

**Verdict: 04D reached — NO.** On the unmodified harness (MARS `5b8008b`), the full pipeline
01 → 07 ran to completion and Agent 04 routed every plan by CWE alone (`CWE-1104` → 04c, everything
else → 04b). No routing rule, script, or downstream consumer refers to `04d-version-migration`, so
the migration skill could not be reached from Agent 04, and nothing after Agent 04 could have used
its output.

| | |
|---|---|
| Run | Round A — `E:/mv/A`, a detached git worktree of MARS at `5b8008b` (no changes to the harness) |
| Invocation model | The repository's real one: each agent invoked by name as a Claude Code subagent (`01_architect` … `07_audit-and-pr`), sequentially, file handoff through `docs/agent_output/` |
| Input | MARS `src/` (6 Spring Boot **2.7.12** / Java 17 services), register `00-issues/issue-register.xlsx` (ISSUE-001…004) |
| Human approval | **HUMAN APPROVAL = REUSED EXISTING APPROVAL** — all 4 plans already read `Status: Approved`; no Status cell was edited |
| Toolchain | Portable Temurin JDK **17.0.20.1** (`JAVA_HOME` for the run only), module `mvnw` wrappers (Maven 3.8.7), Node 22.14.0 |
| Infrastructure | Disposable `mongo:6.0` (server 6.0.28) container `mv-mongo` on 27017 for the 06 test gates; stopped and removed after 06 (`--rm`). Neo4j: **NOT EXECUTED — OPTIONAL DEPENDENCY NOT CONFIGURED** |
| Traces | [`evidence/roundA/trace-01.log` … `trace-07.log`](./evidence/roundA/) (102 lines, written live by each agent) |

## 1. Agents and skills executed

| # | Agent | Skills run | Commands (from the traces) | Result |
|---|---|---|---|---|
| 01 | `01_architect` | 01a, 01b, 01d (01c skipped — no Neo4j) | `scan.js`; `list-context-workload.js`; `validate-context.js`; `npm run all` | 6 modules / 51 types; 77/77 nodes described (reused, fingerprints current); `architecture.md`, `function-reference.md` |
| 02 | `02_root-cause-analyst` | 00-issue-register, 02 | `list-issues.js`; `collect-evidence.js --all`; `render-root-cause.js --all` | 4/4 root-cause reports |
| 03 | `03_blast-radius-analyst` | 03 | `list-root-causes.js`; `collect-impact.js --all`; `render-blast-radius.js --all` | 4/4 blast-radius reports |
| 04 | `04_fix-generator` | 04a (Stage 1), **04b** (001–003), **04c** (004) | `list-remediation-workload.js`; `collect-remediation-context.js --all`; `render-fix-plan.js --all`; 04b `list-fix-workload.js`, `verify-patch.js` ×4, `render-fix-report.js --all`; 04c `list-fix-workload.js`, `apply-version-bump.js --issue ISSUE-004`, `render-fix-report.js --all` | 3 Compiled, 1 Compile Failed |
| 05 | `05_existing-app-test-agent` | 05-verify | `collect-{rescan,redteam,behavior}.js --all`; `render-*.js --all` | 12/12 reports |
| 06 | `06_additional-test-execution` | 06a, 06b | `run-qa-gate.js --issue` ×4; `render-qa-report.js --all`; `run-build-gate.js --all`; `render-build-report.js --all` | QA 2 Passed / 2 Failed; Build 3 Passed / 1 Failed |
| 07 | `07_audit-and-pr` | 07a, 07b | `compute-score.js --all`; `render-verdict.js --all`; `collect-chain.js --all`; `render-scribe.js --all` | 4/4 Blocked; PR + audit content written; nothing published |

## 2. Routing decisions (verbatim from `trace-04.log`)

```text
[04][ROUTER] ISSUE-001 CWE=CWE-770  -> 04b-fixer (rule: "... Every other CWE uses 04b-fixer"; CWE-770 != CWE-1104)
[04][ROUTER] ISSUE-002 CWE=CWE-306  -> 04b-fixer (rule: "... Every other CWE uses 04b-fixer"; CWE-306 != CWE-1104)
[04][ROUTER] ISSUE-003 CWE=CWE-943  -> 04b-fixer (rule: "... Every other CWE uses 04b-fixer"; CWE-943 != CWE-1104)
[04][ROUTER] ISSUE-004 CWE=CWE-1104 -> 04c-dependency-upgrader (rule: "if the plan's CWE is CWE-1104 ... run all of Stage 2 through .claude/skills/04c-dependency-upgrader/")
```

## 3. Why 04D was not reached

| Evidence | Observed in Round A |
|---|---|
| Agent 04's routing rule | Two branches only: `CWE-1104` → 04c, anything else → 04b. `grep -c 04d .claude/agents/04_fix-generator.agent.md` → **0** |
| Agent 04's skill list | 04a, 04a1, 04a2, 04b, 04c — 04D is not listed |
| Plans | No plan carries any field that could request a migration (no `version_migration`, no Fix Type) |
| Session evidence | `.github/.pipeline-context/version-migration/` **does not exist** in the worktree after the run |
| Outputs | No `migration_*` file in `04-remediation/` |
| Downstream consumers | No script in 05, 06 or 07 reads `migration_*` — they consume only `fix_<id>.md` / `.diff` |
| Traces | No line in any of the 7 traces mentions 04d, version-migration or detect-baseline |

04D exists only as a standalone, "on request" skill (`.github/README.md`: *"`04d-version-migration` | on request"*),
discoverable by Claude Code through the `.claude/skills/04d-version-migration/SKILL.md` pointer, but no
agent routes to it. Its own SKILL.md calls it *"the migration arm of `04_fix-generator`"*, but its
ARCHITECTURE.md says *"no change to … `04_fix-generator.agent.md`"* — the integration was never made.

## 4. Pipeline completion and per-issue outcome

| Issue | CWE | Fix (04) | Re-scan | Red-team | Behavior | QA | Build | Verdict |
|---|---|---|---|---|---|---|---|---|
| ISSUE-001 | CWE-770 | Compiled | STILL_VULNERABLE | BYPASS_FOUND | BEHAVIOR_CHANGED | Failed | Passed | **Blocked** (hard gate: re-scan) |
| ISSUE-002 | CWE-306 | Compiled | FIXED | BYPASS_FOUND | BEHAVIOR_CHANGED | Failed | Failed | **Blocked** (hard gate: build) |
| ISSUE-003 | CWE-943 | Compiled | FIXED | BYPASS_FOUND | BEHAVIOR_PRESERVED | Passed | Passed | **Blocked** (score 70 < 90) |
| ISSUE-004 | CWE-1104 | Compile Failed | FIXED | NO_BYPASS_FOUND | BEHAVIOR_CHANGED | Passed | Passed | **Blocked** (score 70 < 75) |

The pipeline terminated cleanly: every stage produced its reports, 07 wrote `verdict_`, `pr_` and
`audit_` for all four, and nothing was branched, pushed or published.

## 5. Failures and defects found (pre-existing; not caused by this validation)

| # | Where | Defect | Effect in this run |
|---|---|---|---|
| D1 | `04c-dependency-upgrader/scripts/apply-version-bump.js` (~l. 338) | Runs `mvnw -q dependency:tree`; `-q` suppresses the tree, so the resolved-version check can never pass | ISSUE-004 reported **Compile Failed** although compile and the real resolution (5.0.0 → 5.4.0, verified separately) succeeded |
| D2 | `06b-build-gatekeeper/scripts/run-build-gate.js` (l. 75, 97) | Same `-q dependency:tree` | "Dependency change detected: no" is meaningless (wrong for ISSUE-002 and ISSUE-004) |
| D3 | `06a-qa-runner/scripts/run-qa-gate.js` | Runs the one new test class in *every* module the fix touches; surefire fails modules without it ("No tests were executed!") | QA **Failed** for the multi-module fixes ISSUE-001/002 although each new test passed in its own module |
| D4 | `05-verify/scripts/list-workload.js`, 06 list scripts | "report written" checks file existence only, not freshness | Agents had to override stale committed reports by judgement |
| D5 | `src/report-service/.../EmployeeReportRepository.java:9`, `src/department-service/.../DepartmentController.java:26` | Application does not compile at HEAD (missing `java.util.List` import; unqualified call) | Fixes had to carry declared out-of-scope build repairs |
| D6 | `03-blast-radius-analyst` caller detection | Name-based match counted report-service as an HTTP caller of employee-service | False "degraded" rows in ISSUE-003/004 blast-radius reports |
| D7 | `04a-fix-strategist` re-render | Re-rendering an Approved plan replaced hand-added sections of the earlier plan | Approval reuse covered changed plan content — flagged for human re-review |

## 6. Environment deviations

- JDK 17 was provided by a portable Temurin 17.0.20.1 under `E:/mv/tools/`; the machine default
  (Microsoft JDK 21.0.11) and `PATH` were not changed. Earlier committed outputs were produced on
  JDK 25 machines, where Lombok failed; on JDK 17 every module compiled.
- `git worktree add` from inside `E:/mv/A` registers temporary entries in the main repository's
  `.git/worktrees/` (shared metadata of a linked worktree); all were removed by the scripts.
- 02 and 03 ran `npm install` in their skill folders (`node_modules` missing in a fresh worktree).
- The main checkout `E:/Virtusa Projects/MARS` was never read from or written to by any agent.

## 7. Conclusion

> **Before modification, Agent 04D could not be reached from Agent 04 because no routing or handoff
> integration existed.** Agent 04's only Stage 2 rule is CWE-based (04b/04c), the plan contract has no
> way to express a migration, and 05–07 consume only `fix_<id>.md` — so even a manually produced
> `migration_<slug>.md` would have been invisible to the rest of the pipeline.
