# Migration Summary — issue-001-20261001T033403Z

> **PARTIAL PASS** — 1 open blocking condition(s) in migration.json: A human confirms API consumers accept the framework-owned response changes (401 default error timestamp 'Z' format, WWW-Authenticate charset parameter, malformed-JSON ProblemDetail without type, health document with livenessState/readinessState/groups)

_Generated automatically by 04d-version-migration from the session's evidence files at the end of every script invocation. Nothing here is hand-written._

## Run metadata

| Field | Value |
|---|---|
| Run ID | issue-001-20261001T033403Z |
| Generated | 2026-10-01T04:19:40.655Z |
| Started | 2026-10-01T03:34:03.024Z |
| Duration | 781 s |
| Source commit | 6d3a8053e4714e3a9d312eeb5aadebe209720e18 |
| Working branch / worktree | master @ E:/mv/R |
| Input location | E:/mv/R/src/spring-boot-migration-demo |
| Output location | .github/.pipeline-context/version-migration/issue-001 |
| Source Java | 17 (JDK 17.0.20.1) |
| Target Java | 21 (JDK 21.0.11) |
| Source platform | Spring Boot 3.5.0 |
| Target platform | Spring Boot 4.1.1 |
| Selected migration pack | spring-boot-3-to-4 |
| Agent 04 issue | ISSUE-001 |
| Approval mode | TEST_AUTO_APPROVED (APPROVAL_MODE, 2026-10-01T03:32:19.806Z) — Approval was programmatically granted for controlled pipeline validation; this does not replace the production human approval requirement. |
| Final status | PARTIAL PASS |
| Session state | RENDERED (reached: BASELINE_DETECTED → WORKSPACE_PREPARED → BASELINE_BUILT → BASELINE_PROBED → PLAN_READY → TRANSFORMATION_PREVIEWED → TRANSFORMATION_APPLIED → TARGET_COMPILED → TARGET_TESTED → FINAL_PROBED → EVIDENCE_READY → RENDERED) |

## Trigger

Routed by **04_fix-generator Stage 2** for `ISSUE-001` (plan `docs/agent_output/04-remediation/fix_plan_ISSUE-001.md`, Fix Type `VERSION_MIGRATION`). Routing evidence recorded in the plan:

- Stage 1 recorded explicit migration intent: Spring Boot 3.5.0 → 4.1.1, Java 17 → 21 — The issue requires the platform parent/BOM to cross a major generation (3.x -> 4.x) together with a Java level change (17 -> 21); the parent manages every Spring and third-party version and the Java level is declared in pom.xml (twice) and the Dockerfile, so no single-coordinate bump can make this move.
- src/spring-boot-migration-demo/pom.xml declares org.springframework.boot:spring-boot-starter-parent 3.5.0 as its parent — source platform proven
- src/spring-boot-migration-demo/pom.xml declares Java 17
- major framework generation changes: 3.x → 4.x
- Java target changes: 17 → 21

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
| 3.5.0 → 4.1.1 | spring-boot-3-to-4 | yes |

Pack eligibility:

| Pack | Nominated by | Eligible | Why |
|---|---|---|---|
| spring-boot-3-to-4 | org.springframework.boot:spring-boot-starter-parent:3, org.springframework.boot:spring-boot-starter-web | yes | source org.springframework.boot:spring-boot-starter-parent 3.5.0 (parent) is in the pack's 3.x generation |

## Planned transformations

- Impact areas: Platform parent, Language level, Starters, Pinned third-party test libraries, JSON handling, Actuator health, Test layer, Test layer (JPA slice), Spring Security 7, Hibernate 7 queries, Configuration properties, Runtime image, Web error handling (13 impact entries)
- Deterministic candidates: boot4-curated
- Residual candidates: {"impact":"I12","why_not_deterministic":"The pack has no recipe for a Dockerfile base image (section 15)."}; {"impact":"I2","why_not_deterministic":"UpgradeJavaVersion may move java.version but leave an explicit compiler <source>/<target>; pack section 15 lists that as residual."}; {"impact":"I4","why_not_deterministic":"Pinned Testcontainers versions are only changed if a build demands it; the curated recipe deliberately excludes the Testcontainers 2 migration."}; {"impact":"I5","why_not_deterministic":"A hand-built mapper bean whose configuration API changed shape is listed as residual in pack section 15 if the recipe leaves it uncompilable."}; {"impact":"I9","why_not_deterministic":"Only if Security 7 fails to compile; lambda DSL expected to need nothing."}; {"impact":"I10","why_not_deterministic":"Query failures only show at runtime/test time; no recipe can predict them."}; {"impact":"I13","why_not_deterministic":"Only if a Framework 7 signature change breaks the override at compile time."}

## Actual transformations

| File | Origin | Evidence | What |
|---|---|---|---|
| pom.xml | harness-residual | mvn dependency:tree -Dincludes=org.testcontainers on the sandbox after rewrite-01: org.testcontainers:testcontainers:2.0.5 with org.testcontainers:postgresql:1.20.1, jdbc:1.20.1, database-commons:1.20.1, junit-jupiter:1.20.1 (mixed generations). | Restored <version>1.20.1</version> on org.testcontainers:testcontainers after the recipe removed it (net: Testcontainers pins unchanged from the project). |
| pom.xml | openrewrite | — | Parent 3.5.0 -> 4.1.1, java.version 17 -> 21, compiler source/target replaced by release ${java.version}, spring-security-test replaced by spring-boot-starter-security-test, spring-boot-starter-webmvc-test and spring-boot-starter-data-jpa-test added. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | openrewrite | — | The hand-built Jackson 2 ObjectMapper bean became a Jackson 3 JsonMapper.builder() bean with NON_NULL value and content inclusion and INDENT_OUTPUT disabled; JavaTimeModule registration and the WRITE_DATES_AS_TIMESTAMPS disable were dropped. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | openrewrite | — | Health and HealthIndicator imports moved to org.springframework.boot.health.contributor. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | openrewrite | — | @MockBean became @MockitoBean, @WebMvcTest and ObjectMapper imports repointed to their Boot 4 / Jackson 3 packages; assertions unchanged. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | openrewrite | — | @DataJpaTest import repointed to org.springframework.boot.data.jpa.test.autoconfigure. |
| Dockerfile | openrewrite | — | Base image eclipse-temurin:17-jre -> eclipse-temurin:21-jre (comments unchanged). |

## File changes

| File | Change | Tool | Reason |
|---|---|---|---|
| Dockerfile | modified | openrewrite | The jar is now compiled for Java 21 and will not start on a Java 17 JRE. |
| pom.xml | modified | openrewrite | Boot 4 generation jump plus the modularised test starters. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | modified | openrewrite | Boot 4 ships Jackson 3 (tools.jackson), which has java.time support built in and writes dates as ISO-8601 by default; the mapper configuration API changed. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | modified | openrewrite | Boot 4 relocated the health contributor types. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | modified | openrewrite | @MockBean was removed in Boot 4; the MVC test slice and Jackson databind moved package. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | modified | openrewrite | The JPA test slice moved module and package in Boot 4. |

## Dependency changes

| Dependency | Old | New | Reason |
|---|---|---|---|
| org.springframework.boot:spring-boot-starter-parent (parent) | 3.5.0 | 4.1.1 | platform parent |
| org.springframework.boot:spring-boot-starter-data-jpa-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-security-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-webmvc-test | — | (managed) | added |
| org.springframework.security:spring-security-test | (managed) | — | removed |
| property java.version | 17 | 21 | build property |

## OpenRewrite

| # | Transformation | Mode | Status | Recipes | Files | Decision | Error |
|---|---|---|---|---|---|---|---|
| 0 | rewrite-00 | dry-run | previewed | mars.migration.SpringBoot3To4Curated | 0 | accepted | — |
| 1 | rewrite-01 | apply | applied | mars.migration.SpringBoot3To4Curated | 6 | accepted | — |

## Compile history

| Round | Label | Goal | JDK | Outcome | Errors | Declared platform | Diagnosis | Repair |
|---|---|---|---|---|---|---|---|---|
| R0 (baseline) | round 0: untouched project, Boot 3.5.0 on JDK 17 | package | 17.0.20.1 | tests-failed | 7 | 3.5.0 | The untouched project on JDK 17 compiles and packages up to the test phase; 19 tests ran, 17 passed. The two failures (EmployeeControllerTest actuator health/info expecting 200 but getting 404) are pre-existing: a @WebMvcTest slice does not register actuator endpoints. EmployeeRepositoryIntegrationTest ran for real against a Testcontainers postgres:16. This round is the reference: a later round 'passes' when it shows no new failures against these two. |  |
| R1 | boot4-curated OpenRewrite applied (ErrorResponse.java excluded) | test-compile | 21.0.11 | passed | 0 | 4.1.1 | The previewed boot4-curated recipe (rewrite-00 inspected, applied as rewrite-01 with ErrorResponse.java excluded) moved the parent, Java level, test starters, Jackson 3, health package, mocking annotation and slice imports in one step, so main and test code compiled on JDK 21 at the first attempt — none of the section 2/3/4 compile symptoms the plan predicted surfaced because the recipe pre-empted them. | Applied OpenRewrite boot4-curated (rewrite-01) to 6 files; ErrorResponse.java excluded and restored (would have removed the @JsonFormat error-timestamp pattern) |
| R2 | reverted rejected recipe hunk: Testcontainers core pin 1.20.1 restored; same goal as round 0 (package with tests) | package | 21.0.11 | tests-failed | 7 | 4.1.1 | Raised to the same goal as round 0 (package with tests). The result is identical to round 0: 19 run, the same 2 pre-existing actuator-slice failures, no new failures. The four @WithMockUser tests pass, confirming the spring-boot-starter-security-test swap covered the section 4.4 trap, and the 5 Testcontainers integration tests pass against postgres:16 with the 1.20.1 pins under the Boot 4.1.1 BOM (JUnit Jupiter 6.0.3). | Reverted the rejected rewrite-01 hunk: restored <version>1.20.1</version> on org.testcontainers:testcontainers (dependency:tree had shown testcontainers 2.0.5 mixed with 1.20.1 modules) |
| R3 | no source change; package the runnable jar on JDK 21 for the final probe | package-skip-tests | 21.0.11 | passed | 0 | 4.1.1 | Packaging goal with tests skipped, to produce the runnable Java 21 jar for the final runtime probe; the test comparison is carried by round 2. Green. |  |

## Tests

| Side | Round | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|---|
| Before | 0 | 19 | 17 | 2 | 0 | 0 |
| After | 2 | 19 | 17 | 2 | 0 | 0 |

New failures against round 0: **no**

## Runtime

| Check | Result |
|---|---|
| Baseline probed | yes (started: true) |
| Final probed | yes (started: true, 13.981 s) |
| Readiness | {"path":"/actuator/health","status":200,"ready_after_ms":16593} |
| Probes (final) | 19 |
| Runtime failures | none |

## Behaviour comparison

🟡 same status on all 19, body differs on 17

| Probes | Identical | Body differs | Status changed |
|---|---|---|---|
| 19 | 2 | 17 | 0 |

Expected differences:

- actuator health anonymous: expected-framework-change
- actuator metrics anonymous: expected-framework-change
- list employees unauthenticated is 401: expected-framework-change
- list employees bad credentials is 401: expected-framework-change
- list employees authenticated: non-deterministic
- get employee 1: non-deterministic
- get employee not found is 404: non-deterministic
- search by department: non-deterministic
- high earners native query: non-deterministic
- create employee: non-deterministic
- create employee without department omits null: non-deterministic
- create duplicate employee number is 409: non-deterministic
- create invalid is 400: non-deterministic
- malformed json is 400: expected-framework-change
- update employee 2: non-deterministic
- update missing employee is 404: non-deterministic
- get deleted employee is 404: non-deterministic

Unexpected differences:

_none_

## Pipeline handoff

| Question | Answer |
|---|---|
| Did 04D produce the Agent 04 standard output? | yes — `docs/agent_output/04-remediation/fix_ISSUE-001.md` (Status Compiled), `docs/agent_output/04-remediation/fix_ISSUE-001.diff` |
| Did Agent 05 consume it? | yes — 05-verify/rescan_ISSUE-001.md, 05-verify/redteam_ISSUE-001.md, 05-verify/behavior_ISSUE-001.md |
| Did Agent 06 consume it? | yes — 06-test-gate/qa_ISSUE-001.md (Passed), 06-test-gate/build_ISSUE-001.md (Failed) |
| Did Agent 07 consume it? | yes — 07-ship/verdict_ISSUE-001.md, 07-ship/pr_ISSUE-001.md, 07-ship/audit_ISSUE-001.md (Decision: Blocked) |

_Downstream answers reflect the files present when this summary was last regenerated; `node scripts/finalize-run.js --issue <ID>` refreshes them._

## Evidence

- Session: `.github/.pipeline-context/version-migration/issue-001`
- Report: `docs/agent_output/04-remediation/migration_issue-001.md`
- Diff: `docs/agent_output/04-remediation/migration_issue-001.diff`
- `.github/.pipeline-context/version-migration/issue-001/baseline.json`
- `.github/.pipeline-context/version-migration/issue-001/migration-plan.json`
- `.github/.pipeline-context/version-migration/issue-001/probes.json`
- `.github/.pipeline-context/version-migration/issue-001/migration.json`
- `.github/.pipeline-context/version-migration/issue-001/state.json`
- `.github/.pipeline-context/version-migration/issue-001/rounds`
- `.github/.pipeline-context/version-migration/issue-001/runtime`
- `.github/.pipeline-context/version-migration/issue-001/transformations`
