# ADR-U003: The migration capability has one contract and a deterministic reference-pack engine

**Status:** Accepted
**Date:** 2026-09-24
**Deciders:** Unified harness architecture

## Context

Two migration systems exist. Bootshift has a 20-stage OpenRewrite pipeline for the lines it
supports. The spring-migration reference has the `04d-version-migration` workflow: a reference pack
(Markdown, pinned by SHA-256), round 0, build-file rules up front, compiler-driven rounds, behaviour
probes and the "never guess an import path" stop. The Spring Boot 3 to 4 jump the harness must
migrate is covered by the reference pack, not by Bootshift's recipes.

## Decision

`MigrationCapability` (assess, plan, execute, validate) is the only contract the kernel sees. The
`spring-migration` pack implements it with:

- a read-only **advisor** (`MigrationAdvisor`). It gives the traffic light, need, priority,
  complexity, effort score and evidence confidence as separate answers, grounded in the build model,
  Bootshift's lifecycle facts, the reference pack and the requested objectives.
- a **reference-pack engine**. The pack's rules are derived once into
  `reference-packs/spring-boot-3-to-4.rules.json`, pinned to the pack's SHA-256; a drifted pack is a
  stop condition. Build-file rules are applied up front. A source rule is applied only when a round
  fails with that rule's quoted symptom, and only to the files the errors name. An error no rule
  matches stops the run for a human.

Every change is a `ChangeProposal` from a `DETERMINISTIC_RULE` provider. It is authorized by the
human execution decision plus the frozen plan's rule allowlist and plan hash, and applied by the
Mutation Gateway. Manual or LLM patches are proposals that need their own approval.

Bootshift's own pipeline stays callable unchanged through its CLI. It is not re-implemented here.

## Consequences

- `FullMigrationE2ETest` compares the harness's round sequence with the recorded reference run
  (rounds 00 to 07): same rules needed, and it ends on a skip-tests package round after round 0's
  pre-existing failures.
- There is no OpenRewrite recipe execution inside the unified migration flow yet. This is a
  documented limitation, not hidden.
