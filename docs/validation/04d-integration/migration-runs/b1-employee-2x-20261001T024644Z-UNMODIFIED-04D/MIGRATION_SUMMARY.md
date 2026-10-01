# Migration Summary — b1-employee-2x-20261001T024644Z

> **IN_PROGRESS** — stopped after BASELINE_BUILT — the migration has not been rendered; if the run ends here it is incomplete

_Generated automatically by 04d-version-migration from the session's evidence files at the end of every script invocation. Nothing here is hand-written._

## Run metadata

| Field | Value |
|---|---|
| Run ID | b1-employee-2x-20261001T024644Z |
| Generated | 2026-10-01T03:09:32.781Z |
| Started | 2026-10-01T02:46:44.870Z |
| Duration | 152 s |
| Source commit | 5b8008b09db2b302a8d03477b519b6a656998d8a |
| Working branch / worktree | HEAD @ E:/mv/A |
| Input location | E:/mv/A/src/employee-service |
| Output location | ../evidence/roundB/ctx/version-migration/b1-employee-2x |
| Source Java | 17 (JDK 17.0.20.1) |
| Target Java | 21 (JDK 21.0.11) |
| Source platform | Spring Boot 2.7.12 |
| Target platform | Spring Boot 4.1.1 |
| Selected migration pack | spring-boot-3-to-4 |
| Agent 04 issue | — |
| Approval mode | n/a — direct request, not routed by Agent 04 |
| Final status | IN_PROGRESS |
| Session state | BASELINE_BUILT (reached: BASELINE_DETECTED → WORKSPACE_PREPARED → BASELINE_BUILT) |

## Trigger

invoked directly, not by 04_fix-generator Stage 2

## Detection

| What | Value | Detected from |
|---|---|---|
| build tool | maven (wrapper) Apache Maven 3.8.7 (b89d5959fcde851dcb1c8946a785a163f14e1e29) | pom.xml |
| declared Java | 17 | pom.xml (java.version / compiler release) |
| source platform | 2.7.12 | org.springframework.boot:spring-boot-starter-parent (parent) |
| requested target platform | 4.1.1 | request |
| requested target Java | 21 | request |
| JDKs available | 17 (17.0.20.1), 21 (21.0.11) | local toolchain probe |

## Migration path

**Status:** `SUPPORTED` — 2.7.12 → 4.1.1

Warnings:

- source org.springframework.boot:spring-boot-starter-parent 2.7.12 is not on the 3.5.x preparation line the pack expects — a planning input, not an automatic upgrade

Unresolved:

- compatibility of org.springframework.cloud:spring-cloud-dependencies 2021.0.7 with Spring Boot 4.1.1 is not proven locally — The Spring Cloud release train must be one documented as compatible with the requested Boot 4 release. Check Spring Cloud's supported-versions table; unresolved until confirmed.

## Planned transformations

_No migration plan was recorded._

## Actual transformations

_none_

## File changes

| File | Change | Tool | Reason |
|---|---|---|---|
| .mvn/wrapper/maven-wrapper.jar | added | — | — |

## Dependency changes

_none_

## OpenRewrite

_none_

## Compile history

| Round | Label | Goal | JDK | Outcome | Errors | Declared platform | Diagnosis | Repair |
|---|---|---|---|---|---|---|---|---|
| R0 (baseline) | Pre-migration reference build | package | 17.0.20.1 | tests-failed | 9 | 2.7.12 | — | — |

## Tests

| Side | Round | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|---|
| Before | 0 | 7 | 5 | 0 | 2 | 0 |
| After | — | — | — | — | — | — |

New failures against round 0: **no**

## Runtime

| Check | Result |
|---|---|
| Baseline probed | no |
| Final probed | no |
| Readiness | — |
| Probes (final) | 0 |
| Runtime failures | none |

## Behaviour comparison

_Not compared — both a baseline and a final probe are required._

## Pipeline handoff

_Direct request — no Agent 04 handoff applies._

## Evidence

- Session: `../evidence/roundB/ctx/version-migration/b1-employee-2x`
- Report: _not rendered_
- Diff: _none_
- `../evidence/roundB/ctx/version-migration/b1-employee-2x/baseline.json`
- `../evidence/roundB/ctx/version-migration/b1-employee-2x/state.json`
- `../evidence/roundB/ctx/version-migration/b1-employee-2x/rounds`
