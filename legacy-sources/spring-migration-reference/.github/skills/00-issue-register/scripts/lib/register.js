/**
 * Issue register — the pipeline's single input, read from Excel.
 *
 * The register lives at docs/agent_output/00-issues/issue-register.xlsx, one row per
 * reported vulnerability. It is READ-ONLY input: whoever reports the issue owns
 * that file, and nothing in this pipeline writes to it.
 *
 * Six skills consume it (02, 03, 04, 06, 11, 12). They all go through this module
 * so the column contract is defined in exactly one place.
 *
 * Every issue object carries a synthesized markdown `body` built from the long-text
 * columns, using the same `## Section` headings the register's markdown predecessor
 * used. That is deliberate: downstream skills extract `## Summary`,
 * `## Observed Behavior` and `## Detection Notes` by regex, and they keep working
 * against the spreadsheet without knowing the source changed.
 */
const fs = require('fs');
const path = require('path');
const { readTable } = require('./xlsx');

const REGISTER_FILENAME = 'issue-register.xlsx';

/** Scalar columns -> issue field. */
const SCALARS = {
  issue_id: 'id',
  title: 'title',
  type: 'type',
  severity: 'severity',
  status: 'status',
  reported_on: 'reportedOn',
  reported_by: 'reportedBy',
};

/** List columns — newline- or comma-separated inside a single cell. */
const LISTS = {
  affected_services: 'services',
  affected_symbols: 'symbols',
  affected_files: 'files',
  entry_points: 'entryPoints',
};

/** Long-text columns, in the order they are rendered into the markdown body. */
const SECTIONS = [
  ['summary', 'Summary'],
  ['affected_area', 'Affected Area'],
  ['data_flow', 'Data Flow (source → sink)'],
  ['observed_behavior', 'Observed Behavior'],
  ['expected_behavior', 'Expected Behavior'],
  ['steps_to_reproduce', 'Steps to Reproduce'],
  ['impact', 'Impact'],
  ['detection_notes', 'Detection Notes'],
];

const COLUMNS = [...Object.keys(SCALARS), ...Object.keys(LISTS), ...SECTIONS.map((s) => s[0])];

/** Split a list cell on newlines or commas, trimming bullet markers. */
function splitList(cell) {
  if (!cell) return [];
  return String(cell)
    .split(/\r?\n|,/)
    .map((v) => v.trim().replace(/^[-*•]\s*/, ''))
    .filter(Boolean);
}

function registerPath(issuesDir) {
  return path.join(issuesDir, REGISTER_FILENAME);
}

/**
 * Rebuilds the markdown body from the spreadsheet's long-text columns.
 * Section headings and blank-line placement must stay exactly as written — the
 * downstream extractors match on them.
 */
function synthesizeBody(row, id, title) {
  const out = [`# ${id} — ${title}`, ''];
  for (const [column, heading] of SECTIONS) {
    const text = (row[column] || '').trim();
    if (!text) continue;
    out.push(`## ${heading}`, '', text, '');
  }
  return out.join('\n');
}

function toIssue(row, file, relFile) {
  const issue = { raw: row };
  for (const [column, field] of Object.entries(SCALARS)) {
    issue[field] = (row[column] || '').trim() || null;
  }
  for (const [column, field] of Object.entries(LISTS)) {
    issue[field] = splitList(row[column]);
  }
  issue.title = issue.title || '(untitled)';
  issue.file = file;
  issue.relativeFile = relFile;
  issue.body = synthesizeBody(row, issue.id, issue.title);
  // Mirrors the old front-matter map, so callers that read `data.<key>` still work.
  issue.data = {
    issue_id: issue.id,
    title: issue.title,
    type: issue.type,
    severity: issue.severity,
    status: issue.status,
    reported_on: issue.reportedOn,
    reported_by: issue.reportedBy,
    affected_services: issue.services,
    affected_symbols: issue.symbols,
    affected_files: issue.files,
    entry_points: issue.entryPoints,
  };
  return issue;
}

/**
 * Every issue in the register, sorted by id. Rows without an `issue_id` are
 * ignored (blank spacer rows, notes a reporter left behind).
 */
function listIssues(issuesDir, relFn) {
  const file = registerPath(issuesDir);
  if (!fs.existsSync(file)) return [];

  const { headers, rows } = readTable(fs.readFileSync(file));
  if (!headers.includes('issue_id')) {
    throw new Error(
      `${file} has no "issue_id" column. Expected columns: ${COLUMNS.join(', ')}.`
    );
  }
  const relFile = relFn ? relFn(file) : file;

  return rows
    .filter((r) => (r.issue_id || '').trim())
    .map((r) => toIssue(r, file, relFile))
    .sort((a, b) => a.id.localeCompare(b.id, undefined, { numeric: true }));
}

/** One issue by id, or null. Case-insensitive. */
function readIssue(issuesDir, issueId, relFn) {
  if (!issueId) return null;
  return listIssues(issuesDir, relFn)
    .find((i) => i.id.toLowerCase() === String(issueId).toLowerCase()) || null;
}

/** Like readIssue, but throws a message naming what is actually available. */
function resolveIssue(issuesDir, issueId, relFn) {
  if (!issueId) {
    throw new Error('Missing --issue. Pass an issue id (ISSUE-001) or --all for every issue in the register.');
  }
  const issues = listIssues(issuesDir, relFn);
  const match = issues.find((i) => i.id.toLowerCase() === String(issueId).toLowerCase());
  if (match) return match;
  throw new Error(
    `No issue with issue_id "${issueId}" in ${registerPath(issuesDir)}. `
    + `Available: ${issues.map((i) => i.id).join(', ') || 'none — the register is empty'}`
  );
}

module.exports = {
  REGISTER_FILENAME,
  COLUMNS,
  SECTIONS,
  registerPath,
  splitList,
  synthesizeBody,
  listIssues,
  readIssue,
  resolveIssue,
};
