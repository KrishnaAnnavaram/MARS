#!/usr/bin/env node
/**
 * Version Migration — optional last step: put the migrated code into the real project.
 *
 * Everything before this point happens in a sandbox. This is the one script that can write
 * to the project directory, it never runs as part of the normal flow, and it refuses unless:
 *
 *   - the last recorded round is green, and
 *   - --to-project is passed explicitly.
 *
 * It copies the changed files out of the sandbox rather than applying a patch, so it cannot fail
 * halfway on a context mismatch: the sandbox holds the exact tree that was built and probed.
 * Without --to-project it prints what would change and writes nothing.
 *
 * Usage:
 *   node scripts/apply-migration.js --slug <slug>                # dry run, prints the plan
 *   node scripts/apply-migration.js --slug <slug> --to-project   # actually writes the project
 */
const fs = require('fs');
const path = require('path');
const { sessionPaths, readJson, listRounds, rel, run } = require('./lib/migration');

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

  console.log(`\nMigration "${args.slug}"`);
  console.log(`  Last round     ${last.round} — ${last.outcome} on JDK ${last.jdk.major}`);
  console.log(`  Project        ${rel(projectDir)}`);
  console.log(`  Sandbox        ${rel(paths.workspace)}`);
  console.log(`  Files changed  ${changes.length}`);
  for (const c of changes) console.log(`    ${c.state.padEnd(3)} ${c.file}`);

  if (!args.toProject) {
    console.log('\n  Dry run — nothing written. Pass --to-project to apply these changes to the project.');
    console.log(`  The same changes are also in docs/agent_output/04-remediation/migration_${args.slug}.diff once the report is rendered.\n`);
    return;
  }

  if (last.outcome !== 'passed') {
    console.error(`\n  REFUSED: the last round ended "${last.outcome}", not "passed".`);
    console.error('  A migration is only applied to the project once its build is green. Fix the');
    console.error('  remaining errors in the sandbox and run another round.\n');
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

main();
