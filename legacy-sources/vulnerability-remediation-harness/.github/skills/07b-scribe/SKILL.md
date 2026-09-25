---
name: 07b-scribe
description: 'Writes docs/agent_output/07-ship/pr_<id>.md (PR-ready title/body/checklist) and docs/agent_output/07-ship/audit_<id>.md (full chain-of-custody audit trail from issue through merge verdict) for every fix with a rendered merge-arbiter verdict — always, regardless of whether it Cleared or was Blocked. Never touches git or GitHub; content only, for a human to act on. Phase C step 3, second half. Use when asked to write up a fix for review, produce PR content, or document the full trail behind a shipped or blocked patch.'
argument-hint: 'Nothing (processes every fix with a rendered merge-arbiter verdict), or a specific issue id such as ISSUE-001'
---

# Scribe

Phase C step 3, second half. Writes the record — always. A Cleared patch gets a PR ready to open; a
Blocked one still gets a PR draft (clearly marked not to use) and, either way, a full audit trail
linking every stage from the original issue to the final decision. **This skill never runs `git` or
`gh` — everything it produces is content for a human to act on themselves.**

**`docs/agent_output/04-remediation/`, `docs/agent_output/04-remediation/`, `docs/agent_output/02-root-cause/`, `docs/agent_output/03-blast-radius/`, `docs/agent_output/00-issues/`,
`docs/agent_output/05-verify/`, `docs/agent_output/06-test-gate/`, `docs/agent_output/06-test-gate/` and `docs/agent_output/07-ship/verdict_*.md` are all read-only input.**
Nothing here writes to any of them — the Scribe only ever adds `pr_<id>.md` and `audit_<id>.md`
alongside the merge arbiter's verdict.

## Why the decision always comes from the verdict file, never from you

`render-scribe.js` reads `Decision` straight out of `docs/agent_output/07-ship/verdict_<id>.md` — never from what you
write. This means the Blocked banner on a PR draft cannot be silently omitted by an agent choosing
not to mention it; it is mechanically driven by the merge arbiter's actual output, every time.

## Inputs

The full chain for one fix: the issue, root cause report, blast radius report (if any), fix plan,
fix report + diff, all three Step 1 verdicts, the QA report, the build report, and the merge
arbiter's verdict.

## Output

- `docs/agent_output/07-ship/audit_<id>.md` — chronological chain of custody, every claim linked to its source.
- `docs/agent_output/07-ship/pr_<id>.md` — PR title, summary, and test-plan checklist. Opens with a
  `⚠️ BLOCKED — do not open this PR` banner when the linked verdict is not `Cleared`.

`docs/agent_output/07-ship/README.md`'s index is owned by this skill (not by merge-arbiter) — it is rewritten on
every render run and lists every verdict alongside whether its PR/audit content exists yet.

Intermediate: `.github/.pipeline-context/scribe/<id>.chain.facts.{json,md}` (script), `<id>.content.json` (agent).

## Procedure

### Step 1 — Discover the workload

```powershell
cd .github/skills/07b-scribe
node scripts/list-scribe-workload.js
```

No `npm install` needed — zero dependencies. Workload = every fix with a rendered
`docs/agent_output/07-ship/verdict_<id>.md`, Cleared or Blocked alike.

### Step 2 — Collect the chain of custody

```powershell
node scripts/collect-chain.js --all
```

Gathers links and full text from every stage into `.github/.pipeline-context/scribe/<id>.chain.facts.md` — facts
only, no prose synthesis.

### Step 3 — Per fix: write the content

Read the briefing in full, then write `.github/.pipeline-context/scribe/<id>.content.json` per
[templates/content.schema.json](./templates/content.schema.json): `audit_narrative` (every claim
linked to its source document — do not restate a fact without the link), `pr_title`, `pr_summary`,
`pr_test_plan` (drawn from what the QA and build gates actually ran, including anything they could
not run). **Do not restate Cleared/Blocked here** — the render script pulls that from the verdict
directly.

### Step 4 — Render

```powershell
node scripts/render-scribe.js --all
```

Writes both files for every fix with content ready, and rewrites `docs/agent_output/07-ship/README.md`'s index.

### Step 5 — Report back

Per fix: Decision (from the verdict, not restated by you), and links to both files. If Blocked, say
so plainly rather than downplaying it.

## Constraints

- DO NOT create, edit, rename or delete anything outside `.github/.pipeline-context/scribe/`, `docs/agent_output/07-ship/pr_*.md`
  and `docs/agent_output/07-ship/audit_*.md`. Every upstream document — including `docs/agent_output/07-ship/verdict_*.md` — is
  read-only.
- DO NOT run `git`, `gh`, or any command that mutates the repository or a remote. This skill produces
  content, never actions.
- DO NOT restate or infer the Cleared/Blocked decision yourself — it is read mechanically from the
  verdict file.
- DO NOT soften a Blocked outcome in the PR content or omit the banner — it is rendered
  automatically, but your `pr_summary`/`pr_test_plan` text must not read as if the patch were ready.
- DO NOT state a claim in the audit narrative without a link to the document that supports it.
- DO NOT print full upstream reports into chat — link to the rendered files.
- No `npm install` is needed — this skill has zero dependencies.
