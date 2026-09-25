---
name: 02-root-cause-analyst
description: 'Reads every issue already present in docs/agent_output/00-issues/ and diagnoses each one by correlating four inputs — the issue markdown, docs/agent_output/01-architecture/architecture.md, docs/agent_output/01-architecture/function-reference.md, and the Neo4j knowledge graph (file/service dependencies and the method call graph) — then writes one docs/agent_output/02-root-cause/root_cause_<issue_id>.md per issue. Use when asked to find root causes for reported issues, analyze the issue register, diagnose a defect or vulnerability, or trace why something fails.'
argument-hint: 'Nothing (processes every issue in docs/agent_output/00-issues/), or a specific issue id such as ISSUE-001'
---

# Root Cause Analyst

Turns reported symptoms into evidence-backed diagnoses. Facts are collected by scripts so they
cannot drift; the causal reasoning is yours. The two are kept in separate files and merged into one
report per issue, so every factual claim in the output is traceable to a scan, a document, or a
graph query.

**The issue register is read-only input.** Issues live in the spreadsheet
`docs/agent_output/00-issues/issue-register.xlsx`, one row each, written by whoever reports them. Nothing
in this skill creates, edits or deletes a row — the scripts only read them, and neither should you.
If the register is empty, there is nothing to analyse; say so rather than drafting an issue.

## When to Use

- "Find the root cause of every issue in docs/agent_output/00-issues/" — the default, whole-register run
- "What is the root cause of ISSUE-001?" — a single-issue run
- "Analyze the defect in department-service", "why does the create endpoint fail?"
- After the Architect pipeline has run — this skill consumes its outputs

## Inputs

| # | Input | Why it is needed |
|---|---|---|
| 1 | `docs/agent_output/00-issues/issue-register.xlsx` | The symptoms: what fails, how to reproduce, observed vs expected. Supplied externally; read-only |
| 2 | `docs/agent_output/01-architecture/architecture.md` | Module topology, service map, REST surface — places each defect in the system |
| 3 | `docs/agent_output/01-architecture/function-reference.md` | Exact signatures, source locations, method source, resolved callers/callees |
| 4 | Neo4j knowledge graph | Live traversal: who calls the defect, what it reaches, which modules and endpoints are on a path, cross-service dependencies |

Inputs 2-4 are produced by the **Architect** agent's skills
([code-cartographer](../01a-code-cartographer/SKILL.md) → [graph-forge](../01c-graph-forge/SKILL.md) →
[blueprint-scribe](../01d-blueprint-scribe/SKILL.md)). If they are stale or missing, refresh them first.

A row is picked up when it carries an `issue_id`; rows without one (spacers, reporter notes) are
ignored. Reading the spreadsheet is owned by the [00-issue-register](../00-issue-register/SKILL.md)
skill, which also rebuilds each row into the `## Summary` / `## Observed Behavior` /
`## Detection Notes` markdown this pipeline extracts. The full column contract is documented in
[docs/agent_output/00-issues/README.md](../../docs/00-issues/README.md).

## Output

One report per issue: `docs/agent_output/02-root-cause/root_cause_<issue_id>.md` — for example
`docs/agent_output/02-root-cause/root_cause_ISSUE-001.md`.

Written to be read top-down by whoever has to decide what to do about the defect, not only by the
engineer who will fix it — plain language and diagrams first, source and graph detail folded into
`<details>` blocks:

1. **Plain-language headline** under the title, then an **At a glance** table (what breaks, root
   cause, where, severity, confidence)
2. **What was reported** — the symptom, verbatim from the issue
3. **What should happen — and what happens instead** — green/red comparison diagram
4. **How it fails, step by step** — numbered chain plus a flow diagram ending in red
5. **Where the defect is** — call-graph diagram (a self-call is drawn as an explicit loop), the
   statement, the explanation, a method table, and the source folded away
6. **What it means** · **Why it happened** · **How to fix it** · **How to check the fix worked** ·
   **How to stop it happening again** · open questions
7. **Appendix** — inputs used and the Cypher executed, collapsed

Three diagrams, one idea each, one colour meaning throughout: green = correct, red = where it goes
wrong.

**Scope boundary.** Section 6 says what the defect *means* — the consequence and why the severity
is right — in prose. It does not map how far the defect spreads: no service maps, no endpoint
status tables, no who-is-affected breakdown. Keep this report focused on cause and remedy.

Intermediate files land in `.github/.pipeline-context/rca/` (gitignored):
`<issue_id>.evidence.json`, `<issue_id>.evidence.md`, `<issue_id>.analysis.json`.

## Procedure

Five steps. Steps 3 and 4 repeat per issue; steps 1, 2 and 5 handle the whole register in one go.

### Step 1 — Discover what to analyse

```powershell
cd .github/skills/02-root-cause-analyst
npm install                        # first run only
node scripts/list-issues.js
```

Prints every issue with its severity, status and pipeline state (`not started` → `evidence
collected` → `analysis written` → `report written`). This list is the workload: analyse exactly
these issues. `--pending` narrows to issues without a report; `--json` gives machine-readable output.

### Step 2 — Collect the evidence

```powershell
node scripts/collect-evidence.js --all              # every issue in the register
node scripts/collect-evidence.js --issue ISSUE-001  # or just one
```

For each issue the collector resolves `affected_symbols` against `.github/.pipeline-context/artifacts.json`, walks
the call graph in both directions, queries Neo4j for callers/callees/endpoints/fan-in/module impact,
slices the relevant rows out of `architecture.md` and the matching entries out of
`function-reference.md`, and detects cross-service HTTP consumers that no Java call edge captures.

Useful flags: `--depth <1-8>` (traversal depth, default 4), `--no-graph` (skip Neo4j).
In `--all` mode each issue is processed independently — one failure does not abort the batch, and
the run ends with a list of what succeeded and what did not.

**Prerequisites.** If the collector reports a missing `artifacts.json`, run the Code Cartographer
scan first. If it warns that `docs/agent_output/01-architecture/architecture.md` or `docs/agent_output/01-architecture/function-reference.md` is missing, run
Blueprint Scribe. If it reports an unresolved symbol, the issue's `affected_symbols` does not match
the code — report that back to whoever filed the issue; do not edit the issue file, and do not
analyse around the gap.

If Neo4j is unreachable the collector says so and falls back to the static call graph — the run
still succeeds, and the fallback is recorded in the report.

### Step 3 — Read the evidence briefing (per issue)

Read `.github/.pipeline-context/rca/<issue_id>.evidence.md` in full, then read the actual source files it points
to. Confirm each of these before forming any conclusion:

- Which method is the **defect site**, and which methods are merely **on the path** to it
- Whether the reported symptom is explained by the call edges shown, or whether an edge is missing
- Every REST endpoint and scheduled job that reaches the defect site
- Every module and cross-service consumer in the affected area
- Anything the evidence does **not** show — that becomes an open question, not a guess

### Step 4 — Write the analysis (per issue)

Write `.github/.pipeline-context/rca/<issue_id>.analysis.json` following
[templates/analysis.schema.json](./templates/analysis.schema.json)
(worked shape in [templates/analysis.example.json](./templates/analysis.example.json)).

Rules for this file:

1. **One root cause per issue.** Name a single defect. If the evidence supports more than one
   independent defect, that is a second issue — report it back rather than folding it in.
2. **Cause, not symptom.** "The handler recurses instead of delegating" is a cause;
   "a StackOverflowError is thrown" is a symptom.
3. **Cite the evidence.** Every claim must point at a file and line, a call edge, an endpoint, or a
   graph result that appears in the bundle.
4. **Never invent structure.** If a class, method, edge or config value is not in the evidence or in
   the source, it does not go in the report.
5. **Say what you do not know.** Use `open_questions` and set `confidence` honestly — `Low` when the
   diagnosis still needs runtime confirmation.
6. **Scope impact to the graph.** The `impact.narrative` must be consistent with the affected area
   the collector computed; do not widen or narrow it by assumption.
7. **Keep issues independent.** Analyse each on its own evidence. Two issues in the same service are
   not evidence for each other.
8. **Write `plain_summary` for a non-engineer.** One or two sentences naming what stops working and
   why, with no class or method names — it becomes the headline of the report. `summary` stays
   technical and is folded away for the engineer who picks up the fix.
9. **Supply `expected_flow`.** Two to five short steps describing the correct behaviour. Rendered
   directly against your causal chain as a green/red comparison, which is the fastest way to show a
   reader what went wrong. Keep each step under about 45 characters, and keep the causal-chain step
   labels short for the same reason — the detail belongs in the sentence after the label, not in
   the label itself.

### Step 5 — Render the reports

```powershell
node scripts/render-root-cause.js --all              # every issue with evidence + analysis
node scripts/render-root-cause.js --issue ISSUE-001  # or just one
```

The renderer validates each analysis JSON, fails with a precise message on any missing required
field, and writes `docs/agent_output/02-root-cause/root_cause_<issue_id>.md`. In `--all` mode it renders what is
ready and lists the issues still pending an analysis. Re-running overwrites, so iterate freely: fix
the analysis JSON, re-render.

Finish by re-running `node scripts/list-issues.js` and confirming every issue reads
`report written`.

### Step 6 — Report back

Per issue: the root cause in one or two sentences, the impact in one line, the recommended fix in
one sentence, and a link to the generated file. Lead with a coverage line
(`Analyzed N of N issues`). Do not paste whole reports into chat.

## Notes

- Self-contained folder (own `package.json`) — can be copied or moved independently
- `scripts/lib/issues.js` holds the shared register access and path resolution used by all three
  scripts
- Neo4j credentials are read from `../01c-graph-forge/.env`; add a local `.env` here only to override.
  Never print the password or commit an `.env`
- Every script is read-only against the codebase and against `docs/agent_output/00-issues/`; the only files written
  are `.github/.pipeline-context/rca/*` and `docs/agent_output/02-root-cause/*.md`
- `.github/.pipeline-context/` is gitignored — only `docs/agent_output/02-root-cause/*.md` is meant to be committed
