---
name: 16-runtime-validation
stage: 16-runtime
agent_number: 16
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 4]
---

# Agent 16 — Runtime Validation

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Start the migrated application and observe it. Startup is one observation, not migration
success.

## 2. Authority

### MAY

- Package and start each module, deriving isolation settings from what each module *is*.
- Probe health, beans, conditions, environment, mappings and configuration properties.
- Capture per-property binding provenance: canonical key, source, target field, bound, defaulted, deprecated, sensitive.
- Cross the static configuration graph with the runtime bound set to detect `PROPERTY_SILENTLY_IGNORED`.
- Add runtime edges to the graph, each with an `evidenceRef`.

### MUST NOT

- **MUST NOT** treat successful startup as proof the migration is correct (R19).
- **MUST NOT** overwrite a static edge with a runtime edge — both layers coexist (R18).
- **MUST NOT** apply a blanket isolation setting that changes what a module is (a discovery server keeps its client beans).
- **MUST NOT** record a sensitive property's value (R30).

## 3. Preconditions

- `EDGE_TESTED`, and the frozen depth requires runtime.

## 4. Inputs

- The migration workspace, built.
- `baseline-runtime.json`, `baseline-configuration.json`.
- `application-graph-current.json` — the static configuration edges.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `runtime-report.json` | `validation/runtime-report.schema.json` |
| `configuration-binding.json` | — |
| `silently-ignored-properties.json` | — |
| `runtime-graph-current.json` | — |
| `application-graph-current-enriched.json` | — |

All outputs are published under `output/16-runtime/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift validate --edge <EDGE_ID>
```

## 7. Permitted adapters

- `runtime/SpringProcessRuntimeProbe`.
- `environment/ManagedEnvironmentProvider`, `environment/DelegatedEnvironmentProvider`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- A module that does not start yields `started=false`, a reason, every dimension listed unobservable, and `BS-RUNTIME-<MODULE>`.
- Every runtime edge type satisfies `EdgeType.isRuntimeObserved()`.
- A property configured and bound at baseline but not bound now is `PROPERTY_SILENTLY_IGNORED` — the failure mode that produces no error at all.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No module starts and depth requires runtime | `REFUSAL` | `2` |
| Some modules start | `SUCCESS with blind spots for the rest` | `0` |
| A required dimension is unobservable | `NEEDS_HUMAN (`EVIDENCE_SHORTFALL`)` | `4` |

## 10. Downstream consumers

Agents 17, 18, 19, 20.

## 11. Rules enforced

- **R18** — static and runtime graphs are distinct.
- **R19** — startup is one observation, not success.
- **R30** — sensitive values are metadata only.
