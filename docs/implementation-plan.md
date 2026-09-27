# MARS: Phased Implementation Plan

This plan follows spec §30. Each phase ends with compile, relevant tests, architecture/contract
tests, a diff review and notes. Bootshift's own suite (190 tests) runs in every reactor build.

## Module layout (Shared Kernel + Capability Packs)

```text
pom.xml                                   mars-harness-parent (reactor)
legacy-sources/bootshift/                 Bootshift, built UNCHANGED; its core/ports/adapters/stages are the kernel base
legacy-sources/vulnerability-remediation-harness/   VRH: catalog, KB, scoring, fixtures (read as data)
legacy-sources/spring-migration-reference/          reference pack, recorded rounds, sample app (fixture source)
kernel/core        com.mars.harness.kernel.core      ids, identity (module/program-unit/symbol/statement + reattachment),
                                                     findings, migration assessment model, decisions, ChangeProposal,
                                                     validation, verdict, run state machine, evidence, policy, outcomes
kernel/ports       com.mars.harness.kernel.ports     the spec §27 interfaces
kernel/adapters    com.mars.harness.kernel.adapters  JavaParser code observation, XLSX/SARIF intake, run store,
                                                     Maven build runner, HTTP probe runtime, rule scanner,
                                                     Bootshift bridges
kernel/engine      com.mars.harness.kernel.engine    orchestrator (phases 0–9), gates, MutationGateway (the only
                                                     writer; delegates to Bootshift FileMutationGateway), identity
                                                     synchronizer, unified validation, verdict, reports
capabilities/spring-migration          com.mars.harness.capabilities.migration   advisor, reference-pack engine, rounds, probes, report
capabilities/vulnerability-remediation com.mars.harness.capabilities.security    intake, root cause, blast radius, routing
                                                     (catalog/KB/research), planning, fixers, verify, gates, arbiter, audit
apps/cli           com.mars.harness.cli              `harness` (picocli) composition root
tests              com.mars.harness.tests.{unit,architecture,contract,parity,integration,e2e,acceptance}
schemas/  policies/  fixtures/  docs/ (+ docs/adr)
```

## Phases

| Phase | Deliverable | Exit criteria |
|---|---|---|
| A | Inspect and freeze: `current-system-analysis.md`, `protected-business-logic.md`, baseline runs | done (see analysis §3) |
| B | Monorepo: legacy exports, reactor pom including Bootshift unchanged | `mvn install` green; Bootshift 190/0/3 |
| C | Shared kernel on Bootshift: kernel core/ports reuse FileRegistry, ChangeLedger, Json/Hashing/Ids, SensitiveValues; `RunStore` (pointer-after-write); `BootshiftAnalysisBridge` running stages 00–03 unchanged | bridge parity test green |
| D | Identity extension: MODULE / PROGRAM_UNIT / persistent SYMBOL / STATEMENT; reattachment hierarchy; lineage queries | identity golden tests (rename, edit, move, method rename/move, statement edit/move, insertion, deletion, split, merge, ambiguous, low-confidence) |
| E | Canonical schemas (JSON Schema 2020-12) plus validation tests | every artifact type validates; negative cases fail |
| F | Capability adapters: migration (reference pack + Bootshift lifecycle/diagnostics), security (VRH rules ported, legacy data files read in place) | parity tests B1–B10, C1/C5/C6/C7 |
| G | Read-only discovery: MigrationAdvisor, SecurityDiscovery, CombinedAssessment, SequenceAdvisor; Human Gate A | discovery leaves the workspace byte-identical; assessment evidence complete |
| H | Canonical decisions (execution, remediation approval, migration A2, apply) with Markdown compat renderers | decision validation tests |
| I | ChangeProposal + single MutationGateway (major checkpoint) | architecture tests: no source writes outside the gateway; runtime bypass detector |
| J | Unified validation dimensions; verdict engine | "unexecuted is never PASS" contract tests |
| K | Post-security migration reassessment (Gate A2) | E2E 32.13 |
| L | Remove duplicated infrastructure only where parity is proven | recorded in the report, conservative |
| Z | Acceptance scenarios 1–10 plus the real-toolchain migration E2E; `IMPLEMENTATION-REPORT.md` | all suites green; evidence captured |

## Decisions recorded as ADRs (docs/adr)

| ADR | Decision |
|---|---|
| ADR-U001 | Bootshift is built unchanged in the reactor and extended by composition; the kernel never forks Bootshift classes. Physical relocation into `kernel/` is deferred until parity is proven (Phase L). |
| ADR-U002 | Persistent sub-file identity: allocated IDs, an evidence-based reattachment hierarchy, uncertainty marked and never silently attached. |
| ADR-U003 | The migration capability has two engines behind one contract. The reference-pack engine drives 3→4 rounds; the Bootshift pipeline stays callable unchanged for its supported lines. Transformations are deterministic reference-pack rules; manual or LLM patches are proposals that need approval. |
| ADR-U004 | Unified CWE catalog = VRH catalog plus repo C's CWE-1104 entry, loaded in place from `legacy-sources` with per-entry provenance; KB and scoring are read from the VRH files unchanged. |
| ADR-U005 | LLM-judgement steps (research analysis, catalog-strategy narrative) are INPUT artifacts validated against the legacy schemas. Without them the harness produces the legacy evidence-gap outcome instead of inventing one. |
| ADR-U006 | Decisions are machine artifacts (write-once, integrity-hashed, scoped by hash, stale on baseline or proposal change); Markdown is rendered from them and never read back as authority. |
| ADR-U007 | Run workspace mapping: Bootshift `original/` is the immutable source snapshot and `migration/` is the only mutable tree. Builds and runtime run in a disposable exec copy, so tool output never appears as a bypass. |
