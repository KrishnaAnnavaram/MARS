# Control Center security

The Control Center adds a network interface to a system whose central promise is that nothing
changes without a recorded human decision. This document states what that interface may and may
not do, how identity is established, and what is not covered yet.

## Boundaries

**The API may:**

- read a run's persisted artifacts and execution events
- ask the engine to `analyze`, `resume` or `decide*` (Gate A, A2, plan approval, Gate B)

**The API may not:** these rules are enforced on the compiled code by `ControlCenterArchitectureTest`
and `ArchitectureTest`.

- write any file (run state, decisions, evidence, events, artifacts, source)
- transition the state machine, save a `RunSession`, or construct a `Decision` or `ChangeProposal`
- reach the Mutation Gateway, proposal sinks, `MutationPort` or Bootshift's writer
- apply a run's result to the original project (`applyToProject` and `decideApply` stay CLI-only)
- let the kernel, capabilities or CLI depend on Spring, servlets or the Control Center

**The browser** is a view. It enforces nothing. Roles are checked by the server on every request;
the UI only hides controls a user cannot use.

## Identity and decisions

Integrity and authentication are different things, and the Control Center never conflates them:

- **Integrity** (the HMAC on each decision, the hash chains on evidence and ledgers) answers "was
  this record modified?" It is re-verified on every read and shown as VERIFIED or TAMPERED.
- **Authentication** answers "who made this decision?" It is recorded in each decision as
  `actor_authentication`, covered by the HMAC, from a closed vocabulary:

| Value | Set by | Means |
|---|---|---|
| `LOCALLY_ASSERTED` | the CLI | the actor typed their own name; nothing verified it |
| `DEVELOPMENT_ASSERTED` | the Control Center in dev mode | a configured development user signed in; this is not authentication by an identity provider |
| `OIDC_AUTHENTICATED:<issuer>` | the Control Center in OIDC mode | the API validated a JWT from that issuer |

The actor name comes from the authenticated principal, and the role from the MARS role that
permits deciding (APPROVER or ADMIN). A request body cannot name the actor. The engine's
`DecisionValidator` still refuses reserved machine identities (harness, llm, agent, copilot,
claude, ci, …) and empty rationales, whatever the entry point.

### Dev mode (default)

`mars.control-center.auth.mode=dev`. Development users are configured in `application.yml`
(`viewer`, `operator`, `approver`, `admin`; password from `MARS_CC_DEV_PASSWORD`, default
`mars-dev`). Sign-in creates a session cookie (`HttpOnly`, `SameSite=Strict`) and rotates the
session ID. Every non-GET request needs the CSRF token: the SPA pattern, where the `XSRF-TOKEN`
cookie is echoed as the `X-XSRF-TOKEN` header. The server binds to `127.0.0.1` by default and logs a
warning at startup.

Dev mode is for a developer's own machine. Do not expose it to a network.

### OIDC mode

`mars.control-center.auth.mode=oidc` (or `MARS_CC_AUTH_MODE=oidc`) together with
`spring.security.oauth2.resourceserver.jwt.issuer-uri=<issuer>` (Entra ID, Okta, Keycloak, or any
standards-based provider). The API is a stateless resource server:

- **Roles.** Roles come from the claim `mars.control-center.auth.roles-claim` (default `roles`).
  Values are mapped through `mars.control-center.auth.role-mapping` (for example
  `mars-approvers: APPROVER`) or taken as MARS role names directly. Unknown values grant nothing.
- **Actor.** The actor name comes from `mars.control-center.auth.name-claim` (default
  `preferred_username`), falling back to `sub`.
- **CSRF.** CSRF protection is off, because bearer tokens are not sent automatically by browsers.

What exists today: the server side of OIDC (token validation, role mapping, decision attribution),
covered by a unit test of the claim mapping. Not implemented yet: the browser half of the login
flow (authorization code with PKCE). The SPA has a token-provider hook (`setTokenProvider` in
`src/api/client.ts`) for it, and its SSE client already sends `Authorization` headers.

## Roles

| Role | Read | Start runs | Resume | Decide |
|---|---|---|---|---|
| VIEWER | ✓ | | | |
| OPERATOR | ✓ | ✓ | ✓ | |
| APPROVER | ✓ | | ✓ | ✓ |
| ADMIN | ✓ | ✓ | ✓ | ✓ |

Resuming authorizes nothing (it advances by the recorded decisions), so approvers may continue a
run after Gate B. Starting runs and deciding are separate: starting a run is not approval, and
approving does not require the right to start runs.

## Other controls

- **Filesystem boundary.** Repositories and finding inputs must resolve, after following links,
  under `mars.control-center.repository-roots`. Absolute server paths under the harness, runs and
  repository roots are replaced by placeholders (`<harness>`, `<runs>`, `<repositories>`) in
  responses: error messages, evidence records (a location inside the run is shown run-relative),
  execution events, served artifacts and log lines. The files on disk keep the original text. Paths
  outside those roots, such as a build tool's installation directory, are shown as recorded.
- **Rendered documents.** Markdown is rendered without raw HTML, links open only for `http(s)` URLs
  (in a new tab), images are not fetched, and Mermaid runs in its `strict` security level (no
  scripts or click handlers; labels sanitized).
- **Served artifacts.** Only evidence areas are served. `original/`, `migration/`, `exec/` contents,
  Bootshift's checkpoint repository and the decision integrity key are never listed or served. Source
  context for findings is read from the run's own copy and limited to a few lines.
- **Credential masking.** Credential-like literals (passwords, tokens, API keys, private keys) are
  masked in source context, diffs, logs and evidence shown to the browser. When a diff is masked,
  the response says so. The approval still binds to the exact proposal hash, which covers the
  unmasked content. This is a best-effort display control; the run directory remains sensitive
  data.
- **Stale and duplicate decisions.** Decisions are bound to the hash the reviewer saw, gates
  accept one decision, and proposal decisions must name what they supersede. One permit per run
  serializes all engine calls.
- **Headers.** The API sends a Content-Security-Policy (`default-src 'self'`; inline styles only,
  which the graph libraries need), `X-Frame-Options: DENY` and `nosniff`.
- **Errors** carry a correlation ID, never stack traces.

## Known limitations

- **CLI and Control Center at the same time.** The Control Center serializes its own engine calls.
  It does not coordinate with a CLI process advancing the same run at the same moment. Event
  appends stay consistent (they are file-locked), but the engine is not designed for concurrent
  advancement of one run. Do not run `harness resume` on a run the Control Center is advancing. The
  UI shows `EXTERNAL_ACTIVITY` when another process appends events.
- **OIDC browser login** is not implemented yet (see above).
- **Idempotency replay** is in memory and bounded (24 h, 10,000 keys). After a restart, a retried
  command meets the domain guards instead (a gate accepts one decision, and a proposal decision must
  name what it supersedes).
- **Dev mode is not authentication.** Every decision it records says so (`DEVELOPMENT_ASSERTED`).

## Hardening checklist for a shared deployment

1. `MARS_CC_AUTH_MODE=oidc` and a configured issuer; no dev users.
2. Terminate TLS in front of the service; keep `server.address` on a private interface.
3. Restrict `repository-roots` to the directories the team works on.
4. Give the service account write access to the runs root only.
5. Map IdP groups to MARS roles with the least privilege needed; keep APPROVER separate from OPERATOR.
6. Export traces and metrics (see the user guide) and alert on `mars.control_center.jobs{outcome=error}`.
