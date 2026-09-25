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
  way the rule describes, or because the pack's baseline section requires it up front.
