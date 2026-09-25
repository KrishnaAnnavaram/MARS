---
name: 00-pipeline-conductor
stage: 00-bootstrap
agent_number: 00
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2, 3, 4]
---

# Agent 00 — Pipeline Conductor

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Create the run, allocate the workspaces, verify the harness's own OSS licence posture, and
sequence the twenty agents. The conductor owns *ordering and state*, never *truth*: it never
decides whether a migration is safe, and it never answers a question a downstream stage is
responsible for answering.

## 2. Authority

### MAY

- Allocate the run id and create `original/`, `runtime-old/`, `migration/` and `runtime-new/` workspaces.
- Take the read-only snapshot of the repository under analysis.
- Run the OSS licence gate over the harness's own components before any other stage starts.
- Advance `RunState`, refuse illegal transitions, and resume a run from a recorded state.
- Stop the pipeline on the first non-success outcome and propagate its exit code unchanged.

### MUST NOT

- **MUST NOT** write to the repository under analysis. The snapshot is a copy; the source is input.
- **MUST NOT** add the repository under analysis to the harness's Maven reactor.
- **MUST NOT** re-interpret, soften, or override a downstream stage's outcome.
- **MUST NOT** enter a mutating state before `BASELINE_SEALED` has been recorded (R6).
- **MUST NOT** proceed if the OSS gate fails — a harness with an unverified dependency cannot make verifiable claims (R7).

## 3. Preconditions

- The repository under analysis exists and is readable.
- Git, a JDK and at least one supported build tool are on `PATH` or discoverable.
- A policy resolves — by name, by `--policy-file`, or the `production` default.

## 4. Inputs

- `--repo` — the application under analysis (default `./src`).
- The resolved policy document.
- An existing `RunState`, when `--run-id` is supplied.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `workspace-manifest.json` | — |
| `oss-gate-report.json` | — |
| `run-context.json` | — |
| `manifest.json` | `common/artifact-envelope.schema.json` |

All outputs are published under `output/00-bootstrap/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift run --target auto --policy production
bootshift stages
```

## 7. Permitted adapters

- `scm/GitScmAdapter` — snapshot only.
- `state/FilesystemRunStateStore`.
- `telemetry/StructuredTelemetryAdapter`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- The workspace root is outside the repository under analysis.
- `original/` is read-only for the life of the run.
- `StateMachine` refuses any transition into a state where `isMutating()` is true unless a baseline hash has been recorded.
- The OSS gate result is an artifact, not a log line.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| A workspace cannot be created | `FAILURE` | `1` |
| The repository under analysis is missing or unreadable | `FAILURE` | `1` |
| A harness component has an unknown or forbidden licence | `POLICY_BLOCK` | `3` |
| A downstream stage returns a non-success outcome | `propagated unchanged` | `1 / 2 / 3 / 4` |

## 10. Downstream consumers

Every stage. The conductor supplies `RunContext`, the workspace paths and the policy.

## 11. Rules enforced

- **R6** — no mutation before the baseline is sealed.
- **R7** — strict OSS, enforced before anything else runs.
- **R23** — the artifact plane is the truth; the state machine only says where the run is.
