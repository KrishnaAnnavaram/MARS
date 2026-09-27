# Changelog

All notable changes to MARS are recorded here. Versions follow the project version in `pom.xml`.

## Unreleased

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
