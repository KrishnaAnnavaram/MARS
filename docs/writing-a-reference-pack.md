# Writing a Reference Pack

A **reference pack** teaches MARS a new migration path. It has two parts:

1. **The pack**: a Markdown migration guide with YAML front matter. It is the human-readable,
   authoritative knowledge.
2. **The rules file**: `capabilities/spring-migration/reference-packs/<id>.rules.json`, the
   machine-readable rules derived from the pack and pinned to it by SHA-256.

The shipped example is Spring Boot 3 → 4:

- pack: `legacy-sources/spring-migration-reference/.github/skills/04d-version-migration/references/spring-boot-3-to-4.md`
- rules: [`capabilities/spring-migration/reference-packs/spring-boot-3-to-4.rules.json`](../capabilities/spring-migration/reference-packs/spring-boot-3-to-4.rules.json)

Read both side by side before writing your own.

## What a pack can and cannot do

The migration capability is built for **Spring Boot on Maven**. The Migration Advisor detects the
current platform from the Spring Boot parent or dependencies, and the engine applies a fixed set of
rule *kinds* (listed below). MARS loads every `*.rules.json` in the `reference-packs` folder
automatically, so:

- **Another Spring Boot jump** (for example 2 → 3) that only uses the existing rule kinds needs
  **no code changes**, only a pack and a rules file.
- **A new rule kind** needs a transformer in
  [`ReferencePackEngine`](../capabilities/spring-migration/src/main/java/com/mars/harness/capabilities/migration/engine/ReferencePackEngine.java).
  Until one exists, a build-file rule of an unknown kind changes nothing, and a symptom rule of an
  unknown kind is recorded as "rule kind X has no transformer" and the round stops in `NEEDS_HUMAN`.
- **Another framework** also needs platform detection and lifecycle facts in
  [`MigrationAdvisor`](../capabilities/spring-migration/src/main/java/com/mars/harness/capabilities/migration/advisor/MigrationAdvisor.java).

## 1. Write the pack (Markdown)

The pack must start with front matter. MARS reads these fields:

```yaml
---
id: spring-boot-3-to-4
title: Spring Boot 3.x to 4.x (Java 17 to 21)
from: "3"
to: "4"
language_from: "17"
language_to: "21"
detect:
  - org.springframework.boot:spring-boot-starter-parent:3
  - org.springframework.boot:spring-boot-dependencies:3
  - org.springframework.boot:spring-boot-starter-web
---
```

| Field | Use |
|---|---|
| `id` | Must equal `pack_id` in the rules file. |
| `from`, `to` | The platform versions the pack migrates between. |
| `language_from`, `language_to` | Java levels. `language_to` is what the `JAVA_LEVEL` rule sets. |
| `detect` | `group:artifact[:version-prefix]` entries. The pack applies to a project whose parent, dependencies or plugins match one of them. |

Below the front matter, write the guide in numbered sections (`§1.1`, `§2.2`, …). Every rule cites
its section, and the reports link findings back to it.

**Where to put it.** Anything under `legacy-sources/` must stay byte-identical (an architecture test
enforces this), so put a new pack elsewhere, for example next to its rules file in
`capabilities/spring-migration/reference-packs/`. `pack_file` is resolved relative to the MARS root.

## 2. Write the rules file

```json
{
  "pack_id": "spring-boot-3-to-4",
  "pack_file": "path/to/the/pack.md",
  "pack_sha256": "<sha-256 of the pack file>",
  "framework": "spring-boot",
  "pinned_target": "4.1.1",
  "pinned_target_section": "§1.1",
  "build_file_rules": [ … ],
  "symptom_rules": [ … ],
  "expected_runtime_differences": [ … ],
  "not_required": [ … ]
}
```

### Build-file rules (applied up front)

These run once, before the first build round, because nothing else can move until they do.

| `kind` | Fields | Effect |
|---|---|---|
| `PARENT_VERSION` | `group`, `artifact` | Sets the parent version to the recommended target (`pinned_target`) |
| `JAVA_LEVEL` | none | Sets `java.version` and compiler source/target to the pack's `language_to` |
| `RENAME_DEPENDENCY` | `from`, `to` (`group:artifact`) | Renames a dependency |
| `BUMP_EXPLICIT_VERSION` | `ga`, `below_major`, `to` | Bumps an explicitly versioned dependency whose major version is below `below_major` |
| `DOCKER_BASE` | `pattern`, `replacement` | Replaces text in `Dockerfile`s (e.g. the base JRE image) |

### Symptom rules (applied only when the compiler asks)

A symptom rule is applied **only** when a build round fails with an error that contains one of its
`symptoms` (compile errors) or `test_symptoms` (test failures). Matching is a case-insensitive
substring test on the error message, and the **first matching rule wins**, so order rules from
most to least specific. Copy the symptom text verbatim from a real build log.

The rule is applied only to the files the errors name. If no rule matches an error, the run stops
in `NEEDS_HUMAN`: MARS never guesses an import path.

| `kind` | Fields | Effect |
|---|---|---|
| `REPOINT_IMPORTS` | `imports` (old → new, prefix or exact), optional `annotations`, `types`, `add_dependency` {`ga`, `scope`} | Rewrites imports, annotation names and type names in the failing files, and optionally adds a dependency to the owning pom |
| `REPLACE_DEPENDENCY` | `from`, `to`, optional `scope` | Replaces a dependency in every pom |
| `JACKSON3` | none | Spring-specific Jackson 2 → 3 transformation; not reusable for other packs |

Common fields on every rule:

| Field | Use |
|---|---|
| `id` | Stable rule ID (e.g. `SB4-HEALTH`), shown in plans, the ledger and reports |
| `section` | The pack section it comes from |
| `description` | One line shown in reports and change reasons |
| `mandatory` | Counts toward the advisor's "mandatory migration issue" effort factor (default `true`) |
| `api` | `true` marks it a removed or relocated API (counts toward that effort factor) |
| `scan` / `scan_pom` | Text the **advisor** looks for in `.java` files / `pom.xml` during read-only discovery, to estimate effort before anything runs |

### Expected runtime differences

After migration, MARS replays the behaviour probes and compares them with round 0. A difference
is accepted only if the pack documents it:

```json
{"section": "§13", "path_prefix": "/actuator/health", "verdict": "body-differs",
 "explanation": "Boot 4 health document: keys reordered, same information"}
```

Match by `path_prefix` or by `status_min` (the status code before migration is at least this).
Only **body** differences can be explained this way. A changed status code is never explained
away.

### `not_required`

A list of things the migration deliberately does not do (business-logic fixes, refactors…). It is
printed in the migration report so reviewers know what was out of scope.

## 3. Pin the pack

Compute the SHA-256 of the pack file **exactly as it is on disk** and put it in `pack_sha256`:

```bash
sha256sum path/to/the/pack.md
```

```powershell
(Get-FileHash path\to\the\pack.md -Algorithm SHA256).Hash.ToLower()
```

If the pack text changes later, the hash no longer matches. The advisor then reports a
`BLK-PACK-DRIFT` blocker and the engine refuses to migrate until the rules are re-derived. This
stops rules and guide from silently drifting apart.

Line-ending conversion (for example Git's `core.autocrlf` on Windows) changes the bytes and
therefore the hash. Hash the file as MARS will read it, and consider a `.gitattributes` entry that
fixes the pack's line endings.

## 4. Test it

1. Add a small fixture project under `fixtures/` that starts on the old platform.
2. Run `harness analyze` on it and check the migration assessment: the pack should be detected,
   and `scan` hits should appear as migration issues.
3. Run a `MIGRATION_ONLY` strategy. Each round's errors either match a rule or stop in
   `NEEDS_HUMAN`. Every stop is a missing rule or symptom text to add.
4. Add an end-to-end test modelled on `FullMigrationE2ETest`, and, if you can, a real-toolchain
   case modelled on `RealToolchainAcceptanceIT`.
