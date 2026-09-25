/**
 * Parity driver: VRH 07a compute-score.js, run unchanged in-process with `--all`.
 *
 *   node arbiter.js <cases.json> <dataDir>
 *     cases: [{ id, severity, fixStatus, rescan, redteam, behavior, qa, build }]
 *
 * compute-score reads its upstream reports and the issue register from fixed folders under the
 * legacy repository's docs/agent_output/. Those reads are served from memory here (the register
 * is written by the legacy xlsx writer), so nothing is written into legacy-sources/; the score
 * files land in <dataDir>/merge via PIPELINE_CONTEXT_DATA_DIR. Prints every <id>.score.json.
 */
const fs = require('fs');
const path = require('path');
const { vrh, emit, readJson } = require('./legacy');

const [casesFile, dataDir] = process.argv.slice(2);
process.env.PIPELINE_CONTEXT_DATA_DIR = dataDir;

const SKILL = vrh('07a-merge-arbiter');
const arbiter = require(path.join(SKILL, 'scripts', 'lib', 'arbiter.js'));
const xlsx = require(vrh('00-issue-register', 'scripts', 'lib', 'xlsx.js'));

const cases = readJson(casesFile);
const virtualRoot = path.resolve(arbiter.REPO_ROOT, 'docs', 'agent_output');
const files = new Map();
const key = (p) => path.resolve(String(p)).toLowerCase();
const put = (file, content) => files.set(key(file), content);

for (const c of cases) {
  put(path.join(arbiter.FIXES_DIR, `fix_${c.id}.md`),
    `# Fix report\n\n## ${c.id} fix\n\n| Field | Value |\n|---|---|\n| **Status** | ${c.fixStatus} |\n| **CWE** | \`CWE-89\` |\n`);
  const verdict = (v) => `| Field | Value |\n|---|---|\n| **Verdict** | ${v} |\n`;
  const status = (v) => `| Field | Value |\n|---|---|\n| **Status** | ${v} |\n`;
  put(path.join(arbiter.VERIFY_DIR, `rescan_${c.id}.md`), verdict(c.rescan));
  put(path.join(arbiter.VERIFY_DIR, `redteam_${c.id}.md`), verdict(c.redteam));
  put(path.join(arbiter.VERIFY_DIR, `behavior_${c.id}.md`), verdict(c.behavior));
  put(path.join(arbiter.QA_DIR, `qa_${c.id}.md`), status(c.qa));
  put(path.join(arbiter.BUILD_DIR, `build_${c.id}.md`), status(c.build));
}
put(path.join(arbiter.ISSUES_DIR, 'issue-register.xlsx'), xlsx.writeSheet({
  rows: [['issue_id', 'title', 'severity'], ...cases.map((c) => [c.id, `${c.id} title`, c.severity || ''])],
}));

const inVirtualRoot = (p) => key(p).startsWith(`${virtualRoot.toLowerCase()}${path.sep}`) || key(p) === virtualRoot.toLowerCase();
const real = { existsSync: fs.existsSync, readFileSync: fs.readFileSync, readdirSync: fs.readdirSync };
fs.existsSync = (p) => {
  if (!inVirtualRoot(p)) return real.existsSync(p);
  const k = key(p);
  return files.has(k) || [...files.keys()].some((f) => f.startsWith(`${k}${path.sep}`));
};
fs.readFileSync = (p, options) => {
  if (!inVirtualRoot(p)) return real.readFileSync(p, options);
  const content = files.get(key(p));
  if (content === undefined) throw Object.assign(new Error(`ENOENT: ${p}`), { code: 'ENOENT' });
  const buf = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
  const encoding = typeof options === 'string' ? options : options && options.encoding;
  return encoding ? buf.toString(encoding) : buf;
};
fs.readdirSync = (p, options) => {
  if (!inVirtualRoot(p)) return real.readdirSync(p, options);
  const prefix = `${key(p)}${path.sep}`;
  const names = new Set();
  for (const f of files.keys()) {
    if (f.startsWith(prefix)) names.add(f.slice(prefix.length).split(path.sep)[0]);
  }
  // The virtual keys are lower-cased; hand back the ids as the cases spell them.
  const byLower = new Map();
  for (const c of cases) byLower.set(`fix_${c.id}.md`.toLowerCase(), `fix_${c.id}.md`);
  return [...names].map((n) => byLower.get(n) || n);
};

const log = console.log;
const error = console.error;
console.log = () => {};
console.error = () => {};
process.argv = [process.argv[0], path.join(SKILL, 'scripts', 'compute-score.js'), '--all'];
try {
  require(path.join(SKILL, 'scripts', 'compute-score.js'));
} finally {
  console.log = log;
  console.error = error;
}

const scores = {};
for (const c of cases) {
  const file = arbiter.scorePathFor(c.id);
  scores[c.id] = real.existsSync(file) ? readJson(file) : null;
}
emit({ exitCode: process.exitCode || 0, scoring: arbiter.loadScoring(), scores });
