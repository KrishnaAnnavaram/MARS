/**
 * Shared helpers for the 04D tests.
 *
 * Every test works in its own temporary directory: PIPELINE_CONTEXT_DATA_DIR points the sessions
 * there and MIGRATION_REPORT_DIR points the reports there, so nothing is written into the
 * repository and the fixture project is only ever read (and copied into a disposable sandbox).
 *
 * OpenRewrite and Maven are replaced by a small fake build tool (MIGRATION_MVN) whose behaviour is
 * chosen per test with FAKE_MVN_MODE. That makes the unavailable, required, dry-run, apply and
 * scope-violation paths deterministic and offline; the real tool is exercised by the E2E run.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawnSync } = require('child_process');

const SKILL_DIR = path.resolve(__dirname, '..');
const FIXTURES = path.join(__dirname, 'fixtures');
const SAMPLE_PROJECT = path.join(FIXTURES, 'sample-project');
const LEGACY_SESSION = path.join(FIXTURES, 'legacy-session', 'spring-boot-3-to-4');
const IS_WIN = process.platform === 'win32';

function tempRoot(label = 'case') {
  return fs.mkdtempSync(path.join(os.tmpdir(), `04d-${label}-`));
}

function copyDir(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    const s = path.join(from, entry.name);
    const d = path.join(to, entry.name);
    if (entry.isDirectory()) copyDir(s, d);
    else fs.copyFileSync(s, d);
  }
}

/** sha256 over every file under a directory, path-sorted — proves a tree was not modified. */
function treeHash(dir) {
  const hash = crypto.createHash('sha256');
  const walk = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.name === '.git') continue;
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) walk(full);
      else hash.update(path.relative(dir, full)).update(fs.readFileSync(full));
    }
  };
  walk(dir);
  return hash.digest('hex');
}

const FAKE_MVN_JS = String.raw`
// Fake build tool for 04D tests. Behaviour: FAKE_MVN_MODE. Every call is appended to FAKE_MVN_CALLS.
const fs = require('fs');
const path = require('path');
const args = process.argv.slice(2);
const mode = process.env.FAKE_MVN_MODE || 'ok';
if (process.env.FAKE_MVN_CALLS) fs.appendFileSync(process.env.FAKE_MVN_CALLS, JSON.stringify({ cwd: process.cwd(), args }) + '\n');
if (args[0] === '-v') { console.log('Apache Maven 3.9.99 (fake)'); process.exit(0); }
const rewrite = args.find((a) => a.includes('rewrite-maven-plugin'));
const app = path.join('src', 'main', 'java', 'com', 'example', 'fixture', 'App.java');
const other = path.join('src', 'main', 'java', 'com', 'example', 'fixture', 'Other.java');
const patch = [
  'diff --git a/src/main/java/com/example/fixture/App.java b/src/main/java/com/example/fixture/App.java',
  '--- a/src/main/java/com/example/fixture/App.java',
  '+++ b/src/main/java/com/example/fixture/App.java',
  '@@ -3 +3 @@',
  '-import com.fasterxml.jackson.databind.ObjectMapper;',
  '+import tools.jackson.databind.json.JsonMapper;',
  '',
].join('\n');
const edit = (file) => fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace('com.fasterxml.jackson.databind.ObjectMapper', 'tools.jackson.databind.json.JsonMapper') + '// rewritten\n');
if (rewrite) {
  if (process.env.FAKE_ECHO_SECRET) console.log('[DEBUG] using repository token ' + process.env.FAKE_ECHO_SECRET + ' and -Drepo.password=hunter2hunter2');
  if (mode === 'unavailable') {
    console.log('[ERROR] Plugin org.openrewrite.maven:rewrite-maven-plugin:6.46.1 or one of its dependencies could not be resolved: Could not transfer artifact org.openrewrite.maven:rewrite-maven-plugin:pom:6.46.1 from/to central: java.net.UnknownHostException: repo.maven.apache.org');
    process.exit(1);
  }
  if (mode === 'unauthorized') {
    console.log('[ERROR] Could not transfer artifact org.openrewrite.recipe:rewrite-spring:jar:6.37.1 from/to private (https://repo.example/maven): status code: 401, reason phrase: Unauthorized (401)');
    process.exit(1);
  }
  if (mode === 'tool-fails') { console.log('[ERROR] java.lang.IllegalStateException: recipe blew up while visiting App.java'); process.exit(1); }
  const dry = /dryRun/.test(rewrite);
  if (dry) {
    if (mode === 'dryrun-mutates') edit(app);
    if (mode !== 'no-changes') {
      fs.mkdirSync(path.join('target', 'rewrite'), { recursive: true });
      fs.writeFileSync(path.join('target', 'rewrite', 'rewrite.patch'), patch);
    }
    console.log('[INFO] BUILD SUCCESS');
    process.exit(0);
  }
  if (mode === 'no-changes') { console.log('[INFO] BUILD SUCCESS'); process.exit(0); }
  edit(app);
  if (mode === 'run-out-of-scope') fs.appendFileSync(other, '// unrelated change\n');
  if (mode === 'run-infra') fs.writeFileSync('pom.xml', fs.readFileSync('pom.xml', 'utf8').replace('</project>', '<build><plugins><plugin><groupId>org.openrewrite.maven</groupId><artifactId>rewrite-maven-plugin</artifactId></plugin></plugins></build>\n</project>'));
  console.log('[INFO] BUILD SUCCESS');
  process.exit(0);
}
if (process.env.FAKE_BUILD === 'fail') {
  console.log('[ERROR] ' + path.resolve(app) + ':[3,8] package com.fasterxml.jackson.databind does not exist');
  console.log('[ERROR] ' + path.resolve(app) + ':[13,19] cannot find symbol');
  console.log('[ERROR]   symbol:   class ObjectMapper');
  console.log('[INFO] BUILD FAILURE');
  process.exit(1);
}
console.log('[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0');
console.log('[INFO] BUILD SUCCESS');
process.exit(0);
`;

/** Writes the fake build tool into `dir` and returns the command 04D should run. */
function fakeMaven(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const js = path.join(dir, 'fake-mvn.js');
  fs.writeFileSync(js, FAKE_MVN_JS);
  if (IS_WIN) {
    const cmd = path.join(dir, 'mvn.cmd');
    fs.writeFileSync(cmd, `@echo off\r\n"${process.execPath}" "%~dp0fake-mvn.js" %*\r\n`);
    return cmd;
  }
  const sh = path.join(dir, 'mvn');
  fs.writeFileSync(sh, `#!/bin/sh\nexec "${process.execPath}" "$(dirname "$0")/fake-mvn.js" "$@"\n`);
  fs.chmodSync(sh, 0o755);
  return sh;
}

/** A JDK major installed here, or null — build-driving tests skip without one. */
function anyJdkMajor() {
  const env = { ...process.env };
  const probe = spawnSync(process.execPath, ['-e', `
    const m = require(${JSON.stringify(path.join(SKILL_DIR, 'scripts', 'lib', 'migration.js'))});
    const j = m.installedJdks();
    process.stdout.write(j.length ? String(j[j.length - 1].major) : '');
  `], { encoding: 'utf8', env });
  return probe.stdout ? Number(probe.stdout) : null;
}

function envFor(root, extra = {}) {
  return {
    ...process.env,
    PIPELINE_CONTEXT_DATA_DIR: path.join(root, 'ctx'),
    MIGRATION_REPORT_DIR: path.join(root, 'reports'),
    ...extra,
  };
}

function runScript(script, args, env) {
  const result = spawnSync(process.execPath, [path.join(SKILL_DIR, 'scripts', script), ...args], {
    cwd: SKILL_DIR, env, encoding: 'utf8', timeout: 120000,
  });
  return { status: result.status, stdout: result.stdout || '', stderr: result.stderr || '', out: `${result.stdout}\n${result.stderr}` };
}

/**
 * Loads the skill's modules against a temporary data/report directory. lib/migration.js fixes its
 * paths at load time, so the module cache is cleared and the environment set first.
 */
function freshModules(root) {
  process.env.PIPELINE_CONTEXT_DATA_DIR = path.join(root, 'ctx');
  process.env.MIGRATION_REPORT_DIR = path.join(root, 'reports');
  for (const key of Object.keys(require.cache)) {
    if (key.startsWith(path.join(SKILL_DIR, 'scripts'))) delete require.cache[key];
  }
  return {
    migration: require(path.join(SKILL_DIR, 'scripts', 'lib', 'migration.js')),
    references: require(path.join(SKILL_DIR, 'scripts', 'lib', 'references.js')),
    openrewrite: require(path.join(SKILL_DIR, 'scripts', 'lib', 'openrewrite.js')),
  };
}

function sessionDir(root, slug) {
  return path.join(root, 'ctx', 'version-migration', slug);
}

/** Copies the recorded golden (pre-v2) session into a temporary data dir. */
function installLegacySession(root, slug = 'spring-boot-3-to-4') {
  const dest = sessionDir(root, slug);
  copyDir(LEGACY_SESSION, dest);
  return dest;
}

/** A minimal valid plan for the fixture project, selecting the given pack transformation. */
function samplePlan(slug, transformation = 'boot4-composite', extra = {}) {
  return {
    slug,
    reference_pack: 'references/spring-boot-3-to-4.md',
    source: { platform: { name: 'Spring Boot', version: '3.5.0', source: 'baseline.json' }, language: { name: 'Java', version: '17' } },
    target: { platform: { name: 'Spring Boot', version: '4.1.1', source: 'request' }, language: { name: 'Java', version: '21', source: 'request' } },
    constraints: [{ id: 'C1', statement: 'Java 21 target JDK available', status: 'satisfied' }],
    impact: [{
      id: 'I1', file: 'src/main/java/com/example/fixture/App.java', area: 'JSON handling',
      evidence: 'imports com.fasterxml.jackson.databind.ObjectMapper', reference_rule: 'section 2', risk: 'medium',
      verification: 'test-compile on JDK 21', handled_by: 'deterministic', expected_symptoms: ['com.fasterxml.jackson.databind', 'ObjectMapper'],
    }],
    characterization: [{ probe: null, protects: ['I1'], category: 'serialization', reason: 'fixture is never started' }],
    deterministic_candidates: [{ id: transformation, provider: 'openrewrite', transformation, covers: ['I1'] }],
    residual_candidates: [],
    out_of_scope: ['anything not required by the jump'],
    stop_conditions: ['round 0 does not compile'],
    ...extra,
  };
}

/**
 * Sets up a v2 session on the fixture project up to "plan ready": detect, sandbox, round 0 with the
 * fake build tool, a synthetic baseline runtime record (the fixture never starts), and a plan.
 */
function preparedSession(root, { slug = 'fixture', jdk, transformation = 'boot4-composite', plan = true, extraEnv = {} } = {}) {
  const mvn = fakeMaven(path.join(root, 'bin'));
  const env = envFor(root, { MIGRATION_MVN: mvn, FAKE_MVN_CALLS: path.join(root, 'calls.jsonl'), ...extraEnv });
  const steps = [
    runScript('detect-baseline.js', ['--project', SAMPLE_PROJECT, '--slug', slug, '--to-java', '21', '--to-version', '4.1.1'], env),
    runScript('prepare-workspace.js', ['--slug', slug], env),
    runScript('run-migration-build.js', ['--slug', slug, '--baseline', '--jdk', String(jdk), '--intent', 'compile'], env),
  ];
  const dir = sessionDir(root, slug);
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'runtime', 'baseline.json'), JSON.stringify({
    slug, phase: 'baseline', generated_at: new Date().toISOString(), jdk: { major: jdk, version: String(jdk) },
    started: false, failure: 'test fixture is never started', probes: [],
  }, null, 2));
  if (plan) fs.writeFileSync(path.join(dir, 'migration-plan.json'), JSON.stringify(samplePlan(slug, transformation), null, 2));
  return { env, dir, steps, mvn };
}

module.exports = {
  SKILL_DIR, FIXTURES, SAMPLE_PROJECT, LEGACY_SESSION, IS_WIN,
  tempRoot, copyDir, treeHash, fakeMaven, anyJdkMajor, envFor, runScript, freshModules,
  sessionDir, installLegacySession, samplePlan, preparedSession,
};
