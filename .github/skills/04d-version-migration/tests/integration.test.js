/**
 * Agent 04 integration: pack eligibility by declared source version, the migration-path gate, the
 * --issue entry point (Stage 2 of 04_fix-generator), the standard fix_<id>.md handoff, and the
 * per-run summary every script writes.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const {
  SKILL_DIR, SAMPLE_PROJECT, tempRoot, copyDir, envFor, runScript, sessionDir, fakeMaven, installLegacySession,
} = require('./helpers');

const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');

/** A copy of the fixture project with its parent version (or the whole <parent>) rewritten. */
function fixtureWithParent(root, version) {
  const dir = path.join(root, 'project');
  copyDir(SAMPLE_PROJECT, dir);
  const pom = path.join(dir, 'pom.xml');
  let text = fs.readFileSync(pom, 'utf8');
  text = version === null
    ? text.replace(/<parent>[\s\S]*?<\/parent>/, '')
    : text.replace('<version>3.5.0</version>', `<version>${version}</version>`);
  fs.writeFileSync(pom, text);
  return dir;
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function summaryFor(root) {
  const runs = path.join(root, 'reports', 'migration-runs');
  const [runId] = fs.existsSync(runs) ? fs.readdirSync(runs) : [];
  if (!runId) return null;
  return {
    json: readJson(path.join(runs, runId, 'migration-summary.json')),
    md: fs.readFileSync(path.join(runs, runId, 'MIGRATION_SUMMARY.md'), 'utf8'),
  };
}

test('13. forcing the 3→4 pack on a Boot 2.x source: never eligible, one session never spans two generations, BLOCKED, summary written', () => {
  const root = tempRoot('eligibility');
  const project = fixtureWithParent(root, '2.7.12');
  const env = envFor(root, { MIGRATION_MVN: fakeMaven(path.join(root, 'bin')) });
  // Pack mode (--reference skips the ladder): the B1 regression — a starter must not make the pack eligible.
  const result = runScript('detect-baseline.js', ['--project', project, '--slug', 'boot2', '--to-java', '21', '--to-version', '4.1.1', '--reference', 'spring-boot-3-to-4'], env);
  assert.equal(result.status, 2, result.out);
  const baseline = readJson(path.join(sessionDir(root, 'boot2'), 'baseline.json'));
  assert.deepEqual(baseline.reference_packs, [], 'the 3→4 pack must not be selected for a 2.x source');
  const e = baseline.reference_pack_eligibility.find((x) => x.pack === 'spring-boot-3-to-4');
  assert.equal(e.eligible, false);
  assert.equal(e.relation, 'behind');
  assert.equal(e.source_platform.version, '2.7.12');
  assert.ok(e.matched_on.includes('org.springframework.boot:spring-boot-starter-web'), 'the starter still nominates the pack');
  // Both generation packs exist now, so pack mode reports that the jump needs two sessions (the
  // ladder, without --reference, plans it as one path of edges instead — see ladder.test.js).
  assert.equal(baseline.migration_path.status, 'MULTI_STEP_REQUIRED');
  assert.deepEqual(baseline.migration_path.missing_capability, []);
  assert.deepEqual(baseline.migration_path.required_path.steps.map((s) => [s.capability, s.available]), [['spring-boot-2-to-3', true], ['spring-boot-3-to-4', true]]);
  assert.equal(readJson(path.join(sessionDir(root, 'boot2'), 'state.json')).state, 'BLOCKED');

  const prepare = runScript('prepare-workspace.js', ['--slug', 'boot2'], env);
  assert.equal(prepare.status, 1, 'a blocked baseline never gets a sandbox');
  assert.ok(!fs.existsSync(path.join(sessionDir(root, 'boot2'), 'workspace')));

  const summary = summaryFor(root);
  assert.ok(summary, 'MIGRATION_SUMMARY written even though the run was blocked');
  assert.equal(summary.json.final_status, 'BLOCKED');
  assert.match(summary.md, /MULTI_STEP_REQUIRED/);
});

test('13b. a Boot 3.x source stays eligible and SUPPORTED; a Boot 3 project without a versioned platform is not', () => {
  const root = tempRoot('eligibility3');
  const env = envFor(root, { MIGRATION_MVN: fakeMaven(path.join(root, 'bin')) });
  const ok = runScript('detect-baseline.js', ['--project', SAMPLE_PROJECT, '--slug', 'boot3', '--to-java', '21', '--to-version', '4.1.1'], env);
  assert.equal(ok.status, 0, ok.out);
  const baseline = readJson(path.join(sessionDir(root, 'boot3'), 'baseline.json'));
  assert.equal(baseline.reference_packs[0].id, 'spring-boot-3-to-4');
  assert.equal(baseline.migration_path.status, 'SUPPORTED');
  assert.equal(summaryFor(root).json.final_status, 'IN_PROGRESS');

  const noParent = fixtureWithParent(root, null);
  const unproven = runScript('detect-baseline.js', ['--project', noParent, '--slug', 'noparent', '--to-version', '4.1.1'], env);
  assert.equal(unproven.status, 2, unproven.out);
  const b = readJson(path.join(sessionDir(root, 'noparent'), 'baseline.json'));
  assert.equal(b.migration_path.status, 'NO_ELIGIBLE_PACK');
  assert.match(b.reference_pack_eligibility[0].reason, /not proven/);
});

test('13c. pack mode: a jump past the pack generation (3.x → 5.0.0, no 4→5 pack) is UNSUPPORTED, never jumped', () => {
  const root = tempRoot('multistep');
  const env = envFor(root, { MIGRATION_MVN: fakeMaven(path.join(root, 'bin')) });
  const r = runScript('detect-baseline.js', ['--project', SAMPLE_PROJECT, '--slug', 'boot3to5', '--to-version', '5.0.0', '--reference', 'spring-boot-3-to-4'], env);
  assert.equal(r.status, 2, r.out);
  const b = readJson(path.join(sessionDir(root, 'boot3to5'), 'baseline.json'));
  assert.equal(b.migration_path.status, 'UNSUPPORTED_MIGRATION_PATH');
  assert.deepEqual(b.migration_path.missing_capability, ['spring-boot-4-to-5']);
});

function writePlan(dir, id, { status = 'Approved', fixType = 'VERSION_MIGRATION', approvedBy = null } = {}) {
  const request = {
    issue_id: id,
    project: path.relative(REPO_ROOT, SAMPLE_PROJECT).replace(/\\/g, '/'),
    platform: 'Spring Boot',
    platform_coordinate: 'org.springframework.boot:spring-boot-starter-parent',
    source_version: '3.5.0',
    target_version: '4.1.1',
    source_java: '17',
    target_java: '21',
  };
  const lines = [
    `# Fix Plan — ${id}`, '', '## Spring Boot 3.5 has reached end of OSS support', '', '> Migrate the platform.', '',
    '## At a glance', '', '| | |', '|---|---|',
    `| **Status** | ${status} |`,
    ...(approvedBy ? [`| **Approved by** | ${approvedBy} |`] : []),
    `| **Fix Type** | \`${fixType}\` — Stage 2 routes to \`04d-version-migration\` |`,
    '| **CWE** | `CWE-1104` — Use of Unmaintained Third Party Components |', '',
    '## Routing decision', '', '- descriptor declares org.springframework.boot:spring-boot-starter-parent 3.5.0', '- major framework generation changes: 3.x → 4.x', '',
    `<!-- 04d-migration-request ${JSON.stringify(request)} -->`, '',
    '## Approval', '', 'checkpoint', '',
  ];
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `fix_plan_${id}.md`), lines.join('\n'));
}

test('14. --issue refuses a plan that is not Approved, and hands downstream a Refused fix with a summary', () => {
  const root = tempRoot('issue-refused');
  const plans = path.join(root, 'remediation');
  writePlan(plans, 'ISSUE-900', { status: 'Proposed' });
  const env = envFor(root, { MIGRATION_MVN: fakeMaven(path.join(root, 'bin')), PIPELINE_OUTPUT_DIR: plans });
  const r = runScript('detect-baseline.js', ['--issue', 'ISSUE-900'], env);
  assert.equal(r.status, 1, r.out);
  assert.match(r.out, /not "Approved"/);
  const fix = fs.readFileSync(path.join(plans, 'fix_ISSUE-900.md'), 'utf8');
  assert.match(fix, /\| \*\*Status\*\* \| Refused \|/);
  assert.match(fix, /\| \*\*Fix Type\*\* \| `VERSION_MIGRATION` \|/);
  assert.ok(!fs.existsSync(path.join(plans, 'fix_ISSUE-900.diff')), 'no diff for a refused migration');
  assert.equal(summaryFor(root).json.final_status, 'BLOCKED');

  writePlan(plans, 'ISSUE-901', { fixType: 'CODE_FIX' });
  const wrongType = runScript('detect-baseline.js', ['--issue', 'ISSUE-901'], env);
  assert.equal(wrongType.status, 1);
  assert.match(wrongType.out, /not VERSION_MIGRATION/);
});

test('14b. --issue with an Approved VERSION_MIGRATION plan takes project, target and approval mode from the plan', () => {
  const root = tempRoot('issue-ok');
  const plans = path.join(root, 'remediation');
  writePlan(plans, 'ISSUE-902', { approvedBy: 'TEST_AUTO_APPROVED — controlled validation' });
  const env = envFor(root, { MIGRATION_MVN: fakeMaven(path.join(root, 'bin')), PIPELINE_OUTPUT_DIR: plans });
  const r = runScript('detect-baseline.js', ['--issue', 'ISSUE-902', '--to-version', '9.9.9'], env);
  assert.equal(r.status, 0, r.out);
  const b = readJson(path.join(sessionDir(root, 'issue-902'), 'baseline.json'));
  assert.equal(b.target.platform.version, '4.1.1', 'the plan, never the caller, decides the target');
  assert.equal(b.language.target, '21');
  assert.equal(path.resolve(b.project.dir), path.resolve(SAMPLE_PROJECT));
  assert.equal(b.issue.id, 'ISSUE-902');
  assert.match(b.issue.approval_mode, /TEST_AUTO_APPROVED/);
  assert.equal(b.issue.routing_evidence.length, 2);
  assert.ok(!fs.existsSync(path.join(plans, 'fix_ISSUE-902.md')), 'no handoff while the migration is still in progress');
  const s = summaryFor(root).json;
  assert.equal(s.trigger.kind, 'agent-04-routing');
  assert.equal(s.final_status, 'IN_PROGRESS');
});

test('15. the per-run summary is generated from evidence for a finished session (recorded golden run)', () => {
  const root = tempRoot('summary');
  installLegacySession(root);
  const env = envFor(root);
  const render = runScript('render-migration-report.js', ['--slug', 'spring-boot-3-to-4'], env);
  assert.equal(render.status, 0, render.out);
  const s = summaryFor(root);
  assert.ok(s, 'render-migration-report.js finalizes the run');
  const rounds = fs.readdirSync(path.join(sessionDir(root, 'spring-boot-3-to-4'), 'rounds')).filter((f) => f.endsWith('.json')).length;
  assert.equal(s.json.compile_history.length, rounds);
  assert.notEqual(s.json.final_status, 'IN_PROGRESS');
  assert.ok(s.json.tests && s.json.tests.before, 'test counts read from the round logs');
  for (const heading of ['Run metadata', 'Trigger', 'Detection', 'Migration path', 'Planned transformations', 'Actual transformations',
    'File changes', 'Dependency changes', 'OpenRewrite', 'Compile history', 'Tests', 'Runtime', 'Behaviour comparison', 'Pipeline handoff', 'Evidence']) {
    assert.match(s.md, new RegExp(`^## ${heading}$`, 'm'), heading);
  }
});
