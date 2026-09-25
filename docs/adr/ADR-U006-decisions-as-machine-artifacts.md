# ADR-U006: Decisions are write-once machine artifacts; Markdown is a rendering

**Status:** Accepted
**Date:** 2026-09-24
**Deciders:** Unified harness architecture

## Context

VRH records approvals by a human editing the Status field of a Markdown fix plan. That is readable
but not machine-authoritative: nothing binds the approval to the exact diff that was reviewed, and a
later edit to the plan is indistinguishable from the original approval.

## Decision

A decision is a JSON artifact (`decisions/DEC-*.json`) that is:

- **write-once.** A new decision supersedes an old one; nothing is edited in place.
- **scoped by hash.** A proposal approval names the proposal ID and its SHA-256. An execution
  decision names the combined-assessment hash. A post-security decision names the refreshed
  assessment hash. Every decision names the baseline seal. A changed proposal, assessment or
  baseline makes the decision stale.
- **integrity-hashed** with an HMAC under a per-run key (`decisions/.integrity-key`). A tampered
  decision authorizes nothing.
- **human only.** Reserved machine identities (harness, llm, agent, copilot, claude, ci, …) are
  refused as actor or role. The actor is recorded as `LOCALLY_ASSERTED`: integrity is checkable,
  identity is not claimed to be authenticated.

The VRH-format `fix_plan_<ISSUE>.md` and the decision Markdown are rendered from these artifacts and
never read back as authority. An architecture test forbids anything but the `decide*` entry points
from recording a decision, and forbids report code from changing state.

## Consequences

- A missing decision is never approval: undecided proposals stay PENDING_APPROVAL in the verdict.
- `GatewayAuthorizationTest` shows a proposal edited on disk after approval is refused as a stale
  approval.
- HMAC with a key stored in the run is tamper evidence, not tamper proof against someone who can
  also rewrite the key. A signing service is the upgrade path.
