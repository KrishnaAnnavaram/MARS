# Audit Trail — ISSUE-002

## Employee PII and payroll data are exposed to unauthenticated callers and written to application logs

> ⛔ Blocked — not safe to ship.

_Written by the scribe agent on 2026-08-16, independent of outcome — this file exists whether the patch cleared or was blocked._

## Chain of custody

ISSUE-002 was reported as a Critical access-control vulnerability with two exposure channels: every REST endpoint answering anonymously across six services, and PII plus salary written into application logs. The root cause analyst confirmed no module declared any authentication mechanism. The blast radius analyst rated it P0, six services broken, with a complete HR dataset including salaries and home addresses downloadable without credentials. The fix strategist matched it to CWE-306 and specified a framework-level filter chain across all affected modules, explicitly rejecting per-endpoint patching because the catalog flags that as an anti-pattern; a human approved it. The fixer's patch secured employee-service only. All three Step 1 checks found problems: the re-scanner returned STILL_VULNERABLE, since report-service still serves the payroll spreadsheet and sheduler-service still serves every employee record anonymously; the red-team returned BYPASS_FOUND, confirming an attacker simply queries a different service, and additionally flagged a static HTTP Basic credential with a default password committed in application.properties as a new CWE-798 exposure introduced by the fix; the behavior-guard returned BEHAVIOR_CHANGED, noting the patch is wider than the plan in one place and narrower in five others. The QA and build gates failed on the JDK 25 versus Java 17 toolchain mismatch. The merge arbiter scored 0 of 100 against a 90 threshold with both the re-scanner and build hard gates triggered, and Blocked, applying no override.

Full scored decision: [docs/agent_output/07-ship/verdict_ISSUE-002.md](./verdict_ISSUE-002.md)
