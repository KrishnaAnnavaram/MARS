# ADR-U008: The Control Center observes structured execution events and decides only through the engine

**Status:** Accepted
**Date:** 2026-09-27
**Deciders:** Unified harness architecture

## Context

MARS runs are long, stop at human gates, and are driven from a terminal. Developers, operators and
managers need to watch a run live, understand why it stopped, inspect the evidence and record
decisions, without reading console logs and without weakening any of the guarantees the CLI has:
the engine is authoritative for lifecycle, advancement, decisions and mutations, and nothing
executes without a recorded human decision.

Three designs were available for live visibility:

1. **Tail the console log** and infer progress from its wording.
2. **Poll the run artifacts** and infer activity from their changes.
3. **Emit structured execution events** from the places where the engine does the work.

And three for human control:

1. Let the web layer write decision files itself.
2. Give the web layer its own orchestration (a second state machine or advancer).
3. Call the engine's existing public operations (`analyze`, `decide*`, `resume`).

## Decision

**Structured events.** The kernel emits typed `ExecutionEvent`s (core) through an
`ExecutionEventStore` port. The filesystem adapter appends them to `events/events.jsonl` with a
strictly increasing per-run sequence, under an in-process monitor plus an OS file lock, so the CLI
and the Control Center can both append to and read one consistent log. State transitions are
emitted from the persisted history after each save; capabilities report only their own activity
through a restricted `ActivityReporter` (no decisions, mutations, state or verdicts).

**Events are witnesses, not authority.** The run record, ledgers, decisions and artifacts stay the
source of truth. The Control Center answers "what is true now" from snapshots of those artifacts,
and "what happened" from events. A missing event never makes anything false; an event never makes
anything true. Runs recorded before events existed are shown from their state history.

**Server-Sent Events** carry events to the browser: traffic is one-way (server to browser), SSE
reconnects with `Last-Event-ID`, and the server replays exactly the events after it, so a reload,
a dropped connection or a server restart loses and duplicates nothing. Human commands are ordinary
authenticated HTTP requests. WebSockets were not needed.

**Snapshot plus replay.** On load the UI fetches the authoritative snapshot, then subscribes from
the start of the run (or its last seen sequence). Events invalidate snapshots, which are re-read
from the API. Gaps trigger a snapshot refresh.

**Decisions go through `HarnessEngine`.** The API's command service checks what the reviewer saw
against what is true now (gate, bound hash, existing decision), then calls `decideExecution`,
`decideProposal`, `decideMigrationPlan` or `decidePostSecurityMigration`, which record through the
`ApprovalPort`. The actor comes from the authenticated principal; `actor_authentication` states how
it was established (`DEVELOPMENT_ASSERTED` or `OIDC_AUTHENTICATED:<issuer>`; the CLI stays
`LOCALLY_ASSERTED`). Advancement is `HarnessEngine.resume`, i.e. the unchanged `RunAdvancer`. One
permit per run serializes every engine call the Control Center makes.

**Logs and OpenTelemetry are separate.** Tool logs are shown as supplementary text. OpenTelemetry
spans and Micrometer metrics describe the Control Center's operation for operators; the UI never
reads them, and spans are derived from the events after each job, so the kernel has no tracing
dependency.

## Consequences

- The Control Center is a second composition root next to the CLI (`HarnessFactory`), and the CLI
  works exactly as before. Both write the same run artifacts and events.
- ArchUnit rules keep the web layer away from the Mutation Gateway, proposal sinks, state
  transitions, run-state writes, decision construction and apply-to-project, and keep transport
  concerns out of the kernel and capabilities.
- The event stream adds one append per operation; a failing event store is logged and recorded as
  degraded, and never fails a run.
- Two processes advancing the same run at the same time (a CLI `resume` while the Control Center
  advances) are not coordinated; appends stay consistent, but the engine itself is not designed for
  concurrent advancement. The Control Center reports external activity when it sees events it did
  not produce.
- Apply-to-project (writing the original repository) remains CLI-only.
