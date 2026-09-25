---
name: 05-verify
description: "Re-scan, red-team, and behavior-check drafted remediation diffs in isolated worktrees. Use to verify a patch does not remain vulnerable, bypassable, or behaviorally unsafe."
argument-hint: "[ISSUE-001]"
---

# Verification Layer

Canonical instructions, verdict schemas, and verification scripts are in `.github/skills/05-verify/`.

Read `.github/skills/05-verify/SKILL.md` before acting. Run re-scan, red-team, and behavior guard independently against materialized patched content; do not claim that this stage decides shipment.