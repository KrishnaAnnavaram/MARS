---
name: 20-provenance-qa
stage: 20-provenance
agent_number: 20
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1]
---

# Agent 20 — Provenance Graph and Q&A

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Make every artifact answerable. Build the provenance graph linking changes to plans to facts to
impacts to decisions to observations, and answer questions by traversing it.

## 2. Authority

### MAY

- Build nodes from the ledger, the plans, the facts, the impacts, the decisions and the observations.
- Answer `explain change`, `explain impact` and `lineage` by traversing recorded edges.
- Publish the consolidated blind-spot and gap catalogs for the run.

### MUST NOT

- **MUST NOT** answer from a narrative. Every answer is a traversal of recorded edges.
- **MUST NOT** omit a change that has no authorizing plan node; it belongs in `gaps.json`.
- **MUST NOT** mutate anything.

## 3. Preconditions

- `EVIDENCE_SEALED`.

## 4. Inputs

- The change ledger, the plans, `migration-knowledge.json`, `impact-report.json`.
- `approval-report.json`, every validation report.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `provenance-graph.json` | — |
| `blind-spots.json` | — |
| `gaps.json` | — |
| `question-catalog.json` | — |

All outputs are published under `output/20-provenance/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift explain change <CHG_ID>
bootshift explain impact <IMPACT_ID>
bootshift lineage <FILE_ID>
bootshift blind-spots
bootshift gaps
```

## 7. Permitted adapters

- None.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- A change with empty `knowledge refs` has no justifying fact and appears in `gaps.json`; that catalog being empty is a property the harness asserts, not a hope.
- Every provenance answer terminates at an artifact hash, a ledger entry, or a signed decision.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| The ledger chain does not verify | `FAILURE` | `1` |
| Changes exist with no authorizing plan | `SUCCESS; they appear in `unexplained`` | `0` |

## 10. Downstream consumers

The human. This is the last stage.

## 11. Rules enforced

- **R12** — every attempt is recorded, and therefore answerable.
- **R23** — the artifact plane is the truth.
