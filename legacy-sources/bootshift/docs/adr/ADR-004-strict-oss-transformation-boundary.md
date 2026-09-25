# ADR-004: The strict-OSS transformation boundary

**Status:** Accepted
**Date:** 2026-09-10

## Context

Most of the mature deterministic Spring migration automation lives in the OpenRewrite Spring recipe
estate, which is source-available rather than open source. Depending on it would give the harness
broad transformation coverage immediately, and would also make the harness undistributable under a
strict open-source policy.

The alternative — recreating that estate — is a multi-year effort that would produce a worse version
of something that already exists.

## Decision

Neither. The boundary is drawn by **cost of verification**, not by coverage.

**Automate deterministically where the transformation is cheap to implement, trivial to verify and
stable across framework generations:**

- Maven descriptor changes: parent version, properties, managed versions, coordinates
- The Jakarta EE namespace relocation, restricted to the packages that actually moved
- Configuration property migration, from **generated** rules
- Mechanical JUnit 4 to Jupiter constructs

**Treat everything else as measured residual**, and let the compile-repair loop and the differential
layer manage it.

Two supporting decisions follow from this:

1. **Property rules are generated, never hand-written.** They come from the deprecation entries in
   the target release's own `spring-configuration-metadata.json`. On the reference corpus this
   produced 542 rules, each carrying the artifact and deprecation level that justify it.
   Hand-maintaining that many rules is how they end up wrong and stale.

2. **OpenRewrite core is discovered, not assumed.** Apache-2.0 OpenRewrite core is a legitimate tool
   for this harness, so the adapter probes the classpath at planning time and reports `AVAILABLE` or
   `UNAVAILABLE` honestly rather than pretending either way. The source-available Spring recipe
   estate is blocked by coordinate in the license gate and by an ArchUnit rule, regardless of what
   license string it declares.

## Consequences

- Deterministic coverage on the reference corpus is 0.9983, achieved with no source-available
  component anywhere in the dependency tree.
- Where coverage is genuinely absent, the residual is measured and automatically raises the planned
  validation depth. Residual is not a failure; it is the input to the layers that exist to manage it.
- The harness cannot mechanically rewrite complex framework API migrations. Those surface as compile
  failures with a diagnosed root cause. That is a worse developer experience than an automated fix,
  and a better one than a silent wrong fix.
