---
name: 12-transformation
stage: 12-transformation
agent_number: 12
determinism: DETERMINISTIC (AI OPTIONAL, DEFAULT OFF)
mutation_permission: VIA GATEWAY ONLY
exit_codes: [0, 1, 2, 3]
---

# Agent 12 — Transformation

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Apply the frozen plan's transformations for one edge — through the gateway, and only through
the gateway.

## 2. Authority

### MAY

- Compute the intended content of a file with a deterministic transformer.
- Submit that content to `FileMutationGateway` with the edge id, the plan reference and the justifying fact.
- Record a rejected change as an attempt, with the reason.
- Take entry and exit checkpoints for the edge.

### MUST NOT

- **MUST NOT** write to the filesystem. Transformers return content; the gateway writes it (R11).
- **MUST NOT** touch a file outside the edge's authorized scope.
- **MUST NOT** apply a transformation whose parameters came from the landing target rather than this edge.
- **MUST NOT** rewrite a `javax.*` package that did not relocate — 28 prefixes move, 26 do not.
- **MUST NOT** delete a reference to a removed type unless the transformer carries recorded evidence that removal is a no-op at the target version.
- **MUST NOT** discard an attempt because it failed (R12).

## 3. Preconditions

- `PLAN_FROZEN`, and `BASELINE_SEALED` before that.

## 4. Inputs

- `edge-plan.json` for this edge.
- `migration-knowledge.json` — the justifying facts.
- The migration workspace at the edge's entry checkpoint.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `transformation-report.json` | — |
| `proposed-changes.json` | — |
| `change-event entries` | `change-event/change-event.schema.json` |

All outputs are published under `output/12-transformation/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift migrate --edge <EDGE_ID>
bootshift migrate   # all edges
```

## 7. Permitted adapters

- `mutation/FileMutationGateway` — the only writer.
- `transform/MavenPomTransformer`, `transform/JakartaNamespaceTransformer`, `transform/ConfigurationPropertyTransformer`, `transform/TestFrameworkTransformer`, `transform/RemovedAnnotationTransformer`.
- `scm/GitScmAdapter` — checkpoints.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- One recipe is applied per batch, so each transformer reads the previous recipe's output. Batching every recipe against the pre-edge tree let the last write for a file silently discard the others while the ledger recorded them all as applied.
- Every proposal carries the hash of the content it was derived from; the gateway rejects a stale one.
- `detectBypass()` runs after every edge; a content mismatch on a registered file fails the stage.
- Every applied, rejected and failed change appears in the ledger with its outcome.
- The ledger head hash is reported at the end of the edge.
- `MutationBoundaryTest` (11 tests) and `ArchitectureTest` together make 'only the gateway writes' a property of the codebase.
- Transformers are pure: same input and parameters, same output.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| A change targets a file outside scope | `rejected; edge continues` | `0` |
| Every change fails to apply | `FAILURE` | `1` |
| A bypass write is detected | `FAILURE — integrity violation` | `1` |
| AI is required but forbidden by policy | `POLICY_BLOCK` | `3` |

## 10. Downstream consumers

Agents 13, 14, 19, 20.

## 11. Rules enforced

- **R11** — single writer.
- **R12** — every attempt is recorded.
- **R13** — edge by edge.
