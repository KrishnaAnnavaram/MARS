# Audit Trail — ISSUE-002

## Employee PII and payroll data are exposed to unauthenticated callers and written to application logs

> ⛔ Blocked — not safe to ship.

_Written by the scribe agent on 2026-08-16, independent of outcome — this file exists whether the patch cleared or was blocked._

## Chain of custody

| Stage | Agent | Date | Key finding / verdict | Source document |
|---|---|---|---|---|
| Issue intake | Issue Register | Reported 2026-08-09 | Security review flagged PII/payroll exposure and access control; Status Open | [docs/agent_output/00-issues/issue-register.xlsx](../00-issues/issue-register.xlsx) |
| Root cause | Root Cause Analyst | 2026-08-16 (evidence collected 07:14:01.429Z) | No module declares an authentication mechanism; every REST handler dispatches for anonymous callers — CWE-306, Critical severity, High confidence | [docs/agent_output/02-root-cause/root_cause_ISSUE-002.md](../02-root-cause/root_cause_ISSUE-002.md) |
| Blast radius | Blast Radius Analyst | 2026-08-16 (reach measured 07:14:29.442Z) | P0 — six services broken, 10 of 10 endpoints down, complete HR dataset including salaries and home addresses downloadable with no credentials | [docs/agent_output/03-blast-radius/blast_radius_ISSUE-002.md](../03-blast-radius/blast_radius_ISSUE-002.md) |
| Fix plan | Fix Strategist | 2026-08-16 (context collected 07:14:44.126Z) | CWE-306 canonical filter-chain approach across all four affected modules; Status Approved (human-approved, six files, high confidence) | [docs/agent_output/04-remediation/fix_plan_ISSUE-002.md](../04-remediation/fix_plan_ISSUE-002.md) |
| Fix (implementation) | Fixer | 2026-08-16 (verification run 07:21:01.056Z) | Status Compile Failed (environmental); patch as written touches only 3 files — `src/employee-service/pom.xml`, `EmployeeController.java`, `application.properties` — securing employee-service only, versus the plan's 6-file, 4-module scope | [docs/agent_output/04-remediation/fix_ISSUE-002.md](../04-remediation/fix_ISSUE-002.md) |
| Re-scan | re-scanner | 2026-08-16 (facts collected 07:21:23.560Z) | STILL_VULNERABLE — 2 of 4 signatures still present; report-service, sheduler-service and department-service remain unauthenticated | [docs/agent_output/05-verify/rescan_ISSUE-002.md](../05-verify/rescan_ISSUE-002.md) |
| Red-team | red-team-recon | 2026-08-16 (facts collected 07:21:24.748Z) | BYPASS_FOUND — 2 bypasses: querying an unsecured sibling service, and authenticating with the newly-introduced hardcoded default credential (CWE-798) | [docs/agent_output/05-verify/redteam_ISSUE-002.md](../05-verify/redteam_ISSUE-002.md) |
| Behavior guard | behavior-guard | 2026-08-16 (facts collected 07:21:25.713Z) | BEHAVIOR_CHANGED — wider than plan in 1 place (new hardcoded credential, unmigrated internal callers), narrower in 5 | [docs/agent_output/05-verify/behavior_ISSUE-002.md](../05-verify/behavior_ISSUE-002.md) |
| QA gate | qa-runner | 2026-08-16 (gate run 07:26:52.511Z) | Failed — new `EmployeeControllerAuthTest` could not run: exit code 1, JDK 25 / Lombok annotation-processing mismatch against the Java-17-targeted project† | [docs/agent_output/06-test-gate/qa_ISSUE-002.md](../06-test-gate/qa_ISSUE-002.md) |
| Build gate | build-gatekeeper script | 2026-08-16 (gate run 07:27:55.976Z) | Failed — `mvnw verify` exit code 1, same JDK 25 / Lombok mismatch† | [docs/agent_output/06-test-gate/build_ISSUE-002.md](../06-test-gate/build_ISSUE-002.md) |
| Merge verdict | merge-arbiter | 2026-08-16 (score computed 07:28:29.190Z) | Blocked — 0/100 against a 90 threshold (Critical severity); re-scanner and build-gatekeeper hard gates both triggered; no override applied | [docs/agent_output/07-ship/verdict_ISSUE-002.md](./verdict_ISSUE-002.md) |

† `qa_ISSUE-002.md` and `build_ISSUE-002.md` each carry the deterministic gate result (FAIL, exit code 1) in their own "Gate result" / "Build result" sections, and `verdict_ISSUE-002.md`'s "Upstream reports" table records both as `Failed`. Their own "At a glance" tables' `Status` cells read `Passed`, which is inconsistent with both of those and is left unedited here since it is pre-existing content in files outside the set this pass touches for verdict changes; this table follows the deterministic gate result and the merge arbiter's own tally.

## Narrative

ISSUE-002 was reported as a Critical access-control vulnerability with two exposure channels: every REST endpoint answering anonymously across six services, and PII plus salary written into application logs. The root cause analyst confirmed no module declared any authentication mechanism. The blast radius analyst rated it P0, six services broken, with a complete HR dataset including salaries and home addresses downloadable without credentials. The fix strategist matched it to CWE-306 and specified a framework-level filter chain across all affected modules, explicitly rejecting per-endpoint patching because the catalog flags that as an anti-pattern; a human approved it. The fixer's patch secured employee-service only. All three Step 1 checks found problems: the re-scanner returned STILL_VULNERABLE, since report-service still serves the payroll spreadsheet and sheduler-service still serves every employee record anonymously; the red-team returned BYPASS_FOUND, confirming an attacker simply queries a different service, and additionally flagged a static HTTP Basic credential with a default password committed in application.properties as a new CWE-798 exposure introduced by the fix; the behavior-guard returned BEHAVIOR_CHANGED, noting the patch is wider than the plan in one place and narrower in five others. The QA and build gates failed on the JDK 25 versus Java 17 toolchain mismatch. The merge arbiter scored 0 of 100 against a 90 threshold with both the re-scanner and build hard gates triggered, and Blocked, applying no override.

## Evidence index

- [docs/agent_output/00-issues/issue-register.xlsx](../00-issues/issue-register.xlsx) — original issue report
- [docs/agent_output/01-architecture/architecture.md](../01-architecture/architecture.md) — architecture reference cited by root cause and blast radius
- [docs/agent_output/02-root-cause/root_cause_ISSUE-002.md](../02-root-cause/root_cause_ISSUE-002.md) — root cause analysis
- [docs/agent_output/03-blast-radius/blast_radius_ISSUE-002.md](../03-blast-radius/blast_radius_ISSUE-002.md) — blast radius analysis
- [docs/agent_output/04-remediation/fix_plan_ISSUE-002.md](../04-remediation/fix_plan_ISSUE-002.md) — approved fix plan
- [docs/agent_output/04-remediation/fix_ISSUE-002.md](../04-remediation/fix_ISSUE-002.md) — fixer's report
- [docs/agent_output/04-remediation/fix_ISSUE-002.diff](../04-remediation/fix_ISSUE-002.diff) — the applied-in-sandbox patch
- [docs/agent_output/05-verify/rescan_ISSUE-002.md](../05-verify/rescan_ISSUE-002.md) — re-scan verdict
- [docs/agent_output/05-verify/redteam_ISSUE-002.md](../05-verify/redteam_ISSUE-002.md) — red-team recon
- [docs/agent_output/05-verify/behavior_ISSUE-002.md](../05-verify/behavior_ISSUE-002.md) — behavior guard
- [docs/agent_output/06-test-gate/qa_ISSUE-002.md](../06-test-gate/qa_ISSUE-002.md) — QA gate
- [docs/agent_output/06-test-gate/build_ISSUE-002.md](../06-test-gate/build_ISSUE-002.md) — build gate
- [docs/agent_output/07-ship/verdict_ISSUE-002.md](./verdict_ISSUE-002.md) — scored merge verdict
- [.claude/skills/04a-fix-strategist/catalog/cwe-patterns.json](../../../.claude/skills/04a-fix-strategist/catalog/cwe-patterns.json) — CWE-306 / CWE-798 catalog entries used to check the fix and the red-team findings against known anti-patterns

Full scored decision: [docs/agent_output/07-ship/verdict_ISSUE-002.md](./verdict_ISSUE-002.md)
