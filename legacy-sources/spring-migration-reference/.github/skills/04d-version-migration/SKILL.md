---
name: 04d-version-migration
description: 'Migrates a Java project to a new language level and/or framework version — Spring Boot 3 to 4, Java 17 to 21, or any jump with a reference pack in references/. Records how the application builds and behaves before anything changes, upgrades the declared versions per the reference pack, then iterates build rounds on the target JDK — reading each build failure and changing the source it points at — until the build is green, re-probes the running application, and renders docs/agent_output/04-remediation/migration_<slug>.md plus a cumulative migration_<slug>.diff. All edits happen in a sandbox copy; the project directory is never modified unless applying is explicitly requested. Use when asked to upgrade or migrate a framework version, move to a newer Java version, modernise a legacy build, or produce a migration report.'
argument-hint: 'The migration being asked for, e.g. "Spring Boot 3 to 4" or "migrate to Java 21", optionally with the project path'
---

# Version Migration

The migration arm of `04_fix-generator`. Where `04b-fixer` and `04c-dependency-upgrader` implement
an approved fix for a diagnosed defect, this skill handles a different kind of change entirely: a
whole project moving to a newer language level and/or framework generation, where nothing is broken
to begin with and the goal is to arrive on the other side with identical behaviour.

That difference drives everything about how this skill works:

- **A migration is not one patch, it is a sequence of rounds.** The first build after a version bump
  is *supposed* to fail. Each failure names the next set of source changes. The skill records every
  round so the report can show the migration as the sequence it actually was.
- **The compiler is the authority; the reference pack is the map.** Rules come from
  `references/<pack>.md`, but a rule is only applied because a build round failed in the way that
  rule describes — never because the pack mentions it.
- **Behaviour is the acceptance criterion, not compilation.** The application is exercised on the
  old runtime before anything changes and on the new runtime once it is green, and the two are
  compared request by request. A green build with no behavioural evidence is an unfinished
  migration.
- **The project directory is never edited.** Everything happens in a sandbox copy under
  `.github/.pipeline-context/version-migration/<slug>/workspace/`. Applying the result to the real
  project is a separate, explicit, refuse-if-not-green step.

Nothing in this skill is specific to any framework. The scripts know about JDKs, build tools,
compiler errors and HTTP responses; every fact about a particular version jump lives in a reference
pack, and adding a new migration is a matter of writing one.

## When to use

- "Upgrade this app from Spring Boot 3 to 4" / "migrate to Java 21" / "move us off the old framework
  version"
- "What would it take to upgrade X, and what would have to change in the code?"
- "Produce a migration report for the version upgrade"
- Any request to change a *platform or language version* rather than to fix a defect. A defect with
  a root cause report and an approved fix plan belongs to `04b-fixer`; a single vulnerable
  dependency to bump belongs to `04c-dependency-upgrader`; a whole-generation jump belongs here.

## Inputs

| # | Input | Why it is needed |
|---|---|---|
| 1 | The project directory — build descriptor, source, tests, container and CI files | What is being migrated. Read-only |
| 2 | The requested target version(s) | The jump. Ask if the user only said "upgrade" without a target |
| 3 | A reference pack in [`references/`](./references/) matching the jump | The only source of framework-specific rules. No matching pack means the migration does not start — see below |
| 4 | The application's own routes and credentials, read from its source | Builds the probe list that proves behaviour is preserved |
| 5 | Two JDKs: the version the project builds on today and the target | Round 0 runs on the first, every later round on the second |

**No matching reference pack is a stop condition, not a licence to improvise.** If
`detect-baseline.js` matches nothing, say so plainly and offer to write a pack for the jump first.
Migrating a framework generation from memory produces changes nobody can review against anything.

## Output

Per migration: `docs/agent_output/04-remediation/migration_<slug>.md` and a sibling
`migration_<slug>.diff` (the cumulative patch, directly `git apply`-able).

**That folder is shared with the fix reports** written by `04a`/`04b`/`04c`. Nothing collides: a
migration only ever writes files with the `migration_` prefix, which their index scan ignores, and
the migration table in `04-remediation/README.md` is a self-delimited block written above their
marker, so re-running either renderer preserves the other's index. A migration never creates, reads
as a gate, or edits a `fix_plan_*.md` or `fix_*.md`.

The report is written for someone who was not in the room — colour-coded, diagrammed, and honest:

- Badges, a plain-language summary, and an **At a glance** table — result, versions before/after,
  rounds, every file changed, behaviour verdict
- **1. What moved** — a before/after stack diagram and the full version matrix, each row with why
- **2. How the migration went** — a round flow diagram and an error-kind breakdown
- **3. Round by round** — per round: the command, the JDK, the outcome, errors grouped by kind with
  what that kind means, **every individual error line** with file and line number, the agent's
  diagnosis, and the build log
- **4. Source changes the upgrade forced** — grouped by area, with before/after excerpts and the
  reference rule each came from
- **5. Every file that changed** — the complete file-by-file list taken from the patch itself, with
  line counts, what changed in each, and each file's own diff. A changed file with no explanation in
  the migration record is called out rather than passed over
- **6. Does it still behave the same?** — the before/after probe comparison, request by request
- **7. Changes that were *not* caused by the upgrade** — kept separate, deliberately
- **8. What still needs a human** — follow-ups and residual risk
- **9. The patch** — diffstat, the patch file, and how to apply it

Intermediate files live in `.github/.pipeline-context/version-migration/<slug>/` (gitignored):
`baseline.json`, `workspace.json`, `workspace/`, `rounds/round-NN.{json,log}`,
`runtime/{baseline,final}.json`, and the agent-written `migration.json`.

## Procedure

### Step 1 — Detect the baseline

```powershell
cd .github/skills/04d-version-migration
node scripts/detect-baseline.js --project <path-to-project> --to-java 21
```

Zero dependencies — nothing to `npm install`. Prints and records: the declared language level, the
build tool and its version, the platform parent, every declared dependency and plugin, the container
and CI files, and every JDK installed on this machine. It also matches the project against each
reference pack's `detect:` coordinates and names the pack to follow.

Confirm the suggested pack is the right one, then **read it end to end before touching anything**.
Note the slug it printed — every later command takes it.

### Step 2 — Understand the application as it is today

Before any version changes, read the application: its entry point, controllers and routes, security
configuration and credentials, persistence layer, configuration files, and its tests. You are
answering two questions: *what does this application do*, and *how will I know it still does it*.

Write the answer to the second one as a probe file — the requests that will be replayed on both
runtimes:

```json
{
  "base_url": "http://localhost:8080",
  "auth": { "type": "basic", "username": "demo", "password": "demo123" },
  "readiness": { "path": "/actuator/health", "timeout_seconds": 120 },
  "requests": [
    { "name": "list all",             "method": "GET",  "path": "/api/v1/employees" },
    { "name": "get one",              "method": "GET",  "path": "/api/v1/employees/1" },
    { "name": "not found is 404",     "method": "GET",  "path": "/api/v1/employees/99999" },
    { "name": "unauthenticated is 401","method": "GET", "path": "/api/v1/employees", "no_auth": true },
    { "name": "health",               "method": "GET",  "path": "/actuator/health" }
  ]
}
```

Save it in the session directory the scripts printed. Cover the real endpoints, not just health:
the happy path, an error path, and an authentication boundary, at minimum. Probes that only prove
the process starts prove almost nothing.

### Step 3 — Create the sandbox

```powershell
node scripts/prepare-workspace.js --slug <slug>
```

Copies the project (excluding build output and any VCS metadata) into
`.github/.pipeline-context/version-migration/<slug>/workspace/` and commits it to a throwaway git
repository. **Every edit from here on is made to files under that workspace path, never to the
project.** The commit is what makes the cumulative diff exact at the end.

### Step 4 — Round 0: the reference build and the reference behaviour

```powershell
node scripts/run-migration-build.js --slug <slug> --baseline --jdk 17
node scripts/probe-runtime.js --slug <slug> --phase baseline --jdk 17 --probes <session>/probes.json
```

Both must run **before any version is changed**, on the JDK the project uses today. This is the only
opportunity to record what "working" means for this application; there is no way back to it later.

**If round 0 is not green, find out why before going further** — the answer decides whether to
continue:

- **It does not compile.** Stop and report it. A project that does not build before a migration
  cannot be migrated; every later failure would be indistinguishable from the migration's own.
- **It compiles but some tests already fail** — a missing Docker daemon for Testcontainers, an
  assertion that was already wrong, an environment-dependent test. That is not a reason to stop and
  it is not something to fix here. Record it, keep using the same build goal for every later round,
  and let the report compare the counts: the question a migration has to answer is not "is the suite
  green" but "did *this* change break anything that was not already broken".

Either way the failures belong in the report. Do not fix a pre-existing failure to make the baseline
look better — that change would not be caused by the migration, and it destroys the comparison.

### Step 5 — Change the declared versions

Apply the reference pack's build-file section inside the sandbox: the parent or BOM version, the
language level (property *and* any explicit compiler configuration), renamed or split artifacts,
and third-party libraries the pack flags as needing a manual bump. Also update the container image
and CI language versions the baseline listed.

Do not pre-emptively rewrite source code in this step, even where the pack says it will be needed.
Let the build tell you. The point is a report where every source change traces to a real failure.

### Step 6 — The round loop

```powershell
node scripts/run-migration-build.js --slug <slug> --jdk 21 --intent test-compile --label "swapped starters and BOM"
```

Each round records the outcome, every error with its file and line, a category per error, what the
build declares at that moment, and the workspace diffstat. Then:

1. **Read the errors the script grouped.** The category tells you the *shape* of the breakage — a
   package that no longer exists, a signature that changed, an artifact that will not resolve.
2. **Look the shape up in the reference pack's symptom table.** Apply the rule it names.
3. **For an error with no matching rule**, work it out from the actual dependency — resolve the
   coordinate, inspect the jar, read the type that replaced it. Never guess an import path.
4. **Edit the sandbox, change nothing else**, and run the next round with a `--label` saying what
   you changed.

Useful `--intent` values, cheapest first: `compile`, `test-compile`, `package`, `verify`. Compile
errors are worth iterating on with `test-compile`; move to `package` once they are clean so tests
actually run. **Run at least one round with the same goal round 0 used** — that is the only pair the
report can compare test counts across, and a migration can break tests without producing a single
compiler error.

Expect the test layer to break a round after the main code — tests only compile once main does.
Keep going until a round comes back green. Every round is kept; a failed round is evidence, not
something to hide.

### Step 7 — Prove the behaviour survived

```powershell
node scripts/probe-runtime.js --slug <slug> --phase final --jdk 21 --probes <session>/probes.json
```

Same probe file, new runtime. The renderer compares the two runs request by request and separates
the two kinds of difference: a **changed status** is a contract break, while a **same status with a
different body** is usually a framework envelope, an embedded timestamp or an unordered collection.
Read the recorded body excerpts in `runtime/{baseline,final}.json` before calling either one a
regression, and say which it is in the behaviour notes. A framework-owned payload that changed shape
(a health document, a default error body) is a real finding for clients even though no application
code changed — report it rather than rounding it down to "unchanged".

### Step 8 — Write the judgement file

Write `.github/.pipeline-context/version-migration/<slug>/migration.json` per
[templates/migration.schema.json](./templates/migration.schema.json) (worked example in
[templates/migration.example.json](./templates/migration.example.json)).

It carries only what a script cannot know: what each round's failures actually meant, why each
change was needed and which reference rule it came from, before/after excerpts for the source
changes, the behaviour verdict, anything changed that the migration did *not* require, follow-ups
and residual risk. Do not restate error counts, versions or probe results — those come from the
recorded facts. **Every round that ran needs a `round_notes` entry; the renderer refuses without
one.**

### Step 9 — Render the report

```powershell
node scripts/render-migration-report.js --slug <slug>
```

Writes the report, exports the cumulative patch, and rewrites the index. Fix any validation error it
prints and re-render.

### Step 10 — Applying to the project (only if asked)

```powershell
node scripts/apply-migration.js --slug <slug>                # dry run: lists what would change
node scripts/apply-migration.js --slug <slug> --to-project   # writes the project
```

Refused unless the last recorded round is green. Do not run the `--to-project` form unless the user
has asked for the migration to be applied — the report and the patch are the deliverable by default.

### Step 11 — Report back

Lead with the result and the rounds it took, then the versions moved, the source changes forced,
and the behaviour verdict. Link to the report; do not paste it, the diff, or build logs into chat.

## Constraints

- DO NOT edit, create or delete any file in the project directory. Every migration edit goes into
  the sandbox workspace. `apply-migration.js --to-project` is the single exception and runs only on
  an explicit request, only when the final round is green.
- DO NOT start a migration with no matching reference pack, and DO NOT invent framework rules from
  memory. Report the gap and offer to write the pack first.
- DO NOT skip round 0, or run it after changing a version. A migration with no recorded
  pre-migration build and probe cannot demonstrate anything and must be reported as such.
- DO NOT change source code before a build round has failed on it, except where the reference pack's
  baseline section requires it up front (language level, coordinates). Every other change traces to
  a real error.
- DO NOT guess a package, class or coordinate. Resolve it against the dependency tree or the jar —
  the reference pack itself says to prefer what resolves over what it documents.
- DO NOT bundle unrelated work into the migration: no bug fixes, no refactors, no reformatting, no
  new features, no import reordering. If something unrelated must change, record it in
  `out_of_scope_changes` and present it separately.
- DO NOT mutate the machine's `JAVA_HOME`, `PATH` or any global toolchain setting. `--jdk` pins the
  JDK for that child process only.
- DO NOT report a migration as successful when the last round was not green, and DO NOT describe
  behaviour as preserved without a completed before/after probe comparison.
- DO NOT hand-edit a rendered report or the exported diff — change the inputs and re-render.
- DO NOT delete or rewrite a round record to make the history look cleaner. The failed rounds are
  the most useful part of the report.
- DO NOT print full reports, diffs, build logs or probe bodies into chat — link to the files.
- No `npm install` is needed — this skill has zero dependencies.

## Known caveats

- **Two JDKs must be installed.** `detect-baseline.js` lists what it found. If the target JDK is
  missing, install it or point `MIGRATION_JDK_<major>` at an existing install; the round scripts
  refuse rather than silently building on the wrong one.
- **Maven or Gradle must be resolvable.** A project wrapper (`mvnw`/`gradlew`) is preferred and used
  automatically; otherwise a system install is found on `PATH` or in the conventional locations, or
  `MIGRATION_MVN` can point at one directly.
- **The runtime probe needs a free port.** Default 8080; pass `--port` if something else is using
  it. The probe stops the application again even when a request fails.
- **A first build on a new BOM downloads a lot.** The first round after a version bump is slow and
  its duration is not comparable with later rounds. `--timeout` (seconds) raises the 900s default.
- **Body-hash comparison is strict.** A response embedding a timestamp or a generated id will differ
  between runs for reasons unrelated to the migration. Judge those cases and say so in the
  behaviour notes rather than pretending the difference is not there.

## Notes

- Self-contained folder — zero dependencies, nothing to `npm install`.
- `scripts/lib/migration.js` holds path resolution, JDK and build-tool discovery, project inventory
  and build-output classification; `scripts/lib/references.js` holds only reference-pack discovery.
  Neither imports from another skill — this pipeline duplicates helpers per skill rather than
  coupling skills together, the same choice already made for `04b-fixer` and `04c-dependency-upgrader`.
- Error categories in `lib/migration.js` classify by message shape only. They never name a library
  and never propose a fix; that mapping is the reference pack's job, so a new framework needs no
  code change.
- Adding a migration = adding `references/<id>.md` with the front matter documented in
  [`references/README.md`](./references/README.md). No script changes.
- Everything written by this skill lands in `.github/.pipeline-context/version-migration/*` or
  `docs/agent_output/04-remediation/*`. Nothing here writes to `docs/agent_output/00-issues/`,
  `02-root-cause/`, `03-blast-radius/` or `04-remediation/`.
