# Migration Summary — b2-golden-20261001T025104Z

> **PASS** — green on the requested target, no new test failures, behaviour compared with no status changes

_Generated automatically by 04d-version-migration from the session's evidence files at the end of every script invocation. Nothing here is hand-written._

## Run metadata

| Field | Value |
|---|---|
| Run ID | b2-golden-20261001T025104Z |
| Generated | 2026-10-01T03:09:32.362Z |
| Started | 2026-10-01T02:51:04.944Z |
| Duration | 866 s |
| Source commit | 08625161ca388fce117db2a6f9864f999275f519 |
| Working branch / worktree | master @ E:/mv/golden |
| Input location | E:/mv/golden |
| Output location | ../evidence/roundB/ctx/version-migration/b2-golden |
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

## Planned transformations

- Impact areas: Platform parent, Language level, Starter rename, Test starters, Security test starter, JSON handling, Actuator health, Test layer (MVC slice), Test layer (JPA slice + Testcontainers), Runtime image, Configuration properties, Persistence (Hibernate 7), Security 7 (13 impact entries)
- Deterministic candidates: boot4-curated
- Residual candidates: {"impact":"I10","why_not_deterministic":"No recipe in the pack changes a Dockerfile base image (section 15)."}; {"impact":"I2","why_not_deterministic":"UpgradeJavaVersion may not normalise the explicit maven-compiler-plugin <source>/<target>; fixed by hand only if the preview or a round shows it stayed at 17."}; {"impact":"I6","why_not_deterministic":"A hand-built mapper bean whose configuration API changed shape may be beyond the recipe (section 15); fixed only if a round names it."}; {"impact":"I12","why_not_deterministic":"Hibernate 7 query parsing issues surface only in tests or at runtime; nothing to change unless they fail."}; {"impact":"I13","why_not_deterministic":"Lambda DSL expected to compile unchanged; only a Security 7 compile error would justify an edit."}

## Actual transformations

| File | Origin | Evidence | What |
|---|---|---|---|
| pom.xml | openrewrite | — | Parent 4.1.1, java.version 21, compiler release, security-test starter, webmvc-test and data-jpa-test starters. |
| pom.xml | harness-residual | Preview rewrite-00.patch removes the pin. spring-boot-dependencies-4.1.1.pom sets testcontainers.version 2.0.5. Plan out_of_scope: no Testcontainers version change. The rejected hunk shared pom.xml with wanted hunks, so it was reverted by hand before round 2. | Restored <version>1.20.1</version> on org.testcontainers:testcontainers after the apply removed it. |
| pom.xml | build-file | Plan impact I3 is a verified pack rule that the recipe did not apply: rewrite-01 left the web starter in place. spring-boot-starter-web-4.1.1.pom describes itself as 'deprecated in favor of spring-boot-starter-webmvc'. spring-boot-starter-webmvc 4.1.1 resolves, and the dependency tree after round 2 shows the same jackson/tomcat/http-converter/webmvc modules. No build failure demanded it, because the deprecated starter still resolves in 4.1.1. | spring-boot-starter-web renamed to spring-boot-starter-webmvc. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | openrewrite | — | The hand-built Jackson 2 ObjectMapper bean became a tools.jackson ObjectMapper built with JsonMapper.builder(), with NON_NULL value and content inclusion and INDENT_OUTPUT disabled. The JavaTimeModule registration and the WRITE_DATES_AS_TIMESTAMPS disable were dropped as Jackson 3 defaults. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | openrewrite | — | Health/HealthIndicator imports moved to org.springframework.boot.health.contributor. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | openrewrite | — | @MockBean became @MockitoBean, @WebMvcTest was imported from org.springframework.boot.webmvc.test.autoconfigure, and ObjectMapper from tools.jackson.databind. The slice was kept: there is no widening to @SpringBootTest. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | openrewrite | — | @DataJpaTest imported from org.springframework.boot.data.jpa.test.autoconfigure. |
| Dockerfile | openrewrite | — | Base image eclipse-temurin:17-jre to eclipse-temurin:21-jre. |

## File changes

| File | Change | Tool | Reason |
|---|---|---|---|
| Dockerfile | modified | openrewrite | The jar now targets Java 21. |
| pom.xml | modified | build-file | Boot 4 names the servlet starter after its stack, and the old name is deprecated. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | modified | openrewrite | Boot 4 ships Jackson 3, which moves the databind package and replaces the mapper configuration API. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | modified | openrewrite | Boot 4 relocated the health contributor types. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | modified | openrewrite | @MockBean was removed, the slice moved package, and Jackson 3 replaced Jackson 2. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | modified | openrewrite | The JPA slice moved module and package. |

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
| 0 | rewrite-00 | dry-run | previewed | mars.migration.SpringBoot3To4Curated | 0 | superseded | — |
| 1 | rewrite-01 | apply | applied | mars.migration.SpringBoot3To4Curated | 6 | accepted | — |

## Compile history

| Round | Label | Goal | JDK | Outcome | Errors | Declared platform | Diagnosis | Repair |
|---|---|---|---|---|---|---|---|---|
| R0 (baseline) | Pre-migration reference build | package | 17.0.20.1 | tests-failed | 7 | 3.5.0 | Pre-migration reference on JDK 17 with Boot 3.5.0. The Docker daemon was up, so Testcontainers ran: 19 tests run, 0 errors. The only failures are EmployeeControllerTest.testActuatorHealth_Public and testActuatorInfo_Public. They return 404 because a @WebMvcTest slice does not load the actuator endpoints. Both are pre-existing, they were not fixed, and they are the comparison point for the target rounds. |  |
| R1 | Applied boot4-curated (preview rewrite-00) with ErrorResponse.java excluded | test-compile | 21.0.11 | passed | 0 | 4.1.1 | This round built the OpenRewrite apply straight away. Main and test code compile on JDK 21 at release 21 against Boot 4.1.1 (Framework 7, Security 7, Hibernate 7, Jackson 3), so the recipe left no compile-level residual. The parent, java.version and compiler release resolved, which proves constraints C2 and C3. The one rejected hunk (the Testcontainers pin removal) was still in the tree for this round. It cannot affect a test-compile, and it was reverted before round 2. | Applied rewrite-01 (boot4-curated, previewed as rewrite-00) with src/main/java/com/example/migrationdemo/exception/ErrorResponse.java excluded as out of scope |
| R2 | Restored Testcontainers 1.20.1 pin (rejected rewrite hunk); spring-boot-starter-web -> spring-boot-starter-webmvc (pack 1.3) | package | 21.0.11 | tests-failed | 7 | 4.1.1 | This round used the same package goal as round 0, so its test counts are comparable. It has the same 19 tests and the same 2 failures (the actuator slice tests, 404), with 0 errors. The 6 @WithMockUser tests still pass, so the section 4.4 regression did not occur: the security-test starter was swapped before the first test run. The 5 Testcontainers tests pass on PostgreSQL 16 with Testcontainers 1.20.1, which proves constraint C5 and shows the JPQL and native queries work under Hibernate 7.4.5. Nothing was introduced. | Restored <version>1.20.1</version> on org.testcontainers:testcontainers (reverts the rejected rewrite-01 hunk); spring-boot-starter-web renamed to spring-boot-starter-webmvc (pack section 1.3, build-file edit) |
| R3 | Packaging gate: runnable jar on JDK 21 (no source change) | package-skip-tests | 21.0.11 | passed | 0 | 4.1.1 | Packaging gate on JDK 21 with tests skipped, because round 2 already ran the suite. A runnable Boot 4.1.1 jar was produced, and the final runtime probe ran against it. |  |

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
| Final probed | yes (started: true, 20.101 s) |
| Readiness | {"path":"/actuator/health","status":200,"ready_after_ms":23252} |
| Probes (final) | 13 |
| Runtime failures | none |

## Behaviour comparison

🟡 same status on all 13, body differs on 8

| Probes | Identical | Body differs | Status changed |
|---|---|---|---|
| 13 | 5 | 8 | 0 |

Expected differences:

- employee not found is 404: non-deterministic
- create duplicate is 409: non-deterministic
- create invalid is 400: non-deterministic
- bad path variable type: expected-framework-change
- unauthenticated is 401: expected-framework-change
- bad credentials is 401: expected-framework-change
- health is public: expected-framework-change
- metrics endpoint: expected-framework-change

Unexpected differences:

_none_

## Pipeline handoff

_Direct request — no Agent 04 handoff applies._

## Evidence

- Session: `../evidence/roundB/ctx/version-migration/b2-golden`
- Report: `../evidence/roundB/reports/migration_b2-golden.md`
- Diff: `../evidence/roundB/reports/migration_b2-golden.diff`
- `../evidence/roundB/ctx/version-migration/b2-golden/baseline.json`
- `../evidence/roundB/ctx/version-migration/b2-golden/migration-plan.json`
- `../evidence/roundB/ctx/version-migration/b2-golden/probes.json`
- `../evidence/roundB/ctx/version-migration/b2-golden/migration.json`
- `../evidence/roundB/ctx/version-migration/b2-golden/state.json`
- `../evidence/roundB/ctx/version-migration/b2-golden/rounds`
- `../evidence/roundB/ctx/version-migration/b2-golden/runtime`
- `../evidence/roundB/ctx/version-migration/b2-golden/transformations`
