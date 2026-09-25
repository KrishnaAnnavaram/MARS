# ADR-U007: One mutable tree per run; builds and runtimes run in disposable copies

**Status:** Accepted
**Date:** 2026-09-24
**Deciders:** Unified harness architecture

## Context

Bootshift's run layout has an immutable `original/` snapshot and a mutable `migration/` workspace.
The migration reference says "the project directory is never edited" and builds in scratch copies.
Maven and a running application write `target/`, logs and databases. If those writes landed in the
tracked workspace, the bypass detector would flag them as untracked mutations, or worse, they would
be committed as part of a change.

## Decision

- `original/` is the sealed source snapshot. `migration/` (the kernel's "workspace") is the only
  mutable tree, and it is written only by the Mutation Gateway.
- Every build and runtime runs in `exec/<label>`: a fresh copy of the workspace made by
  `WorkspaceSandbox`. The copy is disposable; its logs are kept next to it as evidence.
- The customer's repository is read at analyze time and never written. Applying a result to it is a
  separate, decision-bound step (`APPLY_TO_PROJECT`, bound to the ledger head) that refuses stale
  or unverified state.
- `ArtifactStore` refuses to write into `original`, `migration`, `exec`, the checkpoint repository or
  Bootshift's output area.

## Consequences

- Every E2E test asserts the customer repository's tree hash is unchanged.
- `MutationBypassIT` shows that a direct write into the workspace is detected (post-batch and at final
  validation) and blocks the verdict.
