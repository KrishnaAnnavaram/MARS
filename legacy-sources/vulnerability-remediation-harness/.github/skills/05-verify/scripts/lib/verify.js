/**
 * Verification Layer — shared path resolution and read-only access, used by all three Step 1
 * checks (re-scanner, red-team-recon, behavior-guard). They share this one skill folder because
 * they read the exact same inputs; each still writes its own facts/verdict/report under its own
 * name so the three stay independently runnable and auditable.
 *
 * docs/agent_output/04-remediation/, docs/agent_output/04-remediation/, docs/agent_output/02-root-cause/ and docs/agent_output/00-issues/ are all READ-ONLY input here.
 * The only files this skill writes are under .github/.pipeline-context/verify/ and docs/agent_output/05-verify/.
 */
const fs = require('fs');
const path = require('path');
const register = require('../../../00-issue-register/scripts/lib/register');
const { spawnSync } = require('child_process');

const SKILL_DIR = path.resolve(__dirname, '..', '..');
const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');

const PATHS = {
  SKILL_DIR,
  REPO_ROOT,
  DATA_DIR,
  WORK_DIR: path.join(DATA_DIR, 'verify'),
  WORKTREES_DIR: path.join(DATA_DIR, 'verify', 'worktrees'),
  FIXES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  FIX_PLANS_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  ROOT_CAUSE_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '02-root-cause'),
  ISSUES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues'),
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '05-verify'),
  OUT_README: path.join(REPO_ROOT, 'docs', 'agent_output', '05-verify', 'README.md'),
  CWE_CATALOG_FILE: path.join(SKILL_DIR, '..', '04a-fix-strategist', 'catalog', 'cwe-patterns.json'),
};

function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

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

function numberedListItems(sectionText) {
  if (!sectionText) return [];
  const out = [];
  const re = /^\d+\.\s+(.+)$/gm;
  let m;
  while ((m = re.exec(sectionText))) out.push(m[1].trim());
  return out;
}

// ---------------------------------------------------------------------------
// docs/agent_output/04-remediation/fix_<id>.md — the workload
// ---------------------------------------------------------------------------

function parseFixReport(text) {
  const title = /^##\s+(?!\d)(.+)$/m.exec(text);
  const status = /\|\s*\*\*Status\*\*\s*\|\s*([^|]+)\|/.exec(text);
  const cwe = /\|\s*\*\*CWE\*\*\s*\|\s*`([^`]+)`/.exec(text);
  // Depth-agnostic: the report links back to the repo root with however many `../`
  // its own folder depth requires, so never hard-code that count here.
  const planLink = /\|\s*\*\*Fix plan\*\*\s*\|\s*\[[^\]]*\]\((?:\.\.\/)+([^)]+)\)/.exec(text);
  const patchLink = /\|\s*\*\*Patch\*\*\s*\|\s*\[[^\]]*\]\((?:\.\.\/)+([^)]+)\)/.exec(text);

  const whatChanged = sectionBody(text, 'What changed');
  const why = sectionBody(text, 'Why this is the smallest correct diff');

  return {
    title: title ? title[1].trim() : '(untitled)',
    status: status ? status[1].trim() : 'unknown',
    cwe: cwe ? cwe[1].trim() : null,
    fixPlanFile: planLink ? planLink[1].trim() : null,
    patchFile: patchLink ? patchLink[1].trim() : null,
    filesChanged: linkedPaths(whatChanged),
    whyApproach: why,
  };
}

// Step 1 (re-scanner/red-team-recon/behavior-guard) is diff-and-source static analysis — it
// never invokes a compiler, so it does not actually need Fixer's own compile check to have
// passed. A Compile Failed fix still has a real diff and real patched source to reason about,
// and is still real, reviewable evidence. "Refused" is excluded: that means Fixer never drafted
// anything at all, so there is nothing here to analyze.
const STEP1_ELIGIBLE_STATUSES = ['Compiled', 'Compile Failed'];

function listStep1Fixes() {
  if (!fs.existsSync(PATHS.FIXES_DIR)) return [];
  return fs
    .readdirSync(PATHS.FIXES_DIR)
    .filter((f) => /^fix_(?!plan_).+\.md$/i.test(f))
    .map((name) => {
      const file = path.join(PATHS.FIXES_DIR, name);
      const id = name.replace(/^fix_/i, '').replace(/\.md$/i, '');
      const parsed = parseFixReport(fs.readFileSync(file, 'utf8'));
      return {
        id, fixReportFile: file, relativeFixReportFile: rel(file), ...parsed,
      };
    })
    .filter((f) => STEP1_ELIGIBLE_STATUSES.includes(f.status))
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
}

function resolveFix(idOrPath) {
  if (!idOrPath) {
    throw new Error('Missing --issue. Pass an issue id (ISSUE-001) or --all for every fix in docs/agent_output/04-remediation/ that Fixer actually drafted a diff for (Status: Compiled or Compile Failed).');
  }
  const all = listStep1Fixes();
  const byId = all.find((f) => f.id.toLowerCase() === String(idOrPath).toLowerCase());
  if (byId) return byId;

  // Allow a Refused fix to resolve by exact id too, so a caller gets a clear "nothing to
  // analyze" error instead of "not found" — but it is never included in --all.
  if (fs.existsSync(PATHS.FIXES_DIR)) {
    const name = fs.readdirSync(PATHS.FIXES_DIR).find((f) => f.toLowerCase() === `fix_${String(idOrPath).toLowerCase()}.md`);
    if (name) {
      const file = path.join(PATHS.FIXES_DIR, name);
      const parsed = parseFixReport(fs.readFileSync(file, 'utf8'));
      return {
        id: idOrPath, fixReportFile: file, relativeFixReportFile: rel(file), ...parsed,
      };
    }
  }
  throw new Error(
    `No fix report for "${idOrPath}" in ${rel(PATHS.FIXES_DIR)}. `
    + `Available (Compiled or Compile Failed): ${all.map((f) => f.id).join(', ') || 'none — run the Fixer agent first'}`
  );
}

// ---------------------------------------------------------------------------
// Upstream chain: fix plan -> root cause -> issue (all read-only)
// ---------------------------------------------------------------------------

function parseFixPlan(text) {
  const approach = sectionBody(text, 'Remediation approach');
  const risks = sectionBody(text, 'Risks to watch');
  const catalogCwe = /\*\*Catalog pattern \(`([^`]+)`\)/.exec(text);
  return {
    approach,
    riskNotes: risks ? risks.split(/\r?\n/).filter((l) => l.trim().startsWith('-')).map((l) => l.replace(/^-\s*/, '').trim()) : [],
    catalogCwe: catalogCwe ? catalogCwe[1].trim() : null,
  };
}

function parseRootCause(text) {
  const section = /##\s+\d*\.?\s*Where the defect is\s*\r?\n([\s\S]*?)(?=\r?\n##\s)/.exec(text);
  const statement = section ? /^>\s*\*\*(.+?)\*\*\s*$/m.exec(section[1]) : null;
  const explanation = section ? section[1].replace(/^>.*$/m, '').trim() : null;
  return {
    statement: statement ? statement[1].trim() : null,
    explanation,
  };
}

/**
 * The register row for a fix, plus the grep-able signatures the re-scanner re-derives.
 * `body` is synthesized by the register loader from the spreadsheet's text columns, so
 * the Detection Notes extraction below is unchanged from when issues were markdown.
 */
function readIssueForFix(id) {
  const issue = register.readIssue(PATHS.ISSUES_DIR, id, rel);
  if (!issue) return null;

  const detectionSection = /##\s+Detection Notes\s*\r?\n([\s\S]*?)(?=\r?\n##\s|$)/.exec(issue.body);
  const signatures = [];
  if (detectionSection) {
    const re = /`([^`]{3,80})`/g;
    let m;
    while ((m = re.exec(detectionSection[1]))) {
      const token = m[1].trim();
      // Skip file paths and obvious non-signatures; keep short code-shaped tokens.
      if (!token.includes('/') && !token.includes('\\') && token.length <= 60) signatures.push(token);
    }
  }
  return {
    file: issue.file,
    relativeFile: issue.relativeFile,
    body: issue.body,
    detectionNotes: detectionSection ? detectionSection[1].trim() : null,
    signatures: [...new Set(signatures)],
  };
}

/** Walks fix -> fix plan -> root cause -> issue, returning whatever resolves (never throws on a missing hop). */
function upstreamChainFor(fix) {
  const chain = { fixPlan: null, rootCause: null, issue: null };
  if (fix.fixPlanFile) {
    const planPath = path.join(REPO_ROOT, fix.fixPlanFile);
    const planText = readIfPresent(planPath);
    if (planText) chain.fixPlan = { file: fix.fixPlanFile, ...parseFixPlan(planText) };
  }
  const rootCauseFile = path.join(PATHS.ROOT_CAUSE_DIR, `root_cause_${fix.id}.md`);
  const rootCauseText = readIfPresent(rootCauseFile);
  if (rootCauseText) chain.rootCause = { file: rel(rootCauseFile), ...parseRootCause(rootCauseText) };
  chain.issue = readIssueForFix(fix.id);
  return chain;
}

// ---------------------------------------------------------------------------
// CWE catalog (read-only, owned by 04a-fix-strategist)
// ---------------------------------------------------------------------------

function loadCatalogEntry(cwe) {
  if (!cwe || !fs.existsSync(PATHS.CWE_CATALOG_FILE)) return null;
  const raw = JSON.parse(fs.readFileSync(PATHS.CWE_CATALOG_FILE, 'utf8'));
  return raw[cwe] || null;
}

// ---------------------------------------------------------------------------
// Isolated worktree — materialize the patched file content, read-only otherwise.
// Strict subset of 04b-fixer/scripts/verify-patch.js: no build, apply + read + remove only.
// ---------------------------------------------------------------------------

function run(cmd, args, options) {
  const result = spawnSync(cmd, args, { cwd: REPO_ROOT, encoding: 'utf8', shell: false, ...options });
  return { status: result.status, stdout: result.stdout || '', stderr: result.stderr || '' };
}

function removeWorktreeIfPresent(worktreeDir) {
  if (!fs.existsSync(worktreeDir)) return;
  const result = run('git', ['worktree', 'remove', '--force', worktreeDir]);
  if (result.status !== 0) {
    run('git', ['worktree', 'prune']);
    fs.rmSync(worktreeDir, { recursive: true, force: true });
  }
}

/**
 * Applies the fix's patch inside a throwaway worktree, reads the given repo-relative files from
 * the result, then removes the worktree — always, even on failure. Never touches the real tree.
 * Returns { applied: boolean, files: { [path]: string|null }, error: string|null }.
 */
function materializePatchedFiles(id, patchFile, filePaths) {
  const worktreeDir = path.join(PATHS.WORKTREES_DIR, id);
  fs.mkdirSync(PATHS.WORKTREES_DIR, { recursive: true });
  removeWorktreeIfPresent(worktreeDir);

  try {
    const add = run('git', ['worktree', 'add', '--detach', worktreeDir, 'HEAD']);
    if (add.status !== 0) return { applied: false, files: {}, error: `worktree add failed: ${add.stderr || add.stdout}` };

    const apply = run('git', ['apply', path.resolve(patchFile)], { cwd: worktreeDir });
    if (apply.status !== 0) return { applied: false, files: {}, error: `git apply failed: ${apply.stderr || apply.stdout}` };

    const files = {};
    for (const p of filePaths) {
      const abs = path.join(worktreeDir, p);
      files[p] = fs.existsSync(abs) ? fs.readFileSync(abs, 'utf8') : null;
    }
    return { applied: true, files, error: null };
  } finally {
    removeWorktreeIfPresent(worktreeDir);
  }
}

// ---------------------------------------------------------------------------
// Path helpers for this skill's own intermediate/output files
// ---------------------------------------------------------------------------

const factsPathFor = (id, name) => path.join(PATHS.WORK_DIR, `${id}.${name}.facts.json`);
const briefingPathFor = (id, name) => path.join(PATHS.WORK_DIR, `${id}.${name}.facts.md`);
const verdictPathFor = (id, name) => path.join(PATHS.WORK_DIR, `${id}.${name}.verdict.json`);
const reportPathFor = (id, prefix) => path.join(PATHS.OUT_DIR, `${prefix}_${id}.md`);

// ---------------------------------------------------------------------------
// docs/agent_output/05-verify/README.md — one combined index shared by all three renderers, so re-scan,
// red-team and behavior results never race to overwrite each other's row in the table.
// ---------------------------------------------------------------------------

const VERIFY_INDEX_MARKER = '<!-- AUTO-GENERATED TABLE — regenerated by 05_existing-app-test-agent, do not hand-edit below this line -->';

function verifyReadmeContract() {
  return `# Step 1 — Multi-layer Verification

Three parallel, static/reasoning-based checks run against every fix in [\`docs/agent_output/04-remediation/\`](../04-remediation/)
that Fixer actually drafted a diff for (\`Status: Compiled\` **or** \`Compile Failed\` — a failed
compile doesn't stop this stage, since none of these three checks invoke a compiler; see "Why
Compile Failed fixes are still eligible" below): **re-scan** (does the finding still trigger?),
**red-team** (can the patch be bypassed?) and **behavior guard** (did real behavior change?) — all run
by the \`05_existing-app-test-agent\`.
None of them run a live database or a deployed instance — this repo has no embedded-Mongo/
Testcontainers dependency, so all three reason over the diff, the pre-patch source, and the patched
source materialized inside a throwaway \`git worktree\`.

Reports land here as \`rescan_<id>.md\`, \`redteam_<id>.md\`, \`behavior_<id>.md\`. The **merge
arbiter** (Phase C step 3) reads all three; none of them alone decides whether a patch ships.

`;
}

function readVerdict(prefix, id) {
  const file = path.join(PATHS.OUT_DIR, `${prefix}_${id}.md`);
  if (!fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8');
  const m = /\|\s*\*\*Verdict\*\*\s*\|\s*([^|]+)\|/.exec(text);
  return m ? m[1].trim() : 'unknown';
}

/** Combines rescan/redteam/behavior results for every fix into one table — call after any render. */
function rewriteVerifyIndex() {
  if (!fs.existsSync(PATHS.OUT_DIR)) return;
  const ids = new Set();
  for (const f of fs.readdirSync(PATHS.OUT_DIR)) {
    const m = /^(?:rescan|redteam|behavior)_(.+)\.md$/i.exec(f);
    if (m) ids.add(m[1]);
  }
  const rows = [...ids].sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).map((id) => {
    const anyFile = ['rescan', 'redteam', 'behavior']
      .map((p) => path.join(PATHS.OUT_DIR, `${p}_${id}.md`))
      .find((f) => fs.existsSync(f));
    const title = anyFile ? (/^##\s+(.+)$/m.exec(fs.readFileSync(anyFile, 'utf8')) || [])[1] : null;
    return {
      id,
      title: (title || '(untitled)').trim(),
      rescan: readVerdict('rescan', id) || '_pending_',
      redteam: readVerdict('redteam', id) || '_pending_',
      behavior: readVerdict('behavior', id) || '_pending_',
    };
  });

  const existing = fs.existsSync(PATHS.OUT_README) ? fs.readFileSync(PATHS.OUT_README, 'utf8') : verifyReadmeContract();
  const contract = existing.includes(VERIFY_INDEX_MARKER) ? existing.slice(0, existing.indexOf(VERIFY_INDEX_MARKER)) : existing.trimEnd() + '\n\n';
  const table = [VERIFY_INDEX_MARKER, ''];
  if (!rows.length) table.push('_No Step 1 reports yet._', '');
  else {
    table.push('| ID | Title | Re-scan | Red-team | Behavior |', '|---|---|---|---|---|');
    for (const r of rows) table.push(`| ${r.id} | ${r.title} | ${r.rescan} | ${r.redteam} | ${r.behavior} |`);
    table.push('');
  }
  fs.mkdirSync(PATHS.OUT_DIR, { recursive: true });
  fs.writeFileSync(PATHS.OUT_README, contract.trimEnd() + '\n\n' + table.join('\n'));
}

module.exports = {
  ...PATHS,
  rel,
  readIfPresent,
  sectionBody,
  linkedPaths,
  numberedListItems,
  parseFixReport,
  listStep1Fixes,
  resolveFix,
  upstreamChainFor,
  loadCatalogEntry,
  materializePatchedFiles,
  factsPathFor,
  briefingPathFor,
  verdictPathFor,
  reportPathFor,
  rewriteVerifyIndex,
};
