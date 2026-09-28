# Changelog

All notable changes to MARS are recorded here. Versions follow the project version in `pom.xml`.

## Unreleased

### Control Center (web)

- A web Control Center (`apps/control-center-api`, `ui/mars-control-center`) for live visibility and
  human decisions on MARS runs. It calls the same engine as the CLI and never bypasses the approval
  store or the Mutation Gateway. See the [user guide](docs/control-center-user-guide.md).
- The kernel records first-class execution events (`runs/<id>/events/events.jsonl`), streamed live with
  replay after reconnects and restarts ([ADR-U008](docs/adr/ADR-U008-control-center-event-streaming.md)).
- Decisions record how the actor was identified (`LOCALLY_ASSERTED`, `DEVELOPMENT_ASSERTED`,
  `OIDC_AUTHENTICATED:<issuer>`); roles are enforced by the server.
- Reports render as documents, with tables and Mermaid diagrams; absolute server paths are kept out
  of API responses.

### Documentation

- README: overview section, Windows and run-ID tips, step-by-step worked example, glossary, troubleshooting,
  and a CLI reference corrected against the actual command options.
- New: [user guide](docs/user-guide.md), [writing a reference pack](docs/writing-a-reference-pack.md),
  [documentation index](docs/README.md), [contributing guide](CONTRIBUTING.md) and
  [security policy](SECURITY.md).
- The MARS name is used consistently across docs and ADRs, and the original specification moved
  to `docs/spec/`.

## 1.0.0 (2026-09-26)

First release of MARS, the Migration and Remediation System.

- Unified Bootshift (migration engine), VRH (vulnerability remediation) and the Spring Boot 3 → 4
  migration reference into one Shared Kernel + Capability Packs architecture, with Bootshift
  running unchanged.
- Persistent identity down to the statement level, one Mutation Gateway, human decision gates
  A / A2 / B, 13 validation dimensions and a single verdict with an evidence package.
- `harness` CLI covering analyze, decide, approve, submit-patch, submit-research, resume, report,
  lineage, apply and verify.
- A human `REJECTED` or `DEFERRED` decision is now the reason of record ahead of technical
  refusals, so a deferred strategy-only proposal is reported as `DEFERRED_BY_DEVELOPER`.
- Project renamed to MARS; project report added (`docs/MARS-Project-Report.docx`).
- Test evidence: Bootshift 190 run / 0 failures (3 skipped), harness 175 run / 0 failures,
  real toolchain 2 run / 0 failures.
