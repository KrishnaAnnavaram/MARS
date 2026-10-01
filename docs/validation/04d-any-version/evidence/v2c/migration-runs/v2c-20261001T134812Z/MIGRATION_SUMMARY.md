# Migration Summary — v2c-20261001T134812Z

> **PASS** — green on the requested target, no new test failures, behaviour compared with no status changes

_Generated automatically by 04d-version-migration from the session's evidence files at the end of every script invocation. Nothing here is hand-written._

## Run metadata

| Field | Value |
|---|---|
| Run ID | v2c-20261001T134812Z |
| Generated | 2026-10-01T14:08:52.603Z |
| Started | 2026-10-01T13:48:12.328Z |
| Duration | 1240 s |
| Source commit | 08625161ca388fce117db2a6f9864f999275f519 |
| Working branch / worktree | master @ E:/mv/golden |
| Input location | E:/mv/golden |
| Output location | ../evidence/v3/v2c/ctx/version-migration/v2c |
| Source Java | 17 (JDK 17.0.20.1) |
| Target Java | 21 (JDK 21.0.11) |
| Source platform | Spring Boot 3.5.0 |
| Target platform | Spring Boot 4.1.1 |
| Selected migration pack | spring-boot-3-to-4 |
| Agent 04 issue | — |
| Approval mode | n/a — direct request, not routed by Agent 04 |
| Final status | PASS |
| Session state | RENDERED (reached: BASELINE_DETECTED → WORKSPACE_PREPARED → BASELINE_BUILT → BASELINE_PROBED → PLAN_READY → TRANSFORMATION_PREVIEWED → TRANSFORMATION_APPLIED → TARGET_COMPILED → TARGET_TESTED → FINAL_PROBED → EVIDENCE_READY → RENDERED) |

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
| E2 | 3.5.16 → 4.0.8 | MAJOR_BOUNDARY | oss-composite: openrewrite/spring-boot-3-to-4.oss.yml | Apache-2.0 | 17 | — | rewrite-02 previewed (5 files) | rewrite-03 applied (5 files) | R2 compile compile-failed; R3 test-compile passed | yes (R3) |
| E3 | 4.0.8 → 4.1.1 | MINOR (landing) | pin-only | Apache-2.0 | 21 | — | rewrite-04 previewed (1 files) | rewrite-05 applied (1 files) | R4 compile passed | yes (R4) |

Edge notes:

- E1: patch edge within 3.5: pins the platform only — a patch release carries no framework migration
- E3: no open-source-only upstream recipe for 4.1: the edge pins the platform and Java level; compiler-driven repair handles the rest

## Endpoint preservation

| Inventory | Before | After | Preserved | Missing | Added |
|---|---|---|---|---|---|
| Source mappings (static scan) | 11 | 11 | 11 | 0 | 0 |
| Running application (actuator /mappings) | — | — | — | — | — |

- /actuator/mappings not exposed or not probed — static inventory only

No endpoint observed before the migration is missing after it, in any inventory that was available.

## Planned transformations

- Impact areas: Platform parent, JSON handling, Runtime image (3 impact entries)
- Deterministic candidates: edge:E1, edge:E2, edge:E3
- Residual candidates: {"impact":"I9","why_not_deterministic":"No recipe in the pack changes a Dockerfile base image."}

## Actual transformations

| File | Origin | Evidence | What |
|---|---|---|---|
| pom.xml | openrewrite | — | Parent 3.5.0 -> 3.5.16 -> 4.0.8 -> 4.1.1, java.version 17 -> 21, starter renames, the two test-slice starters added. The three Testcontainers <version>1.20.1</version> pins are unchanged. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | harness-residual | Round 2 groups G2/G4/G5/G6/G7: JavaTimeModule, disable, setDefaultPropertyInclusion, jsr310 package, WRITE_DATES_AS_TIMESTAMPS. | The mutable Jackson 2 ObjectMapper bean became a JsonMapper built with JsonMapper.builder() (DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS disabled, NON_NULL inclusion, INDENT_OUTPUT disabled). This is the same reviewed edit V2 made. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | openrewrite | — | com.fasterxml.jackson.databind imports moved to tools.jackson.databind. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | openrewrite | — | Health and HealthIndicator moved to org.springframework.boot.health.contributor. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | openrewrite | — | @MockBean became @MockitoBean; the @WebMvcTest and ObjectMapper imports moved. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | openrewrite | — | @DataJpaTest import moved to org.springframework.boot.data.jpa.test.autoconfigure. |
| pom.xml | reference-rule | First final probe: GET /h2-console 404 (baseline 200). After the edit (round 6), the second final probe returned 200. | Added runtime-scope org.springframework.boot:spring-boot-h2console (managed). |
| Dockerfile | harness-residual | No recipe in the ladder changes a Dockerfile. The first attempt (round 7) rewrote the file's CRLF line endings; it was redone preserving them, and round 8 is the final state. | Base image eclipse-temurin:17-jre -> eclipse-temurin:21-jre. |

## File changes

| File | Change | Tool | Reason |
|---|---|---|---|
| Dockerfile | modified | harness-residual | The jar targets Java 21 (class file major 65). |
| pom.xml | modified | openrewrite + reference-rule | The ladder's three edges to the requested Boot 4.1.1 / Java 21 target. · spring.h2.console.enabled=true no longer serves /h2-console on Boot 4 without the module. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | modified | harness-residual + openrewrite | Jackson 3 mappers are immutable, WRITE_DATES_AS_TIMESTAMPS moved to DateTimeFeature, and java.time support is built in. A package move cannot fix any of these. · Jackson 3 package root. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | modified | openrewrite | Boot 4 relocated the health contributor types. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | modified | openrewrite | Boot 4 removed @MockBean and moved the web slice; Jackson 3. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | modified | openrewrite | Boot 4 moved the JPA slice. |

## Dependency changes

| Dependency | Old | New | Reason |
|---|---|---|---|
| org.springframework.boot:spring-boot-starter-parent (parent) | 3.5.0 | 4.1.1 | platform parent |
| org.springframework.boot:spring-boot-starter-webmvc | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-data-jpa-test | — | (managed) | added |
| org.springframework.boot:spring-boot-h2console | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-security-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-webmvc-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-web | (managed) | — | removed |
| org.springframework.security:spring-security-test | (managed) | — | removed |
| property java.version | 17 | 21 | build property |

## OpenRewrite

| # | Transformation | Mode | Status | Recipes | Files | Decision | Error |
|---|---|---|---|---|---|---|---|
| 0 | rewrite-00 | dry-run | previewed | mars.migration.edge.E1 | 1 | superseded | — |
| 1 | rewrite-01 | apply | applied | mars.migration.edge.E1 | 1 | accepted | — |
| 2 | rewrite-02 | dry-run | previewed | mars.migration.edge.E2 | 5 | superseded | — |
| 3 | rewrite-03 | apply | applied | mars.migration.edge.E2 | 5 | accepted | — |
| 4 | rewrite-04 | dry-run | previewed | mars.migration.edge.E3 | 1 | superseded | — |
| 5 | rewrite-05 | apply | applied | mars.migration.edge.E3 | 1 | accepted | — |

## Compile history

| Round | Label | Goal | JDK | Outcome | Errors | Declared platform | Diagnosis | Repair |
|---|---|---|---|---|---|---|---|---|
| R0 (baseline) | Pre-migration reference build | test | 17.0.20.1 | tests-failed | 7 | 3.5.0 | Pre-migration reference build on JDK 17, test goal: 19 tests, 2 failures. testActuatorHealth_Public and testActuatorInfo_Public expect 200 inside a @WebMvcTest slice, which loads no actuator endpoints, so they get 404. Pre-existing. |  |
| R1 | OpenRewrite rewrite-01 applied (mars.migration.edge.E1) | compile | 17.0.20.1 | passed | 0 | 3.5.16 | Edge E1 (3.5.0 -> 3.5.16, pin-only) compiles. The preview changed only the parent version, so the Testcontainers pins survive (V2 defect A fixed). | Applied rewrite-01 (previewed as rewrite-00). |
| R2 | OpenRewrite rewrite-03 applied (mars.migration.edge.E2) | compile | 17.0.20.1 | compile-failed | 10 | 4.0.8 | Edge E2 (3.5.16 -> 4.0.8, open-source composite). Only JacksonConfig fails, on the Jackson 2 mutable mapper API and the jsr310 module. | Applied rewrite-03 (previewed as rewrite-02): composite moves, starter renames, both test-slice starters added (V2 defect B fixed), parent 4.0.8. |
| R3 | Jackson 3 mapper repair (JacksonConfig, pack 2.1/2.2) | test-compile | 17.0.20.1 | passed | 0 | 4.0.8 | test-compile is green after the Jackson 3 mapper repair, with no hand-added test starters. The round was tagged E2 automatically from the declared platform, so the edge completed and E3 was offered (V2b defect fixed). | JacksonConfig rebuilt with JsonMapper.builder(). |
| R4 | OpenRewrite rewrite-05 applied (mars.migration.edge.E3) | compile | 21.0.11 | passed | 0 | 4.1.1 | Edge E3 (4.0.8 -> 4.1.1, pin-only) compiles on JDK 21. The edge recipe's UpgradeDependencyVersion found that the two composite-added test starters were pinned at the managed version and removed the tags, so they follow the 4.1.1 parent (V2b version-skew defect fixed). | Applied rewrite-05 (previewed as rewrite-04). |
| R5 | Landing 4.1.1 test suite | test | 21.0.11 | tests-failed | 7 | 4.1.1 | Landing test suite on JDK 21: 19 tests, the same 2 pre-existing failures. The first final probe then showed GET /h2-console 404 (baseline 200). |  |
| R6 | Restore /h2-console: spring-boot-h2console module (pack 7.1) | test | 21.0.11 | tests-failed | 7 | 4.1.1 | After adding spring-boot-h2console the suite is unchanged (19/2 pre-existing), and the second final probe shows /h2-console 200. | Added runtime spring-boot-h2console. |
| R7 | Final state: Dockerfile runtime image on Java 21 | test | 21.0.11 | tests-failed | 7 | 4.1.1 | Dockerfile base image moved to Java 21. The edit tool rewrote the file's line endings, so the change was redone in round 8. | Dockerfile eclipse-temurin:21-jre (line endings damaged). |
| R8 | Final state: Dockerfile edit redone preserving CRLF | test | 21.0.11 | tests-failed | 7 | 4.1.1 | Final source state: a one-line Dockerfile diff with CRLF preserved; 19 tests, the same 2 pre-existing failures. | Dockerfile re-edited preserving CRLF. |
| R9 | Final package on JDK 21 | package | 21.0.11 | tests-failed | 7 | 4.1.1 | Final package on JDK 21: the same 19 tests and 2 pre-existing failures, with no source change since round 8. |  |

## Tests

| Side | Round | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|---|
| Before | 0 | 19 | 17 | 2 | 0 | 0 |
| After | 9 | 19 | 17 | 2 | 0 | 0 |

New failures against round 0: **no**

## Runtime

| Check | Result |
|---|---|
| Baseline probed | yes (started: true) |
| Final probed | yes (started: true, 7.712 s) |
| Readiness | {"path":"/actuator/health","status":200,"ready_after_ms":9547} |
| Probes (final) | 9 |
| Runtime failures | none |

## Behaviour comparison

🟡 same status on all 9, body differs on 7

| Probes | Identical | Body differs | Status changed |
|---|---|---|---|
| 9 | 2 | 7 | 0 |

Expected differences:

- GET /api/v1/employees: non-deterministic
- high earners: non-deterministic
- unauthenticated is 401: expected-framework-change
- endpoint inventory (actuator): expected-framework-change
- GET /actuator/health: expected-framework-change
- GET /actuator/metrics: expected-framework-change
- GET /h2-console: expected-framework-change

Unexpected differences:

_none_

## Pipeline handoff

_Direct request — no Agent 04 handoff applies._

## Evidence

- Session: `../evidence/v3/v2c/ctx/version-migration/v2c`
- Report: `../evidence/v3/v2c/reports/migration_v2c.md`
- Diff: `../evidence/v3/v2c/reports/migration_v2c.diff`
- `../evidence/v3/v2c/ctx/version-migration/v2c/baseline.json`
- `../evidence/v3/v2c/ctx/version-migration/v2c/migration-plan.json`
- `../evidence/v3/v2c/ctx/version-migration/v2c/probes.json`
- `../evidence/v3/v2c/ctx/version-migration/v2c/migration.json`
- `../evidence/v3/v2c/ctx/version-migration/v2c/state.json`
- `../evidence/v3/v2c/ctx/version-migration/v2c/rounds`
- `../evidence/v3/v2c/ctx/version-migration/v2c/runtime`
- `../evidence/v3/v2c/ctx/version-migration/v2c/transformations`
