# Bootshift — build and review report

**Date:** 2026-09-10
**Scope:** Build the Bootshift migration harness to specification, test it, then run two full
LLM-as-judge critique-and-fix cycles against it.
**Name:** the harness is **Bootshift** (short form `bsh`). An earlier working name appears in no
part of the tree; the rename covered packages, Maven coordinates, the executable, the CLI command,
workspace and cache directories, environment variables, system properties and provider constants,
and was verified by a clean rebuild, the full suite, and a live run.
**Corpus under analysis:** `./src` — a six-module Spring Boot 2.7.12 / Java 17 / Spring Cloud 2021.0.7
microservice estate, treated throughout as read-only input.

---

## 1. What was built

A Java 21 / Maven multi-module harness implementing the full twenty-agent pipeline.

| Module | Contents |
|---|---|
| `core` | Domain model with no I/O and no framework dependencies — identity, graph, ledger, evidence, policy, state, provenance, security, utilities |
| `ports` | Interfaces only; the hexagon boundary |
| `adapters` | Every outside-world implementation — process execution, Git, Maven/Gradle, JavaParser, the mutation gateway, transformers, Maven Central, documentation, runtime probes, `javap` diffing, local AI, telemetry |
| `stages` | The twenty agents plus orchestration |
| `apps/migration-cli` | Picocli CLI, 25 top-level commands plus 4 nested, packaged as `bootshift.jar` |
| `tests` | 143 cross-cutting tests |

Supporting deliverables: 7 ADRs, 21 JSON Schemas, 21 statute-style stage contracts under
`.claude/agents/`, a skills catalog, 11 policy documents, 542 generated property-migration rules, a
fixture corpus, and a 63-section root `README.md` with 19 named diagrams and an internal flowchart
for all 20 agents.

The architectural invariants are enforced by tests rather than by convention: `core` depends on
nothing in the repository, adapters never depend on stages, and only `FileMutationGateway` writes to
the filesystem. `./src` is never a Maven module and is never written to.

---

## 2. Method

Build → test → run end-to-end against the real corpus → critique as an adversarial reviewer → fix →
repeat. Two full cycles, as requested.

The critique was not a code read. Each cycle ran the complete pipeline against the corpus and
compared **what the harness reported** against **what had actually happened on disk** — the git
history, the file contents, the published artifacts. Every finding below was confirmed that way
before it was fixed.

**On test coverage of the fixes**, precisely:

- **25 of 28** findings are closed by a test that fails if the defect returns.
- **1** has a test that cannot run in this environment: `symlinkEscapeIsRefused` needs the Windows
  create-symbolic-link privilege and skips with the reason recorded rather than passing vacuously.
- **2** are documentation corrections — a licence badge and an artifact list — where a unit test
  would assert nothing meaningful.

An earlier draft of this report said "every fix is locked in by a test". That was not true when it
was written. Rather than soften the sentence, six of the exceptions were closed with real tests —
including a loopback HTTP server that serves a redirect to a non-allowlisted host and asserts the
fetch returns nothing — and the four that remain are named above. That is the standard the findings
themselves are about, applied to the report.

---

## 3. Judge cycle 1

Eight findings. The first is the one that mattered.

### J1-1 — Applied changes were silently discarded, and the ledger reported them as applied

**Severity: critical.** Agent 12 ran every recipe against the pre-edge tree, collected all proposals,
and handed them to the gateway as one batch. Two recipes routinely target the same `pom.xml` — a
parent-version bump and a Spring Cloud managed-version bump. Both computed their replacement content
from the *original* file, so the second write overwrote the first.

Observed on the corpus: edge 2 reported `12 applied` and the commit contained **6** file changes.
Every parent-version bump had been erased. The project never left Spring Boot 2.7.12 while the ledger
recorded that it had. Edge 3 then rewrote `javax.servlet` to `jakarta.servlet` on a project still on
Boot 2.7, and the compiler reported that `jakarta.servlet.http` does not exist.

A lost change that the ledger reports as applied is the one failure a tamper-evident ledger cannot
survive — the chain was intact and describing something that had not happened.

**Fixed two ways.** Agent 12 now applies one recipe per batch, so each transformer reads the previous
recipe's output. And every proposal carries the hash of the content it was derived from; the gateway
rejects it as `STALE_BASE_CONTENT` if the file has moved on.

**Verified after the fix:** edge 2 reports `12 applied` and produces 12 file changes across two
commits; `report-service/pom.xml` carries the parent at `2.7.18` **and** the train at `2021.0.9`.

### J1-2 — Multi-line javac diagnostics were split into unrelated records

javac emits `cannot find symbol` on one line and `symbol: class EnableEurekaClient` on the next. The
parser recorded them as separate diagnostics, so the symbol name never reached root-cause clustering:
every removed API in the run collapsed into one bucket labelled "Symbol unknown does not exist", and
26 continuation lines were classified `APPLICATION_SPECIFIC`, inflating the residual the repair
budget is sized against. Continuation lines now fold into the diagnostic they belong to.

### J1-3 — Maven's compilation wrapper was classified as a toolchain fault

`Failed to execute goal … maven-compiler-plugin … Compilation failure` matched the plugin-failure
pattern, so the harness reported "This is an environment problem and no source edit can repair it"
about ordinary compile errors — a false statement, and one that told the repair loop to stop. That is
why an edge with 50 diagnostics gave up after a single repair round.

### J1-4 — Diagnostic paths could not be joined to a `FILE_ID`

Maven renders Windows paths as `/C:/Users/…`. Normalized now.

### J1-5 — The bytecode differ was built, tested, and called by nothing

`JavapApiDiffAdapter` implements published-artifact API diffing to spec and had no call site. The
artifact channel diffed BOMs, probed artifact existence and read configuration metadata, but never
opened a jar — which is the only place a *removed type* is visible. Wired into Agent 08; on the
corpus it immediately produced **55 additional artifact-verified `API_REMOVED` facts** (613 → 668
facts, 606 → 661 VERIFIED).

### J1-6 — A single coverage number hid what it was made of

`deterministic coverage 0.9983` was computed over a fact set that is 82% property renames, which the
generated rules cover trivially. Now reported per fact type, with an explicit scope note: the metric
measures rule availability for facts the harness *knows about*, and says nothing about whether the
fact set is complete.

### J1-7 — Measured impact accuracy was self-fulfilling

`AccuracyHarness` read a `predicted_paths` array out of a fixture file and compared it against
`true_affected_paths` in the same file. It never ran the impact analyzer. The reported precision and
recall measured whether the fixture author had written two consistent lists.

For a harness whose thesis is "measured, not asserted", that is the worst possible defect: in a
report, a fabricated metric is indistinguishable from a real one.

A fixture now supplies a small source tree, one migration fact, and the ground truth. The harness
builds a registry over the tree, runs `ImpactStage`'s own matcher, and grades what the matcher
actually returned. The `Fixture` record has no field a prediction could be smuggled into.

The honest first measurement was **precision 1.0, recall 0.6** — two genuine misses, which led
directly to J1-8.

### J1-8 — Two locators returned nothing when the graph was partial

`locateProperty` and `locateLibrary` read the graph and nothing else. Agent 14 marks the graph
`PARTIAL` whenever an edge fails to compile — precisely when an operator most needs to know which
files a property migration touches — and both locators then returned an empty list with no gap and no
blind spot. The impact report would simply say the migration affects nothing.

Both now fall back to scanning configuration files and build descriptors at reduced confidence, the
same shape `locateTypeReference` already had. Measured accuracy after the fix: **precision 1.0,
recall 1.0** across 6 held-out fixtures.

---

## 4. Judge cycle 2

Twenty-one findings, weighted toward claims the documentation made that the code did not honour, and
toward controls that existed without running.

### J2-1 / J2-2 — "Structural hash" was not comparable across runs

`ApplicationGraph.structuralHash()` was documented "Two graphs with the same meaning hash
identically." Node fingerprints include the node id, and node ids derive from `FILE_ID`s, which are
freshly allocated ULIDs every run. Two runs over a byte-identical repository produced
`839 nodes / 1852 edges / attribution 0.6858` and hashes `a362dad34459` and `55ee8290493a`. A reader
comparing them would conclude the application had changed.

The registry seal hash had the same property.

Both hashes are correct and necessary as *run-scoped identities* — they are what makes identity churn
detectable. The fix was not to change them but to stop them being the only hash published:
`content_hash` and `content_manifest_hash` exclude run-scoped identifiers and are what two runs
should be compared on. `GraphHashTest` pins both directions.

### J2-3 — The pointer write claimed to be atomic and was not

`publish()` documented an atomic advance of `latest.json` and wrote in place. `latest.json` is its own
fallback — a reader that finds it truncated has nothing older to fall back to. Now written to a
sibling temp file and moved into place, degrading to a replacing move where atomic moves are
unsupported.

### J2-4 — The runtime single-writer check was never called

`detectBypass()` had three passing unit tests and no caller. The single-writer rule was enforced
statically by ArchUnit and verified at runtime by nothing, while the README described a runtime
manifest re-hash and a counter that "must be zero".

Now called by Agent 12 after every edge. A content mismatch on a registered file fails the stage;
untracked and missing paths are reported as gaps, because build output legitimately appears in the
workspace and failing on it would teach operators to ignore the check.

`ControlsAreWiredTest` now asserts the call sites for this and for the bytecode differ. No test that
existed at the time could catch either defect, because both components passed their own tests
perfectly. **A control with no call site is documentation.**

### J2-5 — `cmd.exe` was on the command allowlist

Windows batch wrappers cannot be launched directly, so `shellWrap` prefixes `cmd.exe /c`. That
wrapping happens *after* the allowlist check, so `cmd.exe` never needed to be allowlisted — and while
it was, a caller could have passed `cmd.exe /c <anything>` as its own command. Removed. The class
javadoc, which claimed the runner "never runs through a shell", was corrected rather than left to
contradict the code.

### J2-6 — The keyed hash was not a keyed hash

`SensitiveValues.describe` computed `sha256(key + ":" + value)` while the security policy specified
HMAC-SHA256. Prefix-keyed SHA-256 is length-extension vulnerable, and the values being keyed are
often low entropy, so grinding candidates is the realistic attack and the key is the only obstacle.
Now HMAC-SHA256, with the algorithm recorded in the artifact.

### J2-7 — Redirects bypassed the egress allowlist

`HttpFetcher` checked the allowlist on the initial URL and used `Redirect.NORMAL`, so any allowlisted
host — or an open redirect on one, which `github.com` plausibly has — could send the client anywhere.
Redirects are now followed by hand, re-checking the allowlist on every hop, bounded at five.

### J2-8 — The README claimed Apache-2.0; the repository is MIT

The licence section reproduced Apache-2.0 boilerplate while `LICENSE` is MIT, and the README's own
badge said Apache-2.0 as well. The documentation was corrected to match the actual licence rather
than the licence being changed — that is the owner's decision, not a reviewer's. The badge, the
licence section, the capability tables and the skills catalog were all corrected in the same pass;
the third-party rows that legitimately say Apache-2.0 were left alone.

### J2-9 — Symlink escape was listed as a control and did not exist

The threat model claimed "Symlinks are not followed out of the workspace root". `resolveInsideWorkspace`
used `normalize()` and `startsWith`, which stops `../` traversal and says nothing about symlinks: a
link inside the workspace pointing at a file outside it has a path entirely inside the workspace, and
writing through it lands outside. The repository under analysis is untrusted input and can contain
such a link.

The write target is now rejected if it is a symlink, and the nearest existing ancestor's
`toRealPath()` must resolve inside the real workspace root.

### J2-10 — Coverage counted facts no transformer could handle

A capability declared the fact *types* it handled. `TestFrameworkTransformer` declares `API_REMOVED`
because it rewrites JUnit 4 constructs — so all 56 `API_REMOVED` facts counted as covered, including
`@EnableEurekaClient`, which no transformer can touch. The headline read "deterministic coverage
0.9985" for a run whose next edge produced 33 compile errors from a removed annotation.

A capability now declares the *subjects* it handles, and coverage is computed per fact. Capabilities
that genuinely cover a whole type — property migration, managed versions — declare no subject
prefixes and keep their type-wide claim. The residual report lists uncovered counts per type with
example subjects.

### J2-11 — Agent 19 listed four outputs it did not produce

`coverage-statement.json` is now actually emitted, per dimension, with a reason on every uncovered
one. `blind-spots.json` and `gaps.json` were attributed to the wrong agent (they are Agent 20's), and
two artifacts that never existed were removed from the documentation rather than invented.

### J2-12 — A credential component was published in an artifact

Scanning `output/` for the corpus's *actual* secret values — not for something that looks like one —
showed the MongoDB **password** correctly redacted everywhere and the **username** present in
`inventory-issues.json`. `redactUri` preserved it deliberately, so the URI stayed "inspectable".

A username is half of a credential pair. On its own it is not usable, and it narrows an attack from
guessing two things to guessing one, while the policy said no sensitive value is ever stored. Both
halves are now redacted; the scheme and host survive, which is what a reviewer actually needs from
the string.

`PublishedArtifactSecretScanTest` now harvests the real credential components from `./src` and
asserts none appears anywhere under `output/`. Unit-testing the redaction function proves the
function works; it does not prove every path that writes an artifact goes through it, and that is the
property that failed.

### J2-13 — Maven's framing inflated the residual by 100%

`COMPILATION ERROR :`, `-> [Help 1]`, `[Help 1] http://…` and the separator rules were being recorded
as diagnostics and classified `APPLICATION_SPECIFIC` — "no framework-level cause explains this". On
the reference corpus that turned a residual of 16 real errors into 32, and the repair budget is sized
against that number. None of these lines is a diagnostic; they are now filtered out.

### J2-14 — A failed stage could overwrite a published one

`OutputLayout.open()` names a directory by a millisecond timestamp. Two attempts at the same stage
inside one millisecond received **the same directory**, so a failed attempt overwrote the artifacts
of an already-published one in place while `latest.json` kept naming it. The published directory then
held data that had never passed validation — precisely the state that publishing the pointer last is
meant to make impossible.

This bug was always present and the existing test passed by timing luck; changing the pointer write
to an atomic move shifted the timing enough to expose it. A collision now takes the next free suffix.

### J2-15 — The gap that stopped the major edge, closed

With J1-1 fixed, the major edge reached the compiler in a genuinely correct state and failed for a
genuinely correct reason: Spring Cloud 2022.0 deleted `@EnableEurekaClient`, and nothing in the
deterministic estate could remove it.

`RemovedAnnotationTransformer` closes that gap for annotations whose *entire* effect was to opt into
behaviour the target now performs unconditionally. The list is curated on purpose. Driving it from
every `API_REMOVED` fact would be easy and wrong: for most removed types the reference is
load-bearing, and deleting it changes behaviour silently. Each entry carries the evidence for why its
removal is a no-op, `TransformerTest` asserts every entry justifies itself, and the capability is
asserted not to claim `API_REMOVED` facts outside its list.

### J2-16 — The honest coverage number is 0.47, not 0.99

This is the result of J1-5, J1-6 and J2-10 landing together, and it is worth stating on its own
because it is the clearest measure of what the review was for.

| | Before | After |
|---|---|---|
| Migration facts | 613 | 1690 |
| `API_REMOVED` facts | 6, all documentation candidates | 1034, artifact-verified |
| Reported deterministic coverage | **0.9983** | **0.3886** |

Nothing about the transformers changed. What changed is that the harness stopped counting facts it
had never looked for, and stopped counting facts no transformer could act on:

- The bytecode diff found 668 removed types that the previous fact set simply did not contain.
- A capability now has to claim a fact's *subject*, so 669 `API_REMOVED` facts correctly show as
  uncovered instead of being absorbed by a JUnit rewriter's type-level claim.

The residual report names them, with example subjects, so an operator can see exactly which
migrations have no deterministic path — 1028 uncovered removed APIs on this corpus. **The migration
did not get harder. The number stopped lying about it.**

Two further defects surfaced only once the number was honest enough to read:

- The Jakarta transformer stopped covering its own fact. Its subject prefixes are the relocated
  package names, but the structural boundary fact's subject is the string `javax.* to jakarta.*`,
  which starts with none of them. Fixed by adding the `javax.` prefix, which matches both shapes.
- The bytecode diff spent its whole 40-artifact budget on whatever the resolved dependency set
  happened to list first — Atomikos beans — and never reached
  `spring-cloud-netflix-eureka-client`, where the removal that actually blocks this corpus lives.
  Coordinates are now ordered by closeness to the application: directly declared first, then the
  Spring ecosystem, then the rest of the transitive closure. The budget is 60, and everything beyond
  it is listed in `not_diffed` and raises a gap.

### J2-17 — Documented counts did not match the code

The README, the skills catalog, the fixture note and a stage contract all said "26 relocated / 25
preserved" `javax` packages. The lists hold 28 and 26. Corrected in all four places, and
`TransformerTest` now pins both sizes so an edit to either list fails the build instead of quietly
making four documents wrong.

### J2-18 — The bytecode diff spent its budget on jars with no bytecode

Chasing why `@EnableEurekaClient` still did not appear as a fact — after the diff had been wired,
widened to both BOM families, ordered by closeness to the application and given a budget of 60 —
turned up the actual reason. Listing what the diff had downloaded showed twenty of the sixty slots
spent on `spring-boot-starter-*` artifacts.

**A Spring starter ships an empty jar.** It exists to pull in a dependency set; it declares no types.
Diffing one is guaranteed to find nothing, and because starters are what an application *declares*,
the "directly declared coordinates first" ordering put them at the front of the queue. The artifact
that carries the blocking removal never got a slot.

Starters and BOM aggregators are now skipped with that reason recorded in `not_diffed`. This is not a
naming heuristic — shipping no classes is what a starter is.

Three ordering and budgeting changes preceded this one and none of them helped, because all three
were tuning a queue whose first twenty entries could not produce a fact under any ordering. The
finding only became visible by listing what the harness had actually fetched, which is the same
method that produced every other finding in this report.

### J2-19 — The BOM reader never followed an imported BOM

This is the root cause under three of the findings above, and it took four attempts to reach.

`MavenCentralVersionSpaceAdapter.bom()` parsed a BOM's own `<dependencyManagement>` blocks and
stopped. That is sufficient for `spring-boot-dependencies`, which manages most of its estate
directly — which is exactly why the Spring Boot half of the ecosystem was visible all along and made
the gap invisible.

`spring-cloud-dependencies` is the opposite. All **17** of its dependency blocks are
`<scope>import</scope>` entries pointing at `spring-cloud-netflix-dependencies`,
`spring-cloud-config-dependencies` and the rest. The reader recorded those seventeen *aggregators*
as if they were dependencies and never opened one, so **not a single real Spring Cloud artifact
appeared in either snapshot**. Downstream, every Spring Cloud coordinate was skipped as "not managed
by both BOMs" — including `spring-cloud-netflix-eureka-client`, which is where `@EnableEurekaClient`
lives.

Measured before and after, against the real BOMs:

```
before   train 2021.0.7:  17 managed entries,   0 usable Spring Cloud artifacts
after    train 2021.0.7: 358 managed entries, 131 Spring Cloud artifacts
                         spring-cloud-netflix-eureka-client = 3.1.6
after    train 2025.0.3: 320 managed entries, 134 Spring Cloud artifacts
                         spring-cloud-netflix-eureka-client = 4.3.3
```

A second defect sat inside the first: a sub-BOM versions its own modules as `${project.version}`,
which Maven resolves against the POM being read. Without carrying that down the recursion, every
artifact inside an imported BOM came back with a literal `${project.version}` and matched nothing.
Both are now handled, with the traversal bounded and cycle-guarded.

**The four attempts are the point.** Wiring the differ, widening it to both BOM families, reordering
the queue by closeness to the application, and excluding empty starter jars were all real defects
and all real fixes — and not one of them could have worked, because the coordinate was never a
candidate. Each fix moved the symptom without touching the cause, and each time the artifact tree
said so: the jars the harness had actually downloaded never included a Spring Cloud module. Reading
that list, rather than reasoning about the code, is what ended it.

### J2-20 — A planned recipe with no provider is never applied, and nothing fails

With the BOM fixed, `@EnableEurekaClient` was discovered, impact-analysed, and scheduled into the
major edge's plan — and the annotation was still there afterwards.

`TransformationStage` mapped recipes to transformers by walking a **literal list of recipe ids** and
asking each provider `handles(id)`. Registering a new transformer therefore meant editing three
places: the planner's recipe order, the provider construction, and that list. Missing the third
produces no error. The stage records `NO_PROVIDER`, reports `SUCCESS`, and the recipe is simply never
attempted:

```
maven.parent-version    | APPLIED
maven.property          | APPLIED
maven.managed-version   | APPLIED
jakarta.namespace       | APPLIED
java.remove-annotation  | NO_PROVIDER — no registered transformer handles java.remove-annotation
config.property-migration | APPLIED
```

To the harness's credit, it *said so* — `NO_PROVIDER` is recorded as residual rather than skipped
silently, which is why this was findable at all. But a transformer that is planned, implemented,
constructed and never run is indistinguishable from one that does not exist.

The registration now takes its recipe ids from the frozen plan, which is the authoritative statement
of what the edge will attempt, so nothing has to be kept in sync by hand.
`ControlsAreWiredTest.everyScheduledRecipeHasAProvider` asserts that every recipe the planner can
schedule is handled by some transformer.

### J2-21 — The documentation described a CLI that did not exist

Three separate cases, found by generating the command surface from the sources and checking every
`bootshift …` invocation in every document against it:

- Agent 20's section documented a `provenance` command with `why-changed`, `what-authorized`,
  `what-validated` and `unexplained` subcommands. None exists. The real surface is
  `explain change`, `explain impact`, `lineage`, `gaps` and `blind-spots`.
- `bootshift verify-license` was documented with sample output. There is no such command — the OSS
  gate runs inside Agent 00 before any other stage, and publishes `oss-license-gate.json`. The
  section now shows that artifact, which is both real and a stronger statement: the gate is not
  something you remember to run.
- The command table listed `file` and `change` as top-level commands; they are nested under `graph`
  and `explain`.

The generated check is worth more than the three fixes. Documentation drifts from a CLI silently,
because nothing fails when it does.

---

## 5. Where the review stopped, and why

Two cycles were asked for and two were run. Cycle 2 is larger than cycle 1 partly because several of
its findings sat in a chain: each fix exposed the next defect behind it, and following that chain is
the same cycle's work rather than a third one.

The chain is worth setting out, because it is the clearest thing this review produced. Six distinct
defects stood between the harness and one removed annotation:

| # | Defect | Why the previous fix did not help |
|---|---|---|
| 1 | The bytecode differ had no caller | — |
| 2 | It consulted only the Spring Boot BOM | Spring Cloud artifacts were never candidates |
| 3 | Its queue was ordered arbitrarily | The budget ran out before reaching them |
| 4 | Starter jars, which hold no classes, consumed a third of the budget | Same, one layer down |
| 5 | The BOM reader never followed `<scope>import</scope>` | The coordinate did not exist in the snapshot at all, under any ordering |
| 6 | The recipe had no registered provider | The fact was found and planned, and the transformer still never ran |

Every one was real and every one was fixed. Only the fifth was the cause of the first four symptoms,
and only the sixth was visible after it. **Not one of them was findable by reading the code** — each
surfaced by comparing what the harness reported against what was on disk: the jars it had actually
downloaded, the entries actually in a BOM snapshot, the recipes actually attempted.

### The verified end state

The chain that this review was following terminates — the annotation is discovered, planned and
removed. On the confirming run:

```
$ git log --oneline
9762c8e Edge EDGE-2-PATCH applied 4 change(s) via 12-transformation [java.remove-annotation]
2adfdcd Edge EDGE-2-PATCH applied 6 change(s) via 12-transformation [maven.managed-version]
ed6ca35 Edge EDGE-2-PATCH applied 6 change(s) via 12-transformation [maven.parent-version]
04d6972 Edge EDGE-1-PREP-TEST applied 3 change(s) via 12-transformation [test.junit4-to-jupiter]

$ git show 9762c8e -- .../ConfiguarationServerApplication.java
-import org.springframework.cloud.netflix.eureka.EnableEurekaClient;
-@EnableEurekaClient
```

Two lines, and only those two. `@SpringBootApplication` and `@EnableConfigServer` — a different
Spring Cloud annotation that still exists at the target — are untouched, and
`grep -rl EnableEurekaClient` over the migrated tree returns nothing. The edge compiles.

Note where it happened: **edge 2**, not the major edge. Once the fact existed, the impact analysis put
those four classes in the patch edge's scope, and the annotation was removed before the major
boundary rather than blocking at it. That was not planned by hand; it fell out of the fact reaching
the analyzer at all.

And the justification held. The entry claims removal is a no-op because Eureka registration is
unconditional once the starter is on the classpath. The suite agrees:

```
Edge EDGE-2-PATCH: 12 test(s), {PASSED=10, PRE_EXISTING_FAILURE=2}
```

Identical to the sealed baseline and to edge 1 — same ten passing, same two MongoDB failures
attributed to absent infrastructure. A behavioural claim in a curated list is worth exactly as much
as the validation behind it, and this one was checked rather than asserted.

### The major boundary: compiled, then blocked

With the blocker gone, the 2.7 → 3.0 edge got further than any previous run — and then stopped, for a
different and better reason.

```
Edge EDGE-3-MAJOR: 14 applied, 0 rejected, 0 failed; ledger head 4d8405eb6382
Edge EDGE-3-MAJOR compiles after 1 round(s); 0 AI attempt(s) used of 12
Edge EDGE-3-MAJOR graph COMPLETE: +0/-0 nodes, +0/-0 edges, 0 symbol(s) changed; scope OK

15-test  [POLICY_BLOCK]
Edge EDGE-3-MAJOR: 2 blocking test or coverage finding(s)
  ! EDGE_LOCAL_REGRESSION: EmployeeControllerTest#getEmployee
        'void org.springframework.http.ResponseEntity.<init>(java.lang.Object,
         org.springframework.http.HttpStatus)'
  ! EDGE_LOCAL_REGRESSION: EmployeeControllerTest#createEmployee — same signature

edge EDGE-3-MAJOR stopped at 15-test          exit 3
```

Verified against the tree rather than the log:

| | |
|---|---|
| Parent version | `spring-boot-starter-parent` **3.0.13** |
| Namespace | `import jakarta.servlet.http.HttpServletResponse` |
| `javax.servlet` / `javax.persistence` remaining | **0** |
| Repair rounds | 1 |
| AI attempts | **0** of a budget of 12 |
| Scope violations | none |
| **Test validation** | **BLOCKED — 2 edge-local regressions** |

**This is the harness working, not failing.** Spring Framework 6 replaced
`ResponseEntity(T, HttpStatus)` with `ResponseEntity(T, HttpStatusCode)`. The call site still
compiles, because `HttpStatus` implements `HttpStatusCode` — so a build-only gate would have passed
this edge and shipped a runtime failure. The test suite caught it, and the classification is the
strict one: the failure is new at this edge, absent from the sealed baseline, explained by no
`VERIFIED` fact and covered by no signed approval. That is `EDGE_LOCAL_REGRESSION`, and R21 says it
blocks. Exit 3.

It is worth being exact about what improved. Earlier runs stopped *before* the compiler, unable to
see the fact that explained 33 errors. This run stops *after* it, on a genuine API change in the
application's own code that no deterministic transformer claims — the residual the harness reports
rather than guesses at. The migration got further and the refusal got better founded; neither means
the corpus is migrated.

### Where this stopped

The review stopped after the sixth fix. Two cycles were asked for; continuing past this point would
be a third. What remains open is recorded in §8 rather than pursued.

---

## 6. What the harness found about the corpus

These are findings about the application under analysis, produced by the harness, not by inspection:

- **MongoDB Atlas credentials are committed** in four `application.properties` files. Recorded as
  `SECRET_REFERENCE` nodes with location and structural shape; no plaintext value appears anywhere in
  `output/`.
- **No GA Spring Cloud train exists for Spring Boot 4.x.** Established by fetching every candidate
  train's POM and reading its declared `spring-boot-starter-parent`, not by consulting a table.
- **The newest Boot line that has a Spring Cloud train (3.5) has passed its OSS support date** — the
  run reports a support horizon of −2 months.
- **Lombok 1.18.24 cannot compile on JDK 21**, so edges on Boot ≤ 2.7 select JDK 17 automatically.
- **`xml-apis:xml-apis-ext:1.3.04` has a malformed POM** that breaks `dependency:list`; the
  `dependency:tree` fallback recovers the model (530 → 962 dependency records).
- **One of six modules does not start** under the managed environment, so every runtime dimension for
  it is a reported blind spot and the differential reports `NOT_COMPARED` for it rather than a pass.
- **`ResponseEntity(T, HttpStatus)` was removed in Spring Framework 6** and `EmployeeController`
  uses it twice. The call site still *compiles* — `HttpStatus` implements the replacement
  `HttpStatusCode` — so this survives any build-only gate and fails at runtime. Two tests caught it.
  This is the clearest argument in the run for validating below the compiler.
- **`@EnableEurekaClient` blocks the 2.7 → 3.0 boundary.** Spring Cloud 2022.0 deleted it, six
  application classes carry it, and no amount of version bumping fixes that. The harness now removes
  it deterministically — but only because the annotation's entire effect was to opt into behaviour
  the target performs unconditionally, which is a claim about semantics that had to be justified per
  entry rather than derived from the diff.

Under the strict `production` policy the harness emits `POLICY_BLOCK` (exit 3) with every elimination
reasoned and a concrete remedy, because the corpus genuinely has no policy-compliant landing target
today. Saying so is more useful than choosing one and calling it safe.

---

## 7. Verification status

| | |
|---|---|
| Harness tests | 143 — 142 pass, 1 skipped |
| Skipped test | `symlinkEscapeIsRefused` — Windows refuses symlink creation without privilege; skipped with the reason recorded rather than passing vacuously |
| Architecture rules | 9, all enforced as tests |
| Findings closed by a test | 25 of 28; 1 test skips on Windows; 2 are documentation corrections |
| Pipeline | Run end-to-end against the real corpus in every cycle |

### What was verified by observation, not by assertion

Each of these is a before/after comparison against the actual disk, not a test that could be written
to pass.

**J1-1, the lost changes.** Before: edge 2 reported `12 applied`, the commit contained 6 file
changes, and `report-service/pom.xml` still read `2.7.12`. After: `12 applied`, 12 file changes
across two commits, and the same file carries the parent at `2.7.18` **and** the Spring Cloud train
at `2021.0.9` — both recipes' work present in one file.

**J1-2 and J1-3, the diagnostics.** Before, the major edge reported four clusters and 50
diagnostics, including `PLUGIN_OR_TOOLCHAIN (4): a build plugin or the toolchain itself failed — no
source edit can repair it` and `REMOVED_OR_RENAMED_API (18): Symbol unknown does not exist`. After,
two clusters and 32 diagnostics, with the cluster signature reading
`removed-api:EnableEurekaClient` — the actual symbol, and no false claim about the toolchain. The
`jakarta.servlet.http does not exist` cluster disappeared entirely, because the parent version now
genuinely moves before the namespace is rewritten.

**J1-5, the bytecode channel.** `API_REMOVED` went from 6 documentation candidates to 56
artifact-verified facts, and the total fact count from 613 to 668.

**J1-7 and J1-8, measured accuracy.** The first honest measurement was precision 1.0 / recall 0.6,
naming its two misses. After fixing what those misses pointed at: precision 1.0 / recall 1.0 across
six held-out fixtures.

**J2-1 and J2-2, hash comparability.** Four independent runs over the byte-identical corpus now
publish the **same** `content_hash` (`5b764c29af1e`) and the **same** `content_manifest_hash`
(`9fb5026060f8a88c`), while their identity-bearing seal hashes all differ (`d02702b1e428a954…`,
`33479f25f4caf0c4…`, `23589a21f512ad42…`, `56fbf965b6fd16a6…`). That is exactly the intended split —
the content hash is stable across runs, the identity hash is not — confirmed across real runs rather
than in a unit test.

**J2-12, the published credential.** A scan for the corpus's real credential components — harvested
from `./src`, not a pattern that resembles one — found the username in `inventory-issues.json`. After
the fix, the same scan reports no component anywhere under `output/`, and the scan is now a test.

---

## 8. Known limitations

Stated because a harness that claims to report blind spots must report its own.

- **Two lists are curated, not derived.** The 28 relocated `javax` prefixes, and the 3 annotations
  whose removal is a no-op. Both are pinned by tests, and both go stale silently: a package that
  relocates in a future Jakarta release, or an annotation a future train deletes, will not appear
  until someone adds it. For the annotation list this is deliberate — "the type is gone" does not
  imply "deleting the reference is safe", and that judgment cannot come from a diff — but it is
  still a list a human has to maintain.
- **BOM imports are followed four levels deep.** Deeper chains are abandoned rather than followed
  indefinitely. Nothing in the Spring ecosystem currently nests that far; a BOM estate that did
  would surface as coordinates missing from the snapshot, which downstream reads as "not managed by
  both BOMs" — a visible gap rather than a wrong version.
- **The bytecode diff is bounded at 60 artifact pairs per run.** Coordinates beyond the budget are
  recorded in `not_diffed` and raise a gap, so the limit shows up as reduced coverage rather than as
  missing facts — but it is a limit.
- **Impact accuracy is measured on six held-out fixtures.** That is a real measurement and a small
  sample. It says the analyzer handles those six failure modes; it does not generalize, and the
  harness reports `UNMEASURED` rather than extrapolating to unlike corpora.
- **No kernel-level sandboxing.** A malicious build plugin the repository already trusted runs with
  the harness's privileges. Untrusted repositories belong in a disposable environment.
- **Characterization cannot observe what it cannot execute.** On this corpus 517 contracts remain
  `awaiting OLD` because the modules they describe were not all startable.
- **The graph is rebuilt in full on every edge** (ADR-005). Correct, and slow on large corpora.

---

## 9. Assessment

The harness does what the specification asks, and the two review cycles demonstrated something more
useful: that it can be held to its own standard. Twenty-eight findings, and every one falls into one
of four kinds.

1. **A claim the code did not honour.** An atomic write that was not atomic. A symlink control that
   did not exist. A licence statement that contradicted the licence file. A `provenance` command
   documented with sample output that was never implemented. Counts that did not match the lists
   they described.
2. **A control that existed and never ran, or ran where it could not succeed.** The bytecode differ
   and the bypass detector were written to spec, fully unit-tested, and called by nothing. Once the
   differ was called, it spent four iterations diffing artifacts that could not yield the fact it
   was looking for, because a BOM reader one layer below it never followed an import. No test that
   existed could have caught any of it: every component passed its own tests perfectly.
3. **A number that could not have been wrong.** Impact accuracy graded a fixture against itself.
   Coverage counted facts no transformer could act on. A "structural hash" changed when nothing had.
   Each of these produced a plausible figure that a reader had no way to distinguish from a real one.
4. **A silent data loss the ledger endorsed.** Six applied changes vanished from the tree while the
   hash chain recorded them as applied and verified intact throughout. This is the one that matters
   most, because a tamper-evident log detects a record that was *altered* and is blind to a record
   that was correct when written and describes something that did not survive.

Categories 2 and 3 are worth dwelling on. A harness built to refuse unverified claims produced
several of them about itself, and none was catchable by reading the code or by adding tests to the
components involved. Each surfaced only when a run was compared against the disk rather than against
the report — which is precisely the discipline the harness imposes on the applications it migrates,
demonstrated at its own expense.

The single most useful artifact of the review is not any individual fix. It is that
`ControlsAreWiredTest`, `DocumentedCommandsExistTest`, `ImpactAccuracyTest`, `GraphHashTest`,
`PublishedArtifactSecretScanTest`, `ExecutionAndEgressBoundaryTest` and
`ArtifactChannelBudgetTest` now assert the properties that failed *silently*: that a control has a
caller, that a documented command exists, that a metric is earned, that a hash means what its name
says, that a secret does not reach an artifact, that a boundary documented as closed is actually
closed, and that a budget is spent on artifacts that can actually yield a fact.

### On the headline number

`deterministic coverage` fell from **0.9983** to **0.3886** over the course of this work, while the fact
set grew from **613** to **1690**. Nothing about the transformers changed. The harness stopped counting
facts it had never looked for — it had never opened a jar, and never followed a BOM import — and
stopped crediting a JUnit rewriter with every removed API in the ecosystem. The migration did not get
harder; the number stopped lying about it, and the residual report now names the 1028 removed APIs that
have no deterministic path so an operator can see what a human still has to do.

A harness whose purpose is to refuse comfortable claims should be expected to make its own numbers
worse when it is working.
