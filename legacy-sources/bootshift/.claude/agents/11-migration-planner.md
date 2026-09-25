---
name: 11-migration-planner
stage: 11-plan
agent_number: 11
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 3, 4]
---

# Agent 11 — Migration Planner

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Turn facts, impact and contracts into a frozen, per-edge plan: exactly which transformations
run, over exactly which files, validated to exactly which depth.

## 2. Authority

### MAY

- Compute each edge's validation depth as `MAX(class, residual, impact, policy)` and freeze it.
- Resolve, per edge, its own Java level and Spring Cloud train, omitting a transformation when no train exists for that Boot line.
- Scope each edge to a specific set of `FILE_ID`s.
- Compute deterministic coverage **per fact**, by asking whether some available capability claims that fact's subject, and record the residual with example subjects.
- Declare a composite transformation, with a reconciliation record and a `CHECKPOINT_COLLAPSE` gate.
- Freeze the plan.

### MUST NOT

- **MUST NOT** lower a frozen depth for any reason (R14).
- **MUST NOT** apply the landing target's train or Java level to a transit edge.
- **MUST NOT** include a transformation whose parameters it cannot resolve — omit and record instead of guessing.
- **MUST NOT** silently collapse a mandatory checkpoint (R17).
- **MUST NOT** count a fact as covered because a capability declares its fact *type*. A capability that rewrites JUnit 4 declares `API_REMOVED`; crediting it with every removed API in the ecosystem produced a coverage figure of 0.9985 for a run whose next edge failed to compile.
- **MUST NOT** scope an edge to files outside the impact set without recording why.

## 3. Preconditions

- `CHARACTERIZATION_COMPLETE`.

## 4. Inputs

- `migration-knowledge.json`, `impact-report.json`, `characterization-contracts.json`.
- `migration-path.json`, `lifecycle-registry.json`.
- `migration-rules/` including the generated property rules.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `migration-plan.json` | `migration-plan/migration-plan.schema.json` |
| `edge-plan.json` | `migration-plan/edge-plan.schema.json` |
| `residual-report.json` | — |
| `transformation-capability-registry.json` | — |

All outputs are published under `output/11-plan/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift plan
```

## 7. Permitted adapters

- `transform/OpenRewriteCoreProbe` — capability discovery and licence check only.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Each edge plan carries `spring_cloud_train` and its note, or an explicit `null` with the reason.
- The frozen depth is written before any mutation stage may run.
- `PREPARATORY` edges scope in every `JAVA_TEST` file id, so test rewrites are in scope where they belong.
- Deterministic coverage measures rule availability for the facts the harness knows about. It says nothing about whether the fact set is complete; that is reported separately by Agent 08's `api_diff` coverage. The artifact states this scope in `metric_scope`.
- Every recipe the planner schedules must be handled by a registered transformer. A scheduled recipe with no provider is recorded `NO_PROVIDER` and never applied, which is indistinguishable from the transformer not existing. `ControlsAreWiredTest` asserts the mapping.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No transformation exists for a required change | `REFUSAL` | `2` |
| Policy forbids the only available plan | `POLICY_BLOCK` | `3` |
| A composite collapses a mandatory checkpoint | `NEEDS_HUMAN` | `4` |

## 10. Downstream consumers

Agents 12–17. The plan is the authorization every mutation is checked against.

## 11. Rules enforced

- **R14** — validation depth is frozen.
- **R17** — mandatory checkpoints are not silently collapsed.
- **R25** — a human authorizes what the evidence cannot.
