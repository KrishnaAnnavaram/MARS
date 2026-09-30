---
name: 04a-fix-strategist
description: 'Reads every root cause report in docs/agent_output/02-root-cause/ (and the matching blast radius report, if one exists), matches the defect against a curated CWE-aligned remediation pattern catalog, and writes one docs/agent_output/04-remediation/fix_plan_<issue_id>.md per root cause — a strategy document, not a diff. Every plan starts at Status: Proposed and is a checkpoint: the Fixer agent will not act on it until a human hand-edits that Status to Approved. Use when asked to propose a fix, plan a remediation, pick a CWE-aligned fix strategy, or decide how a reported vulnerability should be fixed.'
argument-hint: 'Nothing (processes every root cause report in docs/agent_output/02-root-cause/), or a specific issue id such as ISSUE-001'
---

# Fix Strategist

Phase A (`02_root-cause-analyst`, `03_blast-radius-analyst`) diagnoses and scopes; this skill is the first
half of Phase B — deciding *how* a defect should be fixed, before any code is written. It never
writes a diff. That split exists on purpose: it creates a checkpoint where a human signs off on the
remediation approach before the Fixer agent touches anything.

**`docs/agent_output/02-root-cause/`, `docs/agent_output/03-blast-radius/` and `docs/agent_output/00-issues/` are all read-only input.** Nothing in
this skill creates, edits or deletes a file in any of them.

## When to Use

- "Propose a fix for ISSUE-001" — a single-issue run
- "Write fix plans for every open issue" — the default, whole-workload run
- After `02_root-cause-analyst` has produced a report — this skill consumes its output
- Re-run any time after a plan's Status has been set — approved/rejected plans are preserved, not overwritten

## Inputs

| # | Input | Why it is needed |
|---|---|---|
| 1 | `docs/agent_output/02-root-cause/root_cause_<id>.md` | The confirmed diagnosis — defines the workload, one plan per report |
| 2 | `docs/agent_output/03-blast-radius/blast_radius_<id>.md` | Priority/reach context, if available. Not required — its absence just means the plan proceeds without it |
| 3 | Issue row from `docs/agent_output/00-issues/issue-register.xlsx`, read through `00-issue-register` | `affected_files`, entry points, the reported symptom text |
| 4 | Current source of every affected file, read straight off disk | What actually needs to change |
| 5 | `catalog/cwe-patterns.json` | The only source of remediation strategy — see [catalog/README.md](./catalog/README.md) |

Root cause and blast radius reports carry **no YAML front matter** — they are plain markdown,
discovered by filename (`root_cause_*.md` / `blast_radius_*.md`) and read by regex against their "At
a glance" tables and bold-label lines, the same technique `03_blast-radius-analyst` uses to read root
cause reports. This skill's own output follows the identical convention (see Output, below).

## Output

One plan per root cause report: `docs/agent_output/04-remediation/fix_plan_<issue_id>.md`.

1. Plain-language headline, then an **At a glance** table: Status, CWE (+ OWASP category), affected
   file count, confidence, links to the root cause and blast radius reports
2. **Remediation approach** — the strategy, adapted from the catalog entry to this defect site
3. **Alternatives considered** (if any)
4. **Planned changes** — a per-file table of what must change, no diff (illustrative code, if any, is
   folded into a labelled `<details>` block and must not read as an applied patch)
5. **Risks to watch**
6. **How the fix must be verified** — concrete steps the Fixer's eventual verification must satisfy
7. **Approval** — explicit instructions for the human checkpoint
8. **Appendix** — inputs used

`docs/agent_output/04-remediation/README.md`'s index table is fully rewritten on every render run, scanning whatever is
currently in `docs/agent_output/04-remediation/` — it always reflects the real state of every plan's Status cell.

Intermediate files land in `.github/.pipeline-context/fix-strategy/` (gitignored):
`<issue_id>.context.json`, `<issue_id>.context.md`, `<issue_id>.strategy.json`.

## The approval checkpoint

A freshly rendered plan has `Status: Proposed`. **This skill never sets a plan to `Approved` or
`Rejected` — only a human editing the rendered file does that.** If you re-render a plan that a human
has already approved or rejected, the existing status is preserved (a note is appended noting the
re-proposal); it is never silently reset back to `Proposed`.

## Procedure

### Step 1 — Discover the workload

```powershell
cd .github/skills/04a-fix-strategist
node scripts/list-remediation-workload.js
```

No `npm install` needed — this skill has zero dependencies. Prints every root cause report with its
plan pipeline state and, once a plan exists, its Status. `--pending` narrows to plans not yet
Approved/Rejected; `--json` gives machine-readable output.

### Step 2 — Collect remediation context

```powershell
node scripts/collect-remediation-context.js --all              # every root cause report
node scripts/collect-remediation-context.js --issue ISSUE-001  # or just one
```

For each report, the collector reads the diagnosis, the blast radius report if present, the current
source of every `affected_files` entry, and regex-scans the issue + root cause text for `CWE-\d+`
mentions, looking each one up in the catalog. It records catalog matches **and catalog gaps** — it
never guesses a pattern for a CWE the catalog doesn't have. In `--all` mode one failure does not
abort the batch.

**Prerequisites.** If a root cause report has no matching issue in `docs/agent_output/00-issues/`, the collector
fails loudly for that item rather than guessing `affected_files`.

### Step 3 — Read the briefing, then the catalog entry in full (per issue)

Read `.github/.pipeline-context/fix-strategy/<issue_id>.context.md`, then open
[catalog/cwe-patterns.json](./catalog/cwe-patterns.json) and read the matched entry's
`canonical_approach` and `anti_patterns` in full — not just the title. If more than one CWE was
detected, pick the one that names the root cause, not every CWE loosely related to the symptom. If
the detected CWE has no catalog entry, that is a **catalog gap**: say so in `catalog_reference.title`
(`null`) and `open_questions`, and either point at the closest applicable existing entry with your
reasoning, or state that a new catalog entry is needed — do not invent a pattern to fill the gap.

### Step 4 — Write the strategy (per issue)

Write `.github/.pipeline-context/fix-strategy/<issue_id>.strategy.json` following
[templates/strategy.schema.json](./templates/strategy.schema.json)
(worked shape in [templates/strategy.example.json](./templates/strategy.example.json)).

Rules for this file:

1. **One CWE per plan.** If the evidence supports two independent defect classes, that belongs to
   two issues (and two plans), not one strategy trying to cover both.
2. **Strategy, not code.** `affected_files[].planned_change` is prose describing the change; an
   `illustrative_sketch` is optional and must read as illustrative, never as an applied patch — the
   Fixer writes the actual diff, in the app's existing style, against the live source.
3. **Cite the catalog.** Every recommendation traces to a specific catalog entry's
   `canonical_approach`, adapted to this defect site — not to general knowledge recalled from memory.
4. **Say what you rejected and why**, when there was a real alternative worth naming — this is what
   makes the strategy auditable rather than a single unexamined guess.
5. **Write `verification_plan` as steps the Fixer can actually execute** — what to compile, what
   existing behaviour must be unchanged, and how to confirm the original symptom/exploit from the
   issue no longer reproduces.
6. **Never touch `docs/agent_output/04-remediation/*.md` Status directly**, and never mark your own plan `Approved`.

### Step 5 — Render the plans

```powershell
node scripts/render-fix-plan.js --all              # every issue with context + strategy
node scripts/render-fix-plan.js --issue ISSUE-001  # or just one
```

The renderer validates each strategy JSON, fails with a precise message on any missing required
field, and writes `docs/agent_output/04-remediation/fix_plan_<issue_id>.md` plus the auto-generated index table in
`docs/agent_output/04-remediation/README.md`. In `--all` mode it renders what is ready and lists what is still pending.

Finish by re-running `node scripts/list-remediation-workload.js` and confirming every root cause
report reads `plan rendered (...)`.

### Step 6 — Report back

Per issue: the CWE and catalog pattern cited, the approach in one or two sentences, the current
Status, and a link to the generated file. Lead with a coverage line (`Proposed N of N plans`). Do not
paste whole plans into chat. Close by reminding the user that a plan sits at `Proposed` until they
(or whoever owns the decision) edit its Status cell to `Approved`.

## Notes

- Self-contained folder — zero dependencies, nothing to `npm install`, can be copied or moved
  independently.
- `scripts/lib/plans.js` holds shared path resolution, the front-matter/table parser, and read-only
  access to the issue register, root cause reports, blast radius reports and the CWE catalog.
- Every script here is read-only against `docs/agent_output/00-issues/`, `docs/agent_output/02-root-cause/` and
  `docs/agent_output/03-blast-radius/`; the only files written are `.github/.pipeline-context/fix-strategy/*` and
  `docs/agent_output/04-remediation/*.md`.
- `.github/.pipeline-context/` is gitignored — only `docs/agent_output/04-remediation/*.md` is meant to be committed.
