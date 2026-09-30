---
name: 04a2-remediation-research
description: 'Agent 4 deepest-gap fallback. Activates ONLY when a diagnosed CWE is in neither the remediation catalog (04a) nor the local knowledge base (04a1) — a KB gap. Instead of stopping, it runs a structured, evidence-driven security investigation (understand the vulnerability, validate the root cause, model the threat, define the security objective, generate and evaluate multiple remediation candidates, recommend the smallest safe one, and define validation tests) and produces a NOVEL remediation strategy for human review. Always Low confidence, always Proposed, never a diff, never self-approved, never a fabricated citation. If evidence is insufficient it does NOT dead-end: it produces a Proposed EVIDENCE-GAP plan (what is known + what evidence is missing + a conservative default) for human review, rather than inventing a confident fix. Use when a vulnerability has no known remediation in either the catalog or the KB.'
argument-hint: 'A specific issue id whose CWE is a catalog gap AND a KB gap, e.g. ISSUE-005'
---

# Remediation Research (04a2) — the novel-research fallback

The third level of Agent 4's remediation knowledge hierarchy:

| Level | Skill | Knowledge source | Meaning |
|---|---|---|---|
| 1 | `04a-fix-strategist` | CWE catalog | We already know the remediation pattern |
| 2 | `04a1-remediation-intelligence` | Local KB / historical fixes | We have relevant prior knowledge |
| **3** | **`04a2-remediation-research`** | **Structured security investigation** | **No existing remediation — we must develop a new proposal** |

Before 04a2, a KB gap was a dead-end. Now the pipeline investigates and produces a **novel, evidence-backed, Low-confidence** remediation proposal — without loosening any gate.

## When it runs (and when it must not)

| Condition | Action |
|---|---|
| Any detected CWE is in the catalog | **Not 04a2.** 04a handles it. |
| Gap CWE is in the KB | **Not 04a2.** 04a1 handles it. |
| Gap CWE in **neither** catalog nor KB (KB gap) | **04a2 runs.** |
| 04a2 cannot establish a *confident* strategy | Produce a **Proposed EVIDENCE-GAP plan** (`research_status: insufficient_evidence`) — what is known + what evidence is missing + a conservative default, routed to a human. **Never a dead-end; never a fabricated confident fix.** |

The trigger is the KB gap that `04a1`'s `run-fallback.js` reports when the gap CWE has no KB entry.

## What it will not do

- **Never invents** a remediation without analysis, and **never fabricates a citation.** With no web access in this runtime, output is labelled *internally derived*, `external_research_available: false`, and **Low** confidence.
- **Never writes a diff**, never edits source — 04b implements after approval.
- **Never self-approves** — output is always `Status: Proposed`.
- **Never upgrades its own confidence** — 04a2 is Low by default.

## Inputs

| # | Input | Source |
|---|---|---|
| 1 | Root cause report (Agent 2) | `docs/agent_output/02-root-cause/root_cause_<id>.md` |
| 2 | Blast radius report (Agent 3), if any | `docs/agent_output/03-blast-radius/blast_radius_<id>.md` |
| 3 | Issue row + vulnerable source | issue register + files on disk |
| 4 | Catalog + KB (to confirm the double gap) | `04a/catalog/cwe-patterns.json`, `04a1/knowledge/remediation-kb.json` |

## Output

`.claude/.pipeline-context/fix-strategy/<id>.strategy.json` — base-contract compatible (so the existing `render-fix-plan.js` renders it), plus:
- `remediation_source: 04a2-remediation-research`, `confidence: Low`, `catalog_reference.title: null`
- a `research` payload (vulnerability, root cause, threat, objective, candidates, recommendation, validation)
- `derived_pattern.type: novel-research` with honest provenance
- `promotion_candidate: true` and a separate `<id>.promotion-candidate.json`

`render-fix-plan.js` renders it as a normal `fix_plan_<id>.md` at **Status: Proposed** with the novel-research sections.

## Procedure

```powershell
cd .claude/skills/04a2-remediation-research   # zero dependencies

# 1. Extract the deterministic vulnerability understanding + confirm the double gap
node scripts/research-context.js --issue <ISSUE-ID>        # or --gap-check <CWE>

# 2. Author the analysis (agent judgment) to
#    .claude/.pipeline-context/research/<id>.analysis.json  — threat, security_objective,
#    candidates (>=2), evaluation, recommendation, validation_requirements, provenance,
#    research_status ("established" or "insufficient_evidence"). See templates/.

# 3. Assemble the strategy (validates + writes strategy.json + promotion candidate; on
#    insufficient_evidence it writes a Proposed EVIDENCE-GAP plan instead of a targeted fix)
node scripts/generate-strategy.js --issue <ISSUE-ID>

# 4. Render with 04a's real renderer -> Proposed plan
node ../04a-fix-strategist/scripts/render-fix-plan.js --issue <ISSUE-ID>
```

Then hand off exactly as 04a/04a1 do: the plan sits at `Proposed` until a human approves; then 04b → 05 → 06 → 07, every gate unchanged.

## The 13-step research method (what the agent authors in step 2)

1. Confirm the gap · 2. Collect context (reuse Agents 1/2/3) · 3. Understand the vulnerability ·
4. Validate root cause (facts vs conclusions vs **hypotheses**, kept distinct) · 5. Threat model ·
6. Security objective (the invariant) · 7. Research approaches (evidence-based) · 8. Generate ≥2
candidates · 9. Evaluate them · 10. Select the smallest safe one · 11. Define validation tests ·
12. Attach evidence + provenance · 13. Emit `strategy.json` (Low, Proposed).

## How 04a2 differs from 04a and 04a1

| | 04a | 04a1 | 04a2 |
|---|---|---|---|
| Knowledge | catalog | KB / historical fixes | **novel investigation** |
| Confidence | established | derived | **Low (always)** |
| Citations | catalog entry | KB refs | **internal reasoning; none fabricated** |
| Multiple candidates | no | no | **yes (≥2)** |
| No confident fix | — | reports KB gap | **evidence-gap plan (`insufficient_evidence`) → human** |

## KB promotion (the learning loop)

A 04a2 remediation that is approved → implemented (04b) → verified (05) → tested (06) → **Cleared** (07) becomes eligible for promotion into the KB (`04a1/knowledge/remediation-kb.json`). 04a2 writes a **promotion candidate**; it is **never** auto-written to the KB. A human promotes it, after which future similar issues are handled by 04a1.

## Self-test

```powershell
node scripts/test-sample.js
```

Runs the full path on the bundled double-gap fixture (`SAMPLE-CWE502`) through 04a's real renderer in a throwaway temp dir, asserting: 04a2 fired, Low confidence, catalog + KB both gap, ≥2 candidates, `Status: Proposed`, no diff, no auto-approve, honest provenance — and that the evidence-gap path (`SAMPLE-THIN`) produces a **Proposed evidence-gap plan** (not a dead stop). Touches nothing in the repo.

## Notes

- Zero dependencies. Reuses `04a`'s `plans.js` and `04a1`'s `fallback.js` so all three skills agree on catalog/KB membership.
- Isolated testing via `PIPELINE_CONTEXT_DATA_DIR` + `PIPELINE_OUTPUT_DIR`.
