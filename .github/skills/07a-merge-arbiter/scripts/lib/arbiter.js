/**
 * Merge Arbiter — shared path resolution and read-only access to every Phase C upstream report.
 * docs/agent_output/04-remediation/, docs/agent_output/05-verify/, docs/agent_output/06-test-gate/, docs/agent_output/06-test-gate/ and docs/agent_output/00-issues/ are all READ-ONLY input here.
 * Only .github/.pipeline-context/merge/ and docs/agent_output/07-ship/ are written by this skill.
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
  WORK_DIR: path.join(DATA_DIR, 'merge'),
  FIXES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  VERIFY_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '05-verify'),
  QA_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '06-test-gate'),
  BUILD_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '06-test-gate'),
  ISSUES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues'),
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '07-ship'),
  OUT_README: path.join(REPO_ROOT, 'docs', 'agent_output', '07-ship', 'README.md'),
  SCORING_FILE: path.join(SKILL_DIR, 'scoring.json'),
};

function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

function parseFixReport(text) {
  const title = /^##\s+(?!\d)(.+)$/m.exec(text);
  const status = /\|\s*\*\*Status\*\*\s*\|\s*([^|]+)\|/.exec(text);
  const cwe = /\|\s*\*\*CWE\*\*\s*\|\s*`([^`]+)`/.exec(text);
  return {
    title: title ? title[1].trim() : '(untitled)',
    status: status ? status[1].trim() : 'unknown',
    cwe: cwe ? cwe[1].trim() : null,
  };
}

// Merge arbiter never compiles anything itself — it only aggregates upstream reports. A fix drafted
// by Fixer as either Compiled or Compile Failed is eligible; only a Refused fix (no diff drafted at
// all) has nothing to aggregate. Whether an individual Compile-Failed fix can actually clear the
// merge bar is decided by the score/gate math against Step 1/2's real evidence, not by this filter.
const STEP3_ELIGIBLE_STATUSES = ['Compiled', 'Compile Failed'];

function listStep3Fixes() {
  if (!fs.existsSync(PATHS.FIXES_DIR)) return [];
  return fs
    .readdirSync(PATHS.FIXES_DIR)
    .filter((f) => /^fix_(?!plan_).+\.md$/i.test(f))
    .map((name) => {
      const file = path.join(PATHS.FIXES_DIR, name);
      const id = name.replace(/^fix_/i, '').replace(/\.md$/i, '');
      return { id, relativeFixReportFile: rel(file), ...parseFixReport(fs.readFileSync(file, 'utf8')) };
    })
    .filter((f) => STEP3_ELIGIBLE_STATUSES.includes(f.status))
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
}

/** Generic "At a glance" verdict/status extractor shared by every upstream report shape. */
function extractField(text, label) {
  const re = new RegExp(`\\|\\s*\\*\\*${label}\\*\\*\\s*\\|\\s*([^|]+)\\|`);
  const m = re.exec(text);
  return m ? m[1].trim() : null;
}

function readUpstream(id) {
  const files = {
    rescan: path.join(PATHS.VERIFY_DIR, `rescan_${id}.md`),
    redteam: path.join(PATHS.VERIFY_DIR, `redteam_${id}.md`),
    behavior: path.join(PATHS.VERIFY_DIR, `behavior_${id}.md`),
    qa: path.join(PATHS.QA_DIR, `qa_${id}.md`),
    build: path.join(PATHS.BUILD_DIR, `build_${id}.md`),
  };
  const out = {};
  for (const [name, file] of Object.entries(files)) {
    const text = readIfPresent(file);
    out[name] = {
      present: Boolean(text),
      file: rel(file),
      verdict: text ? (extractField(text, 'Verdict') || extractField(text, 'Status')) : null,
    };
  }
  return out;
}

function readIssueSeverity(id) {
  const issue = register.readIssue(PATHS.ISSUES_DIR, id, rel);
  return issue ? issue.severity : null;
}

function loadScoring() {
  return JSON.parse(fs.readFileSync(PATHS.SCORING_FILE, 'utf8'));
}

const scorePathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.score.json`);
const arbitrationPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.arbitration.json`);
const verdictPathFor = (id) => path.join(PATHS.OUT_DIR, `verdict_${id}.md`);

module.exports = {
  ...PATHS,
  rel,
  readIfPresent,
  parseFixReport,
  STEP3_ELIGIBLE_STATUSES,
  listStep3Fixes,
  readUpstream,
  readIssueSeverity,
  loadScoring,
  scorePathFor,
  arbitrationPathFor,
  verdictPathFor,
};
