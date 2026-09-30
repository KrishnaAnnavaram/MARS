/**
 * detect-baseline.js: every v1 field is still there with its v1 shape, the v2 fields are additive,
 * and the project is only read.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const {
  tempRoot, envFor, runScript, SAMPLE_PROJECT, treeHash, sessionDir, fakeMaven,
} = require('./helpers');

function detect(extraArgs = []) {
  const root = tempRoot('baseline');
  const env = envFor(root, { MIGRATION_MVN: fakeMaven(path.join(root, 'bin')) });
  const before = treeHash(SAMPLE_PROJECT);
  const result = runScript('detect-baseline.js', ['--project', SAMPLE_PROJECT, '--slug', 'fixture', '--to-java', '21', ...extraArgs], env);
  const baseline = JSON.parse(fs.readFileSync(path.join(sessionDir(root, 'fixture'), 'baseline.json'), 'utf8'));
  return { root, result, baseline, unchanged: before === treeHash(SAMPLE_PROJECT) };
}

test('4. every v1 baseline field is present with its v1 shape', () => {
  const { result, baseline, unchanged } = detect(['--to-version', '4.1.1']);
  assert.equal(result.status, 0, result.out);
  assert.ok(unchanged, 'the project directory was not modified');
  for (const key of ['slug', 'generated_at', 'project', 'build_tool', 'language', 'platform', 'toolchain', 'reference_packs']) {
    assert.ok(key in baseline, `baseline.${key}`);
  }
  for (const key of ['dir', 'relative_to_repo', 'name', 'descriptor', 'coordinates', 'sources', 'ancillary_files']) {
    assert.ok(key in baseline.project, `project.${key}`);
  }
  assert.deepEqual(Object.keys(baseline.project.sources).sort(), ['main', 'resources', 'test']);
  assert.equal(baseline.project.sources.main, 2);
  assert.equal(baseline.project.sources.test, 1);
  assert.deepEqual(baseline.project.ancillary_files, ['Dockerfile']);
  for (const key of ['tool', 'kind', 'command', 'display', 'version']) assert.ok(key in baseline.build_tool, `build_tool.${key}`);
  assert.equal(baseline.build_tool.tool, 'maven');
  for (const key of ['name', 'declared', 'target', 'from_jdk', 'to_jdk']) assert.ok(key in baseline.language, `language.${key}`);
  assert.equal(baseline.language.declared, '17');
  assert.equal(baseline.language.target, '21');
  assert.deepEqual(baseline.platform.parent, { groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-parent', version: '3.5.0' });
  assert.ok(Array.isArray(baseline.platform.dependencies) && baseline.platform.dependencies.length === 3);
  assert.ok(Array.isArray(baseline.toolchain.installed_jdks));
  const pack = baseline.reference_packs[0];
  for (const key of ['id', 'title', 'stack', 'from', 'to', 'language_from', 'language_to', 'file', 'matched_on']) assert.ok(key in pack, `reference_packs[0].${key}`);
  assert.equal(pack.file, 'references/spring-boot-3-to-4.md');
});

test('5. v2 fields are additive: target, migration path, observations, capabilities, state', () => {
  const { root, baseline } = detect(['--to-version', '4.1.1']);
  assert.deepEqual(baseline.target.platform, { name: 'Spring Boot', version: '4.1.1', source: 'request' });
  assert.equal(baseline.target.language.source, 'request');
  assert.equal(baseline.migration_path.on_preparation_line, true);
  assert.equal(baseline.migration_path.requested_target, '4.1.1');
  assert.equal(baseline.migration_path.platform.version, '3.5.0');

  const obs = baseline.observations;
  assert.match(obs.basis, /not a semantic Java model/i);
  assert.deepEqual(obs.entry_points, ['src/main/java/com/example/fixture/App.java']);
  assert.deepEqual(obs.config_files.map((c) => c.file), ['src/main/resources/application.yml']);
  assert.deepEqual(obs.pinned_dependencies, [{ coordinate: 'org.testcontainers:testcontainers', version: '1.20.1', declared: '1.20.1', scope: 'test' }]);
  assert.equal(obs.container_runtime[0].java_guess, '17');
  const surfaces = Object.fromEntries(obs.pack_surfaces.map((s) => [s.id, s.files.map((f) => f.file)]));
  assert.deepEqual(surfaces.json, ['src/main/java/com/example/fixture/App.java']);
  assert.deepEqual(surfaces['test-mocking'], ['src/test/java/com/example/fixture/AppTest.java']);
  assert.deepEqual(surfaces['web-api'], ['src/main/java/com/example/fixture/App.java']);
  assert.ok(obs.migration_sensitive_files.includes('pom.xml'));

  const ids = baseline.capabilities.transformations.map((t) => t.id);
  assert.deepEqual(ids.sort(), ['boot35-preparation', 'boot4-composite', 'boot4-curated']);
  assert.ok(baseline.capabilities.transformations.every((t) => /not checked/.test(t.availability)));

  const state = JSON.parse(fs.readFileSync(path.join(sessionDir(root, 'fixture'), 'state.json'), 'utf8'));
  assert.equal(state.state, 'BASELINE_DETECTED');
});

test('5b. without --to-version the target is recorded as unresolved, never inferred', () => {
  const { baseline } = detect();
  assert.equal(baseline.target.platform.version, null);
  assert.equal(baseline.target.platform.source, 'unresolved');
  assert.ok(baseline.migration_path.unresolved.some((u) => /no exact target platform version/.test(u)));
});
