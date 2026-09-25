# Issue & Defect Register

Reported issues and vulnerabilities for this workspace, held in a single spreadsheet:
**[`issue-register.xlsx`](./issue-register.xlsx)** — one row per issue. It describes **what is wrong
and how it shows up** — symptoms, reproduction, and impact only. Diagnosis lives separately, in
[`docs/agent_output/02-root-cause/`](../02-root-cause/), produced by the **Root Cause Analyst** agent
(`.github/agents/02_root-cause-analyst.agent.md`).

> **This register is owned by whoever reports the issue.** Add issues by opening the spreadsheet in
> Excel and appending a row — from your tracker, a scanner export, or by hand. Every agent that
> consumes issues treats it as read-only input: nothing in the pipeline writes to this file. Add a
> row, re-run the agents, and it is picked up. A row with a blank `issue_id` is ignored, so notes and
> spacer rows are safe.

## Current register

| ID | Title | Type | Severity | Services | Status |
|---|---|---|---|---|---|
| ISSUE-001 | Unbounded repository `findAll()` reads whole collections into memory across multiple services | Vulnerability | High | `sheduler-service`, `report-service` | Open |
| ISSUE-002 | Employee PII and payroll data are exposed to unauthenticated callers and written to application logs | Vulnerability | Critical | `employee-service`, `report-service`, `sheduler-service`, `department-service`, `configuaration-server`, `discovery-service` | Open |
| ISSUE-003 | MongoDB (NoSQL) injection in the employee search endpoint via string-concatenated `BasicQuery` | Vulnerability | Critical | `employee-service` | Open |

This table is for humans. The pipeline reads the spreadsheet itself, so a new issue works whether or
not this table is updated.

## Columns

Row 1 is the header and the column **names** are the contract — order does not matter, and unknown
columns are ignored, so you can keep extra tracker fields alongside these.

### Identity and triage

| Column | Notes |
|---|---|
| `issue_id` | **Required.** Unique; also names every output, e.g. `root_cause_ISSUE-003.md` |
| `title` | One-line summary |
| `type` | `Defect` \| `Vulnerability` \| `Performance` \| `Security` |
| `severity` | `Critical` \| `High` \| `Medium` \| `Low` — scales the merge-arbiter's pass threshold |
| `status` | `Open` \| `In Progress` \| `Fixed` \| `Closed` |
| `reported_on` | Free text date, stored as text so Excel cannot reformat it |
| `reported_by` | Who or what found it |

### Where the defect lives

Multi-value cells: **one entry per line** inside the cell (Alt+Enter in Excel). Commas also work.

| Column | Notes |
|---|---|
| `affected_services` | Maven module names, exactly as on disk |
| `affected_symbols` | `Type.method` — resolved against `.github/.architect/artifacts.json` and the Neo4j graph |
| `affected_files` | Repo-relative paths |
| `entry_points` | REST endpoints, as `METHOD /path` |

### Narrative

Long-text columns. They are rendered back into a markdown document (`## Summary`, `## Observed
Behavior`, …) before any agent reads them, so markdown formatting inside a cell is preserved and
works exactly as it did when issues were markdown files.

| Column | Consumed by |
|---|---|
| `summary` | Blast Radius Analyst quotes it |
| `affected_area` | Context for the reader |
| `data_flow` | Source → sink trace |
| `observed_behavior` | Root Cause Analyst uses it as the reported symptom |
| `expected_behavior` | Context for the Fix Strategist |
| `steps_to_reproduce` | Context for the QA Runner |
| `impact` | Context for the Blast Radius Analyst |
| `detection_notes` | **Re-scanner extracts every `` `backticked` `` token here as a grep signature** to re-check against the patched code |

## Checking the register

```powershell
cd .github/skills/00-issue-register
node scripts/list-register.js          # every row, as the pipeline parses it
node scripts/list-register.js --full   # plus the synthesized markdown body
```

## Checking what has been analysed

```powershell
cd .github/skills/02-root-cause-analyst
node scripts/list-issues.js            # every issue and its pipeline state
node scripts/list-issues.js --pending  # only those without a report yet
```
