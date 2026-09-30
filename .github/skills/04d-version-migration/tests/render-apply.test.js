/**
 * The renderer still renders pre-v2 evidence, renders v2 evidence honestly, and apply eligibility
 * refuses incomplete sessions. `apply-migration.js --to-project` is never run by these tests: the
 * eligibility decision it is gated on is exercised directly, and the CLI only in its dry-run form.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const {
  tempRoot, freshModules, installLegacySession, envFor, runScript, SKILL_DIR, preparedSession, anyJdkMajor,
} = require('./helpers');

const JDK = anyJdkMajor();

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2));
}

test('14. the renderer still renders an old-style (pre-v2) session', () => {
  const root = tempRoot('render-legacy');
  const dir = installLegacySession(root);
  const r = runScript('render-migration-report.js', ['--slug', 'spring-boot-3-to-4'], envFor(root));
  assert.equal(r.status, 0, r.out);
  const report = fs.readFileSync(path.join(root, 'reports', 'migration_spring-boot-3-to-4.md'), 'utf8');
  for (const heading of ['## At a glance', '## 1. What moved', '## 2. How the migration went', '### 2.1 The round ledger',
    '### 2.2 Exactly what failed, and why', '## 3. Round by round', '## 4. Source changes the upgrade forced',
    '## 5. Every file that changed', '## 6. Does it still behave the same?', '## 7. Changes that were *not* caused by the upgrade',
    '## 8. What still needs a human', '## 9. The patch']) {
    assert.ok(report.includes(heading), `v1 section kept: ${heading}`);
  }
  assert.match(report, /Spring Boot `3\.5\.0` → `4\.1\.1`/);
  assert.match(report, /Java `17` → `21`/);
  assert.match(report, /8 recorded/);
  assert.match(report, /before: 15 run, 2 failed, 1 error → after: 15 run, 2 failed, 1 error/);
  assert.match(report, /🟢 4 identical · 🟡 5 same status with a different body · 🔴 0 changed status/);
  assert.match(report, /No migration plan was recorded/, 'the missing plan is stated, not invented');
  assert.match(report, /No deterministic transformation was used/);
  assert.ok(!fs.existsSync(path.join(dir, 'state.json')), 'rendering an old session does not start writing state into it');
  assert.ok(fs.readFileSync(path.join(root, 'reports', 'README.md'), 'utf8').includes('migration_spring-boot-3-to-4.md'));
});

test('14b. v2 evidence renders honestly: an unavailable recipe is shown as not run', { skip: JDK ? false : 'no JDK' }, () => {
  const root = tempRoot('render-v2');
  const { env, dir } = preparedSession(root, { jdk: JDK });
  runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...env, FAKE_MVN_MODE: 'unavailable' });
  runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--intent', 'package', '--label', 'versions bumped by hand'], env);
  writeJson(path.join(dir, 'migration.json'), {
    slug: 'fixture',
    title: 'Fixture 3.5.0 to 4.1.1',
    summary: 'A fixture migration used to prove that an unavailable OpenRewrite recipe is reported as exactly that, never as executed.',
    migration: { reference_pack: 'references/spring-boot-3-to-4.md', language: { name: 'Java', from: '17', to: '21' }, platform: { name: 'Spring Boot', from: '3.5.0', to: '4.1.1' } },
    round_notes: [{ round: 0, diagnosis: 'Reference build.', changes: [] }, { round: 1, diagnosis: 'Fallback path.', changes: ['bumped versions by hand'] }],
    transformations: [{ record: 'rewrite-00', decision: 'not-run', rationale: 'network unavailable; fell back to the compiler-driven path' }],
    code_changes: [{ file: 'pom.xml', what: 'parent bumped', why: 'the jump', origin: 'build-file', evidence: 'fallback after rewrite-00 unavailable' }],
  });
  const r = runScript('render-migration-report.js', ['--slug', 'fixture'], env);
  assert.equal(r.status, 0, r.out);
  const report = fs.readFileSync(path.join(root, 'reports', 'migration_fixture.md'), 'utf8');
  assert.match(report, /## 0\. What was understood before anything changed/);
  assert.match(report, /Predicted impact vs what actually happened/);
  assert.match(report, /### 2\.4 Deterministic transformations/);
  assert.match(report, /unavailable — did not run/);
  assert.match(report, /reason: network/);
  assert.ok(!/applied to the sandbox/.test(report.split('### 2.4')[1].split('### 2.5')[0]), 'never presented as applied');
  assert.match(report, /## 10\. Evidence and provenance/);
  assert.match(report, /\*\*Predicted\*\*/);
  assert.match(report, /\*\*Unresolved\*\*/);
  assert.match(report, /final build declares `3\.5\.0` 🔴 does not match/, 'requested target checked against what the final build declares');
});

test('14c. the renderer refuses a migration.json that breaks the schema', () => {
  const root = tempRoot('render-bad');
  const dir = installLegacySession(root);
  const doc = JSON.parse(fs.readFileSync(path.join(dir, 'migration.json'), 'utf8'));
  doc.behaviour.verdict = 'probably-fine';
  writeJson(path.join(dir, 'migration.json'), doc);
  const r = runScript('render-migration-report.js', ['--slug', 'spring-boot-3-to-4'], envFor(root));
  assert.equal(r.status, 1);
  assert.match(r.out, /does not match templates\/migration\.schema\.json/);
});

test('15. apply eligibility refuses incomplete or failed sessions and accepts the golden one', () => {
  const root = tempRoot('apply');
  const dir = installLegacySession(root);
  freshModules(root);
  const { eligibility } = require(path.join(SKILL_DIR, 'scripts', 'apply-migration.js'));

  const golden = eligibility('spring-boot-3-to-4', []);
  assert.equal(golden.legacy, true);
  assert.deepEqual(golden.reasons, [], 'the recorded golden session is eligible on its own evidence');

  // A failed last round.
  writeJson(path.join(dir, 'rounds', 'round-08.json'), { ...JSON.parse(fs.readFileSync(path.join(dir, 'rounds', 'round-07.json'), 'utf8')), round: 8, outcome: 'compile-failed' });
  let verdict = eligibility('spring-boot-3-to-4', []);
  assert.equal(verdict.eligible, false);
  assert.ok(verdict.reasons.some((r) => /last round \(8\) ended "compile-failed"/.test(r)));
  fs.rmSync(path.join(dir, 'rounds', 'round-08.json'));

  // Probed before but not after.
  const finalProbe = path.join(dir, 'runtime', 'final.json');
  const saved = fs.readFileSync(finalProbe, 'utf8');
  fs.rmSync(finalProbe);
  verdict = eligibility('spring-boot-3-to-4', []);
  assert.ok(verdict.reasons.some((r) => /probed before the migration but not successfully after/.test(r)));
  fs.writeFileSync(finalProbe, saved);

  // An open blocking condition, and a BLOCKED state.
  const doc = JSON.parse(fs.readFileSync(path.join(dir, 'migration.json'), 'utf8'));
  writeJson(path.join(dir, 'migration.json'), { ...doc, blocking_conditions: [{ condition: 'licence review pending', status: 'open' }] });
  verdict = eligibility('spring-boot-3-to-4', []);
  assert.ok(verdict.reasons.some((r) => /open blocking condition: licence review pending/.test(r)));
  writeJson(path.join(dir, 'migration.json'), doc);
  const { migration } = freshModules(root);
  migration.recordState('spring-boot-3-to-4', 'BLOCKED', 'test', 'required recipe unavailable');
  verdict = require(path.join(SKILL_DIR, 'scripts', 'apply-migration.js')).eligibility('spring-boot-3-to-4', []);
  assert.ok(verdict.reasons.some((r) => /BLOCKED/.test(r)));

  // No rounds at all.
  fs.rmSync(path.join(dir, 'rounds'), { recursive: true });
  verdict = require(path.join(SKILL_DIR, 'scripts', 'apply-migration.js')).eligibility('spring-boot-3-to-4', []);
  assert.ok(verdict.reasons.some((r) => /no build rounds recorded/.test(r)));
});

test('15b. apply defaults to a dry run and reports eligibility without writing anything', () => {
  const root = tempRoot('apply-cli');
  installLegacySession(root);
  const r = runScript('apply-migration.js', ['--slug', 'spring-boot-3-to-4'], envFor(root));
  assert.equal(r.status, 0, r.out);
  assert.match(r.out, /Eligible to apply: YES/);
  assert.match(r.out, /Dry run — nothing written/);
  assert.match(r.out, /MISSING \(no sandbox repository\)/, 'the archived session has no sandbox, and says so');
});

test('15c. a v2 session whose final version is not the requested one is not eligible', { skip: JDK ? false : 'no JDK' }, () => {
  const root = tempRoot('apply-v2');
  const { env, dir } = preparedSession(root, { jdk: JDK });
  runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--intent', 'package'], env);
  freshModules(root);
  const verdict = require(path.join(SKILL_DIR, 'scripts', 'apply-migration.js')).eligibility('fixture', []);
  assert.equal(verdict.eligible, false);
  assert.ok(verdict.reasons.some((r) => /final declared platform version is 3\.5\.0, but 4\.1\.1 was requested/.test(r)));
  void dir;
});
