---
name: 00-issue-register
description: "Read and validate the Excel vulnerability issue register. Use when listing registered issues, inspecting a parsed issue row, or checking the issue-register column contract."
argument-hint: "[--full | --issue ISSUE-001]"
---

# Issue Register

Canonical instructions and zero-dependency scripts are in `.github/skills/00-issue-register/`.

Read `.github/skills/00-issue-register/SKILL.md` before acting. It defines the read-only spreadsheet contract, issue normalization, markdown-body synthesis, and reporting requirements.

Run its scripts from that canonical folder. Never write `docs/agent_output/00-issues/issue-register.xlsx` unless the user explicitly asks to add or change an issue.