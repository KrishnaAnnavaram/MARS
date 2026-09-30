---
name: 04b-fixer
description: 'Reads every fix plan in docs/agent_output/04-remediation/ whose Status cell reads Approved, drafts the smallest diff that implements the plan in the app''s existing style, verifies it by applying and building it inside a throwaway git worktree (never the real working tree), and writes one docs/agent_output/04-remediation/fix_<issue_id>.md report plus a standalone fix_<issue_id>.diff. A CWE-1104 plan (a vulnerable dependency version) takes this skill''s dependency-upgrade path instead, which drafts a version bump and also confirms the mvn dependency:tree-resolved version meets the plan''s minimum_fixed_version. Refuses any plan not Approved. Use when asked to implement an approved fix, apply a remediation, write the patch for a fix plan, bump a vulnerable library version, or generate a verified diff for a vulnerability.'
argument-hint: 'Nothing (processes every Approved fix plan in docs/agent_output/04-remediation/), or a specific issue id such as ISSUE-001'
---

# Fixer

The second half of Phase B. The **Fix Strategist** half of `04_fix-generator` decides *how* to fix a
diagnosed defect and writes that decision down as a plan a human must approve; this skill (the
**Fixer** half of that same agent) turns an **Approved** plan into a compiled, reviewable diff. **It
never edits the real working tree.** Every patch is drafted to a file, checked by applying it inside a
throwaway `git worktree`, and reported — the actual repository's source files and `git status` are
untouched at every point in this pipeline.

**This skill has two paths, chosen by the plan's CWE:**

| Plan CWE | Path | Scripts |
|---|---|---|
| Any CWE except `CWE-1104` | **Code-logic path** (the original VRH Fixer) | `list-fix-workload.js`, `verify-patch.js`, `render-fix-report.js` |
| `CWE-1104` (vulnerable dependency version) | **Dependency-upgrade path** (merged from the Spring migration reference's `04c-dependency-upgrader`) | `list-dependency-workload.js`, `apply-version-bump.js`, `render-dependency-report.js` |

Both paths share the approval gate, the isolated-worktree rule, the output location and the Status
vocabulary. Each verification script refuses the other path's plans. The dependency-upgrade path is
described in its own section below.

**A Compiled result here is not a merge signal.** This skill only proves the patch builds. Whether it
actually closes the vulnerability, survives adversarial re-testing, passes a real test/build gate, and
is safe to ship is decided entirely by the rest of the pipeline: VRH's agents 05 to 07, or MARS's own
05/06/07 verification and merge arbiter in a harness run. See [`.github/README.md`](../../README.md).
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

`verify-patch.js` also refuses a `CWE-1104` plan and points at `apply-version-bump.js`, and
`apply-version-bump.js` refuses every other CWE. Do not work around either refusal.

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

## Dependency-upgrade path (CWE-1104 only)

Merged from the Spring migration reference's `04c-dependency-upgrader`, whose scripts now live in this
skill under new names. It exists as a separate path because a dependency fix must answer a second
question that a compile cannot: *did the version change actually take effect in what Maven resolves?*
A `<version>` edit can be textually correct and still be overridden by a `dependencyManagement` entry
elsewhere in the module or its parent.

**Inputs.** An Approved plan whose CWE is exactly `CWE-1104`, with its **Dependency** row
(`maven_coordinate`, `current_version`, `minimum_fixed_version`, optional CVE). `04a-fix-strategist`
renders that row from the strategy's `dependency_upgrade` object and refuses a CWE-1104 strategy
without it.

**Output.** The same `docs/agent_output/04-remediation/fix_<issue_id>.md` + `.diff` pair as the
code-logic path, so both appear in the one shared index. Intermediate files land in
`.github/.pipeline-context/dependency-upgrader/`, kept apart from `fixer/` so the two paths never
collide on the same `<id>.rationale.json`.

1. **Discover the workload.** `node scripts/list-dependency-workload.js` (`--approved` narrows it).
   It lists CWE-1104 plans only.
2. **Draft the version bump (you write this, no script).** Read the plan's Dependency row, then the
   affected module's current `pom.xml`. Change the `<version>` for that exact `groupId:artifactId` to
   a version at or above `minimum_fixed_version`, matching the file's formatting. Touch nothing else.
   Generate the patch with `git diff` against a disposable copy and save it to
   `.github/.pipeline-context/dependency-upgrader/<issue_id>.patch.diff`. Check it with
   `git apply --check` before going further, exactly as for the code-logic path.
3. **Write the rationale** to `.github/.pipeline-context/dependency-upgrader/<issue_id>.rationale.json`
   per [templates/dependency-rationale.schema.json](./templates/dependency-rationale.schema.json)
   (example: [templates/dependency-rationale.example.json](./templates/dependency-rationale.example.json)).
   If the real `pom.xml` did not match what the plan assumed, for example the version is managed
   through a property, set `matches_plan: false` and explain.
4. **Verify in isolation.** `node scripts/apply-version-bump.js --issue <ISSUE-ID>` creates a
   throwaway `git worktree` from `HEAD`, applies the patch only there, and checks in order: the
   *declared* version meets the target, the module compiles, `mvn dependency:tree` runs, and the
   *resolved* version meets the target. It always removes the worktree. It refuses a plan that is not
   `Approved` or not `CWE-1104`.
5. **Render.** `node scripts/render-dependency-report.js --all` writes the report and diff and
   rewrites the shared index with the same text the other two renderers write. Then run the same
   `git apply --check` and byte-for-byte comparison as Step 4 of the code-logic path, against the
   `dependency-upgrader/` intermediate patch.
6. **Report back** per plan: Status, the coordinate with old and new version, verification level,
   and a link to the report.

Constraints specific to this path:

- DO NOT bump to "latest" without checking the advisory's fixed-version boundary. The target is the
  plan's `minimum_fixed_version`.
- DO NOT treat a passing compile as enough. The bump is verified only when the *resolved*
  `dependency:tree` version also meets the target.
- DO NOT widen the patch beyond the one dependency the plan names, and do not reformat the `pom.xml`.
- The JDK and Lombok caveat below applies here too: check whether a compile error also occurs on the
  unmodified module before blaming the bump.

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
- `scripts/lib/depfixplans.js` is the dependency-upgrade path's own copy of the plan parser, plus
  parsing of the **Dependency** row. It stays a copy rather than a shared import, following the
  pipeline's rule of not coupling helpers across paths.

## Inside MARS

This is skill **04b** of the merged Agent 04
([`.github/agents/04_fix-generator.agent.md`](../../agents/04_fix-generator.agent.md)). It works in
two modes:

- **Harness mode (a MARS run).** MARS already produces real patches for three shapes with its
  deterministic Java fixers: SQL string concatenation (CWE-89), path containment (CWE-22, KB fix
  `HF-PATH-001`) and dependency bumps (CWE-1104). Every other approved plan is *strategy-only*: a
  person approved the strategy, and a concrete patch is still needed. That patch is this skill's job.
  Draft it with the same smallest-diff rules as Step 2, against the file in the run's workspace
  (`runs/<RUN>/migration/`), and check it with `git diff --no-index` against a temporary copy. Then
  register it with the harness instead of `verify-patch.js`:

  ```bash
  harness submit-patch --run <RUN> --finding <FINDING-ID> \
    --file <repo/relative/path>=<local file with the full new content> \
    --reason "<which plan this implements>" \
    --provider llm --model <model id> \
    --prompt-hash <sha256 of the input you drafted from> \
    --response-hash <sha256 of the content you submitted>
  ```

  The harness records it as a new proposal. A person must approve it with
  `harness approve remediation`. The harness then applies it through its Mutation Gateway and
  verifies it with re-scan, red team, behaviour guard, QA and build, and the 07a arbiter. Without the
  three provenance options an `llm` patch is refused at apply time. Never run `approve`, `decide` or
  `apply` yourself.
- **Pipeline mode (the file workflow).** Both paths above, unchanged, against
  `docs/agent_output/04-remediation/` in the repository this skill sits in.
