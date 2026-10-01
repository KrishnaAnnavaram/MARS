# Migration Summary — v2-golden-20261001T125906Z

> **PASS** — green on the requested target, no new test failures, behaviour compared with no status changes

_Generated automatically by 04d-version-migration from the session's evidence files at the end of every script invocation. Nothing here is hand-written._

## Run metadata

| Field | Value |
|---|---|
| Run ID | v2-golden-20261001T125906Z |
| Generated | 2026-10-01T13:56:54.784Z |
| Started | 2026-10-01T12:59:06.565Z |
| Duration | 1368 s |
| Source commit | 08625161ca388fce117db2a6f9864f999275f519 |
| Working branch / worktree | master @ E:/mv/golden |
| Input location | E:/mv/golden |
| Output location | ../evidence/v3/v2/ctx/version-migration/v2-golden |
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
| E1 | 3.5.0 → 3.5.16 | PATCH | pin-only | Apache-2.0 | 17 | — | rewrite-00 previewed (1 files) | rewrite-01 applied (1 files) | R1 test-compile passed; R2 test-compile passed | yes (R1) |
| E2 | 3.5.16 → 4.0.8 | MAJOR_BOUNDARY | oss-composite: openrewrite/spring-boot-3-to-4.oss.yml | Apache-2.0 | 17 | — | rewrite-02 previewed (5 files); rewrite-04 no-changes (0 files) | rewrite-03 applied (5 files) | R3 test-compile compile-failed; R4 test-compile compile-failed; R5 test-compile passed; R6 package tests-failed | yes (R5) |
| E3 | 4.0.8 → 4.1.1 | MINOR (landing) | pin-only | Apache-2.0 | 21 | — | rewrite-05 previewed (1 files) | rewrite-06 applied (1 files) | R7 test-compile passed; R8 package tests-failed; R9 package-skip-tests passed; R10 package tests-failed; R11 package-skip-tests passed | yes (R7) |

Edge notes:

- E1: patch edge within 3.5: pins the platform only — a patch release carries no framework migration
- E3: no open-source-only upstream recipe for 4.1: the edge pins the platform and Java level; compiler-driven repair handles the rest

## Endpoint preservation

| Inventory | Before | After | Preserved | Missing | Added |
|---|---|---|---|---|---|
| Source mappings (static scan) | 7 | 11 | 7 | 0 | 4 |
| Running application (actuator /mappings) | — | — | — | — | — |

- /actuator/mappings not exposed or not probed — static inventory only

No endpoint observed before the migration is missing after it, in any inventory that was available.

Added: `GET /actuator/health`, `GET /actuator/info`, `GET /actuator/metrics`, `GET /h2-console`

## Planned transformations

- Impact areas: Platform parent, Language level property, Compiler configuration, Starter rename, Security test starter, Modular test starters, JSON handling, Actuator health, Test layer (web slice), Test layer (JPA slice), Error contract, Security 7, Hibernate 7 / Spring Data JPA, Configuration properties, Runtime image, Pinned Testcontainers (16 impact entries)
- Deterministic candidates: edge:E1, edge:E2, edge:E3
- Residual candidates: {"impact":"I3","why_not_deterministic":"The generated edge recipe pins only Java properties present in the pom (java.version); no selected recipe normalises an explicit maven-compiler-plugin <source>/<target>. Pack rule 1.2 applied as a build-file edit."}; {"impact":"I7","why_not_deterministic":"The OSS composite moves the databind/core packages only. The Jackson 3 configuration API (immutable mapper, builder customizer, DateTimeFeature, changeDefaultPropertyInclusion) and the jsr310 module are not mechanical type moves; repaired from compiler errors per pack 2.2."}; {"impact":"I9","why_not_deterministic":"Any remaining test-layer break after the composite's type moves (e.g. injected mapper type, @WithMockUser at test time) is only visible to test-compile/test rounds."}; {"impact":"I14","why_not_deterministic":"Removed or relocated properties fail at runtime, not at compile time; no Apache-2.0 property-migration recipe is available under the policy (SpringBootProperties_4_x is source-available)."}; {"impact":"I15","why_not_deterministic":"No recipe in the pack or ladder changes a Dockerfile base image (pack 8, 15)."}; {"impact":"I16","why_not_deterministic":"Explicitly pinned third-party test library; only changed if a build demands it."}

## Actual transformations

| File | Origin | Evidence | What |
|---|---|---|---|
| pom.xml | openrewrite | — | Parent moved 3.5.0 -> 3.5.16 -> 4.0.8 -> 4.1.1 and java.version 17 -> 21 by the edge recipes; web and security-test starters renamed by the composite. |
| pom.xml | harness-residual | Inspected previews rewrite-00/02/05 show the version removals; mvn dependency:tree after rewrite-03 showed org.testcontainers:testcontainers:2.0.5 next to postgresql/junit-jupiter 1.20.1. Plan out_of_scope: Testcontainers 2 migration. | Restored <version>1.20.1</version> on the pinned Testcontainers artifacts after each edge apply (3 artifacts after rewrite-01, testcontainers core after rewrite-03 and rewrite-06). |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | harness-residual | Round 3 errors G2/G4/G5/G6/G7 (JavaTimeModule, disable(SerializationFeature), setDefaultPropertyInclusion, package com.fasterxml.jackson.datatype.jsr310, WRITE_DATES_AS_TIMESTAMPS). javap: spring-boot-jackson-4.0.8 JacksonAutoConfiguration.jsonMapper is @Primary @ConditionalOnMissingBean(JsonMapper); jackson-databind 3.1.5 has DateTimeFeature and MapperBuilder.changeDefaultPropertyInclusion(UnaryOperator). | The mutable Jackson 2 ObjectMapper bean became a JsonMapper built with JsonMapper.builder(): DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS disabled, default inclusion NON_NULL (value and content), INDENT_OUTPUT disabled; JavaTimeModule registration dropped. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | openrewrite | — | Imports com.fasterxml.jackson.databind.{ObjectMapper,SerializationFeature} moved to tools.jackson.databind. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | openrewrite | — | Health and HealthIndicator imports moved to org.springframework.boot.health.contributor. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | openrewrite | — | @MockBean became @MockitoBean, @WebMvcTest import moved to org.springframework.boot.webmvc.test.autoconfigure, ObjectMapper import moved to tools.jackson.databind. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | openrewrite | — | @DataJpaTest import moved to org.springframework.boot.data.jpa.test.autoconfigure. |
| pom.xml | reference-rule | Round 4: package org.springframework.boot.webmvc.test.autoconfigure / data.jpa.test.autoconfigure does not exist. mvn dependency:get of both starters at 4.0.8 succeeded; jars contain WebMvcTest, AutoConfigureMockMvc and DataJpaTest in the new packages. | Added test-scope spring-boot-starter-webmvc-test and spring-boot-starter-data-jpa-test. |
| pom.xml | harness-residual | First final probe: 'h2 console (configured in application.yml)' 200 -> 404 ('No static resource h2-console'). H2ConsoleAutoConfiguration present in spring-boot-autoconfigure-3.5.0, absent from 4.1.1, present in spring-boot-h2console-4.1.1 (BOM-managed). After the change the probe returned 200 again. | Added runtime-scope org.springframework.boot:spring-boot-h2console. |
| Dockerfile | harness-residual | Plan impact I15; baseline observations.container_runtime; no recipe in the pack or ladder changes a Dockerfile. | Base image moved from eclipse-temurin:17-jre to eclipse-temurin:21-jre. |

## File changes

| File | Change | Tool | Reason |
|---|---|---|---|
| Dockerfile | modified | harness-residual | The jar now targets Java 21 (class file major 65) and would not start on a 17 runtime. |
| pom.xml | modified | openrewrite + harness-residual + reference-rule | The requested Boot 4.1.1 / Java 21 target, walked as the ladder's three edges. · ChangeParentPom removed explicit versions that the new parent manages; on 4.x this would have moved only the core artifact to Testcontainers 2.0.5 while postgresql/junit-jupiter stayed 1.20.1. · The relocated slice annotations live in these modules; the composite's AddDependency(onlyIfUsing) proposed nothing, in rewrite-03 and again in the re-preview rewrite-04. · Boot 4 split the H2 console auto-configuration out of spring-boot-autoconfigure; application.yml still enables the console. |
| src/main/java/com/example/migrationdemo/config/JacksonConfig.java | modified | harness-residual + openrewrite | Jackson 3 mappers are immutable (no registerModule/disable/setDefaultPropertyInclusion on the instance), WRITE_DATES_AS_TIMESTAMPS moved to DateTimeFeature, java.time support is built in, and Boot 4's own @Primary JsonMapper backs off only for a bean typed JsonMapper. · Jackson 3 package root. |
| src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java | modified | openrewrite | Boot 4 relocated the health contributor types. |
| src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java | modified | openrewrite | Boot 4 removed @MockBean, moved the web slice to its own module, and ships Jackson 3. |
| src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java | modified | openrewrite | Boot 4 moved the JPA slice to its own module. |

## Dependency changes

| Dependency | Old | New | Reason |
|---|---|---|---|
| org.springframework.boot:spring-boot-starter-parent (parent) | 3.5.0 | 4.1.1 | platform parent |
| org.springframework.boot:spring-boot-starter-webmvc | — | (managed) | added |
| org.springframework.boot:spring-boot-h2console | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-security-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-webmvc-test | — | (managed) | added |
| org.springframework.boot:spring-boot-starter-data-jpa-test | — | (managed) | added |
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
| 4 | rewrite-04 | dry-run | no-changes | mars.migration.edge.E2 | 0 | not-run | — |
| 5 | rewrite-05 | dry-run | previewed | mars.migration.edge.E3 | 1 | superseded | — |
| 6 | rewrite-06 | apply | applied | mars.migration.edge.E3 | 1 | accepted | — |

## Compile history

| Round | Label | Goal | JDK | Outcome | Errors | Declared platform | Diagnosis | Repair |
|---|---|---|---|---|---|---|---|---|
| R0 (baseline) | Pre-migration reference build | package | 17.0.20.1 | tests-failed | 7 | 3.5.0 | Pre-migration reference build on JDK 17, package goal: 19 tests, 2 failures. Both failures are EmployeeControllerTest actuator tests that expect 200 from /actuator/health and /actuator/info inside a @WebMvcTest slice, which does not load the actuator endpoints, so they get 404. They are pre-existing and are the comparison baseline. Docker was available, so the 5 Testcontainers PostgreSQL integration tests ran and passed. |  |
| R1 | OpenRewrite rewrite-01 applied (mars.migration.edge.E1) | test-compile | 17.0.20.1 | passed | 0 | 3.5.16 | Edge E1 (3.5.0 -> 3.5.16, pin-only) verified on JDK 17: test-compile is green. The applied recipe also stripped the explicit Testcontainers versions, which the preview had shown and which was rejected for the next round. | Applied rewrite-01 (edge E1 recipe, previewed as rewrite-00). |
| R2 | Reverted the out-of-scope hunk of rewrite-01: restored the pinned Testcontainers 1.20.1 versions ChangeParentPom removed | test-compile | 17.0.20.1 | passed | 0 | 3.5.16 | Same edge after reverting the rejected hunk: still green, so the patch release needed nothing else. | Restored <version>1.20.1</version> on the three org.testcontainers dependencies (rejected hunk of rewrite-01). |
| R3 | OpenRewrite rewrite-03 applied (mars.migration.edge.E2) | test-compile | 17.0.20.1 | compile-failed | 10 | 4.0.8 | Edge E2 (3.5.16 -> 4.0.8, open-source composite) on JDK 17. Main code fails only in JacksonConfig. The composite moved the databind package, but the hand-built mapper uses the Jackson 2 mutable API and the jsr310 module, none of which a package move can fix. Test code did not get compiled yet because main failed first. | Applied rewrite-03 (edge E2 recipe: mars.migration.oss.SpringBoot3To4 + parent 4.0.8, previewed as rewrite-02). |
| R4 | JacksonConfig moved to the Jackson 3 JsonMapper builder; restored pinned Testcontainers 1.20.1 core version (rejected rewrite-03 hunk) | test-compile | 17.0.20.1 | compile-failed | 7 | 4.0.8 | Main code now compiles; the failure moved to the test layer, which only compiles once main does. Both test classes import the relocated slice annotations, but the modules that contain them are not on the classpath: the composite's AddDependency steps did not add them. | JacksonConfig: Jackson 2 ObjectMapper bean replaced by a JsonMapper built with the Jackson 3 builder (pack 2.2).; Restored <version>1.20.1</version> on org.testcontainers:testcontainers (rejected hunk of rewrite-03). |
| R5 | Added spring-boot-starter-webmvc-test and spring-boot-starter-data-jpa-test (pack 4.3; the composite's AddDependency did not fire) | test-compile | 17.0.20.1 | passed | 0 | 4.0.8 | Test-compile is green on Boot 4.0.8 with JDK 17, so edge E2 is complete. Before this round the composite's own deterministic step was retried (rewrite-04 dry-run) and proposed no change, so the starters were added by hand. | Added test-scope spring-boot-starter-webmvc-test and spring-boot-starter-data-jpa-test (pack 4.3). |
| R6 | Same code as round 5; goal raised to package to run the suite on the boundary edge | package | 17.0.20.1 | tests-failed | 7 | 4.0.8 | The same package goal as round 0, run on the boundary edge to catch Boot 4 test-time breaks before the next edge: 19 tests, the same 2 pre-existing actuator failures, no new ones. @WithMockUser tests still pass, so the security-test starter rename covered pack 4.4, and the 5 PostgreSQL integration tests pass with Hibernate 7 and the pinned Testcontainers 1.20.1. The log also shows a new warning, "Parameter 'executable' is unknown for plugin spring-boot-maven-plugin:4.0.8:repackage": Boot 4 dropped the fully-executable jar option, which the build ignores rather than failing on. |  |
| R7 | OpenRewrite rewrite-06 applied (mars.migration.edge.E3) | test-compile | 21.0.11 | passed | 0 | 4.1.1 | Edge E3 (4.0.8 -> 4.1.1 plus java.version 21) on JDK 21: test-compile is green. The log shows javac running with 'release 21', and the classes are major version 65. The Boot parent's maven.compiler.release=${java.version} wins over the stale <source>17</source>/<target>17</target>, so the pack 1.2 symptom (silently compiling at 17) did not happen. | Applied rewrite-06 (edge E3 recipe, previewed as rewrite-05). |
| R8 | Restored pinned Testcontainers 1.20.1 core version (rejected rewrite-06 hunk); Dockerfile base image to eclipse-temurin:21-jre; same package goal as round 0 | package | 21.0.11 | tests-failed | 7 | 4.1.1 | Same goal as round 0, on the landing version and JDK 21: 19 tests, the same 2 pre-existing failures, no new failures. | Restored <version>1.20.1</version> on org.testcontainers:testcontainers (rejected hunk of rewrite-06).; Dockerfile base image eclipse-temurin:17-jre -> eclipse-temurin:21-jre (pack 8). |
| R9 | Same code as round 8; package without tests to produce the runnable jar on JDK 21 | package-skip-tests | 21.0.11 | passed | 0 | 4.1.1 | Package without tests produced the runnable jar on JDK 21. The first final probe ran on this jar and found /h2-console returning 404. |  |
| R10 | Added runtime spring-boot-h2console: Boot 4 moved H2ConsoleAutoConfiguration out of spring-boot-autoconfigure and the final probe lost /h2-console (200 -> 404) | package | 21.0.11 | tests-failed | 7 | 4.1.1 | After the H2 console module was added, the same goal as round 0 gives the same 19 tests and the same 2 pre-existing failures, so the added runtime module changed nothing in the suite. | Added runtime-scope org.springframework.boot:spring-boot-h2console (Boot 4 moved H2ConsoleAutoConfiguration out of spring-boot-autoconfigure). |
| R11 | Same code as round 10; package without tests for the runnable jar | package-skip-tests | 21.0.11 | passed | 0 | 4.1.1 | Final packaging round on JDK 21 is green. The final runtime probe was re-run on this jar. |  |

## Tests

| Side | Round | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|---|
| Before | 0 | 19 | 17 | 2 | 0 | 0 |
| After | 10 | 19 | 17 | 2 | 0 | 0 |

New failures against round 0: **no**

## Runtime

| Check | Result |
|---|---|
| Baseline probed | yes (started: true) |
| Final probed | yes (started: true, 8.262 s) |
| Readiness | {"path":"/actuator/health","status":200,"ready_after_ms":9531} |
| Probes (final) | 20 |
| Runtime failures | none |

## Behaviour comparison

🟡 same status on all 20, body differs on 18

| Probes | Identical | Body differs | Status changed |
|---|---|---|---|
| 20 | 2 | 18 | 0 |

Expected differences:

- list all employees: non-deterministic
- get employee 1: non-deterministic
- search by department: non-deterministic
- high earners above 80000 (native query): non-deterministic
- create employee is 201: non-deterministic
- create without department omits null field: non-deterministic
- update employee 6 is 200: non-deterministic
- employee not found is 404: non-deterministic
- duplicate employee number is 409: non-deterministic
- deleted employee 6 is 404: non-deterministic
- invalid create is 400: non-deterministic
- unauthenticated list is 401: expected-framework-change
- bad credentials is 401: expected-framework-change
- search without department is 400: expected-framework-change
- endpoint inventory (actuator): expected-framework-change
- health is public: expected-framework-change
- metrics index: expected-framework-change
- h2 console (configured in application.yml): expected-framework-change

Unexpected differences:

_none_

## Pipeline handoff

_Direct request — no Agent 04 handoff applies._

## Evidence

- Session: `../evidence/v3/v2/ctx/version-migration/v2-golden`
- Report: `../evidence/v3/v2/reports/migration_v2-golden.md`
- Diff: `../evidence/v3/v2/reports/migration_v2-golden.diff`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/baseline.json`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/migration-plan.json`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/probes.json`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/migration.json`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/state.json`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/rounds`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/runtime`
- `../evidence/v3/v2/ctx/version-migration/v2-golden/transformations`
