---
name: 19-evidence-report
stage: 19-evidence
agent_number: 19
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 19 — Evidence and Report

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Assemble the evidence manifest, assign an evidence level to every claim, state coverage per
dimension honestly, and produce the reports.

## 2. Authority

### MAY

- Content-address every artifact from every stage.
- Assign `levelReached` from supporting artifacts and compare it against `levelRequired`.
- Publish a claim only when `levelReached >= levelRequired`.
- Produce coverage statements for every dimension, covered or not.
- Re-hash every artifact to self-verify the manifest.

### MUST NOT

- **MUST NOT** publish a claim whose evidence does not reach the required level.
- **MUST NOT** omit an uncovered dimension from the coverage statement.
- **MUST NOT** let an `E0` or `E1` claim authorize anything.
- **MUST NOT** include a plaintext sensitive value anywhere in the report or bundle (R30).

## 3. Preconditions

- Every edge has reached `EDGE_COMPLETE`, or the run is terminating with findings.

## 4. Inputs

- Every artifact published by every stage in the run.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `evidence-manifest.json` | — |
| `claims.json` | — |
| `coverage-statement.json` | — |
| `migration-result.json` | `evidence/migration-result.schema.json` |
| `file-lineage.json` | — |
| `symbol-lineage.json` | — |
| `migration-report.md` | — |

All outputs are published under `output/19-evidence/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift report
bootshift evidence
bootshift verify
bootshift export
```

## 7. Permitted adapters

- `evidence/FilesystemEvidenceStore`.
- `scm/GitScmAdapter` — the export refuses when the tree hash differs from the sealed hash.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- `verify()` returns `VERIFIED`, `ARCHIVED` or `TAMPERED` — absence and alteration are distinct.
- Pruning never rewrites the manifest.
- `SchemaConformanceTest` asserts no plaintext secret reaches `output/`.
- The export emits a CycloneDX 1.5 SBOM.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| An artifact hash does not match | `FAILURE — `TAMPERED`` | `1` |
| A claim falls short of its level | `SUCCESS; `EVIDENCE_SHORTFALL` gate` | `0` |
| The tree hash differs from the sealed hash at export | `REFUSAL` | `2` |

## 10. Downstream consumers

Agent 20, and the human reading the report.

## 11. Rules enforced

- **R8** — documentation alone is insufficient.
- **R21** — what is not covered is stated.
- **R30** — sensitive values are metadata only.
