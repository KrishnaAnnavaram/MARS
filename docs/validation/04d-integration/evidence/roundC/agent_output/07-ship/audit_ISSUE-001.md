# Audit Trail — ISSUE-001

## Service runs on Spring Boot 3.5.0 / Java 17, a platform line past its open-source support window — must move to Spring Boot 4.1.1 on Java 21

> ⛔ Blocked — not safe to ship.

_Written by the scribe agent on 2026-10-01, independent of outcome — this file exists whether the patch cleared or was blocked._

## Chain of custody

**1. Issue.** ISSUE-001 reports that `spring-boot-migration-demo` runs on Spring Boot 3.5.0 / Java 17 ([issue register](../../../docs/agent_output/00-issues/issue-register.xlsx)). That platform line is past its open-source support window, and the organisation standard is Spring Boot 4.1.1 on Java 21. The acceptance bar is unchanged API behaviour: same endpoints, status codes, payloads and authentication boundary ([issue register](../../../docs/agent_output/00-issues/issue-register.xlsx)).

**2. Root cause.** The Root Cause Analyst traced the defect to the build files, not to a runtime code path ([root cause](../../../docs/agent_output/02-root-cause/root_cause_ISSUE-001.md)). The out-of-support platform is declared in four places:
- pom.xml:19 sets spring-boot-starter-parent 3.5.0;
- pom.xml:24 sets java.version 17;
- pom.xml:125-126 sets compiler source/target 17;
- Dockerfile:4 uses eclipse-temurin:17-jre.

Severity is Medium and confidence High ([root cause](../../../docs/agent_output/02-root-cause/root_cause_ISSUE-001.md)).

**3. Blast radius.** The Blast Radius Analyst found an exposure, not an outage: priority P3, 0 of 7 endpoints down, and no other service affected ([blast radius](../../../docs/agent_output/03-blast-radius/blast_radius_ISSUE-001.md)).

**4. Fix plan and approval.** The Fix Strategist chose CWE-1104 and the `VERSION_MIGRATION` fix type, routed to the 04D version-migration skill. The target was exactly Spring Boot 3.5.0 -> 4.1.1 and Java 17 -> 21 ([fix plan](../../../docs/agent_output/04-remediation/fix_plan_ISSUE-001.md)). The plan was approved as TEST_AUTO_APPROVED, a programmatic approval for controlled pipeline validation. It does not replace the production human approval requirement ([fix plan](../../../docs/agent_output/04-remediation/fix_plan_ISSUE-001.md)).

**5. Migration (04D).**
- **Status and run.** The fix report records Status Compiled and Migration Status **PARTIAL PASS** for run `issue-001-20261001T033403Z` ([fix report](../../../docs/agent_output/04-remediation/fix_ISSUE-001.md), [MIGRATION_SUMMARY.md](../../../docs/agent_output/04-remediation/migration-runs/issue-001-20261001T033403Z/MIGRATION_SUMMARY.md)). The migration report's headline badge reads 'Passed on the final build'. That badge refers to round 3's build, not the overall Migration Status ([migration report](../../../docs/agent_output/04-remediation/migration_issue-001.md)).
- **Versions.** Source was Spring Boot 3.5.0 on JDK 17.0.20.1. Target was Spring Boot 4.1.1 on JDK 21.0.11, and the final build declares 4.1.1, which matches the request ([MIGRATION_SUMMARY.md](../../../docs/agent_output/04-remediation/migration-runs/issue-001-20261001T033403Z/MIGRATION_SUMMARY.md)).
- **Transformations.** One previewed OpenRewrite recipe (`mars.migration.SpringBoot3To4Curated`, rewrite-spring 6.37.1) changed 6 files. Two of its proposals were rejected: removing the @JsonFormat error-timestamp pattern, and un-pinning only the Testcontainers core artifact ([migration report §2.4](../../../docs/agent_output/04-remediation/migration_issue-001.md)).
- **Build rounds.** There were 4 rounds ([migration report §2.1](../../../docs/agent_output/04-remediation/migration_issue-001.md)):
  - R0: JDK 17 baseline, tests failed;
  - R1: test-compile on JDK 21, passed;
  - R2: package with tests, tests failed;
  - R3: package with tests skipped, passed.
- **Tests.** Before: 19 run, 17 passed, 2 failed. After: 19 run, 17 passed, 2 failed. The 2 failures are the same pre-existing ones in both: EmployeeControllerTest.testActuatorHealth_Public and testActuatorInfo_Public, which get a 404 in the @WebMvcTest slice. The migration introduced no new failures ([MIGRATION_SUMMARY.md](../../../docs/agent_output/04-remediation/migration-runs/issue-001-20261001T033403Z/MIGRATION_SUMMARY.md)).
- **Behaviour comparison.** 19 probes were run before and after. 2 were identical, 17 differed only in the body, and 0 changed status. The security boundary was preserved ([migration report §6](../../../docs/agent_output/04-remediation/migration_issue-001.md)).
- **Open blocking condition (C8).** Some framework-owned responses changed shape: the 401 timestamp format, the WWW-Authenticate charset parameter, a ProblemDetail without `type`, and new health components. A human must confirm that API consumers accept these, and until then the result is PARTIAL PASS ([migration report §0.2](../../../docs/agent_output/04-remediation/migration_issue-001.md)).
- **Not verified by 04D** ([migration report §6](../../../docs/agent_output/04-remediation/migration_issue-001.md)):
  - the container image was not built or run;
  - the runtime was not probed against PostgreSQL (the probes used H2);
  - /h2-console was not probed;
  - which mapper bean Spring MVC actually uses was not instrumented.
- **Manual follow-ups** ([migration report §8](../../../docs/agent_output/04-remediation/migration_issue-001.md)):
  - confirm consumers and monitors accept the framework-owned response changes;
  - build and run the eclipse-temurin:21-jre image, and update CI/deployment descriptors that pin Java 17;
  - run the service once against PostgreSQL on Java 21;
  - plan the Testcontainers 2.x move separately;
  - confirm the Spring Boot 3.5.x support end date and the rewrite-spring Moderne Source Available License use.

**6. Independent verification (Phase C Step 1).**
- **Re-scan: FIXED, High confidence.** None of the 4 signatures remain. In its own worktree on JDK 21.0.11, the resolved tree has all 50 org.springframework.boot artifacts at 4.1.1 ([re-scan](../../../docs/agent_output/05-verify/rescan_ISSUE-001.md)).
- **Red-team: NO_BYPASS_FOUND, Medium confidence.** It tried 11 vectors and none succeeded. Two stayed uncertain: the new anonymous liveness/readiness health endpoints, and the fact that JacksonConfig no longer drives HTTP serialization ([red-team](../../../docs/agent_output/05-verify/redteam_ISSUE-001.md)).
- **Behavior guard: BEHAVIOR_CHANGED, High confidence.** It found 8 out-of-scope changes ([behavior](../../../docs/agent_output/05-verify/behavior_ISSUE-001.md)):
  - the WWW-Authenticate charset parameter;
  - the ProblemDetail without `type`, with reordered keys;
  - new health components and groups, with new anonymous endpoints;
  - a different metrics name list;
  - unknown request properties now accepted, where they used to get a 400;
  - trailing JSON tokens now rejected;
  - the H2 console no longer served;
  - the app's JacksonConfig bean no longer the Spring MVC mapper.

**7. Test gates (Phase C Step 2).**
- **QA: Passed.** The new MigratedRuntimeContractTest exited 0 in an isolated worktree with no live dependency ([QA](../../../docs/agent_output/06-test-gate/qa_ISSUE-001.md)). It covers:
  - Jackson 3 ISO dates and NON_NULL;
  - the retained @JsonFormat pattern;
  - DatabaseHealthIndicator UP/DOWN/UNKNOWN.
- **Build: Failed.** `mvn verify` on JDK 21 exited 1 with 19 tests run and 2 failures ([build](../../../docs/agent_output/06-test-gate/build_ISSUE-001.md)). The 2 failures are the same actuator-slice tests that 04D recorded as failing on the untouched 3.5.0 baseline ([migration report §2.2](../../../docs/agent_output/04-remediation/migration_issue-001.md)). The build gate's dependency-tree diff reports no changes ([build](../../../docs/agent_output/06-test-gate/build_ISSUE-001.md)). That is not consistent with the resolved-tree move the re-scan observed ([re-scan](../../../docs/agent_output/05-verify/rescan_ISSUE-001.md)), so the diff is not usable evidence for this migration.

**8. Merge verdict.** compute-score.js scored 70/100 against a Medium-severity threshold of 75: red-team 30, behavior 0, QA 40 ([verdict](../../../docs/agent_output/07-ship/verdict_ISSUE-001.md)). The build-gatekeeper hard gate fired. The version-migration hard gate was evaluated against Migration Status PARTIAL PASS and did not fire. No override was applied ([verdict](../../../docs/agent_output/07-ship/verdict_ISSUE-001.md)).

Full scored decision: [docs/agent_output/07-ship/verdict_ISSUE-001.md](./verdict_ISSUE-001.md)
