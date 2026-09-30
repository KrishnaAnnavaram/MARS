/**
 * Dependency Upgrader — shared path resolution and read-only access to fix plans.
 *
 * The workload is fix plans in docs/agent_output/04-remediation/ whose Status cell reads "Approved"
 * AND whose CWE is CWE-1104 (a dependency-version upgrade) — every other CWE belongs to 04b-fixer,
 * not this skill. docs/agent_output/04-remediation/ is READ-ONLY input here: nothing in this skill
 * edits a plan file. The human-editable Status cell is the one piece of that file this skill reads
 * as a gate, never writes.
 *
 * Kept as its own copy of 04b-fixer's lib/fixplans.js (not a shared import) — this pipeline
 * deliberately duplicates path/parsing helpers per skill rather than coupling skills together, the
 * same choice already made for every other skill pair in this repo.
 */
const fs = require('fs');
const path = require('path');

const SKILL_DIR = path.resolve(__dirname, '..', '..');
const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');

const PATHS = {
  SKILL_DIR,
  REPO_ROOT,
  DATA_DIR,
  WORK_DIR: path.join(DATA_DIR, 'dependency-upgrader'),
  WORKTREES_DIR: path.join(DATA_DIR, 'dependency-upgrader', 'worktrees'),
  FIX_PLANS_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  OUT_README: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation', 'README.md'),
};

function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

// ---------------------------------------------------------------------------
// Fix plan parsing — plans carry no front matter, same "At a glance table + bold
// labels" convention docs/agent_output/02-root-cause and docs/agent_output/03-blast-radius use.
// ---------------------------------------------------------------------------

function sectionBody(text, headingPattern) {
  const re = new RegExp(`##\\s+\\d*\\.?\\s*${headingPattern}\\s*\\r?\\n([\\s\\S]*?)(?=\\r?\\n##\\s)`);
  const m = re.exec(text);
  return m ? m[1].trim() : null;
}

function linkedPaths(sectionText) {
  if (!sectionText) return [];
  const paths = [];
  const re = /\]\(\.\.\/\.\.\/([^)]+)\)/g;
  let m;
  while ((m = re.exec(sectionText))) {
    if (!paths.includes(m[1])) paths.push(m[1]);
  }
  return paths;
}

function numberedListItems(sectionText) {
  if (!sectionText) return [];
  const items = [];
  const re = /^\d+\.\s+(.+)$/gm;
  let m;
  while ((m = re.exec(sectionText))) items.push(m[1].trim());
  return items;
}

/** Parses the "| **Dependency** | `coord` current → ≥fixed (CVE) |" row render-fix-plan.js writes. */
function dependencyUpgradeRow(text) {
  const re = /\|\s*\*\*Dependency\*\*\s*\|\s*`([^`]+)`\s+([^\s]+)\s+→\s+≥([^\s(]+)(?:\s+\(([^)]+)\))?\s*\|/;
  const m = re.exec(text);
  if (!m) return null;
  return {
    mavenCoordinate: m[1].trim(),
    currentVersion: m[2].trim(),
    minimumFixedVersion: m[3].trim(),
    cve: m[4] ? m[4].trim() : null,
  };
}

function parseFixPlan(text) {
  const title = /^##\s+(?!\d)(.+)$/m.exec(text);
  const plainSummary = /^>\s*(.+)$/m.exec(text);
  const status = /\|\s*\*\*Status\*\*\s*\|\s*([^|]+)\|/.exec(text);
  const cwe = /\|\s*\*\*CWE\*\*\s*\|\s*`([^`]+)`/.exec(text);
  const rootCauseLink = /\|\s*\*\*Root cause report\*\*\s*\|\s*\[[^\]]*\]\(\.\.\/\.\.\/([^)]+)\)/.exec(text);
  const blastRadiusLink = /\|\s*\*\*Blast radius report\*\*\s*\|\s*\[[^\]]*\]\(\.\.\/\.\.\/([^)]+)\)/.exec(text);

  const plannedChanges = sectionBody(text, 'Planned changes');
  const verification = sectionBody(text, 'How the fix must be verified');
  const approach = sectionBody(text, 'Remediation approach');

  return {
    title: title ? title[1].trim() : '(untitled)',
    plainSummary: plainSummary ? plainSummary[1].trim() : null,
    status: status ? status[1].trim() : 'unknown',
    cwe: cwe ? cwe[1].trim() : null,
    rootCauseReport: rootCauseLink ? rootCauseLink[1].trim() : null,
    blastRadiusReport: blastRadiusLink ? blastRadiusLink[1].trim() : null,
    approach,
    affectedFiles: linkedPaths(plannedChanges),
    verificationPlan: numberedListItems(verification),
    dependencyUpgrade: dependencyUpgradeRow(text),
  };
}

// ---------------------------------------------------------------------------
// Fix plan register — the workload
// ---------------------------------------------------------------------------

function listFixPlans() {
  if (!fs.existsSync(PATHS.FIX_PLANS_DIR)) return [];
  return fs
    .readdirSync(PATHS.FIX_PLANS_DIR)
    .filter((f) => /^fix_plan_.+\.md$/i.test(f))
    .map((name) => {
      const file = path.join(PATHS.FIX_PLANS_DIR, name);
      const id = name.replace(/^fix_plan_/i, '').replace(/\.md$/i, '');
      const parsed = parseFixPlan(fs.readFileSync(file, 'utf8'));
      return {
        id,
        planFile: file,
        relativePlanFile: rel(file),
        ...parsed,
      };
    })
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
}

/** Approved AND CWE-1104 — this skill's actual workload. A plan for any other CWE belongs to 04b-fixer. */
function listApprovedDependencyPlans() {
  return listFixPlans().filter((p) => p.status === 'Approved' && p.cwe === 'CWE-1104');
}

function resolveFixPlan(idOrPath) {
  if (!idOrPath) {
    throw new Error('Missing --issue. Pass an issue id (ISSUE-001) or --all for every Approved CWE-1104 fix plan in docs/agent_output/04-remediation/.');
  }
  const all = listFixPlans();
  const byId = all.find((p) => p.id.toLowerCase() === String(idOrPath).toLowerCase());
  if (byId) return byId;

  const asPath = path.isAbsolute(idOrPath) ? idOrPath : path.join(REPO_ROOT, idOrPath);
  const byPath = all.find((p) => path.resolve(p.planFile) === path.resolve(asPath));
  if (byPath) return byPath;

  throw new Error(
    `No fix plan for "${idOrPath}" in ${rel(PATHS.FIX_PLANS_DIR)}. `
    + `Available: ${all.map((p) => p.id).join(', ') || 'none — run the Fix Strategist agent first'}`
  );
}

// ---------------------------------------------------------------------------
// Path helpers
// ---------------------------------------------------------------------------

const patchPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.patch.diff`);
const rationalePathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.rationale.json`);
const verificationJsonPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.verification.json`);
const verificationMdPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.verification.md`);
const worktreePathFor = (id) => path.join(PATHS.WORKTREES_DIR, id);
const fixReportPathFor = (id) => path.join(PATHS.OUT_DIR, `fix_${id}.md`);
const fixDiffPathFor = (id) => path.join(PATHS.OUT_DIR, `fix_${id}.diff`);

module.exports = {
  ...PATHS,
  rel,
  readIfPresent,
  parseFixPlan,
  listFixPlans,
  listApprovedDependencyPlans,
  resolveFixPlan,
  patchPathFor,
  rationalePathFor,
  verificationJsonPathFor,
  verificationMdPathFor,
  worktreePathFor,
  fixReportPathFor,
  fixDiffPathFor,
};
