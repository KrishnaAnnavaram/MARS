---
name: 18-approval
stage: 18-approval
agent_number: 18
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 3, 4]
---

# Agent 18 — Approval

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Collect the judgments a machine must not self-authorize, and refuse to proceed until a human
has made them.

## 2. Authority

### MAY

- Open an approval request per finding, bound to the evidence that produced it.
- Record an externally supplied, signed decision.
- Close a gate for exactly the findings the decision names.
- Return `NEEDS_HUMAN` and stop the run.

### MUST NOT

- **MUST NOT** approve anything itself (R9).
- **MUST NOT** accept a decision with an empty rationale or no actor — `ApprovalPort.record` throws.
- **MUST NOT** carry an approval from one edge or finding to another.
- **MUST NOT** treat the absence of a decision as approval.

## 3. Preconditions

- At least one validation stage has produced findings.

## 4. Inputs

- Findings from Agents 06, 08, 13, 15, 16, 17.
- Decisions supplied through `bootshift approve`.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `approval-report.json` | `evidence/approval-report.schema.json` |
| `approval-requests.json` | — |

All outputs are published under `output/18-approval/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift approve --request <REQ_ID> --actor <name> --role <role> \
    --verdict APPROVED --rationale "…"
```

## 7. Permitted adapters

- None. Decisions arrive from outside the harness.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- A decision is a signed artifact with actor, role, rationale, policy version, timestamp and signature.
- An approval is scoped to the findings it was raised for.
- Outstanding gates produce exit code 4 and stop the run.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| Gates open, no decisions | `NEEDS_HUMAN` | `4` |
| A decision is `REJECTED` | `BLOCKED` | `3` |
| A decision is unsigned or has no rationale | `FAILURE` | `1` |

## 10. Downstream consumers

Agents 15, 17 (re-classification), 19, 20.

## 11. Rules enforced

- **R9** — AI cannot authorize.
- **R25** — a human authorizes what the evidence cannot.
