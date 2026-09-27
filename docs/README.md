# MARS Documentation

## Using MARS

| Document | Read it when |
|---|---|
| [Project README](../README.md) | You want the overview: rules, architecture, flow, CLI and configuration |
| [User guide](user-guide.md) | You are running MARS and want to know how to do a specific task |
| [Writing a reference pack](writing-a-reference-pack.md) | You want to add a new migration path |

## Design and evidence

| Document | Contents |
|---|---|
| [Implementation report](IMPLEMENTATION-REPORT.md) | Final architecture, preserved behaviour, full test evidence, defects found and fixed, limitations |
| [Architecture decision records](adr/) | ADR-U001 … U007: why the kernel, identity, capabilities, knowledge, judgement, decisions and run layout are the way they are |
| [Protected business logic](protected-business-logic.md) | Every behaviour carried over from the source systems, how it is preserved, and the test that guards it |
| [Project report (.docx)](MARS-Project-Report.docx) | The project report as a Word document |

## Background (historical)

These documents record how MARS was built. They are kept for traceability and describe the
state at the time they were written.

| Document | Contents |
|---|---|
| [Current system analysis](current-system-analysis.md) | Phase A: the three source systems and their baseline test state, before any integration |
| [Implementation plan](implementation-plan.md) | The phased plan and module layout |
| [Original specification](spec/UNIFIED_HARNESS_IMPLEMENTATION_MASTER_PROMPT.md) | The specification MARS was implemented from; other docs cite its section numbers (§) |

## Reference material elsewhere in the repository

| Location | Contents |
|---|---|
| [`schemas/v1/`](../schemas/v1) | JSON Schemas for every artifact MARS writes |
| [`policies/default/unified-policy.json`](../policies/default/unified-policy.json) | The default policy |
| [`legacy-sources/SOURCES.json`](../legacy-sources/SOURCES.json) | Provenance of the three imported source systems |
