/**
 * Runtime comparison keeps raw differences visible. Extra observations and the agent's
 * classification sit beside the raw verdict and never replace it.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { tempRoot, freshModules, LEGACY_SESSION, SKILL_DIR } = require('./helpers');

const readRuntime = (phase) => JSON.parse(fs.readFileSync(path.join(LEGACY_SESSION, 'runtime', `${phase}.json`), 'utf8'));
const probeModule = () => require(path.join(SKILL_DIR, 'scripts', 'probe-runtime.js'));

test('13. the golden before/after comparison keeps every raw difference', () => {
  const { migration } = freshModules(tempRoot('runtime'));
  const before = readRuntime('baseline');
  const after = readRuntime('final');
  const compared = migration.compareProbeRecords(before, after);
  assert.equal(compared.total, 9);
  assert.equal(compared.matched, 4, 'four business endpoints byte-identical');
  assert.equal(compared.bodyOnly, 5, 'five bodies differ at the same status');
  assert.equal(compared.statusChanged, 0);
  const identical = compared.rows.filter((r) => r.verdict === 'identical').map((r) => r.name).sort();
  assert.deepEqual(identical, ['get employee 1', 'high earners', 'list all employees', 'search by department']);
  const auth = compared.rows.filter((r) => /unauthenticated|bad credentials/.test(r.name));
  assert.ok(auth.every((r) => r.beforeStatus === 401 && r.afterStatus === 401), 'authentication boundary still rejects');
  for (const r of compared.rows.filter((x) => x.verdict !== 'identical')) {
    assert.notEqual(r.beforeHash, r.afterHash, `${r.name}: raw hashes are reported as recorded`);
  }

  // The agent classifying a difference does not change the raw verdict.
  const judged = migration.compareProbeRecords(before, after, [
    { probe: 'health is public', classification: 'expected-framework-change', explanation: 'health document reordered' },
  ]);
  const health = judged.rows.find((r) => r.name === 'health is public');
  assert.equal(health.verdict, 'body-differs');
  assert.equal(health.classification, 'expected-framework-change');
});

test('13b. extra semantic observations are additive and never hide a raw difference', () => {
  const { migration } = freshModules(tempRoot('semantic'));
  const probe = probeModule();
  const request = { ignore_json_paths: ['timestamp'] };
  const a = '{"status":404,"timestamp":"2026-09-06T14:35:21.651+00:00","error":"Not Found"}';
  const b = '{"error":"Not Found","status":404,"timestamp":"2026-09-06T14:35:50.560Z"}';
  const oa = probe.semanticObservations(a, request);
  const ob = probe.semanticObservations(b, request);
  assert.notEqual(probe.hashBody(a), probe.hashBody(b), 'raw hashes differ');
  assert.notEqual(oa.semantic_hash, ob.semantic_hash, 'timestamps are NOT ignored by default');
  assert.equal(oa.ignored_paths_hash, ob.ignored_paths_hash, 'only an explicitly configured path is ignored');

  const reordered = probe.semanticObservations('{"b":1,"a":[2,1]}', {});
  assert.equal(reordered.semantic_hash, probe.semanticObservations('{"a":[2,1],"b":1}', {}).semantic_hash, 'key order ignored');
  assert.notEqual(reordered.semantic_hash, probe.semanticObservations('{"a":[1,2],"b":1}', {}).semantic_hash, 'array order kept by default');
  assert.equal(probe.semanticObservations('{"a":[2,1]}', { unordered_arrays: true }).semantic_hash,
    probe.semanticObservations('{"a":[1,2]}', { unordered_arrays: true }).semantic_hash, 'array order ignored only on request');
  assert.deepEqual(probe.semanticObservations('not json', {}), { json_parseable: false });

  const rec = (body, obs) => ({ probes: [{ name: 'p', method: 'GET', path: '/p', ok: true, status: 200, body_hash: probe.hashBody(body), ...obs }] });
  const row = migration.compareProbeRecords(rec(a, oa), rec(b, ob)).rows[0];
  assert.equal(row.verdict, 'body-differs', 'raw verdict unchanged');
  assert.equal(row.semantic, 'same-after-configured-ignored-paths', 'the extra observation is recorded beside it');

  const removed = probe.removePath({ items: [{ id: 1, v: 'x' }, { id: 2, v: 'y' }] }, 'items[*].id');
  assert.deepEqual(removed, { items: [{ v: 'x' }, { v: 'y' }] });
});

test('13c. a changed probe set is flagged; a one-sided run is never a comparison', () => {
  const { migration } = freshModules(tempRoot('probeset'));
  const before = { probes_sha: 'aaa', probes: [] };
  const after = { probes_sha: 'bbb', probes: [] };
  assert.equal(migration.compareProbeRecords(before, after).probeSetChanged, true);
  assert.match(migration.compareProbeRecords(before, null).verdictText, /Only one side/);
  assert.equal(probeModule().probeSetHash({ requests: [{ name: 'x' }] }), probeModule().probeSetHash({ auth: { password: 'changed' }, requests: [{ name: 'x' }] }), 'credentials are not part of the probe-set identity');
});
