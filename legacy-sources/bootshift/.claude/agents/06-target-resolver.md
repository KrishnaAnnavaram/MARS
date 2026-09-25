---
name: 06-target-resolver
stage: 06-target
agent_number: 06
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 3, 4]
---

# Agent 06 — Target Resolver

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Resolve the landing target — the **highest safe supported stable GA version** the evidence
permits — decompose the path into edges, and freeze the result.

## 2. Authority

### MAY

- Enumerate candidate lines and eliminate each with a stated, evidenced reason.
- Resolve, per candidate line, the Spring Cloud train by reading each train's declared `spring-boot-starter-parent` from its published POM.
- Decompose the path into `PREPARATORY`, `PATCH`, `MINOR`, `MAJOR`, `PLATFORM` and `ECOSYSTEM` edges.
- Assign each edge its own target state, Java level and Spring Cloud train.
- Freeze the target.

### MUST NOT

- **MUST NOT** select the newest version that exists; the target is the newest *safe supported stable GA* version (R16).
- **MUST NOT** select a milestone, RC or snapshot unless policy explicitly allows it.
- **MUST NOT** apply the landing target's Java level or Spring Cloud train to a transit edge.
- **MUST NOT** eliminate a candidate without recording the reason and the evidence quality behind it.
- **MUST NOT** land on an EOL line without an explicit policy allowance or a signed decision.

## 3. Preconditions

- `COMPATIBILITY_REGISTRY_READY`.

## 4. Inputs

- `lifecycle-registry.json`, `compatibility-registry.json`.
- `--target` (`auto` or an explicit version).
- Policy: `allow_milestone_targets`, `allow_eol_landing_target`, `minimum_support_horizon_months`.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `target-state.json` | `target/target-state.schema.json` |
| `migration-path.json` | — |
| `target-resolution-report.json` | — |

All outputs are published under `output/06-target/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift resolve-target --target auto
```

## 7. Permitted adapters

- `compat/MavenCentralVersionSpaceAdapter`.
- `http/HttpFetcher`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Every eliminated candidate has a reason code and an evidence grade.
- A line with no GA Spring Cloud train is eliminated as `NO_SPRING_CLOUD_TRAIN`, with the probe result as evidence.
- The frozen target is immutable for the run; changing it requires a new run.
- A `POLICY_BLOCK` names a concrete remedy, never just a failure.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No candidate satisfies policy | `POLICY_BLOCK` | `3` |
| The only viable target is EOL and policy forbids it | `POLICY_BLOCK` | `3` |
| The support horizon is short but non-negative | `NEEDS_HUMAN (`SHORT_HORIZON_TARGET`)` | `4` |

## 10. Downstream consumers

Agents 07, 08, 09, 10, 11, and every edge stage.

## 11. Rules enforced

- **R16** — auto-target is the highest safe supported stable GA version.
- **R25** — a human authorizes what the evidence cannot.
