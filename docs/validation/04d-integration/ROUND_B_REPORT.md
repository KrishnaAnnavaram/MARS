# Round B — Standalone 04D Validation

Does the 04D migration capability itself work? Two cases, both run **outside** Agent 04 (a direct
request), in disposable data/report directories (`PIPELINE_CONTEXT_DATA_DIR`, `MIGRATION_REPORT_DIR`),
never touching the input projects.

| Case | Input | Request | Code | Result |
|---|---|---|---|---|
| **B1** | MARS `src/employee-service` — Spring Boot **2.7.12**, Java 17, `javax.*` | → 4.1.1 / Java 21 | unmodified 04D (`5b8008b`) | **BUG CONFIRMED** — the 3→4 pack was selected for a 2.x source; 04D proceeded to a sandbox and round 0 |
| **B1′** | same | same | fixed 04D (`validate/04d-integration`) | **BLOCKED — `UNSUPPORTED_MIGRATION_PATH`**, missing `spring-boot-2-to-3`; no sandbox |
| **B2** | `sample-java-project@6978c4a` — Spring Boot **3.5.0**, Java 17 | → 4.1.1 / Java 21 | unmodified 04D | **PASS** — 4 rounds, green packaging, no new test failures, 13 probes with 0 status changes |

Per-run summaries (`migration-runs/<RUN_ID>/MIGRATION_SUMMARY.md` + `migration-summary.json`) exist for all
three. The unmodified 04D had no summary capability; the B1/B2 summaries under `roundB-unmodified/`
were generated afterwards by the new `finalize-run.js` **from the sessions' own evidence files**
(baseline, rounds, transformations, runtime records, judgement) — nothing in them is hand-written.

---

## B1 — Spring Boot 2.7.12 source against the 3→4 pack (unmodified 04D)

**Facts about the source** (all verified):

| Fact | Value | Where |
|---|---|---|
| Source Spring Boot | 2.7.12 | `src/employee-service/pom.xml` `<parent>` spring-boot-starter-parent |
| Source Java | 17 | `pom.xml` `java.version` |
| `javax` usage | yes — `javax.servlet.http.HttpServletResponse` (report-service main), `javax.ws.rs.core.Application` (employee-service test) | `grep -rn "import javax\."` over `src/` |
| Required path | 2.7 → 3.x (Jakarta EE 9+ namespace move) → 4.1.1 | Boot 3 is the `javax` → `jakarta` generation |
| Packs available | `spring-boot-3-to-4` only | `references/` |

**What the unmodified 04D did** ([log](./evidence/roundB-unmodified/B1-unmodified-detect.log), [log](./evidence/roundB-unmodified/B1-unmodified-proceeds.log)):

```text
$ node scripts/detect-baseline.js --project ../../../src/employee-service --slug b1-employee-2x --to-java 21 --to-version 4.1.1
  Platform parent      org.springframework.boot:spring-boot-starter-parent:2.7.12
  Reference packs matched:
    spring-boot-3-to-4 — Spring Boot 3.x to 4.x (Java 17 to 21)
      references/spring-boot-3-to-4.md  (matched on org.springframework.boot:spring-boot-starter-web)
    ! source ... 2.7.12 is not on the 3.5.x preparation line the pack expects — a planning input, not an automatic upgrade
  Next:    node scripts/prepare-workspace.js --slug b1-employee-2x                       exit=0
$ node scripts/prepare-workspace.js --slug b1-employee-2x                                    exit=0  (33 files copied)
$ node scripts/run-migration-build.js --slug b1-employee-2x --baseline --jdk 17          TESTS-FAILED (compiled; Mongo-backed tests timed out)
```

**Classification: BUG.** A Boot 2.7 project was accepted as a valid 3→4 source; nothing in the skill
noticed that the 2→3 (Jakarta) generation was missing. The only signal was a soft "planning input"
warning about the 3.5 preparation line. Its run summary reads `IN_PROGRESS — stopped after BASELINE_BUILT`.

**Responsible detection logic:**

| Location | Code | Why it is wrong |
|---|---|---|
| `references/spring-boot-3-to-4.md` front matter, `detect:` | `- org.springframework.boot:spring-boot-starter-web` | An unversioned entry, contrary to `references/README.md` ("keep `detect:` entries specific enough that they cannot match a project that has *already* been migrated — a version prefix…") |
| `scripts/lib/references.js` `matchesCoordinate`, l. 254 | `if (!versionPrefix) return true;` | An unversioned entry matches **any** version |
| `scripts/lib/references.js` `matchReferencePacks` | `.filter((pack) => pack.matched.length > 0)` | **Any one** matching entry selects the pack |
| `scripts/detect-baseline.js` `migrationPath`, l. 231 | `warnings.push(... 'a planning input, not an automatic upgrade')` | A source outside the pack's generation is only a warning, never a stop |

### B1′ — after the fix (`validate/04d-integration`)

```text
  Reference packs eligible (a detect entry nominates; the declared source version decides):
    ✗ spring-boot-3-to-4 — NOT ELIGIBLE: source org.springframework.boot:spring-boot-starter-parent 2.7.12 (parent) is generation 2, but the pack migrates from 3.x
  Migration path gate: UNSUPPORTED_MIGRATION_PATH
    2.7.12 → 3.x   spring-boot-2-to-3   MISSING
    3.x → 4.1.1   spring-boot-3-to-4   available
  BLOCKED — ... reaching 4.1.1 requires 2.7.12 → 3.x (spring-boot-2-to-3: MISSING), 3.x → 4.1.1 (spring-boot-3-to-4)
  Missing capability: spring-boot-2-to-3                                                   exit=2
$ node scripts/prepare-workspace.js --slug b1-employee-2x
Refused: the baseline for "b1-employee-2x" is not migratable — UNSUPPORTED_MIGRATION_PATH: ...  exit=1
```

[MIGRATION_SUMMARY (BLOCKED)](./evidence/roundB-fixed/reports/migration-runs/b1-employee-2x-20261001T030532Z/MIGRATION_SUMMARY.md) ·
[log](./evidence/roundB-fixed/B1-fixed.log). `baseline.json` records:

```json
"migration_path": { "status": "UNSUPPORTED_MIGRATION_PATH", "source": "2.7.12", "requested_target": "4.1.1",
  "required_path": { "steps": [ { "from": "2.7.12", "to": "3.x", "capability": "spring-boot-2-to-3", "available": false },
                                { "from": "3.x", "to": "4.1.1", "capability": "spring-boot-3-to-4", "available": true } ] },
  "missing_capability": [ "spring-boot-2-to-3" ] }
```

---

## B2 — Spring Boot 3.5.0 → 4.1.1 (the known-good input), unmodified 04D

Run by an agent following the unmodified `SKILL.md` exactly (Steps 1–11, 13; no `--to-project`).
[Trace](./evidence/roundB-unmodified/B2-trace.log) ·
[MIGRATION_SUMMARY (PASS)](./evidence/roundB-unmodified/reports/migration-runs/b2-golden-20261001T025104Z/MIGRATION_SUMMARY.md) ·
[migration report](./evidence/roundB-unmodified/reports/migration_b2-golden.md) ·
[diff](./evidence/roundB-unmodified/reports/migration_b2-golden.diff)

| Check | Result | Evidence |
|---|---|---|
| Repository discovery | 1 Maven project; Java 17 (`java.version`, compiler `<source>/<target>`); parent 3.5.0 | `baseline.json` |
| Pack selection | `spring-boot-3-to-4`, matched on the parent `:3` entry (+ starter-web); on the 3.5.x preparation line | `baseline.json` |
| Migration plan | 13 impacts, 9 constraints, 1 deterministic candidate (`boot4-curated`); `--check-plan` valid | `migration-plan.json` |
| OpenRewrite | rewrite-maven-plugin 6.46.1 / rewrite-spring 6.37.1 (Moderne Source Available License). `rewrite-00` dry-run: 7 files proposed; **rejected** `ErrorResponse.java` (would drop `@JsonFormat` → changes error timestamp contract) and one pom hunk (un-pins Testcontainers 1.20.1 → 2.0.5). `rewrite-01` apply: 6 files, scope check OK, reconciliation `matches-request` | `transformations/rewrite-0{0,1}.*` |
| Dependency changes | parent 3.5.0 → 4.1.1; `spring-boot-starter-web` → `spring-boot-starter-webmvc`; test starters split (webmvc-test, data-jpa-test, security-test); resolved: Framework 7.0.9, Security 7.1.1, Hibernate 7.4.5, Jackson 3.1.5 | report §1, §5 |
| Java / Spring / config | `java.version` 17 → 21, compiler `<release>`, Dockerfile `17-jre` → `21-jre`; Jackson 3 `JsonMapper.builder()`; health contributor + `@MockitoBean`/`@WebMvcTest`/`@DataJpaTest` imports moved | report §4 |
| Jakarta | Not applicable — the source is already on `jakarta.*` (Boot 3) | — |
| Residual edits | 2, both evidence-backed: restore the Testcontainers pin (rejected hunk), web → webmvc rename (pack §1.3) | `migration.json` `code_changes` |
| Compile history | R0 `package` JDK 17 → tests-failed (19 run / 2 failed, pre-existing) · R1 `test-compile` JDK 21 → **passed** · R2 `package` JDK 21 → tests-failed (19 / 2, **the same two**) · R3 `package-skip-tests` JDK 21 → **passed** | `rounds/round-0{0..3}` |
| Tests | 19 run before and after; the same 2 pre-existing failures (`EmployeeControllerTest.testActuator{Health,Info}_Public`: a `@WebMvcTest` slice has no actuator); 5/5 Testcontainers PostgreSQL tests pass both sides; **no new failures** | report §3 |
| Runtime | Started on JDK 17 (14.6 s) and JDK 21 (20.1 s); readiness 200 | `runtime/{baseline,final}.json` |
| Behaviour | 13 probes, **0 status changes**; 5 byte-identical (all business endpoints); 3 non-deterministic (timestamps); 5 expected framework changes (ProblemDetail `type`, 401 timestamp format + `WWW-Authenticate` charset, health shape, metrics list). Security boundary preserved (401/401, 200 authenticated) | report §6 |
| Apply gate | `apply-migration.js` dry-run: "Eligible to apply: YES" (6 files); `git apply --check` against the input: exit 0; input left untouched | B2 trace |

**B2 verdict: PASS** — the existing 04D migrates a real Boot 3.5.0 application to 4.1.1 / Java 21 with
deterministic transformations, evidence-driven repair, unchanged tests and compared runtime behaviour.

**Defects the B2 run reported (in the unmodified skill):**

1. SKILL.md Step 1 has no `--slug`, which created a stray session — **fixed** (Step 1 now passes `--slug`).
2. The curated recipe does not rename `spring-boot-starter-web`, though pack §15 says it covers starter renames — open (pack content).
3. Pack §1.3's symptom is wrong for 4.1.1 (`spring-boot-starter-web` 4.1.1 exists, only deprecated) — open.
4. The curated recipe still removes the Testcontainers pin — open (pack content; handled by preview inspection).
5. A rejected hunk in a shared file can only be reverted after the apply's own build — open (design).
6. Pack §13 understates the health payload change — open.
7. `apply-migration.js` prints the default diff path, ignoring `MIGRATION_REPORT_DIR` — open (cosmetic).
8. Probe body excerpts are capped at 1500 characters (metrics list not fully comparable) — open.

## Environment for Round B

- JDKs: Temurin **17.0.20.1** (portable, `MIGRATION_JDK_17`) and Microsoft **21.0.11** (`MIGRATION_JDK_21`); Maven **3.9.9** (portable, `MIGRATION_MVN`); B1 round 0 used the module wrapper (Maven 3.8.7).
- Docker: the B2 agent had to start Docker Desktop (it was stopped). Docker then auto-started five pre-existing
  containers of the user's (`smdag-*`, `redis-server`, restart policy `unless-stopped`); they were not touched.
  Testcontainers ran `postgres:16` + Ryuk 0.8.1 in rounds 0 and 2 and removed them. Docker Desktop was shut down
  again at the end of B2.
- Network: Maven Central for the Boot 4.1.1 BOM, rewrite-maven-plugin 6.46.1 and rewrite-spring 6.37.1.
