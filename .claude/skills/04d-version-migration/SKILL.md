---
name: 04d-version-migration
description: "Migrate a Java project to a new framework generation or language level: understand the application, record the baseline build and runtime behaviour, plan the impact, apply previewed deterministic OpenRewrite transformations in a sandbox, repair what remains with evidence-driven build rounds, and compare behaviour before/after. Use to upgrade Spring Boot 3 to 4, move to a newer Java version, or produce a migration report."
argument-hint: "[\"Spring Boot 3 to 4.1.1\" | \"migrate to Java 21\"]"
---

# Version Migration

Canonical instructions, reference packs, schemas, and sandbox scripts are in `.github/skills/04d-version-migration/`.

Read `.github/skills/04d-version-migration/SKILL.md` and the reference pack it names before acting. A missing reference pack is a stop condition — never migrate a framework generation from memory. Record the baseline build and runtime probe before changing anything, write and validate the migration plan before any transformation, apply only OpenRewrite changes whose dry-run you have inspected against the plan, change source by hand only when a build, test or probe demands it, and complete the before/after probe comparison before calling a migration successful.

All work happens in the sandbox copy under `.github/.pipeline-context/version-migration/<slug>/workspace/`. Applying the result to the real project is a separate step that runs only when a human explicitly asks for it.

This skill is also Stage 2 of `04_fix-generator` for any Approved fix plan whose **Fix Type** reads `VERSION_MIGRATION`: start with `node scripts/detect-baseline.js --issue <ID>` (it re-checks the plan and takes the target from it), and rendering then writes the standard `fix_<id>.md` + `fix_<id>.diff` handoff that agents 05–07 consume. A pack is used only when the project's declared platform version is in the pack's `from` generation; an unsupported path (e.g. Boot 2.x → 4.x with no 2→3 pack) is BLOCKED, never jumped. Every script writes the per-run `MIGRATION_SUMMARY.md` / `migration-summary.json` automatically.
