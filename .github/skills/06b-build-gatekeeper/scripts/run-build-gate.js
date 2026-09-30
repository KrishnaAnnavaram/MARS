#!/usr/bin/env node
/**
 * Build Gatekeeper — Deterministic Build + Dependency Gate
 *
 * Everything in this script is mechanical: apply the fix diff inside a throwaway git worktree,
 * run `mvn verify` (stronger than Fixer's own `compile` pre-gate) for the affected module(s), and
 * diff `mvn dependency:tree` output captured before and after the patch to flag any dependency
 * version change the fix plan didn't call for. No agent writes a judgment file anywhere in this
 * skill — render-build-report.js turns this script's own JSON straight into the report.
 *
 * Usage:
 *   node scripts/run-build-gate.js --issue ISSUE-001 [--keep]
 *   node scripts/run-build-gate.js --all [--keep]
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, WORK_DIR, WORKTREES_DIR,
  rel, resolveFix, listStep2Fixes, run, removeWorktreeIfPresent, wrapperFor, runWrapper,
  changedFilesFromPatch, modulesFromFiles, diffDependencyTrees, tail, resultPathFor,
} = require('./lib/gate');

function parseArgs(argv) {
  const args = { keep: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--issue' || a === '-i') args.issue = argv[++i];
    else if (a === '--all' || a === '-a') args.all = true;
    else if (a === '--keep') args.keep = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Build Gatekeeper — Deterministic Build + Dependency Gate

  node scripts/run-build-gate.js --issue <ISSUE-ID> [--keep]
  node scripts/run-build-gate.js --all [--keep]

Runs against any fix with a drafted diff (Compiled or Compile Failed) — this gate compiles the
module itself, independently of Fixer's own compile check. Refuses only a "Refused" fix.`);
}

function runOne(fix, args) {
  if (fix.status === 'Refused') {
    return { id: fix.id, refused: true, reason: 'Fix report Status is "Refused" — Fixer never drafted a diff to build.' };
  }
  const patchAbs = path.join(REPO_ROOT, fix.patchFile || '');
  if (!fix.patchFile || !fs.existsSync(patchAbs)) throw new Error(`Fix patch not found (looked for ${fix.patchFile || 'none'}).`);

  const worktreeDir = path.join(WORKTREES_DIR, fix.id);
  const record = { generatedAt: new Date().toISOString(), id: fix.id, worktree: rel(worktreeDir) };

  fs.mkdirSync(WORKTREES_DIR, { recursive: true });
  removeWorktreeIfPresent(worktreeDir);

  try {
    const add = run('git', ['worktree', 'add', '--detach', worktreeDir, 'HEAD']);
    if (add.status !== 0) { record.passed = false; record.stage = 'worktree-create'; record.output = tail(add.stdout + add.stderr); return record; }

    const patchText = fs.readFileSync(patchAbs, 'utf8');
    const changedFiles = changedFilesFromPatch(patchText);
    const modules = modulesFromFiles(changedFiles);
    record.changedFiles = changedFiles;
    record.modules = modules;
    if (!modules.length) { record.passed = false; record.stage = 'module-detection'; record.output = `No known module resolved from: ${changedFiles.join(', ')}`; return record; }

    // Baseline dependency tree, before the patch is applied.
    record.dependencyChecks = [];
    const baselineTrees = {};
    for (const module of modules) {
      const moduleDir = path.join(worktreeDir, module);
      const wrapper = wrapperFor(moduleDir);
      const treeBefore = runWrapper(wrapper, ['-q', 'dependency:tree'], { cwd: moduleDir });
      baselineTrees[module] = treeBefore.stdout;
    }

    const check = run('git', ['apply', '--check', patchAbs], { cwd: worktreeDir });
    if (check.status !== 0) { record.passed = false; record.stage = 'patch-apply-check'; record.output = tail(check.stdout + check.stderr); return record; }
    const apply = run('git', ['apply', patchAbs], { cwd: worktreeDir });
    if (apply.status !== 0) { record.passed = false; record.stage = 'patch-apply'; record.output = tail(apply.stdout + apply.stderr); return record; }

    record.steps = [];
    let allPassed = true;
    for (const module of modules) {
      const moduleDir = path.join(worktreeDir, module);
      const wrapper = wrapperFor(moduleDir);

      const verify = runWrapper(wrapper, ['-q', 'verify', '-DskipITs'], { cwd: moduleDir });
      record.steps.push({
        module, command: 'mvnw verify', exitCode: verify.status,
        output: tail(verify.stdout + verify.stderr + (verify.error ? `\n[spawn error] ${verify.error}` : '')),
      });
      if (verify.status !== 0) allPassed = false;

      const treeAfter = runWrapper(wrapper, ['-q', 'dependency:tree'], { cwd: moduleDir });
      const depDiff = diffDependencyTrees(baselineTrees[module], treeAfter.stdout);
      record.dependencyChecks.push({ module, added: depDiff.added, removed: depDiff.removed });
    }

    record.passed = allPassed;
    record.stage = 'complete';
    return record;
  } finally {
    if (!args.keep) removeWorktreeIfPresent(worktreeDir);
    else record.kept = rel(worktreeDir);
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  let targets;
  if (args.all) {
    targets = listStep2Fixes();
    if (!targets.length) { console.log('No fixes with a drafted diff found. Nothing to build.'); return; }
    console.log(`Build Gatekeeper — running ${targets.length} gate(s): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    if (!args.issue) throw new Error('Missing --issue or --all.');
    targets = [resolveFix(args.issue)];
  }

  const failed = [];
  for (const fix of targets) {
    const record = runOne(fix, args);
    fs.mkdirSync(WORK_DIR, { recursive: true });
    fs.writeFileSync(resultPathFor(fix.id), JSON.stringify(record, null, 2));
    if (record.refused) {
      console.log(`\n${record.id} — REFUSED: ${record.reason}`);
    } else {
      console.log(`\n${record.id} — ${record.passed ? 'PASS' : 'FAIL'} (${record.stage})`);
      const depChanges = (record.dependencyChecks || []).filter((d) => d.added.length || d.removed.length);
      if (depChanges.length) console.log(`  dependency changes detected in: ${depChanges.map((d) => d.module).join(', ')}`);
      if (record.kept) console.log(`  worktree kept at ${record.kept}`);
    }
    if (record.refused || !record.passed) failed.push(record.id);
  }
  console.log(`\nNext: node scripts/render-build-report.js --all`);
  if (failed.length) process.exitCode = 1;
}

try { main(); } catch (err) { console.error('Build gate failed:', err.message); process.exit(1); }
