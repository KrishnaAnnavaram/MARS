#!/usr/bin/env node
/**
 * Fixer — Isolated Patch Verification
 *
 * The Fixer never edits the real working tree. To still prove a drafted patch actually
 * compiles (and, where requested, passes a specific test), this script:
 *
 *   1. Requires the fix plan to be Status: Approved — refuses otherwise.
 *   2. Requires the agent to have already written .github/.pipeline-context/fixer/<id>.patch.diff.
 *   3. Creates a throwaway `git worktree` checked out from HEAD.
 *   4. Applies the patch inside that worktree only.
 *   5. Runs the affected Maven module's wrapper (`compile`, plus `test -Dtest=<Class>` if
 *      --test was given) inside the worktree.
 *   6. Always removes the worktree afterwards (unless --keep) — the main working tree and
 *      `git status` are never touched, at any point, by this script.
 *
 * Writes:
 *   .github/.pipeline-context/fixer/<id>.verification.json  — machine-readable, consumed by render-fix-report.js
 *   .github/.pipeline-context/fixer/<id>.verification.md    — human-readable summary
 *
 * This script is deterministic. It never judges whether the fix is *right* — only whether the
 * patch applies and the result builds (and, if asked, passes the named test).
 *
 * Usage:
 *   node scripts/verify-patch.js --issue ISSUE-001 [--test EmployeeSearchRepositoryTest] [--keep]
 *   node scripts/verify-patch.js --all [--keep]   # every Approved plan with a patch already drafted
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const {
  REPO_ROOT, WORK_DIR, WORKTREES_DIR,
  rel, resolveFixPlan, listApprovedFixPlans,
  patchPathFor, worktreePathFor, verificationJsonPathFor, verificationMdPathFor,
} = require('./lib/fixplans');

const KNOWN_MODULES = [
  'configuaration-server', 'discovery-service', 'department-service',
  'employee-service', 'report-service', 'sheduler-service',
];
const TAIL_CHARS = 4000;

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = { keep: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--issue' || arg === '-i') args.issue = argv[++i];
    else if (arg === '--all' || arg === '-a') args.all = true;
    else if (arg === '--test' || arg === '-t') args.test = argv[++i];
    else if (arg === '--keep') args.keep = true;
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Fixer — Isolated Patch Verification

  node scripts/verify-patch.js --issue <ISSUE-ID> [--test <TestClassName>] [--keep]
  node scripts/verify-patch.js --all [--keep]

Options:
  --issue, -i   A single issue id, e.g. ISSUE-001. The fix plan must be Status: Approved.
  --all, -a     Verify every Approved plan that already has a patch drafted
  --test, -t    Also run this single test class after a successful compile
  --keep        Do not remove the worktree afterwards — for inspecting a failure
  --help, -h    Show this message

Refuses to run on any plan whose fix plan Status is not "Approved".`);
}

// ---------------------------------------------------------------------------
// git / maven helpers
// ---------------------------------------------------------------------------

function run(cmd, args, options) {
  const result = spawnSync(cmd, args, {
    cwd: REPO_ROOT, encoding: 'utf8', shell: false, ...options,
  });
  return {
    status: result.status,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
    error: result.error ? result.error.message : null,
  };
}

function tail(text, n = TAIL_CHARS) {
  if (!text) return '';
  return text.length > n ? `…(truncated)…\n${text.slice(-n)}` : text;
}

function removeWorktreeIfPresent(worktreeDir) {
  if (!fs.existsSync(worktreeDir)) return;
  const result = run('git', ['worktree', 'remove', '--force', worktreeDir]);
  if (result.status !== 0) {
    // Best effort: prune stale metadata, then fall back to a plain directory delete so a
    // half-broken worktree never blocks the next run.
    run('git', ['worktree', 'prune']);
    fs.rmSync(worktreeDir, { recursive: true, force: true });
  }
}

/** Tolerant of both "a/path" / "b/path" (git diff) and bare paths. */
function changedFilesFromPatch(patchText) {
  const files = new Set();
  const re = /^(?:\+\+\+|---)\s+(?:(?:a|b)\/)?(.+?)\s*$/gm;
  let m;
  while ((m = re.exec(patchText))) {
    const p = m[1].trim();
    if (p && p !== '/dev/null') files.add(p.replace(/\\/g, '/'));
  }
  return [...files];
}

function modulesFromFiles(files) {
  const modules = new Set();
  for (const f of files) {
    const top = f.split('/')[0];
    if (KNOWN_MODULES.includes(top)) modules.add(top);
  }
  return [...modules];
}

function wrapperFor(moduleDir) {
  return process.platform === 'win32'
    ? path.join(moduleDir, 'mvnw.cmd')
    : path.join(moduleDir, 'mvnw');
}

/**
 * Runs the Maven wrapper without shell:true, which string-concatenates args unsafely
 * (the behaviour Node's DEP0190 warns about). On Windows a .cmd cannot be exec'd
 * directly, so it goes through `cmd.exe /d /s /c`.
 *
 * `/s` strips the first and last quote of the command string, so a single quoted
 * path is unquoted again before cmd parses it — which splits any repo path
 * containing a space (e.g. "D:\Office Research\..."). The fix is to quote each
 * argument and wrap the whole command in one more pair of quotes, passing it
 * verbatim so Node does not re-quote on top.
 */
function runWrapper(wrapper, mvnArgs, options) {
  if (process.platform === 'win32') {
    const command = [wrapper, ...mvnArgs].map((a) => `"${a}"`).join(' ');
    return run('cmd.exe', ['/d', '/s', '/c', `"${command}"`], { ...options, windowsVerbatimArguments: true });
  }
  return run(wrapper, mvnArgs, options);
}

// ---------------------------------------------------------------------------
// Verification
// ---------------------------------------------------------------------------

function verifyOne(plan, args) {
  if (plan.status !== 'Approved') {
    return {
      id: plan.id,
      passed: false,
      refused: true,
      reason: `Fix plan Status is "${plan.status}", not "Approved". The Fixer will not verify or apply an unapproved plan — see ${plan.relativePlanFile}.`,
    };
  }

  const patchFile = patchPathFor(plan.id);
  if (!fs.existsSync(patchFile)) {
    throw new Error(`No patch drafted at ${rel(patchFile)}. Write the diff first (the agent's job), then re-run.`);
  }
  const patchText = fs.readFileSync(patchFile, 'utf8');
  if (!patchText.trim()) {
    throw new Error(`Patch file ${rel(patchFile)} is empty.`);
  }

  const worktreeDir = worktreePathFor(plan.id);
  const record = {
    generatedAt: new Date().toISOString(),
    id: plan.id,
    patchFile: rel(patchFile),
    worktree: rel(worktreeDir),
    testRequested: args.test || null,
  };

  fs.mkdirSync(WORKTREES_DIR, { recursive: true });
  removeWorktreeIfPresent(worktreeDir); // self-heal a stale worktree from a crashed prior run

  try {
    const add = run('git', ['worktree', 'add', '--detach', worktreeDir, 'HEAD']);
    if (add.status !== 0) {
      record.passed = false;
      record.stage = 'worktree-create';
      record.output = tail(add.stdout + add.stderr);
      return record;
    }

    const check = run('git', ['apply', '--check', path.resolve(patchFile)], { cwd: worktreeDir });
    if (check.status !== 0) {
      record.passed = false;
      record.stage = 'patch-apply-check';
      record.output = tail(check.stdout + check.stderr);
      return record;
    }
    const apply = run('git', ['apply', path.resolve(patchFile)], { cwd: worktreeDir });
    if (apply.status !== 0) {
      record.passed = false;
      record.stage = 'patch-apply';
      record.output = tail(apply.stdout + apply.stderr);
      return record;
    }

    const changedFiles = changedFilesFromPatch(patchText);
    const modules = modulesFromFiles(changedFiles);
    record.changedFiles = changedFiles;
    record.modules = modules;

    if (!modules.length) {
      record.passed = false;
      record.stage = 'module-detection';
      record.output = `Could not resolve a known Maven module from the patch's changed paths: ${changedFiles.join(', ') || '(none)'}.`;
      return record;
    }

    record.steps = [];
    let allPassed = true;
    for (const module of modules) {
      const moduleDir = path.join(worktreeDir, module);
      const wrapper = wrapperFor(moduleDir);
      const compile = runWrapper(wrapper, ['-q', 'compile'], { cwd: moduleDir });
      record.steps.push({
        module,
        command: `mvnw compile (${module})`,
        exitCode: compile.status,
        output: tail(compile.stdout + compile.stderr + (compile.error ? `\n[spawn error] ${compile.error}` : '')),
      });
      if (compile.status !== 0) { allPassed = false; continue; }

      if (args.test) {
        const test = runWrapper(wrapper, ['-q', 'test', `-Dtest=${args.test}`], { cwd: moduleDir });
        record.steps.push({
          module,
          command: `mvnw test -Dtest=${args.test} (${module})`,
          exitCode: test.status,
          output: tail(test.stdout + test.stderr + (test.error ? `\n[spawn error] ${test.error}` : '')),
        });
        if (test.status !== 0) allPassed = false;
      }
    }

    record.passed = allPassed;
    record.stage = 'complete';
    record.level = args.test ? 'compile + targeted test' : 'compile only';
    return record;
  } finally {
    if (!args.keep) removeWorktreeIfPresent(worktreeDir);
    else record.kept = rel(worktreeDir);
  }
}

function renderMarkdown(record) {
  const out = [];
  out.push(`# Verification — ${record.id}`);
  out.push('');
  out.push(`_Run ${record.generatedAt} in an isolated git worktree, applied and removed — the main working tree was not touched._`);
  out.push('');
  if (record.refused) {
    out.push(`**Refused.** ${record.reason}`);
    out.push('');
    return out.join('\n');
  }
  out.push(`**Result:** ${record.passed ? 'PASS' : 'FAIL'} at stage \`${record.stage}\``);
  if (record.level) out.push(`**Verification level:** ${record.level}`);
  if (record.modules) out.push(`**Modules built:** ${record.modules.join(', ') || 'none'}`);
  out.push('');
  if (record.output) {
    out.push('```');
    out.push(record.output);
    out.push('```');
    out.push('');
  }
  if (record.steps) {
    for (const s of record.steps) {
      out.push(`### ${s.command}`);
      out.push('');
      out.push(`Exit code: ${s.exitCode}`);
      out.push('');
      out.push('```');
      out.push(s.output);
      out.push('```');
      out.push('');
    }
  }
  return out.join('\n');
}

function writeRecord(record) {
  fs.mkdirSync(WORK_DIR, { recursive: true });
  fs.writeFileSync(verificationJsonPathFor(record.id), JSON.stringify(record, null, 2));
  fs.writeFileSync(verificationMdPathFor(record.id), renderMarkdown(record));
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  let targets;
  if (args.all) {
    targets = listApprovedFixPlans().filter((p) => fs.existsSync(patchPathFor(p.id)));
    if (!targets.length) {
      console.log('No Approved fix plans with a patch already drafted. Nothing to verify.');
      return;
    }
    console.log(`Fixer — verifying ${targets.length} patch(es): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    if (!args.issue) throw new Error('Missing --issue. Pass an issue id (--issue ISSUE-001) or --all.');
    targets = [resolveFixPlan(args.issue)];
  }

  const done = [];
  const failed = [];
  for (const plan of targets) {
    try {
      const record = verifyOne(plan, args);
      writeRecord(record);
      done.push(record);
      if (record.refused) {
        console.log(`\n${record.id} — REFUSED: ${record.reason}`);
      } else {
        console.log(`\n${record.id} — ${record.passed ? 'PASS' : 'FAIL'} (${record.stage}${record.level ? `, ${record.level}` : ''})`);
        if (record.kept) console.log(`  worktree kept at ${record.kept} for inspection`);
      }
    } catch (err) {
      if (targets.length === 1) throw err;
      failed.push({ id: plan.id, message: err.message });
      console.error(`\n${plan.id} — ERROR: ${err.message}`);
    }
  }

  if (targets.length > 1) {
    const passed = done.filter((r) => r.passed).length;
    console.log(`\nVerified ${done.length}/${targets.length}: ${passed} pass, ${done.length - passed} fail/refused.`);
    if (failed.length) console.log(`Errored: ${failed.map((f) => f.id).join(', ')}`);
  }
  console.log(`\nNext: node scripts/render-fix-report.js --all (after writing each <id>.rationale.json)`);

  if (failed.length || done.some((r) => !r.passed)) process.exitCode = 1;
}

try {
  main();
} catch (err) {
  console.error('Verification failed:', err.message);
  process.exit(1);
}
