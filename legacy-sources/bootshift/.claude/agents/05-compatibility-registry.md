---
name: 05-compatibility-registry
stage: 05-compatibility
agent_number: 05
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 3]
---

# Agent 05 — Compatibility Registry

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Establish what the project is on today and what version space exists, with an evidence quality
grade attached to every lifecycle fact.

## 2. Authority

### MAY

- Read the current Boot line, Java level and Spring Cloud train from the build model.
- Enumerate published versions from the artifact repository.
- Grade every lifecycle row `VERIFIED`, `ADVISORY`, `ESTIMATED` or `UNKNOWN`, and downgrade rows older than the staleness window.
- Probe whether an internal-looking component exists in a public repository, rather than inferring internality from a group id.

### MUST NOT

- **MUST NOT** eliminate a candidate line on anything weaker than a `VERIFIED` lifecycle fact.
- **MUST NOT** assume an unknown internal component is compatible (R20).
- **MUST NOT** treat a curated table as current without checking its `AS_OF` date.

## 3. Preconditions

- `BASELINE_SEALED`.

## 4. Inputs

- `build-model.json`, `dependency-model.json`.
- `LifecycleSource` with its `AS_OF` date.
- `policies/default/internal-components/`.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `compatibility-registry.json` | — |
| `lifecycle-registry.json` | `compatibility/lifecycle-registry.schema.json` |
| `version-space-evidence.json` | — |
| `artifact-availability.json` | — |
| `internal-components.json` | — |

All outputs are published under `output/05-compatibility/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift compatibility
```

## 7. Permitted adapters

- `compat/MavenCentralVersionSpaceAdapter`.
- `http/HttpFetcher` — egress allowlist, content-addressed cache.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Every lifecycle row carries an evidence quality and a source.
- A stale row is downgraded, never silently trusted — the harness becomes more cautious as its data ages.
- `unknown_internal_component_action` decides whether an `UNKNOWN` component blocks; it is never resolved by assumption.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| The artifact repository is unreachable and the cache is cold | `REFUSAL` | `2` |
| An internal component is `UNKNOWN` and policy says `BLOCK` | `POLICY_BLOCK` | `3` |

## 10. Downstream consumers

Agents 06, 08, 11.

## 11. Rules enforced

- **R20** — unknown internal components are never assumed compatible.
- **R31** — capabilities are discovered dynamically.
