# ADR-007 — Recipes are applied one batch at a time, and a proposal carries its base hash

**Status:** Accepted
**Date:** 2026-09-10
**Supersedes:** nothing
**Related:** ADR-002 (the artifact plane is the source of truth)

---

## Context

Agent 12 turns a frozen edge plan into file changes. Transformers are pure functions: given a file's
content and a set of parameters they return the intended new content. They do not write — the
`FileMutationGateway` does, because it is the single writer (R11).

The original design ran every recipe for an edge, collected all proposals, and handed them to the
gateway as one batch. That reads well: the transformers stay pure, the gateway stays the only
writer, and the batch is checkpointed atomically.

It is wrong, and the reference corpus proved it.

Recipes are not independent. A minor or patch edge runs `maven.parent-version` **and**
`maven.managed-version`, and both target the same `pom.xml`. Each transformer read the file from
disk, and both ran before any write, so both computed their replacement content from the **pre-edge**
version of the file. The gateway then applied both. The second write contained none of the first
one's change.

The failure is not that a change was lost. It is that **the ledger recorded both as `APPLIED`**.

Observed on the corpus:

```
Edge EDGE-2-PATCH: 12 applied, 0 rejected, 0 failed
$ git show --stat
 6 files changed, 6 insertions(+), 6 deletions(-)
```

Six of twelve recorded changes were not in the tree. Every parent-version bump had been erased by a
managed-version bump to the same file. The project stayed on Spring Boot 2.7.12 while the ledger
said it had moved. The next edge then rewrote `javax.servlet` to `jakarta.servlet` on a project still
running Boot 2.7, and the compiler reported that `jakarta.servlet.http` does not exist.

A tamper-evident hash chain detects a record that was *altered*. It cannot detect a record that was
*correct when written and describes something that did not survive*. The chain verified perfectly
throughout.

## Decision

**Two changes, because either alone leaves a hole.**

### 1. One recipe per batch

Agent 12 applies each recipe's proposals through the gateway before running the next recipe. Every
transformer therefore reads the tree as the previous recipe left it.

### 2. Every proposal carries the hash of the content it was derived from

Transformers set `base_hash` on each `ProposedChange`. The gateway compares it against the file's
current hash and rejects a mismatch as `STALE_BASE_CONTENT`, recording the rejection in the ledger.

## Consequences

### Accepted

- **One commit per recipe instead of one per edge.** The git history is longer. Each commit names the
  recipe that produced it, which is better provenance than a single opaque per-edge commit, and the
  edge checkpoint tag still resolves to the edge's final state.
- **Slower.** A file touched by three recipes is read and written three times. This is a rounding
  error next to a compile or a test run.
- **The checkpoint is per batch, not per edge.** `revertTo` still takes the edge's entry checkpoint,
  so rollback semantics are unchanged.

### Gained

- The ledger and the tree cannot disagree about what was applied.
- A second recipe that would have overwritten the first is a **recorded rejection** rather than a
  silent loss — R12 applies to this failure mode too.
- The staleness check defends against any future caller that batches proposals, including one that
  has not been written yet. The invariant does not depend on Agent 12 continuing to behave.

### Rejected alternatives

**Merge proposals that target the same path.** A three-way merge of two independently-computed
versions of an XML document is exactly the kind of plausible-looking operation that produces a subtly
wrong file. Refusing is correct; merging is guessing.

**Order recipes so they never collide.** This is a property nobody can maintain. Every new recipe
would have to be checked against every existing one, and the failure mode when it is missed is
silent.

**Have transformers accept content instead of reading from disk.** Cleaner in principle, and it moves
the problem rather than solving it: the caller then has to track which version of the content to pass,
which is the same bug one layer up.

## Verification

`MutationBoundaryTest.staleProposalIsRejected` applies a change, then submits a second proposal
computed from the original content, and asserts the second is rejected with `STALE_BASE_CONTENT` and
that the first change survives. `freshProposalApplies` asserts the check does not reject ordinary
work.

End to end on the corpus after the change:

```
Edge EDGE-2-PATCH: 12 applied, 0 rejected, 0 failed
$ git log --oneline
3ab27b2 Edge EDGE-2-PATCH applied 6 change(s) via 12-transformation [maven.managed-version]
48caa82 Edge EDGE-2-PATCH applied 6 change(s) via 12-transformation [maven.parent-version]
$ grep -A3 '<parent>' report-service/pom.xml
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>
```

Twelve recorded changes, twelve changes in the tree, and both recipes' work present in the same file.
