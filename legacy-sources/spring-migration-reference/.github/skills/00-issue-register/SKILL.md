---
name: 00-issue-register
description: 'Reads the Excel issue register at docs/agent_output/00-issues/issue-register.xlsx — one row per reported vulnerability — and serves it to every agent that consumes issues. Owns the column contract and rebuilds each row into the markdown body downstream extractors expect. Use when asked what issues are in the register, to check how a row parses, or when adding a new issue.'
argument-hint: 'Nothing (lists the whole register), or --full to include the synthesized markdown body'
---

# Issue Register

Step **00** — the pipeline's input, before any agent runs. Six skills read issues; they all read
them through this one, so the column contract is defined in exactly one place.

**The register is read-only input.** It is owned by whoever reports the issue. Nothing in this
skill, or anywhere else in the pipeline, writes to `issue-register.xlsx`.

## Why a spreadsheet

Issues arrive from people and tools that already work in spreadsheets — scanner exports, tracker
dumps, a security reviewer's own sheet. A reporter adds a row in Excel and re-runs the agents; there
is no markdown syntax to get wrong and no front matter to hand-maintain.

## The trick that keeps everything else unchanged

Downstream skills have always pulled three sections out of an issue by regex:

- `## Summary` — quoted by the Blast Radius Analyst
- `## Observed Behavior` — the reported symptom, used by the Root Cause Analyst
- `## Detection Notes` — the re-scanner extracts every `` `backticked` `` token from here as a grep
  signature to re-check against the patched code

So the loader **rebuilds a markdown body** from the spreadsheet's long-text columns, using those
exact headings, and hands it to callers as `issue.body`. Every existing extractor keeps working
against the spreadsheet without knowing the source changed.

## Files

| Path | Purpose |
|---|---|
| `scripts/lib/xlsx.js` | Minimal XLSX reader/writer — **zero dependencies**, built on Node's `zlib` |
| `scripts/lib/register.js` | Column contract, row → issue normalization, markdown-body synthesis |
| `scripts/list-register.js` | CLI: dump the register exactly as the pipeline parses it |

Zero dependencies, no `npm install`. Every other skill in this pipeline advertises the same, and six
of them read issues — pulling a spreadsheet library into all six would have broken that, so
`xlsx.js` implements just the slice of the format the register needs.

## Reading it from another skill

```js
const register = require('../../../00-issue-register/scripts/lib/register');

register.listIssues(ISSUES_DIR, rel);          // every row, sorted by id
register.readIssue(ISSUES_DIR, 'ISSUE-003', rel); // one row, or null
register.resolveIssue(ISSUES_DIR, id, rel);    // one row, or throws naming what exists
```

Each issue object carries:

```
id title type severity status reportedOn reportedBy      scalars
services symbols files entryPoints                        arrays, split on newline or comma
body                                                      synthesized markdown
data                                                      the old front-matter-shaped map
raw                                                       the untouched spreadsheet row
file relativeFile                                         the register the row came from
```

## Usage

```powershell
cd .github/skills/00-issue-register
node scripts/list-register.js
node scripts/list-register.js --full          # include the synthesized body
node scripts/list-register.js --issue ISSUE-003
```

## Column contract

Defined in `scripts/lib/register.js` and documented for reporters in
[`docs/agent_output/00-issues/README.md`](../../../docs/agent_output/00-issues/README.md). Row 1 is the header; column
names are the contract, order does not matter, unknown columns are ignored, and a row with a blank
`issue_id` is skipped.

## Adding a new issue

1. Open `docs/agent_output/00-issues/issue-register.xlsx` in Excel.
2. Append a row. `issue_id` is the only strictly required cell, but `affected_symbols` and
   `affected_files` are what let the Root Cause Analyst locate the defect in the graph, and
   `detection_notes` is what the re-scanner re-checks after a fix — a row without them still runs,
   just with thinner evidence.
3. Save, then `node scripts/list-register.js` to confirm it parses.
4. Re-run the agents from step 02.
