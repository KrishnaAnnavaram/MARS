# ADR-U005: Judgement steps are validated input artifacts, never invented by the harness

**Status:** Accepted
**Date:** 2026-09-24
**Deciders:** Unified harness architecture

## Context

Some VRH steps are judgement: the 04d research analysis (root-cause facts and hypotheses, threat,
candidate strategies, evaluation, recommendation). In VRH an agent writes `analysis.json` and a
script validates and reshapes it. A deterministic harness that "does research" itself would either
fabricate or hide an LLM behind a deterministic facade.

## Decision

Judgement arrives as an **input artifact** (`--research ISSUE=analysis.json`, or `submit-research`)
and is validated against the legacy contract, ported from 04d `generate-strategy.js`:

- at least two candidates for an established analysis
- a recommendation naming an existing candidate
- non-empty validation requirements
- confidence always Low, and never self-upgraded
- no cited sources when external research was not available

With no analysis, or an insufficient one, the plan is the legacy **evidence-gap** outcome
(`EVIDENCE_GAP`). A research plan is a strategy-only proposal: approving it authorizes producing a
concrete fix, which needs its own approval. A promotion candidate for the KB is written, never
promoted automatically.

LLM-authored patches follow the same rule: `submit-patch --provider llm` registers a proposal that
must carry model, prompt, context and response hashes. It needs a human approval and can never be
covered by execution-level authority.

## Consequences

- `VulnerabilityRoutesE2ETest` pins Low confidence, two candidates, strategy-only, no fabricated
  citation and the evidence-gap fallback.
- `HumanApprovalsE2ETest` shows an LLM patch without provenance is refused even after human approval,
  and that an LLM actor cannot approve.
