/**
 * The migration ladder: any published Boot line to any later one, OpenRewrite at the centre.
 * Path planning (edges, boundaries, licence frontier, Spring Cloud trains), the generated edge
 * recipe, the licence gate, edge ordering and edge completion, and endpoint inventory.
 * Everything runs offline (MIGRATION_OFFLINE=1 via helpers.envFor) on the ladder's recorded versions.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const {
  SAMPLE_PROJECT, tempRoot, copyDir, envFor, runScript, sessionDir, fakeMaven, freshModules, anyJdkMajor,
} = require('./helpers');

const JDK = anyJdkMajor();
const needsJdk = { skip: JDK ? false : 'no JDK installed — build-driving tests need one' };
const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));

function fixture(root, { parent = '3.5.0', java = '17', cloud = null } = {}) {
  const dir = path.join(root, 'project');
  copyDir(SAMPLE_PROJECT, dir);
  const pom = path.join(dir, 'pom.xml');
  let text = fs.readFileSync(pom, 'utf8').replace('<version>3.5.0</version>', `<version>${parent}</version>`)
    .replace('<java.version>17</java.version>', `<java.version>${java}</java.version>`);
  if (cloud) {
    text = text.replace('<java.version>', `<spring-cloud.version>${cloud}</spring-cloud.version>\n        <java.version>`)
      .replace('</dependencies>', `</dependencies>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>\${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>`);
  }
  fs.writeFileSync(pom, text);
  return dir;
}

function detect(root, project, slug, extra) {
  const env = envFor(root, { MIGRATION_MVN: fakeMaven(path.join(root, 'bin')) });
  const r = runScript('detect-baseline.js', ['--project', project, '--slug', slug, ...extra], env);
  const file = path.join(sessionDir(root, slug), 'baseline.json');
  return { r, env, baseline: fs.existsSync(file) ? readJson(file) : null };
}

const shape = (edges) => edges.map((e) => `${e.id} ${e.from}->${e.to} ${e.class} ${e.recipe_source}${e.recipe ? `:${e.recipe.split('.').pop()}` : ''}${e.composite ? `:${path.basename(e.composite)}` : ''} [${e.stack}]`);

test('16. 2.7 → 4.1.1 under open-source-only: every major gets a boundary edge, only Apache-2.0 recipes run', () => {
  const root = tempRoot('ladder-oss');
  const { r, baseline } = detect(root, fixture(root, { parent: '2.7.12' }), 'l1', ['--to-version', '4.1.1', '--to-java', '21']);
  assert.equal(r.status, 0, r.out);
  const mp = baseline.migration_path;
  assert.equal(mp.status, 'SUPPORTED');
  assert.equal(mp.policy, 'open-source-only');
  assert.deepEqual(shape(mp.edges), [
    'E1 2.7.12->2.7.18 PATCH pin-only [core-apache-2.0]',
    'E2 2.7.18->3.0.13 MAJOR_BOUNDARY upstream:UpgradeSpringBoot_3_0 [apache-2.0]',
    'E3 3.0.13->3.3.13 MINOR upstream:UpgradeSpringBoot_3_3 [apache-2.0]',
    'E4 3.3.13->3.5.16 MINOR pin-only [core-apache-2.0]',
    'E5 3.5.16->4.0.8 MAJOR_BOUNDARY oss-composite:spring-boot-3-to-4.oss.yml [core-apache-2.0]',
    'E6 4.0.8->4.1.1 MINOR pin-only [core-apache-2.0]',
  ]);
  assert.ok(mp.edges.every((e) => e.open_source === true && /Apache/.test(e.license)), 'nothing source-available is planned');
  assert.equal(mp.edges.filter((e) => e.class === 'MAJOR_BOUNDARY').length, 2, 'one boundary per major crossed');
  assert.equal(mp.edges[1].java, '17', 'the 3.0 boundary raises Java to the 3.x floor');
  assert.equal(mp.edges[5].java, '21', 'the landing edge reaches the requested Java');
  assert.equal(mp.edges[4].pack, 'spring-boot-3-to-4', 'the 3→4 boundary carries its rules pack');
  assert.equal(baseline.license_policy, 'open-source-only');
});

test('16b. source-available is an explicit choice: it swaps in the newer upstream recipes and says so', () => {
  const root = tempRoot('ladder-sa');
  const { r, baseline } = detect(root, fixture(root, { parent: '2.7.12' }), 'l2', ['--to-version', '4.1.1', '--license-policy', 'source-available']);
  assert.equal(r.status, 0, r.out);
  const boundary4 = baseline.migration_path.edges.find((e) => e.to === '4.0.8');
  assert.equal(boundary4.recipe, 'org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0');
  assert.equal(boundary4.open_source, false);
  assert.equal(boundary4.license, 'Moderne Source Available License');
  assert.equal(baseline.license_policy, 'source-available');
});

test('16c. a Spring Cloud application is never planned onto a Boot line with no GA train', () => {
  const root = tempRoot('ladder-cloud');
  const project = fixture(root, { parent: '2.7.12', cloud: '2021.0.7' });
  // Spring Cloud 2025.1 is GA for Boot 4.0/4.1, so a Cloud application is planned all the way.
  const boot4 = detect(root, project, 'c0', ['--to-version', '4.1.1']);
  assert.equal(boot4.r.status, 0, boot4.r.out);
  assert.deepEqual(boot4.baseline.migration_path.edges.map((e) => e.cloud_train), ['2021.0.9', '2022.0.5', '2023.0.6', '2025.0.3', '2025.1.3', '2025.1.3']);
  // A line whose train is not GA blocks the path and names the closest supportable target.
  const { references } = freshModules(root);
  const ladder = references.loadLadder();
  const noTrain = { ...ladder, cloud: { ...ladder.cloud, trains: { ...ladder.cloud.trains, '4.0': null, '4.1': null } } };
  const blocked = references.planLadderPath(noTrain, { source: '2.7.12', target: '4.1.1', cloud: { used: true, published: {} } });
  assert.equal(blocked.status, 'BLOCKED_ECOSYSTEM');
  assert.equal(blocked.closest_supported_target, '3.5.16');
  const ok = detect(root, project, 'c2', ['--to-version', '3.5']);
  assert.equal(ok.r.status, 0, ok.r.out);
  assert.equal(ok.baseline.target.platform.version, '3.5.16', 'a bare line resolves to its latest GA patch');
  assert.deepEqual(ok.baseline.migration_path.edges.map((e) => e.cloud_train), ['2021.0.9', '2022.0.5', '2023.0.6', '2025.0.3']);
  assert.equal(ok.baseline.migration_path.evidence.cloud.property, 'spring-cloud.version');
});

test('16d. a target past the last rung is a missing capability, never a jump', () => {
  const root = tempRoot('ladder-5');
  const { r, baseline } = detect(root, SAMPLE_PROJECT, 'l5', ['--to-version', '5.0.0']);
  assert.equal(r.status, 2, r.out);
  assert.equal(baseline.migration_path.status, 'UNSUPPORTED_MIGRATION_PATH');
  assert.deepEqual(baseline.migration_path.missing_capability, ['ladder-rung:spring-boot-5.0']);
});

test('16e. the generated edge recipe pins the platform, Java and Cloud train with Apache-2.0 core recipes only', () => {
  const root = tempRoot('ladder-yml');
  const { openrewrite, references } = freshModules(root);
  const ladder = references.loadLadder();
  const plan = references.planLadderPath(ladder, {
    source: '2.7.12', target: '3.5.16', javaFrom: '17', javaTarget: '21', cloud: { used: true, published: {} },
  });
  const boundary = plan.edges.find((e) => e.class === 'MAJOR_BOUNDARY');
  const yml = openrewrite.renderEdgeRecipe(boundary, { buildTool: 'maven', parent: true, javaProperties: ['java.version'], cloudProperty: 'spring-cloud.version' });
  assert.match(yml, /name: mars\.migration\.edge\.E2/);
  assert.match(yml, /- org\.openrewrite\.java\.spring\.boot3\.UpgradeSpringBoot_3_0/);
  // The parent pin is a plain XML edit: ChangeParentPom would also drop explicit versions the new
  // parent manages (observed in validation run V2) — a library change nobody asked for.
  assert.match(yml, /org\.openrewrite\.xml\.ChangeTagValue:\n\s+elementName: \/project\/parent\/version\n\s+oldValue: 2\.7\.18\n\s+newValue: 3\.0\.13/);
  assert.doesNotMatch(yml, /ChangeParentPom/);
  // Explicitly versioned platform artifacts (a composite's AddDependency writes one) move with the
  // platform, so the next edge cannot leave them behind (observed in validation run V2b).
  assert.match(yml, /org\.openrewrite\.maven\.UpgradeDependencyVersion:\n\s+groupId: org\.springframework\.boot\n\s+artifactId: "\*"\n\s+newVersion: 3\.0\.13/);
  // A plan can narrow an upstream edge recipe to some of its sub-recipes.
  const narrowed = openrewrite.renderEdgeRecipe(boundary, { buildTool: 'maven', parent: true, recipes: ['org.openrewrite.java.migrate.jakarta.JavaxMigrationToJakarta'] });
  assert.match(narrowed, /- org\.openrewrite\.java\.migrate\.jakarta\.JavaxMigrationToJakarta/);
  assert.doesNotMatch(narrowed, /UpgradeSpringBoot_3_0/);
  assert.match(yml, /key: spring-cloud\.version\n\s+newValue: 2022\.0\.5/);
  const landing = plan.edges[plan.edges.length - 1];
  const landingYml = openrewrite.renderEdgeRecipe(landing, { buildTool: 'maven', parent: true, javaProperties: ['java.version'] });
  assert.match(landingYml, /key: java\.version\n\s+newValue: "21"/, 'the landing edge raises Java to the request');
  // The open-source 3 -> 4 composite is inlined and referenced, with the edge target rendered in.
  const p4 = references.planLadderPath(ladder, { source: '3.5.0', target: '4.1.1' });
  const composite = openrewrite.renderEdgeRecipe(p4.edges.find((e) => e.recipe_source === 'oss-composite'), { buildTool: 'maven', parent: true });
  assert.match(composite, /name: mars\.migration\.oss\.SpringBoot3To4/);
  assert.match(composite, /- mars\.migration\.oss\.SpringBoot3To4/);
  assert.match(composite, /version: "4\.0\.8"/);
  assert.doesNotMatch(composite, /org\.openrewrite\.java\.spring\./, 'no source-available recipe in the open-source composite');
  assert.throws(() => openrewrite.renderEdgeRecipe({ ...landing, to: '4.1.1; rm -rf /' }, {}), /not a plain identifier or version/);
});

test('16f. licence gate: under open-source-only a source-available pack recipe never runs', needsJdk, () => {
  const root = tempRoot('ladder-licence');
  const mvn = fakeMaven(path.join(root, 'bin'));
  const env = envFor(root, { MIGRATION_MVN: mvn });
  runScript('detect-baseline.js', ['--project', SAMPLE_PROJECT, '--slug', 'lic', '--to-java', '21', '--to-version', '4.1.1', '--reference', 'spring-boot-3-to-4'], env);
  runScript('prepare-workspace.js', ['--slug', 'lic'], env);
  runScript('run-migration-build.js', ['--slug', 'lic', '--baseline', '--jdk', String(JDK), '--intent', 'compile'], env);
  const dir = sessionDir(root, 'lic');
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'runtime', 'baseline.json'), JSON.stringify({ slug: 'lic', phase: 'baseline', started: false, probes: [] }));
  const { samplePlan } = require('./helpers');
  fs.writeFileSync(path.join(dir, 'migration-plan.json'), JSON.stringify(samplePlan('lic', 'boot4-composite'), null, 2));
  const r = runScript('run-migration-build.js', ['--slug', 'lic', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...env, FAKE_MVN_MODE: 'dryrun-ok' });
  assert.equal(r.status, 1, r.out);
  const record = readJson(path.join(dir, 'transformations', 'rewrite-00.json'));
  assert.equal(record.status, 'rejected-license');
  assert.equal(record.open_source, false);
  const calls = fs.existsSync(path.join(root, 'calls.jsonl')) ? fs.readFileSync(path.join(root, 'calls.jsonl'), 'utf8') : '';
  assert.doesNotMatch(calls, /rewrite-maven-plugin/, 'OpenRewrite was never started');
});

test('16g. edges run in order: a later edge is refused until each earlier one stands green on its version', needsJdk, () => {
  const root = tempRoot('ladder-order');
  const { r, env } = detect(root, SAMPLE_PROJECT, 'ord', ['--to-version', '4.1.1', '--to-java', '21']);
  assert.equal(r.status, 0, r.out);
  const runEnv = { ...env, FAKE_MVN_CALLS: path.join(root, 'calls.jsonl') };
  runScript('prepare-workspace.js', ['--slug', 'ord'], runEnv);
  runScript('run-migration-build.js', ['--slug', 'ord', '--baseline', '--jdk', String(JDK), '--intent', 'compile'], runEnv);
  const dir = sessionDir(root, 'ord');
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'runtime', 'baseline.json'), JSON.stringify({ slug: 'ord', phase: 'baseline', started: false, probes: [] }));
  const { samplePlan } = require('./helpers');
  const plan = samplePlan('ord', 'boot4-composite');
  plan.deterministic_candidates = ['E1', 'E2', 'E3'].map((id) => ({ id, provider: 'openrewrite', transformation: `edge:${id}`, edge: id, covers: ['I1'] }));
  fs.writeFileSync(path.join(dir, 'migration-plan.json'), JSON.stringify(plan, null, 2));

  const early = runScript('run-migration-build.js', ['--slug', 'ord', '--edge', 'E2', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...runEnv, FAKE_MVN_MODE: 'dryrun-ok' });
  assert.equal(early.status, 1, early.out);
  assert.match(early.out, /edges run in order, and E1 is not complete/);

  const dry = runScript('run-migration-build.js', ['--slug', 'ord', '--edge', 'E1', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...runEnv, FAKE_MVN_MODE: 'dryrun-ok' });
  assert.equal(dry.status, 0, dry.out);
  const record = readJson(path.join(dir, 'transformations', 'rewrite-00.json'));
  assert.equal(record.edge, 'E1');
  assert.equal(record.status, 'previewed');
  assert.equal(record.open_source, true);
  const yml = fs.readFileSync(path.join(dir, 'transformations', 'rewrite-00.rewrite.yml'), 'utf8');
  assert.match(yml, /oldValue: 3\.5\.0\n\s+newValue: 3\.5\.16/);
  const calls = fs.readFileSync(path.join(root, 'calls.jsonl'), 'utf8');
  assert.match(calls, /rewrite-maven-plugin:6\.46\.1:dryRunNoFork/, 'pin-only edges run on the Apache-2.0 core stack');
  assert.match(calls, /-Drewrite\.activeRecipes=mars\.migration\.edge\.E1/);

  // E1 lands when a build tagged with it compiles on 3.5.16 (here the pin is made by hand, as a
  // residual edit would be when a recipe is unavailable).
  const pom = path.join(dir, 'workspace', 'pom.xml');
  fs.writeFileSync(pom, fs.readFileSync(pom, 'utf8').replace('<version>3.5.0</version>', '<version>3.5.16</version>'));
  const build = runScript('run-migration-build.js', ['--slug', 'ord', '--edge', 'E1', '--jdk', String(JDK), '--intent', 'compile'], runEnv);
  assert.equal(build.status, 0, build.out);
  assert.match(build.out, /Edge E1 3\.5\.0 → 3\.5\.16: COMPLETE/);
  const round = readJson(path.join(dir, 'rounds', 'round-01.json'));
  assert.equal(round.edge, 'E1');
  const next = runScript('run-migration-build.js', ['--slug', 'ord', '--edge', 'E2', '--jdk', String(JDK), '--rewrite', 'dry-run'], { ...runEnv, FAKE_MVN_MODE: 'dryrun-ok' });
  assert.equal(next.status, 0, next.out);
  const e2 = readJson(path.join(dir, 'transformations', 'rewrite-01.json'));
  assert.equal(e2.edge, 'E2');
  assert.match(fs.readFileSync(path.join(dir, 'transformations', 'rewrite-01.rewrite.yml'), 'utf8'), /mars\.migration\.oss\.SpringBoot3To4/);
});

test('16h. endpoint inventory: detect records what the source maps; --discover drafts only safe probes', () => {
  const root = tempRoot('ladder-endpoints');
  const { r, env, baseline } = detect(root, SAMPLE_PROJECT, 'ep', ['--to-version', '4.1.1']);
  assert.equal(r.status, 0, r.out);
  const endpoints = baseline.observations.endpoints;
  assert.ok(endpoints.length >= 1, 'the fixture controller is in the inventory');
  assert.ok(endpoints.every((e) => e.method && e.path.startsWith('/') && e.file && e.line));
  const d = runScript('probe-runtime.js', ['--slug', 'ep', '--discover'], env);
  assert.equal(d.status, 0, d.out);
  const draft = readJson(path.join(sessionDir(root, 'ep'), 'probes.discovered.json'));
  assert.ok(draft.requests.every((q) => q.method === 'GET'), 'only non-mutating requests are drafted');
  assert.equal(draft.requests.length - 1 + draft.not_probed.length, endpoints.length, 'every endpoint is drafted or listed as not probed');
  const { migration } = freshModules(root);
  const cmp = migration.compareEndpoints([{ method: 'GET', path: '/a' }, { method: 'POST', path: '/b' }], [{ method: 'GET', path: '/a' }, { method: 'GET', path: '/c' }]);
  assert.deepEqual(cmp.missing, ['POST /b']);
  assert.deepEqual(cmp.added, ['GET /c']);
});

test('16i. fixes from the real runs: composite order, required params, build-failed outcome, status-change rule', () => {
  const root = tempRoot('ladder-fixes');
  const { migration, references } = freshModules(root);

  // The open-source composite adds the Boot 4 test starters FIRST, keyed on the Boot 3 types the
  // project still uses (keyed on the Boot 4 types, which are unresolvable until the starter exists,
  // it never fired — V2).
  assert.equal(references.loadLadder().oss_composites['4'], 'openrewrite/spring-boot-3-to-4.oss.yml');
  const composite = fs.readFileSync(path.join(__dirname, '..', 'references', 'openrewrite', 'spring-boot-3-to-4.oss.yml'), 'utf8');
  const addAt = composite.indexOf('org.openrewrite.maven.AddDependency');
  const moveAt = composite.indexOf('org.openrewrite.java.ChangeType');
  assert.ok(addAt !== -1 && addAt < moveAt, 'AddDependency runs before the type moves');
  assert.match(composite, /onlyIfUsing: org\.springframework\.boot\.test\.autoconfigure\.web\.servlet\.\*/);

  // Required @RequestParam endpoints are not drafted as bare GET probes (they only prove a 400).
  const project = path.join(root, 'proj');
  fs.mkdirSync(path.join(project, 'src', 'main', 'java', 'x'), { recursive: true });
  fs.writeFileSync(path.join(project, 'src', 'main', 'java', 'x', 'C.java'), [
    '@RestController', '@RequestMapping("/api")', 'public class C {',
    '  @GetMapping("/a") public String a() { return ""; }',
    '  @GetMapping("/b") public String b(@RequestParam String name) { return name; }',
    '  @GetMapping("/c") public String c(@RequestParam(required = false) String q) { return q; }',
    '  @GetMapping("/d") public String d(@RequestParam(value = "dept", defaultValue = "x") String d) { return d; }',
    '}',
  ].join('\n'));
  const eps = migration.scanEndpoints(project);
  const byPath = Object.fromEntries(eps.map((e) => [e.path, e.required_params || []]));
  assert.deepEqual(byPath, { '/api/a': [], '/api/b': ['name'], '/api/c': [], '/api/d': [] });

  // A plugin failing after compilation (an old coverage plugin on Java 21 classes) is not a compile failure.
  const plugin = [{ category: 'plugin-failure', message: 'Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.7:report' },
    { category: 'java-release', message: 'Unsupported class file major version 65' }];
  assert.equal(migration.buildOutcome({ status: 1 }, plugin), 'build-failed');
  assert.equal(migration.buildOutcome({ status: 1 }, [{ category: 'missing-symbol', message: 'cannot find symbol' }, ...plugin]), 'compile-failed');
  assert.equal(migration.buildOutcome({ status: 1 }, [{ category: 'java-release', message: 'invalid target release: 21' }, plugin[0]]), 'compile-failed');

  // A status change FAILs unless classified as an expected framework change; then it needs a human.
  const { deriveStatus } = require(path.join(__dirname, '..', 'scripts', 'lib', 'summary.js'));
  const base = {
    state: null, inferred: { reached: ['RENDERED'], state: 'RENDERED' }, finished: true,
    rounds: [{ round: 0, baseline: true, outcome: 'passed', build: { intent: 'package' } }, { round: 1, outcome: 'passed', build: { intent: 'package' } }],
    baseline: {}, tests: null, finalDeclared: '3.5.16', requested: '3.5.16', endpoints: null, edges: [],
  };
  const row = { name: 'trailing slash', verdict: 'status-differs' };
  const unclassified = deriveStatus({ ...base, migration: {}, comparison: { total: 1, rows: [{ ...row, classification: null }] } });
  assert.equal(unclassified.status, 'FAIL');
  const accepted = deriveStatus({ ...base, migration: {}, comparison: { total: 1, rows: [{ ...row, classification: 'expected-framework-change' }] } });
  assert.equal(accepted.status, 'PARTIAL PASS');
  assert.match(accepted.reason, /need human acceptance: trailing slash/);
});

test('16j. fixes from V2b: framework endpoints from configuration, repair rounds tagged with their edge', () => {
  const root = tempRoot('ladder-v2b');
  const { migration } = freshModules(root);

  // Endpoints no controller maps but configuration switches on are in the inventory, so --discover
  // probes them and a major upgrade cannot drop them silently (Boot 4 moved the H2 console out).
  const yml = path.join(root, 'yml');
  fs.mkdirSync(path.join(yml, 'src', 'main', 'resources'), { recursive: true });
  fs.writeFileSync(path.join(yml, 'src', 'main', 'resources', 'application.yml'), [
    'spring:', '  h2:', '    console:', '      enabled: true', '      path: /db-console  # comment',
    'management:', '  endpoints:', '    web:', '      base-path: /manage', '      exposure:',
    '        include:', '          - health', '          - "info"', '          - env', '        exclude: env',
    '---', 'spring:', '  config:', '    activate:', '      on-profile: prod', 'management:', '  endpoints:', '    web:', '      exposure:', '        include: "*"',
  ].join('\n'));
  const eps = migration.scanEndpoints(yml);
  assert.deepEqual(eps.map((e) => `${e.method} ${e.path}`), ['GET /db-console', 'GET /manage/health', 'GET /manage/info']);
  assert.ok(eps.every((e) => e.source === 'config' && e.file === 'src/main/resources/application.yml' && e.line > 0));

  const props = path.join(root, 'props');
  fs.mkdirSync(path.join(props, 'src', 'main', 'resources'), { recursive: true });
  fs.writeFileSync(path.join(props, 'src', 'main', 'resources', 'application.properties'),
    '# no console\nspring.h2.console.enabled=false\nmanagement.endpoints.web.exposure.include=health,metrics\nmanagement.server.port=9090\n');
  assert.deepEqual(migration.scanEndpoints(props), [], 'a disabled console and a separate management port add nothing');

  // A residual repair round after an edge's apply carries no --edge; it is tagged with the open edge
  // whose target the sandbox declares, so the edge completes and the next edge is offered (V2b).
  const { openEdgeFor } = require(path.join(__dirname, '..', 'scripts', 'run-migration-build.js'));
  const edges = [{ id: 'E1', seq: 1, from: '3.5.0', to: '3.5.16' }, { id: 'E2', seq: 2, from: '3.5.16', to: '4.0.8' }];
  const rounds = path.join(sessionDir(root, 'inf'), 'rounds');
  fs.mkdirSync(rounds, { recursive: true });
  fs.writeFileSync(path.join(rounds, 'round-01.json'), JSON.stringify({ round: 1, edge: 'E1', outcome: 'passed', declared: { platform: { version: '3.5.16' } } }));
  assert.equal(openEdgeFor('inf', edges, { version: '4.0.8' }).id, 'E2');
  assert.equal(openEdgeFor('inf', edges, { version: '3.5.16' }), null, 'a completed edge is not reopened');
  assert.equal(openEdgeFor('inf', edges, null), null);
});

test('16k. the apply gate refuses an unfinished migration path and a lost endpoint', () => {
  const root = tempRoot('ladder-apply');
  freshModules(root);
  const { eligibility } = require(path.join(__dirname, '..', 'scripts', 'apply-migration.js'));
  const dir = sessionDir(root, 'gate');
  fs.mkdirSync(path.join(dir, 'rounds'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'runtime'), { recursive: true });
  const write = (file, data) => fs.writeFileSync(path.join(dir, file), JSON.stringify(data));
  write('baseline.json', {
    slug: 'gate', project: { dir: path.join(root, 'nowhere'), descriptor: 'pom.xml' }, language: { declared: '17' }, build_tool: { tool: 'maven', kind: 'system' }, target: { platform: { version: '4.0.8' } },
    migration_path: { status: 'SUPPORTED', edges: [
      { id: 'E1', seq: 1, class: 'PATCH', role: 'transit', from: '3.5.0', to: '3.5.16', notes: [] },
      { id: 'E2', seq: 2, class: 'MAJOR_BOUNDARY', role: 'landing', from: '3.5.16', to: '4.0.8', notes: [] },
    ] },
    observations: { endpoints: [{ method: 'GET', path: '/a', file: 'A.java', line: 1 }] },
  });
  write('workspace.json', { baseline_commit: 'none' });
  write('rounds/round-00.json', { round: 0, baseline: true, outcome: 'passed', build: { intent: 'package' }, declared: { platform: { version: '3.5.0' } } });
  write('rounds/round-01.json', { round: 1, edge: 'E1', outcome: 'passed', build: { intent: 'package' }, declared: { platform: { version: '3.5.16' } } });
  const inv = (paths) => ({ started: true, endpoint_inventory: { available: true, endpoints: paths } });
  write('runtime/baseline.json', inv(['GET /a', 'GET /h2-console']));
  write('runtime/final.json', inv(['GET /a']));
  const { reasons } = eligibility('gate');
  assert.ok(reasons.some((r) => /edge E2 \(3\.5\.16 → 4\.0\.8\) never stood green/.test(r)), reasons.join('\n'));
  assert.ok(reasons.some((r) => /endpoint\(s\) lost \(running application\): GET \/h2-console/.test(r)), reasons.join('\n'));
});
