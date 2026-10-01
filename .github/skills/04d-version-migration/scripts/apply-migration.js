#!/usr/bin/env node
/**
 * Version Migration — optional last step: put the migrated code into the real project.
 *
 * Everything before this point happens in a sandbox. This is the one script that can write
 * to the project directory, it never runs as part of the normal flow, and it refuses unless
 * --to-project is passed explicitly AND the session is eligible:
 *
 *   - a baseline and a pre-migration round 0 exist, and at least one target round ran;
 *   - the last recorded round is green (for a v2 session: on a goal that packages the artifact);
 *   - if the application was characterised at runtime before the migration, it was probed after;
 *   - nothing blocks it: no BLOCKED state, no open blocking condition in migration.json, no
 *     unresolved blocking plan constraint, and the final declared platform version is the one
 *     that was requested;
 *   - the project has not drifted since the sandbox was copied, so the change lands cleanly.
 *
 * A session written before v2 (no plan, no state.json) is held only to the checks its own files
 * can answer — it is never refused merely for lacking the newer optional evidence.
 *
 * It copies the changed files out of the sandbox rather than applying a patch, so it cannot fail
 * halfway on a context mismatch: the sandbox holds the exact tree that was built and probed.
 * Without --to-project it prints what would change, and whether it would be allowed, and writes
 * nothing.
 *
 * Usage:
 *   node scripts/apply-migration.js --slug <slug>                # dry run, prints the plan
 *   node scripts/apply-migration.js --slug <slug> --to-project   # actually writes the project
 */
const fs = require('fs');
const path = require('path');
const {
  sessionPaths, readJson, listRounds, rel, run, inferState, isSandboxRepo,
} = require('./lib/migration');

const PACKAGING_INTENTS = ['package', 'package-skip-tests', 'verify'];

/**
 * Every reason the session may not be applied. Empty means eligible. Pure apart from reading the
 * session and (for the drift check) the project — so it can be tested without ever applying.
 */
function eligibility(slug, changes = null) {
  const p = sessionPaths(slug);
  const reasons = [];
  const notes = [];
  const baseline = readJson(p.baseline);
  const meta = readJson(p.workspaceMeta);
  if (!baseline || !meta) return { eligible: false, reasons: ['session is not set up (no baseline.json / workspace.json)'], notes, legacy: true };
  const state = inferState(slug);
  const legacy = state.legacy;
  const rounds = listRounds(slug);
  const round0 = rounds.find((r) => r.baseline || r.round === 0);
  const targets = rounds.filter((r) => !(r.baseline || r.round === 0));
  const last = rounds[rounds.length - 1];

  if (!last) reasons.push('no build rounds recorded — nothing has been verified');
  if (!round0) reasons.push('no pre-migration round 0 — there is no reference to compare against');
  if (!targets.length) reasons.push('no target build round — the migrated code was never built');
  if (last && last.outcome !== 'passed') reasons.push(`the last round (${last.round}) ended "${last.outcome}", not "passed"`);
  if (!legacy && last && last.outcome === 'passed' && !PACKAGING_INTENTS.includes(last.build.intent)) {
    reasons.push(`the last green round ran "${last.build.intent}", which does not prove the application packages — finish with package or package-skip-tests`);
  }

  const before = readJson(path.join(p.runtimeDir, 'baseline.json'));
  const after = readJson(path.join(p.runtimeDir, 'final.json'));
  if (before && before.started && !(after && after.started)) {
    reasons.push('the application was probed before the migration but not successfully after it — behaviour is unproven');
  }

  if (state.state === 'BLOCKED') reasons.push(`session is BLOCKED: ${state.blocked || 'see state.json'}`);
  const migration = readJson(p.migration);
  for (const c of (migration && migration.blocking_conditions) || []) {
    if (c.status === 'open') reasons.push(`open blocking condition: ${c.condition}`);
  }
  const plan = readJson(p.plan);
  for (const c of (plan && plan.constraints) || []) {
    if (c.blocking && ['unresolved', 'violated'].includes(c.status)) {
      const closed = ((migration && migration.unresolved_constraints) || [])
        .some((u) => u.constraint === c.id && ['resolved', 'waived'].includes(u.status));
      if (!closed) reasons.push(`blocking plan constraint ${c.id} is ${c.status}: ${c.statement}`);
    }
  }

  const requested = baseline.target && baseline.target.platform ? baseline.target.platform.version : null;
  if (requested && last && last.declared) {
    const declared = last.declared.platform
      || (baseline.reference_packs && baseline.reference_packs[0] && last.declared.parent
        ? { version: last.declared.parent.version } : null);
    if (declared && declared.version !== requested) {
      reasons.push(`the final declared platform version is ${declared.version}, but ${requested} was requested — reconcile it and rebuild`);
    }
  } else if (!requested && !legacy) {
    notes.push('no exact target version was recorded (detect-baseline.js --to-version), so the final version could not be checked against the request');
  }

  // Clean application: every file about to be overwritten must still be what the sandbox was
  // copied from. A project edited since then would have those edits silently replaced.
  const projectDir = path.resolve(baseline.project.dir);
  const drifted = [];
  if (changes && isSandboxRepo(p.workspace) && fs.existsSync(projectDir)) {
    const git = (...a) => run('git', ['-C', p.workspace, ...a]);
    for (const c of changes) {
      const file = c.file.split(' -> ')[0];
      const original = git('show', `${meta.baseline_commit}:${file}`);
      const target = path.join(projectDir, file);
      const exists = fs.existsSync(target);
      if (c.state.startsWith('A')) {
        if (exists) drifted.push(`${file} (now exists in the project)`);
        continue;
      }
      if (!exists) { drifted.push(`${file} (missing from the project)`); continue; }
      if (original.status === 0 && original.stdout.replace(/\r\n/g, '\n') !== fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n')) {
        drifted.push(`${file} (edited in the project since the sandbox was copied)`);
      }
    }
  }
  if (drifted.length) reasons.push(`the project has drifted since the sandbox was copied: ${drifted.join(', ')}`);

  return { eligible: reasons.length === 0, reasons, notes, legacy, state: state.state };
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--slug' || a === '-s') args.slug = argv[++i];
    else if (a === '--to-project') args.toProject = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Version Migration — Apply

  node scripts/apply-migration.js --slug <slug> [--to-project]

Options:
  --slug, -s      Session name
  --to-project    Actually copy the migrated files into the project directory.
                  Refused unless the last recorded round passed.
  --help, -h      Show this message`);
}

function listWorkspaceChanges(workspace, baselineCommit) {
  if (!isSandboxRepo(workspace)) return [];
  const git = (...a) => run('git', ['-C', workspace, ...a]);
  git('add', '-A');
  const status = git('diff', '--cached', '--name-status', baselineCommit).stdout.trim();
  if (!status) return [];
  return status.split(/\r?\n/).map((line) => {
    const [state, ...rest] = line.split(/\t/);
    return { state: state.trim(), file: rest.join('\t').split(path.sep).join('/') };
  });
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();
  if (!args.slug) {
    console.error('--slug is required.');
    process.exitCode = 1;
    return;
  }

  const paths = sessionPaths(args.slug);
  const baseline = readJson(paths.baseline);
  const meta = readJson(paths.workspaceMeta);
  if (!baseline || !meta) {
    console.error(`Session "${args.slug}" is not set up.`);
    process.exitCode = 1;
    return;
  }
  const rounds = listRounds(args.slug);
  const last = rounds[rounds.length - 1];
  if (!last) {
    console.error('No build rounds recorded — nothing has been verified.');
    process.exitCode = 1;
    return;
  }

  const projectDir = path.resolve(baseline.project.dir);
  const changes = listWorkspaceChanges(paths.workspace, meta.baseline_commit);
  const verdict = eligibility(args.slug, changes);

  console.log(`\nMigration "${args.slug}"`);
  console.log(`  Last round     ${last.round} — ${last.outcome} on JDK ${last.jdk.major}`);
  console.log(`  Session state  ${verdict.state}${verdict.legacy ? ' (pre-v2 session: held to the checks its own files can answer)' : ''}`);
  console.log(`  Project        ${rel(projectDir)}`);
  console.log(`  Sandbox        ${rel(paths.workspace)}${isSandboxRepo(paths.workspace) ? '' : ' — MISSING (no sandbox repository)'}`);
  console.log(`  Files changed  ${changes.length}`);
  for (const c of changes) console.log(`    ${c.state.padEnd(3)} ${c.file}`);
  console.log(`\n  Eligible to apply: ${verdict.eligible ? 'YES' : 'NO'}`);
  for (const r of verdict.reasons) console.log(`    ✗ ${r}`);
  for (const n of verdict.notes) console.log(`    · ${n}`);

  if (!args.toProject) {
    console.log('\n  Dry run — nothing written. Pass --to-project to apply these changes to the project.');
    console.log(`  The same changes are also in docs/agent_output/04-remediation/migration_${args.slug}.diff once the report is rendered.\n`);
    return;
  }

  if (!isSandboxRepo(paths.workspace)) {
    console.error('\n  REFUSED: the sandbox workspace is missing, so there is nothing verified to copy.\n');
    process.exitCode = 1;
    return;
  }
  if (!verdict.eligible) {
    console.error('\n  REFUSED: this migration is not eligible to be applied (reasons above).');
    console.error('  A migration is only applied once its build is green, its behaviour was compared, nothing');
    console.error('  blocks it and the project has not changed underneath it. Fix that in the sandbox and re-run.\n');
    process.exitCode = 1;
    return;
  }

  let applied = 0;
  let removed = 0;
  for (const change of changes) {
    const source = path.join(paths.workspace, change.file);
    const target = path.join(projectDir, change.file);
    if (change.state.startsWith('D')) {
      if (fs.existsSync(target)) { fs.rmSync(target); removed += 1; }
      continue;
    }
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(source, target);
    applied += 1;
  }

  console.log(`\n  Applied: ${applied} file(s) written, ${removed} removed, in ${rel(projectDir)}`);
  console.log('  The sandbox is left intact so the run stays auditable.');
  console.log(`  Review with: git -C "${projectDir}" diff   (if the project is version-controlled)\n`);
}

if (require.main === module) require('./lib/summary').runAndFinalize(main, 'apply-migration.js');

module.exports = { eligibility, listWorkspaceChanges, PACKAGING_INTENTS };
