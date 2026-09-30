---
name: 04b-fixer
description: "Generate and verify the smallest remediation diff for a human-approved fix. Code-logic path for every CWE; dependency-upgrade path for CWE-1104, which also confirms the resolved dependency:tree version. Use to implement an approved plan, write the patch for a strategy-only MARS proposal, or bump a vulnerable dependency."
argument-hint: "[ISSUE-001 | RUN-… FINDING-…]"
---

# Fixer

Canonical instructions, schemas, and isolated-worktree scripts are in `.github/skills/04b-fixer/`.

Read `.github/skills/04b-fixer/SKILL.md` before acting, including its "Dependency-upgrade path" and "Inside MARS" sections. Work only on plans a person approved: a Status of exactly `Approved` in Pipeline mode, or an approved proposal in a harness run. Route CWE-1104 plans to the dependency-upgrade path. Never modify real application source. In a harness run, submit the patch with `harness submit-patch` and let a person approve it.
