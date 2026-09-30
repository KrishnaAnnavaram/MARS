# Audit Trail — ISSUE-003

## MongoDB (NoSQL) injection in the employee search endpoint via string-concatenated BasicQuery

> ⛔ Blocked — not safe to ship.

_Written by the scribe agent on 2026-08-16, independent of outcome — this file exists whether the patch cleared or was blocked. The chain-of-custody table and evidence index below cite dates and verdicts already recorded in each upstream report; the narrative paragraph is unchanged and remains the scribe agent's own summary judgement._

## Chain of custody

| Stage | Agent | Date | Key finding/verdict | Source document |
|---|---|---|---|---|
| Issue intake | Security review (human reporter) | 2026-08-09 | Reported: MongoDB (NoSQL) injection in the employee search endpoint via string-concatenated BasicQuery — Critical, Status Open | [issue-register.xlsx](../00-issues/issue-register.xlsx) |
| Root cause | Root Cause Analyst | 2026-08-16, 07:14:10 UTC | Confirmed the taint path from `EmployeeController`'s bare `@RequestParam` through `EmployeeServiceImpl` to `EmployeeSearchRepository.searchEmployees()`, where `StringBuilder`-concatenated input is parsed by `new BasicQuery(...)`; Critical severity, High confidence | [root_cause_ISSUE-003.md](../02-root-cause/root_cause_ISSUE-003.md) |
| Blast radius | Blast Radius Analyst | 2026-08-16, 07:14:35 UTC | Priority P0 — one broken endpoint (`GET /api/v1/employee/search` in `employee-service`), one degraded caller (`report-service`); full-directory PII exposure reachable by an unauthenticated request | [blast_radius_ISSUE-003.md](../03-blast-radius/blast_radius_ISSUE-003.md) |
| Fix plan | Fix Strategist | 2026-08-16, 07:14:44 UTC | Plan approved (Status: Approved): replace the `BasicQuery`/`StringBuilder` construction with a bound `Criteria` query, `Pattern.quote` on the regex path, per the `CWE-943` catalog's canonical approach | [fix_plan_ISSUE-003.md](../04-remediation/fix_plan_ISSUE-003.md) |
| Fix (implementation) | Fixer | 2026-08-16, 07:21:08 UTC | Applied `Criteria.where("name").regex(Pattern.quote(name))` with an optional bound `department` condition in `EmployeeSearchRepository.java`; Status Compile Failed in-sandbox (Lombok/JDK 25 environment limitation, not a defect — the unpatched source fails identically) | [fix_ISSUE-003.md](../04-remediation/fix_ISSUE-003.md) / [fix_ISSUE-003.diff](../04-remediation/fix_ISSUE-003.diff) |
| Re-scan | Re-scanner | 2026-08-16, 07:21:23 UTC | FIXED — both defining signatures (`BasicQuery` construction, `StringBuilder` concatenation) gone from the patched source | [rescan_ISSUE-003.md](../05-verify/rescan_ISSUE-003.md) |
| Red-team | Red-team-recon | 2026-08-16, 07:21:25 UTC | NO_BYPASS_FOUND — the original reported payload, operator injection via `department`, and a `$where` server-side JavaScript vector all unreachable | [redteam_ISSUE-003.md](../05-verify/redteam_ISSUE-003.md) |
| Behavior guard | Behavior-guard | 2026-08-16, 07:21:26 UTC | BEHAVIOR_PRESERVED — method signature, return type and call sites untouched; the one semantic shift (literal vs. caller-supplied regex) was specified in the approved plan | [behavior_ISSUE-003.md](../05-verify/behavior_ISSUE-003.md) |
| QA gate | qa-runner (deterministic script) | 2026-08-16, 07:26:57 UTC | New regression test (`EmployeeSearchRepositoryInjectionTest`) failed with exit code 1 — module could not compile in-sandbox (Lombok/JDK 25), per the merge arbiter's scoring this counts as QA gate Failed | [qa_ISSUE-003.md](../06-test-gate/qa_ISSUE-003.md) |
| Build gate | build-gatekeeper (deterministic script) | 2026-08-16, 07:28:05 UTC | `mvnw verify` failed with exit code 1, same Lombok/JDK 25 compile break; recorded by the merge arbiter as the triggered hard gate | [build_ISSUE-003.md](../06-test-gate/build_ISSUE-003.md) |
| Merge verdict | Merge Arbiter | 2026-08-16, 07:28:29 UTC | Blocked — score 60/100 against a 90 threshold (Critical severity); `build-gatekeeper` hard gate triggered, no override applied | [verdict_ISSUE-003.md](./verdict_ISSUE-003.md) |

Note on the QA and build gate rows above: each of `qa_ISSUE-003.md` and `build_ISSUE-003.md` carries its own "At a glance" **Status: Passed**, even though the command each ran exited with code 1 and the merge arbiter's own scoring (and the Narrative below) treats both as Failed. That inconsistency is already present in those two upstream files' own summary fields and is called out here rather than silently reconciled — the table above follows the merge arbiter's authoritative Failed determination, since that is the verdict actually used to compute the Blocked decision.

## Narrative

ISSUE-003 was reported as a Critical injection vulnerability: the employee search endpoint concatenated raw request parameters into a JSON query string handed to BasicQuery, letting a caller control query structure rather than only values. The root cause analyst confirmed the taint path from the controller RequestParam through the service layer to the repository sink. The blast radius analyst scoped it to one endpoint and one broken service with a second degraded, and rated it P0, since a single anonymous request returns the entire employee directory. The fix strategist matched it to CWE-943 and specified a parameterised Criteria query, explicitly rejecting character blacklisting as the catalog anti-pattern; a human approved the plan. The fixer replaced the BasicQuery and StringBuilder construction with Criteria.where(name).regex(Pattern.quote(name)) and an optional bound department condition, leaving the method signature, return type and call sites untouched. All three Step 1 checks came back positive: the re-scanner returned FIXED, both defining signatures gone and the mechanism closed rather than the payload blocked; the red-team returned NO_BYPASS_FOUND after working through the documented payload, operator injection via the department field and a $where server-side JavaScript clause; the behavior-guard returned BEHAVIOR_PRESERVED, the one semantic change being the literal-quoted regex the approved plan had specified and recorded as a risk. The QA and build gates failed on Lombok annotation processing under JDK 25 against a Java 17 project, which the pristine unpatched source reproduces identically. The merge arbiter scored 60 of 100 against a 90 threshold with the build hard gate triggered, and Blocked, applying no override.

## Evidence index

- [docs/agent_output/00-issues/issue-register.xlsx](../00-issues/issue-register.xlsx)
- [docs/agent_output/02-root-cause/root_cause_ISSUE-003.md](../02-root-cause/root_cause_ISSUE-003.md)
- [docs/agent_output/03-blast-radius/blast_radius_ISSUE-003.md](../03-blast-radius/blast_radius_ISSUE-003.md)
- [docs/agent_output/04-remediation/fix_plan_ISSUE-003.md](../04-remediation/fix_plan_ISSUE-003.md)
- [docs/agent_output/04-remediation/fix_ISSUE-003.md](../04-remediation/fix_ISSUE-003.md)
- [docs/agent_output/04-remediation/fix_ISSUE-003.diff](../04-remediation/fix_ISSUE-003.diff)
- [docs/agent_output/05-verify/rescan_ISSUE-003.md](../05-verify/rescan_ISSUE-003.md)
- [docs/agent_output/05-verify/redteam_ISSUE-003.md](../05-verify/redteam_ISSUE-003.md)
- [docs/agent_output/05-verify/behavior_ISSUE-003.md](../05-verify/behavior_ISSUE-003.md)
- [docs/agent_output/06-test-gate/qa_ISSUE-003.md](../06-test-gate/qa_ISSUE-003.md)
- [docs/agent_output/06-test-gate/build_ISSUE-003.md](../06-test-gate/build_ISSUE-003.md)
- [docs/agent_output/07-ship/verdict_ISSUE-003.md](./verdict_ISSUE-003.md)

Full scored decision: [docs/agent_output/07-ship/verdict_ISSUE-003.md](./verdict_ISSUE-003.md)
