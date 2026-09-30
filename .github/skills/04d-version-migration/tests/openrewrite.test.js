/**
 * OpenRewrite inside 04D: command construction, sandbox-only execution, preview-before-apply,
 * credential redaction, and the unavailable / required / failed paths — all driven through the
 * real run-migration-build.js with a fake build tool, so nothing touches the network.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const {
  tempRoot, freshModules, preparedSession, runScript, anyJdkMajor, SAMPLE_PROJECT, treeHash, sessionDir,
} = require('./helpers');

const JDK = anyJdkMajor();
const needsJdk = { skip: JDK ? false : 'no JDK installed — build-driving tests need one' };
const APP = 'src/main/java/com/example/fixture/App.java';
const OTHER = 'src/main/java/com/example/fixture/Other.java';

const readRecord = (dir, id) => JSON.parse(fs.readFileSync(path.join(dir, 'transformations', `${id}.json`), 'utf8'));
const readState = (dir) => JSON.parse(fs.readFileSync(path.join(dir, 'state.json'), 'utf8'));
const rounds = (dir) => fs.readdirSync(path.join(dir, 'rounds')).filter((f) => f.endsWith('.json')).sort();

test('6. command construction: Maven dry-run/apply, Gradle init script, sandbox cwd, no credentials', () => {
  const root = tempRoot('cmd');
  const { openrewrite, references, migration } = freshModules(root);
  const pack = references.resolveReferencePack('spring-boot-3-to-4');
  const t = pack.transformations.find((x) => x.id === 'boot4-composite');
  const workspace = path.join(root, 'ctx', 'version-migration', 's', 'workspace');

  const dry = openrewrite.buildInvocation({ tool: 'maven', mode: 'dry-run', transformation: t, workspace });
  assert.equal(dry.cwd, workspace, 'runs in the sandbox');
  assert.ok(dry.args.includes('org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRunNoFork'));
  assert.ok(dry.args.includes('-Drewrite.activeRecipes=org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0'));
  assert.ok(dry.args.includes('-Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-spring:6.37.1'));
  assert.equal(dry.patchFile, path.join(workspace, 'target', 'rewrite', 'rewrite.patch'));
  const apply = openrewrite.buildInvocation({ tool: 'maven', mode: 'apply', transformation: t, workspace });
  assert.ok(apply.args.includes('org.openrewrite.maven:rewrite-maven-plugin:6.46.1:runNoFork'));
  assert.ok(!apply.args.some((a) => /dryRun/.test(a)));

  const init = path.join(root, 'rewrite-00.init.gradle');
  const gradle = openrewrite.buildInvocation({ tool: 'gradle', mode: 'dry-run', transformation: t, workspace, initScriptFile: init });
  assert.deepEqual(gradle.args, ['--init-script', init, 'rewriteDryRun']);
  assert.match(gradle.initScript.content, /classpath\("org\.openrewrite:plugin:7\.41\.0"\)/);
  assert.match(gradle.initScript.content, /rewrite\("org\.openrewrite\.recipe:rewrite-spring:6\.37\.1"\)/);
  assert.match(gradle.initScript.content, /activeRecipe\("org\.openrewrite\.java\.spring\.boot4\.UpgradeSpringBoot_4_0"\)/);
  assert.equal(openrewrite.buildInvocation({ tool: 'gradle', mode: 'apply', transformation: t, workspace, initScriptFile: init }).args[2], 'rewriteRun');

  // Recorded command: sandbox path shortened, credential-bearing values redacted.
  const secretEnv = { MY_REPO_TOKEN: 'tok_abcdef123456' };
  const recorded = migration.redact(openrewrite.sanitiseCommand('mvn', [...dry.args, '-Drepo.password=hunter2hunter2', `-Durl=https://bob:s3cr3t@repo.example/m2`, `-Dtoken=${secretEnv.MY_REPO_TOKEN}`], { workspace, session: path.dirname(workspace) }), secretEnv);
  assert.ok(!recorded.includes('hunter2hunter2'));
  assert.ok(!recorded.includes('s3cr3t'));
  assert.ok(!recorded.includes('tok_abcdef123456'));
  assert.ok(recorded.includes('rewrite-maven-plugin:6.46.1'), 'recipe metadata survives redaction');

  // Only the session's own sandbox is acceptable.
  fs.mkdirSync(path.join(workspace, '.git'), { recursive: true });
  assert.ok(openrewrite.assertSandbox(workspace, 's', SAMPLE_PROJECT));
  assert.throws(() => openrewrite.assertSandbox(SAMPLE_PROJECT, 's', SAMPLE_PROJECT), /outside the sandbox/);

  // Plan coverage: nothing the plan does not name can run.
  const plan = { deterministic_candidates: [{ id: 'boot4-composite', provider: 'openrewrite', transformation: 'boot4-composite', covers: [] }] };
  assert.match(openrewrite.selectTransformation({ pack, plan: null }).error, /no migration-plan/);
  assert.match(openrewrite.selectTransformation({ pack, plan, overrides: { recipes: ['org.example.Evil'] } }).error, /not covered by the migration plan/);
  const ok = openrewrite.selectTransformation({ pack, plan });
  assert.equal(ok.transformation.license, 'Moderne Source Available License');
  assert.equal(ok.transformation.pack_transformation, 'boot4-composite');
});

test('6b. availability is classified from the tool output', () => {
  const { openrewrite } = freshModules(tempRoot('cls'));
  assert.deepEqual(openrewrite.classifyFailure('Could not transfer artifact x: UnknownHostException'), { available: false, reason: 'network' });
  assert.deepEqual(openrewrite.classifyFailure('status code: 401, reason phrase: Unauthorized'), { available: false, reason: 'credentials' });
  assert.deepEqual(openrewrite.classifyFailure('This recipe requires a Moderne license'), { available: false, reason: 'license' });
  assert.deepEqual(openrewrite.classifyFailure('Recipes not found: org.x.Y'), { available: false, reason: 'recipe-resolution' });
  assert.deepEqual(openrewrite.classifyFailure('NullPointerException in visitor'), { available: true, reason: 'tool-failed' });
});

test('7. an unavailable optional recipe falls back cleanly and is never reported as run', needsJdk, () => {
  const root = tempRoot('fallback');
  const { env, dir, steps } = preparedSession(root, { jdk: JDK });
  steps.forEach((s) => assert.equal(s.status, 0, s.out));
  const secret = 'repoToken_9f8e7d6c5b';
  const r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'],
    { ...env, FAKE_MVN_MODE: 'unavailable', FAKE_ECHO_SECRET: secret, PRIVATE_REPO_TOKEN: secret });
  assert.equal(r.status, 0, r.out);
  assert.match(r.out, /UNAVAILABLE here \(network\)/);
  assert.match(r.out, /compiler-driven path/);
  const record = readRecord(dir, 'rewrite-00');
  assert.equal(record.status, 'unavailable');
  assert.equal(record.applied, false);
  assert.deepEqual(record.availability, { available: false, reason: 'network' });
  assert.equal(record.provider, 'openrewrite');
  assert.equal(record.license, 'Moderne Source Available License');
  assert.deepEqual(rounds(dir), ['round-00.json'], 'no build round is invented for a transformation that did not run');
  assert.notEqual(readState(dir).state, 'BLOCKED');
  // No credential reached evidence.
  for (const f of ['rewrite-00.json', 'rewrite-00.log']) {
    const text = fs.readFileSync(path.join(dir, 'transformations', f), 'utf8');
    assert.ok(!text.includes(secret), `${f} leaks the token`);
    assert.ok(!text.includes('hunter2hunter2'), `${f} leaks the password`);
  }
});

test('8. a required recipe that is unavailable blocks the session with the reason', needsJdk, () => {
  const root = tempRoot('required');
  const { env, dir } = preparedSession(root, { jdk: JDK });
  const r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run', '--rewrite-policy', 'required'],
    { ...env, FAKE_MVN_MODE: 'unauthorized' });
  assert.equal(r.status, 1);
  assert.match(r.out, /BLOCKED/);
  const record = readRecord(dir, 'rewrite-00');
  assert.equal(record.status, 'unavailable');
  assert.equal(record.availability.reason, 'credentials');
  const state = readState(dir);
  assert.equal(state.state, 'BLOCKED');
  assert.match(state.reason, /required transformation .* unavailable: credentials/);
});

test('9. a dry-run previews without mutating the sandbox or the project', needsJdk, () => {
  const root = tempRoot('dryrun');
  const projectBefore = treeHash(SAMPLE_PROJECT);
  const { env, dir } = preparedSession(root, { jdk: JDK });
  const workspace = path.join(dir, 'workspace');
  const before = fs.readFileSync(path.join(workspace, APP), 'utf8');
  const r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...env, FAKE_MVN_MODE: 'dryrun-ok' });
  assert.equal(r.status, 0, r.out);
  const record = readRecord(dir, 'rewrite-00');
  assert.equal(record.status, 'previewed');
  assert.equal(record.mode, 'dry-run');
  assert.deepEqual(record.proposed_files, [APP]);
  assert.ok(fs.existsSync(path.join(dir, 'transformations', 'rewrite-00.patch')));
  assert.equal(fs.readFileSync(path.join(workspace, APP), 'utf8'), before, 'sandbox source unchanged by a dry-run');
  assert.equal(treeHash(SAMPLE_PROJECT), projectBefore, 'project untouched');
  assert.deepEqual(rounds(dir), ['round-00.json'], 'a dry-run runs no build');
  // Every tool call ran in the sandbox.
  const calls = fs.readFileSync(path.join(root, 'calls.jsonl'), 'utf8').trim().split('\n').map(JSON.parse);
  const rewriteCalls = calls.filter((c) => c.args.some((a) => a.includes('rewrite-maven-plugin')));
  assert.ok(rewriteCalls.length === 1 && path.resolve(rewriteCalls[0].cwd) === path.resolve(workspace));
  assert.ok(rewriteCalls[0].args.some((a) => a.endsWith(':dryRunNoFork')));
});

test('9b. a "dry-run" that changes source is voided and the sandbox restored', needsJdk, () => {
  const root = tempRoot('dryrun-mut');
  const { env, dir } = preparedSession(root, { jdk: JDK });
  const workspace = path.join(dir, 'workspace');
  const before = fs.readFileSync(path.join(workspace, APP), 'utf8');
  const r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...env, FAKE_MVN_MODE: 'dryrun-mutates' });
  assert.equal(r.status, 1);
  assert.equal(readRecord(dir, 'rewrite-00').status, 'failed');
  assert.equal(fs.readFileSync(path.join(workspace, APP), 'utf8'), before);
});

test('9c. apply needs an inspected preview, applies inside it, builds immediately and links both', needsJdk, () => {
  const root = tempRoot('apply');
  const { env, dir } = preparedSession(root, { jdk: JDK });
  const noPreview = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'apply'], { ...env, FAKE_MVN_MODE: 'run-ok' });
  assert.equal(noPreview.status, 1);
  assert.match(noPreview.out, /needs --rewrite-preview/);

  runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...env, FAKE_MVN_MODE: 'dryrun-ok' });
  const r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'apply', '--rewrite-preview', 'rewrite-00'], { ...env, FAKE_MVN_MODE: 'run-ok' });
  assert.equal(r.status, 0, r.out);
  const record = readRecord(dir, 'rewrite-01');
  assert.equal(record.status, 'applied');
  assert.equal(record.applied, true);
  assert.equal(record.preview, 'rewrite-00');
  assert.deepEqual(record.changed_files.map((c) => c.file), [APP]);
  assert.ok(record.checkpoint.ref.startsWith('refs/checkpoints/rewrite-01'));
  assert.equal(record.build_round, 1, 'linked to the build that verified it');
  const round1 = JSON.parse(fs.readFileSync(path.join(dir, 'rounds', 'round-01.json'), 'utf8'));
  assert.equal(round1.transformation, 'rewrite-01');
  assert.equal(round1.build.intent, 'test-compile', 'apply defaults to a compile-level round');
  assert.equal(record.reconciliation.status, 'reconcile-required', 'fixture parent is still 3.5.0 — the request is 4.1.1');
});

test('9d. an apply that strays outside the preview, or installs OpenRewrite into the build, is reverted', needsJdk, () => {
  for (const mode of ['run-out-of-scope', 'run-infra']) {
    const root = tempRoot(mode);
    const { env, dir } = preparedSession(root, { jdk: JDK });
    const workspace = path.join(dir, 'workspace');
    const snapshot = { app: fs.readFileSync(path.join(workspace, APP), 'utf8'), other: fs.readFileSync(path.join(workspace, OTHER), 'utf8'), pom: fs.readFileSync(path.join(workspace, 'pom.xml'), 'utf8') };
    runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...env, FAKE_MVN_MODE: 'dryrun-ok' });
    const r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'apply', '--rewrite-preview', 'rewrite-00'], { ...env, FAKE_MVN_MODE: mode });
    assert.equal(r.status, 1, `${mode}: ${r.out}`);
    const record = readRecord(dir, 'rewrite-01');
    assert.equal(record.status, 'reverted', mode);
    assert.equal(record.scope_check.ok, false);
    assert.equal(fs.readFileSync(path.join(workspace, APP), 'utf8'), snapshot.app, `${mode}: App.java restored`);
    assert.equal(fs.readFileSync(path.join(workspace, OTHER), 'utf8'), snapshot.other, `${mode}: Other.java restored`);
    assert.equal(fs.readFileSync(path.join(workspace, 'pom.xml'), 'utf8'), snapshot.pom, `${mode}: pom.xml restored`);
    assert.deepEqual(rounds(dir), ['round-00.json'], `${mode}: no build for a reverted apply`);
  }
});

test('9e. preconditions: no transformation before round 0, the baseline probe and a valid plan', needsJdk, () => {
  const root = tempRoot('pre');
  const { env, dir } = preparedSession(root, { jdk: JDK, plan: false });
  let r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], env);
  assert.equal(r.status, 1);
  assert.match(r.out, /no migration plan/);
  fs.writeFileSync(path.join(dir, 'migration-plan.json'), JSON.stringify({ slug: 'fixture' }));
  r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], env);
  assert.match(r.out, /does not validate/);
  fs.rmSync(path.join(dir, 'runtime', 'baseline.json'));
  r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], env);
  assert.match(r.out, /no baseline runtime record/);
  fs.rmSync(path.join(dir, 'rounds'), { recursive: true });
  r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--rewrite', 'dry-run'], env);
  assert.match(r.out, /no round 0/);
  assert.ok(!fs.existsSync(path.join(dir, 'transformations')), 'nothing was executed');
});

test('9f. round 0 is immutable: refused on an edited sandbox, and no target round runs before it', needsJdk, () => {
  const root = tempRoot('r0');
  const { env, dir } = preparedSession(root, { jdk: JDK });
  const noRounds = tempRoot('r0b');
  const second = preparedSession(noRounds, { jdk: JDK });
  fs.rmSync(path.join(second.dir, 'rounds'), { recursive: true });
  const early = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--intent', 'compile'], second.env);
  assert.equal(early.status, 1);
  assert.match(early.out, /no round 0 recorded/);

  fs.appendFileSync(path.join(dir, 'workspace', OTHER), '// edited\n');
  const again = runScript('run-migration-build.js', ['--slug', 'fixture', '--baseline', '--jdk', String(JDK), '--intent', 'compile'], env);
  assert.equal(again.status, 1);
  assert.match(again.out, /round 0 must run on the untouched sandbox/);
  // An ordinary target round still works exactly as in v1.
  const target = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--intent', 'compile', '--label', 'edited Other'], env);
  assert.equal(target.status, 0, target.out);
  const round1 = JSON.parse(fs.readFileSync(path.join(dir, 'rounds', 'round-01.json'), 'utf8'));
  assert.equal(round1.label, 'edited Other');
  assert.equal(round1.outcome, 'passed');
  assert.equal(round1.transformation, null);
});

test('9g. grouped errors are parser facts; plan correlation is labelled a heuristic', needsJdk, () => {
  const root = tempRoot('groups');
  const { env, dir } = preparedSession(root, { jdk: JDK });
  const r = runScript('run-migration-build.js', ['--slug', 'fixture', '--jdk', String(JDK), '--intent', 'compile'], { ...env, FAKE_BUILD: 'fail' });
  assert.equal(r.status, 0);
  const round = JSON.parse(fs.readFileSync(path.join(dir, 'rounds', 'round-01.json'), 'utf8'));
  assert.equal(round.outcome, 'compile-failed');
  assert.ok(round.errors.length >= 2, 'raw errors kept');
  const pkg = round.error_groups.find((g) => g.subject === 'package com.fasterxml.jackson.databind');
  assert.ok(pkg && pkg.basis === 'parser' && pkg.package_family === 'com.fasterxml.jackson');
  const sym = round.error_groups.find((g) => g.subject === 'class ObjectMapper');
  assert.ok(sym, 'symbol group');
  assert.equal(sym.basis, 'parser + source-import', 'the import that provided the symbol is read from the source');
  assert.equal(sym.package, 'com.fasterxml.jackson.databind');
  assert.match(round.correlation.basis, /heuristic/);
  assert.ok(round.correlation.matches.some((m) => m.impact.includes('I1')));
});

test('9h. the project directory is never touched by any of the above', () => {
  // Every test above copies the fixture into a disposable sandbox; this is the last line of defence.
  const expected = ['Dockerfile', 'pom.xml', 'src'];
  assert.deepEqual(fs.readdirSync(SAMPLE_PROJECT).sort(), expected);
  assert.ok(!fs.existsSync(path.join(SAMPLE_PROJECT, 'target')));
  assert.ok(!fs.existsSync(path.join(SAMPLE_PROJECT, '.git')));
  void sessionDir;
});
