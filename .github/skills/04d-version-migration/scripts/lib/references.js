/**
 * Version Migration — reference pack discovery.
 *
 * A reference pack is a Markdown file in `references/` carrying front matter that says
 * which stack and version jump it covers and how to recognise a project that needs it.
 * This module only reads that front matter and matches it against a project's declared
 * coordinates — it never encodes any framework's rules itself. Adding a new pack is
 * therefore a documentation change, not a code change.
 *
 * Front matter may also carry optional, nested metadata — provenance, target constraints, the
 * migration path, pack-declared source surfaces to look for, ecosystem BOMs whose compatibility
 * must be checked, and deterministic transformations (OpenRewrite recipes). A pack without any of
 * it parses exactly as before: every optional field normalises to null or an empty list.
 */
const fs = require('fs');
const path = require('path');
const { REFERENCES_DIR } = require('./migration');

// ---------------------------------------------------------------------------
// Front matter — a small YAML subset
//
// Scalars, `- item` lists, nested maps, and lists of maps, by indentation. Every scalar stays a
// string (`from: "3"` and `from: 3` both read as "3"), matching what the original flat reader
// produced, so nothing downstream sees a type change. Single-quoted scalars are literal, which is
// how a pack writes a regular expression without escaping it twice.
// ---------------------------------------------------------------------------

function stripInlineComment(value) {
  if (/^['"]/.test(value)) return value;
  const m = /^(.*?)\s+#.*$/.exec(value);
  return m ? m[1] : value;
}

function parseScalar(raw) {
  const value = stripInlineComment(String(raw).trim());
  if (/^'.*'$/.test(value)) return value.slice(1, -1).replace(/''/g, "'");
  if (/^".*"$/.test(value)) return value.slice(1, -1).replace(/\\"/g, '"');
  if (/^\[.*\]$/.test(value)) {
    const inner = value.slice(1, -1).trim();
    return inner ? inner.split(',').map((s) => parseScalar(s)) : [];
  }
  return value;
}

function tokenise(block) {
  return block
    .split(/\r?\n/)
    .map((raw) => raw.replace(/\s+$/, ''))
    .filter((line) => line.trim() && !line.trim().startsWith('#'))
    .map((line) => ({ indent: line.length - line.trimStart().length, text: line.trim() }));
}

const PAIR = /^([A-Za-z0-9_.\-]+):(?:\s+(.*)|\s*)$/;
const isItem = (text) => text === '-' || text.startsWith('- ');

function parseBlock(lines, start, indent) {
  if (start >= lines.length) return [null, start];
  return isItem(lines[start].text) ? parseList(lines, start, indent) : parseMap(lines, start, indent);
}

function parseMap(lines, start, indent) {
  const out = {};
  let i = start;
  while (i < lines.length && lines[i].indent === indent && !isItem(lines[i].text)) {
    const m = PAIR.exec(lines[i].text);
    if (!m) { i += 1; continue; }
    const [, key, rest] = m;
    i += 1;
    if (rest !== undefined && rest.trim() !== '') {
      out[key] = parseScalar(rest);
      continue;
    }
    const next = lines[i];
    if (next && next.indent > indent) {
      [out[key], i] = parseBlock(lines, i, next.indent);
    } else if (next && next.indent === indent && isItem(next.text)) {
      [out[key], i] = parseList(lines, i, indent);
    } else {
      out[key] = [];
    }
  }
  return [out, i];
}

function parseList(lines, start, indent) {
  const out = [];
  let i = start;
  while (i < lines.length && lines[i].indent === indent && isItem(lines[i].text)) {
    const body = lines[i].text === '-' ? '' : lines[i].text.slice(2).trim();
    if (!body) {
      i += 1;
      const next = lines[i];
      if (next && next.indent > indent) {
        let value;
        [value, i] = parseBlock(lines, i, next.indent);
        out.push(value);
      } else {
        out.push(null);
      }
      continue;
    }
    if (PAIR.test(body) && !/^['"]/.test(body)) {
      // A map item: re-read this line as the first key of a map indented past the dash.
      const childIndent = indent + (lines[i].text.length - body.length);
      const synthetic = [...lines.slice(0, i), { indent: childIndent, text: body }, ...lines.slice(i + 1)];
      let value;
      [value, i] = parseMap(synthetic, i, childIndent);
      out.push(value);
      continue;
    }
    out.push(parseScalar(body));
    i += 1;
  }
  return [out, i];
}

/** Reads a pack's front matter. `key: value`, `key:` + `- item` lists, and nested maps/lists. */
function parseFrontMatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  if (!m) return {};
  const lines = tokenise(m[1]);
  if (!lines.length) return {};
  const [data] = parseMap(lines, 0, lines[0].indent);
  return data || {};
}

// ---------------------------------------------------------------------------
// Normalisation
// ---------------------------------------------------------------------------

const asList = (value) => {
  if (value === undefined || value === null || value === '') return [];
  return Array.isArray(value) ? value.filter((v) => v !== null && v !== '') : [value];
};

const FLOATING_VERSION = /^(RELEASE|LATEST|latest|release)$|[+*[\](),]|SNAPSHOT$/;

/** One deterministic transformation a pack declares. Only shape is normalised here; policy is not. */
function normaliseTransformation(t, index) {
  const x = t && typeof t === 'object' ? t : {};
  return {
    id: x.id || `transformation-${index + 1}`,
    provider: x.provider || 'openrewrite',
    title: x.title || null,
    policy: (x.policy || 'optional').toLowerCase(),
    phase: x.phase || 'after-baseline',
    build_tools: asList(x.build_tools).map(String),
    recipes: asList(x.recipes || x.recipe).map(String),
    artifacts: asList(x.artifacts || x.artifact).map(String),
    plugin_version: x.plugin_version || null,
    gradle_plugin_version: x.gradle_plugin_version || null,
    config: x.config || null,
    license: x.license || null,
    license_note: x.license_note || null,
    source_repository: x.source_repository || null,
    recipe_target: x.recipe_target || null,
    covers: asList(x.covers).map(String),
    excludes: asList(x.excludes).map(String),
    notes: x.notes || null,
  };
}

/**
 * Problems that make a transformation unsafe to *execute* (as opposed to merely describe). A
 * floating version — RELEASE, LATEST, a range, a SNAPSHOT — is refused because the same run could
 * not be reproduced; a pack may name one for discovery, but never for execution.
 */
function transformationProblems(t) {
  const problems = [];
  if (t.provider !== 'openrewrite') problems.push(`provider "${t.provider}" is not supported (only openrewrite)`);
  if (!t.recipes.length) problems.push('no recipe named');
  if (!['optional', 'required'].includes(t.policy)) problems.push(`policy "${t.policy}" must be optional or required`);
  for (const coord of t.artifacts) {
    const parts = coord.split(':');
    if (parts.length < 3) problems.push(`artifact "${coord}" is not groupId:artifactId:version`);
    else if (FLOATING_VERSION.test(parts[parts.length - 1])) problems.push(`artifact "${coord}" uses a floating version — pin it`);
  }
  for (const [label, v] of [['plugin_version', t.plugin_version], ['gradle_plugin_version', t.gradle_plugin_version]]) {
    if (v && FLOATING_VERSION.test(v)) problems.push(`${label} "${v}" is floating — pin it`);
  }
  for (const value of [...t.recipes, ...t.artifacts, t.plugin_version, t.gradle_plugin_version].filter(Boolean)) {
    if (!/^[\w.:\-]+$/.test(value)) problems.push(`"${value}" contains characters not allowed in a recipe or coordinate`);
  }
  if (t.config && (path.isAbsolute(t.config) || t.config.split(/[\\/]/).includes('..'))) {
    problems.push(`config "${t.config}" must be a path inside references/`);
  }
  return problems;
}

function normaliseSurface(s, index) {
  const x = s && typeof s === 'object' ? s : {};
  return {
    id: x.id || `surface-${index + 1}`,
    label: x.label || x.id || `surface ${index + 1}`,
    rule: x.rule || null,
    scope: x.scope || 'all',
    patterns: asList(x.patterns || x.pattern).map(String),
  };
}

function packFromFile(file) {
  const full = path.join(REFERENCES_DIR, file);
  return normalisePack(parseFrontMatter(fs.readFileSync(full, 'utf8')), file, full);
}

/** Front matter → pack object. The v1 fields keep their v1 names and defaults exactly. */
function normalisePack(meta, file, full = null) {
  return {
    file,
    path: full,
    id: meta.id || file.replace(/\.md$/, ''),
    title: meta.title || file,
    stack: meta.stack || 'unknown',
    from: meta.from || null,
    to: meta.to || null,
    language_from: meta.language_from || null,
    language_to: meta.language_to || null,
    detect: Array.isArray(meta.detect) ? meta.detect : [],
    // Optional v2 metadata — all absent in a pack written before it existed.
    provenance: meta.provenance && typeof meta.provenance === 'object' ? meta.provenance : null,
    target_constraints: meta.target_constraints && typeof meta.target_constraints === 'object' ? meta.target_constraints : null,
    migration_path: meta.path && typeof meta.path === 'object' ? meta.path : null,
    surfaces: asList(meta.surfaces).map(normaliseSurface),
    ecosystem_boms: asList(meta.ecosystem_boms).map((b) => (typeof b === 'object' ? b : { coordinate: String(b) })),
    transformations: asList(meta.transformations).map(normaliseTransformation),
    verification: asList(meta.verification),
  };
}

function listReferencePacks() {
  if (!fs.existsSync(REFERENCES_DIR)) return [];
  return fs
    .readdirSync(REFERENCES_DIR)
    .filter((f) => f.endsWith('.md') && f.toLowerCase() !== 'readme.md')
    .map(packFromFile)
    .sort((a, b) => a.id.localeCompare(b.id));
}

/**
 * A detect entry is `groupId:artifactId` or `groupId:artifactId:versionPrefix`.
 * A pack matches when any entry matches the project's parent, a dependency or a plugin.
 */
function matchesCoordinate(entry, inventory) {
  const [groupId, artifactId, versionPrefix] = entry.split(':');
  const candidates = [
    ...(inventory.parent ? [inventory.parent] : []),
    ...(inventory.dependencies || []),
    ...(inventory.plugins || []),
  ];
  return candidates.some((c) => {
    if (groupId && c.groupId && c.groupId !== groupId) return false;
    if (groupId && !c.groupId) return false;
    if (artifactId && c.artifactId !== artifactId) return false;
    if (!versionPrefix) return true;
    return typeof c.version === 'string' && c.version.startsWith(versionPrefix);
  });
}

function matchReferencePacks(inventory) {
  return listReferencePacks()
    .map((pack) => ({
      ...pack,
      matched: pack.detect.filter((entry) => matchesCoordinate(entry, inventory)),
    }))
    .filter((pack) => pack.matched.length > 0);
}

function resolveReferencePack(idOrFile) {
  const packs = listReferencePacks();
  const wanted = String(idOrFile || '').replace(/^references\//, '');
  return packs.find((p) => p.id === wanted || p.file === wanted || p.file === `${wanted}.md`) || null;
}

module.exports = {
  parseFrontMatter, listReferencePacks, matchReferencePacks, resolveReferencePack, normalisePack,
  normaliseTransformation, transformationProblems, matchesCoordinate, FLOATING_VERSION,
};
