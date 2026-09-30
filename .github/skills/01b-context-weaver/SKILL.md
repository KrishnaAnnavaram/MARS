---
name: 01b-context-weaver
description: 'Adds the semantic layer to the code knowledge graph. Selects the architecturally significant nodes (modules, packages, types, methods, endpoints, framework base types), briefs an author on each with real evidence, validates the resulting contextual descriptions against the schema and against artifacts.json, and serves them back to downstream agents. Use when asked to describe/document/annotate the graph, explain what parts of the code mean, add context for other agents, or as the step between Code Cartographer and Graph Forge.'
argument-hint: 'None — reads .github/.pipeline-context/artifacts.json, writes .github/.pipeline-context/context/'
---

# Context Weaver

[code-cartographer](../01a-code-cartographer/SKILL.md) answers **what exists**. This skill answers **what it means**.

A structurally-correct graph tells a downstream agent that `EmployeeServiceImpl.getEmployeeSalary()` calls
`DepartmentUrlConfiguration.getDepartmentByIdUrl()` and returns an `EmployeeSalaryResponse`. It does not
tell it that the method blocks on a cross-service HTTP call, that the response is dereferenced without a
null check, or that department-service being down turns this into a 500 rather than a degraded result.
That second kind of knowledge is what a Root Cause, Blast Radius or QA agent actually reasons with, and
it is what this skill captures.

## When to Use

- The user asks to "describe the graph", "add context", "explain what the code means", or "make the graph
  more useful to the other agents"
- Between Code Cartographer and Graph Forge in the Architect pipeline
- After any scan where source changed — re-runs are incremental and only re-describe what actually moved

## The two rules

**1. Descriptions are interpretation, never fact.** Everything Code Cartographer produces is what the
parser saw. Everything this skill produces is a reading of it. The two are kept apart end to end: every
description carries an author and a confidence, the schema forbids unlabelled claims, and Graph Forge
loads them into a separate `ctx*` property namespace so no consumer can mistake one for the other.

**2. Only significant nodes get described.** Describing all 51 types and 57 methods in this workspace
would cost more than it returns and bury the signal. Nodes are scored, and only those above the threshold
are briefed — currently 77 of ~108, with DTOs and enums correctly left out.

## Procedure

```
Code Cartographer                     (produces artifacts.json)
        |
        v
1. node scripts/list-context-workload.js     -> context-workload.{json,md}
2. author descriptions.json                  -> agent writes it, or generate-descriptions.js does
3. node scripts/validate-context.js          -> gate: nothing loads until this passes
        |
        v
Graph Forge                           (attaches the ctx* layer to the graph)
```

1. **Select and brief** — `npm run workload`. Scores every node, picks the significant ones, and writes an
   authoring brief containing each node's signature, annotations, collaborators, call edges and exact
   source. Skips anything already described at the same fingerprint.
2. **Author** — the architect agent reads `context-workload.md` and writes
   `.github/.pipeline-context/context/descriptions.json` against [descriptions.schema.json](./templates/descriptions.schema.json).
   See [descriptions.example.json](./templates/descriptions.example.json) for the shape and the expected
   depth. For a workload too large for one agent context, `npm run generate` does the same job through the
   Messages API (see *Batch generation* below).
3. **Validate** — `npm run validate`. Checks shape, references and freshness. Nothing reaches the graph
   until this passes.
4. **Load** — [graph-forge](../01c-graph-forge/SKILL.md) picks up `descriptions.json` automatically.

## What gets selected, and why

Scored rather than rule-listed, so the threshold is one knob instead of a growing pile of special cases.

| Kind | Selected when |
|---|---|
| `Module` | always — six service boundaries is the cheapest, highest-leverage context in the graph |
| `Package` | holds ≥ 2 types (a single-type package carries no grouping intent) |
| `Type` | score ≥ 3: Spring stereotype (+3), exposes endpoints (+3), interface with an implementation (+3), extends a framework base (+3), scheduled job (+2), holds an HTTP client (+2), fan-in ≥ 2 (+2), injects in-repo collaborators (+1) |
| `Method` | score ≥ 3, and not a trivial accessor: REST handler (+4), `@Scheduled` (+4), ≥ 2 callers (+3), ≥ 2 callees (+2), makes an outbound HTTP call (+2), interface contract (+2), > 15 lines (+1) |
| `Endpoint` | always — the external contract, and where every downstream investigation starts |
| `ExternalType` | always — `MongoRepository` and friends contribute methods the parser never sees, so a call to an inherited method looks like a dead end without this |

Two scoring rules exist specifically to serve downstream agents rather than to describe the code:
**interfaces with implementations** score on their own because Spring injects them, so they are the type
every caller is coded against; and **types extending a framework base** score on their own because their
inherited behaviour is invisible in the graph.

Tune with `--type-threshold`, `--method-threshold`, `--min-package-types`, `--max-nodes`.

## Staleness — the part that keeps this honest

A description is written against one shape of the code. When that shape changes, the description may have
quietly become false, and a confidently wrong description is worse than none at all.

Every node carries a **fingerprint** of the code it describes — for a method, that includes its
normalized source text. `list-context-workload.js` recomputes it to decide what needs re-describing;
Graph Forge recomputes it at load time and sets `ctxStale = true` on anything that drifted. Consumers
are told to ignore stale entries. Nothing silently serves a description written for code that no longer
exists.

## Batch generation (optional)

The agent authoring descriptions itself is the primary path — no API key, no network, and it is the same
pattern every other agent-authored artifact in this repo uses. `scripts/generate-descriptions.js` exists
only for a workload too large to fit in one agent context. It sends the same brief to an LLM API in
batches, pins responses to the schema with structured outputs, and writes the same `descriptions.json`.

**Provider-agnostic.** Anthropic, OpenAI and Google are equally supported — pick one with `LLM_PROVIDER`
in `.env`, everything else in the script is shared:

```bash
cd .github/skills/01b-context-weaver
npm install                                     # installs dotenv; SDKs are optional deps
cp .env.example .env                            # set LLM_PROVIDER + that provider's key
npm install @anthropic-ai/sdk                   # or: openai / @google/generative-ai
node scripts/generate-descriptions.js --dry-run # inspect the batch plan first, no API calls
node scripts/generate-descriptions.js --batch-size 10
```

| `.env` key | Meaning |
|---|---|
| `LLM_PROVIDER` | `anthropic` \| `openai` \| `google` |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_MODEL` | Used when `LLM_PROVIDER=anthropic` |
| `OPENAI_API_KEY` / `OPENAI_MODEL` | Used when `LLM_PROVIDER=openai` |
| `GOOGLE_API_KEY` / `GOOGLE_MODEL` | Used when `LLM_PROVIDER=google` |
| `CONTEXT_EFFORT` | Thinking depth — Anthropic only, ignored elsewhere |
| `CONTEXT_BATCH_SIZE` | Nodes per request |
| `CONTEXT_TYPE_THRESHOLD` / `CONTEXT_METHOD_THRESHOLD` / `CONTEXT_MIN_PACKAGE_TYPES` / `CONTEXT_MAX_NODES` | Same knobs `list-context-workload.js` exposes as CLI flags — an explicit flag always overrides the `.env` value |

`--provider` and `--model` flags override `.env` for one run without editing the file. Each provider
lives in its own file under `scripts/lib/providers/` behind one shared interface
(`isInstalled`, `generate`) — `scripts/lib/providers/index.js` is the only place that reads
`LLM_PROVIDER`, so adding a fourth provider is one new file plus one registry line.

The instruction block and schema are identical across batches; on Anthropic that prefix carries a cache
breakpoint, so only per-batch evidence is billed at full price. Batches run sequentially on purpose —
concurrent requests sharing a prefix all miss the cache the others are still writing.

**A failed run never touches the file.** If every batch fails (bad key, rate limit, all refused), the
script prints why and leaves `descriptions.json` exactly as it was — it will not relabel existing
descriptions as `author: llm:<provider>` when that provider contributed nothing. When a run *does*
produce new nodes and merges them with an existing agent-authored set, `author` becomes `mixed (...)`
rather than silently crediting the wrong source.

Output still goes through the validator regardless of which provider wrote it; nothing this script
produces is trusted more than what the agent writes by hand.

## Reading context back

`scripts/lookup-context.js` is the read side for the agents downstream. It resolves loose symbols the way
the other analyst skills do and returns the stored context, each entry labelled with its confidence and
staleness:

```bash
node scripts/lookup-context.js --symbol EmployeeServiceImpl.getEmployeeSalary
node scripts/lookup-context.js --module employee-service --format md
node scripts/lookup-context.js --search "salary" --format json
```

It reads `descriptions.json` rather than Neo4j, so a Root Cause or QA run can get its bearings without a
database. For graph-side queries, see the Cypher in [graph-forge/SKILL.md](../01c-graph-forge/SKILL.md).

## Notes

- Self-contained folder — only `dotenv` is a hard dependency (for `.env`), and only needed at all if you
  configure one. All three provider SDKs are optional and only `generate-descriptions.js` touches them.
  Can be copied or moved independently.
- The fingerprint functions are duplicated in `01c-graph-forge/scripts/build-graph.js` so that skill stays
  independently copyable too. **Any change to them must be mirrored in both files** or staleness detection
  silently inverts.
- `descriptions.json` is the source of truth; the graph is a loaded copy. Re-running Graph Forge always
  reconciles the graph to the file.
