---
name: 08-migration-knowledge
stage: 08-knowledge
agent_number: 08
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 08 — Migration Knowledge

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Produce migration facts from two independent channels — documentation and artifact reality —
and mark each fact by what actually supports it. Only artifact-verified facts may authorize a
change.

## 2. Authority

### MAY

- Extract candidate facts from pinned documentation.
- Verify facts against published artifacts: `javap` signatures, `spring-configuration-metadata.json`, POM contents, class presence.
- Read a BOM transitively, following `<scope>import</scope>` entries to a bounded depth and carrying `${project.version}` down, because a Spring Cloud train is composed almost entirely of imports.
- Diff the published bytecode of every coordinate both BOMs manage at different versions, within a per-run budget, skipping packaging-only artifacts that declare no types.
- Generate configuration-property migration rules from published metadata.
- Mark each fact `VERIFIED`, `CANDIDATE` or `CONFLICTING`.

### MUST NOT

- **MUST NOT** promote a documentation-only fact to `VERIFIED` (R8).
- **MUST NOT** discard a `CONFLICTING` fact — a disagreement between channels is a finding.
- **MUST NOT** let a `CANDIDATE` fact authorize a mutation.
- **MUST NOT** hand-maintain property rules that can be generated from published metadata.
- **MUST NOT** report a coordinate as unaffected because it was never examined. A coordinate outside the diff budget, or managed by only one BOM, is recorded in `api_diff.not_diffed` and raises a gap.

## 3. Preconditions

- `DOCUMENTATION_RETRIEVED`.

## 4. Inputs

- `document-registry.json` — the documentation channel.
- Published artifacts fetched per edge — the artifact channel.
- `migration-path.json`, `dependency-model.json`.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `migration-knowledge.json` | `migration-knowledge/migration-knowledge.schema.json` |
| `documentation-channel.json` | — |
| `artifact-channel.json` | — |
| `knowledge-summary.json` | — |
| `migration-rules/generated-properties/property-migration-rules.json` | — |

All outputs are published under `output/08-knowledge/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift knowledge
```

## 7. Permitted adapters

- `apidiff/JavapApiDiffAdapter`.
- `compat/MavenCentralVersionSpaceAdapter`.
- `http/HttpFetcher`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Every fact names its channel, its evidence level and the artifact it was verified against.
- `CONFLICTING` raises the `DOCUMENTATION_CONFLICT` approval gate.
- Generated property rules are written to `migration-rules/generated-properties/`, and their count feeds the planner's deterministic coverage.
- Only the published-bytecode diff can establish that a type was *removed* rather than *documented as deprecated*, which is why `API_REMOVED` requires evidence level `E3`. Reading a BOM only one level deep hides an entire release train: `spring-cloud-dependencies` has 17 dependency blocks and all 17 are imports.
- The diff's reach is reported, not assumed. `artifacts_diffed`, `artifact_budget` and `not_diffed` are published so a reader can see what was examined.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No artifact can be fetched or read | `REFUSAL` | `2` |
| A documentation-only fact would authorize a mutation | `REFUSAL` | `2` |
| Channels disagree | `SUCCESS; `DOCUMENTATION_CONFLICT` gate raised` | `0` |

## 10. Downstream consumers

Agents 09, 11, 12, 15, 17.

## 11. Rules enforced

- **R6** — documentation lags releases.
- **R8** — documentation alone is insufficient.
- **R15** — the artifact plane is truth.
