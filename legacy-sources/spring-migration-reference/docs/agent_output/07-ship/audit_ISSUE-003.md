# Audit Trail — ISSUE-003

## MongoDB (NoSQL) injection in the employee search endpoint via string-concatenated BasicQuery

> ⛔ Blocked — not safe to ship.

_Written by the scribe agent on 2026-08-16, independent of outcome — this file exists whether the patch cleared or was blocked._

## Chain of custody

ISSUE-003 was reported as a Critical injection vulnerability: the employee search endpoint concatenated raw request parameters into a JSON query string handed to BasicQuery, letting a caller control query structure rather than only values. The root cause analyst confirmed the taint path from the controller RequestParam through the service layer to the repository sink. The blast radius analyst scoped it to one endpoint and one broken service with a second degraded, and rated it P0, since a single anonymous request returns the entire employee directory. The fix strategist matched it to CWE-943 and specified a parameterised Criteria query, explicitly rejecting character blacklisting as the catalog anti-pattern; a human approved the plan. The fixer replaced the BasicQuery and StringBuilder construction with Criteria.where(name).regex(Pattern.quote(name)) and an optional bound department condition, leaving the method signature, return type and call sites untouched. All three Step 1 checks came back positive: the re-scanner returned FIXED, both defining signatures gone and the mechanism closed rather than the payload blocked; the red-team returned NO_BYPASS_FOUND after working through the documented payload, operator injection via the department field and a $where server-side JavaScript clause; the behavior-guard returned BEHAVIOR_PRESERVED, the one semantic change being the literal-quoted regex the approved plan had specified and recorded as a risk. The QA and build gates failed on Lombok annotation processing under JDK 25 against a Java 17 project, which the pristine unpatched source reproduces identically. The merge arbiter scored 60 of 100 against a 90 threshold with the build hard gate triggered, and Blocked, applying no override.

Full scored decision: [docs/agent_output/07-ship/verdict_ISSUE-003.md](./verdict_ISSUE-003.md)
