# ADR-U001: Bootshift is the kernel base, built unchanged and extended by composition

**Status:** Accepted
**Date:** 2026-09-24
**Deciders:** Unified harness architecture

## Context

The unified harness needs a persistent file identity, an immutable source snapshot, an isolated
workspace, a hash-chained change ledger, a checkpoint repository and a single mutation gateway.
Bootshift (`legacy-sources/bootshift`, commit `eacdca19`) already has all of these. Its ADRs
(ADR-001 to ADR-004) and 190 tests pin their behaviour down.

Three integration options were considered:

| Option | Risk to proven behaviour | Cost | Reversible |
|---|---|---|---|
| Rewrite the kernel from scratch | high: every invariant re-derived | high | no |
| Fork Bootshift classes into `kernel/` and edit them | medium: silent drift from the tested original | medium | hard |
| Build Bootshift unchanged in the reactor and compose around it | low | low | yes |

## Decision

Bootshift is a module of the Maven reactor, byte-for-byte as exported (`git archive`, recorded in
`legacy-sources/SOURCES.json`). The kernel depends on `bootshift-core`, `bootshift-adapters` and
`bootshift-stages` and never copies or subclasses their internals:

- `FILE_ID` stays Bootshift's (`FileRegistry`, ADR-001). The kernel adds MODULE, PROGRAM_UNIT, SYMBOL
  and STATEMENT identity *below* the file (ADR-U002).
- Stages 00 to 03 (bootstrap, inventory, build model, graph) run through Bootshift's own
  `StageExecutor` (`BootshiftBridge`).
- Every tracked-source write goes through Bootshift's `FileMutationGateway`, wrapped by the kernel
  `MutationGateway`, which adds proposal and approval authorization, a pre-batch checkpoint with
  atomic rollback, sub-file identity sync, lineage and bypass detection.
- The Bootshift `ChangeLedger` schema is unchanged. The kernel's lineage ledger and evidence log use
  the same hash-chain rule.

One pure function is duplicated on purpose: `kernel-core` `UnifiedDiff` copies Bootshift's
`FileMutationGateway.unifiedDiff`, so capability packs can render reviewable diffs without depending
on Bootshift's adapters module. A parity test pins the copy to the original.

Physically relocating Bootshift code into `kernel/` is deferred until parity is proven.

## Consequences

- Bootshift's 190 tests run in every reactor build and must stay green. They are the parity proof
  for the kernel base.
- An architecture test forbids any `com.bootshift..` class from depending on `com.mars..`, and forbids
  any class but the kernel `MutationGateway` from driving Bootshift's writer.
- A known Bootshift imprecision is documented, not patched: `Ids.ulid()` is time-ordered but not
  strictly monotonic within one millisecond, despite its class comment.
