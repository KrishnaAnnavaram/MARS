---
name: 04-baseline
stage: 04-baseline
agent_number: 04
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 04 — Baseline

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Capture what the application does *before* anything changes, and seal it. Everything the rest of
the run classifies as a regression is classified against this.

## 2. Authority

### MAY

- Build every module, run the test suite with coverage, and start each runnable module.
- Select a JDK per module through `ToolchainProbe`, including working around known toolchain hazards.
- Re-run tests without coverage instrumentation when the agent cannot attach, recording coverage as unavailable with the reason.
- Capture bound-property provenance and enrich the graph with runtime edges.
- Record the environment fingerprint and the equivalence contract.
- Seal the baseline.

### MUST NOT

- **MUST NOT** modify the application to make the baseline look better — no disabling tests, no skipping modules, no relaxing assertions.
- **MUST NOT** report a module as started when it did not; `started=false` plus a blind spot is the correct output.
- **MUST NOT** overwrite a sealed baseline with a different hash (R23).
- **MUST NOT** run in the read-only `original/` workspace — baseline execution happens in `runtime-old/`.

## 3. Preconditions

- `APPLICATION_GRAPH_BUILT` and `GRAPH_VERIFIED`.

## 4. Inputs

- `build-model.json`, `application-graph.json`.
- The environment provider mode and any declared environment attributes.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `baseline-manifest.json` | `baseline/baseline-manifest.schema.json` |
| `baseline-build.json` | — |
| `baseline-tests.json` | — |
| `baseline-coverage.json` | — |
| `baseline-runtime.json` | — |
| `baseline-configuration.json` | — |
| `runtime-graph-baseline.json` | — |
| `application-graph-baseline-enriched.json` | — |
| `environment-equivalence.json` | — |

All outputs are published under `output/04-baseline/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift baseline [--skip-tests] [--skip-runtime]
```

## 7. Permitted adapters

- `build/MavenBuildAdapter`, `build/GradleBuildAdapter`, `build/ToolchainProbe`.
- `runtime/SpringProcessRuntimeProbe`.
- `environment/ManagedEnvironmentProvider`, `environment/DelegatedEnvironmentProvider`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- The sealed baseline hash is immutable for the run.
- `--skip-tests` and `--skip-runtime` are recorded as blind spots — a skipped observation is never an implied pass.
- Every runtime edge carries an `evidenceRef` to the observation that produced it.
- A test failing at baseline is what makes `PRE_EXISTING_FAILURE` a defensible classification later.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No module builds | `REFUSAL` | `2` |
| A module does not start | `SUCCESS with `BS-RUNTIME-<MODULE>`` | `0` |
| The baseline is already sealed with a different hash | `FAILURE` | `1` |

## 10. Downstream consumers

Agents 10, 15, 16, 17, 19. Nothing may mutate until this stage seals.

## 11. Rules enforced

- **R6** — no mutation before the baseline is sealed.
- **R23** — the sealed baseline is immutable.
- **R31** — capabilities are discovered dynamically.
