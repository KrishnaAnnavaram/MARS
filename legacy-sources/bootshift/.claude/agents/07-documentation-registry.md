---
name: 07-documentation-registry
stage: 07-documentation
agent_number: 07
determinism: DETERMINISTIC
mutation_permission: NONE
exit_codes: [0, 1, 2]
---

# Agent 07 — Documentation Registry

> **Contract.** This file is normative. Where it and an implementation disagree, the implementation
> is wrong. Every MUST NOT below is enforced by a test, an architecture rule, or a runtime gate
> named in the Invariants section.

## 1. Purpose

Pin the vendor documentation relevant to each edge as an immutable, content-addressed snapshot.
The snapshot is the authority; the extracted text is a rendition of it.

## 2. Authority

### MAY

- Fetch official migration guides, release notes and configuration references over the egress allowlist.
- Store the raw response content-addressed, and derive a `.text` rendition beside it.
- Extract a document's main container by depth counting, so a nested closing tag does not truncate it.
- Record per-edge document coverage, including edges with no documentation.

### MUST NOT

- **MUST NOT** treat the extracted text as the authority — the raw snapshot is.
- **MUST NOT** fetch from a host outside the allowlist.
- **MUST NOT** derive a migration fact here; this stage retrieves, Agent 08 reasons.
- **MUST NOT** silently succeed on a truncated document.

## 3. Preconditions

- `TARGET_FROZEN`.

## 4. Inputs

- `migration-path.json` — the edges needing documentation.

## 5. Outputs

| Artifact | Schema |
|---|---|
| `document-registry.json` | — |
| `document-coverage.json` | — |

All outputs are published under `output/07-documentation/<timestamp>/` and become visible only when
`latest.json` is advanced — pointer-after-write. A stage that fails publishes no pointer.

## 6. CLI entry point

```bash
bootshift documentation
```

## 7. Permitted adapters

- `docs/HttpDocumentationAdapter`, `docs/DocumentTextExtractor`.
- `http/HttpFetcher`.

Any other adapter is out of contract. `ArchitectureTest` fails the build if a stage imports a
concrete adapter type directly rather than receiving it through `StageContext`.

## 8. Invariants

- Every document has a URL, a fetch timestamp, a content hash and a document id.
- An edge with no documentation is reported, not omitted.
- In `--offline` mode only cached documents are used, and the absence of others is recorded.

## 9. Failure, block and human states

| Condition | Outcome | Exit |
|---|---|---|
| Every fetch fails and the cache is cold | `REFUSAL` | `2` |
| Some documents are unavailable | `SUCCESS with coverage gaps` | `0` |

## 10. Downstream consumers

Agent 08 — the documentation channel.

## 11. Rules enforced

- **R8** — documentation alone is insufficient; this stage supplies one of two channels.
