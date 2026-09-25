---
name: 03-blast-radius-analyst
description: 'Measures how far each diagnosed defect reaches across the workspace — services, REST endpoints, scheduled jobs, cross-service HTTP calls and shared infrastructure — by combining docs/agent_output/02-root-cause/ reports, docs/agent_output/00-issues/, docs/agent_output/01-architecture/architecture.md, docs/agent_output/01-architecture/function-reference.md and the Neo4j knowledge graph, then writes one diagram-led docs/agent_output/03-blast-radius/blast_radius_<issue_id>.md per root cause. Use when asked what a defect affects, what breaks if it ships, which services or endpoints are impacted, or for an impact/blast radius assessment.'
argument-hint: 'Nothing (processes every root cause report), or a specific issue id such as ISSUE-001'
---

# Blast Radius Analyst

Answers one question per defect: **how far does this reach?** The Root Cause Analyst establishes
*why* something is broken; this skill establishes *what else is broken because of it* — and says it
in language and diagrams a non-engineer can follow.

Scripts measure the reach so it cannot be overstated or understated. You supply the plain-language
judgement on top. The two are kept in separate files and merged into one report.

**Root cause reports and issues are read-only input.** Nothing here writes to `docs/agent_output/02-root-cause/` or
`docs/agent_output/00-issues/`. If a root cause report does not exist for an issue, that issue is not in scope —
the Root Cause Analyst agent has to run first.

## When to Use

- "What is the blast radius of these issues?" — the default, whole-workload run
- "What breaks if ISSUE-001 ships?" / "which services does this affect?"
- "Who is impacted by this defect?" / "how bad is this really?"
- Impact assessment for a release, a go/no-go call, or a triage discussion

## Inputs

| # | Input | What it contributes |
|---|---|---|
| 1 | `docs/agent_output/02-root-cause/root_cause_<id>.md` | The confirmed diagnosis. Defines the workload — one blast radius per root cause |
| 2 | Issue row from `docs/agent_output/00-issues/issue-register.xlsx`, read through `00-issue-register` | The reported symptom, affected symbols and entry points |
| 3 | `docs/agent_output/01-architecture/architecture.md` | Service topology and the complete REST surface to measure against |
| 4 | `docs/agent_output/01-architecture/function-reference.md` | Defect-site signatures, locations and source |
| 5 | Neo4j knowledge graph | Live connections: which endpoints reach the defect, which modules depend on which, what infrastructure is shared |

Inputs 3-5 are produced by the **Architect** agent
([code-cartographer](../01a-code-cartographer/SKILL.md) → [graph-forge](../01c-graph-forge/SKILL.md) →
[blueprint-scribe](../01d-blueprint-scribe/SKILL.md)). Input 1 comes from the
[root-cause-analyst](../02-root-cause-analyst/SKILL.md) agent.

## Output

One report per root cause: `docs/agent_output/03-blast-radius/blast_radius_<issue_id>.md`.

Written to be read top-down by someone who has not seen the code:

1. **At a glance** — priority, services broken, endpoints down, in one table
2. **What is broken** — plain language
3. **How far it spreads** — ring diagram: defect → service → callers → what users lose
4. **Which services are affected** — colour-coded service map plus a status table
5. **Which endpoints are affected** — status per endpoint and what a caller actually sees
6. **What happens on a single request** — caller to failure point
7. **Who feels it** — roles and journeys, not classes
8. **What is NOT affected** — explicit scoping
9. **Containment and priority**, then a short appendix of how it was measured

Intermediates land in `.github/.pipeline-context/blast-radius/` (gitignored):
`<id>.facts.json`, `<id>.facts.md`, `<id>.narrative.json`.

## Procedure

### Step 1 — Discover the workload

```powershell
cd .github/skills/03-blast-radius-analyst
npm install                              # first run only
node scripts/list-root-causes.js
```

Lists every root cause report and its pipeline state (`not started` → `reach measured` →
`narrative written` → `report written`). This is the workload: one blast radius report per row.
`--pending` narrows to unfinished rows; `--json` gives machine-readable output.

If the list is empty, stop and say so — run the Root Cause Analyst agent first rather than
analysing an undiagnosed issue.

### Step 2 — Measure the reach

```powershell
node scripts/collect-impact.js --all              # every root cause
node scripts/collect-impact.js --issue ISSUE-001  # or just one
```

For each defect the collector resolves the defect sites, walks the call graph backwards to find
every endpoint and scheduled job that reaches them, inventories what each service exposes, detects
cross-service HTTP calls that carry no Java call edge, works out shared infrastructure from the pom
dependencies, and cross-checks all of it against Neo4j.

Flags: `--depth <1-10>` (traversal depth, default 6), `--no-graph` (skip Neo4j and use
`artifacts.json` only — the run still succeeds and the fallback is recorded in the report).

### Step 3 — Read the facts briefing (per issue)

Read `.github/.pipeline-context/blast-radius/<id>.facts.md` in full, and read the root cause report it cites.
Before writing anything, be able to answer:

- Does the service still **run** with this defect, or does it fail to build, start or stay up?
  This is the single most important judgement — it decides `scope` in the next step.
- Which endpoints reach the defect, and which merely live in the same service?
- Which services call the broken one over HTTP, and what do they lose when it fails?
- Which scheduled jobs run through the defect, and how often?
- What is genuinely **unaffected**?

The briefing reports two endpoint counts for exactly this reason: endpoints whose handler reaches
the defect, and every endpoint the broken service hosts. Pick the right one deliberately.

### Step 4 — Write the narrative (per issue)

Write `.github/.pipeline-context/blast-radius/<id>.narrative.json` following
[templates/narrative.schema.json](./templates/narrative.schema.json)
(worked shape in [templates/narrative.example.json](./templates/narrative.example.json)).

Rules for this file:

1. **Set `scope` from the root cause, not the symptom.** `endpoint` when the service keeps serving
   and only the defective path fails; `service` when the whole service is unavailable; `multi-service`
   when more than one service is directly broken rather than merely degraded.
2. **Write for a non-engineer.** "Nobody can create a department" beats "the POST handler recurses".
   Class and method names belong in the root cause report; keep them out of the headline and the
   user impact table.
3. **Stay inside the measurement.** Do not name a service, endpoint or job that the facts file does
   not list. If you believe the reach is wider, say so in `open_questions`.
4. **Separate broken from degraded.** Broken means the request fails. Degraded means it succeeds
   with missing, stale or slow results. Do not blur them.
5. **Fill in `not_affected`.** A blast radius that only lists damage is half a report — readers need
   to know what they can still rely on.
6. **Containment is not the fix.** What to disable, monitor or communicate now. The corrective
   change belongs to the root cause report; link, do not repeat.
7. **Be honest about conditions.** If impact depends on data volume, traffic or network exposure,
   set `confidence` accordingly and record the unknown.

### Step 5 — Render the reports

```powershell
node scripts/render-blast-radius.js --all              # every one that is ready
node scripts/render-blast-radius.js --issue ISSUE-001  # or just one
```

The renderer validates each narrative, fails with a precise message on any missing field, applies
`scope` to decide endpoint status, generates the diagrams, and writes
`docs/agent_output/03-blast-radius/blast_radius_<issue_id>.md`. In `--all` mode it renders what is ready and lists
what is still pending. Re-running overwrites, so iterate freely.

Finish by re-running `node scripts/list-root-causes.js` and confirming every row reads
`report written`.

### Step 6 — Report back

Per issue: the headline in one sentence, services and endpoints affected, the priority, and a link
to the generated file. Lead with a coverage line. Do not paste whole reports into chat.

## How status is decided

The colour in every diagram and table follows one rule set, applied by the scripts:

| Status | Meaning |
|---|---|
| 🔴 Broken | The service contains the defect, or hosts an endpoint whose handler reaches it |
| 🟠 Degraded | The service calls a broken service over HTTP — its own code is sound, its data is not |
| 🟡 At risk | Shares infrastructure (data store, config, discovery) with a broken service |
| 🟢 Unaffected | No code path and no HTTP call to anything broken |

## Notes

- Self-contained folder (own `package.json`, own `scripts/lib/`) — can be copied or moved
  independently. It deliberately carries its own code model rather than importing the Root Cause
  Analyst's, so neither skill breaks if the other moves
- Neo4j credentials are read from `../01c-graph-forge/.env`; add a local `.env` here only to override.
  Never print the password or commit an `.env`
- Read-only against the codebase, `docs/agent_output/00-issues/` and `docs/agent_output/02-root-cause/`. The only files written are
  `.github/.pipeline-context/blast-radius/*` and `docs/agent_output/03-blast-radius/*.md`
- `.github/.pipeline-context/` is gitignored — only `docs/agent_output/03-blast-radius/*.md` is meant to be committed
