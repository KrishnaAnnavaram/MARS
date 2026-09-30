---
name: 04d-remediation-research
description: "Agent 4 deepest-gap fallback. Fires ONLY when a diagnosed CWE is in neither the catalog (04a) nor the KB (04c) — a KB gap. Runs a structured, evidence-driven security investigation (understand vuln, validate root cause, threat model, security objective, >=2 remediation candidates, evaluate, recommend, define validation tests) and produces a NOVEL, Low-confidence, Proposed remediation for human review. Never a diff, never self-approved, never a fabricated citation; on insufficient evidence it produces a Proposed EVIDENCE-GAP plan (what is known + what is missing) for a human instead of dead-ending. Use when a vulnerability has no known remediation in catalog or KB."
argument-hint: "[ISSUE-005]"
---

# Remediation Research (04d)

Canonical instructions, schemas, scripts, and the research method are in
`.github/skills/04d-remediation-research/`.

Read `.github/skills/04d-remediation-research/SKILL.md` before acting. 04d activates only on a **KB
gap** (CWE in neither catalog nor KB), reported by `04c`'s `run-fallback.js`. It investigates and
derives a **novel** remediation strategy — always `confidence: Low`, always `Status: Proposed`,
`remediation_source: 04d-remediation-research`, `derived_pattern.type: novel-research`. It never
invents a citation, never writes a diff, never self-approves, and on insufficient evidence produces a
Proposed EVIDENCE-GAP plan (`research_status: insufficient_evidence`) — what is known + what is
missing — for human review rather than forcing a confident fix or dead-ending. The existing render →
human approval → 04b → 05 → 06 → 07 flow is unchanged.

In MARS this is part of Agent 04. Read the "Inside MARS" section of the canonical SKILL.md first: in a harness run, the harness performs the deterministic part of this skill and a person approves every change.
