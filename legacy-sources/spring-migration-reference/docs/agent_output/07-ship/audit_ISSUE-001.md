# Audit Trail — ISSUE-001

## Unbounded repository findAll() reads whole collections into memory across multiple services

> ⛔ Blocked — not safe to ship.

_Written by the scribe agent on 2026-08-16, independent of outcome — this file exists whether the patch cleared or was blocked._

## Chain of custody

ISSUE-001 was reported as a High-severity availability vulnerability: two services read the whole employee collection into memory with no limit. The root cause analyst confirmed both call sites and traced them through the graph to the endpoints and the scheduled job that reach them. The blast radius analyst scoped it to two services and two endpoints and rated it P2 — nothing failing at today's data volume, but a whole-service outage that arrives without warning as the workforce grows. The fix strategist matched it to CWE-770 and specified paginated reads with a server-enforced maximum page size, iterating until pages are exhausted; a human approved that plan. The fixer drafted a patch that caps the page size at 500 but reads only the first page. Verification split three ways: the re-scanner returned FIXED, judging the unbounded allocation genuinely closed despite the literal findAll( token still matching; the red-team returned NO_BYPASS_FOUND, since the cap is a compiled-in constant with no caller-facing parameter; the behavior-guard returned BEHAVIOR_CHANGED, because reading only page 0 silently truncates results beyond 500 and the plan had called for full pagination. The QA and build gates both failed on Lombok annotation processing under JDK 25 against a Java 17 project — a failure the pristine unpatched source reproduces exactly, so it reflects the toolchain rather than the patch. The merge arbiter scored 30 of 100 against an 85 threshold with the build hard gate triggered, and Blocked, applying no override.

Full scored decision: [docs/agent_output/07-ship/verdict_ISSUE-001.md](./verdict_ISSUE-001.md)
