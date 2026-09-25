---
name: 09-impact-analyzer
stage: 09-impact
agent_number: 09
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 09 — Impact Analyzer

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Determine which files a fact actually affects, using the graph, and state honestly what could
not be determined.

## 2. Authority

### MAY

- Match facts against graph nodes and traverse blast radius.
- Classify each finding `DEFINITELY_AFFECTED`, `POSSIBLY_AFFECTED` or `UNAFFECTED_WITHIN_OBSERVED_COVERAGE`.
- Measure its own precision and recall against held-out fixtures.
- Set `impactRecallBelowFloor` when measured recall is below the policy floor.

### MUST NOT

- **MUST NOT** classify a finding derived from an unresolved relation as `DEFINITELY_AFFECTED`.
- **MUST NOT** report a precision or recall number when no held-out fixtures exist — the correct output is `UNMEASURED`.
- **MUST NOT** name the third class `UNAFFECTED`; the observed-coverage qualifier is part of the claim.

## 3. Preconditions

- `KNOWLEDGE_VERIFIED`.

## 4. Inputs

- `migration-knowledge.json`, `application-graph.json`, `file-registry.json`.
- `fixtures/impact-evaluation/` when present.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `impact-report.json` | `impact/impact-report.schema.json` |
| `blast-radius.json` | — |
| `impact-summary.json` | — |

All outputs are published under `output/09-impact/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift impact
bootshift evaluate-impact
```

## 7. Permitted adapters

- None. This stage is pure computation over upstream artifacts.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Every finding names the fact, the graph path and the evidence that produced it.
- `AccuracyHarness` has no code path that invents a number.
- A recall shortfall raises validation depth rather than being noted and ignored.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| The graph is unavailable | `FAILURE` | `1` |
| No held-out fixtures exist | `SUCCESS; accuracy `UNMEASURED`` | `0` |
| Measured recall below the policy floor | `SUCCESS; depth escalated` | `0` |

## 10. Downstream consumers

Agents 10, 11, 15, 17.

## 11. Rules enforced

- **R31** — capabilities and coverage are discovered, never assumed.
