/**
 * Stage 2 routing: every plan gets exactly one Fix Type, decided from the strategy *and* the build
 * descriptor on disk — never from a keyword or the mere presence of a dependency.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { classifyFixType, migrationRequestComment } = require('../scripts/lib/routing');

function repoWithPom(parentVersion, javaVersion = '17') {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), '04a-routing-'));
  const dir = path.join(root, 'src', 'app');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'pom.xml'), `<project>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>${parentVersion}</version></parent>
  <properties><java.version>${javaVersion}</java.version><poi.version>5.0.0</poi.version></properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>\${poi.version}</version></dependency>
  </dependencies>
</project>`);
  return root;
}

const migration = (extra = {}) => ({
  project: 'src/app', platform: 'Spring Boot', platform_coordinate: 'org.springframework.boot:spring-boot-starter-parent',
  source_version: '3.5.0', target_version: '4.1.1', source_java: '17', target_java: '21', ...extra,
});

test('a platform generation jump proven by the descriptor is VERSION_MIGRATION, routed to 04d', () => {
  const r = classifyFixType({ version_migration: migration() }, { repoRoot: repoWithPom('3.5.0') });
  assert.deepEqual(r.errors, []);
  assert.equal(r.fix_type, 'VERSION_MIGRATION');
  assert.equal(r.skill, '04d-version-migration');
  assert.ok(r.evidence.some((e) => /declares org\.springframework\.boot:spring-boot-starter-parent 3\.5\.0/.test(e)));
  assert.ok(r.evidence.some((e) => /major framework generation changes: 3\.x → 4\.x/.test(e)));
});

test('a migration claim the descriptor contradicts is refused, not re-routed', () => {
  const wrongSource = classifyFixType({ version_migration: migration() }, { repoRoot: repoWithPom('2.7.12') });
  assert.equal(wrongSource.fix_type, 'VERSION_MIGRATION');
  assert.match(wrongSource.errors.join(' '), /declares .* 2\.7\.12 .*not the stated source_version 3\.5\.0/);

  const noJump = classifyFixType({ version_migration: migration({ target_version: '3.5.6', target_java: '17' }) }, { repoRoot: repoWithPom('3.5.0') });
  assert.match(noJump.errors.join(' '), /stays in one generation/);

  const noProject = classifyFixType({ version_migration: migration({ project: 'src/missing' }) }, { repoRoot: repoWithPom('3.5.0') });
  assert.match(noProject.errors.join(' '), /no pom\.xml/);
});

test('a Java-level jump alone is a VERSION_MIGRATION', () => {
  const r = classifyFixType({ version_migration: migration({ target_version: '3.5.0' }) }, { repoRoot: repoWithPom('3.5.0') });
  assert.deepEqual(r.errors, []);
  assert.ok(r.evidence.some((e) => /Java target changes: 17 → 21/.test(e)));
});

test('a single library bump is DEPENDENCY_UPGRADE; bumping the platform parent across a generation is refused', () => {
  const repo = repoWithPom('3.5.0');
  const lib = classifyFixType({
    dependency_upgrade: { maven_coordinate: 'org.apache.poi:poi-ooxml', current_version: '5.0.0', minimum_fixed_version: '5.4.0' },
    affected_files: [{ file: 'src/app/pom.xml', planned_change: 'bump' }],
  }, { repoRoot: repo });
  assert.deepEqual(lib.errors, []);
  assert.equal(lib.skill, '04c-dependency-upgrader');

  const platform = classifyFixType({
    dependency_upgrade: { maven_coordinate: 'org.springframework.boot:spring-boot-starter-parent', current_version: '3.5.0', minimum_fixed_version: '4.1.1' },
    affected_files: [{ file: 'src/app/pom.xml', planned_change: 'bump' }],
  }, { repoRoot: repo });
  assert.match(platform.errors.join(' '), /plan it as version_migration/);
});

test('no migration or upgrade recorded is a CODE_FIX; both at once is an error', () => {
  assert.equal(classifyFixType({}, { repoRoot: repoWithPom('3.5.0') }).fix_type, 'CODE_FIX');
  const both = classifyFixType({ version_migration: migration(), dependency_upgrade: { maven_coordinate: 'a:b', current_version: '1', minimum_fixed_version: '2' } }, { repoRoot: repoWithPom('3.5.0') });
  assert.match(both.errors.join(' '), /never both/);
});

test('the machine-readable migration request 04d reads back is an HTML comment carrying the exact request', () => {
  const comment = migrationRequestComment('ISSUE-005', migration());
  const m = /^<!-- 04d-migration-request (\{.*\}) -->$/.exec(comment);
  assert.ok(m, comment);
  const request = JSON.parse(m[1]);
  assert.equal(request.issue_id, 'ISSUE-005');
  assert.equal(request.target_version, '4.1.1');
  assert.equal(request.project, 'src/app');
});
