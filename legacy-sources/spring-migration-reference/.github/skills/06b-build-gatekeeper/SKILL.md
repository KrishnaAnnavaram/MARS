---
name: 06b-build-gatekeeper
description: 'Runs mvn verify and a dependency-tree diff for every drafted fix (Status: Compiled or Compile Failed) inside an isolated git worktree — fully deterministic, no agent-authored judgment anywhere in the output. Phase C step 2, second half. Use when asked to confirm a fix builds cleanly, check for dependency drift introduced by a patch, or run the build gate before a patch can ship.'
argument-hint: 'Nothing (processes every drafted fix in docs/agent_output/04-remediation/), or a specific issue id such as ISSUE-001'
---

# Build Gatekeeper

Phase C step 2, second half — and the one skill in this whole pipeline with **no agent-authored
content anywhere in its output**. Every fact in `docs/agent_output/06-test-gate/build_<id>.md` comes straight from
`run-build-gate.js`. This is deliberate: the user asked for "deterministic CI/CD execution, not
open-ended agentic reasoning" here, so there is nothing for an agent to interpret, soften, or
override — you run the two scripts and relay exactly what they say.

**`docs/agent_output/04-remediation/` is read-only input.** Nothing here writes to it.

## What it checks

1. **Build:** `mvn verify` (stronger than Fixer's own `compile` pre-gate) for the fix's affected
   module(s), applied inside a throwaway `git worktree` — never the real working tree.
2. **Dependency drift:** `mvn dependency:tree`, captured once before the patch is applied and once
   after, in the same worktree — any added/removed line is a dependency the fix pulled in or dropped
   that its plan didn't call for.

## Inputs

1. `docs/agent_output/04-remediation/fix_<id>.md`, Status `Compiled` or `Compile Failed`.
2. The fix's diff (`docs/agent_output/04-remediation/fix_<id>.diff`).

## Output

`docs/agent_output/06-test-gate/build_<id>.md` — Status (`Passed` / `Failed` / `Refused`), full build output, and the
dependency diff. `docs/agent_output/06-test-gate/README.md`'s index is rewritten on every render run.

Intermediate: `.github/.pipeline-context/build/<id>.result.json` (the script's own output — the only file this
skill's report is built from) and a transient `.github/.pipeline-context/build/worktrees/<id>/`.

## Procedure

```powershell
cd .github/skills/06b-build-gatekeeper
node scripts/list-build-workload.js
node scripts/run-build-gate.js --issue ISSUE-001      # or --all
node scripts/render-build-report.js --all
```

No `npm install` needed — zero dependencies. Report back per fix: Status, and one line on the
dependency diff (if any). Do not add interpretation the script's output doesn't already contain.

## Constraints

- DO NOT create, edit, rename or delete anything in `docs/agent_output/04-remediation/`.
- DO NOT run against a fix whose Status is `Refused`. The gate compiles independently, so
  `Compiled` and `Compile Failed` fixes are both in scope; do not work around a refusal.
- DO NOT write any judgment file for this skill — there is no schema/template here, on purpose. If
  you find yourself wanting to add interpretation to the report, that belongs in the merge arbiter's
  narrative instead, not here.
- DO NOT treat a build failure whose errors sit outside the fix's `filesChanged` as the patch's
  fault without checking — see Known caveat below.
- No `npm install` is needed — this skill has zero dependencies.

## Known caveat

This machine's only JDK is newer than this project targets (Java 17), and its Lombok version does
not reliably process annotations on it — a `Failed` result whose compiler errors are all outside the
fix's own changed files (missing Lombok-generated `log` fields, missing generated constructors) is
very likely this pre-existing toolchain mismatch, not the patch. Cross-check error locations against
`filesChanged` before reporting a failure as caused by the patch — same check already documented in
Fixer's and qa-runner's `SKILL.md`.
