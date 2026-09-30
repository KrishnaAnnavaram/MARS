---
name: 05-verify
description: 'Shared skill behind Phase C Step 1 — three independent, parallel, static/reasoning-based checks against every fix in docs/agent_output/04-remediation/ that Fixer drafted a diff for, Compiled or Compile Failed alike: re-scanner (does the finding still trigger?), red-team-recon (can the patch be bypassed?), behavior-guard (did real behavior change?). No live database or deployed instance is used — this repo has no embedded-Mongo/Testcontainers dependency. Use when asked to re-verify a fix, check whether a patch can be bypassed, or confirm a patch did not change unrelated behavior.'
argument-hint: 'Nothing (processes every fix with a drafted diff), or a specific issue id such as ISSUE-001'
---

# Verification Layer

One agent — `05_existing-app-test-agent` — runs all three checks (re-scan, red-team, behavior guard)
from this one skill folder, because they read the exact same inputs (the fix diff, the pre-patch
source, the patched source, the fix plan, the root cause report, the issue). Each still writes its
own facts, its own agent-authored verdict, and its own rendered report, so the three stay
independently runnable — in any order — and independently auditable. None of them decides whether a
patch ships; `07_audit-and-pr` reads all three.

**`docs/agent_output/04-remediation/`, `docs/agent_output/02-root-cause/` and `docs/agent_output/00-issues/` are all read-only input.**
Nothing in this skill writes to any of them.

## Why static, not dynamic

This repository has no embedded-MongoDB or Testcontainers dependency — `@DataMongoTest` needs a real
MongoDB to even start its context. Rather than add new test infrastructure, all three checks reason
over materials instead of running a live instance: the diff, the pre-patch source (read straight off
the real tree), and the *patched* source, which is materialized by applying the fix's own diff inside
a throwaway `git worktree` and reading the result — a strict subset of the pattern Fixer already uses
to verify a patch compiles, with no build step, ever removed after reading. **The real working tree
and `git status` are never touched by any script in this skill.**

## Why `Compile Failed` fixes are still eligible

None of these three checks invoke a compiler — they read the diff and materialize the patched file
with `git apply` inside a throwaway worktree, nothing more. So a `Compile Failed` result from Fixer
(which does invoke a compiler) doesn't mean there's nothing here to analyze — the diff still exists,
still applies, and is still real, reviewable evidence about whether the vulnerability is closed,
bypassable, or has side effects. Only `Refused` (Fixer never drafted anything at all because the plan
wasn't Approved) is actually excluded from this skill's workload. `06_additional-test-execution`
*does* need a real compiler, so it stays correctly gated on `Status: Compiled`.

## When to Use

- "Re-verify the fix for ISSUE-001" / "does this still trigger" — re-scanner
- "Can this patch be bypassed" / "red-team this fix" — red-team-recon
- "Did this change any other behavior" — behavior-guard
- "Run Step 1 on every drafted fix" — all three, `--all`

## Inputs (per check)

| Input | re-scanner | red-team-recon | behavior-guard |
|---|---|---|---|
| Fix report (`docs/agent_output/04-remediation/fix_<id>.md`), Status `Compiled` or `Compile Failed` | required | required | required |
| Fix diff (`docs/agent_output/04-remediation/fix_<id>.diff`) | via patched-file materialization | full text + patched files | full text + patched files |
| Fix plan (approach, risk notes) | — | yes | yes (scope to compare against) |
| Root cause report (statement, explanation) | yes | — | — |
| Issue's Detection Notes (signatures) | yes | — | — |
| CWE catalog entry (`04a-fix-strategist/catalog/cwe-patterns.json`) | — | yes (`canonical_approach`, `anti_patterns`) | — |
| Pre-patch source (real tree, read-only) | — | — | yes |

## Output

- `docs/agent_output/05-verify/rescan_<id>.md` — `FIXED` / `STILL_VULNERABLE` / `INCONCLUSIVE`
- `docs/agent_output/05-verify/redteam_<id>.md` — `NO_BYPASS_FOUND` / `BYPASS_FOUND` / `INCONCLUSIVE`
- `docs/agent_output/05-verify/behavior_<id>.md` — `BEHAVIOR_PRESERVED` / `BEHAVIOR_CHANGED` / `INCONCLUSIVE`

`docs/agent_output/05-verify/README.md`'s index is rewritten by whichever renderer last ran.

Intermediate files land in `.github/.pipeline-context/verify/` (gitignored):
`<id>.rescan.facts.{json,md}`, `<id>.redteam.facts.{json,md}`, `<id>.behavior.facts.{json,md}`, and
the matching `<id>.<name>.verdict.json` the agent writes. `.github/.pipeline-context/verify/worktrees/<id>/` is
transient — created and destroyed within a single collector run.

## Procedure (each check follows the same three steps)

### Step 1 — Discover the workload

```powershell
cd .github/skills/05-verify
node scripts/list-workload.js
```

No `npm install` needed — zero dependencies. Shows every fix Fixer drafted a diff for (Compiled or
Compile Failed) with all three checks' pipeline state side by side, so any one agent knows its own
workload without re-deriving the fix register three times. `--pending <rescan|redteam|behavior>`
narrows to one check's outstanding work.

### Step 2 — Collect facts (per check, own script)

```powershell
node scripts/collect-rescan.js --all      # or --issue ISSUE-001
node scripts/collect-redteam.js --all
node scripts/collect-behavior.js --all
```

Each materializes the patched file(s) in an isolated worktree, gathers the relevant upstream
documents, and writes `.github/.pipeline-context/verify/<id>.<name>.facts.md` — facts only, no verdict. If the
worktree fails to apply, that is recorded as a fact (`worktree.applied: false`) for the agent to
treat as `INCONCLUSIVE`, not silently retried or hidden.

### Step 3 — Per fix: read the briefing, write the verdict

Read `.github/.pipeline-context/verify/<id>.<name>.facts.md` in full before writing anything.

- **Re-scan**: absence of the old grep signature is a data point, not proof — reason about
  whether the *mechanism* the CWE describes is actually closed.
- **Red-team**: enumerate concrete alternate vectors against the *new* code, grounded in the
  cited catalog entry's `anti_patterns`. A restated version of the original vulnerability is not a
  new vector.
- **Behavior guard**: every change must trace to the fix plan's stated scope or land in
  `out_of_scope_changes`. The mechanical signature diff in the briefing is an aid, not the verdict.

Write `.github/.pipeline-context/verify/<id>.<name>.verdict.json` per the matching `templates/<name>.schema.json`.

### Step 4 — Render

```powershell
node scripts/render-rescan.js --all
node scripts/render-redteam.js --all
node scripts/render-behavior.js --all
```

Each validates its own verdict JSON and writes the matching `docs/agent_output/05-verify/<name>_<id>.md`.

## Constraints

- DO NOT create, edit, rename or delete anything in `docs/agent_output/04-remediation/`, `docs/agent_output/04-remediation/`,
  `docs/agent_output/02-root-cause/` or `docs/agent_output/00-issues/`.
- DO NOT run against a fix whose Status is `Refused` — Fixer never drafted a diff, so there is
  nothing to analyze.
- DO NOT add live-database test infrastructure to make these checks dynamic — that is an explicit,
  considered scope boundary for this skill, not an oversight.
- DO NOT treat "no signature found" / "no vector succeeded" as automatically High confidence — set
  `confidence` honestly, especially when the worktree failed to apply.
- DO NOT print full source files or the entire report into chat — link to the files.
- No `npm install` is needed — this skill has zero dependencies.

## Output Format (each agent, independently)

One line (`Re-scanned/Red-teamed/Checked N of N fix(es)`), then per fix: the verdict, one or two
sentences of reasoning, and a link to the rendered report. If the underlying fix is `Compile Failed`,
say so plainly alongside the verdict — this check's result stands on its own regardless, but a
reader should not mistake it for a claim that the patch builds. Do not paste whole reports into chat.
