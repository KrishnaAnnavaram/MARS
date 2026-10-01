# Migration Summary — v2b-20261001T133255Z

> **IN_PROGRESS** — stopped after FINAL_PROBED — the migration has not been rendered; if the run ends here it is incomplete

_Generated automatically by 04d-version-migration from the session's evidence files at the end of every script invocation. Nothing here is hand-written._

## Run metadata

| Field | Value |
|---|---|
| Run ID | v2b-20261001T133255Z |
| Generated | 2026-10-01T13:44:23.768Z |
| Started | 2026-10-01T13:32:55.853Z |
| Duration | 675 s |
| Source commit | 08625161ca388fce117db2a6f9864f999275f519 |
| Working branch / worktree | master @ E:/mv/golden |
| Input location | E:/mv/golden |
| Output location | ../evidence/v3/v2b/ctx/version-migration/v2b |
| Source Java | 17 (JDK 17.0.20.1) |
| Target Java | 21 (JDK 21.0.11) |
| Source platform | Spring Boot 3.5.0 |
| Target platform | Spring Boot 4.1.1 |
| Selected migration pack | spring-boot-3-to-4 |
| Agent 04 issue | — |
| Approval mode | n/a — direct request, not routed by Agent 04 |
| Final status | IN_PROGRESS |
| Session state | FINAL_PROBED (reached: BASELINE_DETECTED → WORKSPACE_PREPARED → BASELINE_BUILT → BASELINE_PROBED → PLAN_READY → TRANSFORMATION_PREVIEWED → TRANSFORMATION_APPLIED → TARGET_COMPILED → FINAL_PROBED) |

## Trigger

invoked directly, not by 04_fix-generator Stage 2

## Detection

| What | Value | Detected from |
|---|---|---|
| build tool | maven (env) Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937) | pom.xml |
| declared Java | 17 | pom.xml (java.version / compiler release) |
| source platform | 3.5.0 | org.springframework.boot:spring-boot-starter-parent (parent) |
| requested target platform | 4.1.1 | request |
| requested target Java | 21 | request |
| JDKs available | 17 (17.0.20.1), 21 (21.0.11) | local toolchain probe |

## Migration path

**Status:** `SUPPORTED` — 3.5.0 → 4.1.1

| Step | Capability | Available |
|---|---|---|
| 3.5.0 → 3.5.16 | pin-only (core-apache-2.0) | yes |
| 3.5.16 → 4.0.8 | openrewrite/spring-boot-3-to-4.oss.yml | yes |
| 4.0.8 → 4.1.1 | pin-only (core-apache-2.0) | yes |

Pack eligibility:

| Pack | Nominated by | Eligible | Why |
|---|---|---|---|
| spring-boot-3-to-4 | org.springframework.boot:spring-boot-starter-parent:3, org.springframework.boot:spring-boot-starter-web | yes | source org.springframework.boot:spring-boot-starter-parent 3.5.0 (parent) is in the pack's 3.x generation |

## Migration path (edges)

Ladder `references/openrewrite/spring-boot-ladder.json` · licence policy **open-source-only** · granularity boundary · versions from maven-metadata https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml

| Edge | From → To | Class | Recipe | Licence | JDK | Cloud train | Previews | Applies | Rounds | Complete |
|---|---|---|---|---|---|---|---|---|---|---|
| E1 | 3.5.0 → 3.5.16 | PATCH | pin-only | Apache-2.0 | 17 | — | rewrite-00 previewed (1 files) | rewrite-01 applied (1 files) | R1 compile passed | yes (R1) |
| E2 | 3.5.16 → 4.0.8 | MAJOR_BOUNDARY | oss-composite: openrewrite/spring-boot-3-to-4.oss.yml | Apache-2.0 | 17 | — | rewrite-02 previewed (5 files) | rewrite-03 applied (5 files) | R2 compile compile-failed; R4 test-compile passed | yes (R4) |
| E3 | 4.0.8 → 4.1.1 | MINOR (landing) | pin-only | Apache-2.0 | 21 | — | rewrite-04 previewed (1 files); rewrite-05 previewed (1 files) | rewrite-06 applied (1 files) | R5 compile passed | yes (R5) |

Edge notes:

- E1: patch edge within 3.5: pins the platform only — a patch release carries no framework migration
- E3: no open-source-only upstream recipe for 4.1: the edge pins the platform and Java level; compiler-driven repair handles the rest

## Endpoint preservation

| Inventory | Before | After | Preserved | Missing | Added |
|---|---|---|---|---|---|
| Source mappings (static scan) | 7 | 7 | 7 | 0 | 0 |
| Running application (actuator /mappings) | — | — | — | — | — |

- /actuator/mappings not exposed or not probed — static inventory only

No endpoint observed before the migration is missing after it, in any inventory that was available.

## Planned transformations

- Impact areas: Platform parent, JSON handling, Runtime image (3 impact entries)
- Deterministic candidates: edge:E1, edge:E2, edge:E3
- Residual candidates: {"impact":"I9","why_not_deterministic":"No recipe in the pack changes a Dockerfile base image."}

## Actual transformations

_none_

## File changes

| File | Change | Tool | Reason |
|---|---|---|---|
| pom.xml | modified | — | — |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | modified | — | — |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | modified | — | — |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | modified | — | — |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | modified | — | — |

## Dependency changes

| Dependency | Old | New | Reason |
|---|---|---|---|
| org.springframework.boot:spring-boot-starter-parent (parent) | 3.5.0 | 4.1.1 | platform parent |
| org.springframework.boot:spring-boot-starter-webmvc | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-data-jpa-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-security-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-webmvc-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-web | (managed) | — | removed |
| org.springframework.security:spring-security-test | (managed) | — | removed |
| property java.version | 17 | 21 | build property |

## OpenRewrite

| # | Transformation | Mode | Status | Recipes | Files | Decision | Error |
|---|---|---|---|---|---|---|---|
| 0 | rewrite-00 | dry-run | previewed | mars.migration.edge.E1 | 1 | — | — |
| 1 | rewrite-01 | apply | applied | mars.migration.edge.E1 | 1 | — | — |
| 2 | rewrite-02 | dry-run | previewed | mars.migration.edge.E2 | 5 | — | — |
| 3 | rewrite-03 | apply | applied | mars.migration.edge.E2 | 5 | — | — |
| 4 | rewrite-04 | dry-run | previewed | mars.migration.edge.E3 | 1 | — | — |
| 5 | rewrite-05 | dry-run | previewed | mars.migration.edge.E3 | 1 | — | — |
| 6 | rewrite-06 | apply | applied | mars.migration.edge.E3 | 1 | — | — |

## Compile history

| Round | Label | Goal | JDK | Outcome | Errors | Declared platform | Diagnosis | Repair |
|---|---|---|---|---|---|---|---|---|
| R0 (baseline) | Pre-migration reference build | compile | 17.0.20.1 | passed | 0 | 3.5.0 | — | — |
| R1 | OpenRewrite rewrite-01 applied (mars.migration.edge.E1) | compile | 17.0.20.1 | passed | 0 | 3.5.16 | — | — |
| R2 | OpenRewrite rewrite-03 applied (mars.migration.edge.E2) | compile | 17.0.20.1 | compile-failed | 10 | 4.0.8 | — | — |
| R3 | Jackson 3 JacksonConfig repair (same reviewed edit as V2) | test-compile | 17.0.20.1 | passed | 0 | 4.0.8 | — | — |
| R4 | E2 residual repair re-check (edge inferred) | test-compile | 17.0.20.1 | passed | 0 | 4.0.8 | — | — |
| R5 | OpenRewrite rewrite-06 applied (mars.migration.edge.E3) | compile | 21.0.11 | passed | 0 | 4.1.1 | — | — |
| R6 | Landing 4.1.1 test suite | test | 21.0.11 | tests-failed | 7 | 4.1.1 | — | — |

## Tests

| Side | Round | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|---|
| Before | 6 | 19 | 17 | 2 | 0 | 0 |
| After | — | — | — | — | — | — |

New failures against round 0: **no**

## Runtime

| Check | Result |
|---|---|
| Baseline probed | yes (started: true) |
| Final probed | yes (started: true, 7.606 s) |
| Readiness | {"path":"/actuator/health","status":200,"ready_after_ms":9503} |
| Probes (final) | 4 |
| Runtime failures | none |

## Behaviour comparison

🟡 same status on all 4, body differs on 3

| Probes | Identical | Body differs | Status changed |
|---|---|---|---|
| 4 | 1 | 3 | 0 |

Expected differences:

_none_

Unexpected differences:

_none_

## Pipeline handoff

_Direct request — no Agent 04 handoff applies._

## Evidence

- Session: `../evidence/v3/v2b/ctx/version-migration/v2b`
- Report: _not rendered_
- Diff: _none_
- `../evidence/v3/v2b/ctx/version-migration/v2b/baseline.json`
- `../evidence/v3/v2b/ctx/version-migration/v2b/migration-plan.json`
- `../evidence/v3/v2b/ctx/version-migration/v2b/probes.json`
- `../evidence/v3/v2b/ctx/version-migration/v2b/state.json`
- `../evidence/v3/v2b/ctx/version-migration/v2b/rounds`
- `../evidence/v3/v2b/ctx/version-migration/v2b/runtime`
- `../evidence/v3/v2b/ctx/version-migration/v2b/transformations`
