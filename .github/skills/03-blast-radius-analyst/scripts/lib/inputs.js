/**
 * Blast Radius Analyst — shared input access.
 *
 * The workload is the set of root cause reports in docs/agent_output/02-root-cause/: one blast radius
 * report per root cause. Issues and root cause reports are READ-ONLY input — nothing in
 * this skill writes to docs/agent_output/00-issues/ or docs/agent_output/02-root-cause/.
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
  ARTIFACTS_FILE: path.join(DATA_DIR, 'artifacts.json'),
  WORK_DIR: path.join(DATA_DIR, 'blast-radius'),
  ISSUES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues'),
  ROOT_CAUSE_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '02-root-cause'),
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '03-blast-radius'),
  ARCHITECTURE_MD: path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'architecture.md'),
  FUNCTION_REFERENCE_MD: path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'function-reference.md'),
};

function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

// ---------------------------------------------------------------------------
// Front matter — scalars, inline arrays, block sequences of scalars
// ---------------------------------------------------------------------------

function stripQuotes(value) {
  const v = value.trim();
  if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) return v.slice(1, -1);
  return v;
}

function parseFrontMatter(text) {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!match) return { data: {}, body: text };

  const data = {};
  let currentKey = null;
  for (const rawLine of match[1].split(/\r?\n/)) {
    if (!rawLine.trim() || rawLine.trim().startsWith('#')) continue;

    const item = /^\s*-\s+(.*)$/.exec(rawLine);
    if (item && currentKey) {
      data[currentKey].push(stripQuotes(item[1]));
      continue;
    }
    const pair = /^([A-Za-z0-9_]+):\s*(.*)$/.exec(rawLine);
    if (!pair) continue;
    const [, key, rawValue] = pair;
    const value = rawValue.trim();

    if (value === '') {
      data[key] = [];
      currentKey = key;
    } else if (value.startsWith('[') && value.endsWith(']')) {
      data[key] = value.slice(1, -1).split(',').map(stripQuotes).filter(Boolean);
      currentKey = null;
    } else {
      data[key] = stripQuotes(value);
      currentKey = null;
    }
  }
  return { data, body: match[2] };
}

function asArray(value) {
  if (!value) return [];
  return Array.isArray(value) ? value.filter(Boolean) : [value].filter(Boolean);
}

// ---------------------------------------------------------------------------
// Issue register (read-only)
// ---------------------------------------------------------------------------

/** One row of the register spreadsheet, or null. Read-only input. */
function readIssue(issueId) {
  return register.readIssue(PATHS.ISSUES_DIR, issueId, rel);
}

// ---------------------------------------------------------------------------
// Root cause reports — the workload
// ---------------------------------------------------------------------------

/** Pull the facts a blast radius report needs to quote from a root cause report. */
function parseRootCauseReport(text) {
  const out = { title: null, statement: null, defectLocation: null, severity: null, confidence: null, summary: null };

  const title = /^##\s+(?!\d)(.+)$/m.exec(text);
  if (title) out.title = title[1].trim();

  const section = /##\s+\d*\.?\s*Root Cause\s*\r?\n([\s\S]*?)(?=\r?\n##\s)/.exec(text);
  if (section) {
    const statement = /^>\s*\*\*(.+?)\*\*\s*$/m.exec(section[1]);
    if (statement) out.statement = statement[1].trim();
    const location = /\*\*Defect location:\*\*\s*`([^`]+)`/.exec(section[1]);
    if (location) out.defectLocation = location[1].trim();
  }

  const summary = /##\s+\d*\.?\s*Summary\s*\r?\n\r?\n([\s\S]*?)(?=\r?\n##\s)/.exec(text);
  if (summary) out.summary = summary[1].trim();

  const severity = /\|\s*Type \/ Severity\s*\|\s*([^|]+)\|/.exec(text);
  if (severity) out.severity = severity[1].trim();

  const confidence = /\|\s*Analysis confidence\s*\|\s*([^|]+)\|/.exec(text);
  if (confidence) out.confidence = confidence[1].trim();

  return out;
}

/**
 * Every root cause report in docs/agent_output/02-root-cause/, sorted by issue id. This is the
 * workload: one blast radius report per root cause, no more and no fewer.
 */
function listRootCauses() {
  if (!fs.existsSync(PATHS.ROOT_CAUSE_DIR)) return [];
  return fs
    .readdirSync(PATHS.ROOT_CAUSE_DIR)
    .filter((f) => /^root_cause_.+\.md$/i.test(f))
    .map((name) => {
      const file = path.join(PATHS.ROOT_CAUSE_DIR, name);
      const id = name.replace(/^root_cause_/i, '').replace(/\.md$/i, '');
      const text = fs.readFileSync(file, 'utf8');
      const parsed = parseRootCauseReport(text);
      const issue = readIssue(id);
      return {
        id,
        title: (issue && issue.title) || parsed.title || '(untitled)',
        severity: (issue && issue.severity) || parsed.severity || null,
        rootCause: parsed,
        reportFile: file,
        relativeReportFile: rel(file),
        issue,
      };
    })
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
}

function resolveRootCause(idOrPath) {
  if (!idOrPath) {
    throw new Error('Missing --issue. Pass an issue id (ISSUE-001) or --all for every root cause report in docs/agent_output/02-root-cause/.');
  }
  const all = listRootCauses();
  const byId = all.find((r) => r.id.toLowerCase() === String(idOrPath).toLowerCase());
  if (byId) return byId;

  const asPath = path.isAbsolute(idOrPath) ? idOrPath : path.join(REPO_ROOT, idOrPath);
  const byPath = all.find((r) => path.resolve(r.reportFile) === path.resolve(asPath));
  if (byPath) return byPath;

  throw new Error(
    `No root cause report for "${idOrPath}" in ${rel(PATHS.ROOT_CAUSE_DIR)}. ` +
    `Available: ${all.map((r) => r.id).join(', ') || 'none — run the Root Cause Analyst agent first'}`
  );
}

const factsPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.facts.json`);
const briefingPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.facts.md`);
const narrativePathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.narrative.json`);
const reportPathFor = (id) => path.join(PATHS.OUT_DIR, `blast_radius_${id}.md`);

module.exports = {
  ...PATHS,
  rel,
  readIfPresent,
  parseFrontMatter,
  asArray,
  readIssue,
  parseRootCauseReport,
  listRootCauses,
  resolveRootCause,
  factsPathFor,
  briefingPathFor,
  narrativePathFor,
  reportPathFor,
};
