---
name: 04c-dependency-upgrader
description: 'Reads every fix plan in docs/agent_output/04-remediation/ whose Status cell reads Approved AND whose CWE is CWE-1104 (a dependency-version upgrade), drafts the smallest diff — a version bump in a pom.xml — verifies it by applying it inside a throwaway git worktree and confirming both the declared version and the mvn dependency:tree-resolved version meet the plan''s minimum_fixed_version, then writes the same docs/agent_output/04-remediation/fix_<issue_id>.md + fix_<issue_id>.diff pair 04b-fixer produces for every other CWE. Refuses any plan not Approved, or not CWE-1104. Use when asked to implement an approved dependency-upgrade fix, bump a vulnerable library version, or generate a verified diff for an outdated-dependency vulnerability.'
argument-hint: 'Nothing (processes every Approved CWE-1104 fix plan in docs/agent_output/04-remediation/), or a specific issue id such as ISSUE-004'
---

# Dependency Upgrader

The Stage 2 sibling of `04b-fixer`, for exactly one CWE: **CWE-1104**, "Use of Unmaintained Third
Party Components." `04a-fix-strategist` (shared with 04b) still decides *how* to fix a diagnosed
defect and writes that decision down as a plan a human must approve; this skill turns an **Approved**
plan whose CWE is **CWE-1104** into a compiled, reviewable version-bump diff. **It never edits the
real working tree.** Every patch is drafted to a file, checked by applying it inside a throwaway `git
worktree`, and reported — the actual repository's source files and `git status` are untouched at
every point.

**Why this exists as its own skill instead of a branch inside `04b-fixer`:** every other Fixer
verification answers one question — "does this compile." A dependency-version fix needs to answer a
second, equally mechanical question that a plain compile check cannot: "did the version actually
change, and did that change take effect in what Maven resolves." A `<version>` edit can be textually
correct and still not take effect if another `dependencyManagement` entry overrides it elsewhere in
the module or its parent. That is a genuinely different, deterministic check — declared version, then
compile, then *resolved* version via `mvn dependency:tree` — which is why it gets its own script
rather than a flag on `verify-patch.js`.

**A Compiled result here is not a merge signal**, exactly as for `04b-fixer`. It only proves the
version bump applied, compiled, and actually took effect. Whether it closes the vulnerability,
survives adversarial re-testing, passes a real test/build gate, and is safe to ship is decided by the
rest of the pipeline (`05_existing-app-test-agent`, `06_additional-test-execution`,
`07_audit-and-pr`).

**`docs/agent_output/04-remediation/` is read-only input.** This skill reads a plan's Status and CWE
cells as gates; nothing here ever writes to a plan file.

## When to use

- "Implement the fix for ISSUE-004" — but only once its plan's Status reads `Approved` and its CWE is
  `CWE-1104`
- "Apply every approved dependency-upgrade fix plan" — the default, whole-workload run
- After a human has edited a CWE-1104 plan's Status from `Proposed` to `Approved`
- Re-run `list-fix-workload.js` any time to see what is/isn't ready — it only ever lists CWE-1104
  plans; every other CWE belongs to `04b-fixer`

## Inputs

| # | Input | Why it is needed |
|---|---|---|
| 1 | `docs/agent_output/04-remediation/fix_plan_<id>.md`, **Status: Approved** and **CWE: CWE-1104 only** | The gate. Anything else is skipped, not acted on |
| 2 | The plan's **Dependency** row (`maven_coordinate`, `current_version`, `minimum_fixed_version`, optional `cve`) | The machine-checkable target this skill verifies against — not just prose |
| 3 | The current `pom.xml` of the affected module, read straight off disk | What to diff against |
| 4 | An isolated `git worktree` created from `HEAD` | Where the patch is applied, compiled and checked — never the real tree |

## Output

Per Approved CWE-1104 plan: `docs/agent_output/04-remediation/fix_<issue_id>.md` and a sibling
`docs/agent_output/04-remediation/fix_<issue_id>.diff` — **the exact same location and filename
pattern `04b-fixer` uses**, so both appear in the same shared index without any special-casing
downstream.

1. Plain-language summary of the version bump
2. **At a glance** — Status (`Compiled` / `Compile Failed` / `Refused`), CWE, link to the plan, the
   dependency coordinate with old → new version, verification level
3. **What changed** — the one-row (usually) file table
4. **The diff** — the full patch, fenced
5. **Why this is the minimum correct version bump** — ties back to the plan's approach
6. **Deviations from the plan**, if any
7. **Dependency verification evidence** — declared version, compile result, and the
   `dependency:tree`-resolved version, each shown separately, folded into `<details>`
8. **Residual risk** and open questions
9. **How to apply this patch** — the literal `git apply` command

`docs/agent_output/04-remediation/README.md`'s index is rewritten on every render run — identical
scan/write logic to `04a-fix-strategist`'s and `04b-fixer`'s own renderers, so re-running any of the
three always converges on the same index content regardless of which ran last.

Intermediate files land in `.github/.pipeline-context/dependency-upgrader/` (gitignored, kept
separate from `04b-fixer`'s own intermediate directory so the two skills never collide on the same
`<id>.rationale.json`): `<id>.patch.diff`, `<id>.rationale.json`, `<id>.verification.{json,md}`, and
transiently `.github/.pipeline-context/dependency-upgrader/worktrees/<id>/` (always removed by
`apply-version-bump.js` unless `--keep`).

## The approval gate

`list-fix-workload.js` and `apply-version-bump.js` both check a plan's Status **and CWE** before doing
anything. A plan at `Proposed` or `Rejected` is never drafted, never verified, never reported on — and
neither is a plan for any CWE other than `CWE-1104`, even if `Approved`. If asked to act on either,
refuse and say why; do not proceed "just this once."

## Procedure

### Step 1 — Discover the workload

```powershell
cd .github/skills/04c-dependency-upgrader
node scripts/list-fix-workload.js
```

No `npm install` needed — zero dependencies. `--approved` narrows to Approved plans only.

### Step 2 — Per plan: draft the version-bump patch (you write this directly, no script)

For each Approved CWE-1104 plan, read it — especially its **Dependency** row — then open the current
`pom.xml` of the affected module. Write the **smallest diff**: change the `<version>` text for that
exact `groupId`/`artifactId` to a version `>=` `minimum_fixed_version` (the plan's stated target, not
necessarily "latest"), matching the file's existing formatting. Do not touch any other dependency or
reformat anything else in the file. Save it as a standard unified diff to
`.github/.pipeline-context/dependency-upgrader/<issue_id>.patch.diff`.

Then write `.github/.pipeline-context/dependency-upgrader/<issue_id>.rationale.json` per
[templates/rationale.schema.json](./templates/rationale.schema.json)
(worked shape in [templates/rationale.example.json](./templates/rationale.example.json)). If the real
`pom.xml` didn't match what the plan assumed (e.g. the version was managed via a property instead of a
direct tag), set `matches_plan: false` and explain exactly what and why.

### Step 3 — Verify in isolation

```powershell
node scripts/apply-version-bump.js --issue ISSUE-004
```

Creates a throwaway `git worktree` from `HEAD`, applies your patch **only there**, then runs four
checks in order: (1) confirms the *declared* version in the patched `pom.xml` meets the target, (2)
compiles the affected module, (3) runs `mvn dependency:tree` in the same worktree, (4) confirms the
*resolved* version for that coordinate also meets the target — catching a bump that doesn't actually
take effect due to a management override elsewhere. Always removes the worktree afterward. **Refuses
outright if the plan isn't `Approved`, or isn't `CWE-1104`.**

If verification fails at any stage, that is a real result: fix the patch and re-run, or report the
failure honestly in the rationale/report rather than proceeding as if it passed.

### Step 4 — Render the report

```powershell
node scripts/render-fix-report.js --all
```

Writes `docs/agent_output/04-remediation/fix_<issue_id>.md` and `fix_<issue_id>.diff`, and rewrites
the shared auto-generated index in `docs/agent_output/04-remediation/README.md`. The rendered Status
always reflects the actual verification result.

### Step 5 — Report back

Per plan: Status, the dependency + old→new version, verification level, and a link to the report.
Lead with a coverage line. Do not paste whole reports, diffs, or dependency-tree output into chat —
link to them.

## Constraints

- DO NOT create, edit, rename or delete anything in `docs/agent_output/00-issues/`,
  `docs/agent_output/02-root-cause/`, `docs/agent_output/03-blast-radius/`, or (outside the render
  script) `docs/agent_output/04-remediation/`.
- DO NOT act on any fix plan whose Status is not exactly `Approved`, or whose CWE is not exactly
  `CWE-1104` — a plan for any other CWE belongs to `04b-fixer`, not this skill.
- DO NOT edit any real source file in the repository, at any point, for any reason. All Stage 2 code
  goes into `.github/.pipeline-context/dependency-upgrader/<id>.patch.diff` and is only ever applied
  inside the throwaway worktree `apply-version-bump.js` creates and destroys.
- DO NOT bump to "latest" without checking the advisory's actual fixed-version boundary — the target
  is the plan's `minimum_fixed_version`, not whatever the newest release happens to be.
- DO NOT treat a passing compile as sufficient — a version bump is only verified once the *resolved*
  `dependency:tree` version also meets the target, not just the declared `<version>` text.
- DO NOT widen a patch beyond the one dependency named in the plan — touching unrelated dependencies
  or reformatting the pom.xml is not the smallest diff.
- DO NOT claim a verification passed that did not.
- DO NOT leave a kept worktree (`--keep`) behind after a normal run.
- DO NOT print full dependency-tree output, plans, diffs, or rationale into chat — link to the files.
- No `npm install` is needed — this skill has zero dependencies.

## Known caveats

Same JDK/Lombok caveat as `04b-fixer`: if the compile stage fails with an error outside the patched
`pom.xml`'s own dependency block, check whether the same error occurs on the unmodified module too
(compare error locations against the patch's changed files). If so, it is very likely the pre-existing
JDK-vs-Lombok toolchain mismatch on this machine, not the version bump — report it as such rather than
attributing an unrelated compile failure to the dependency upgrade.

## Notes

- Self-contained folder — zero dependencies, nothing to `npm install`.
- `scripts/lib/depfixplans.js` is a deliberate copy of `04b-fixer/scripts/lib/fixplans.js`'s path
  resolution and plan-parsing logic (not a shared import) — this pipeline duplicates helpers per
  skill rather than coupling skills together, the same choice already made for every other skill pair
  in this repo. The one addition here is parsing the plan's **Dependency** row into structured facts.
- Every script here is read-only against `docs/agent_output/04-remediation/`; the only files written
  are `.github/.pipeline-context/dependency-upgrader/*` and `docs/agent_output/04-remediation/*`.
  Nothing here ever edits a file under `docs/agent_output/00-issues/`, `docs/agent_output/02-root-cause/`
  or `docs/agent_output/03-blast-radius/`, and nothing here edits the real application source.
- `.github/.pipeline-context/` is gitignored — only `docs/agent_output/04-remediation/*` is meant to
  be committed.
