# ADR-003: Static and runtime graphs are separate evidence layers

**Status:** Accepted
**Date:** 2026-09-10

## Context

Static analysis can prove that a controller *references* a service. It cannot prove that the bean was
actually created, that the profile was active, that the endpoint was actually mapped, or that a
configuration key was actually bound. The Spring programming model is conditional throughout: the
same source produces different runtime graphs under different profiles and classpaths.

A single merged graph would let a runtime observation quietly overwrite a static relationship, or let
a static inference be reported with the authority of an observation. Both are dishonest.

## Decision

Two layers, never merged:

```text
STATIC/SEMANTIC GRAPH        RUNTIME OBSERVATION GRAPH
built by Agent 03            observed by Agent 04 and Agent 16
rebuilt by Agent 14          every edge carries an evidenceRef
```

`EdgeType.isRuntimeObserved()` partitions the vocabulary. `INJECTS` is what the source says;
`ACTUALLY_INJECTED` is what the running application did. `USES_CONFIG_PROPERTY` is a reference;
`ACTUALLY_BINDS_PROPERTY` is a binding that happened.

The lifecycle is:

```text
G0_BASELINE_STATIC   + Agent 04 observations = G0_BASELINE_ENRICHED
G_EDGE_STATIC        + Agent 16 observations = G_EDGE_ENRICHED
```

The Agent 14 scope gate operates on the **static** diff only. Runtime differences feed Agent 17 and
the final provenance graph, not the pre-test scope decision.

## Consequences

- A runtime edge never overwrites a static edge; both coexist, and a report can say whether a
  relationship was inferred, observed, or both.
- Every runtime edge references the observation that produced it, so it is checkable.
- `PROPERTY_SILENTLY_IGNORED` becomes detectable, because it is exactly the case where the static
  layer has a `CONFIGURES` edge and the runtime layer has no `ACTUALLY_BINDS_PROPERTY` edge.
- The cost is that consumers must be explicit about which layer they are querying. We consider that a
  feature: a question that does not say which layer it means is usually a question that has not been
  thought through.
