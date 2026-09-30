/**
 * Fix Strategist — shared path resolution and read-only access to upstream reports.
 *
 * The workload is the set of root cause reports in docs/agent_output/02-root-cause/: one fix plan per root
 * cause. A blast radius report for the same id is read if present, but is not required.
 * docs/agent_output/00-issues/, docs/agent_output/02-root-cause/ and docs/agent_output/03-blast-radius/ are all READ-ONLY input here — nothing
 * in this skill writes to them.
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
  WORK_DIR: path.join(DATA_DIR, 'fix-strategy'),
  ISSUES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues'),
  ROOT_CAUSE_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '02-root-cause'),
  BLAST_RADIUS_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '03-blast-radius'),
  ARCHITECTURE_MD: path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'architecture.md'),
  FUNCTION_REFERENCE_MD: path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'function-reference.md'),
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  OUT_README: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation', 'README.md'),
  CATALOG_FILE: path.join(SKILL_DIR, 'catalog', 'cwe-patterns.json'),
};

function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

function readIfPresent(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

// ---------------------------------------------------------------------------
// Front matter — scalars, inline arrays, block sequences of scalars
// (same hand-rolled subset used by root-cause-analyst and blast-radius-analyst)
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
// Root cause reports — the workload (no front matter; parsed like blast-radius-analyst does)
// ---------------------------------------------------------------------------

function parseRootCauseReport(text) {
  const out = { title: null, statement: null, defectLocation: null, severity: null, confidence: null };

  const title = /^##\s+(?!\d)(.+)$/m.exec(text);
  if (title) out.title = title[1].trim();

  const section = /##\s+\d*\.?\s*Where the defect is\s*\r?\n([\s\S]*?)(?=\r?\n##\s)/.exec(text);
  if (section) {
    const statement = /^>\s*\*\*(.+?)\*\*\s*$/m.exec(section[1]);
    if (statement) out.statement = statement[1].trim();
    const location = /\*\*Location:\*\*\s*`([^`]+)`/.exec(section[1]);
    if (location) out.defectLocation = location[1].trim();
  }

  const severity = /\|\s*\*\*Severity\*\*\s*\|\s*([^|]+)\|/.exec(text);
  if (severity) out.severity = severity[1].trim();

  const confidence = /\|\s*\*\*Confidence\*\*\s*\|\s*([^|]+)\|/.exec(text);
  if (confidence) out.confidence = confidence[1].trim();

  const fixSection = /##\s+\d*\.?\s*How to fix it\s*\r?\n\r?\n([\s\S]*?)(?=\r?\n##\s)/.exec(text);
  if (fixSection) out.recommendedFix = fixSection[1].trim();

  return out;
}

function listRootCauseReports() {
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

function resolveRootCauseReport(idOrPath) {
  if (!idOrPath) {
    throw new Error('Missing --issue. Pass an issue id (ISSUE-001) or --all for every root cause report in docs/agent_output/02-root-cause/.');
  }
  const all = listRootCauseReports();
  const byId = all.find((r) => r.id.toLowerCase() === String(idOrPath).toLowerCase());
  if (byId) return byId;

  const asPath = path.isAbsolute(idOrPath) ? idOrPath : path.join(REPO_ROOT, idOrPath);
  const byPath = all.find((r) => path.resolve(r.reportFile) === path.resolve(asPath));
  if (byPath) return byPath;

  throw new Error(
    `No root cause report for "${idOrPath}" in ${rel(PATHS.ROOT_CAUSE_DIR)}. `
    + `Available: ${all.map((r) => r.id).join(', ') || 'none — run the Root Cause Analyst agent first'}`
  );
}

// ---------------------------------------------------------------------------
// Blast radius reports — optional companion input (also read-only, also no front matter)
// ---------------------------------------------------------------------------

function parseBlastRadiusReport(text) {
  const out = { headline: null, priority: null, scope: null, confidence: null };

  const headline = /^>\s*(.+)$/m.exec(text);
  if (headline) out.headline = headline[1].trim();

  const priority = /\|\s*\*\*Priority\*\*\s*\|\s*([^|]+)\|/.exec(text);
  if (priority) out.priority = priority[1].trim();

  const scope = /\|\s*\*\*How far it spreads\*\*\s*\|\s*([^|]+)\|/.exec(text);
  if (scope) out.scope = scope[1].trim();

  const confidence = /\|\s*\*\*Confidence\*\*\s*\|\s*([^|]+)\|/.exec(text);
  if (confidence) out.confidence = confidence[1].trim();

  return out;
}

/** null if no blast radius report exists for this id — that is expected, not an error. */
function readBlastRadiusReport(id) {
  const file = path.join(PATHS.BLAST_RADIUS_DIR, `blast_radius_${id}.md`);
  if (!fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8');
  return { file, relativeFile: rel(file), ...parseBlastRadiusReport(text) };
}

// ---------------------------------------------------------------------------
// CWE catalog
// ---------------------------------------------------------------------------

function loadCatalog() {
  if (!fs.existsSync(PATHS.CATALOG_FILE)) return {};
  const raw = JSON.parse(fs.readFileSync(PATHS.CATALOG_FILE, 'utf8'));
  const { $comment, ...entries } = raw;
  return entries;
}

/** Every distinct CWE-<n> mention in the given text, in order of first appearance. */
function detectCweMentions(text) {
  if (!text) return [];
  const seen = [];
  const re = /CWE-\d+/gi;
  let m;
  while ((m = re.exec(text))) {
    const id = m[0].toUpperCase();
    if (!seen.includes(id)) seen.push(id);
  }
  return seen;
}

// ---------------------------------------------------------------------------
// This skill's own output — read back only to avoid clobbering a human approval
// ---------------------------------------------------------------------------

/**
 * If a fix plan already exists, read its Status cell. Used so re-running the
 * Strategist after a human has approved (or rejected) a plan does not silently
 * reset that decision back to Proposed.
 */
function existingPlanStatus(id) {
  const file = planPathFor(id);
  if (!fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8');
  const status = /\|\s*\*\*Status\*\*\s*\|\s*([^|]+)\|/.exec(text);
  return status ? status[1].trim() : null;
}

// ---------------------------------------------------------------------------
// Fix-strategy path helpers
// ---------------------------------------------------------------------------

const contextJsonPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.context.json`);
const contextBriefingPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.context.md`);
const strategyPathFor = (id) => path.join(PATHS.WORK_DIR, `${id}.strategy.json`);
const planPathFor = (id) => path.join(PATHS.OUT_DIR, `fix_plan_${id}.md`);

module.exports = {
  ...PATHS,
  rel,
  readIfPresent,
  parseFrontMatter,
  asArray,
  readIssue,
  parseRootCauseReport,
  listRootCauseReports,
  resolveRootCauseReport,
  parseBlastRadiusReport,
  readBlastRadiusReport,
  loadCatalog,
  detectCweMentions,
  existingPlanStatus,
  contextJsonPathFor,
  contextBriefingPathFor,
  strategyPathFor,
  planPathFor,
};
