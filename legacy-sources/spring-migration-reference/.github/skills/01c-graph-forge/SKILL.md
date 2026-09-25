---
name: 01c-graph-forge
description: 'Loads Code Cartographer artifacts.json into a Neo4j graph database, modeling Modules, Packages, Types, Methods, Endpoints and Maven dependencies with relationships (CONTAINS, EXTENDS, IMPLEMENTS, DEPENDS_ON, EXPOSES, USES, HAS_METHOD), and attaches the Context Weaver semantic layer (what each node is for, how it fails, what it touches) onto the same nodes. Use when asked to build/update a knowledge graph, load the code graph into Neo4j, or visualize/query code connections in Neo4j.'
argument-hint: 'None — reads .github/.pipeline-context/artifacts.json and connection details from .env'
---

# Graph Forge

Turns the static `artifacts.json` produced by [code-cartographer](../01a-code-cartographer/SKILL.md) into a connected graph in Neo4j, so architecture questions can be answered with Cypher instead of re-reading source files.

If [context-weaver](../01b-context-weaver/SKILL.md) has produced `.github/.pipeline-context/context/descriptions.json`, the same load also attaches the **semantic layer** — what each significant node is for, how it fails, what it touches — onto those nodes, so a single query returns both the shape and the meaning. That input is optional: a graph without it is still a valid, useful graph.

## When to Use
- The user asks to "build a graph", "load the code into Neo4j", "map connections/dependencies", or "visualize the architecture"
- Requires `.github/.pipeline-context/artifacts.json` to already exist — run Code Cartographer's scan first if it's missing or stale

## Graph Model
| Node | Key property | Notes |
|---|---|---|
| `Module` | `name` | One per Maven module (service) |
| `Package` | `name` | Java package |
| `Type` | `id` (FQN) | class/interface/enum/record |
| `Method` | `id` (`TypeId#name(paramTypes)`) | Disambiguates overloads |
| `Endpoint` | `id` (`METHOD path`) | Derived from `@GetMapping`/`@PostMapping`/etc. + class-level `@RequestMapping` base path |
| `MavenDependency` | `ga` (`groupId:artifactId`) | Third-party libraries |
| `ExternalType` | `name` | Base class/interface not defined in this repo (e.g. `MongoRepository`) |

| Relationship | Meaning |
|---|---|
| `Module -[:CONTAINS]-> Package/Type` | Structural containment |
| `Package -[:CONTAINS]-> Type` | Structural containment |
| `Module -[:DEPENDS_ON]-> MavenDependency` | pom.xml dependency |
| `Type -[:EXTENDS]-> Type/ExternalType` | Class superclass or interface extension |
| `Type -[:IMPLEMENTS]-> Type/ExternalType` | Class implementing an interface |
| `Type -[:HAS_METHOD]-> Method` | Method ownership |
| `Type -[:EXPOSES]-> Endpoint` | REST endpoint exposed by a controller |
| `Type -[:USES]-> Type` | Best-effort: a field's type references another known type (dependency signal) |
| `Method -[:CALLS]-> Method` | Best-effort call graph: resolves same-class calls, field-based calls (`this.foo.bar()`/`foo.bar()`), and static calls on a known type, by matching method name (call sites don't carry static argument types, so overload resolution is name-based only) |
| `ContextNote -[:ABOUT]-> Module/Type` | A cross-cutting fact that belongs to no single node (shared datastore, service dependency with no Java call edge) attached to everything it concerns |

## Semantic Layer (`ctx*` properties)

Loaded from `.github/.pipeline-context/context/descriptions.json` onto `Module`, `Package`, `Type`, `Method`, `Endpoint` and `ExternalType` nodes.

**Everything above this section is parser output. Everything in it is interpretation.** That distinction is why every property is namespaced `ctx*` — the prefix is load-bearing, not cosmetic. Nodes in this graph may already carry a generated `description` that only restates the AST (`"GenderType — enum in module sheduler-service. 0 methods"`); keeping the semantic layer in its own namespace means it never overwrites generated text, is never mistaken for it, and can be searched on its own without the boilerplate drowning it.

| Property | Meaning | Read by |
|---|---|---|
| `ctxSummary` | What this is and what it is for, in the domain's words | everyone |
| `ctxRole` | Architectural role: controller, service, repository, contract, framework-base, … | everyone |
| `ctxCriticality` | `low`/`medium`/`high`/`critical` — damage a defect here does, not code complexity | Blast Radius |
| `ctxResponsibilities` | The distinct jobs this node owns | everyone |
| `ctxInvariants` | What must hold true for this to be correct | Root Cause |
| `ctxFailureModes` | How this realistically breaks and what the caller sees | Root Cause |
| `ctxSideEffects` | Writes, outbound calls, publishes, mutated state | Blast Radius |
| `ctxDataTouched` | Collections, tables, entities, config keys | Blast Radius |
| `ctxUpstream` / `ctxDownstream` | What drives this / what it depends on, including edges the call graph cannot see | Blast Radius |
| `ctxTestHints` | The request to replay, the fixture needed, the boundary to assert | QA Runner |
| `ctxOpenQuestions` | What the source could not settle | everyone |
| `ctxEvidence` | Where the claims came from (`file:line`) | everyone |
| `ctxAuthor` | `architect-agent` or `llm:<model-id>` | everyone |
| `ctxConfidence` | `high`/`medium`/`low` — weight claims by this | everyone |
| `ctxFingerprint` / `ctxStale` | Which shape of the code this was written for, and whether it has drifted since | everyone |

**`ctxStale = true` means ignore it.** The code changed after the description was written, so it may no longer be true. Re-run Context Weaver to refresh.

Descriptions only ever annotate nodes the parser found — the loader uses `MATCH`, never `MERGE`, so a description with an unrecognized id is skipped rather than inventing a node behind it.

## Procedure
1. Ensure `.github/.pipeline-context/artifacts.json` exists (run Code Cartographer first).
2. Set up connection details once: copy [.env.example](./.env.example) to `.env` in this folder and fill in your Neo4j instance (`NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`, `NEO4J_DATABASE`). Never commit the real `.env` — it's already gitignored.
3. Install dependencies: `cd .github/skills/01c-graph-forge && npm install`
4. Run: `npm run graph` (or `node scripts/build-graph.js`)
5. The script creates uniqueness constraints and `MERGE`s nodes/relationships, so re-running after a fresh scan updates the graph instead of duplicating it.

See [build-graph.js](./scripts/build-graph.js) for implementation details.

## Useful follow-up Cypher
```cypher
// Which types does a service expose over REST?
MATCH (m:Module {name: 'employee-service'})-[:CONTAINS]->(t:Type)-[:EXPOSES]->(e:Endpoint)
RETURN t.name, e.method, e.path;

// Fan-in: most-depended-upon types
MATCH (t:Type)<-[:USES]-(other)
RETURN t.name, count(other) AS dependents ORDER BY dependents DESC;

// Function-level call graph for a controller endpoint
MATCH p=(c:Type {name: 'EmployeeSchedulerController'})-[:HAS_METHOD]->(:Method)-[:CALLS*1..4]->(:Method)
RETURN p;

// Most-called methods (fan-in at function level)
MATCH (m:Method)<-[:CALLS]-(caller)
RETURN m.name, count(caller) AS callers ORDER BY callers DESC;
```

## Querying the semantic layer

```cypher
// Symptom -> code. Find the node whose documented failure modes match a reported
// symptom, without needing to know the class name first.
CALL db.index.fulltext.queryNodes('context_search', 'salary returns null department')
YIELD node, score
RETURN labels(node)[0] AS kind, coalesce(node.name, node.id) AS id,
       node.ctxSummary AS summary, node.ctxFailureModes AS failureModes
ORDER BY score DESC LIMIT 10;

// Root Cause: the meaning of a method plus the meaning of everything it calls.
MATCH (m:Method {id: $methodId})
OPTIONAL MATCH (m)-[:CALLS*1..3]->(callee:Method) WHERE callee.ctxSummary IS NOT NULL
RETURN m.ctxSummary AS summary, m.ctxInvariants AS invariants,
       m.ctxFailureModes AS failureModes, m.ctxStale AS stale,
       collect(DISTINCT {callee: callee.name, summary: callee.ctxSummary}) AS downstream;

// Blast Radius: everything a change here can reach that has a side effect, ranked
// by how much damage it does.
MATCH (start:Type {id: $typeId})<-[:USES*1..3]-(affected:Type)
WHERE affected.ctxSideEffects IS NOT NULL AND size(affected.ctxSideEffects) > 0
RETURN affected.name, affected.ctxCriticality, affected.ctxSideEffects, affected.ctxDataTouched
ORDER BY CASE affected.ctxCriticality
  WHEN 'critical' THEN 0 WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END;

// QA: the test plan surface for one service — every endpoint with its hints.
MATCH (m:Module {name: $module})-[:CONTAINS]->(:Type)-[:EXPOSES]->(e:Endpoint)
RETURN e.id, e.ctxSummary, e.ctxCriticality, e.ctxTestHints, e.ctxFailureModes;

// Cross-cutting facts touching a module — the couplings with no code edge.
MATCH (c:ContextNote)-[:ABOUT]->(m:Module {name: $module})
RETURN c.topic, c.ctxSummary, c.ctxConfidence;

// Audit: what is described, by whom, and what has gone stale.
MATCH (n) WHERE n.ctxSummary IS NOT NULL
RETURN labels(n)[0] AS kind, count(*) AS described,
       sum(CASE WHEN n.ctxStale THEN 1 ELSE 0 END) AS stale
ORDER BY described DESC;
```

## Notes
- Self-contained folder (own `package.json`, own `.env`) — can be moved independently
- Credentials live only in the local `.env`; never print the password back to the user or into generated docs
