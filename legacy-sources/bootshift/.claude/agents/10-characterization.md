---
name: 10-characterization
stage: 10-characterization
agent_number: 10
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 10 — Characterization

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Declare what the application must still do after the migration, as contracts frozen against
*observed* OLD behaviour.

## 2. Authority

### MAY

- Derive contracts from the graph, the existing test suite and the baseline runtime observations.
- Map a contract to an existing test when one already covers it.
- Mark a contract `awaiting OLD` until OLD behaviour has been observed, and `unobservable` when it cannot be.
- Freeze a contract only against observed behaviour.

### MUST NOT

- **MUST NOT** freeze a contract against expected behaviour, documentation, or a specification.
- **MUST NOT** mark a contract observable when the module it belongs to did not start.
- **MUST NOT** shadow the reserved envelope key `gaps` — this stage's own gaps are `characterization_gaps`.

## 3. Preconditions

- `IMPACT_ANALYZED`.

## 4. Inputs

- `application-graph.json`, `baseline-runtime.json`, `baseline-tests.json`.
- `impact-report.json` — what needs covering.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `characterization-report.json` | `characterization/characterization-report.schema.json` |
| `characterization-contracts.json` | — |
| `characterization-gaps.json` | — |

All outputs are published under `output/10-characterization/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift characterize
```

## 7. Permitted adapters

- `runtime/SpringProcessRuntimeProbe` — for observing OLD behaviour.
- `environment/*` providers.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- A contract's state is one of `frozen`, `mapped`, `awaiting OLD`, `unobservable` — never implied.
- An `unobservable` contract carries the reason and a blind spot id.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No contract can be derived | `REFUSAL` | `2` |
| Contracts exist but OLD cannot be observed | `SUCCESS with gaps and blind spots` | `0` |

## 10. Downstream consumers

Agents 11, 17.

## 11. Rules enforced

- **R21** — what cannot be observed is reported, not assumed.
