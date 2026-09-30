#!/usr/bin/env node
/**
 * Agent 04 consistency check.
 *
 * Agent 04's skills (.github/skills/04a..04e) are working copies of files that MARS's Java harness
 * reads in place from legacy-sources/ (see policies/default/unified-policy.json). The two must agree,
 * or Harness mode and Pipeline mode would route the same finding differently. This script fails if:
 *
 *   - any of the five skills, the support library, or a Claude wrapper is missing or misnamed
 *   - the agent definitions do not name all five skills
 *   - the merged CWE catalog differs from the catalog the harness loads
 *     (VRH's catalog plus the Spring migration reference's supplemental entries)
 *   - the knowledge base or ranking weights differ from the files the harness loads
 *   - the 04e reference pack differs from the pack the harness's rules are pinned to
 *     (compared ignoring line endings)
 *   - a script still points at a pre-merge skill path
 *   - the three renderers that share docs/agent_output/04-remediation/README.md write different text
 *   - any skill script fails `node --check`
 *
 * Usage (from the repository root):  node .github/scripts/agent04-check.js
 * Zero dependencies. Read-only.
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const assert = require('assert');

const ROOT = path.resolve(__dirname, '..', '..');
const SKILLS = path.join(ROOT, '.github', 'skills');
const FIVE = [
  '04a-fix-strategist',
  '04b-fixer',
  '04c-remediation-intelligence',
  '04d-remediation-research',
  '04e-version-migration',
];
const SUPPORT = ['00-issue-register'];

const failures = [];
let checks = 0;
const fail = (msg) => failures.push(msg);
const rel = (p) => path.relative(ROOT, p).split(path.sep).join('/');
const read = (p) => fs.readFileSync(p, 'utf8');
const readJson = (p) => JSON.parse(read(p));
const lf = (s) => s.replace(/\r\n/g, '\n');

function check(label, fn) {
  checks += 1;
  try {
    fn();
  } catch (e) {
    fail(`${label}: ${e.message.split('\n')[0]}`);
  }
}

function frontMatterName(file) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(read(file));
  if (!m) return null;
  const line = m[1].split(/\r?\n/).find((l) => l.startsWith('name:'));
  return line ? line.slice(5).trim().replace(/^['"]|['"]$/g, '') : null;
}

function withoutDollarKeys(obj) {
  return Object.fromEntries(Object.entries(obj).filter(([k]) => !k.startsWith('$')));
}

// --- 1. skills, support library and Claude wrappers -------------------------------------------
for (const skill of [...FIVE, ...SUPPORT]) {
  check(`skill ${skill}`, () => {
    const file = path.join(SKILLS, skill, 'SKILL.md');
    assert(fs.existsSync(file), `missing ${rel(file)}`);
    assert.strictEqual(frontMatterName(file), skill, `front matter name in ${rel(file)}`);
  });
  check(`Claude wrapper ${skill}`, () => {
    const file = path.join(ROOT, '.claude', 'skills', skill, 'SKILL.md');
    assert(fs.existsSync(file), `missing ${rel(file)}`);
    assert.strictEqual(frontMatterName(file), skill, `front matter name in ${rel(file)}`);
  });
}

// --- 2. agent definitions name all five skills ------------------------------------------------
check('canonical agent definition', () => {
  const file = path.join(ROOT, '.github', 'agents', '04_fix-generator.agent.md');
  const text = read(file);
  for (const skill of FIVE) assert(text.includes(skill), `${rel(file)} does not mention ${skill}`);
});
check('Claude agent definition', () => {
  const file = path.join(ROOT, '.claude', 'agents', '04-fix-generator.md');
  const m = /^skills:\s*\[([^\]]*)\]/m.exec(read(file));
  assert(m, `${rel(file)} has no skills: [...] list`);
  const listed = m[1].split(',').map((s) => s.trim());
  for (const skill of [...SUPPORT, ...FIVE]) assert(listed.includes(skill), `${rel(file)} skills list lacks ${skill}`);
});

// --- 3. knowledge agrees with what the harness loads ------------------------------------------
const policy = readJson(path.join(ROOT, 'policies', 'default', 'unified-policy.json')).security;

check('merged CWE catalog', () => {
  const vrh = withoutDollarKeys(readJson(path.join(ROOT, policy.catalog_file)));
  const supplemental = withoutDollarKeys(readJson(path.join(ROOT, policy.supplemental_catalog_file)));
  // VrhKnowledge: the supplemental file only adds entries the VRH catalog does not have.
  const effective = { ...vrh };
  for (const [cwe, entry] of Object.entries(supplemental)) if (!(cwe in effective)) effective[cwe] = entry;
  const merged = withoutDollarKeys(readJson(path.join(SKILLS, '04a-fix-strategist', 'catalog', 'cwe-patterns.json')));
  assert.deepStrictEqual(Object.keys(merged).sort(), Object.keys(effective).sort(), 'catalog CWE sets differ');
  assert.deepStrictEqual(merged, effective, 'catalog entry contents differ');
});
check('knowledge base', () => {
  assert.deepStrictEqual(
    readJson(path.join(SKILLS, '04c-remediation-intelligence', 'knowledge', 'remediation-kb.json')),
    readJson(path.join(ROOT, policy.kb_file)),
    'remediation-kb.json differs from the harness copy',
  );
});
check('ranking weights', () => {
  assert.deepStrictEqual(
    readJson(path.join(SKILLS, '04c-remediation-intelligence', 'ranking-weights.json')),
    readJson(path.join(ROOT, policy.ranking_weights_file)),
    'ranking-weights.json differs from the harness copy',
  );
});
check('04e reference pack', () => {
  const rules = readJson(path.join(ROOT, 'capabilities', 'spring-migration', 'reference-packs', 'spring-boot-3-to-4.rules.json'));
  const pinned = /reference pack (\S+\.md)/.exec(rules.$comment || '');
  assert(pinned, 'cannot find the pinned pack path in the rules file $comment');
  const harnessPack = lf(read(path.join(ROOT, pinned[1].replace(/\.$/, ''))));
  const skillPack = lf(read(path.join(SKILLS, '04e-version-migration', 'references', 'spring-boot-3-to-4.md')));
  assert.strictEqual(skillPack, harnessPack, 'spring-boot-3-to-4.md differs from the pack the harness rules are pinned to');
});

// --- 4. no script points at a pre-merge skill path --------------------------------------------
const scripts = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!['node_modules', '.venv'].includes(entry.name)) walk(full);
    } else if (entry.name.endsWith('.js')) {
      scripts.push(full);
    }
  }
})(SKILLS);

check('stale skill paths', () => {
  const stale = ['skills/04c-dependency-upgrader', 'skills/04d-version-migration'];
  const hits = [];
  for (const file of scripts) {
    const text = read(file);
    for (const s of stale) if (text.includes(s)) hits.push(`${rel(file)} -> ${s}`);
  }
  assert.strictEqual(hits.length, 0, `stale references: ${hits.join('; ')}`);
});

// --- 5. the shared 04-remediation README index is written identically -------------------------
check('shared README index text', () => {
  const renderers = [
    '04a-fix-strategist/scripts/render-fix-plan.js',
    '04b-fixer/scripts/render-fix-report.js',
    '04b-fixer/scripts/render-dependency-report.js',
  ];
  const blocks = renderers.map((r) => {
    const text = lf(read(path.join(SKILLS, r)));
    const m = /return `# Remediation\n[\s\S]*?\n`;\n/.exec(text);
    assert(m, `no README template found in ${r}`);
    return m[0];
  });
  blocks.slice(1).forEach((b, i) => assert.strictEqual(b, blocks[0], `${renderers[i + 1]} writes different README text from ${renderers[0]}`));
});

// --- 6. every skill script parses --------------------------------------------------------------
for (const file of scripts) {
  check(`syntax ${rel(file)}`, () => {
    const r = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });
    assert.strictEqual(r.status, 0, (r.stderr || '').trim());
  });
}

// --- report ------------------------------------------------------------------------------------
if (failures.length) {
  console.error(`Agent 04 check FAILED: ${failures.length} of ${checks} check(s)`);
  failures.forEach((f) => console.error(`  - ${f}`));
  process.exit(1);
}
console.log(`Agent 04 check passed: ${checks} checks (5 skills, catalog, KB, weights, reference pack, renderers, ${scripts.length} scripts).`);
