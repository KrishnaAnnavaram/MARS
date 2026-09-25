/**
 * Root Cause Analyst — shared issue-register access.
 *
 * The issue register (docs/agent_output/00-issues/issue-register.xlsx) is READ-ONLY input,
 * supplied by whoever reports the issue. Nothing in this skill writes to it. Reading
 * and parsing the spreadsheet is owned by the 00-issue-register skill so the column
 * contract lives in exactly one place; these helpers wrap it and resolve the paths of
 * everything else the pipeline reads and writes.
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
  RCA_DIR: path.join(DATA_DIR, 'rca'),
  ISSUES_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues'),
  ISSUE_REGISTER_FILE: register.registerPath(path.join(REPO_ROOT, 'docs', 'agent_output', '00-issues')),
  ARCHITECTURE_MD: path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'architecture.md'),
  FUNCTION_REFERENCE_MD: path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'function-reference.md'),
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '02-root-cause'),
};

/** Repo-relative, forward-slashed — for display and for markdown links. */
function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

// ---------------------------------------------------------------------------
// Front matter — supported subset: scalars, inline arrays, block sequences of scalars
// ---------------------------------------------------------------------------

function stripQuotes(value) {
  const v = value.trim();
  if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
    return v.slice(1, -1);
  }
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
      data[key] = value.slice(1, -1).split(',').map((v) => stripQuotes(v)).filter(Boolean);
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
// Register
// ---------------------------------------------------------------------------

/**
 * Every issue in the register spreadsheet, sorted by id. Rows without an `issue_id`
 * (blank spacers, reporter notes) are not issues and are skipped.
 *
 * Each object carries a `body` synthesized from the register's long-text columns,
 * using the same `## Section` headings this pipeline has always extracted.
 */
function listIssues() {
  return register.listIssues(PATHS.ISSUES_DIR, rel);
}

/** Accepts an issue id (ISSUE-001) and returns the full issue object. */
function resolveIssue(issueArg) {
  return register.resolveIssue(PATHS.ISSUES_DIR, issueArg, rel);
}

function evidencePathFor(issueId) {
  return path.join(PATHS.RCA_DIR, `${issueId}.evidence.json`);
}

function analysisPathFor(issueId) {
  return path.join(PATHS.RCA_DIR, `${issueId}.analysis.json`);
}

function reportPathFor(issueId) {
  return path.join(PATHS.OUT_DIR, `root_cause_${issueId}.md`);
}

module.exports = {
  ...PATHS,
  rel,
  parseFrontMatter,
  asArray,
  listIssues,
  resolveIssue,
  evidencePathFor,
  analysisPathFor,
  reportPathFor,
};
