# Execution events and the live stream

MARS records what it does as it does it, as structured **execution events**. Events are witnesses:
the run record, the ledgers, the decisions and the artifacts remain the source of truth. The
Control Center uses events to show activity live and to keep a complete, replayable history of
every run.

## The event

One JSON object per line in `runs/<RUN_ID>/events/events.jsonl` (snake_case, nulls omitted):

```json
{
  "event_id": "EVT-01M3K3W9CZ…",
  "run_id": "RUN-01M3K3TTKT…",
  "sequence": 97,
  "timestamp": "2026-09-28T04:20:27.412Z",
  "type": "REMEDIATION_ROUTED",
  "category": "SECURITY",
  "phase": "SECURITY_ANALYSIS_RUNNING",
  "component": "Security / CWE Router (catalog, KB, research)",
  "activity": "security.plan",
  "status": "COMPLETED",
  "title": "INV-101 routed CATALOG (CWE-89), concrete fix proposed",
  "message": "…",
  "progress": { "mode": "DETERMINATE", "completed": 1, "total": 5, "unit": "findings" },
  "subjects": [ { "kind": "FINDING", "id": "FINDING-…", "label": "INV-101" }, { "kind": "PLAN", "id": "PLAN-…", "label": "CATALOG" } ],
  "evidence_refs": [ "EVID-…" ],
  "artifact_refs": [],
  "attributes": { "capability": "vulnerability-remediation", "route": "CATALOG", "cwe": "CWE-89" }
}
```

| Field | Meaning |
|---|---|
| `sequence` | 1-based and strictly increasing within the run. It is assigned by the store under a lock, so it is gap-free across threads and processes. |
| `type` / `category` | The operation (see below). The category is derived from the type: KERNEL, MIGRATION, SECURITY, HUMAN, MUTATION, VALIDATION or ERROR. |
| `phase` | The run state when the event was emitted. For `STATE_TRANSITION`, it is the target state. |
| `component` | The real executor, for example `Kernel / Mutation Gateway`. Tool-backed components name the adapter (`Kernel / Build (MavenBuildRunner)`), so a simulated tool is never mistaken for the real one. |
| `activity`, `status` | A stable key that pairs STARTED with COMPLETED, FAILED or SKIPPED. WAITING means the run stopped for a human. |
| `progress` | Present only when the executor knows the counts. It is `INDETERMINATE` when the total is unknown. Progress is never derived from time. |
| `human_action` | Set on `HUMAN_ACTION_REQUIRED`: the gate, the decision type, the reason and the options the domain accepts. |
| `attributes` | Type-specific scalar facts, such as a transition's `from`/`to`/`transition_index`, a build outcome, a decision's `actor_authentication`, or the gateway's `checks`. |

## Types

Only operations MARS performs have a type. The full list is in `ExecutionEventType`. The groups are:

- **Run lifecycle:** `RUN_CREATED`, `STATE_TRANSITION`, `ADVANCE_STARTED`, `ADVANCE_STOPPED`,
  `RUN_COMPLETED` and `RUN_FAILED`. `ADVANCE_STOPPED` with status FAILED means an operation ended in
  an error and the run stayed in its last persisted state.
- **Analysis:** `INGEST_*`, `INVENTORY_COMPLETED`, `IDENTITY_*`, `GRAPH_BUILD_*`, `BASELINE_*`,
  `DISCOVERY_*`, `SECURITY_DISCOVERY_*`, `SECURITY_SCAN_COMPLETED`, `RCA_*`, `BLAST_RADIUS_*`,
  `MIGRATION_ASSESSMENT_*` and `SEQUENCE_ASSESSMENT_COMPLETED`.
- **Humans:** `HUMAN_ACTION_REQUIRED` and `DECISION_RECORDED`.
- **Migration:** `MIGRATION_PLAN_CREATED`, `MIGRATION_EXECUTION_*`, `MIGRATION_ROUND_*`,
  `MIGRATION_RULE_APPLIED`, `MIGRATION_VALIDATION_*` and `POST_SECURITY_REASSESSMENT_*`.
- **Security:** `REMEDIATION_PLANNING_*`, `REMEDIATION_ROUTED` and `FIX_VERIFICATION_*`.
- **Mutation:** `PROPOSAL_REGISTERED`, `MUTATION_STARTED`, `MUTATION_APPLIED`, `MUTATION_REFUSED`,
  `MUTATION_ROLLED_BACK`, `MUTATION_BYPASS_DETECTED` and `PROPOSAL_VALIDATION_RECORDED`.
- **Final:** `FINAL_VALIDATION_*`, `VERDICT_COMPUTED` and `GRAPH_REBUILT`.

**Capabilities report only their own work.** A capability pack reaches the event plane only
through `CapabilityContext.activity()` (an `ActivityReporter`). The kernel accepts only the
capability-reportable types: scan, RCA, blast radius, routing, and migration rounds and rules. It
stamps them with the run, the state, the time, the sequence and the capability's ID. A capability
cannot report a decision, a mutation, a state change or a verdict.

## Guarantees

- **Append-only, sequenced.** Appends go through an in-process monitor plus an OS file lock, and
  the next sequence is read from the file under the lock. So the CLI and the Control Center, or two
  sessions in one process, produce one gap-free sequence.
- **Torn-write tolerant.** A crash mid-append leaves at most one unterminated fragment. The next
  append terminates it, and readers report it as malformed rather than misreading it. Readers parse
  only newline-terminated lines.
- **Transitions exactly once, at least once.** `STATE_TRANSITION` events are published from the
  persisted history after each save. A crash between the two writes is caught up at the next save.
  Readers de-duplicate on `attributes.transition_index`.
- **Never fatal.** A failing event store is logged and kept as a degradation note; the run
  continues.
- **Runs before events.** Runs recorded before the event stream existed have no `events.jsonl`. The
  Control Center shows them from their state history and artifacts, and says so.

## The SSE stream

`GET /api/v1/runs/{runId}/events` with `Accept: text/event-stream`.

| Event name | Data | When |
|---|---|---|
| `hello` | `{run_id, last_sequence, replay_after, replayed, server_time, malformed_lines}` | once per connection |
| `execution-event` | the event (above); the SSE `id:` is its `sequence` | replayed after the cursor, then live |
| `worker` | `{advancing, kind, started_at, triggered_by, last_finished_at, last_failed, last_error}` | on connect and whenever this server starts or stops an engine operation for the run |
| `heartbeat` | `{server_time, last_sequence}` | every 15 s |

**Cursor.** The server replays every event with `sequence >` the cursor, then streams new ones.
The cursor is `?after=N` when given, otherwise the `Last-Event-ID` header, otherwise 0 (the whole
history).

**Reconnect.** The browser client sends `Last-Event-ID` = the last sequence it delivered. It drops
anything at or below it and reports a gap if a sequence is skipped. A gap should not happen; if it
does, the snapshot and history are re-read. Backoff is 0.5 s to 15 s. If no data and no heartbeat
arrive for 40 s, the client treats the connection as dead and reconnects.

**Restart.** The stream reads the file, not server memory. After a server restart, a reconnecting
client resumes exactly after its cursor.

**Other processes.** The server tails the file, so events appended by a CLI process stream like
any other. The run is then reported as `EXTERNAL_ACTIVITY` rather than `ADVANCING`, because this
server is not the one advancing it.

A disconnected stream means the page is not receiving events. It never means the run stopped.
Snapshots keep being refreshed.

## History endpoint

`GET /api/v1/runs/{runId}/events/history?after=N&limit=L` returns persisted events as JSON, for
paging and tests. Live updates use the stream.

## Events are not logs, and not telemetry

Tool output (Maven rounds, verification builds, runtime logs) is shown on the Logs screen as
supplementary text. Nothing in the UI depends on log wording. OpenTelemetry spans and Micrometer
metrics (see Operations in the user guide) describe the Control Center's operation for operators. They
are derived from events after each job, and the UI never reads them.
