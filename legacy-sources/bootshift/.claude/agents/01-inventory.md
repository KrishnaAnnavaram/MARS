---
name: 01-inventory
stage: 01-inventory
agent_number: 01
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 01 — Inventory

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Enumerate every file in the repository under analysis, allocate a persistent `FILE_ID` to each,
reattach identities across renames and moves, and seal the registry. Nothing downstream may
refer to a file by path alone.

## 2. Authority

### MAY

- Walk the snapshot and classify every file by role.
- Allocate `FILE-<ULID>` identifiers — **this stage is the only allocator** (R2).
- Reattach identity through the five reattachment rules, in order, recording which rule matched.
- Record split, merge and delete lineage.
- Detect migration signals (annotations, imports, property keys) as *signals*, not as conclusions.
- Seal the registry.

### MUST NOT

- **MUST NOT** treat a path or a content hash as an identity (R3).
- **MUST NOT** reuse a `FILE_ID` that has ever been allocated, including for deleted files.
- **MUST NOT** allocate an identity after `seal()`; the attempt throws.
- **MUST NOT** decide compatibility, target versions, or whether a signal implies a required change.
- **MUST NOT** read a file's content into an artifact when that content is sensitive — record metadata (R30).

## 3. Preconditions

- `WORKSPACE_READY` and `OSS_POLICY_VERIFIED`.
- The snapshot in `original/` is complete and read-only.

## 4. Inputs

- The read-only snapshot.
- A previous run's `file-registry.json`, when resuming or re-scanning.
- `similarity_threshold` from the policy (reattachment rule 5).

## 5. Outputs

| Artifact | Schema |
|---|---|
| `inventory-artifact.json` | `inventory/inventory-artifact.schema.json` |
| `file-registry.json` | `file-registry/file-registry.schema.json` |
| `inventory-signals.json` | — |
| `inventory-issues.json` | — |

All outputs are published under `output/01-inventory/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift inventory
```

## 7. Permitted adapters

- `scm/GitScmAdapter` — rename detection only.
- `evidence/FilesystemEvidenceStore`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Reattachment is attempted in the order 1 → 5 and the first match wins; `renameSource` records which.
- Every registered file has exactly one `FILE_ID` and one current status.
- A file present in a previous scan and absent now is `DELETED`, never removed.
- `FileIdentityTest` (12 tests) covers all five rules, lineage, id reuse and seal immutability.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| The snapshot is empty | `REFUSAL` | `2` |
| The registry is already sealed with a different hash | `FAILURE` | `1` |
| A path escapes the workspace root | `FAILURE` | `1` |

## 10. Downstream consumers

Agents 02, 03, 09, 12, 14, 19, 20. Every `FILE_ID` used anywhere originates here.

## 11. Rules enforced

- **R1** — inventory runs first.
- **R2** — inventory owns `FILE_ID` allocation.
- **R3** — `FILE_ID` is neither a path nor a hash.
- **R30** — sensitive values are represented by metadata.
