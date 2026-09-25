# ADR-001: Persistent, allocated file identity

**Status:** Accepted
**Date:** 2026-09-10
**Deciders:** Bootshift architecture

## Context

Every downstream claim in this harness is anchored to a file. "This file changed because of that
migration fact" is only meaningful if *this file* means the same thing at the start of the run and
at the end of it — after edits, renames, package moves, splits and merges.

Three candidate identities were considered:

| Candidate | Survives edit | Survives rename | Survives both | Recoverable from disk alone |
|---|---|---|---|---|
| Repository path | yes | **no** | no | yes |
| Content hash | **no** | yes | no | yes |
| Allocated identifier | yes | yes | yes | **no** |

A migration is precisely the operation that changes content *and* moves files. Path identity breaks
the moment `SecurityConfig.java` becomes `ApplicationSecurityConfiguration.java`. Content-hash
identity breaks on the first edit. Neither survives the combination, which is the normal case.

## Decision

File identity is an **allocated, persisted, opaque identifier** (`FILE-<ULID>`), held in the File
Registry, and the following invariant is enforced:

```text
FILE_ID != PATH
FILE_ID != CONTENT_HASH
PATH    != CONTENT_HASH
```

The registry stores path and content hash as *attributes* of an identity rather than as the identity
itself, so all three can change independently while the identity persists.

Agent 01 (Inventory) is the sole allocator. No later stage may invent or replace an identity.

### Reattachment

On a re-scan, an observed file is matched to an existing identity in this fixed order:

1. exact current path
2. provider-reported or Git-reported rename mapping
3. exact content hash, restricted to identities whose recorded path is no longer present
4. token-shingle similarity at or above the configured threshold, restricted to the same file role
   and to identities whose recorded path is no longer present
5. otherwise, allocate a new identity

Steps 3 and 4 are restricted to identities whose old path has disappeared from the current scan.
Without that restriction, two files with identical content would fight over one identity.

Every reattachment records how it was decided (`rename_source`) and how confident it is
(`rename_confidence`). A similarity match at 0.74 and a provider-reported rename are both renames,
but they are not equally trustworthy, and the artifacts say so.

### Split, merge, delete

- **Split:** the highest-containment descendant keeps the identity; new siblings get new identities
  and record `split_from`.
- **Merge:** the target keeps its identity; sources become `merged_into` and stay queryable.
- **Delete:** identity is never erased. Status becomes `DELETED` and lineage remains intact.

## Consequences

### The trade-off we are accepting

**Identity recovery depends on the integrity of the File Registry.** A derived identity (path or
hash) can always be recomputed from a checkout. An allocated identity cannot. If the registry is
lost, the lineage of the run is lost with it, and the harness cannot honestly claim to know which
final file corresponds to which baseline file.

This is a real cost and we accept it deliberately, because the alternative is worse: a derived
identity that silently breaks during exactly the operation the harness exists to perform.

### Mitigations

- The registry is sealed after inventory (`FILE_REGISTRY_SEALED`) and its seal hash is bound into the
  baseline manifest, so a substituted registry is detectable.
- The registry is written with pointer-after-write semantics, so a failed run cannot leave a partial
  registry as the current one.
- The live registry is persisted in the run workspace after every mutating stage, not only at the
  end, so a crash costs at most one stage.
- The registry is a first-class evidence object, content-addressed in the evidence store.

### What this enables

- `bootshift lineage <FILE_ID>` shows the complete history from baseline path and hash through
  every change to the final path and hash.
- The Change Ledger can reference a stable identity rather than a moving path.
- Graph Diff can attribute a structural change to a file even after that file was renamed.
