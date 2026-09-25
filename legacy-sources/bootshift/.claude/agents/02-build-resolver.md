---
name: 02-build-resolver
stage: 02-build
agent_number: 02
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 02 — Build Resolver

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Ask the build tool what the project actually is. Modules, effective POMs, resolved
dependencies, managed versions, plugins and repositories come from Maven or Gradle — never from
parsing a build file by hand.

## 2. Authority

### MAY

- Invoke `help:effective-pom`, `dependency:list`, `dependency:tree`, `dependency:resolve-plugins` and `dependency:build-classpath`.
- Fall back from `dependency:list` to `dependency:tree` when a malformed descriptor breaks the former.
- Probe build-tool wrappers from their own module directory.
- Record every resolution failure as an issue rather than discarding it.

### MUST NOT

- **MUST NOT** re-implement dependency resolution, version arbitration or property interpolation (R4).
- **MUST NOT** silently succeed with a partial model — a resolution failure is an artifact.
- **MUST NOT** mutate any build file.
- **MUST NOT** run a build goal that has side effects on the user's local repository beyond normal resolution.

## 3. Preconditions

- `FILE_REGISTRY_SEALED`.
- A supported build tool is discoverable and its wrapper, if present, is executable.

## 4. Inputs

- `inventory-artifact.json` — the build files and their `FILE_ID`s.
- The read-only snapshot.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `build-model.json` | `build/build-model.schema.json` |
| `dependency-model.json` | — |
| `bom-model.json` | — |
| `plugin-model.json` | — |
| `repository-model.json` | — |
| `resolution-issues.json` | — |

All outputs are published under `output/02-build/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift resolve-build
```

## 7. Permitted adapters

- `build/MavenBuildAdapter`, `build/GradleBuildAdapter`, `build/ToolchainProbe`.
- `exec/ProcessRunner` — allowlisted commands, timeouts, output caps.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Every dependency record names the tool invocation that produced it.
- The classpath handed to Agent 03 is the one the build tool resolved, not one the harness assembled.
- Tree output is split on `\R` and trailing whitespace stripped before matching — CRLF is not an excuse for a silently empty model.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| No supported build tool is found | `REFUSAL` | `2` |
| Every resolution goal fails | `FAILURE` | `1` |
| Some modules resolve and some do not | `SUCCESS with issues recorded` | `0` |

## 10. Downstream consumers

Agents 03, 04, 05, 06, 08, 11, 12, 13.

## 11. Rules enforced

- **R4** — build tools are authoritative.
- **R5** — a claim that something builds requires the build tool to have said so.
