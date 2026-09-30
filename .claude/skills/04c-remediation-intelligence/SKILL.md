---
name: 04c-remediation-intelligence
description: "Fallback for the Fix Strategist that fires only on a catalog gap — a diagnosed CWE with no cwe-patterns.json entry. Searches a local remediation knowledge base, ranks historical fixes, and derives a grounded, cited, human-review-first remediation strategy instead of a hollow gap plan. Use when every detected CWE for an issue is uncatalogued."
argument-hint: "[SAMPLE-CWE22]"
---

# Remediation Intelligence (04c)

Canonical instructions, the knowledge base, schemas, and scripts are in
`.github/skills/04c-remediation-intelligence/`.

Read `.github/skills/04c-remediation-intelligence/SKILL.md` before acting. This skill activates only
when the Fix Strategist (`04a`) reports a **catalog gap** and no detected CWE is catalogued. It never
invents a pattern (a CWE missing from both the catalog and the knowledge base is a reported KB gap),
never writes a diff, and never approves — it writes a `Status: Proposed` strategy for human review,
which `04a`'s render step turns into the plan exactly as it does for a catalogued fix.

In MARS this is part of Agent 04. Read the "Inside MARS" section of the canonical SKILL.md first: in a harness run, the harness performs the deterministic part of this skill and a person approves every change.
