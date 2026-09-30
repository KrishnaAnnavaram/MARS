/**
 * Session state inferred from evidence (old sessions need no state.json), and the judgement
 * schemas: v1 documents stay valid, v2 documents validate, malformed ones do not.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { tempRoot, freshModules, installLegacySession, SKILL_DIR, LEGACY_SESSION, samplePlan } = require('./helpers');

const readTemplate = (name) => JSON.parse(fs.readFileSync(path.join(SKILL_DIR, 'templates', name), 'utf8'));

test('10. state is inferred from an old-style session with no state.json', () => {
  const root = tempRoot('legacy-state');
  const dir = installLegacySession(root);
  const { migration } = freshModules(root);
  assert.ok(!fs.existsSync(path.join(dir, 'state.json')));
  const state = migration.inferState('spring-boot-3-to-4');
  assert.equal(state.legacy, true);
  assert.deepEqual(state.reached, [
    'BASELINE_DETECTED', 'WORKSPACE_PREPARED', 'BASELINE_BUILT', 'BASELINE_PROBED',
    'TARGET_COMPILED', 'TARGET_TESTED', 'FINAL_PROBED', 'EVIDENCE_READY',
  ]);
  assert.equal(state.state, 'EVIDENCE_READY');
  assert.equal(state.blocked, null);
  assert.ok(!fs.existsSync(path.join(dir, 'state.json')), 'inferring state writes nothing into an old session');
});

test('10b. recorded BLOCKED overrides inference until cleared; history is kept', () => {
  const root = tempRoot('blocked');
  installLegacySession(root);
  const { migration } = freshModules(root);
  migration.recordState('spring-boot-3-to-4', 'BLOCKED', 'test', 'required recipe unavailable');
  let state = migration.inferState('spring-boot-3-to-4');
  assert.equal(state.state, 'BLOCKED');
  assert.equal(state.blocked, 'required recipe unavailable');
  assert.equal(state.legacy, false);
  migration.clearBlock('spring-boot-3-to-4', 'test', 'access restored');
  state = migration.inferState('spring-boot-3-to-4');
  assert.equal(state.state, 'EVIDENCE_READY');
  assert.equal(state.history.length, 2);
  assert.throws(() => migration.recordState('spring-boot-3-to-4', 'NOT_A_STATE', 'test'), /unknown state/);
});

test('11. the schema still accepts existing (v1) migration.json documents', () => {
  const { migration } = freshModules(tempRoot('schema-v1'));
  const golden = JSON.parse(fs.readFileSync(path.join(LEGACY_SESSION, 'migration.json'), 'utf8'));
  assert.deepEqual(migration.validateTemplate(golden, 'migration.schema.json'), []);
  // The v1 worked example, as it shipped (the fields it uses are all v1 fields).
  const v1Example = readTemplate('migration.example.json');
  const v1Only = { ...v1Example };
  for (const key of ['plan', 'impact_review', 'transformations', 'unresolved_constraints', 'blocking_conditions', 'provenance']) delete v1Only[key];
  assert.deepEqual(migration.validateTemplate(v1Only, 'migration.schema.json'), []);
});

test('12. the schema accepts an enhanced migration.json and rejects malformed ones', () => {
  const { migration } = freshModules(tempRoot('schema-v2'));
  const example = readTemplate('migration.example.json');
  assert.ok(example.transformations && example.impact_review && example.behaviour.differences, 'the shipped example exercises the v2 fields');
  assert.deepEqual(migration.validateTemplate(example, 'migration.schema.json'), []);

  const bad = JSON.parse(JSON.stringify(example));
  bad.behaviour.differences[0].classification = 'looks-fine';
  bad.code_changes[0].origin = 'magic';
  bad.surprise = true;
  const errors = migration.validateTemplate(bad, 'migration.schema.json');
  assert.ok(errors.some((e) => /classification: must be one of/.test(e)));
  assert.ok(errors.some((e) => /origin: must be one of/.test(e)));
  assert.ok(errors.some((e) => /unexpected property "surprise"/.test(e)));
});

test('12b. migration-plan schema: shipped example and generated plan validate; gaps are caught', () => {
  const { migration } = freshModules(tempRoot('plan'));
  assert.deepEqual(migration.validateTemplate(readTemplate('migration-plan.example.json'), 'migration-plan.schema.json'), []);
  assert.deepEqual(migration.validateTemplate(samplePlan('x'), 'migration-plan.schema.json'), []);
  const missing = samplePlan('x');
  delete missing.impact;
  missing.impact_extra = [];
  const errors = migration.validateTemplate(missing, 'migration-plan.schema.json');
  assert.ok(errors.some((e) => /missing required "impact"/.test(e)));
  assert.ok(errors.some((e) => /unexpected property "impact_extra"/.test(e)));
  const risky = samplePlan('x');
  risky.impact[0].risk = 'extreme';
  assert.ok(migration.validateTemplate(risky, 'migration-plan.schema.json').some((e) => /risk: must be one of/.test(e)));
});
