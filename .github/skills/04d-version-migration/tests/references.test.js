/**
 * Reference packs: the v1 contract still parses exactly, and the optional v2 metadata parses.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { tempRoot, freshModules } = require('./helpers');

const { references } = freshModules(tempRoot('refs'));

// The v1 reader, verbatim, so the new parser can be held to its output on v1-shaped front matter.
function v1ParseFrontMatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  if (!m) return {};
  const data = {};
  let listKey = null;
  for (const raw of m[1].split(/\r?\n/)) {
    const line = raw.replace(/\s+$/, '');
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const item = /^\s*-\s+(.*)$/.exec(line);
    if (item && listKey) {
      data[listKey].push(item[1].trim().replace(/^['"]|['"]$/g, ''));
      continue;
    }
    const pair = /^([A-Za-z0-9_.\-]+):\s*(.*)$/.exec(line);
    if (!pair) continue;
    const [, key, value] = pair;
    if (!value.trim()) {
      listKey = key;
      data[key] = [];
    } else {
      listKey = null;
      data[key] = value.trim().replace(/^['"]|['"]$/g, '');
    }
  }
  return data;
}

const V1_PACK = `---
id: spring-boot-3-to-4
title: Spring Boot 3.x to 4.x (Java 17 to 21)
stack: Spring Boot
from: "3"
to: "4"
language_from: "17"
language_to: "21"
detect:
  - org.springframework.boot:spring-boot-starter-parent:3
  - org.springframework.boot:spring-boot-dependencies:3
  - org.springframework.boot:spring-boot-starter-web
---

# body
`;

test('1. the shipped Spring Boot pack is found and keeps its v1 identity', () => {
  const packs = references.listReferencePacks();
  const pack = packs.find((p) => p.id === 'spring-boot-3-to-4');
  assert.ok(pack, 'spring-boot-3-to-4 pack is listed');
  assert.equal(pack.title, 'Spring Boot 3.x to 4.x (Java 17 to 21)');
  assert.equal(pack.stack, 'Spring Boot');
  assert.equal(pack.from, '3');
  assert.equal(pack.to, '4');
  assert.equal(pack.language_from, '17');
  assert.equal(pack.language_to, '21');
  assert.deepEqual(pack.detect, [
    'org.springframework.boot:spring-boot-starter-parent:3',
    'org.springframework.boot:spring-boot-dependencies:3',
    'org.springframework.boot:spring-boot-starter-web',
  ]);
  const match = references.matchReferencePacks({
    parent: { groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-parent', version: '3.5.0' },
    dependencies: [], plugins: [],
  });
  assert.equal(match[0].id, 'spring-boot-3-to-4');
  assert.deepEqual(match[0].matched, ['org.springframework.boot:spring-boot-starter-parent:3']);
});

test('2. a v1 pack with no v2 metadata parses identically and normalises to empty optionals', () => {
  assert.deepEqual(references.parseFrontMatter(V1_PACK), v1ParseFrontMatter(V1_PACK));
  const pack = references.normalisePack(references.parseFrontMatter(V1_PACK), 'old.md');
  assert.equal(pack.id, 'spring-boot-3-to-4');
  assert.equal(pack.detect.length, 3);
  assert.deepEqual(pack.transformations, []);
  assert.deepEqual(pack.surfaces, []);
  assert.deepEqual(pack.ecosystem_boms, []);
  assert.equal(pack.provenance, null);
  assert.equal(pack.target_constraints, null);
  assert.equal(pack.migration_path, null);
  // A pack with no front matter at all still yields a usable (empty) pack.
  assert.deepEqual(references.parseFrontMatter('# no front matter'), {});
});

test('3. enhanced metadata parses: provenance, path, surfaces, BOMs and transformations', () => {
  const pack = references.resolveReferencePack('spring-boot-3-to-4');
  assert.ok(pack.provenance.sources.length >= 2);
  assert.equal(pack.migration_path.preparation_line, '3.5');
  assert.equal(pack.target_constraints.language_target, '21');
  const json = pack.surfaces.find((s) => s.id === 'json');
  assert.ok(json.patterns.includes('com\\.fasterxml\\.jackson\\.databind'), 'single-quoted regex keeps its backslashes');
  assert.ok(new RegExp(json.patterns[0]).test('import com.fasterxml.jackson.databind.ObjectMapper;'));
  assert.ok(pack.ecosystem_boms.some((b) => b.coordinate === 'org.springframework.cloud:spring-cloud-dependencies'));

  const curated = pack.transformations.find((t) => t.id === 'boot4-curated');
  assert.equal(curated.provider, 'openrewrite');
  assert.equal(curated.policy, 'optional');
  assert.deepEqual(curated.build_tools, ['maven', 'gradle']);
  assert.deepEqual(curated.artifacts, ['org.openrewrite.recipe:rewrite-spring:6.37.1']);
  assert.equal(curated.plugin_version, '6.46.1');
  assert.equal(curated.license, 'Moderne Source Available License');
  assert.equal(curated.config, 'openrewrite/spring-boot-3-to-4.curated.yml');
  assert.deepEqual(references.transformationProblems(curated), []);
  const composite = pack.transformations.find((t) => t.id === 'boot4-composite');
  assert.equal(composite.recipe_target, '4.0.x');

  // Nested lists of maps with inline comments and flow lists.
  const parsed = references.parseFrontMatter(`---
id: x
transformations:
  - id: a
    recipes:
      - r.One
      - r.Two   # trailing comment
    artifacts: [g:a:1.0, g:b:2.0]
  - id: b
    recipe: r.Three
---`);
  assert.deepEqual(parsed.transformations[0].recipes, ['r.One', 'r.Two']);
  assert.deepEqual(parsed.transformations[0].artifacts, ['g:a:1.0', 'g:b:2.0']);
  assert.deepEqual(references.normaliseTransformation(parsed.transformations[1], 1).recipes, ['r.Three']);
});

test('3b. a floating or malformed transformation is refused for execution', () => {
  const floating = references.normaliseTransformation({ recipes: ['r.X'], artifacts: ['g:a:RELEASE'], plugin_version: 'LATEST' }, 0);
  const problems = references.transformationProblems(floating);
  assert.ok(problems.some((p) => /floating/.test(p) && /g:a:RELEASE/.test(p)));
  assert.ok(problems.some((p) => /plugin_version/.test(p)));
  const injected = references.normaliseTransformation({ recipes: ['r.X; rm -rf /'], artifacts: ['g:a:1'] }, 0);
  assert.ok(references.transformationProblems(injected).some((p) => /not allowed/.test(p)));
  const escape = references.normaliseTransformation({ recipes: ['r.X'], config: '../../etc/x.yml' }, 0);
  assert.ok(references.transformationProblems(escape).some((p) => /inside references/.test(p)));
});
