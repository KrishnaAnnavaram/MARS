---
name: 17-differential-validation
stage: 17-differential
agent_number: 17
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 4]
---

# Agent 17 — Differential Validation

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Run identical scenarios against the original and the migrated application, normalize
explicitly, and classify every difference.

## 2. Authority

### MAY

- Execute characterized scenarios against `runtime-old/` and `runtime-new/`.
- Apply the versioned normalization policy, listing every rule that fired on every result.
- Classify each comparison `IDENTICAL`, `EXPECTED`, `UNEXPECTED`, `UNEXPLAINED` or `NOT_COMPARED`.
- Retain SQL text as diagnostic evidence while comparing persistence *semantics*.

### MUST NOT

- **MUST NOT** compare a dimension whose environment equivalence is not satisfied — the correct output is `NOT_COMPARED`.
- **MUST NOT** drop a field implicitly. Normalization rules are explicit, versioned and hashed.
- **MUST NOT** classify a difference `EXPECTED` without a `VERIFIED` fact or a signed approval.
- **MUST NOT** shadow the reserved envelope key `gaps` — this stage's own gaps are `provider_gaps`.

## 3. Preconditions

- `EDGE_RUNTIME_GRAPH_ENRICHED`, and the frozen depth requires differential.

## 4. Inputs

- `characterization-contracts.json`.
- `baseline-runtime.json` and the current runtime observations.
- `environment-equivalence.json`, `policies/normalization/`.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `differential-report.json` | `differential/differential-report.schema.json` |
| `environment-equivalence-check.json` | — |
| `normalization-policy.json` | — |

All outputs are published under `output/17-differential/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift validate --edge <EDGE_ID> --depth differential
```

## 7. Permitted adapters

- `runtime/SpringProcessRuntimeProbe` — both sides.
- `environment/*` providers.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- The normalization policy hash is recorded in the evidence manifest; changing it raises `NORMALIZATION_POLICY_CHANGE`.
- `UNEXPLAINED` and `UNEXPECTED` block (R21).
- `NOT_COMPARED` is a first-class outcome with a reason, never a silent omission.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| An unexplained difference | `REFUSAL` | `2` |
| A security, persistence or business-rule difference | `NEEDS_HUMAN` | `4` |
| Environment equivalence unsatisfied | `SUCCESS with `NOT_COMPARED` and a gap` | `0` |

## 10. Downstream consumers

Agents 18, 19, 20.

## 11. Rules enforced

- **R21** — unexplained differences block.
- **R22** — the environment equivalence contract governs what may be compared.
