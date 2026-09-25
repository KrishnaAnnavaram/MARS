# ADR-U004: One CWE knowledge plane, loaded in place from the VRH sources

**Status:** Accepted
**Date:** 2026-09-24
**Deciders:** Unified harness architecture

## Context

VRH routes a finding through the 04a catalog (`cwe-patterns.json`), then the 04c remediation KB
(`remediation-kb.json` with `ranking-weights.json`), then 04d research. It scores verification with
07a `scoring.json`. The unified harness also needs dependency advisories (CWE-1104), which the VRH
catalog does not cover.

## Decision

- The catalog, KB, ranking weights and scoring are read **in place** from
  `legacy-sources/vulnerability-remediation-harness`, unchanged. Their paths are in the unified
  policy (`security.*`), so a VRH knowledge update needs no harness change.
- A single supplemental catalog entry for CWE-1104 is added in the capability. It carries its own
  per-entry provenance and never edits the VRH file.
- The routing order and its reason strings are ported verbatim (`CweRouter` from `detect-gap.js`,
  `KbStrategyDeriver` and `HybridRanker` from 04c, `MergeArbiter` from 07a). Parity tests run the
  legacy JavaScript against the Java port on the same inputs.

## Consequences

- Catalog, KB and research routes keep their business meaning (Scenarios 4 to 6).
- A finding with no CWE is UNCLASSIFIED and needs a human. It is never routed by guesswork.
