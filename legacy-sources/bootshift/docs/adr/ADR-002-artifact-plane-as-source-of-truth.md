# ADR-002: The artifact plane is the source of truth

**Status:** Accepted
**Date:** 2026-09-10

## Context

A long-running pipeline needs to know where it is. The obvious mechanism is a state machine with a
persisted cursor. The obvious failure mode is that the cursor and reality diverge: a stage crashes
after writing artifacts but before updating the cursor, or updates the cursor and then fails to
write, and every later decision is made against a fiction.

## Decision

The state machine says **where the run is**. Versioned artifacts say **what is true**. When the two
disagree, state is reconstructed from validated artifacts, never the other way round.

This is enforced by three mechanisms:

1. **Pointer after write.** A stage writes into `output/<stage>/<timestamp>/`, validates every
   artifact against its JSON Schema, and only then advances `output/<stage>/latest.json`. A failed
   stage leaves the pointer on the last good directory, so the next stage reads valid input or
   refuses.

2. **Refusal on missing input.** A stage that cannot resolve a required upstream artifact exits with
   `STRUCTURED_REFUSAL` and names the command that would produce it. It never proceeds on a default.

3. **State restoration from artifacts.** `RunFactory.restoreState` derives the current state by
   asking which stages have a published `latest.json`, plus whether the baseline manifest records a
   seal. A stored cursor is never trusted on its own.

## Consequences

- Stages are independently runnable and independently re-runnable, which is what R24 requires.
- A crash costs at most the stage in flight.
- Re-running a completed stage is legal and is recorded in history as a re-run rather than rejected,
  because the artifact plane, not the cursor, decides what is true.
- The cost is that a stage cannot cheaply signal "in progress"; a partially written timestamped
  directory simply never becomes current. We accept that: an invisible partial result is safer than
  a visible one.
