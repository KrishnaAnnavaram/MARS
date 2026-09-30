---
name: 04d-version-migration
description: 'Migrates a Java project to a new language level and/or framework version — Spring Boot 3 to 4, Java 17 to 21, or any jump with a reference pack in references/. Understands the application first, records how it builds and behaves before anything changes, writes a migration plan predicting the impact, applies deterministic OpenRewrite transformations the reference pack offers (previewed and inspected before they touch the sandbox), then iterates evidence-driven build rounds on the target JDK until green, re-probes the running application, and renders docs/agent_output/04-remediation/migration_<slug>.md plus a cumulative migration_<slug>.diff. All edits happen in a sandbox copy; the project directory is never modified unless applying is explicitly requested. Use when asked to upgrade or migrate a framework version, move to a newer Java version, modernise a legacy build, or produce a migration report.'
argument-hint: 'The migration being asked for, e.g. "Spring Boot 3 to 4.1.1" or "migrate to Java 21", optionally with the project path'
---

# Version Migration

The migration arm of `04_fix-generator`. Where `04b-fixer` and `04c-dependency-upgrader` implement
an approved fix for a diagnosed defect, this skill handles a different kind of change entirely: a
whole project moving to a newer language level and/or framework generation, where nothing is broken
to begin with and the goal is to arrive on the other side with identical behaviour.

[`ARCHITECTURE.md`](./ARCHITECTURE.md) explains how this skill is put together and why. This file is
the procedure.

That difference drives everything about how this skill works:

- **Understand before mutating.** The application is read, its behaviour recorded, and the impact of
  the jump predicted in a migration plan *before* any version or source changes. The plan is a
  prediction, and the report shows it next to what actually happened.
- **Deterministic first, generative second.** Where the reference pack offers an OpenRewrite recipe
  for a structural change, the recipe makes it — previewed, inspected against the plan, applied to
  the sandbox, and built immediately. The agent's own edits are reserved for what remains, and each
  one traces to evidence.
- **The compiler, the tests and the running application are the authority.** A recipe that ran is
  not evidence that the migration worked, and neither is the agent's confidence. A migration is
  complete only when the build is green on the target, the test suite has no new failures, and the
  before/after probes have been compared.
- **A migration is a sequence of rounds.** Every build is recorded — failed rounds included — so the
  report shows the migration as the sequence it actually was.
- **The project directory is never edited.** Everything happens in a sandbox copy under
  `.github/.pipeline-context/version-migration/<slug>/workspace/`. Applying the result to the real
  project is a separate, explicit, refuse-if-not-eligible step.

Nothing in the scripts is specific to any framework. They know about JDKs, build tools, compiler
errors, OpenRewrite invocations and HTTP responses; every fact about a particular version jump —
including which source patterns matter and which recipes exist — lives in a reference pack.

## When to use

- "Upgrade this app from Spring Boot 3 to 4" / "migrate to Java 21" / "move us off the old framework
  version"
- "What would it take to upgrade X, and what would have to change in the code?"
- "Produce a migration report for the version upgrade"
- Any request to change a *platform or language version* rather than to fix a defect. A defect with
  a root cause report and an approved fix plan belongs to `04b-fixer`; a single vulnerable
  dependency to bump belongs to `04c-dependency-upgrader`; a whole-generation jump belongs here.

## Who does what

This skill runs inside the existing Claude harness. **There is no model client, LLM SDK or AI
service inside 04D** — the reasoning is the harness following this file.

| The agent (Claude, in the harness) | The scripts |
|---|---|
| Reads the application | Discover the project deterministically (`detect-baseline.js`) |
| Interprets the reference pack | Isolate the sandbox and its checkpoints (`prepare-workspace.js`) |
| Writes the migration plan | Pick the JDK and build tool for each child process |
| Selects among the pack's deterministic transformations | Run OpenRewrite, record it, enforce preview-before-apply (`run-migration-build.js`) |
| Inspects every OpenRewrite preview | Run builds, extract and group errors |
| Diagnoses residual compiler, test and runtime failures | Replay probes, keep raw evidence (`probe-runtime.js`) |
| Makes narrowly scoped residual edits in the sandbox | Validate the plan and the judgement file against their schemas |
| Classifies before/after differences | Export the diff, render the report (`render-migration-report.js`) |
| Writes the judgement parts of `migration.json` | Decide whether applying is allowed (`apply-migration.js`) |

**Build, test and runtime evidence outranks judgement.** If the agent believes the migration is
correct and the evidence disagrees, the migration is not complete.

## Inputs

| # | Input | Why it is needed |
|---|---|---|
| 1 | The project directory — build descriptor, source, tests, container and CI files | What is being migrated. Read-only |
| 2 | The **exact** requested target version(s), e.g. Spring Boot `4.1.1`, Java `21` | The jump. Ask if the user only said "upgrade". Never substitute "latest" or a recipe's own target |
| 3 | A reference pack in [`references/`](./references/) matching the jump | The only source of framework-specific rules and recipes. No matching pack means the migration does not start |
| 4 | The application's own routes and credentials, read from its source | Builds the probe list that proves behaviour is preserved |
| 5 | Two JDKs: the version the project builds on today and the target | Round 0 runs on the first, every later round on the second |

**No matching reference pack is a stop condition, not a licence to improvise.** If
`detect-baseline.js` matches nothing, say so plainly and offer to write a pack for the jump first.

## Output

Per migration: `docs/agent_output/04-remediation/migration_<slug>.md` and a sibling
`migration_<slug>.diff` (the cumulative patch, directly `git apply`-able).

**That folder is shared with the fix reports** written by `04a`/`04b`/`04c`. Nothing collides: a
migration only ever writes files with the `migration_` prefix, which their index scan ignores, and
the migration table in `04-remediation/README.md` is a self-delimited block written above their
marker, so re-running either renderer preserves the other's index. A migration never creates, reads
as a gate, or edits a `fix_plan_*.md` or `fix_*.md`.

The report is written for someone who was not in the room. It keeps five things apart that must
never blur — what was **predicted**, **observed**, **transformed**, **verified** and what is still
**unresolved**:

- Badges, a summary, and an **At a glance** table — result, versions, rounds, files, tests, behaviour,
  plan, transformations, and whether the final build declares the requested target
- **0. What was understood before anything changed** — target and path, constraints, predicted
  impact next to what actually failed, changed and was transformed, and which probe protects what
- **1. What moved** · **2. How the migration went** (round diagram, ledger, what failed and why,
  **2.4 deterministic transformations** — every preview and apply, including unavailable, failed and
  reverted ones — and **2.5 how each change was made**) · **3. Round by round** (with parser-grouped
  errors beside the agent's root-cause reading) · **4. Source changes** · **5. Every file that
  changed** · **6. Does it still behave the same?** (raw comparison, extra observations and the
  agent's classification in separate columns; the security boundary) · **7. Not caused by the
  upgrade** · **8. What still needs a human** · **9. The patch** · **10. Evidence and provenance**

Session files live in `.github/.pipeline-context/version-migration/<slug>/` (gitignored):

| File | Written by | What it is |
|---|---|---|
| `baseline.json` | `detect-baseline.js` | What the project declares and contains today; requested target; observations |
| `probes.json` | the agent | The requests replayed before and after |
| `workspace.json`, `workspace/` | `prepare-workspace.js` | The sandbox and its own throwaway git repository |
| `rounds/round-NN.{json,log}` | `run-migration-build.js` | One per build — a round always means a build ran |
| `runtime/{baseline,final}.json` | `probe-runtime.js` | Raw responses before and after |
| `migration-plan.json` | the agent | Pre-mutation plan ([schema](./templates/migration-plan.schema.json)) |
| `transformations/rewrite-NN.{json,patch,log}` | `run-migration-build.js` | One per OpenRewrite preview or apply |
| `migration.json` | the agent | The judgement ([schema](./templates/migration.schema.json)) |
| `state.json` | every script | Transition history and any BLOCKED flag; progress itself is inferred from the files above |

## The mutation rule

1. **Nothing changes before round 0 and the baseline probe exist.** `run-migration-build.js` refuses
   to record round 0 on a sandbox that already differs from the project, refuses every later round
   until round 0 exists, and refuses any transformation until the baseline probe and a valid plan
   exist.
2. **A deterministic transformation may run before any build asks for it only when all of these
   hold:** the reference pack declares it, the migration plan selects it, OpenRewrite has previewed
   it (`--rewrite dry-run`), the agent has inspected that preview against the plan's impact list, the
   apply names that preview (`--rewrite-preview`), and it runs in the sandbox. The scripts enforce
   everything but the inspection — that is the agent's job, and it is not optional.
3. **A residual (hand) edit needs evidence:** a compiler or build error, a test regression against
   round 0, a startup failure, a behavioural difference, or a verified pack rule that no recipe
   handles safely. Record that evidence in `code_changes[].evidence`.
4. **Never** make speculative cleanup, refactoring, formatting or unrelated modernisation — not by
   hand, and not by accepting a recipe that proposes it.

## Procedure

### Step 1 — Detect the baseline

```powershell
cd .github/skills/04d-version-migration
node scripts/detect-baseline.js --project <path-to-project> --to-java 21 --to-version <exact target, e.g. 4.1.1>
```

Zero dependencies — nothing to `npm install`. Records the declared language level, build tool,
platform coordinates, every dependency and plugin, container and CI files, every local JDK, and the
matching reference pack. It also records:

- **`target`** — exactly what was requested. Without `--to-version` the target is `unresolved`; it is
  never inferred.
- **`migration_path`** — the source platform version, whether it sits on the pack's preparation line
  (for Boot 3 → 4: 3.5.x), warnings, and **unresolved** compatibility questions (ecosystem BOMs such
  as Spring Cloud). These are planning inputs, not changes.
- **`observations`** — a regex scan for the source surfaces the pack names (JSON, actuator, security,
  persistence, test slices, mocking…), entry points, config files, pinned versions, container and CI
  Java references. It is labelled as a scan: a list of places to read, not conclusions.
- **`capabilities`** — the deterministic transformations the pack offers, with their recipes,
  pinned versions and licence. Availability is not checked here; a dry-run decides it.

Confirm the pack, then **read it end to end**, including its §14–§15. Note the slug.

### Step 2 — Understand the application, and write the probes

Read the files the observations point at, and beyond them: the entry point, controllers, security
configuration and credentials, persistence, configuration, tests. You are answering *what does this
application do*, *what will this jump touch*, and *how will I know it still does it*.

Write the probes to `<session>/probes.json`. Characterisation is **impact-driven**:

| Impacted area | Probe |
|---|---|
| Controllers / API | the happy path and an error path per important route |
| Security | authenticated, unauthenticated **and** bad-credential requests (`category: "security-boundary"`) |
| Serialization | a representative payload (`category: "serialization"`) |
| Actuator | the actuator endpoints the app exposes (`category: "actuator"`) |
| Persistence | rely on the project's own integration tests and the API; never invent a database oracle you cannot observe |

```json
{
  "base_url": "http://localhost:8080",
  "auth": { "type": "basic", "username": "demo", "password": "demo123" },
  "readiness": { "path": "/actuator/health", "timeout_seconds": 120 },
  "requests": [
    { "name": "list all", "method": "GET", "path": "/api/v1/employees", "category": "business-api", "expect_status": 200 },
    { "name": "not found is 404", "method": "GET", "path": "/api/v1/employees/99999", "category": "error-contract", "expect_status": 404 },
    { "name": "unauthenticated is 401", "method": "GET", "path": "/api/v1/employees", "no_auth": true, "category": "security-boundary", "expect_status": 401 }
  ]
}
```

Optional per-request fields add observations *beside* the raw ones, never instead of them:
`expect_status`, `category`, `ignore_json_paths` (explicit dotted paths only — nothing, not even a
timestamp, is ignored by default), `unordered_arrays`, `capture_headers`. An area no probe or test
observes is reported as unobserved, never as preserved.

### Step 3 — Create the sandbox

```powershell
node scripts/prepare-workspace.js --slug <slug>
```

Copies the project into the sandbox and commits it to a throwaway git repository; the baseline
commit is also the first checkpoint. **Every edit from here on is made under that workspace path.**
Re-running it on an existing sandbox prints where the session stands instead of recreating it.

### Step 4 — Round 0: the immutable reference build and behaviour

```powershell
node scripts/run-migration-build.js --slug <slug> --baseline --jdk 17
node scripts/probe-runtime.js --slug <slug> --phase baseline --jdk 17 --probes <session>/probes.json
```

Both run **before anything changes**, on the JDK the project uses today — the scripts refuse
otherwise. If round 0 does not compile, stop and report it. If it compiles but tests already fail
(a missing Docker daemon, a wrong assertion), record it and keep the same build goal for a later
round, so the report can show "no new failures" rather than "the suite is green". Never fix a
pre-existing failure here.

### Step 5 — Write the migration plan

Write `<session>/migration-plan.json` per
[templates/migration-plan.schema.json](./templates/migration-plan.schema.json) (worked example:
[templates/migration-plan.example.json](./templates/migration-plan.example.json)). Reference
`baseline.json` rather than copying it. It carries:

- **source and target** — the target exactly as requested; **path** and the preparation-line decision
- **constraints** — each `satisfied`, `unresolved`, `violated` or `not-applicable`, with evidence;
  mark `blocking: true` on those that must hold before the result may be applied
- **impact** — one entry per file and reason: area, evidence, reference rule, risk, how it will be
  verified, `handled_by`, and `expected_symptoms` (text a build error would contain)
- **characterization** — which probe protects which impact; `probe: null` with a reason for what
  cannot be observed
- **deterministic_candidates** — the pack transformations you select (and any narrowing of their
  recipes); **residual_candidates** — what you expect to fix by hand, and why no recipe does it
- **out_of_scope** and **stop_conditions**

Validate it (no build runs):

```powershell
node scripts/run-migration-build.js --slug <slug> --check-plan
```

### Step 6 — Deterministic transformations (when the plan selects one)

```powershell
node scripts/run-migration-build.js --slug <slug> --jdk 21 --rewrite dry-run --rewrite-id <candidate>
```

The dry-run changes no source and runs no build. It writes `transformations/rewrite-NN.{json,patch,log}`
with the proposed files and — from the tool's own output — which recipe proposed each change.

**Inspect the patch against the plan.** Accept what maps to an impact entry. Reject anything else —
modernisation, unrelated dependency upgrades, a changed serialization format, cleanup. To reject:

- narrow the recipes (`--rewrite-recipe`, only recipes the plan names) or pick another candidate,
  and preview again; or
- exclude a whole out-of-scope file at apply time (`--rewrite-exclude <file>`); or
- if an unwanted hunk shares a file with wanted ones, apply, then revert that hunk by hand before the
  next round and record it (`code_changes[].origin: "harness-residual"` with the evidence).

Then apply exactly what you inspected; the build runs immediately:

```powershell
node scripts/run-migration-build.js --slug <slug> --jdk 21 --rewrite apply --rewrite-id <candidate> --rewrite-preview rewrite-NN [--rewrite-exclude <file>] --intent test-compile
```

The apply is checkpointed first and **reverted automatically** if it changes any file the preview
did not propose, or leaves OpenRewrite plugin declarations in a build file. Its record carries the
provider, recipes, pinned artifact and plugin versions, licence, sanitised command, changed files,
patch, log, and the build round that verified it.

- **Target reconciliation.** If a recipe leaves an intermediate version (the upstream Boot 4
  composite targets `4.0.x`), the record says `reconcile-required`: move the declared version to the
  requested one, rebuild, and let the build prove it. The report and `apply-migration.js` both check
  the final declared version against the request.
- **Unavailable.** No network, repository credentials, a licence gate, an unsupported build tool:
  the record says `unavailable` with the reason, and it is never reported as having run. An
  `optional` transformation falls back to Step 7; a `required` one blocks the session.
- **Failed.** A tool that ran and failed is a result — recorded, reported, never hidden.

### Step 7 — Declared versions not covered by a transformation

Without a transformation (or for what it did not cover), apply the reference pack's build-file
section in the sandbox: parent or BOM, language level (property *and* compiler configuration),
renamed artifacts, libraries the pack flags, container and CI Java versions. Do not pre-emptively
rewrite source in this step — let the build name it.

### Step 8 — The round loop: residual repair

```powershell
node scripts/run-migration-build.js --slug <slug> --jdk 21 --intent test-compile --label "what changed"
```

Each round records the outcome, every error with file and line and category, **error groups**
(parser facts: same category and subject — a package, a symbol, a test class), and, when a plan
exists, a heuristic correlation of those groups with the plan's `expected_symptoms`. For a failing
round, reason in this order:

1. Read the grouped failure — the parser's fact, e.g. "12 × cannot find symbol: class ObjectMapper".
2. Correlate it with the plan's impact entries.
3. Correlate it with the reference pack's symptom table.
4. Look for a deterministic recipe or pack rule that covers it; prefer it (back to Step 6).
5. When uncertain, verify the actual target class or coordinate against the resolved dependency
   tree or the jar — never guess an import path.
6. Only then make the narrowest residual edit in the sandbox.
7. Rebuild immediately.
8. Record the rationale: `round_notes[].root_causes` (which groups, what they are — your judgement,
   kept separate from the parser's fact) and `code_changes[].evidence`.

`--intent` values, cheapest first: `compile`, `test-compile`, `package`, `verify`. **Run at least one
round with the same goal round 0 used** — the only pair whose test counts are comparable. Finish on a
goal that packages the artifact (`package` or `package-skip-tests`).

### Step 9 — Prove the behaviour survived

```powershell
node scripts/probe-runtime.js --slug <slug> --phase final --jdk 21 --probes <session>/probes.json
```

Same probe file, new runtime. The comparison keeps three layers apart: the **raw** result (status and
a hash of the body, never overridden), **extra observations** (an order-insensitive hash, explicitly
configured ignored paths), and **your classification** in `migration.json`. A changed status is a
contract break. A same-status, different-body row is classified only *after* reading both excerpts:
`expected-framework-change`, `non-deterministic`, `regression` or `unexplained`. A framework-owned
payload that changed shape is a real finding for clients — report it, do not round it down.

### Step 10 — Write the judgement file

Write `<session>/migration.json` per [templates/migration.schema.json](./templates/migration.schema.json)
(worked example: [templates/migration.example.json](./templates/migration.example.json)). It carries
only what a script cannot know. **Every round needs a `round_notes` entry; the renderer refuses
without one**, and refuses a document that does not match the schema.

- `diagnosis` is the **why** of a round — which API moved, why the failure landed where it did.
- `changes` is what changed **before** that round; empty for a re-run with a raised goal.
- `code_changes[].origin` says how each change was made (`openrewrite`, `reference-rule`,
  `harness-residual`, `build-file`) and `evidence` what demanded it.
- `transformations[]` records your decision on every preview and apply — `accepted`, `rejected`,
  `reverted`, `superseded` or `not-run` — and why.
- `behaviour.differences[]` classifies every raw difference; `security_boundary`; `unobserved_areas`.
- `impact_review[]` says what became of each predicted impact; `unresolved_constraints[]` where the
  plan's constraints ended up; `blocking_conditions[]` anything that must stop an apply.
- `out_of_scope_changes`, `manual_follow_ups`, `residual_risk` as before.

Do not restate error counts, versions or probe results — the report reads them from the records.

### Step 11 — Render the report

```powershell
node scripts/render-migration-report.js --slug <slug>
```

Writes the report and the cumulative patch, and rewrites the index. Fix any validation error it
prints and re-render.

### Step 12 — Applying to the project (only if asked)

```powershell
node scripts/apply-migration.js --slug <slug>                # dry run: what would change, and whether it may
node scripts/apply-migration.js --slug <slug> --to-project   # writes the project
```

Refused unless: round 0 and a target round exist; the last round is green (on a packaging goal, for
a v2 session); the application was probed after the migration if it was probed before; nothing
blocks it (BLOCKED state, open `blocking_conditions`, unresolved blocking plan constraints); the final
declared version is the requested one; and the project has not drifted since the sandbox was copied.
A pre-v2 session is held only to what its own files can answer. **Do not run `--to-project` unless
the user asked for the migration to be applied.**

### Step 13 — Report back

Lead with the result and the rounds it took, then the versions moved, what the deterministic
transformations did (and what you rejected from them), the residual changes, and the behaviour
verdict. Link to the report; do not paste it, the diff, or logs into chat.

## Stop or block

Stop and report instead of continuing when:

- no reference pack matches, or the requested target is not exact;
- round 0 does not compile;
- a `required` transformation is unavailable (the session is BLOCKED — resolve access, then re-run);
- a plan stop condition is met, or a blocking constraint turns out `violated`;
- a business or security-boundary probe changes status and the cause is not within the migration's
  scope to fix.

## Constraints

- DO NOT edit, create or delete any file in the project directory. `apply-migration.js --to-project`
  is the single exception and runs only on an explicit request.
- DO NOT start a migration with no matching reference pack, and DO NOT invent framework rules or
  recipes from memory. Report the gap and offer to write the pack first.
- DO NOT skip round 0 or the baseline probe, or run them after changing anything.
- DO NOT change anything before the plan exists, except through the mutation rule above.
- DO NOT apply an OpenRewrite recipe you have not previewed and inspected, run one the plan does not
  name, or accept a preview's out-of-scope changes. DO NOT run OpenRewrite outside the sandbox, and
  DO NOT add rewrite plugins to the project's build to run it.
- DO NOT describe an unavailable, failed or reverted transformation as having run.
- DO NOT substitute "latest" or a recipe's intermediate target for the requested version.
- DO NOT guess a package, class or coordinate. Resolve it against the dependency tree or the jar.
- DO NOT bundle unrelated work into the migration: no bug fixes, refactors, reformatting, new
  features or import reordering — by hand or by recipe. Record it in `out_of_scope_changes`.
- DO NOT mutate the machine's `JAVA_HOME`, `PATH` or any global toolchain setting.
- DO NOT normalise away a response difference to make a migration look clean; classify it.
- DO NOT report a migration as successful when the last round was not green, and DO NOT describe
  behaviour as preserved without a completed before/after comparison.
- DO NOT hand-edit a rendered report or the exported diff; DO NOT delete or rewrite a round or
  transformation record to make the history look cleaner.
- DO NOT write credentials into any file. Recorded commands and logs are redacted; do not put tokens
  into probe files, plans or judgement files either.
- DO NOT call any external AI service from this skill — reasoning is the harness's job.

## Known caveats

- **Two JDKs must be installed.** `detect-baseline.js` lists what it found. Point
  `MIGRATION_JDK_<major>` at an install the default search does not find.
- **Maven or Gradle must be resolvable.** A project wrapper is preferred; otherwise `PATH`, the
  conventional install locations, or `MIGRATION_MVN`.
- **OpenRewrite resolves its plugin and recipe artifacts** through the build tool's own repository
  configuration and credentials, which 04D passes through untouched and never reads. Offline or
  without access, a transformation records `unavailable` and the compiler-driven path remains.
  Recipe artifacts carry their own licences (the Spring recipes are under the Moderne Source
  Available License) — 04D records the licence; judging it is a human's job.
- **OpenRewrite is only wired for Maven and Gradle.** The Gradle path (an init script in the session
  directory) is covered by automated tests of the command it builds; the Maven path has also been
  exercised end to end.
- **The runtime probe needs a free port.** Default 8080; pass `--port`.
- **A first build on a new BOM downloads a lot.** `--timeout` (seconds) raises the default.
- **Error lines vs distinct problems.** javac and Maven print the same problem twice; the report
  keeps the recorded count and prints the deduplicated one beside it.
- **Which tests failed** is read from the build log's results block; a truncated log may name fewer
  tests than the summary counts.
- **Body-hash comparison is strict.** Timestamps and generated ids differ between runs. Classify
  such rows; do not tune the probe until they disappear.
- **Files the running application writes into the sandbox** (a file database not covered by the
  project's `.gitignore`) are listed in the runtime record's `sandbox_side_effects`; keep them out of
  the patch.

## Notes

- Self-contained folder — zero npm dependencies. `npm test` (or `node --test "tests/*.test.js"`) runs
  the automated tests in disposable temporary directories.
- `scripts/lib/migration.js`: paths, JDK and build-tool discovery, project inventory, build-output
  classification and grouping, redaction, sandbox checkpoints, schema validation, session state and
  probe comparison. `scripts/lib/references.js`: reference-pack parsing. `scripts/lib/openrewrite.js`:
  the OpenRewrite provider. None imports from another skill.
- Error categories classify by message shape only; they never name a library or propose a fix.
- Adding a migration = adding `references/<id>.md` (and, optionally, a recipe config under
  `references/openrewrite/`) per [`references/README.md`](./references/README.md). No script changes.
- Everything written by this skill lands in `.github/.pipeline-context/version-migration/*` or
  `docs/agent_output/04-remediation/migration_*` and that folder's index block.
