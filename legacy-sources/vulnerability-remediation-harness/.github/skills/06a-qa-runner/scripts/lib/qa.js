/**
 * QA Runner — shared path resolution, fix-report access, and the isolated test-execution
 * sandbox. docs/agent_output/04-remediation/ is read-only input. The only files this skill writes are under
 * .github/.pipeline-context/qa/ and docs/agent_output/06-test-gate/.
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const SKILL_DIR = path.resolve(__dirname, '..', '..');
const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');

const KNOWN_MODULES = [
  'configuaration-server', 'discovery-service', 'department-service',
  'employee-service', 'report-service', 'sheduler-service',
];

const PATHS = {
  SKILL_DIR,
  REPO_ROOT,
  DATA_DIR,
  WORK_DIR: path.join(DATA_DIR, 'qa'),
  WORKTREES_DIR: path.join(DATA_DIR, 'qa', 'worktrees'),
  FIXES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '06-test-gate'),
  OUT_README: path.join(REPO_ROOT, 'docs', 'agent_output', '06-test-gate', 'README.md'),
};

function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

// ---------------------------------------------------------------------------
// docs/agent_output/04-remediation/fix_<id>.md — same parser pattern as 05-verify/fixer, duplicated
// ---------------------------------------------------------------------------

function sectionBody(text, headingPattern) {
  const re = new RegExp(`##\\s+\\d*\\.?\\s*${headingPattern}\\s*\\r?\\n([\\s\\S]*?)(?=\\r?\\n##\\s)`);
  const m = re.exec(text);
  return m ? m[1].trim() : null;
}

function linkedPaths(sectionText) {
  if (!sectionText) return [];
  const out = [];
  // Depth-agnostic — see parseFixReport.
  const re = /\]\((?:\.\.\/)+([^)]+)\)/g;
  let m;
  while ((m = re.exec(sectionText))) if (!out.includes(m[1])) out.push(m[1]);
  return out;
}

function parseFixReport(text) {
  const title = /^##\s+(?!\d)(.+)$/m.exec(text);
  const status = /\|\s*\*\*Status\*\*\s*\|\s*([^|]+)\|/.exec(text);
  const cwe = /\|\s*\*\*CWE\*\*\s*\|\s*`([^`]+)`/.exec(text);
  // Depth-agnostic: the report links back to the repo root with however many `../`
  // its own folder depth requires, so never hard-code that count here.
  const patchLink = /\|\s*\*\*Patch\*\*\s*\|\s*\[[^\]]*\]\((?:\.\.\/)+([^)]+)\)/.exec(text);
  const whatChanged = sectionBody(text, 'What changed');
  return {
    title: title ? title[1].trim() : '(untitled)',
    status: status ? status[1].trim() : 'unknown',
    cwe: cwe ? cwe[1].trim() : null,
    patchFile: patchLink ? patchLink[1].trim() : null,
    filesChanged: linkedPaths(whatChanged),
  };
}

// The QA gate runs its own independent compile attempt in its own isolated worktree, separate
// from Fixer's — it does not need Fixer's own compile check to have passed to be worth running,
// and a Compile Failed fix still has a real diff to draft a real test against. Only "Refused"
// (Fixer never drafted a diff at all) is excluded. If the gate itself fails to compile, that is
// reported honestly as Failed — this is not a promotion, just a chance to gather real evidence.
const STEP2_ELIGIBLE_STATUSES = ['Compiled', 'Compile Failed'];

function listStep2Fixes() {
  if (!fs.existsSync(PATHS.FIXES_DIR)) return [];
  return fs
    .readdirSync(PATHS.FIXES_DIR)
    .filter((f) => /^fix_(?!plan_).+\.md$/i.test(f))
    .map((name) => {
      const file = path.join(PATHS.FIXES_DIR, name);
      const id = name.replace(/^fix_/i, '').replace(/\.md$/i, '');
      const parsed = parseFixReport(fs.readFileSync(file, 'utf8'));
      return { id, fixReportFile: file, relativeFixReportFile: rel(file), ...parsed };
    })
    .filter((f) => STEP2_ELIGIBLE_STATUSES.includes(f.status))
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
}

function resolveFix(idOrPath) {
  if (!idOrPath) throw new Error('Missing --issue. Pass an issue id (ISSUE-001) or --all for every fix with a drafted diff (Compiled or Compile Failed).');
  const all = listStep2Fixes();
  const byId = all.find((f) => f.id.toLowerCase() === String(idOrPath).toLowerCase());
  if (byId) return byId;
  if (fs.existsSync(PATHS.FIXES_DIR)) {
    const name = fs.readdirSync(PATHS.FIXES_DIR).find((f) => f.toLowerCase() === `fix_${String(idOrPath).toLowerCase()}.md`);
    if (name) {
      const file = path.join(PATHS.FIXES_DIR, name);
      const parsed = parseFixReport(fs.readFileSync(file, 'utf8'));
      return { id: idOrPath, fixReportFile: file, relativeFixReportFile: rel(file), ...parsed };
    }
  }
  throw new Error(`No fix report for "${idOrPath}". Available (Compiled or Compile Failed): ${all.map((f) => f.id).join(', ') || 'none — run the Fixer agent first'}`);
}

// ---------------------------------------------------------------------------
// git / maven — same corrected pattern as 04b-fixer/scripts/verify-patch.js
// (array argv, no shell:true string concatenation; cmd.exe /d /s /c wraps .cmd on Windows)
// ---------------------------------------------------------------------------

function run(cmd, args, options) {
  const result = spawnSync(cmd, args, { cwd: REPO_ROOT, encoding: 'utf8', shell: false, ...options });
  return { status: result.status, stdout: result.stdout || '', stderr: result.stderr || '', error: result.error ? result.error.message : null };
}

function removeWorktreeIfPresent(worktreeDir) {
  if (!fs.existsSync(worktreeDir)) return;
  const result = run('git', ['worktree', 'remove', '--force', worktreeDir]);
  if (result.status !== 0) {
    run('git', ['worktree', 'prune']);
    fs.rmSync(worktreeDir, { recursive: true, force: true });
  }
}

function wrapperFor(moduleDir) {
  return process.platform === 'win32' ? path.join(moduleDir, 'mvnw.cmd') : path.join(moduleDir, 'mvnw');
}

/**
 * Runs the Maven wrapper. On Windows a .cmd cannot be exec'd directly, so it goes
 * through `cmd.exe /d /s /c`.
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
  for (const f of files) { const top = f.split('/')[0]; if (KNOWN_MODULES.includes(top)) modules.add(top); }
  return [...modules];
}

const TAIL_CHARS = 4000;
function tail(text, n = TAIL_CHARS) {
  if (!text) return '';
  return text.length > n ? `…(truncated)…\n${text.slice(-n)}` : text;
}

// ---------------------------------------------------------------------------
// Path helpers
// ---------------------------------------------------------------------------

const testPlanPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.test-plan.json`);
const testDiffPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.new-test.diff`);
const resultPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.result.json`);
const reportPathFor = (id) => path.join(PATHS.OUT_DIR, `qa_${id}.md`);

module.exports = {
  ...PATHS,
  rel,
  readIfPresent,
  parseFixReport,
  listStep2Fixes,
  resolveFix,
  run,
  removeWorktreeIfPresent,
  wrapperFor,
  runWrapper,
  changedFilesFromPatch,
  modulesFromFiles,
  tail,
  testPlanPathFor,
  testDiffPathFor,
  resultPathFor,
  reportPathFor,
};
