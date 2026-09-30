#!/usr/bin/env node
/**
 * Version Migration — Step 2: Sandbox workspace.
 *
 * A migration is not a one-shot patch: it is many rounds of edit-build-read-the-errors, and
 * the intermediate states do not compile. None of that belongs in the user's project
 * directory. This script copies the project into
 * `.github/.pipeline-context/version-migration/<slug>/workspace/` and commits that copy to a
 * throwaway git repository of its own. Every later edit and every build happens there.
 *
 * A fresh `git init` (rather than a `git worktree` off the real repo) is deliberate: it works
 * whether or not the project is version-controlled, never writes to the project's own .git,
 * and still gives an exact cumulative patch at the end via `git diff`.
 *
 * The baseline commit is also the first checkpoint: any later transformation takes its own
 * checkpoint (refs/checkpoints/*) before it runs, and the session state records that the sandbox
 * is prepared, so a resumed session can see exactly where it stands.
 *
 * Usage:
 *   node scripts/prepare-workspace.js --slug <slug>
 *   node scripts/prepare-workspace.js --slug <slug> --force     # discard and recreate
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const {
  DATA_DIR, sessionPaths, readJson, writeJson, rel, run, recordState, inferState, listRounds,
} = require('./lib/migration');

const BUILD_OUTPUT_PATTERNS = ['target/', 'build/', 'out/', '.gradle/', 'node_modules/', '*.class'];
const EXCLUDED = ['.git', 'target', 'build', 'out', 'node_modules', '.idea', '.gradle', '.mvn/wrapper/maven-wrapper.jar'];

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--slug' || a === '-s') args.slug = argv[++i];
    else if (a === '--force' || a === '-f') args.force = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Version Migration — Sandbox workspace

  node scripts/prepare-workspace.js --slug <slug> [--force]

Options:
  --slug, -s   Session name used by detect-baseline.js
  --force, -f  Delete an existing workspace and recreate it from the project. Destroys
               every migration edit made in that workspace so far.
  --help, -h   Show this message`);
}

function copyTree(from, to) {
  fs.mkdirSync(to, { recursive: true });
  let copied = 0;
  const skipped = [];
  // A project that contains the pipeline's own data dir (the repo root, say) must not copy the
  // sessions — including this very workspace — into itself.
  const norm = (p) => (process.platform === 'win32' ? path.resolve(p).toLowerCase() : path.resolve(p));
  const dataDir = norm(DATA_DIR);
  const walk = (src, dest, depth) => {
    for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
      const relative = path.relative(from, path.join(src, entry.name)).split(path.sep).join('/');
      if (EXCLUDED.includes(entry.name) || EXCLUDED.includes(relative)) continue;
      if (norm(path.join(src, entry.name)) === dataDir) continue;
      const srcPath = path.join(src, entry.name);
      const destPath = path.join(dest, entry.name);
      if (entry.isSymbolicLink()) continue;
      if (entry.isDirectory()) {
        fs.mkdirSync(destPath, { recursive: true });
        walk(srcPath, destPath, depth + 1);
      } else if (entry.isFile()) {
        try {
          fs.copyFileSync(srcPath, destPath);
          copied += 1;
        } catch (error) {
          // A file held open by something else (a running app's database file is the usual
          // case) is skipped rather than fatal — it is runtime state, not source, and the
          // migration must still be able to start. Every skip is recorded and reported.
          skipped.push({ file: relative, reason: error.code || error.message });
        }
      }
    }
  };
  walk(from, to, 0);
  return { copied, skipped };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();
  if (!args.slug) {
    console.error('--slug is required. Run detect-baseline.js first; it prints the slug it used.');
    process.exitCode = 1;
    return;
  }

  const paths = sessionPaths(args.slug);
  const baseline = readJson(paths.baseline);
  if (!baseline) {
    console.error(`No baseline for "${args.slug}" at ${rel(paths.baseline)} — run detect-baseline.js first.`);
    process.exitCode = 1;
    return;
  }

  const projectDir = path.resolve(baseline.project.dir);
  if (!fs.existsSync(projectDir)) {
    console.error(`Project directory recorded in the baseline no longer exists: ${projectDir}`);
    process.exitCode = 1;
    return;
  }

  if (fs.existsSync(paths.workspace)) {
    if (!args.force) {
      const meta = readJson(paths.workspaceMeta, {});
      const state = inferState(args.slug);
      console.log(`Workspace already exists: ${rel(paths.workspace)}`);
      console.log(`  created ${meta.created_at || 'unknown'} from ${meta.project_dir || 'unknown'}`);
      console.log(`  session state ${state.state}${state.legacy ? ' (pre-v2 session: state inferred from its files)' : ''}`);
      console.log(`  reached       ${state.reached.join(' → ') || 'nothing yet'}`);
      console.log('  Keeping it — migration edits live here. Pass --force to discard and start over.');
      return;
    }
    const recorded = listRounds(args.slug).length;
    if (recorded) {
      console.log(`  ! --force: ${recorded} round record(s) refer to the workspace being discarded; they stay on disk as history,`);
      console.log('    but round 0 must be re-run before any migration change — the new sandbox has a new baseline commit.');
    }
    fs.rmSync(paths.workspace, { recursive: true, force: true });
  }

  const { copied, skipped } = copyTree(projectDir, paths.workspace);

  const git = (...a) => run('git', ['-C', paths.workspace, ...a]);
  const init = git('init', '-q', '-b', 'migration-baseline');
  // A failed init must stop here: every later `git add -A` would otherwise fall through to the
  // repository enclosing the session directory.
  if (init.status !== 0 || !fs.existsSync(path.join(paths.workspace, '.git'))) {
    console.error(`git init failed in the workspace: ${init.error || init.stderr}`);
    process.exitCode = 1;
    return;
  }
  git('config', 'user.email', 'version-migration@pipeline.local');
  git('config', 'user.name', 'Version Migration Skill');
  git('config', 'core.autocrlf', 'false');
  // Build output must never reach the snapshot or the exported patch. The project's own
  // .gitignore usually covers it; this adds the same patterns for projects that have none,
  // without touching any file in the project itself.
  fs.writeFileSync(
    path.join(paths.workspace, '.git', 'info', 'exclude'),
    BUILD_OUTPUT_PATTERNS.map((pattern) => `${pattern}${os.EOL}`).join(''),
  );
  git('add', '-A');
  const commit = git('commit', '-q', '-m', `baseline: ${baseline.project.name} before ${args.slug}`);
  if (commit.status !== 0) {
    console.error(`git commit failed in the workspace: ${commit.stderr || commit.stdout}`);
    process.exitCode = 1;
    return;
  }
  const head = git('rev-parse', 'HEAD').stdout.trim();
  git('update-ref', 'refs/checkpoints/baseline', head);

  const meta = writeJson(paths.workspaceMeta, {
    slug: args.slug,
    created_at: new Date().toISOString(),
    project_dir: projectDir.split(path.sep).join('/'),
    workspace: paths.workspace.split(path.sep).join('/'),
    baseline_commit: head,
    files_copied: copied,
    files_skipped: skipped,
    excluded: EXCLUDED,
    mode: 'copy+git-init',
    // v2: the baseline commit doubles as the first checkpoint; transformations add their own
    // under refs/checkpoints/ so any of them can be undone exactly.
    checkpoints: { baseline: 'refs/checkpoints/baseline' },
  });
  recordState(args.slug, 'WORKSPACE_PREPARED', 'prepare-workspace.js', `baseline commit ${head.slice(0, 10)}`);

  console.log(`\nWorkspace ready`);
  console.log(`  ${'-'.repeat(60)}`);
  console.log(`  Source project   ${rel(projectDir)}  (never modified by this skill)`);
  console.log(`  Sandbox          ${rel(paths.workspace)}`);
  console.log(`  Files copied     ${copied} (excluding ${EXCLUDED.join(', ')})`);
  if (skipped.length) {
    console.log(`  Files skipped    ${skipped.length} — in use by another process:`);
    for (const s of skipped) console.log(`                     ${s.file} (${s.reason})`);
    console.log('                   Stop anything using them if the build needs them.');
  }
  console.log(`  Baseline commit  ${head.slice(0, 10)}`);
  console.log(`  Metadata         ${rel(meta)}`);
  console.log(`\n  Make every migration edit inside the sandbox path above — and none before round 0 is recorded.`);
  console.log(`  Next: node scripts/run-migration-build.js --slug ${args.slug} --baseline --jdk ${baseline.language.declared || '17'}\n`);
}

if (require.main === module) main();

module.exports = { copyTree, EXCLUDED };
