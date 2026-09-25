---
name: 13-build-repair
stage: 13-build-repair
agent_number: 13
determinism: DETERMINISTIC (AI OPTIONAL, DEFAULT OFF)
mutation_permission: VIA GATEWAY ONLY
exit_codes: [0, 1, 2, 3, 4]
---

# Agent 13 — Build and Repair

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Compile the edge, cluster the diagnostics by root cause, repair deterministically, and only then
— if policy allows — consider a bounded AI repair.

## 2. Authority

### MAY

- Invoke the build tool and parse diagnostics.
- Cluster diagnostics by probable root cause rather than repairing them one at a time.
- Apply deterministic repairs within the policy budget.
- Request an AI repair when deterministic repair is exhausted, AI is enabled, and the budget allows.
- Verify any proposed patch — scope, parse, compile, tests, budget — before it reaches the gateway.

### MUST NOT

- **MUST NOT** accept an AI patch that has not passed every deterministic gate (R9).
- **MUST NOT** exceed `ai_max_files_per_patch` or `ai_max_changed_lines_per_patch` without raising `BROAD_RESIDUAL_PATCH`.
- **MUST NOT** send repository content to a non-loopback endpoint.
- **MUST NOT** include secrets in a prompt (R30).
- **MUST NOT** suppress a diagnostic instead of fixing it.

## 3. Preconditions

- `EDGE_TRANSFORMED`.

## 4. Inputs

- The migration workspace after transformation.
- `edge-plan.json` — the authorized scope and the AI budget.
- `migration-knowledge.json` — facts usable as repair context.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `build-report.json` | — |
| `repair-report.json` | — |
| `diagnostics.json` | — |

All outputs are published under `output/13-build-repair/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift migrate --edge <EDGE_ID>   # includes build and repair
```

## 7. Permitted adapters

- `build/MavenBuildAdapter`, `build/GradleBuildAdapter`, `build/ToolchainProbe`.
- `ai/LocalOssAIProvider` — loopback only, optional.
- `mutation/FileMutationGateway`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Every AI attempt, accepted or rejected, is recorded (R12).
- An accepted AI patch sets `author: AI` on its ledger event, raises `HIGH_RISK_AI_PATCH`, and raises the edge's depth to `DIFFERENTIAL`.
- `AiBoundaryTest` (7 tests) fails the build if a non-loopback endpoint is accepted.
- Residual diagnostics that cannot be repaired block the edge; they are never downgraded to warnings.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| The edge compiles | `SUCCESS` | `0` |
| Diagnostics remain after the repair budget | `REFUSAL` | `2` |
| AI required, policy forbids it | `POLICY_BLOCK` | `3` |
| An AI patch is accepted | `SUCCESS; `HIGH_RISK_AI_PATCH` gate raised` | `0 then 4 at approval` |

## 10. Downstream consumers

Agents 14, 15, 18, 19, 20.

## 11. Rules enforced

- **R5** — build tools are authoritative about whether it compiles.
- **R9** — AI cannot authorize.
- **R10** — AI is optional.
- **R12** — every attempt is recorded.
