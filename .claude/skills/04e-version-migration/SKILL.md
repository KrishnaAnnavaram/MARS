---
name: 04e-version-migration
description: "Migrate a Java project to a new framework generation or Java level (Spring Boot 3 to 4, Java 17 to 21) from a reference pack: round 0 first, build rounds driven by real compiler failures, before-and-after behaviour probes, all in a sandbox. In a MARS run, resolve the migration engine's NEEDS_HUMAN build errors. Use when asked to upgrade or migrate a framework or Java version, or to produce a migration report."
argument-hint: "[\"Spring Boot 3 to 4\" | --project <path> | RUN-…]"
---

# Version Migration (04e)

Canonical instructions, reference packs, schemas, and scripts are in `.github/skills/04e-version-migration/`.

Read `.github/skills/04e-version-migration/SKILL.md` before acting, including its "Inside MARS" section. No matching reference pack is a stop condition. Never skip round 0, never guess an import path, and never write to the project directory unless the user explicitly asked for the migration to be applied. In a harness run, submit fixes with `harness submit-patch` and let a person approve them.
