/**
 * Scribe — shared path resolution and read-only access to the entire chain of custody. Every
 * docs/ folder this skill reads from is read-only input; only .github/.pipeline-context/scribe/ and
 * docs/agent_output/07-ship/{pr,audit}_<id>.md are written here. Scribe never touches git or GitHub — its output
 * is content for a human to act on, always written, regardless of the merge arbiter's decision.
 */
const fs = require('fs');
const path = require('path');
const register = require('../../../00-issue-register/scripts/lib/register');

const SKILL_DIR = path.resolve(__dirname, '..', '..');
const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');

const PATHS = {
  SKILL_DIR,
  REPO_ROOT,
  DATA_DIR,
  WORK_DIR: path.join(DATA_DIR, 'scribe'),
  ISSUES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues'),
  ROOT_CAUSE_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '02-root-cause'),
  BLAST_RADIUS_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '03-blast-radius'),
  FIX_PLANS_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  FIXES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  VERIFY_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '05-verify'),
  QA_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '06-test-gate'),
  BUILD_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '06-test-gate'),
  SHIP_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '07-ship'),
  OUT_README: path.join(REPO_ROOT, 'docs', 'agent_output', '07-ship', 'README.md'),
};

function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

function extractField(text, label) {
  if (!text) return null;
  const re = new RegExp(`\\|\\s*\\*\\*${label}\\*\\*\\s*\\|\\s*([^|]+)\\|`);
  const m = re.exec(text);
  return m ? m[1].trim() : null;
}

function titleOf(text) {
  if (!text) return null;
  const m = /^##\s+(?!\d)(.+)$/m.exec(text);
  return m ? m[1].trim() : null;
}

/** Every fix that has a rendered merge-arbiter verdict — that defines the scribe's workload. */
function listArbitratedFixes() {
  if (!fs.existsSync(PATHS.SHIP_DIR)) return [];
  return fs
    .readdirSync(PATHS.SHIP_DIR)
    .filter((f) => /^verdict_.+\.md$/i.test(f))
    .map((name) => {
      const id = name.replace(/^verdict_/i, '').replace(/\.md$/i, '');
      const file = path.join(PATHS.SHIP_DIR, name);
      const text = fs.readFileSync(file, 'utf8');
      return {
        id,
        verdictFile: file,
        relativeVerdictFile: rel(file),
        title: titleOf(text) || id,
        decision: extractField(text, 'Decision'),
        score: extractField(text, 'Score'),
      };
    })
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
}

function resolveFix(idOrPath) {
  if (!idOrPath) throw new Error('Missing --issue. Pass an issue id (ISSUE-001) or --all for every fix with a rendered merge-arbiter verdict.');
  const all = listArbitratedFixes();
  const byId = all.find((f) => f.id.toLowerCase() === String(idOrPath).toLowerCase());
  if (byId) return byId;
  throw new Error(`No ship verdict for "${idOrPath}". Available: ${all.map((f) => f.id).join(', ') || 'none — run the merge-arbiter agent first'}`);
}

/** Gathers everything the Scribe needs to read, across the entire pipeline, all read-only. */
function chainOfCustodyFor(id) {
  // The register row, rendered back to markdown so the audit trail quotes the issue
  // exactly the way every upstream stage saw it.
  const issue = register.readIssue(PATHS.ISSUES_DIR, id, rel);

  return {
    issue: issue ? { file: issue.relativeFile, text: issue.body } : null,
    rootCause: fileIfExists(path.join(PATHS.ROOT_CAUSE_DIR, `root_cause_${id}.md`)),
    blastRadius: fileIfExists(path.join(PATHS.BLAST_RADIUS_DIR, `blast_radius_${id}.md`)),
    fixPlan: fileIfExists(path.join(PATHS.FIX_PLANS_DIR, `fix_plan_${id}.md`)),
    fix: fileIfExists(path.join(PATHS.FIXES_DIR, `fix_${id}.md`)),
    fixDiff: fileIfExists(path.join(PATHS.FIXES_DIR, `fix_${id}.diff`)),
    rescan: fileIfExists(path.join(PATHS.VERIFY_DIR, `rescan_${id}.md`)),
    redteam: fileIfExists(path.join(PATHS.VERIFY_DIR, `redteam_${id}.md`)),
    behavior: fileIfExists(path.join(PATHS.VERIFY_DIR, `behavior_${id}.md`)),
    qa: fileIfExists(path.join(PATHS.QA_DIR, `qa_${id}.md`)),
    build: fileIfExists(path.join(PATHS.BUILD_DIR, `build_${id}.md`)),
    verdict: fileIfExists(path.join(PATHS.SHIP_DIR, `verdict_${id}.md`)),
  };
}

function fileIfExists(abs) {
  if (!fs.existsSync(abs)) return null;
  return { file: rel(abs), text: fs.readFileSync(abs, 'utf8') };
}

const factsPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.chain.facts.md`);
const contentPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.content.json`);
const verdictPathFor = (id) => path.join(PATHS.SHIP_DIR, `verdict_${id}.md`);
const prPathFor = (id) => path.join(PATHS.SHIP_DIR, `pr_${id}.md`);
const auditPathFor = (id) => path.join(PATHS.SHIP_DIR, `audit_${id}.md`);

module.exports = {
  ...PATHS,
  rel,
  readIfPresent,
  extractField,
  titleOf,
  listArbitratedFixes,
  resolveFix,
  chainOfCustodyFor,
  factsPathFor,
  contentPathFor,
  verdictPathFor,
  prPathFor,
  auditPathFor,
};
