---
name: 03-application-graph
stage: 03-graph
agent_number: 03
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 03 — Application Graph

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Build one typed graph of the application from the filesystem, the build model and the AST, and
project it into ten views. This graph is static only: it contains no runtime observations, by
construction.

## 2. Authority

### MAY

- Parse every Java compilation unit with a symbol solver configured from the *resolved dependency classpath*.
- Model annotation-processor-generated members (Lombok accessors, builders, loggers) as `synthetic`.
- Record an unresolved call recovered by name heuristics at reduced confidence with an explicit evidence code.
- Compute the structural hash, blast radius and per-view projections.
- Raise a gap when type attribution falls below the policy floor.

### MUST NOT

- **MUST NOT** emit a runtime edge type. Static and runtime layers stay distinct (R18).
- **MUST NOT** claim `confidence: 1.0` for a relation the symbol solver did not resolve.
- **MUST NOT** present a synthetic member as source-declared.
- **MUST NOT** include nodes for files outside the application under analysis.

## 3. Preconditions

- `BUILD_RESOLVED`.

## 4. Inputs

- `inventory-artifact.json`, `file-registry.json`.
- `build-model.json` and the resolved classpath from Agent 02.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `application-graph.json` | `graph/application-graph.schema.json` |
| `symbol-registry.json` | `symbol-registry/symbol-registry.schema.json` |
| `graph-summary.json` | — |
| `graph-verification-report.json` | — |
| `graph-issues.json` | — |

All outputs are published under `output/03-graph/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift graph
bootshift graph file <FILE_ID|path>
bootshift graph blast-radius <FILE_ID|path> --depth 3
bootshift graph symbol <fqn>
```

## 7. Permitted adapters

- `analysis/JavaParserCodeModelAdapter`.
- `build/MavenBuildAdapter` — classpath only.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- `EdgeType.isRuntimeObserved()` is false for every edge this stage emits.
- Every edge carries an `evidence` code and a `confidence`.
- The graph verification report cross-checks parsed compilation units against `FILE`-derived type declarations and reports any shortfall.
- `BS-GRAPH-RUNTIME` is raised on every run — this graph cannot see conditional activation.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No compilation unit parses | `REFUSAL` | `2` |
| Attribution below the policy floor | `SUCCESS with `GAP-GRAPH-001`` | `0` |
| Graph query integrity probe fails | `FAILURE` | `1` |

## 10. Downstream consumers

Agents 04, 09, 10, 11, 14, 16, 17, 19, 20.

## 11. Rules enforced

- **R18** — static and runtime graphs are distinct.
- **R31** — capabilities are discovered, never assumed.
