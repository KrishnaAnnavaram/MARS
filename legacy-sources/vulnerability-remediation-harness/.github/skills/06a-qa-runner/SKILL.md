---
name: 06a-qa-runner
description: 'Drafts exactly one new regression test per drafted fix (Status: Compiled or Compile Failed) in docs/agent_output/04-remediation/, mocking Spring Data types rather than using a live database (this repo has no embedded-MongoDB dependency), then runs it deterministically inside an isolated git worktree alongside any named existing test. Execution and pass/fail are entirely script-decided, never agent-judged. Use when asked to write a regression test for a fix, run the test suite scoped to a change, or confirm a patch is covered by a test.'
argument-hint: 'Nothing (processes every drafted fix in docs/agent_output/04-remediation/), or a specific issue id such as ISSUE-001'
---

# QA Runner

Phase C step 2, first half. Deliberately split in two so execution stays deterministic: **you**
(the agent) write exactly one new regression test and explain what it proves; a **script**
(`run-qa-gate.js`) applies it and the fix diff together inside a throwaway `git worktree`, runs it
for real, and decides PASS/FAIL/SKIPPED from the actual exit code. **You never see or touch that
decision after the fact — you cannot upgrade a FAIL, and you must not re-run the gate hoping for a
different result without changing the test itself.**

**`docs/agent_output/04-remediation/` is read-only input.** Nothing here writes to it.

## Why mocked, not database-backed

This repo has no embedded-MongoDB or Testcontainers dependency, so a `@DataMongoTest`-style test
cannot even start its Spring context here — see `employee-service/src/test/.../EmployeeRepositoryTest.java`
for the existing (DB-dependent, not runnable in this sandbox) pattern. Your new test must mock the
relevant Spring Data type instead (e.g. Mockito-mock `MongoTemplate`, capture the `Query`/`Criteria`
argument with an `ArgumentCaptor`, assert on its shape) — that is what makes the test both meaningful
for this specific class of fix and actually executable here.

## Inputs

1. **`docs/agent_output/04-remediation/fix_<id>.md`**, Status `Compiled` or `Compile Failed`.
2. **The fix diff and the current source** of the affected file(s) — what the new test targets.
3. **The reported symptom / injection payload** from the issue and root cause report — what the test
   should replay as its adversarial input.

## Output

Per drafted fix: `docs/agent_output/06-test-gate/qa_<id>.md` — Status (`Passed` / `Failed` / `Refused`), what the new test
proves, the mocking strategy, and the gate's real per-test results (including any `SKIPPED`).
`docs/agent_output/06-test-gate/README.md`'s index is rewritten on every render run.

Intermediate files in `.github/.pipeline-context/qa/` (gitignored): `<id>.test-plan.json` (your rationale),
`<id>.new-test.diff` (your drafted test, as a unified diff), `<id>.result.json` (the script's
verdict — you write this file's contents by *running the script*, never by hand).

## Procedure

### Step 1 — Discover the workload

```powershell
cd .github/skills/06a-qa-runner
node scripts/list-qa-workload.js
```

No `npm install` needed — zero dependencies.

### Step 2 — Per fix: draft the test (agentic)

Read the fix report, the diff, and the current source of the affected file. Write **exactly one**
new test file as a unified diff (git-diff format, `a/`/`b/`-prefixed paths — the format `git diff`
produces for a new file) to `.github/.pipeline-context/qa/<id>.new-test.diff`. The test should replay the original
adversarial input (from the issue) as well as a benign one, asserting the *construction* the fix
produces (e.g. the bound `Criteria`/`Query` shape), not the raw string filter the old code used to
build. Then write `.github/.pipeline-context/qa/<id>.test-plan.json` per
[templates/test-plan.schema.json](./templates/test-plan.schema.json) — `requires_live_dependency`
must be `false` unless you genuinely could not avoid it.

### Step 3 — Run the gate (deterministic — do not edit this step's output)

```powershell
node scripts/run-qa-gate.js --issue ISSUE-001
node scripts/run-qa-gate.js --issue ISSUE-001 --existing-test EmployeeSearchRepositoryTest
```

Applies the fix diff and your test diff together in an isolated worktree, runs your new test class,
and — if `--existing-test` names one — attempts that too, first checking its source for a live-
dependency marker (`@DataMongoTest`, `@SpringBootTest`, Cucumber) and reporting `SKIPPED` with the
reason rather than attempting (and likely erroring confusingly) or fabricating a pass. Always removes
the worktree afterward unless `--keep` (debugging only). **Refuses outright if the fix isn't
`Compiled`.**

### Step 4 — Render

```powershell
node scripts/render-qa-report.js --all
```

Merges your test plan with the script's real result. The rendered Status is read straight from the
script's output — this step cannot change it.

### Step 5 — Report back

Per fix: Status, the new test's file, what it proves, and a link to `docs/agent_output/06-test-gate/qa_<id>.md`.

## Constraints

- DO NOT create, edit, rename or delete anything in `docs/agent_output/04-remediation/`.
- DO NOT run the gate against a fix whose Status is `Refused`. The gate compiles independently, so
  `Compiled` and `Compile Failed` fixes are both in scope.
- DO NOT write a test that needs a live dependency this sandbox doesn't have unless truly
  unavoidable — and even then, never assume it passes; let the gate report the real outcome.
- DO NOT hand-edit `<id>.result.json` — it is the deterministic gate's output, not yours to shape.
- DO NOT re-run the gate speculatively hoping the same test passes differently; if it fails, fix the
  test or the understanding behind it, don't re-roll.
- DO NOT claim a test "should pass" instead of running it — this whole skill exists to replace that
  exact failure mode with a real exit code.
- DO NOT print full source or the entire report into chat — link to it.
- No `npm install` is needed — this skill has zero dependencies.

## Known caveats

Same JDK/Lombok caveat as Fixer and build-gatekeeper: if the gate fails at compile time with an error
outside the new test file and the fix's own `filesChanged`, that is very likely the pre-existing
JDK-vs-Lombok toolchain mismatch on this machine, not the test or the patch. Report it as such.
