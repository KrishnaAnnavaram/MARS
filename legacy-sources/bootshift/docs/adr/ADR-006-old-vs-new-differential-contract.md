# ADR-006: The OLD-vs-NEW differential contract

**Status:** Accepted
**Date:** 2026-09-10

## Context

Compilation, tests and startup are each one evidence dimension. None of them detects a serialization
shape change, an authorization decision flip, a configuration key that silently stopped binding, or a
persistence semantic change. The only mechanism that detects those is running the same scenario
against both applications and comparing the results.

That mechanism is only as good as three things: whether the two environments were actually
comparable, whether normalization hid the difference, and whether an unexplained difference is
allowed to pass.

## Decision

### 1. Environment equivalence is a contract, not an assumption

Every attribute is classified `MUST_MATCH`, `EXPECTED_TO_DIFFER` or `UNCONSTRAINED` before anything
is measured. Locale, timezone, clock strategy, encoding and seed data must match. JDK, Spring Boot,
Hibernate, Jackson and the servlet container are expected to differ — that is the migration.

A dimension whose `MUST_MATCH` attributes are not satisfied is `NOT_COMPARED`, not passed. Comparing
two sides that differ in a controlled attribute produces a number, not evidence.

The provider mode is recorded on every result. A `DELEGATED` environment cannot self-certify a
`MUST_MATCH` attribute, so its equivalence claims are weaker than those of a `MANAGED` one, and the
evidence says so rather than treating the two as interchangeable.

### 2. Normalization is explicit, versioned and hashed

Every rule has an id, a scope, an action and a rationale. The policy hash is part of the evidence
manifest. Nothing is dropped implicitly. Timestamps and trace identifiers are normalized because they
differ by construction; a response body is not.

SQL text is demoted to diagnostic rather than compared, because literal SQL equality is not the
contract — persistence semantics are.

### 3. Classification is three-valued, and one of the values blocks

- `EXPECTED` requires a **VERIFIED** migration fact or a **signed** approval. Not a plausible story.
- `UNEXPECTED` is a migration defect.
- `UNEXPLAINED` blocks the run.

`UNEXPLAINED > 0 => BLOCKED` unless an authorized human decision resolves it with evidence. There is
no configuration switch that turns "we do not understand this difference" into a pass without a named
person putting their name to it.

## Consequences

- The harness will block on differences that turn out to be benign. That asymmetry is intended: the
  cost of investigating a benign difference is much lower than the cost of shipping a real one.
- Dimensions the environment cannot support are reported as `NOT_COMPARED` and cap the achievable
  evidence level for that dimension at E3. The report names which dimensions those were.
- The final claim is never "behaviour is equivalent". It is "these dimensions were compared, for
  these scenarios, under this equivalence contract, with these gaps".
