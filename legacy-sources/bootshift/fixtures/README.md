# Fixture corpus

Fixtures exist so that a property the harness claims is a property a test can fail on.

| Directory | What it protects |
|---|---|
| `identity/` | The five reattachment rules, in order, and split / merge lineage |
| `transform/` | Every deterministic transformer, input and expected output — including the annotation remover, whose fixture asserts a same-named annotation from another package survives |
| `ledger/` | Eight tamper vectors against the hash chain |
| `schema/` | Valid and invalid artifact instances, including the two failure modes that matter most: a shadowed reserved key and a plaintext secret |
| `impact-evaluation/` | Held-out ground truth for measured impact precision and recall |

## The rule that governs `impact-evaluation/`

A fixture supplies **the input and the ground truth**. It never supplies the prediction.

`AccuracyHarness` builds a file registry over the fixture's `tree/`, runs `ImpactStage`'s own
matcher against it, and grades what that matcher actually returned. An earlier version read a
`predicted_paths` array out of the fixture and compared it against `true_affected_paths` in the same
file — which graded whether the fixture author had written two consistent lists, and reported the
result as the analyzer's accuracy. A self-fulfilling metric is worse than no metric, because in a
report it is indistinguishable from a real one.

Fixtures marked `"tuning": true` are excluded from the reported numbers. The harness does not grade
itself on the cases its rules were derived from.

When no held-out fixtures are present, accuracy is reported `UNMEASURED` — never estimated, never
defaulted.
