#!/usr/bin/env node
/**
 * QA Runner — Deterministic Test Gate
 *
 * The agentic part of this skill stops at drafting a test (see SKILL.md). This script is the
 * deterministic part: it applies the fix diff AND the agent's new-test diff together inside a
 * throwaway git worktree, runs the new test (and, if named, one existing test scoped to the
 * change), and records the real exit code. Nothing here is open to interpretation — a FAIL is a
 * FAIL, a test needing a live dependency this sandbox doesn't have is SKIPPED, never silently
 * passed. The agent that reads this result does not get to override it.
 *
 * Usage:
 *   node scripts/run-qa-gate.js --issue ISSUE-001 [--existing-test SomeExistingTestClass] [--keep]
 *   node scripts/run-qa-gate.js --all [--keep]   # every Compiled fix with a drafted test ready
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, WORK_DIR, WORKTREES_DIR,
  rel, resolveFix, listStep2Fixes, run, removeWorktreeIfPresent, wrapperFor, runWrapper,
  changedFilesFromPatch, modulesFromFiles, tail,
  testPlanPathFor, testDiffPathFor, resultPathFor,
} = require('./lib/qa');

const LIVE_DEPENDENCY_MARKERS = ['@DataMongoTest', '@SpringBootTest', '@EmbeddedKafka', 'Cucumber', '@AutoConfigureMockMvc'];

function parseArgs(argv) {
  const args = { keep: false };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--issue' || a === '-i') args.issue = argv[++i];
    else if (a === '--all' || a === '-a') args.all = true;
    else if (a === '--existing-test' || a === '-e') args.existingTest = argv[++i];
    else if (a === '--keep') args.keep = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`QA Runner — Deterministic Test Gate

  node scripts/run-qa-gate.js --issue <ISSUE-ID> [--existing-test <ClassName>] [--keep]
  node scripts/run-qa-gate.js --all [--keep]

Requires .github/.pipeline-context/qa/<id>.test-plan.json and <id>.new-test.diff to already exist (the agent's job).
Runs against any fix with a drafted diff (Compiled or Compile Failed) — this gate compiles the
module itself, independently of Fixer's own compile check. Refuses only a "Refused" fix.`);
}

function findTestFileInWorktree(worktreeDir, module, className) {
  const testRoot = path.join(worktreeDir, module, 'src', 'test', 'java');
  if (!fs.existsSync(testRoot)) return null;
  const stack = [testRoot];
  while (stack.length) {
    const dir = stack.pop();
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) stack.push(full);
      else if (entry.name === `${className}.java`) return full;
    }
  }
  return null;
}

function runOne(fix, args) {
  if (fix.status === 'Refused') {
    return { id: fix.id, refused: true, reason: 'Fix report Status is "Refused" — Fixer never drafted a diff to gate.' };
  }
  const planFile = testPlanPathFor(fix.id);
  const testDiffFile = testDiffPathFor(fix.id);
  if (!fs.existsSync(planFile) || !fs.existsSync(testDiffFile)) {
    throw new Error(`Missing ${rel(planFile)} and/or ${rel(testDiffFile)}. Draft the regression test and its plan first (the agent's job), then re-run.`);
  }
  const testPlan = JSON.parse(fs.readFileSync(planFile, 'utf8'));
  const className = path.basename(testPlan.test_file || '', '.java');
  if (!className) throw new Error(`${rel(planFile)} has no usable "test_file" field.`);

  const fixPatchAbs = path.join(REPO_ROOT, fix.patchFile || '');
  if (!fix.patchFile || !fs.existsSync(fixPatchAbs)) throw new Error(`Fix patch not found (looked for ${fix.patchFile || 'none'}).`);

  const worktreeDir = path.join(WORKTREES_DIR, fix.id);
  const record = { generatedAt: new Date().toISOString(), id: fix.id, testFile: testPlan.test_file, className, worktree: rel(worktreeDir) };

  fs.mkdirSync(WORKTREES_DIR, { recursive: true });
  removeWorktreeIfPresent(worktreeDir);

  try {
    const add = run('git', ['worktree', 'add', '--detach', worktreeDir, 'HEAD']);
    if (add.status !== 0) { record.passed = false; record.stage = 'worktree-create'; record.output = tail(add.stdout + add.stderr); return record; }

    for (const [label, patchAbs] of [['fix', fixPatchAbs], ['test', path.resolve(testDiffFile)]]) {
      const check = run('git', ['apply', '--check', patchAbs], { cwd: worktreeDir });
      if (check.status !== 0) { record.passed = false; record.stage = `${label}-apply-check`; record.output = tail(check.stdout + check.stderr); return record; }
      const apply = run('git', ['apply', patchAbs], { cwd: worktreeDir });
      if (apply.status !== 0) { record.passed = false; record.stage = `${label}-apply`; record.output = tail(apply.stdout + apply.stderr); return record; }
    }

    const fixText = fs.readFileSync(fixPatchAbs, 'utf8');
    const testText = fs.readFileSync(testDiffFile, 'utf8');
    const changedFiles = [...new Set([...changedFilesFromPatch(fixText), ...changedFilesFromPatch(testText)])];
    const modules = modulesFromFiles(changedFiles);
    record.changedFiles = changedFiles;
    record.modules = modules;
    if (!modules.length) { record.passed = false; record.stage = 'module-detection'; record.output = `No known module resolved from: ${changedFiles.join(', ')}`; return record; }

    record.steps = [];
    let allDecided = true; // false only if a real FAIL occurs; SKIPPED does not fail the gate
    for (const module of modules) {
      const moduleDir = path.join(worktreeDir, module);
      const wrapper = wrapperFor(moduleDir);
      const newTest = runWrapper(wrapper, ['-q', 'test', `-Dtest=${className}`], { cwd: moduleDir });
      record.steps.push({
        module, test: className, status: newTest.status === 0 ? 'PASS' : 'FAIL', exitCode: newTest.status,
        output: tail(newTest.stdout + newTest.stderr + (newTest.error ? `\n[spawn error] ${newTest.error}` : '')),
      });
      if (newTest.status !== 0) allDecided = false;

      if (args.existingTest) {
        const existingFile = findTestFileInWorktree(worktreeDir, module, args.existingTest);
        if (!existingFile) {
          record.steps.push({ module, test: args.existingTest, status: 'SKIPPED', exitCode: null, output: 'Test class not found in this module.' });
        } else {
          const source = fs.readFileSync(existingFile, 'utf8');
          const marker = LIVE_DEPENDENCY_MARKERS.find((m) => source.includes(m));
          if (marker) {
            record.steps.push({ module, test: args.existingTest, status: 'SKIPPED', exitCode: null, output: `Uses ${marker}, which needs a live dependency unavailable in this isolated sandbox (no embedded MongoDB). Not run — reported SKIPPED, not a fabricated pass.` });
          } else {
            const existing = runWrapper(wrapper, ['-q', 'test', `-Dtest=${args.existingTest}`], { cwd: moduleDir });
            record.steps.push({
              module, test: args.existingTest, status: existing.status === 0 ? 'PASS' : 'FAIL', exitCode: existing.status,
              output: tail(existing.stdout + existing.stderr),
            });
            if (existing.status !== 0) allDecided = false;
          }
        }
      }
    }

    record.passed = allDecided;
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
    targets = listStep2Fixes().filter((f) => fs.existsSync(testPlanPathFor(f.id)) && fs.existsSync(testDiffPathFor(f.id)));
    if (!targets.length) { console.log('No fixes with a drafted test ready. Nothing to run.'); return; }
    console.log(`QA Runner — running ${targets.length} gate(s): ${targets.map((t) => t.id).join(', ')}`);
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
      (record.steps || []).forEach((s) => console.log(`  ${s.test.padEnd(30)} ${s.status}`));
      if (record.kept) console.log(`  worktree kept at ${record.kept}`);
    }
    if (record.refused || !record.passed) failed.push(record.id);
  }
  console.log(`\nNext: node scripts/render-qa-report.js --all`);
  if (failed.length) process.exitCode = 1;
}

try { main(); } catch (err) { console.error('QA gate failed:', err.message); process.exit(1); }
