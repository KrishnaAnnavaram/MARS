---
name: 04d-version-migration
description: "Migrate a Spring Boot / Java project from any published Boot line to any later one (1.5 → 2.x → 3.x → 4.x), with OpenRewrite at the centre: plan a path of edges that never skips a major, run each edge's OpenRewrite upgrade recipe (open-source-only by default) in a sandbox, repair what remains with evidence-driven build rounds, keep every endpoint, and compare behaviour before/after. Use to upgrade Spring Boot across one or several generations, move to a newer Java version, or produce a migration report."
argument-hint: "[\"Spring Boot 2.7 to 3.5\" | \"Spring Boot 3 to 4.1.1\" | \"migrate to Java 21\"]"
---

# Version Migration

Canonical instructions, the migration ladder, reference packs, schemas, and sandbox scripts are in `.github/skills/04d-version-migration/`.

Read `.github/skills/04d-version-migration/SKILL.md` and the reference packs the planned path names before acting. Never migrate a framework generation from memory. Record the baseline build and runtime probe before changing anything, write and validate the migration plan before any transformation, walk the planned edges in order (each previewed, inspected, applied and built green on its version before the next), change source by hand only when a build, test or probe demands it, keep every endpoint, and complete the before/after probe comparison before calling a migration successful.

For Spring Boot, `detect-baseline.js` plans the path from `references/openrewrite/spring-boot-ladder.json`: `PATCH`, `MINOR` and mandatory `MAJOR_BOUNDARY` edges, each with its OpenRewrite recipe, Java level and Spring Cloud train (verified from Maven Central). The licence policy defaults to `open-source-only` — Spring's OpenRewrite upgrade recipes are Apache-2.0 only up to Boot 3.3 (`rewrite-spring` 5.24.1); beyond that 04D uses its own open-source composites and compiler-driven repair. The Moderne Source Available recipes run only with an explicit `--license-policy source-available`, which is the user's decision.

All work happens in the sandbox copy under `.github/.pipeline-context/version-migration/<slug>/workspace/`. Applying the result to the real project is a separate step that runs only when a human explicitly asks for it.

This skill is also Stage 2 of `04_fix-generator` for any Approved fix plan whose **Fix Type** reads `VERSION_MIGRATION`: start with `node scripts/detect-baseline.js --issue <ID>` (it re-checks the plan and takes the target from it), and rendering then writes the standard `fix_<id>.md` + `fix_<id>.diff` handoff that agents 05–07 consume. A path with a missing rung, a boundary no allowed recipe covers, or a Spring Cloud line with no GA train is BLOCKED, never jumped. Every script writes the per-run `MIGRATION_SUMMARY.md` / `migration-summary.json` automatically, including the edges and endpoint preservation.
