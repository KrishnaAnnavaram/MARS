# Audit Trail — ISSUE-004

## Outdated Apache POI (poi-ooxml 5.0.0) dependency exposes the employee Excel-upload endpoint to a known OOXML parsing vulnerability (CVE-2025-31672)

> ⛔ Blocked — not safe to ship.

_Written by the scribe agent on 2026-08-31, independent of outcome — this file exists whether the patch cleared or was blocked._

## Chain of custody

| Stage | Agent | Date | Key finding/verdict | Source document |
|---|---|---|---|---|
| Issue intake | Dependency audit | 2026-08-30 | `src/employee-service/pom.xml` pins `org.apache.poi:poi-ooxml` at 5.0.0, affected by CVE-2025-31672 | [issue-register.xlsx](../../00-issues/issue-register.xlsx) |
| Root cause | Root Cause Analyst | 2026-08-31 | The pinned 5.0.0 version parses uploads reaching `POST /api/v1/employee/excelUpload`, with only a spoofable Content-Type check (`ExcelUploadImpl.isValidExcelFile`) in front of it | [root_cause_ISSUE-004.md](../02-root-cause/root_cause_ISSUE-004.md) |
| Blast radius | Blast Radius Analyst | 2026-08-31 | Scope `endpoint`, P3 — confined to the one upload path; every other employee-service endpoint and service unaffected | [blast_radius_ISSUE-004.md](../03-blast-radius/blast_radius_ISSUE-004.md) |
| Fix plan | Fix Strategist | 2026-08-31 | Approved — bump to the advisory's minimum fixed version (5.4.0), not "latest"; structural upload validation explicitly rejected as out-of-scope follow-up | [fix_plan_ISSUE-004.md](../04-remediation/fix_plan_ISSUE-004.md) |
| Fix (implementation) | Dependency Upgrader | 2026-08-31 | 1 file changed (`src/employee-service/pom.xml`, single `<version>` line), matches plan; declared-version check passed (5.4.0); compile step Compile Failed | [fix_ISSUE-004.md](../04-remediation/fix_ISSUE-004.md) |
| Re-scan | re-scanner agent | 2026-08-31 | **FIXED** — patched pom.xml declares 5.4.0, outside the advisory's affected range | [rescan_ISSUE-004.md](../05-verify/rescan_ISSUE-004.md) |
| Red-team | red-team-recon agent | 2026-08-31 | **NO_BYPASS_FOUND** — checked against every CWE-1104 catalog anti-pattern; named the pre-existing Content-Type-only validation as a residual, plan-acknowledged gap | [redteam_ISSUE-004.md](../05-verify/redteam_ISSUE-004.md) |
| Behavior guard | behavior-guard agent | 2026-08-31 | **BEHAVIOR_PRESERVED** — diff is a single version string in one non-code file | [behavior_ISSUE-004.md](../05-verify/behavior_ISSUE-004.md) |
| QA gate | qa-runner agent | 2026-08-31 | **Failed** — pre-existing JDK25/Lombok compile mismatch inside `ExcelUploadImpl.java`'s calls to Lombok-generated `Employee` setters, a file this patch never touches | [qa_ISSUE-004.md](../06-test-gate/qa_ISSUE-004.md) |
| Build gate | scripts/render-build-report.js | 2026-08-31 | **Failed** — same broader JDK25/Lombok toolchain mismatch, in `EmployeeController.java`, unrelated to this patch's diff | [build_ISSUE-004.md](../06-test-gate/build_ISSUE-004.md) |
| Merge verdict | merge arbiter | 2026-08-31 | Score 60/100 vs. 75 threshold (Medium severity), build-gatekeeper hard gate tripped, decision **Blocked** | [verdict_ISSUE-004.md](./verdict_ISSUE-004.md) |

## Narrative

**Issue** — reported as Medium severity: [docs/agent_output/00-issues/issue-register.xlsx](../../docs/agent_output/00-issues/issue-register.xlsx) (ISSUE-004 row) documents `src/employee-service/pom.xml` pinning `org.apache.poi:poi-ooxml` at 5.0.0, affected by CVE-2025-31672 (no uniqueness check on ZIP entry paths when loading an OOXML archive), exercised directly on caller-uploaded bytes via `ExcelUploadImpl.getEmployeeDataFromExcel`.

**Root cause** — [docs/agent_output/02-root-cause/root_cause_ISSUE-004.md](../../docs/agent_output/02-root-cause/root_cause_ISSUE-004.md): the pinned 5.0.0 version is what parses uploads reaching `POST /api/v1/employee/excelUpload`, with only a spoofable client-supplied Content-Type check (`ExcelUploadImpl.isValidExcelFile`) in front of it.

**Blast radius** — [docs/agent_output/03-blast-radius/blast_radius_ISSUE-004.md](../../docs/agent_output/03-blast-radius/blast_radius_ISSUE-004.md): scope `endpoint`, P3 — confined to the one upload path; every other employee-service endpoint and every other service is unaffected.

**Fix plan** — [docs/agent_output/04-remediation/fix_plan_ISSUE-004.md](../../docs/agent_output/04-remediation/fix_plan_ISSUE-004.md), Approved. Cites the CWE-1104 catalog pattern ("Use of Unmaintained Third Party Components"): bump to the advisory's own minimum fixed version (5.4.0), not "latest"; explicitly rejected adding structural upload validation in this diff as a separate, out-of-scope follow-up.

**Fix drafted** — [docs/agent_output/04-remediation/fix_ISSUE-004.md](../../docs/agent_output/04-remediation/fix_ISSUE-004.md), 1 file changed (`src/employee-service/pom.xml`, a single `<version>` line), matches the plan. The Dependency Upgrader's own declared-version check passed (5.4.0, matching target); its compile step reports Compile Failed — see build note below.

**Step 1 verification** (parallel, static/reasoning): re-scan [FIXED](../../docs/agent_output/05-verify/rescan_ISSUE-004.md) — the patched pom.xml declares 5.4.0, outside the advisory's affected range; red-team [NO_BYPASS_FOUND](../../docs/agent_output/05-verify/redteam_ISSUE-004.md) — checked the fix against every anti-pattern in the CWE-1104 catalog entry and found none, though it named a real, plan-acknowledged residual gap (no structural upload validation independent of the pinned version); behavior-guard [BEHAVIOR_PRESERVED](../../docs/agent_output/05-verify/behavior_ISSUE-004.md) — the diff is a single version string in one non-code file.

**Step 2 gates**: QA [Failed](../../docs/agent_output/06-test-gate/qa_ISSUE-004.md) and build gate [Failed](../../docs/agent_output/06-test-gate/build_ISSUE-004.md) — both fail on the same pre-existing, project-wide JDK25/Lombok compile mismatch already documented for ISSUE-001/002/003. The compiler errors are entirely inside `ExcelUploadImpl.java`'s calls to Lombok-generated `Employee` setters — a file this dependency-only patch never touches (only `pom.xml` and a newly-added test file were changed).

**Score and decision** — [docs/agent_output/07-ship/verdict_ISSUE-004.md](../../docs/agent_output/07-ship/verdict_ISSUE-004.md): computed score 60/100 against a 75 threshold (Medium severity), build-gatekeeper hard gate tripped, decision **Blocked**. Under this pipeline's current arbitration rules, an override may only ever move a computed `Cleared` decision to the more conservative `Blocked` — it can never clear a computed `Blocked` decision, regardless of evidence. No override was applied; the computed Blocked decision stands as-is.

## Evidence index

- [docs/agent_output/00-issues/issue-register.xlsx](../../00-issues/issue-register.xlsx)
- [docs/agent_output/02-root-cause/root_cause_ISSUE-004.md](../02-root-cause/root_cause_ISSUE-004.md)
- [docs/agent_output/03-blast-radius/blast_radius_ISSUE-004.md](../03-blast-radius/blast_radius_ISSUE-004.md)
- [docs/agent_output/04-remediation/fix_plan_ISSUE-004.md](../04-remediation/fix_plan_ISSUE-004.md)
- [docs/agent_output/04-remediation/fix_ISSUE-004.md](../04-remediation/fix_ISSUE-004.md)
- [docs/agent_output/04-remediation/fix_ISSUE-004.diff](../04-remediation/fix_ISSUE-004.diff)
- [docs/agent_output/05-verify/rescan_ISSUE-004.md](../05-verify/rescan_ISSUE-004.md)
- [docs/agent_output/05-verify/redteam_ISSUE-004.md](../05-verify/redteam_ISSUE-004.md)
- [docs/agent_output/05-verify/behavior_ISSUE-004.md](../05-verify/behavior_ISSUE-004.md)
- [docs/agent_output/06-test-gate/qa_ISSUE-004.md](../06-test-gate/qa_ISSUE-004.md)
- [docs/agent_output/06-test-gate/build_ISSUE-004.md](../06-test-gate/build_ISSUE-004.md)
- [docs/agent_output/07-ship/verdict_ISSUE-004.md](./verdict_ISSUE-004.md)

Full scored decision: [docs/agent_output/07-ship/verdict_ISSUE-004.md](./verdict_ISSUE-004.md)
