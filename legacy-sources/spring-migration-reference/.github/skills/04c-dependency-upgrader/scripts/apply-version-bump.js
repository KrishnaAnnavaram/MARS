#!/usr/bin/env node
/**
 * Dependency Upgrader — Isolated Version-Bump Verification
 *
 * A dependency-version fix answers a different question than a logic fix: not just "does it
 * compile", but "did the version actually change, and did that change actually take effect in
 * what Maven resolves" — a <version> edit can be textually correct in one pom.xml and still not
 * take effect if another dependency-management entry overrides it. This script checks both.
 *
 * The Fixer never edits the real working tree. To prove a drafted version-bump patch actually
 * works, this script:
 *
 *   1. Requires the fix plan to be Status: Approved, and CWE: CWE-1104 — refuses otherwise.
 *   2. Requires the agent to have already written .github/.pipeline-context/dependency-upgrader/<id>.patch.diff.
 *   3. Creates a throwaway `git worktree` checked out from HEAD.
 *   4. Applies the patch inside that worktree only.
 *   5. Confirms the *declared* version in the patched pom.xml for the plan's maven_coordinate is
 *      >= minimum_fixed_version (not just that the file changed at all).
 *   6. Runs the affected Maven module's wrapper `compile`.
 *   7. Runs `dependency:tree` in the same worktree and confirms the *resolved* version for that
 *      coordinate also meets minimum_fixed_version — catches a bump that doesn't actually take
 *      effect due to a management override elsewhere.
 *   8. Always removes the worktree afterwards (unless --keep) — the main working tree and
 *      `git status` are never touched, at any point, by this script.
 *
 * Writes:
 *   .github/.pipeline-context/dependency-upgrader/<id>.verification.json  — machine-readable
 *   .github/.pipeline-context/dependency-upgrader/<id>.verification.md   — human-readable summary
 *
 * This script is deterministic. It never judges whether the fix is *right* — only whether the
 * patch applies, the declared and resolved versions both meet the plan's target, and the build
 * still compiles.
 *
 * Usage:
 *   node scripts/apply-version-bump.js --issue ISSUE-004 [--keep]
 *   node scripts/apply-version-bump.js --all [--keep]   # every Approved CWE-1104 plan with a patch already drafted
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const {
  REPO_ROOT, WORK_DIR, WORKTREES_DIR,
  rel, resolveFixPlan, listApprovedDependencyPlans,
  patchPathFor, worktreePathFor, verificationJsonPathFor, verificationMdPathFor,
} = require('./lib/depfixplans');

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
    else if (arg === '--keep') args.keep = true;
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Dependency Upgrader — Isolated Version-Bump Verification

  node scripts/apply-version-bump.js --issue <ISSUE-ID> [--keep]
  node scripts/apply-version-bump.js --all [--keep]

Options:
  --issue, -i   A single issue id, e.g. ISSUE-004. The fix plan must be Status: Approved and CWE: CWE-1104.
  --all, -a     Verify every Approved CWE-1104 plan that already has a patch drafted
  --keep        Do not remove the worktree afterwards — for inspecting a failure
  --help, -h    Show this message

Refuses to run on any plan whose fix plan Status is not "Approved", or whose CWE is not "CWE-1104".`);
}

// ---------------------------------------------------------------------------
// git / maven helpers (same pattern as 04b-fixer/scripts/verify-patch.js)
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

/** Same DEP0190-safe invocation as verify-patch.js — never shell:true with string concatenation. */
function runWrapper(wrapper, mvnArgs, options) {
  if (process.platform === 'win32') {
    const command = [wrapper, ...mvnArgs].map((a) => `"${a}"`).join(' ');
    return run('cmd.exe', ['/d', '/s', '/c', `"${command}"`], { ...options, windowsVerbatimArguments: true });
  }
  return run(wrapper, mvnArgs, options);
}

// ---------------------------------------------------------------------------
// Version comparison — plain dotted-numeric Maven versions, no full semver needed here
// ---------------------------------------------------------------------------

function compareVersions(a, b) {
  const pa = String(a).split(/[.-]/).map((s) => parseInt(s, 10) || 0);
  const pb = String(b).split(/[.-]/).map((s) => parseInt(s, 10) || 0);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i += 1) {
    const x = pa[i] || 0;
    const y = pb[i] || 0;
    if (x !== y) return x - y;
  }
  return 0;
}

// ---------------------------------------------------------------------------
// Declared version — parse the patched pom.xml(s) for the target coordinate
// ---------------------------------------------------------------------------

function declaredVersionInPom(pomText, groupId, artifactId) {
  const depBlockRe = /<dependency>([\s\S]*?)<\/dependency>/g;
  let m;
  while ((m = depBlockRe.exec(pomText))) {
    const block = m[1];
    const g = /<groupId>\s*([^<]+?)\s*<\/groupId>/.exec(block);
    const a = /<artifactId>\s*([^<]+?)\s*<\/artifactId>/.exec(block);
    const v = /<version>\s*([^<]+?)\s*<\/version>/.exec(block);
    if (g && a && g[1] === groupId && a[1] === artifactId) {
      return v ? v[1] : null;
    }
  }
  return null;
}

/** Resolved version from `mvn dependency:tree` output, e.g. "org.apache.poi:poi-ooxml:jar:5.4.0:compile". */
function resolvedVersionInTree(treeOutput, groupId, artifactId) {
  const escaped = `${groupId}:${artifactId}`.replace(/[.]/g, '\\.');
  const re = new RegExp(`${escaped}:[^:]+:([0-9][^:\\s]*)`);
  const m = re.exec(treeOutput);
  return m ? m[1] : null;
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
      reason: `Fix plan Status is "${plan.status}", not "Approved". This skill will not verify or apply an unapproved plan — see ${plan.relativePlanFile}.`,
    };
  }
  if (plan.cwe !== 'CWE-1104') {
    return {
      id: plan.id,
      passed: false,
      refused: true,
      reason: `Fix plan CWE is "${plan.cwe}", not "CWE-1104". This skill only handles dependency-version upgrades — use 04b-fixer for this plan instead.`,
    };
  }
  if (!plan.dependencyUpgrade) {
    return {
      id: plan.id,
      passed: false,
      refused: true,
      reason: `Fix plan has no parsed "Dependency" row (maven_coordinate/current_version/minimum_fixed_version). Re-render the plan from a strategy.json that includes the dependency_upgrade object.`,
    };
  }

  const { mavenCoordinate, minimumFixedVersion } = plan.dependencyUpgrade;
  const [groupId, artifactId] = mavenCoordinate.split(':');

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
    mavenCoordinate,
    minimumFixedVersion,
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

    // --- New check 1: declared version in the patched pom.xml(s) ---
    let declaredVersion = null;
    let declaredIn = null;
    for (const module of modules) {
      const pomPath = path.join(worktreeDir, module, 'pom.xml');
      if (!fs.existsSync(pomPath)) continue;
      const v = declaredVersionInPom(fs.readFileSync(pomPath, 'utf8'), groupId, artifactId);
      if (v) { declaredVersion = v; declaredIn = `${module}/pom.xml`; break; }
    }
    record.declaredVersion = declaredVersion;
    record.declaredIn = declaredIn;
    if (!declaredVersion) {
      record.passed = false;
      record.stage = 'declared-version-check';
      record.output = `Could not find an explicit <dependency> entry for ${mavenCoordinate} in any patched pom.xml (${modules.map((m) => `${m}/pom.xml`).join(', ')}). The patch may not touch the right file.`;
      return record;
    }
    if (compareVersions(declaredVersion, minimumFixedVersion) < 0) {
      record.passed = false;
      record.stage = 'declared-version-check';
      record.output = `Declared version after patch is ${declaredVersion} in ${declaredIn}, which is still below the plan's minimum_fixed_version ${minimumFixedVersion}.`;
      return record;
    }

    // --- Compile (same as 04b-fixer) ---
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
      if (compile.status !== 0) { allPassed = false; }
    }

    if (!allPassed) {
      record.passed = false;
      record.stage = 'compile';
      return record;
    }

    // --- New check 2: resolved version via mvn dependency:tree ---
    let resolvedVersion = null;
    let resolvedIn = null;
    for (const module of modules) {
      const moduleDir = path.join(worktreeDir, module);
      const wrapper = wrapperFor(moduleDir);
      const tree = runWrapper(wrapper, ['-q', 'dependency:tree'], { cwd: moduleDir });
      record.steps.push({
        module,
        command: `mvnw dependency:tree (${module})`,
        exitCode: tree.status,
        output: tail(tree.stdout + tree.stderr + (tree.error ? `\n[spawn error] ${tree.error}` : '')),
      });
      if (tree.status !== 0) { allPassed = false; continue; }
      const v = resolvedVersionInTree(tree.stdout, groupId, artifactId);
      if (v) { resolvedVersion = v; resolvedIn = module; }
    }
    record.resolvedVersion = resolvedVersion;
    record.resolvedIn = resolvedIn;

    if (!allPassed) {
      record.passed = false;
      record.stage = 'dependency-tree';
      return record;
    }
    if (!resolvedVersion) {
      record.passed = false;
      record.stage = 'dependency-tree-check';
      record.output = `${mavenCoordinate} did not appear in dependency:tree output for module(s) ${modules.join(', ')} — cannot confirm the resolved version.`;
      return record;
    }
    if (compareVersions(resolvedVersion, minimumFixedVersion) < 0) {
      record.passed = false;
      record.stage = 'dependency-tree-check';
      record.output = `Resolved version in ${resolvedIn} is ${resolvedVersion} (from dependency:tree), still below minimum_fixed_version ${minimumFixedVersion} — the declared <version> bump did not take effect in what Maven actually resolves. Check for another dependencyManagement entry overriding it.`;
      return record;
    }

    record.passed = true;
    record.stage = 'complete';
    record.level = 'declared version + compile + resolved dependency-tree version';
    return record;
  } finally {
    if (!args.keep) removeWorktreeIfPresent(worktreeDir);
    else record.kept = rel(worktreeDir);
  }
}

function renderMarkdown(record) {
  const out = [];
  out.push(`# Dependency Upgrade Verification — ${record.id}`);
  out.push('');
  out.push(`_Run ${record.generatedAt} in an isolated git worktree, applied and removed — the main working tree was not touched._`);
  out.push('');
  if (record.refused) {
    out.push(`**Refused.** ${record.reason}`);
    out.push('');
    return out.join('\n');
  }
  out.push(`**Result:** ${record.passed ? 'PASS' : 'FAIL'} at stage \`${record.stage}\``);
  if (record.mavenCoordinate) out.push(`**Coordinate:** \`${record.mavenCoordinate}\`, target \`>= ${record.minimumFixedVersion}\``);
  if (record.declaredVersion) out.push(`**Declared version after patch:** \`${record.declaredVersion}\` (${record.declaredIn})`);
  if (record.resolvedVersion) out.push(`**Resolved version (dependency:tree):** \`${record.resolvedVersion}\` (${record.resolvedIn})`);
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
    targets = listApprovedDependencyPlans().filter((p) => fs.existsSync(patchPathFor(p.id)));
    if (!targets.length) {
      console.log('No Approved CWE-1104 fix plans with a patch already drafted. Nothing to verify.');
      return;
    }
    console.log(`Dependency Upgrader — verifying ${targets.length} patch(es): ${targets.map((t) => t.id).join(', ')}`);
  } else {
    if (!args.issue) throw new Error('Missing --issue. Pass an issue id (--issue ISSUE-004) or --all.');
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
