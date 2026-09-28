# Control Center API

Base path `/api/v1`. The generated OpenAPI document is [control-center-openapi.json](control-center-openapi.json)
(also served live at `/v3/api-docs`, with Swagger UI at `/swagger-ui.html`).

## Conventions

- **JSON** is snake_case, and absent fields have no value; the client must not assume a default. All
  times are ISO-8601 instants. Hashes are lowercase SHA-256 hex.
- **Read endpoints** return snapshots built from the run's persisted artifacts, recomputed on every
  request. Integrity (decision HMACs, the evidence hash chain) is re-verified on read.
- **Commands** end in exactly one engine call. They accept an optional `Idempotency-Key` header: a
  retry with the same key and body returns the first response. The same key with a different body
  is refused.
- **Paths.** Absolute server paths are redacted from messages (`<runs>`, `<harness>`,
  `<repositories>`). Repository and input paths in requests are relative to a configured repository
  root, or absolute under one.
- **Correlation.** Every response carries `X-Correlation-Id`, which also appears in error bodies and
  server logs.

## Authentication and roles

In dev mode, sign in with `POST /session/login`. That sets a session cookie; every non-GET request
must also send `X-XSRF-TOKEN` with the value of the `XSRF-TOKEN` cookie. In OIDC mode, send
`Authorization: Bearer <JWT>`. See [control-center-security.md](control-center-security.md).

| Role | May |
|---|---|
| VIEWER | read everything |
| OPERATOR | + start runs, resume runs |
| APPROVER | + record decisions, resume runs |
| ADMIN | everything |

## Endpoints

### Session

| Method | Path | Notes |
|---|---|---|
| GET | `/session` | the caller: user, roles, permissions, auth mode, and the `actor_authentication` their decisions record |
| GET | `/session/dev-users` | dev mode: configured users (names and roles only) |
| POST | `/session/login` | dev mode: `{username, password}`; rotates the session ID |
| POST | `/session/logout` | |

### Runs (read)

| Method | Path | Returns |
|---|---|---|
| GET | `/runs?status=&capability=&q=&offset=&limit=` | run history (`status`: active, waiting, complete, failed, needs_human; `capability`: migration, security) |
| GET | `/repositories` | repositories under the configured roots, with candidate finding inputs and probes |
| GET | `/runs/{id}` | the overview snapshot: state, liveness, pipeline and path, stage progress, current activity, summaries (migration, security, changes, validation), integrity, environment |
| GET | `/runs/{id}/pipeline` | pipeline stages |
| GET | `/runs/{id}/timeline` | every state transition (with reason) and every decision |
| GET | `/runs/{id}/human-actions` | the gate the run is stopped at: why, what to decide, options and consequences, advice, bound hash, whether the caller can decide, whether resuming makes progress |
| GET | `/runs/{id}/findings` | security cockpit: summary and findings |
| GET | `/runs/{id}/findings/{findingId}` | finding detail: anchor, RCA, blast radius, plan, proposal, decisions, verification, remediation journey, redacted source context |
| GET | `/runs/{id}/proposals` | change explorer |
| GET | `/runs/{id}/proposals/{proposalId}` | proposal detail: the exact edits and unified diffs (credential-like literals masked for display, and flagged), provenance, scope, base hashes, hash verification, decisions, the Mutation Gateway record and lineage, and whether it can be decided now |
| GET | `/runs/{id}/decisions` | every decision, with `integrity` VERIFIED or TAMPERED and an authentication note |
| GET | `/runs/{id}/migration` | assessment (with effort factors), refreshed and post-security assessments, plan, rounds, behaviour and test comparison, validation, blocker |
| GET | `/runs/{id}/validation` | final validation dimensions, dimensions with no result, migration validation, per-fix security verification, proposal validation, baseline |
| GET | `/runs/{id}/verdict` | the verdict as calculated: outcome, reasons, hard failures, unknown dimensions, pending decisions, items, reports |
| GET | `/runs/{id}/evidence?kind=&producer=&subject=&phase=&q=&offset=&limit=` | evidence in chain order, with chain status |
| GET | `/runs/{id}/evidence/{evidenceId}` | one record |
| GET | `/runs/{id}/graph?focus=&depth=&q=&types=&edge_types=&highlight=&limit=` | a bounded view of the canonical graph. `highlight`: FINDING, CHANGED, BLAST_RADIUS or MIGRATION_ISSUE. `limit` is at most 2000, and the response is flagged `truncated` when capped. |
| GET | `/runs/{id}/artifacts` | evidence artifacts (source areas and the integrity key are never listed) |
| GET | `/runs/{id}/artifacts/content?path=` | one artifact's text (credentials masked, server paths replaced by placeholders) |
| GET | `/runs/{id}/logs` | tool logs (`logs/`, and round and verify logs next to `exec/`) |
| GET | `/runs/{id}/logs/content?path=&from=&max=&level=&q=` | log lines (masked like artifacts); `level` is parsed from the tool's prefix, for filtering only |
| GET | `/runs/{id}/events` | the SSE stream; see [control-center-events.md](control-center-events.md) |
| GET | `/runs/{id}/events/history?after=&limit=` | persisted events as JSON |

### Commands

| Method | Path | Body | Engine call | Advances? |
|---|---|---|---|---|
| POST | `/runs` | `{repository, finding_inputs[], research_inputs{}, probes, skip_build}` | `analyze(request, runId)` (202 with `run_id`) | runs phases 0–4 to Gate A |
| POST | `/runs/{id}/resume` | `{accept_pending}` | `resume(runId, acceptPending)` | yes |
| POST | `/runs/{id}/decisions/execution` | `{strategy, rationale, expected_assessment_hash}` | `decideExecution` | yes |
| POST | `/runs/{id}/decisions/post-security` | `{choice, rationale, expected_assessment_hash}` | `decidePostSecurityMigration` | yes |
| POST | `/runs/{id}/decisions/migration-plan` | `{verdict, rationale, expected_plan_hash}` | `decideMigrationPlan` | yes |
| POST | `/runs/{id}/proposals/{proposalId}/decision` | `{verdict, rationale, expected_proposal_hash, supersedes_decision_id?}` | `decideProposal` | no: continue explicitly |

A recorded decision returns:

```json
{ "decision": { "decision_id": "DEC-…", "type": "PROPOSAL_APPROVAL", "selected": "APPROVED",
                "proposal_hash": "…", "baseline_seal": "…", "actor": "approver", "role": "APPROVER",
                "actor_authentication": "DEVELOPMENT_ASSERTED", "integrity": "VERIFIED", "timestamp": "…", … },
  "advancing": false, "next": "Recorded. The Mutation Gateway acts on it when the run continues (Continue execution)." }
```

The actor, role and `actor_authentication` always come from the authenticated principal. Actor
fields in a request body are ignored.

### Decision semantics

- **Gate A** (`WAITING_FOR_EXECUTION_DECISION`). The strategy must be one of the combined
  assessment's `offered_strategies`: MIGRATE_FIRST, SECURITY_FIRST, MIGRATION_ONLY, SECURITY_ONLY,
  ANALYZE_ONLY or STOP. The decision is bound to the combined-assessment hash.
- **Gate A2** (`WAITING_FOR_POST_SECURITY_MIGRATION_DECISION`). The choice is PROCEED, SKIP or STOP,
  bound to the post-security reassessment hash. The gate accepts one decision.
- **Migration plan** (`WAITING_FOR_MIGRATION_APPROVAL`, only when policy requires it). The verdict
  is APPROVED, REJECTED or DEFERRED, bound to the plan hash.
- **Gate B** (`WAITING_FOR_REMEDIATION_APPROVAL` for security and manual proposals; `NEEDS_HUMAN`
  for a manual patch during migration). The verdict is APPROVED, REJECTED or DEFERRED, bound to the
  proposal hash and the baseline seal. The proposal must be PROPOSED or AWAITING_APPROVAL. A second
  decision must name the current one in `supersedes_decision_id`. The API is stricter than the CLI
  here: a proposal is decided at the gate that waits for it.
- A missing decision is never an approval. Approving a strategy-only proposal applies nothing.
- Recording a decision never applies a change. The Mutation Gateway applies approved proposals when
  the run advances, after its own authorization, identity, scope, base-hash and bypass checks.
- There is no pause, cancel or retry: MARS has none. Apply-to-project is CLI-only.

## Errors

```json
{ "code": "PROPOSAL_HASH_MISMATCH", "message": "The proposal no longer hashes to what was reviewed",
  "run_id": "RUN-…", "correlation_id": "…", "details": { "expected": "…", "current": "…" } }
```

| Code | HTTP | Meaning |
|---|---|---|
| RUN_NOT_FOUND, FINDING_NOT_FOUND, PROPOSAL_NOT_FOUND, EVIDENCE_NOT_FOUND, ARTIFACT_NOT_FOUND | 404 | unknown, or not servable |
| INVALID_STATE | 409 | the run is not at the gate or state the command applies to |
| STALE_ASSESSMENT, PLAN_HASH_MISMATCH, PROPOSAL_HASH_MISMATCH | 409 | the reviewed object changed; reload and review again |
| STALE_PROPOSAL | 409 | the proposal is no longer awaiting a decision |
| DECISION_ALREADY_RECORDED | 409 | someone decided already; `details.decision_id` names it |
| RUN_BUSY | 409 | another command holds the run, or MARS is advancing it |
| DECISION_REFUSED | 422 | the engine refused (for example, a reserved machine actor) |
| VALIDATION_FAILURE | 422 | bad input (blank rationale, unknown enum, …) |
| PATH_NOT_ALLOWED | 403 | a path outside the configured repository roots |
| UNAUTHORIZED / FORBIDDEN | 401 / 403 | not signed in / role not permitted (also missing CSRF) |
| ENGINE_FAILURE, INTERNAL_ERROR | 500 | logged with the correlation ID |
