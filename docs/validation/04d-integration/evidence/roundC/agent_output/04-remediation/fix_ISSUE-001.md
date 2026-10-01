# Fix — ISSUE-001

## Service runs on Spring Boot 3.5.0 / Java 17, a platform line past its open-source support window — must move to Spring Boot 4.1.1 on Java 21

> Version migration of `src/spring-boot-migration-demo`: Spring Boot 3.5.0 · Java 17 → Spring Boot 4.1.1 · Java 21, performed by `04d-version-migration` in a sandbox. Migration result: **PARTIAL PASS**.

_Written by 04d-version-migration (Stage 2 of 04_fix-generator, routed on Fix Type VERSION_MIGRATION) on 2026-10-01 from migration run `issue-001-20261001T033403Z`._

## At a glance

| | |
|---|---|
| **Status** | Compiled |
| **CWE** | `CWE-1104` |
| **Fix Type** | `VERSION_MIGRATION` |
| **Migration Skill** | `04d-version-migration` (04D) |
| **Fix plan** | [fix_plan_ISSUE-001.md](../../../docs/agent_output/04-remediation/fix_plan_ISSUE-001.md) |
| **Files changed** | 6 |
| **Verification level** | 04D build rounds (4) on JDK 21 + before/after runtime probes |
| **Matches plan** | no — target from the plan's migration request, but 1 changed file(s) are not in its Planned changes: `src/spring-boot-migration-demo/src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java` (see the migration report for the evidence that required them) |
| **Patch** | [fix_ISSUE-001.diff](../../../docs/agent_output/04-remediation/fix_ISSUE-001.diff) |
| **Migration Status** | PARTIAL PASS |
| **Source Version** | Spring Boot 3.5.0 · Java 17 |
| **Target Version** | Spring Boot 4.1.1 · Java 21 |
| **Target Java** | 21 |
| **Migration Report** | [migration_issue-001.md](../../../docs/agent_output/04-remediation/migration_issue-001.md) |
| **Migration Diff** | [migration_issue-001.diff](../../../docs/agent_output/04-remediation/migration_issue-001.diff) |
| **Migration Summary** | [MIGRATION_SUMMARY.md](../../../docs/agent_output/04-remediation/migration-runs/issue-001-20261001T033403Z/MIGRATION_SUMMARY.md) · [migration-summary.json](../../../docs/agent_output/04-remediation/migration-runs/issue-001-20261001T033403Z/migration-summary.json) |
| **Migration Run** | `issue-001-20261001T033403Z` |
| **Approval** | TEST_AUTO_APPROVED (APPROVAL_MODE, 2026-10-01T03:32:19.806Z) — Approval was programmatically granted for controlled pipeline validation; this does not replace the production human approval requirement. |

## 1. What changed

- [src/spring-boot-migration-demo/Dockerfile](../../../src/spring-boot-migration-demo/Dockerfile) — modified, via openrewrite: The jar is now compiled for Java 21 and will not start on a Java 17 JRE.
- [src/spring-boot-migration-demo/pom.xml](../../../src/spring-boot-migration-demo/pom.xml) — modified, via openrewrite: Boot 4 generation jump plus the modularised test starters.
- [src/spring-boot-migration-demo/src/main/java/com/example/migrationdemo/config/JacksonConfig.java](../../../src/spring-boot-migration-demo/src/main/java/com/example/migrationdemo/config/JacksonConfig.java) — modified, via openrewrite: Boot 4 ships Jackson 3 (tools.jackson), which has java.time support built in and writes dates as ISO-8601 by default; the mapper configuration API changed.
- [src/spring-boot-migration-demo/src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java](../../../src/spring-boot-migration-demo/src/main/java/com/example/migrationdemo/health/DatabaseHealthIndicator.java) — modified, via openrewrite: Boot 4 relocated the health contributor types.
- [src/spring-boot-migration-demo/src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java](../../../src/spring-boot-migration-demo/src/test/java/com/example/migrationdemo/controller/EmployeeControllerTest.java) — modified, via openrewrite: @MockBean was removed in Boot 4; the MVC test slice and Jackson databind moved package.
- [src/spring-boot-migration-demo/src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java](../../../src/spring-boot-migration-demo/src/test/java/com/example/migrationdemo/integration/EmployeeRepositoryIntegrationTest.java) — modified, via openrewrite: The JPA test slice moved module and package in Boot 4.

## 2. The diff

[fix_ISSUE-001.diff](../../../docs/agent_output/04-remediation/fix_ISSUE-001.diff) is the 04D cumulative migration patch re-rooted at the repository root (paths prefixed with `src/spring-boot-migration-demo/`) so it applies with `git apply` from the root like every other fix. The project-relative original is [migration_issue-001.diff](../../../docs/agent_output/04-remediation/migration_issue-001.diff).

## 3. Why this is the smallest correct diff

The employee service moved from Spring Boot 3.5.0 on Java 17 to Spring Boot 4.1.1 on Java 21 in a sandbox copy; the real project was not touched. Every source change was made by one previewed and inspected OpenRewrite recipe (parent, Java level, test starters, Jackson 3, actuator health package, mocking annotation, test-slice imports, container base image); two of its proposals were rejected (removing the error-timestamp format and un-pinning only one Testcontainers artifact). The test suite has exactly the same result as before the upgrade (19 run, the same 2 pre-existing failures), and all 19 runtime probes return the same HTTP status before and after, with identical application payloads apart from generated timestamps. Some framework-owned responses did change shape — the default 401 error timestamp, the WWW-Authenticate header, the malformed-JSON problem body (no more "type":"about:blank") and the health document (two new components) — and a human must confirm API consumers accept these before the migration is applied. Approval was programmatically granted for controlled pipeline validation; this does not replace the production human approval requirement.

Rejected as out of scope (kept out of this patch):
- EmployeeService checks email uniqueness with findByEmployeeNumber(email) (noticed, not changed)
- EmployeeControllerTest.testActuatorHealth_Public / testActuatorInfo_Public fail with 404 in the @WebMvcTest slice (noticed, not fixed)
- Testcontainers 2.x move proposed by the recipe (rejected)
- Removal of the @JsonFormat error-timestamp pattern proposed by the recipe (rejected)

## 4. Verification evidence

| Check | Result |
|---|---|
| Build rounds | 4 (R0 tests-failed, R1 passed, R2 tests-failed, R3 passed) |
| Final round | R3 package-skip-tests on JDK 21.0.11 — passed |
| Target declared by final build | 4.1.1 (matches request) |
| Tests before → after | 19 run / 2 failing → 19 run / 2 failing |
| Runtime probes | 19 compared: 2 identical, 17 body-only differences, 0 status changes |
| Migration result | PARTIAL PASS — 1 open blocking condition(s) in migration.json: A human confirms API consumers accept the framework-owned response changes (401 default error timestamp 'Z' format, WWW-Authenticate charset parameter, malformed-JSON ProblemDetail without type, health document with livenessState/readinessState/groups) |
| Patch applies to repository HEAD (git apply --cached --check) | yes — after normalising CRLF to LF (the sandbox was copied from a working tree whose line endings differ from the index) |

## 5. Residual risk

- The JacksonConfig bean is declared as tools.jackson ObjectMapper built from JsonMapper.builder(); whether Boot 4's HTTP message converters use it or the auto-configured JsonMapper was not instrumented — payload equivalence is inferred from identical probe bodies, and spring.jackson.default-property-inclusion: non_null also enforces NON_NULL either way
- Jackson 3 defaults differ from Jackson 2 in places no probe exercised (e.g. unknown-property handling, Map key ordering); request bodies with unknown fields were not probed
- Testcontainers 1.20.1 is now outside the BOM-managed 2.0.5 line; future Boot patch upgrades may surface conflicts
- spring-boot-starter-web still resolves in 4.1.1 but is the Boot 3 name; a later Boot release may drop it (section 1.3)
- Confirm API consumers and monitors accept the framework-owned response changes listed under blocking_conditions (especially anything parsing /actuator/health components, the ProblemDetail 'type' field, or the 401 timestamp format)
- Build and run the container image (eclipse-temurin:21-jre) and update any CI/deployment descriptors outside this project that pin a Java 17 runtime
- Run the service once against PostgreSQL (docker-compose) on Java 21 — runtime probes used in-memory H2
- Plan the Testcontainers 2.x move separately (Boot 4.1.1 manages testcontainers 2.0.5; the project still pins 1.20.1)
- Confirm the Spring Boot 3.5.x open-source support end date and the rewrite-spring Moderne Source Available License use (recorded, not judged)

## 6. For downstream agents

- 04D's own result does **not** clear this patch. Agents 05, 06 and 07 validate it independently, exactly as they would any other fix.
- **05** — the patch is a whole-project migration. Baseline evidence (round 0, baseline probes, plan) is in `.github/.pipeline-context/version-migration/issue-001`; the report's §0–§6 list predicted vs. actual impact.
- **06** — build and test the patched project on the **target** JDK 21 (point `MIGRATION_JDK_21` at it); the source JDK is 17.
- **07** — include the migration report and summary in the audit trail; a Migration Status other than PASS must be visible in the verdict.

## How to apply this patch

From the repository root, after a Cleared verdict: `git apply docs/agent_output/04-remediation/fix_ISSUE-001.diff`.
