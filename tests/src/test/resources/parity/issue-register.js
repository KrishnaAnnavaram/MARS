/**
 * Parity driver: the legacy VRH 00-issue-register (register.js + xlsx.js), unchanged.
 *
 *   node issue-register.js list <issuesDir>          -> listIssues(issuesDir) + readTable of the workbook
 *   node issue-register.js synthetic <outDir>        -> writes an edge-case workbook with the legacy
 *                                                       writeSheet, then lists it
 */
const fs = require('fs');
const path = require('path');

const ROOT = process.env.HARNESS_ROOT || path.resolve(__dirname, '..', '..', '..', '..', '..');
const LIB = path.join(ROOT, 'legacy-sources', 'vulnerability-remediation-harness', '.github', 'skills',
  '00-issue-register', 'scripts', 'lib');
const register = require(path.join(LIB, 'register.js'));
const xlsx = require(path.join(LIB, 'xlsx.js'));

const SYNTHETIC = [
  ['issue_id', 'title', 'type', 'severity', 'status', 'reported_on', 'reported_by', 'affected_services',
    'affected_symbols', 'affected_files', 'entry_points', 'summary', 'affected_area', 'data_flow',
    'observed_behavior', 'expected_behavior', 'steps_to_reproduce', 'impact', 'detection_notes', 'extra_column'],
  ['ISSUE-10', 'Tenth', 'Vulnerability', 'High', 'Open', '2026-01-10', 'alice', 'svc-a, svc-b', '- Foo.bar\n* Baz.qux',
    'a/A.java\r\nb/B.java', '• GET /x', '  Summary text  ', '', 'param -> sink', 'observed', '', '', 'impact', 'CWE-22', 'x'],
  ['ISSUE-9', '', 'Vulnerability', 'Low', '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', ''],
  ['', 'Spacer row with no id', '', '', '', '', '', '', '', '', '', 'ignored', '', '', '', '', '', '', '', ''],
  ['ISSUE-002', 'Second & <escaped> "quoted"', 'Bug', 'Medium', 'Open', '', '', 'svc-a,,svc-c,', '', '', '',
    'Line one\nLine two', 'Area', '', '', 'expected', 'step 1\nstep 2', '', '', ''],
  ['ISSUE-1', ' Padded title ', 'Vulnerability', 'Critical', 'Open', '', '', '\n- svc-z\n', '', '', '', '', '', '', '', '', '', '', 'notes', ''],
  [],
  ['ISSUE-3', 'Non-breaking spaces ', 'Vulnerability', ' High', '', '', '', ' svc-nbsp , svc-b',
    '', '', '', ' Summary with NBSP ', '', '', '', '', '', '', '', ''],
  ['ISSUE-0100', 'Hundredth', '', '', '', '', '', '', '', '', '', 'Summary\twith tab', '', '', '', '', '', '', '', ''],
];

function strip(issue) {
  const { file, relativeFile, ...rest } = issue;
  return rest;
}

function main() {
  const [mode, dir] = process.argv.slice(2);
  if (mode === 'synthetic') {
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(register.registerPath(dir), xlsx.writeSheet({ rows: SYNTHETIC, wrapFrom: 11 }));
  } else if (mode !== 'list') {
    throw new Error(`unknown mode ${mode}`);
  }
  const file = register.registerPath(dir);
  const table = xlsx.readTable(fs.readFileSync(file));
  const issues = register.listIssues(dir).map(strip);
  process.stdout.write(JSON.stringify({ file, table, issues }));
}

main();
