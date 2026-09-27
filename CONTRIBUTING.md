# Contributing to MARS

Thanks for helping. MARS makes strong promises (no unauthorized change, no unverified claim), so
contributions are judged first on whether they keep those promises.

## Setup

| Tool | Version | Why |
|---|---|---|
| JDK | 21 | Build and run |
| Maven | 3.8+ | Build |
| Node.js | 22 (optional) | Parity tests run the original VRH / migration JavaScript. Without Node they are **skipped**, not passed. |
| Docker | optional | Only for Testcontainers tests in target projects |

```bash
mvn install -DskipTests      # fast build
mvn install                  # full build + every default suite (about 20 minutes)
```

## The rules your change must keep

These are the [five non-negotiable rules](README.md#2-the-five-non-negotiable-rules). In practice:

1. **Never write tracked source outside the Mutation Gateway.** Every code change goes through a
   `ChangeProposal`. The architecture tests fail the build otherwise.
2. **Never let a machine approve.** Decisions come from humans only; do not add paths that record
   a decision automatically.
3. **Never turn unknown into pass.** Missing data is `UNKNOWN`, `NOT_RUN` or
   `INSUFFICIENT_EVIDENCE`. A check that did not run must not count as passed.
4. **Never modify `legacy-sources/`.** It holds byte-identical exports of the three source systems,
   and an architecture test checks this. Port behaviour into `kernel/` or `capabilities/` instead,
   and prove it with a parity test.
5. **Keep the layering.** `kernel/core` and `kernel/ports` never depend on adapters, the engine or
   capabilities. The kernel never depends on a capability. Capabilities see only kernel contracts
   and never depend on each other. External processes are launched only by `kernel/adapters`.
   Wiring lives in `apps/cli`. `ArchitectureTest` enforces all of this.

## Where things go

| Change | Location |
|---|---|
| Domain model (identity, evidence, decisions, verdict) | `kernel/core` |
| A new contract | `kernel/ports` |
| A new tool integration (build tool, scanner, store) | `kernel/adapters` |
| Orchestration, gates, validation, reports | `kernel/engine` |
| Migration knowledge | `capabilities/spring-migration` (see [writing a reference pack](docs/writing-a-reference-pack.md)) |
| Security knowledge, fixers, verification | `capabilities/vulnerability-remediation` |
| CLI commands | `apps/cli` |
| A new artifact format | a JSON Schema in `schemas/v1/` plus a schema test |
| Tunable behaviour | `policies/default/unified-policy.json`, not hard-coded values |

## Tests

All suites live in `tests/`, in packages `unit`, `architecture`, `contract`, `parity`,
`integration`, `e2e` and `acceptance`.

```bash
mvn -pl tests test                                  # default suites (needs the modules installed)
mvn -pl tests test -Dtest=CliWorkflowIT             # one test class
mvn -pl tests test -Dharness.excludedGroups= -Dtest=RealToolchainAcceptanceIT   # real Maven + real apps (~10 min)
```

What to add with a change:

- **Behaviour change:** a unit test, and an E2E test if it affects a run's flow.
- **Ported legacy rule:** a parity test that runs the original JavaScript through `LegacyNode`
  and compares results.
- **New artifact or field:** a schema update and a schema validation test.
- **Anything touching writes, decisions or layering:** check that the architecture tests still
  pass. Never relax them to make a change fit.

## Documentation

- Update the [README](README.md) and the [user guide](docs/user-guide.md) when you change CLI
  commands, options, outputs or exit codes.
- Record significant design decisions as a new ADR in [`docs/adr/`](docs/adr), following the
  existing ADR-U00x format.
- State limitations plainly. If something is not covered, say so in the README's Limitations
  section rather than leaving it implicit.

## Pull requests

- Keep each pull request focused on one change.
- Describe what changed, why, and which tests prove it. Include the test command you ran and its
  result.
- Do not include generated run folders (`runs/`) or build output.
