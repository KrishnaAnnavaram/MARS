---
name: 14-graph-rebuild-diff
stage: 14-graph-diff
agent_number: 14
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 3]
---

# Agent 14 — Graph Rebuild and Graph Diff

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Rebuild the static graph in full and assert that every structural change stayed inside the
authorized scope — before expensive validation runs.

## 2. Authority

### MAY

- Rebuild the graph completely from the migration workspace.
- Diff against `LAST_GOOD_GRAPH`.
- Distinguish an *expected consequence* (a file whose facts changed because a file the ledger changed is referenced by it) from a *scope violation*.
- Mark the graph `PARTIAL` when the edge does not compile.
- Advance `LAST_GOOD_GRAPH` on success.

### MUST NOT

- **MUST NOT** rebuild incrementally (ADR-005). A missed invalidation makes the scope gate pass something it should block.
- **MUST NOT** claim a full graph when the edge did not compile.
- **MUST NOT** treat a scope violation as a warning.

## 3. Preconditions

- `EDGE_COMPILED`.

## 4. Inputs

- The migration workspace.
- `LAST_GOOD_GRAPH`, `edge-plan.json`, the ledger events for this edge.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `application-graph-current.json` | `graph/application-graph.schema.json` |
| `graph-diff.json` | `graph/graph-diff.schema.json` |
| `scope-assertion.json` | — |
| `last-good-graph.json` | — |

All outputs are published under `output/14-graph-diff/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift migrate --edge <EDGE_ID>   # includes graph diff
```

## 7. Permitted adapters

- `analysis/JavaParserCodeModelAdapter`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- The rebuild is full, every time.
- A changed file is either authorized by the plan, changed via the ledger, an expected consequence, or a violation — there is no fifth category.
- `BS-GRAPH-PARTIAL` accompanies every `PARTIAL` graph.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| Scope violation detected | `POLICY_BLOCK` | `3` |
| The edge did not compile | `SUCCESS, graph `PARTIAL`, blind spot raised` | `0` |
| The rebuild fails entirely | `FAILURE` | `1` |

## 10. Downstream consumers

Agents 15, 16, 17, 19, 20.

## 11. Rules enforced

- **R13** — edge by edge.
- **R18** — static and runtime graphs are distinct.
