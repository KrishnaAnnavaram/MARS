---
name: 04b-fixer
description: 'Reads every fix plan in docs/agent_output/04-remediation/ whose Status cell reads Approved, drafts the smallest diff that implements the plan in the app''s existing style, verifies it by applying and building it inside a throwaway git worktree (never the real working tree), and writes one docs/agent_output/04-remediation/fix_<issue_id>.md report plus a standalone fix_<issue_id>.diff. Refuses any plan not Approved. Use when asked to implement an approved fix, apply a remediation, write the patch for a fix plan, or generate a verified diff for a vulnerability.'
argument-hint: 'Nothing (processes every Approved fix plan in docs/agent_output/04-remediation/), or a specific issue id such as ISSUE-001'
---

# Fixer

The second half of Phase B. The **Fix Strategist** half of `04_fix-generator` decides *how* to fix a
diagnosed defect and writes that decision down as a plan a human must approve; this skill (the
**Fixer** half of that same agent) turns an **Approved** plan into a compiled, reviewable diff. **It
never edits the real working tree.** Every patch is drafted to a file, checked by applying it inside a
throwaway `git worktree`, and reported — the actual repository's source files and `git status` are
untouched at every point in this pipeline.

**A Compiled result here is not a merge signal.** This skill only proves the patch builds. Whether it
actually closes the vulnerability, survives adversarial re-testing, passes a real test/build gate, and
is safe to ship is decided entirely by the rest of the pipeline (`05_existing-app-test-agent`,
`06_additional-test-execution`, `07_audit-and-pr`) — see [`.github/README.md`](../../README.md).
Nothing in this skill clears a patch to merge.

**`docs/agent_output/04-remediation/` is read-only input.** This skill reads a plan's Status cell as a gate; nothing
here ever writes to a plan file, and nothing here sets a plan's Status.

## When to Use

- "Implement the fix for ISSUE-001" — but only once its plan's Status reads `Approved`
- "Apply every approved fix plan" — the default, whole-workload run
- After a human has edited a plan's Status from `Proposed` to `Approved`
- Re-run `list-fix-workload.js` any time to see what is/isn't ready

## Inputs

| # | Input | Why it is needed |
|---|---|---|
| 1 | `docs/agent_output/04-remediation/fix_plan_<id>.md`, **Status: Approved only** | The gate. Anything else is skipped, not acted on |
| 2 | The plan's `affected_files` and `planned_change` per file | What to change and why |
| 3 | Current source of each affected file, read straight off disk | What to diff against |
| 4 | An isolated `git worktree` created from `HEAD` | Where the patch is applied and built — never the real tree |

## Output

Per Approved plan: `docs/agent_output/04-remediation/fix_<issue_id>.md` and a sibling `docs/agent_output/04-remediation/fix_<issue_id>.diff`
(the raw patch, directly `git apply`-able — not just a fenced code block in the report).

1. Plain-language summary of the change
2. **At a glance** — Status (`Compiled` / `Compile Failed` / `Refused`), CWE, link to the plan,
   files changed, verification level, whether the diff matches the plan
3. **What changed** — per-file table
4. **The diff** — the full patch, fenced
5. **Why this is the smallest correct diff** — ties back to the plan's approach
6. **Deviations from the plan**, if any
7. **Verification evidence** — from the isolated worktree build, folded into `<details>`
8. **Residual risk** and open questions
9. **How to apply this patch** — the literal `git apply` command

`docs/agent_output/04-remediation/README.md`'s index is fully rewritten on every render run.

Intermediate files land in `.github/.pipeline-context/fixer/` (gitignored):
`<id>.patch.diff`, `<id>.rationale.json`, `<id>.verification.{json,md}`, and transiently
`.github/.pipeline-context/fixer/worktrees/<id>/` (always removed by `verify-patch.js` unless `--keep` is passed).

## The approval gate

`list-fix-workload.js` and `verify-patch.js` both check a plan's Status before doing anything.
**A plan at `Proposed` or `Rejected` is never drafted, never verified, never reported on.** If asked
to act on one, refuse and say why — do not proceed "just this once" and do not nag the user
repeatedly; state it once, move to the next item, and mention it in the final coverage summary.

## Procedure

### Step 1 — Discover the workload

```powershell
cd .github/skills/04b-fixer
node scripts/list-fix-workload.js
```

No `npm install` needed — this skill has zero dependencies. Prints every fix plan with its approval
Status and its own fixer pipeline state. `--approved` narrows to Approved plans only.

### Step 2 — Per plan: draft the patch (you write this directly, no script)

For each Approved plan, read it, then open the current source of every file in `affected_files`.
Produce the **smallest diff** that implements `planned_change` for each file, matching the file's
existing style (imports, naming, formatting, error handling conventions already in use in that
module) — do not refactor or reformat anything the plan did not ask for. Generate it with `git diff`
in a disposable worktree (or `git diff --no-index` against a temporary copy), rather than
hand-counting hunk ranges or combining copied fragments. Save exactly one raw standard unified diff
(the format `git diff` produces, with `a/`/`b/`-prefixed paths) to
`.github/.pipeline-context/fixer/<issue_id>.patch.diff`: no Markdown fences, prose, duplicate file
sections, or partial diffs; the file must end with a newline.

Before writing the rationale or running the verifier, run this from the repository root:

```powershell
git apply --check .github/.pipeline-context/fixer/<issue_id>.patch.diff
```

A failure here means the patch artifact is malformed. Repair and re-check it before proceeding;
do not publish an invalid raw `.diff` as a `Compile Failed` result. A patch that applies cleanly
but fails Maven compilation is different and is still reported honestly as `Compile Failed`.

Then write `.github/.pipeline-context/fixer/<issue_id>.rationale.json` per
[templates/rationale.schema.json](./templates/rationale.schema.json)
(worked shape in [templates/rationale.example.json](./templates/rationale.example.json)). If the real
source didn't match what the plan assumed and you had to deviate, set `matches_plan: false` and
explain exactly what and why in `deviations` — do not silently reinterpret the plan.

### Step 3 — Verify the patch in isolation

```powershell
node scripts/verify-patch.js --issue ISSUE-001
node scripts/verify-patch.js --issue ISSUE-001 --test SomeExistingTestClass   # if one applies and needs no live dependency
```

This creates a throwaway `git worktree` from `HEAD`, applies your patch **only there**, runs the
affected Maven module's wrapper (`compile`, plus the named `test` if given), records the result, and
always removes the worktree afterward. Pass `--keep` only when debugging a failure yourself — never
leave a kept worktree behind in a normal run. **This script refuses to run at all if the plan is not
`Approved`** — if it refuses, stop; do not try to work around it.

If verification fails, that is a real result: fix the patch and re-run, or report the failure
honestly in the rationale/report rather than proceeding as if it passed.

### Step 4 — Render the report

```powershell
node scripts/render-fix-report.js --all              # every Approved plan with patch + rationale + verification ready
node scripts/render-fix-report.js --issue ISSUE-001   # or just one
```

Writes `docs/agent_output/04-remediation/fix_<issue_id>.md` and `docs/agent_output/04-remediation/fix_<issue_id>.diff`, and rewrites the
auto-generated index in `docs/agent_output/04-remediation/README.md`. The rendered Status always reflects the actual
verification result — a failed or refused verification is still published, marked as such, never
upgraded to a pass.

Immediately validate the rendered raw patch and its fidelity to the intermediate source:

```powershell
git apply --check docs/agent_output/04-remediation/fix_<issue_id>.diff
git diff --no-index --exit-code .github/.pipeline-context/fixer/<issue_id>.patch.diff docs/agent_output/04-remediation/fix_<issue_id>.diff
```

Both commands must succeed. If either fails, do not treat the report as a valid deliverable; fix
the intermediate patch or renderer input and re-render before reporting the outcome.

### Step 5 — Report back

Per issue: Status, files changed, verification level, and a link to the report. Lead with a coverage
line. Do not paste whole reports or the full diff into chat — link to them. Close with anything
needing attention: plans still waiting on approval, verification failures, or environment issues
(e.g. Maven needing network access on a cold `.m2` cache).

## Known caveats

- `verify-patch.js` needs a `JAVA_HOME` environment variable pointing at a real JDK before it is run —
  the Maven wrapper does not fall back to `java` on `PATH` on Windows. If it isn't set, the compile
  step fails immediately with a shell-level error rather than a compiler diagnostic; that is an
  environment gap, not a patch problem, and should be reported as such.
- This module targets Java 17 and uses Lombok for generated code (`@Slf4j`, builders, etc.). If the
  only JDK available is a much newer major version than the project targets, Lombok's annotation
  processing can silently fail to run, producing compiler errors like "cannot find symbol: variable
  log" or a missing generated constructor — **in files the patch never touched**. Before blaming a
  patch for a compile failure, check whether the same error occurs on the unmodified module too (the
  quickest check: do the reported error locations fall outside `files_changed`?); if so, it is a
  pre-existing toolchain mismatch on this machine, not something the patch introduced or should be
  expected to fix.
- Verification needs Maven to resolve dependencies, which may need network access on a machine with a
  cold local repository cache — a slow or failing first run for that reason is an environment issue,
  not a signal about the patch. State it plainly if it happens rather than reporting a false failure
  as if it were the patch's fault.
- Verification runs `compile` by default. A test only runs when you name a specific class that does
  not depend on a live service (e.g. MongoDB) unavailable in the isolated sandbox — `Compiled` states
  that specific level, never implies full regression coverage.
- The worktree is built from `HEAD`. It does not reflect any local uncommitted changes to the same
  files elsewhere in the working tree.

## Notes

- Self-contained folder — zero dependencies, nothing to `npm install`.
- `scripts/lib/fixplans.js` holds shared path resolution and the fix-plan table parser.
- Every script here is read-only against `docs/agent_output/04-remediation/`; the only files written are
  `.github/.pipeline-context/fixer/*` and `docs/agent_output/04-remediation/*`. Nothing here ever edits a file under `docs/agent_output/04-remediation/`,
  `docs/agent_output/02-root-cause/` or `docs/agent_output/03-blast-radius/`, and nothing here edits the real application source.
- `.github/.pipeline-context/` is gitignored — only `docs/agent_output/04-remediation/*` is meant to be committed.
