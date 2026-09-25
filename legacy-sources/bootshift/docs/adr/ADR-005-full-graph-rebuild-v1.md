# ADR-005: Full graph rebuild in v1

**Status:** Accepted
**Date:** 2026-09-10

## Context

After every successful compile the harness needs a current application graph to diff against the last
known-good one. Two options: mutate the existing graph incrementally from the change set, or rebuild
it from scratch.

Incremental mutation is much faster. It is also the classic source of silent drift: a missed
invalidation produces a graph that is subtly wrong, the diff then looks clean, and the scope gate
passes something it should have blocked. The failure is invisible precisely because the mechanism
that would detect it is the thing that is broken.

## Decision

**v1 always rebuilds the full graph.** Agent 14 re-parses every module from the migration workspace
and reconstructs the graph from nothing.

Incremental update may be introduced later, and only behind a graph-equivalence test proving

```text
INCREMENTAL_GRAPH == FULL_REBUILD_GRAPH
```

for the same repository state, compared by structural hash.

## Consequences

- Graph rebuild costs a full parse per edge. On the reference corpus (63 Java files across 6 modules)
  that is a few seconds; on a large monolith it would be minutes. That is a real cost.
- In exchange, the scope gate is trustworthy. An unexpected structural change is a fact about the
  repository rather than possibly an artefact of stale graph state.
- The structural hash is order-independent, so a rebuild that produces the same meaning produces the
  same hash and an empty diff. That property is exactly what a future incremental implementation
  would be tested against.
