# Reference packs

One file per version jump. A pack is the **only** place framework-specific migration knowledge
lives in this skill — the scripts and `SKILL.md` stay stack-agnostic on purpose, so covering a
new migration is a matter of adding a file here, not changing code.

`scripts/detect-baseline.js` reads each pack's front matter and suggests the one whose `detect:`
coordinates match the project it was pointed at.

## Front matter contract

```yaml
---
id: <kebab-case-id>              # must equal the filename without .md
title: <human title of the jump>
stack: <framework or platform name>
from: "<version or major the pack migrates from>"
to: "<version or major the pack migrates to>"
language_from: "<Java version before>"
language_to: "<Java version after>"
detect:                          # groupId:artifactId[:versionPrefix], any match selects the pack
  - com.example:example-parent:3
---
```

Keep `detect:` entries specific enough that they cannot match a project that has *already* been
migrated — a version prefix, or an artifact id that only exists on the old side of the jump.

That block is the whole required contract, and a pack with nothing else still parses and works:
every optional field below normalises to empty, and the migration runs the compiler-driven path.

### Optional metadata

All of it is plain data read by `scripts/lib/references.js` — a small YAML subset: scalars,
`- item` lists, nested maps, lists of maps, `[a, b]` flow lists. Every scalar is read as a string.
Single-quote a regular expression (`'com\.example'`) so its backslashes stay literal.

```yaml
provenance:                      # where the pack's knowledge came from
  sources:
    - <release notes / migration guide URL / recipe catalogue inspected>
  observed_run: <a recorded run the pack's round table is taken from>
  last_reviewed: "YYYY-MM-DD"
target_constraints:              # what the target generation requires; recorded as observations
  platform_major: "4"
  language_min: "17"
  language_target: "21"
  note: <how to confirm the exact release's requirements>
path:                            # migration path / preconditions
  preparation_line: "3.5"        # version prefix a project is expected to start from
  note: <what to do when it does not>
surfaces:                        # source patterns detect-baseline.js records per file (regex, not a Java model)
  - id: json
    label: <what the surface is>
    rule: <pack section>
    scope: main | test | all     # default all
    patterns:
      - 'com\.example\.json'
ecosystem_boms:                  # platform BOMs / pinned libraries whose compatibility must be proven
  - coordinate: <groupId:artifactId>
    property: <version property, if any>
    note: <how to check compatibility>
transformations:                 # deterministic transformations the plan may select
  - id: <kebab-case id>
    provider: openrewrite        # the only provider wired today
    policy: optional | required  # optional: fall back when unavailable; required: block
    phase: after-baseline
    build_tools: [maven, gradle]
    recipes:
      - <fully qualified recipe name>
    artifacts:
      - <groupId:artifactId:PINNED-version>
    plugin_version: <pinned rewrite-maven-plugin version>
    gradle_plugin_version: <pinned org.openrewrite:plugin version>
    config: openrewrite/<file>.yml   # optional declarative recipe inside references/, see below
    license: <licence name of the recipe artifact>
    license_note: <anything a reviewer must know>
    source_repository: <where the artifact is resolved from>
    recipe_target: <the version the recipe itself migrates to, or "requested">
    covers: [<impact areas>]
    notes: <scope caveats a reviewer must check in the preview>
verification:                    # commands a human or the agent runs to verify a rule — documentation only
  - <command>
```

**Nothing here is executed as written.** `verification` entries are documentation. A
`transformations` entry is turned into a command by `scripts/lib/openrewrite.js`, which validates
every recipe name, coordinate and version against a plain-identifier pattern and refuses a floating
version (`RELEASE`, `LATEST`, ranges, `SNAPSHOT`): a recipe run must be reproducible. A `config`
file may contain `{{target_platform_version}}` and `{{target_language}}`; they are replaced with the
versions the migration request recorded in `baseline.json` (validated as plain version strings)
into a copy in the session directory, so a curated recipe is pinned to the requested target rather
than to whatever release the upstream recipe happens to target.

### How transformation metadata is used

1. `detect-baseline.js` lists the pack's transformations under `capabilities` in `baseline.json`.
2. The agent selects one in `migration-plan.json` (`deterministic_candidates[].transformation`),
   optionally narrowing its recipes. Nothing the plan does not name can run.
3. `run-migration-build.js --rewrite dry-run` previews it in the sandbox; the agent inspects the
   patch; `--rewrite apply --rewrite-preview rewrite-NN` applies exactly that preview and builds.
4. **Fallback.** If the artifact cannot be resolved — no network, repository credentials, a licence
   gate, an unsupported build tool — the run is recorded as `unavailable` with the reason and never
   reported as executed. An `optional` transformation then falls back to the compiler-driven path
   (the pack's rules, applied as builds demand); a `required` one blocks the session.

Existing packs need none of this. The local pack stays the reviewable migration contract and
nothing requires network access just to read it.

## What a pack must contain

1. **The version baseline** — what the target release requires (language level, build tool, JDK),
   and how to confirm it from the release's own documentation rather than from memory.
2. **Dependency and coordinate changes** — renamed artifacts, split starters, moved BOMs, plugin
   versions, with old → new in a table.
3. **Source-level API changes** — package relocations, renamed types, changed builder or
   configuration APIs, each with a *before* and *after* excerpt and the compiler error it shows up
   as. The compiler error is what makes a rule findable when a build round fails.
4. **Test-layer changes** — annotations, slices, and test-scoped artifacts, which usually break
   separately from and later than main code.
5. **Runtime and configuration changes** — properties renamed or removed, container base images,
   anything that compiles fine and fails at startup.
6. **What is *not* required by the jump** — the changes people habitually bundle into an upgrade
   that the upgrade does not force. Naming them keeps a migration report honest.
7. **How to verify a coordinate or class name before using it**, so a rule that has drifted since
   the pack was written is caught rather than propagated.

## Rules for every pack

- Every rule states the **observable symptom** (the compiler or startup error), not only the fix.
- Anything the pack is not certain of is marked as *verify*, with the command that verifies it.
  A pack is a starting point for a real build, never a substitute for one.
- No rule is applied because a pack says so — it is applied because a build round failed in the
  way the rule describes, because the pack's baseline section requires it up front, or because the
  plan selected a deterministic transformation for it and its dry-run preview was inspected first.
- Stack-specific facts live in the pack, never in `scripts/`. A pack says which source patterns
  matter (`surfaces`) and which recipes exist (`transformations`); the scripts stay generic.
- The exact target version is never written into a pack as a default. It comes from the migration
  request; a recipe that migrates to an intermediate release must be reconciled to it.
