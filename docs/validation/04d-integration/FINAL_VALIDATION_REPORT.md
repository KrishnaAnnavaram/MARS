# Agent 04D Migration — Final Validation Report

**Scope.** Prove, by real execution, whether the 04D migration skill works end to end inside the
MARS remediation pipeline, then make the smallest architectural change that integrates it, and prove
that.

| Round | What | Report |
|---|---|---|
| A | Unmodified harness, full pipeline 01→07 on MARS `src/` (Boot 2.7.12) | [BASELINE_PIPELINE_REPORT.md](./BASELINE_PIPELINE_REPORT.md) |
| B | 04D standalone: B1 Boot 2.7.12 (pack-selection bug), B2 Boot 3.5.0 → 4.1.1 (functional test) | [ROUND_B_REPORT.md](./ROUND_B_REPORT.md) |
| C | Integrated harness, full pipeline 01→04→04D→05→06→07 on the Boot 3.5.0 app | [INTEGRATED_PIPELINE_REPORT.md](./INTEGRATED_PIPELINE_REPORT.md) |

Per-run migration summaries, one per 04D invocation: [`migration-runs/`](./migration-runs/).
Raw evidence, including traces, session records and every agent output: [`evidence/`](./evidence/).

---

## Existing Repository Verdict

> **Before modification, Agent 04D could not be reached from Agent 04, because no routing or handoff
> integration existed.**
> - In the real run, Agent 04 routed all four plans by CWE alone: three to `04b-fixer`, one to
>   `04c-dependency-upgrader`. Its definition contains no reference to 04D. No plan could express a
>   migration. No 04D session directory or `migration_*` output was created.
> - Agents 05–07 read only `fix_<id>.md`, so even a manually produced migration report would have been
>   invisible to them.
> - Run on its own, the existing 04D **does** migrate a real Boot 3.5.0 application to 4.1.1 / Java 21
>   correctly (B2: PASS).
> - It also **wrongly accepts a Boot 2.7.12 source** as a valid 3→4 migration (B1). An unversioned
>   `spring-boot-starter-web` detect entry selects the pack, and the missing 2→3 (Jakarta) generation
>   is only a "planning input" warning.

## Integrated Repository Verdict

> **In the actual run, Agent 04 selected 04D, 04D migrated the application, produced the standard
> handoff and the migration report, and agents 05, 06 and 07 consumed the result.**
> - **Routing.** Agent 04's Stage 1 classified ISSUE-001 as `VERSION_MIGRATION`. The routing
>   classifier verified this against the `pom.xml` on disk: parent 3.5.0 → 4.1.1, Java 17 → 21.
>   Stage 2 dispatched the plan to `04d-version-migration` once the plan was approved; the prompt never
>   mentioned 04D.
> - **Migration.** 04D migrated Spring Boot 3.5.0 / Java 17 → 4.1.1 / Java 21 in 4 rounds, using
>   previewed OpenRewrite plus one evidence-backed residual edit. Tests showed 19 run / 2 failing before
>   and after, the same pre-existing failures, so there were no new failures. 19 runtime probes showed
>   0 status changes.
> - **Handoff.** 04D wrote `fix_ISSUE-001.md` / `.diff` (Status Compiled, Migration Status PARTIAL
>   PASS), `migration_issue-001.md` / `.diff`, and `MIGRATION_SUMMARY.md` / `migration-summary.json`.
> - **05** consumed the handoff and validated it independently: FIXED / NO_BYPASS_FOUND /
>   BEHAVIOR_CHANGED.
> - **06** gated it on the target JDK 21: QA Passed, Build Failed on the 2 pre-existing failures.
> - **07** audited it, with the migration gate evaluated and the migration evidence in the chain of
>   custody. Verdict: **Blocked**, by the build hard gate, for reasons the migration did not introduce.
> - The pipeline terminated cleanly. Nothing was published.
>
> Approval for this run was programmatically granted for controlled pipeline validation
> (`APPROVAL_MODE=TEST_AUTO_APPROVED`). This does not replace the production human approval
> requirement.

---

## Before / after comparison

| Capability | Before Integration | After Integration | Evidence |
|---|---|---|---|
| Agent 04 can recognize migration | **FAIL** | **PASS** | A: no plan field or rule for migration; `grep -c 04d 04_fix-generator.agent.md` = 0. C: Stage 1 recorded `version_migration`; plan row `Fix Type: VERSION_MIGRATION` with 5 routing-evidence bullets |
| Agent 04 can select 04D | **FAIL** | **PASS** | A: `[04][ROUTER] … CWE=… -> 04b/04c` only. C: `[04][ROUTER] ISSUE-001 Fix Type=VERSION_MIGRATION -> 04d-version-migration … DISPATCHED` |
| 04D source-version validation | **FAIL** | **PASS** | B1: 2.7.12 accepted. B1′: `NOT ELIGIBLE: … 2.7.12 (parent) is generation 2, but the pack migrates from 3.x`; C: `pack spring-boot-3-to-4 eligible … SUPPORTED` |
| 04D pack eligibility safe | **FAIL** | **PASS** | B1: matched on `spring-boot-starter-web`. B1′: `UNSUPPORTED_MIGRATION_PATH`, missing `spring-boot-2-to-3`, exit 2, sandbox refused. Tests 13/13b/13c |
| 04D migration executes | **PASS** (standalone only) | **PASS** (inside the pipeline) | B2: 4 rounds, PASS. C: 4 rounds via `detect-baseline.js --issue ISSUE-001` |
| 04D generates report automatically | **PARTIAL PASS** | **PASS** | Before: `migration_<slug>.md` only after a hand-written `migration.json`; nothing on a blocked or failed run. After: `MIGRATION_SUMMARY.md` + `migration-summary.json` written in a `finally` by every 04D script (B1′ BLOCKED, C IN_PROGRESS → PARTIAL PASS) |
| 04D produces standard 04 handoff | **FAIL** | **PASS** | C: `fix_ISSUE-001.md` (Fix Type, Migration Skill, Report, Diff, Summary, Status, versions, validation) + `fix_ISSUE-001.diff` that applies to HEAD |
| Agent 05 consumes migration output | **FAIL** | **PASS** | A: no consumer of `migration_*`. C: "Version migration context" in all 3 briefings; patch applied in all 3 collectors; independent verdicts |
| Agent 06 validates migrated app | **FAIL** | **PASS** | C: both gates on `src/spring-boot-migration-demo`, JDK 21.0.11 via `MIGRATION_JDK_21`; QA Passed, Build Failed (script-decided, pre-existing failures) |
| Agent 07 audits migration | **FAIL** | **PASS** | C: migration hard gate evaluated (clear on PARTIAL PASS); verdict shows Fix Type + Migration Status; chain of custody includes the migration report and summary; audit §5 "Migration (04D)" |
| Full pipeline completes | **FAIL** (01→07 completes, never through 04D) | **PASS** | C: 01 → 04 → 04D → 05 → 06 → 07, clean termination, refreshed summary "Pipeline handoff" = yes/yes/yes/yes |

---

## The 27 validation questions

Before = Round A and B; After = Round C. Where only one column is meaningful, it is the integrated run.

| # | Question | Before | After | Evidence |
|---|---|---|---|---|
| 1 | Does the complete pipeline start correctly? | PASS | PASS | A and C: all 7 agents started, with traces `trace-01…07.log` |
| 2 | Do all earlier agents/stages execute correctly? | PASS | PASS | 01/02/03 produced their reports in both runs. Graph Forge was NOT EXECUTED (optional Neo4j not configured), by design |
| 3 | Does routing eventually reach Agent 4? | PASS | PASS | 04 consumed 02/03 outputs in both runs |
| 4 | Does Agent 4 correctly determine whether Agent 4D is required? | FAIL | PASS | A: CWE-only rule. C: Fix Type `VERSION_MIGRATION`, verified by `routing.js` against `pom.xml`. Routing tests: a library bump → 04c; a parent bump across a generation is refused as `dependency_upgrade`; no keyword routing |
| 5 | Is Agent 4D genuinely invoked? | FAIL | PASS | A: no `version-migration/` session dir. C: session `issue-001`, `state.json` history from `detect-baseline.js --issue` to `RENDERED` |
| 6 | Does Agent 4D load its migration skill? | NOT EXECUTED | PASS | C trace: read `.github/skills/04d-version-migration/SKILL.md`, the pack incl. §14–15, and the curated recipe |
| 7 | Does Agent 4D inspect the source application? | NOT EXECUTED | PASS | `baseline.json` observations (surfaces, entry points, configs); probes written from the source (19 requests) |
| 8 | Does Agent 4D determine the source Spring Boot / Java versions? | PARTIAL PASS | PASS | Before: versions read correctly (B1 2.7.12 / 17), but used unsafely. After: 3.5.0 (parent) / 17 (`java.version`), with the source recorded per value in the summary's Detection table; 2.7.12 correctly blocked |
| 9 | Does Agent 4D determine the target migration version? | PASS | PASS | Exact 4.1.1 / Java 21, never inferred. In C it comes **from the Approved plan**, and the caller's `--to-version` is ignored (test 14b) |
| 10 | Does Agent 4D create a migration plan? | PASS (B2) | PASS | `migration-plan.json`: 13 impacts, 8 constraints, 1 deterministic candidate; `--check-plan` valid |
| 11 | Does Agent 4D make actual migration changes? | PASS (B2) | PASS | 6 files changed in the sandbox; delivered as `fix_ISSUE-001.diff` (applies to HEAD). The project itself is changed only by an explicit apply after a Cleared verdict, by design |
| 12 | Are dependency changes performed correctly? | PASS (B2) | PASS | Parent 3.5.0 → 4.1.1; security-test → `spring-boot-starter-security-test`; + webmvc-test, data-jpa-test; the Testcontainers 1.20.1 pin is kept (out-of-scope hunk rejected with dependency:tree evidence); resolved Framework 7.0.9, Security 7.1.1, Hibernate 7.4.5, Jackson 3.1.5 |
| 13 | Are Spring APIs migrated correctly? | PASS (B2) | PASS | Jackson 3 `JsonMapper.builder()`; health contributor package move; `@MockBean` → `@MockitoBean`; `@WebMvcTest`/`@DataJpaTest` packages; compiles and tests on 4.1.1 |
| 14 | Are Jakarta namespace changes handled where required? | FAIL | PARTIAL PASS | 3.x sources are already on `jakarta.*`, so nothing was needed in B2/C. The 2.7.12 source (which uses `javax.servlet`, `javax.ws`) is now **BLOCKED** with `missing_capability: spring-boot-2-to-3` instead of silently skipping the Jakarta generation. No 2→3 Jakarta capability exists yet |
| 15 | Are configuration/property changes handled? | PARTIAL PASS | PARTIAL PASS | `java.version`, compiler `<release>`, Dockerfile JRE changed; no `application.yml` change was needed (plan item unchanged). 05 found what 04D missed: `spring.h2.console.enabled` no longer takes effect on Boot 4.1.1 (`spring-boot-h2console` absent) |
| 16 | Are build-system changes handled? | PASS (B2) | PASS | pom parent, Java property, compiler release, test starters; OpenRewrite run by coordinate without touching the build file; scope-checked apply |
| 17 | Does the migrated application compile? | PASS (B2) | PASS | R1 `test-compile` and R3 `package-skip-tests` passed on JDK 21.0.11; 06 compiled it independently in a worktree |
| 18 | Do tests execute? | PASS (B2) | PASS | 19 tests run in R0 (JDK 17), R2 (JDK 21) and the 06 build gate; Testcontainers PostgreSQL 5/5 both sides; 06 QA's new 7-test class passed |
| 19 | Does the application start? | PASS (B2) | PASS | Started on JDK 17 (13.6 s) and JDK 21 (14.0 s), readiness 200 |
| 20 | Is behavior validated after migration? | PASS (B2) | PARTIAL PASS | 19 probes, 0 status changes, security boundary preserved, app payloads identical modulo timestamps. Framework-owned changes still need human acceptance (open blocking condition), and 05 found changes no probe covered: unknown-property acceptance, trailing tokens, `/h2-console` |
| 21 | Are migration failures captured? | PASS | PASS | Every round recorded (incl. R0/R2 test failures), rejected hunks, the open blocking condition; BLOCKED runs leave a BLOCKED summary |
| 22 | Are retry/repair mechanisms working? | PARTIAL PASS | PARTIAL PASS | Round loop + evidence-driven residual edit ran (Testcontainers pin restore, R1→R2). A failing-compile → repair cycle was not exercised, because the previewed recipes left no compile errors (that path is covered by the recorded v1 session and the 04D test suite) |
| 23 | Does execution return correctly to Agent 4 / orchestrator? | NOT EXECUTED | PASS | After 04D, Agent 04 finished Stage 2 (re-ran the workload lists) and reported in its Output Format |
| 24 | Does the pipeline continue after Agent 4D? | FAIL | PASS | 05, 06, 07 consumed `fix_ISSUE-001.md` |
| 25 | Does the overall pipeline terminate cleanly? | PASS | PASS | A and C: 07 wrote verdict/PR/audit, no publication, no worktrees left |
| 26 | Are artifacts and evidence generated? | PASS | PASS | Per-stage reports, 04D session (baseline, plan, rounds, transformations, runtime, judgement, state), traces — all under [`evidence/`](./evidence/) |
| 27 | Is a migration summary generated automatically for every run? | FAIL | PASS | Every 04D script ends in `finally: finalizeRun()`: B1′ (BLOCKED), C (IN_PROGRESS → PARTIAL PASS); `finalize-run.js` produced summaries for the pre-integration B1/B2 sessions from their evidence. Test 15 |

---

## What changed (branch `validate/04d-integration`, minimal, inside the existing file-handoff architecture)

**C1 — Routing in Agent 04 (evidence-based, not keyword-based)**
- `04a-fix-strategist/scripts/lib/routing.js` (new) classifies every plan into one Fix Type:
  `CODE_FIX` → 04b, `DEPENDENCY_UPGRADE` → 04c, `VERSION_MIGRATION` → 04d.
- A migration must be declared by Stage 1 (`version_migration` in the strategy) **and** proven by the
  build descriptor:
  - the platform coordinate is declared at the stated source version;
  - the major generation changes, or the Java level changes.
- A contradicted claim is a render error. A `dependency_upgrade` that moves a platform parent/BOM
  across a major generation is refused.
- The plan renders `Fix Type`, `Migration`, a *Routing decision* section, and a machine-readable
  migration request. An `Approved by` cell is preserved on re-render.
- The catalog's CWE-1104 entry gains the anti-pattern that steers Stage 1.
- `04_fix-generator.agent.md` routes Stage 2 on Fix Type and holds the 04D procedure.
- 04b and 04c refuse `VERSION_MIGRATION` plans.

**C2 — Pack eligibility**
- In `references.js`, a detect entry only nominates a pack. Eligibility requires the declared platform
  version, taken from parent, BOM import or plugin, to be in the pack's `from` generation.
- The required path is computed step by step.
- `detect-baseline.js` records `UNSUPPORTED_MIGRATION_PATH`, `MULTI_STEP_REQUIRED`,
  `NO_ELIGIBLE_PACK` or `NO_MATCHING_PACK` as BLOCKED (exit 2), with `required_path` and
  `missing_capability`.
- `prepare-workspace.js` refuses a blocked baseline.

**C3 — Standard handoff (`04d .../lib/handoff.js`, new)**
- `detect-baseline.js --issue <ID>` re-checks Status and Fix Type and takes the request from the
  plan.
- Rendering writes `fix_<id>.md` + `fix_<id>.diff`, re-rooted at the repository root and verified to
  apply to HEAD. The handoff links the migration report, diff and summary.

**C4 — Migration-aware 05/06/07 (small additions, no rewrites)**
- 05 parses the migration links and appends a *Version migration context* section to each briefing.
- 06a/06b:
  - parse Fix Type and Target Java, and build on `MIGRATION_JDK_<n>`;
  - accept any `src/<module>` with a `pom.xml`;
  - fall back to `MIGRATION_MVN`/`mvn` when a module has no wrapper.
- 07a adds a migration hard gate (`migration_not_passed_blocks`) and renders Fix Type and Migration
  Status.
- 07b adds the migration report and summary to the chain of custody.
- Agent definitions for 05/06/07 and `pipeline-contract.md` gain a short migration section each.

**C5 — Per-run summary (`04d .../lib/summary.js`, new; `finalize-run.js`)**
- Every 04D script runs `main()` inside `runAndFinalize` (try/finally, async-safe).
- That writes `migration-runs/<run_id>/MIGRATION_SUMMARY.md` + `migration-summary.json` purely from
  the session's evidence. The status is one of PASS, PARTIAL PASS, FAIL, BLOCKED or IN_PROGRESS.
- The summary has the sections requested: run metadata, trigger, detection, path, planned and actual
  transformations, file and dependency changes, OpenRewrite, compile history, tests, runtime,
  behaviour, pipeline handoff, evidence.

**Hygiene**
- `.gitignore` ignores `.github/.pipeline-context/*` except the tracked `context/descriptions.json`,
  the same rule `.claude/` already had. 04D sandboxes are full project copies with nested `.git`.
- SKILL.md Step 1 now passes `--slug`.
- `.claude/` and `.github/` are kept as path-swapped mirrors.

**Tests**
- 04D: 39/39 (33 existing + 6 new: eligibility, path gate, `--issue` gate, refused handoff, summary).
- 04a routing: 6/6 in both harness copies.
- `pipeline-lint.js`: passes on both harnesses.

## Open items (found during validation, deliberately not fixed — outside the minimal integration)

1. **No `spring-boot-2-to-3` pack.** MARS's own `src/` (Boot 2.7.12) cannot be migrated until one
   exists; 04D now says so instead of attempting it.
2. **`mvn -q dependency:tree`** in 04c `apply-version-bump.js` and 06b `run-build-gate.js` prints
   nothing. It makes every 04c fix "Compile Failed" and every dependency-drift check "no change".
   Dropping `-q` would fix both.
3. **06a runs the single new test in every touched module**, so multi-module fixes always fail QA.
4. **The 04D report badge says "Passed"** where the stricter per-run summary and handoff say PARTIAL
   PASS: the report ignores open `blocking_conditions`.
5. **Pre-existing app defects block the gates regardless of any fix.** The demo app's 2 actuator tests
   fail in a `@WebMvcTest` slice. MARS `report-service` and `department-service` do not compile at
   HEAD.
6. **Smaller items:**
   - 01d `generate-docs.js` hard-codes MARS prose;
   - 03's collector misparses this root-cause layout;
   - probe records drop `--arg`;
   - B2-reported pack content issues (starter-web rename coverage, §1.3 symptom, §13 health wording).

## Reproduce

Recorded machine facts: Windows 11. Temurin 17.0.20.1 and Maven 3.9.9 are portable copies under
`E:/mv/tools`. Microsoft JDK 21.0.11 is the system default and was left unchanged. Node 22.14.0,
Docker 27.3.1.

```bash
source /e/mv/env17.sh   # JAVA_HOME=17, MIGRATION_JDK_17/21, MIGRATION_MVN
cd .github/skills/04d-version-migration && npm test          # 39 tests
cd ../04a-fix-strategist && npm test                          # 6 routing tests
node .claude/scripts/pipeline-lint.js
# B1′: PIPELINE_CONTEXT_DATA_DIR=<tmp> MIGRATION_REPORT_DIR=<tmp> node scripts/detect-baseline.js \
#      --project ../../../src/employee-service --slug b1 --to-java 21 --to-version 4.1.1    # exit 2, BLOCKED
```
