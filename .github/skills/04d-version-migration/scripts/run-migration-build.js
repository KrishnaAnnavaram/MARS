#!/usr/bin/env node
/**
 * Version Migration — Step 3 (repeated): one build round.
 *
 * Builds the sandbox workspace on a chosen JDK, then turns the log into facts: an outcome,
 * every compiler/resolver error with its file and line, a category for each error, and what
 * the workspace declares and has changed at that moment. One JSON record per round, so the
 * final report can show the migration as the sequence of rounds it actually was.
 *
 * The category assigned to an error describes the *shape* of the breakage (a package that no
 * longer exists, a signature that changed, an artifact that cannot be resolved). It never
 * says which library caused it and never proposes a fix — that judgement comes from the
 * agent reading the reference pack. Errors are also grouped by shared subject (parser facts),
 * and — when a migration plan exists — matched against its expected symptoms, a correlation
 * that is recorded as a heuristic and never as compiler truth.
 *
 * Round 0 is immutable: it must run on the untouched sandbox, and no later round runs until it
 * exists. A round always means a build happened.
 *
 * Optionally, before the build, a deterministic OpenRewrite transformation named by the
 * migration plan runs in the sandbox (`--rewrite`):
 *   dry-run  previews it — writes transformations/rewrite-NN.{json,patch,log}, changes no source,
 *            and runs no build (there is nothing new to build).
 *   apply    requires an inspected dry-run (`--rewrite-preview rewrite-NN`), checkpoints the
 *            sandbox, applies, reverts if anything outside the preview changed, and then builds
 *            immediately; the round and the transformation record point at each other.
 *
 * Usage:
 *   node scripts/run-migration-build.js --slug <slug> --baseline --jdk 17
 *   node scripts/run-migration-build.js --slug <slug> --jdk 21 --intent test-compile --label "swapped starters"
 *   node scripts/run-migration-build.js --slug <slug> --jdk 21 --intent package
 *   node scripts/run-migration-build.js --slug <slug> --jdk 21 --rewrite dry-run --rewrite-id boot4-curated
 *   node scripts/run-migration-build.js --slug <slug> --jdk 21 --rewrite apply --rewrite-id boot4-curated --rewrite-preview rewrite-00 --intent test-compile
 */
const fs = require('fs');
const path = require('path');
const {
  sessionPaths, readJson, writeJson, rel, runTool, run, tail,
  resolveJdk, envForJdk, resolveBuildTool, buildArgs,
  inventoryProject, parseBuildErrors, summariseErrors, buildOutcome, nextRoundNumber, stripRootFromText,
  listRounds, groupErrors, correlateGroups, redact, validateTemplate, recordState, declaredPlatformVersion,
  TEST_RUN_INTENTS, isSandboxRepo, inferState,
} = require('./lib/migration');
const { resolveReferencePack } = require('./lib/references');
const { selectTransformation, executeTransformation, linkBuildRound, MODES } = require('./lib/openrewrite');

const INTENTS = ['compile', 'test-compile', 'test', 'package', 'verify', 'package-skip-tests'];
const MAX_RECORDED_ERRORS = 200;

function parseArgs(argv) {
  const args = { intent: 'package', rewriteRecipes: [], rewriteArtifacts: [], rewriteExcludes: [] };
  const list = (value) => String(value || '').split(',').map((s) => s.trim()).filter(Boolean);
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--slug' || a === '-s') args.slug = argv[++i];
    else if (a === '--jdk' || a === '-j') args.jdk = argv[++i];
    else if (a === '--intent' || a === '-i') { args.intent = argv[++i]; args.intentGiven = true; }
    else if (a === '--label' || a === '-l') args.label = argv[++i];
    else if (a === '--round') args.round = Number(argv[++i]);
    else if (a === '--baseline' || a === '-b') args.baseline = true;
    else if (a === '--timeout') args.timeout = Number(argv[++i]) * 1000;
    else if (a === '--rewrite') args.rewrite = argv[++i];
    else if (a === '--rewrite-id') args.rewriteId = argv[++i];
    else if (a === '--rewrite-recipe') args.rewriteRecipes.push(...list(argv[++i]));
    else if (a === '--rewrite-artifact') args.rewriteArtifacts.push(...list(argv[++i]));
    else if (a === '--rewrite-plugin-version') args.rewritePluginVersion = argv[++i];
    else if (a === '--rewrite-preview') args.rewritePreview = argv[++i];
    else if (a === '--rewrite-exclude') args.rewriteExcludes.push(...list(argv[++i]).map((f) => f.split('\\').join('/')));
    else if (a === '--rewrite-policy') args.rewritePolicy = argv[++i];
    else if (a === '--check-plan') args.checkPlan = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Version Migration — Build round

  node scripts/run-migration-build.js --slug <slug> --jdk <major> [--intent <intent>] [--label "<what changed>"]

Options:
  --slug, -s      Session name
  --jdk, -j       JDK major version to build on, e.g. 17 or 21
  --intent, -i    ${INTENTS.join(' | ')}   (default: package)
  --label, -l     One line describing what was changed before this round — shown in the report
  --baseline, -b  Record this as round 0, the pre-migration reference build (untouched sandbox only)
  --round         Force a round number (rarely needed; rounds auto-increment)
  --timeout       Seconds before the build (or transformation) is abandoned (default 900 / 1800)
  --help, -h      Show this message

Deterministic transformation (OpenRewrite, sandbox only — recipes must be named by migration-plan.json):
  --rewrite dry-run|apply      dry-run previews and does not build; apply applies then builds
  --rewrite-id <id>            Plan candidate / pack transformation id (needed when the plan has several)
  --rewrite-recipe <name>      Narrow to these recipes (repeatable or comma-separated; must be in the plan)
  --rewrite-artifact <g:a:v>   Override the recipe artifact coordinate(s) (pinned versions only)
  --rewrite-plugin-version <v> Override the pinned rewrite-maven-plugin version
  --rewrite-preview <rewrite-NN>  apply only: the dry-run record that was inspected
  --rewrite-exclude <file>     apply only: a previewed file rejected as out of scope — restored after
                               the apply and recorded (repeatable or comma-separated; whole files only)
  --rewrite-policy optional|required  Override the pack's policy for an unavailable recipe

Session check (runs no build):
  --check-plan    Validate migration-plan.json against its schema and print the session state`);
}

/** Schema errors plus the cross-checks a schema cannot express. Empty means the plan is usable. */
function planProblems(slug, plan) {
  const paths = sessionPaths(slug);
  const errors = validateTemplate(plan, 'migration-plan.schema.json');
  if (errors.length) return errors;
  const probes = readJson(paths.probes);
  const probeNames = new Set(((probes && probes.requests) || []).map((r) => r.name));
  const impactIds = new Set((plan.impact || []).map((i) => i.id));
  for (const c of plan.characterization || []) {
    if (c.probe && probes && !probeNames.has(c.probe)) errors.push(`characterization names probe "${c.probe}", which probes.json does not define`);
    for (const id of c.protects || []) if (!impactIds.has(id)) errors.push(`characterization protects unknown impact "${id}"`);
  }
  for (const d of plan.deterministic_candidates || []) {
    for (const id of d.covers || []) if (!impactIds.has(id)) errors.push(`deterministic candidate ${d.id} covers unknown impact "${id}"`);
  }
  const baseline = readJson(paths.baseline);
  const requested = baseline && baseline.target && baseline.target.platform ? baseline.target.platform.version : null;
  if (requested && plan.target.platform.version !== requested) {
    errors.push(`plan target ${plan.target.platform.version} is not the requested ${requested}`);
  }
  return errors;
}

/** Validates the plan and prints where the session stands. Never builds, never changes anything. */
function checkPlan(slug) {
  const paths = sessionPaths(slug);
  const state = inferState(slug);
  console.log(`\nSession "${slug}" — state ${state.state}${state.legacy ? ' (pre-v2: inferred from files)' : ''}`);
  console.log(`  reached ${state.reached.join(' → ') || 'nothing yet'}`);
  if (state.blocked) console.log(`  BLOCKED: ${state.blocked}`);
  const plan = readJson(paths.plan);
  if (!plan) return fail(`\n  No migration plan at ${rel(paths.plan)}.`);
  const errors = planProblems(slug, plan);
  if (errors.length) return fail('\n  migration-plan.json is NOT valid:', ...errors);
  console.log(`\n  migration-plan.json is valid: ${plan.impact.length} impact entr${plan.impact.length === 1 ? 'y' : 'ies'}, ${plan.deterministic_candidates.length} deterministic candidate(s), ${(plan.constraints || []).filter((c) => c.status === 'unresolved').length} unresolved constraint(s).\n`);
  return true;
}

function workspaceState(workspace, baselineCommit) {
  const git = (...a) => run('git', ['-C', workspace, ...a]);
  git('add', '-A');
  const status = git('diff', '--cached', '--name-status', baselineCommit).stdout.trim();
  const stat = git('diff', '--cached', '--shortstat', baselineCommit).stdout.trim();
  const files = status
    ? status.split(/\r?\n/).map((line) => {
      const [state, ...rest] = line.split(/\t/);
      return { state, file: rest.join(' -> ').split(path.sep).join('/') };
    })
    : [];
  return { changed_files: files, diff_stat: stat || 'no changes yet' };
}

function fail(message, ...more) {
  console.error(message);
  for (const m of more) console.error(`  ${m}`);
  process.exitCode = 1;
  return null;
}

/**
 * The optional OpenRewrite phase. Returns { proceed: bool, record } — proceed means "now build".
 */
function transformationPhase(args, ctx) {
  const { paths, baseline, jdk, tool } = ctx;
  if (!MODES.includes(args.rewrite)) return { proceed: false, error: fail(`--rewrite must be one of ${MODES.join(', ')}`) };
  if (args.baseline) return { proceed: false, error: fail('--rewrite cannot be combined with --baseline: round 0 is the untouched project.') };

  const rounds = listRounds(args.slug);
  const round0 = rounds.find((r) => r.baseline || r.round === 0);
  if (!round0) return { proceed: false, error: fail('Refused: no round 0. Record the pre-migration build (and probe) before any transformation.') };
  if (['compile-failed', 'dependency-failed', 'timed-out'].includes(round0.outcome)) {
    return { proceed: false, error: fail(`Refused: round 0 ended ${round0.outcome}. A project that does not build before the migration cannot be migrated.`) };
  }
  if (!fs.existsSync(path.join(paths.runtimeDir, 'baseline.json'))) {
    return { proceed: false, error: fail('Refused: no baseline runtime record (runtime/baseline.json). Run probe-runtime.js --phase baseline first — behaviour must be observed before anything changes.') };
  }
  const plan = readJson(paths.plan);
  if (!plan) return { proceed: false, error: fail(`Refused: no migration plan at ${rel(paths.plan)}. Write it per templates/migration-plan.schema.json.`) };
  const planErrors = planProblems(args.slug, plan);
  if (planErrors.length) return { proceed: false, error: fail('Refused: migration-plan.json does not validate:', ...planErrors) };

  const packRef = (baseline.reference_packs || [])[0];
  const pack = packRef ? resolveReferencePack(packRef.id) : null;
  const selected = selectTransformation({
    pack,
    plan,
    id: args.rewriteId,
    overrides: {
      recipes: args.rewriteRecipes,
      artifacts: args.rewriteArtifacts,
      pluginVersion: args.rewritePluginVersion,
      policy: args.rewritePolicy,
    },
  });
  if (selected.error) return { proceed: false, error: fail(`Refused: ${selected.error}`) };

  let preview = null;
  if (args.rewrite === 'apply') {
    if (!args.rewritePreview) return { proceed: false, error: fail('Refused: apply needs --rewrite-preview <rewrite-NN>, the dry-run you inspected.') };
    preview = readJson(path.join(paths.transformationsDir, `${args.rewritePreview}.json`));
    if (!preview) return { proceed: false, error: fail(`Refused: no transformation record ${args.rewritePreview}.`) };
  }

  const t = selected.transformation;
  console.log(`\nOpenRewrite ${args.rewrite} — ${t.id}${t.pack_transformation && t.pack_transformation !== t.id ? ` (pack: ${t.pack_transformation})` : ''}`);
  console.log(`  recipes   ${t.recipes.join(', ')}`);
  console.log(`  artifacts ${t.artifacts.join(', ') || '—'}`);
  console.log(`  licence   ${t.license || 'not recorded'} · policy ${t.policy}`);
  console.log(`  sandbox   ${rel(paths.workspace)} on JDK ${jdk.major}`);
  console.log('  running…');

  const record = executeTransformation({
    slug: args.slug,
    mode: args.rewrite,
    transformation: t,
    tool,
    jdk,
    preview,
    vars: {
      target_platform_version: baseline.target && baseline.target.platform ? baseline.target.platform.version : null,
      target_language: (baseline.target && baseline.target.language && baseline.target.language.version) || baseline.language.target,
    },
    detect: pack ? pack.detect : [],
    projectDir: baseline.project.dir,
    timeoutMs: args.timeout || 1800000,
    exclude: args.rewrite === 'apply' ? args.rewriteExcludes : [],
  });

  console.log(`\n  Record    ${rel(path.join(paths.transformationsDir, `${record.id}.json`))}`);
  console.log(`  Status    ${record.status.toUpperCase()}${record.exit_code !== null ? ` (exit ${record.exit_code})` : ''}`);
  if (record.command) console.log(`  Command   ${record.command}`);
  if (record.log) console.log(`  Log       ${record.log}`);
  for (const n of record.notes) console.log(`  ! ${n}`);

  if (record.status === 'unavailable') {
    console.log(`\n  OpenRewrite is UNAVAILABLE here (${record.availability.reason}). Nothing was transformed and the`);
    console.log('  record says so — it will never be reported as having run.');
    if (record.policy === 'required') {
      recordState(args.slug, 'BLOCKED', 'run-migration-build.js', `required transformation ${t.id} unavailable: ${record.availability.reason}`);
      console.error(`\n  BLOCKED: the plan/pack marks ${t.id} as required. Resolve access (network, repository credentials, licence) and re-run.`);
      process.exitCode = 1;
    } else {
      console.log(`  Policy is optional: continue on the compiler-driven path — change the declared versions per the`);
      console.log('  reference pack and let the build rounds name each source change.\n');
    }
    return { proceed: false, record };
  }
  if (['failed', 'rejected-config', 'rejected-no-preview', 'reverted'].includes(record.status)) {
    console.error(`\n  The transformation did not produce an accepted result (${record.status}). This is recorded, not hidden.`);
    if (record.status === 'reverted') {
      console.error(`  Out-of-preview files: ${(record.scope_check.out_of_preview || []).join(', ') || '—'}`);
      console.error(`  Infrastructure lines: ${(record.scope_check.infrastructure_lines || []).join(' | ') || '—'}`);
      console.error('  The sandbox was restored to the checkpoint taken before the apply.');
    }
    process.exitCode = 1;
    return { proceed: false, record };
  }
  if (args.rewrite === 'dry-run') {
    if (record.status === 'previewed') {
      recordState(args.slug, 'TRANSFORMATION_PREVIEWED', 'run-migration-build.js', `${record.id}: ${record.proposed_files.length} file(s) proposed`);
      console.log(`\n  Proposed changes to ${record.proposed_files.length} file(s) — the sandbox source is unchanged:`);
      for (const f of record.proposed_files) {
        const chain = (record.attribution[f] || []).filter((a) => a.depth <= 1).map((a) => a.recipe.replace(/:.*$/, '').split('.').pop());
        console.log(`    ${f}${chain.length ? `   ← ${[...new Set(chain)].join(', ')}` : ''}`);
      }
      console.log(`\n  Patch     ${record.patch}`);
      console.log('  Read the patch against the plan\'s impact list. Reject anything outside the migration\'s scope');
      console.log('  (narrow --rewrite-recipe or pick another candidate and preview again). Only then apply with');
      console.log(`    --rewrite apply --rewrite-id ${t.id} --rewrite-preview ${record.id}\n`);
    } else {
      console.log('\n  The recipes proposed no change to this project.\n');
    }
    return { proceed: false, record };
  }
  // apply
  if (record.status === 'no-changes') {
    console.log('\n  Applied, but nothing changed — no build round is needed.\n');
    return { proceed: false, record };
  }
  recordState(args.slug, 'TRANSFORMATION_APPLIED', 'run-migration-build.js', `${record.id}: ${record.changed_files.length} file(s)`);
  console.log(`\n  Applied to ${record.changed_files.length} file(s) in the sandbox; all inside the inspected preview.`);
  if (record.excluded_files.length) console.log(`  Excluded as out of scope (restored): ${record.excluded_files.join(', ')}`);
  if (record.reconciliation && record.reconciliation.status === 'reconcile-required') {
    console.log(`  ! Target reconciliation required: recipe left ${record.reconciliation.declared_after_apply}, requested ${record.reconciliation.requested_platform_version}.`);
  }
  console.log('  Building immediately…');
  return { proceed: true, record };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();
  if (!args.slug) return fail('--slug is required.');
  if (args.checkPlan) return checkPlan(args.slug);
  if (!INTENTS.includes(args.intent)) return fail(`Unknown --intent "${args.intent}". One of: ${INTENTS.join(', ')}`);

  const paths = sessionPaths(args.slug);
  const baseline = readJson(paths.baseline);
  const meta = readJson(paths.workspaceMeta);
  if (!baseline || !meta) return fail(`Session "${args.slug}" is not set up — run detect-baseline.js then prepare-workspace.js.`);
  if (!isSandboxRepo(paths.workspace)) return fail(`Workspace missing (or not a sandbox repository) at ${rel(paths.workspace)} — run prepare-workspace.js.`);

  const jdkMajor = args.jdk || baseline.language.declared;
  const jdk = resolveJdk(jdkMajor);
  if (!jdk) {
    return fail(`No JDK ${jdkMajor} found on this machine.`,
      `Install it, or point MIGRATION_JDK_${jdkMajor} at an existing install.`,
      `Detected: ${(baseline.toolchain.installed_jdks || []).map((j) => j.major).join(', ') || 'none'}`);
  }

  const tool = resolveBuildTool(paths.workspace);
  if (!tool.command && !args.rewrite) {
    return fail(`No ${tool.tool} build tool found for the workspace.`, 'Install it, or set MIGRATION_MVN to an absolute path.');
  }

  // Round 0 is the untouched project; nothing migrates before it exists.
  const existingRounds = listRounds(args.slug);
  const hasRound0 = existingRounds.some((r) => r.baseline || r.round === 0);
  if (args.baseline) {
    const pre = workspaceState(paths.workspace, meta.baseline_commit);
    if (pre.changed_files.length) {
      return fail('Refused: round 0 must run on the untouched sandbox, but it already differs from the project:',
        ...pre.changed_files.slice(0, 10).map((c) => `${c.state} ${c.file}`),
        'Re-create it with prepare-workspace.js --force, then record round 0 before changing anything.');
    }
    if (hasRound0) console.log('  (re-recording round 0 — the sandbox is still identical to the project)');
  } else if (!hasRound0 && args.round !== 0) {
    return fail('Refused: no round 0 recorded. Run the pre-migration reference build first:',
      `node scripts/run-migration-build.js --slug ${args.slug} --baseline --jdk ${baseline.language.declared || '<source JDK>'}`);
  }

  let transformation = null;
  if (args.rewrite) {
    const phase = transformationPhase(args, { paths, baseline, jdk, tool });
    if (!phase.proceed) return null;
    transformation = phase.record;
    if (!args.intentGiven) args.intent = 'test-compile';
    if (!tool.command) return fail(`No ${tool.tool} build tool found for the workspace.`);
  }

  const round = args.baseline ? 0 : (args.round !== undefined ? args.round : nextRoundNumber(args.slug));
  const mvnArgs = buildArgs(tool.tool, args.intent);
  const label = args.label || (transformation
    ? `OpenRewrite ${transformation.id} applied (${transformation.recipes.join(', ')})`
    : null);
  const started = Date.now();
  console.log(`\nRound ${round} — ${tool.tool} ${mvnArgs.join(' ')} on JDK ${jdk.major} (${jdk.version})`);
  console.log(`  workspace ${rel(paths.workspace)}`);
  if (label) console.log(`  change    ${label}`);
  console.log('  building…');

  const result = runTool(tool.command, mvnArgs, {
    cwd: paths.workspace,
    env: envForJdk(jdk, { MAVEN_OPTS: process.env.MAVEN_OPTS || '' }),
    timeout: args.timeout || 900000,
  });
  const durationMs = Date.now() - started;
  const log = redact(`${result.stdout}\n${result.stderr}`);
  const errors = parseBuildErrors(log, paths.workspace);
  const summary = summariseErrors(errors);
  const outcome = result.error && /ETIMEDOUT|timed out/i.test(result.error)
    ? 'timed-out'
    : buildOutcome(result, errors);

  const declared = inventoryProject(paths.workspace);
  const state = workspaceState(paths.workspace, meta.baseline_commit);
  const packRef = (baseline.reference_packs || [])[0];
  const pack = packRef ? resolveReferencePack(packRef.id) : null;
  const groups = groupErrors(errors, paths.workspace);
  const plan = readJson(paths.plan);

  const record = {
    round,
    label: label || (round === 0 ? 'Pre-migration reference build' : null),
    baseline: Boolean(args.baseline) || round === 0,
    started_at: new Date(started).toISOString(),
    duration_ms: durationMs,
    jdk: { major: jdk.major, version: jdk.version, home: jdk.home, source: jdk.source },
    build: {
      tool: tool.tool,
      kind: tool.kind,
      command: `${tool.display || tool.command} ${mvnArgs.join(' ')}`,
      intent: args.intent,
      exit_code: result.status,
      spawn_error: result.error,
    },
    outcome,
    declared: {
      java: declared.javaVersion,
      parent: declared.parent,
      dependency_count: (declared.dependencies || []).length,
      platform: pack ? declaredPlatformVersion(declared, pack.detect) : null,
    },
    workspace: state,
    error_summary: summary,
    errors: errors.slice(0, MAX_RECORDED_ERRORS),
    errors_truncated: Math.max(0, errors.length - MAX_RECORDED_ERRORS),
    log_tail: tail(stripRootFromText(log, paths.workspace), 8000),
    // v2 — additive
    transformation: transformation ? transformation.id : null,
    error_groups: groups,
    correlation: plan && groups.length ? correlateGroups(groups, plan) : null,
  };

  const file = writeJson(path.join(paths.roundsDir, `round-${String(round).padStart(2, '0')}.json`), record);
  fs.writeFileSync(path.join(paths.roundsDir, `round-${String(round).padStart(2, '0')}.log`), log);
  if (transformation) linkBuildRound(args.slug, transformation.id, round);

  if (record.baseline) {
    recordState(args.slug, 'BASELINE_BUILT', 'run-migration-build.js', `round 0 ${outcome}`);
  } else if (outcome === 'passed' || outcome === 'tests-failed') {
    const round0 = existingRounds.find((r) => r.baseline || r.round === 0);
    const tested = TEST_RUN_INTENTS.includes(args.intent) && round0 && round0.build.intent === args.intent;
    recordState(args.slug, tested ? 'TARGET_TESTED' : 'TARGET_COMPILED', 'run-migration-build.js', `round ${round} ${outcome}`);
  }

  const mark = outcome === 'passed' ? 'PASSED' : outcome.toUpperCase();
  console.log(`\n  Outcome     ${mark}  (exit ${result.status}, ${(durationMs / 1000).toFixed(1)}s)`);
  console.log(`  Declared    Java ${declared.javaVersion || '?'}${declared.parent ? ` · ${declared.parent.artifactId} ${declared.parent.version}` : ''}`);
  console.log(`  Workspace   ${state.diff_stat}`);
  if (summary.total) {
    console.log(`\n  ${summary.total} error line(s) by category:`);
    for (const c of summary.byCategory) {
      console.log(`    ${String(c.count).padStart(4)}  ${c.label}`);
      console.log(`          ${c.hint}`);
    }
    if (summary.byFile.length) {
      console.log(`\n  Files with the most errors:`);
      for (const f of summary.byFile.slice(0, 10)) console.log(`    ${String(f.count).padStart(4)}  ${f.file}`);
    }
    console.log(`\n  Error groups (parser facts — same category and subject):`);
    for (const g of groups.slice(0, 12)) {
      console.log(`    ${g.id.padEnd(4)} ${String(g.count).padStart(3)}× ${g.subject}${g.package_family ? `  [${g.package_family}]` : ''} — ${g.files.length} file(s)`);
    }
    if (record.correlation && record.correlation.matches.length) {
      console.log(`\n  Plan correlation (heuristic text match, not compiler truth):`);
      for (const m of record.correlation.matches) console.log(`    ${m.group} ↔ impact ${m.impact.join(', ')}`);
    }
    console.log(`\n  Distinct messages (first 12):`);
    const distinct = [...new Set(errors.map((e) => e.message))].slice(0, 12);
    for (const m of distinct) console.log(`    - ${m.length > 150 ? `${m.slice(0, 150)}…` : m}`);
  }
  console.log(`\n  Round record ${rel(file)}`);
  console.log(`  Full log     ${rel(file).replace(/\.json$/, '.log')}`);
  if (outcome === 'passed') {
    console.log(`\n  Build is green on JDK ${jdk.major}. Next: probe the runtime, then render the report.`);
  } else {
    console.log(`\n  Read the grouped errors against the plan and the reference pack; prefer a deterministic recipe,`);
    console.log('  otherwise make the narrowest residual edit in the sandbox, then run the next round.');
  }
  console.log('');
  return record;
}

if (require.main === module) require('./lib/summary').runAndFinalize(main, 'run-migration-build.js');

module.exports = { parseArgs, workspaceState, checkPlan, INTENTS };
