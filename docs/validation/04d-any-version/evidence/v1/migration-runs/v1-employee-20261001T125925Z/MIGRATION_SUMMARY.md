# Migration Summary — v1-employee-20261001T125925Z

> **PARTIAL PASS** — 1 client-visible HTTP status change(s) classified as expected framework changes need human acceptance: get employee trailing slash

_Generated automatically by 04d-version-migration from the session's evidence files at the end of every script invocation. Nothing here is hand-written._

## Run metadata

| Field | Value |
|---|---|
| Run ID | v1-employee-20261001T125925Z |
| Generated | 2026-10-01T13:59:05.139Z |
| Started | 2026-10-01T12:59:25.019Z |
| Duration | 1389 s |
| Source commit | 20a26a512670e49dc603675a941dbfa3b2b5f1e7 |
| Working branch / worktree | validate/04d-integration @ E:/mv/C |
| Input location | E:/mv/C/src/employee-service |
| Output location | ../evidence/v3/v1/ctx/version-migration/v1-employee |
| Source Java | 17 (JDK 17.0.20.1) |
| Target Java | 21 (JDK 21.0.11) |
| Source platform | Spring Boot 2.7.12 |
| Target platform | Spring Boot 3.5.16 |
| Selected migration pack | spring-boot-2-to-3 |
| Agent 04 issue | — |
| Approval mode | n/a — direct request, not routed by Agent 04 |
| Final status | PARTIAL PASS |
| Session state | RENDERED (reached: BASELINE_DETECTED → WORKSPACE_PREPARED → BASELINE_BUILT → BASELINE_PROBED → PLAN_READY → TRANSFORMATION_PREVIEWED → TRANSFORMATION_APPLIED → TARGET_COMPILED → TARGET_TESTED → FINAL_PROBED → EVIDENCE_READY → RENDERED) |

## Trigger

invoked directly, not by 04_fix-generator Stage 2

## Detection

| What | Value | Detected from |
|---|---|---|
| build tool | maven (wrapper) Apache Maven 3.8.7 (b89d5959fcde851dcb1c8946a785a163f14e1e29) | pom.xml |
| declared Java | 17 | pom.xml (java.version / compiler release) |
| source platform | 2.7.12 | org.springframework.boot:spring-boot-starter-parent (parent) |
| requested target platform | 3.5.16 | request (line 3.5 → latest GA patch, maven-metadata) |
| requested target Java | 21 | request |
| JDKs available | 17 (17.0.20.1), 21 (21.0.11) | local toolchain probe |

## Migration path

**Status:** `SUPPORTED` — 2.7.12 → 3.5.16

| Step | Capability | Available |
|---|---|---|
| 2.7.12 → 2.7.18 | pin-only (core-apache-2.0) | yes |
| 2.7.18 → 3.0.13 | org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0 | yes |
| 3.0.13 → 3.3.13 | org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3 | yes |
| 3.3.13 → 3.5.16 | pin-only (core-apache-2.0) | yes |

Pack eligibility:

| Pack | Nominated by | Eligible | Why |
|---|---|---|---|
| spring-boot-2-to-3 | org.springframework.boot:spring-boot-starter-parent:2 | yes | source org.springframework.boot:spring-boot-starter-parent 2.7.12 (parent) is in the pack's 2.x generation |
| spring-boot-3-to-4 | org.springframework.boot:spring-boot-starter-web | no | source org.springframework.boot:spring-boot-starter-parent 2.7.12 (parent) is generation 2, but the pack migrates from 3.x |

## Migration path (edges)

Ladder `references/openrewrite/spring-boot-ladder.json` · licence policy **open-source-only** · granularity boundary · versions from maven-metadata https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml

| Edge | From → To | Class | Recipe | Licence | JDK | Cloud train | Previews | Applies | Rounds | Complete |
|---|---|---|---|---|---|---|---|---|---|---|
| E1 | 2.7.12 → 2.7.18 | PATCH | pin-only | Apache-2.0 | 17 | 2021.0.9 | rewrite-00 failed (0 files); rewrite-01 previewed (1 files) | rewrite-02 applied (1 files) | R1 test-compile passed | yes (R1) |
| E2 | 2.7.18 → 3.0.13 | MAJOR_BOUNDARY | upstream: org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0 | Apache-2.0 | 17 | 2022.0.5 | rewrite-03 previewed (7 files) | rewrite-04 applied (3 files) | R2 test-compile compile-failed; R3 test-compile passed | yes (R3) |
| E3 | 3.0.13 → 3.3.13 | MINOR | upstream: org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_3 | Apache-2.0 | 17 | 2023.0.6 | rewrite-05 previewed (6 files) | rewrite-06 applied (1 files) | R4 test-compile compile-failed; R5 test-compile passed | yes (R5) |
| E4 | 3.3.13 → 3.5.16 | MINOR (landing) | pin-only | Apache-2.0 | 21 | 2025.0.3 | rewrite-07 previewed (1 files) | rewrite-08 applied (1 files) | R6 test-compile passed; R7 package compile-failed; R8 package passed | yes (R6) |

Edge notes:

- E1: patch edge within 2.7: pins the platform and Spring Cloud train only — a patch release carries no framework migration
- E4: no open-source-only upstream recipe for 3.5: the edge pins the platform, Spring Cloud train and Java level; compiler-driven repair handles the rest

## Endpoint preservation

| Inventory | Before | After | Preserved | Missing | Added |
|---|---|---|---|---|---|
| Source mappings (static scan) | 5 | 5 | 5 | 0 | 0 |
| Running application (actuator /mappings) | 10 | 10 | 10 | 0 | 0 |

No endpoint observed before the migration is missing after it, in any inventory that was available.

## Planned transformations

- Impact areas: [E1] Platform patch + Cloud train, [E2] Boot 3.0 parent + Cloud 2022.0, [E2] Spring Cloud 2022.0, [E2] Spring Framework 6 MVC error handling, [E2] javax -> jakarta (test glue), [E2] RestTemplate on HttpClient, [E2] ResponseEntity status API, [E2] Trailing-slash matching off, [E2] Default error bodies (ResponseEntityExceptionHandler), [E3] Boot 3.3 parent + Cloud 2023.0, [E4] Boot 3.5 parent + Cloud 2025.0 + Java 21, [E4] Pinned jacoco-maven-plugin 0.8.7 on Java 21, [E3/E4] @MockBean deprecation (13 impact entries)
- Deterministic candidates: edge:E1, edge:E2, edge:E3, edge:E4
- Residual candidates: {"impact":"I5","why_not_deterministic":"The javax->jakarta move of javax.ws.rs only helps if a Jakarta RS API is on the test classpath; Eureka 4 no longer brings Jersey, so what replaces the import must be decided from the resolved dependency tree"}; {"impact":"I6","why_not_deterministic":"No recipe in the path switches HttpClient 4 to 5 for a test helper; only acted on if the build names it"}; {"impact":"I8","why_not_deterministic":"Trailing-slash matching is a runtime behaviour change; recorded, not re-enabled silently (pack 5.1)"}; {"impact":"I9","why_not_deterministic":"Framework-owned error body; classified after the final probe"}; {"impact":"I12","why_not_deterministic":"No ladder recipe touches the pinned jacoco plugin; only changed if the Java 21 build/test evidence demands it"}

## Actual transformations

| File | Origin | Evidence | What |
|---|---|---|---|
| pom.xml | openrewrite | — | Parent 2.7.12 -> 2.7.18 -> 3.0.13 -> 3.3.13 -> 3.5.16, spring-cloud.version per edge, java.version 17 -> 21. |
| src/main/java/com/aura/vihanga/employeeservice/advice/EmployeeAdvice.java | openrewrite | — | handleHttpRequestMethodNotSupported now overrides the Framework 6 signature taking HttpStatusCode instead of HttpStatus. |
| src/test/java/com/aura/vihanga/employeeservice/cucumberglue/CucumberConfig.java | openrewrite | — | import javax.ws.rs.core.Application became import jakarta.ws.rs.core.Application. |
| src/main/java/com/aura/vihanga/employeeservice/EmployeeServiceApplication.java | reference-rule | Round 2 G1: 2x cannot find symbol class EnableEurekaClient at EmployeeServiceApplication.java:7 and :10. | Removed the @EnableEurekaClient annotation and its import. |
| pom.xml | harness-residual | Round 7: 'Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.7:report ... Unsupported class file major version 65'. Version verified against Maven Central metadata and JaCoCo's release notes (0.8.11, 2023/10/14: officially supports Java 21). | jacoco-maven-plugin in the unitTest profile 0.8.7 -> 0.8.11 (the unused test-scope dependency declaration left at 0.8.7). |
| pom.xml | harness-residual | Rejected at preview inspection (rewrite-03, rewrite-05); round 4 showed the exclusion removes JUnit 4 (org.junit.runner / org.junit.Before / org.junit.Assert not found in 4 test files); round 5 green after the revert. | After rewrite-04 and rewrite-06, hand-reverted the junit exclusion on cucumber-junit, jacoco 0.8.7 -> 0.8.15 (x2) and maven-surefire-plugin 2.19.1 -> 3.1.2 in the inactive cucumberTest profile. |
| src/test/java/com/aura/vihanga/employeeservice/cucumberglue/CucumberConfig.java | harness-residual | Rejected at preview inspection of rewrite-03; round 3 green with the revert. | After rewrite-04, hand-reverted org.junit.Before -> org.junit.jupiter.api.BeforeEach (kept the jakarta import from the same file). |

## File changes

| File | Change | Tool | Reason |
|---|---|---|---|
| pom.xml | modified | openrewrite + harness-residual | The migration path's four edges, each pinned by its generated recipe. · JaCoCo 0.8.7 cannot read Java 21 (major 65) class files, so the report goal failed after the tests passed. · Unrelated upgrades and a JUnit 4 removal the jump does not require; the exclusion actively broke test compilation (round 4). |
| src/main/java/com/aura/vihanga/employeeservice/EmployeeServiceApplication.java | modified | reference-rule | Spring Cloud 2022.0 removed the annotation; the Eureka client starter on the classpath registers the service. |
| src/main/java/com/aura/vihanga/employeeservice/advice/EmployeeAdvice.java | modified | openrewrite | Spring Framework 6 changed ResponseEntityExceptionHandler's handler signatures to HttpStatusCode; the old override would no longer override anything. |
| src/test/java/com/aura/vihanga/employeeservice/cucumberglue/CucumberConfig.java | modified | openrewrite + harness-residual | Jakarta EE 9 namespace; Eureka's Jersey moved to jakarta.ws.rs, which round 3 resolved on the test classpath. · A JUnit 4 -> 5 migration of Cucumber glue is not required by the Boot jump. |

## Dependency changes

| Dependency | Old | New | Reason |
|---|---|---|---|
| org.springframework.boot:spring-boot-starter-parent (parent) | 2.7.12 | 3.5.16 | platform parent |
| org.springframework.cloud:spring-cloud-dependencies | 2021.0.7 | 2025.0.3 | version changed |
| org.jacoco:jacoco-maven-plugin (plugin) | 0.8.7 | 0.8.11 | plugin version changed |
| property java.version | 17 | 21 | build property |
| property spring-cloud.version | 2021.0.7 | 2025.0.3 | build property |

## OpenRewrite

| # | Transformation | Mode | Status | Recipes | Files | Decision | Error |
|---|---|---|---|---|---|---|---|
| 0 | rewrite-00 | dry-run | failed | mars.migration.edge.E1 | 0 | superseded | — |
| 1 | rewrite-01 | dry-run | previewed | mars.migration.edge.E1 | 1 | superseded | — |
| 2 | rewrite-02 | apply | applied | mars.migration.edge.E1 | 1 | accepted | — |
| 3 | rewrite-03 | dry-run | previewed | mars.migration.edge.E2 | 7 | superseded | — |
| 4 | rewrite-04 | apply | applied | mars.migration.edge.E2 | 3 | accepted | — |
| 5 | rewrite-05 | dry-run | previewed | mars.migration.edge.E3 | 6 | superseded | — |
| 6 | rewrite-06 | apply | applied | mars.migration.edge.E3 | 1 | accepted | — |
| 7 | rewrite-07 | dry-run | previewed | mars.migration.edge.E4 | 1 | superseded | — |
| 8 | rewrite-08 | apply | applied | mars.migration.edge.E4 | 1 | accepted | — |

## Compile history

| Round | Label | Goal | JDK | Outcome | Errors | Declared platform | Diagnosis | Repair |
|---|---|---|---|---|---|---|---|---|
| R0 (baseline) | Pre-migration reference build | package | 17.0.20.1 | passed | 0 | 2.7.12 | Pre-migration reference build of the untouched project on JDK 17, goal package: 7 JUnit 5 tests across 4 classes, all passing (the @DataMongoTest class runs against the local MongoDB). The JUnit 4 Cucumber runner is not executed by surefire on the JUnit Platform, so the Cucumber glue is only ever compiled. These are the counts the migration is judged against. |  |
| R1 | OpenRewrite rewrite-02 applied (mars.migration.edge.E1) | test-compile | 17.0.20.1 | passed | 0 | 2.7.18 | Edge E1 (PATCH 2.7.12 -> 2.7.18): the pin-only recipe moved the parent and the Spring Cloud train to 2021.0.9; main and test code compile unchanged on JDK 17. Edge complete. | Applied rewrite-02 (edge E1, previewed as rewrite-01): parent 2.7.18, spring-cloud.version 2021.0.9. |
| R2 | OpenRewrite rewrite-04 applied (mars.migration.edge.E2) | test-compile | 17.0.20.1 | compile-failed | 5 | 3.0.13 | Edge E2 apply build (2.7.18 -> 3.0.13, Cloud 2022.0.5). UpgradeSpringBoot_3_0 fixed the advice signature and the javax.ws.rs import, but Spring Cloud 2022.0 deleted @EnableEurekaClient, which no recipe in the chain removed, so main compilation stopped there before any test code was compiled. The rejected pom hunks were still present in this round. | Applied rewrite-04 (edge E2, previewed as rewrite-03) to pom.xml, EmployeeAdvice.java and CucumberConfig.java; WebClientConfig.java, EmployeeController.java, CucumberSteps.java and EmployeeRepositoryTest.java excluded as out of scope. |
| R3 | Removed @EnableEurekaClient (pack s8); hand-reverted rejected rewrite-04 hunks (junit exclusion, jacoco/surefire bumps, @BeforeEach) | test-compile | 17.0.20.1 | passed | 0 | 3.0.13 | With the annotation removed and the rejected hunks reverted, main and test code compile on 3.0.13. jakarta.ws.rs.core.Application resolves on the test classpath (Eureka's Jakarta Jersey), confirming the accepted import move. Edge E2 complete. | Removed @EnableEurekaClient and its import from EmployeeServiceApplication.java (pack section 8).; Hand-reverted rejected rewrite-04 hunks: cucumber-junit junit exclusion, jacoco 0.8.15 x2, surefire 3.1.2 (pom.xml) and @BeforeEach (CucumberConfig.java). |
| R4 | OpenRewrite rewrite-06 applied (mars.migration.edge.E3) | test-compile | 17.0.20.1 | compile-failed | 14 | 3.3.13 | Edge E3 apply build (3.0.13 -> 3.3.13, Cloud 2023.0.6). Every error is JUnit 4 disappearing from the test classpath: the rejected cucumber-junit junit exclusion was still in pom.xml for this round (the hunk is reverted by hand after the apply, per the procedure). Nothing here is caused by Boot 3.3. | Applied rewrite-06 (edge E3, previewed as rewrite-05) to pom.xml only; WebClientConfig.java, EmployeeController.java, CucumberConfig.java, CucumberSteps.java and EmployeeRepositoryTest.java excluded as out of scope. |
| R5 | Hand-reverted rejected rewrite-06 pom hunks (cucumber-junit junit exclusion that removed JUnit 4, jacoco/surefire bumps) | test-compile | 17.0.20.1 | passed | 0 | 3.3.13 | With the rejected pom hunks reverted, everything compiles on 3.3.13 / JDK 17. Edge E3 complete. | Hand-reverted rejected rewrite-06 pom.xml hunks (junit exclusion, jacoco 0.8.15 x2, surefire 3.1.2). |
| R6 | OpenRewrite rewrite-08 applied (mars.migration.edge.E4) | test-compile | 21.0.11 | passed | 0 | 3.5.16 | Landing edge E4 apply build (3.3.13 -> 3.5.16, Cloud 2025.0.3, java.version 21) on JDK 21: main and test code compile. Only warnings remain: @MockBean is deprecated for removal from Boot 3.4 (plan I13), which still compiles on 3.5. Edge E4 complete. | Applied rewrite-08 (edge E4, previewed as rewrite-07): parent 3.5.16, java.version 21, spring-cloud.version 2025.0.3. |
| R7 | Same goal as round 0 (package, tests run) on JDK 21; no source change | package | 21.0.11 | compile-failed | 1 | 3.5.16 | Same goal as round 0 (package) on JDK 21: the 7 tests all pass, then the pinned JaCoCo 0.8.7 report goal fails because it cannot parse Java 21 (major 65) class files; its agent also logged instrumentation errors during the tests. The script records the outcome as compile-failed, but compilation and tests succeeded; the failure is the reporting plugin. This is the predicted impact I12. |  |
| R8 | jacoco-maven-plugin 0.8.7 -> 0.8.11 in the unitTest profile (Java 21 class files) | package | 21.0.11 | passed | 0 | 3.5.16 | With JaCoCo 0.8.11 the package goal is green on JDK 21: 7 tests run, 0 failures, 0 errors, 0 skipped, which are the same counts as round 0, and the executable war is built for Boot 3.5.16 (manifest Build-Jdk-Spec 21). | jacoco-maven-plugin 0.8.7 -> 0.8.11 in the unitTest profile (pom.xml). |

## Tests

| Side | Round | Total | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|---|
| Before | 0 | 7 | 7 | 0 | 0 | 0 |
| After | 8 | 7 | 7 | 0 | 0 | 0 |

New failures against round 0: **no**

## Runtime

| Check | Result |
|---|---|
| Baseline probed | yes (started: true) |
| Final probed | yes (started: true, 7.485 s) |
| Readiness | {"path":"/actuator/health","status":200,"ready_after_ms":9419} |
| Probes (final) | 13 |
| Runtime failures | none |

## Behaviour comparison

🔴 1 of 13 probe(s) changed status

| Probes | Identical | Body differs | Status changed |
|---|---|---|---|
| 13 | 10 | 2 | 1 |

Expected differences:

- endpoint inventory (actuator): expected-framework-change
- get employee trailing slash: expected-framework-change
- search without name is 400: expected-framework-change

Unexpected differences:

_none_

## Pipeline handoff

_Direct request — no Agent 04 handoff applies._

## Evidence

- Session: `../evidence/v3/v1/ctx/version-migration/v1-employee`
- Report: `../evidence/v3/v1/reports/migration_v1-employee.md`
- Diff: `../evidence/v3/v1/reports/migration_v1-employee.diff`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/baseline.json`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/migration-plan.json`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/probes.json`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/migration.json`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/state.json`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/rounds`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/runtime`
- `../evidence/v3/v1/ctx/version-migration/v1-employee/transformations`
