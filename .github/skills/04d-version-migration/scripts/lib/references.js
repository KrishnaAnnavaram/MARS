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
    // Optional: the groupId:artifactId coordinates whose declared version *is* the platform version.
    // Absent, the pack's versioned detect entries serve (see packEligibility).
    platform_coordinates: asList(meta.platform_coordinates).map(String),
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

// ---------------------------------------------------------------------------
// Source-version eligibility
//
// A detect entry only *nominates* a pack. Whether the pack may be used is decided by the version
// the project actually declares for its platform: a pack migrates from one generation (`from`), so
// the source must be proven to sit in it. "Proven" means a version-bearing coordinate the pack
// names — `platform_coordinates`, or else its versioned detect entries (a parent, an imported BOM,
// a plugin) — is declared with a concrete version. The presence of an unversioned dependency (a
// starter, say) proves nothing about the generation and never makes a pack eligible on its own.
// ---------------------------------------------------------------------------

function majorOf(version) {
  const m = /^\s*v?(\d+)/.exec(String(version === null || version === undefined ? '' : version));
  return m ? Number(m[1]) : null;
}

function platformCoordinatesOf(pack) {
  const explicit = asList(pack.platform_coordinates).map(String);
  const fromDetect = pack.detect
    .filter((entry) => String(entry).split(':').length >= 3)
    .map((entry) => String(entry).split(':').slice(0, 2).join(':'));
  return [...new Set(explicit.length ? explicit : fromDetect)];
}

/** The concrete platform version the project declares on one of the pack's platform coordinates. */
function sourcePlatformVersion(pack, inventory) {
  const candidates = [
    ...(inventory.parent ? [{ ...inventory.parent, where: 'parent' }] : []),
    ...(inventory.dependencies || []).map((d) => ({ ...d, where: d.scope === 'import' ? 'bom-import' : 'dependency' })),
    ...(inventory.plugins || []).map((p) => ({ ...p, where: 'plugin' })),
  ];
  for (const coordinate of platformCoordinatesOf(pack)) {
    const [groupId, artifactId] = coordinate.split(':');
    const hit = candidates.find((c) => c.groupId === groupId && c.artifactId === artifactId
      && typeof c.version === 'string' && /^\d/.test(c.version));
    if (hit) return { coordinate, version: hit.version, where: hit.where, descriptor: inventory.descriptor || null };
  }
  return null;
}

function packEligibility(pack, inventory) {
  const matched = pack.detect.filter((entry) => matchesCoordinate(entry, inventory));
  const platform = sourcePlatformVersion(pack, inventory);
  const base = {
    pack: pack.id, stack: pack.stack, pack_from: pack.from, pack_to: pack.to,
    matched_on: matched, platform_coordinates: platformCoordinatesOf(pack), source_platform: platform,
  };
  if (!matched.length) return { ...base, nominated: false, eligible: false, relation: 'no-match', reason: 'no detect entry matches the project' };
  if (!platform) {
    return {
      ...base, nominated: true, eligible: false, relation: 'unproven',
      reason: `source platform version not proven: none of ${base.platform_coordinates.join(', ') || '(no platform coordinates)'} is declared with a concrete version — a matching unversioned dependency (${matched.join(', ')}) says nothing about the generation`,
    };
  }
  const fromMajor = majorOf(pack.from);
  if (fromMajor === null) {
    return { ...base, nominated: true, eligible: true, relation: 'unchecked', reason: 'the pack declares no `from` generation; eligibility rests on its detect entries' };
  }
  const sourceMajor = majorOf(platform.version);
  if (sourceMajor === fromMajor) {
    return { ...base, nominated: true, eligible: true, relation: 'in-generation', reason: `source ${platform.coordinate} ${platform.version} (${platform.where}) is in the pack's ${pack.from}.x generation` };
  }
  return {
    ...base, nominated: true, eligible: false, relation: sourceMajor < fromMajor ? 'behind' : 'ahead',
    reason: `source ${platform.coordinate} ${platform.version} (${platform.where}) is generation ${sourceMajor}, but the pack migrates from ${pack.from}.x`,
  };
}

/** Every pack, split into the eligible ones and the ones a detect entry nominated but the source version rules out. */
function assessReferencePacks(inventory) {
  const assessed = listReferencePacks().map((pack) => ({ pack, eligibility: packEligibility(pack, inventory) }));
  return {
    eligible: assessed
      .filter((a) => a.eligibility.eligible)
      .map((a) => ({ ...a.pack, matched: a.eligibility.matched_on, eligibility: a.eligibility })),
    ineligible: assessed.filter((a) => a.eligibility.nominated && !a.eligibility.eligible).map((a) => a.eligibility),
  };
}

function matchReferencePacks(inventory) {
  return assessReferencePacks(inventory).eligible;
}

/** `spring-boot-3-to-4` → `spring-boot`: the capability-name prefix packs of one stack share. */
function capabilityPrefix(packId) {
  return String(packId || '').replace(/-\d+(?:\.\d+)*-to-\d+(?:\.\d+)*$/, '') || 'migration';
}

/**
 * The chain of single-generation steps from the source version to the requested target, and the
 * pack that covers each step. A pack covers exactly one `from` → `to` generation of one stack; a
 * step with no pack is a missing capability, and is never silently jumped over.
 */
function requiredMigrationPath(stack, sourceVersion, targetVersion, packIdHint = null) {
  const from = majorOf(sourceVersion);
  const to = majorOf(targetVersion);
  if (from === null || to === null) return null;
  const packs = listReferencePacks().filter((p) => p.stack === stack);
  const prefix = capabilityPrefix(packIdHint || (packs[0] && packs[0].id));
  const steps = [];
  for (let m = from; m < to; m += 1) {
    const pack = packs.find((p) => majorOf(p.from) === m && majorOf(p.to) === m + 1);
    steps.push({
      from: m === from ? String(sourceVersion) : `${m}.x`,
      to: m + 1 === to ? String(targetVersion) : `${m + 1}.x`,
      capability: pack ? pack.id : `${prefix}-${m}-to-${m + 1}`,
      available: Boolean(pack),
    });
  }
  return {
    stack,
    source: String(sourceVersion),
    target: String(targetVersion),
    steps,
    missing_capability: steps.filter((s) => !s.available).map((s) => s.capability),
  };
}

// ---------------------------------------------------------------------------
// The migration ladder — any published line to any later one, OpenRewrite at the centre
//
// references/openrewrite/<stack>-ladder.json lists every line of a platform as one rung, the
// OpenRewrite upgrade recipe for that rung, and which recipe *stack* provides it (with its licence,
// read from the artifacts' own POMs). planLadderPath turns a source and an exact target into a chain
// of edges — PATCH, MINOR, MAJOR_BOUNDARY — that never skips a major (every major crossed gets its
// own boundary edge into that major's first line, and the last line of the major before it is
// reached first), never runs a recipe the licence policy forbids, and says exactly which rung is
// missing when one is. The plan is data; nothing here runs a build.
// ---------------------------------------------------------------------------

const LADDER_DIR = path.join(REFERENCES_DIR, 'openrewrite');
const LICENSE_POLICIES = ['open-source-only', 'source-available'];
const OSI_LICENSE = /apache|\bmit\b|\bbsd\b|eclipse public|\bepl\b|mozilla public|\bmpl\b|\blgpl\b|\bgpl\b/i;
const NOT_OPEN = /source[- ]available|proprietary|commercial/i;

function loadLadder(id = 'spring-boot') {
  const file = path.join(LADDER_DIR, `${id}-ladder.json`);
  if (!fs.existsSync(file)) return null;
  const ladder = JSON.parse(fs.readFileSync(file, 'utf8'));
  ladder.file = `references/openrewrite/${id}-ladder.json`;
  return ladder;
}

/** True only for a licence string that names an OSI open-source licence and nothing source-available. */
function isOpenSourceLicense(license) {
  return Boolean(license) && OSI_LICENSE.test(license) && !NOT_OPEN.test(license);
}

/** `2.3.12.RELEASE`, `3.5.0`, `3.5` → comparable parts. */
function parseVersion(text) {
  const m = /^(\d+)\.(\d+)(?:\.(\d+))?/.exec(String(text || '').trim());
  if (!m) return null;
  return {
    raw: String(text).trim(), major: Number(m[1]), minor: Number(m[2]),
    patch: m[3] === undefined ? null : Number(m[3]), line: `${m[1]}.${m[2]}`,
  };
}

function compareVersions(a, b) {
  const x = typeof a === 'string' ? parseVersion(a) : a;
  const y = typeof b === 'string' ? parseVersion(b) : b;
  return (x.major - y.major) || (x.minor - y.minor) || ((x.patch || 0) - (y.patch || 0));
}

/** The stacks a policy admits, in preference order: the newest allowed recipes first. */
function allowedStacks(ladder, policy) {
  const stacks = ladder.recipe_stacks || {};
  return Object.keys(stacks)
    .filter((name) => policy === 'source-available' || stacks[name].open_source === true)
    .sort((a, b) => Number(stacks[a].open_source) - Number(stacks[b].open_source));
}

function rungFor(ladder, line) {
  return (ladder.lines || []).find((l) => l.line === line) || null;
}

/** Latest GA patch of a line: Maven metadata when it was read, the ladder's fallback otherwise. */
function latestPatch(ladder, line, published = {}) {
  if (published[line]) return { version: published[line], source: 'maven-metadata' };
  const rung = rungFor(ladder, line);
  return rung && rung.latest_known ? { version: rung.latest_known, source: 'ladder-fallback' } : null;
}

/**
 * The recipe one edge runs. Upstream when the rung's recipe is in an allowed stack; otherwise, under
 * open-source-only, 04D's open-source composite for a major boundary, or a pin-only edge inside a
 * major (the edge recipe still pins parent, Java and the Cloud train; compiler-driven repair does
 * the rest). A boundary with neither is a missing capability.
 */
function edgeRecipe(ladder, edgeClass, toLine, toMajor, policy) {
  const stacks = ladder.recipe_stacks || {};
  const rung = rungFor(ladder, toLine);
  const allowed = allowedStacks(ladder, policy);
  if (rung && rung.recipe && edgeClass !== 'PATCH') {
    const stack = allowed.find((s) => (rung.recipe_stacks || []).includes(s));
    if (stack) return { source: 'upstream', recipe: rung.recipe, stack, ...stacks[stack] };
  }
  const core = Object.keys(stacks).find((s) => stacks[s].open_source && !(stacks[s].artifacts || []).length);
  const composites = ladder.oss_composites || {};
  if (edgeClass === 'MAJOR_BOUNDARY') {
    if (composites[String(toMajor)] && core) {
      return { source: 'oss-composite', recipe: null, composite: composites[String(toMajor)], stack: core, ...stacks[core] };
    }
    return null;
  }
  return core ? { source: 'pin-only', recipe: null, stack: core, ...stacks[core] } : null;
}

/**
 * Plans the migration from `source` to `target` (both exact versions). Options:
 *   policy       open-source-only (default) | source-available
 *   granularity  boundary (default: patch/last-line of the source major, then per major its first
 *                line and its last line, then the target) | minor (one edge per line)
 *   published    { line: latestGaPatch } from Maven metadata
 *   javaFrom / javaTarget   the project's Java level today and the requested one
 *   cloud        { used, published: { train: latestGaPatch } } when the project imports Spring Cloud
 *   packs        reference packs, to attach rules to each major boundary
 */
function planLadderPath(ladder, { source, target, policy = 'open-source-only', granularity = 'boundary', published = {}, javaFrom = null, javaTarget = null, cloud = null, packs = [] }) {
  const S = parseVersion(source);
  const T = parseVersion(target);
  const result = {
    ladder: ladder.file, stack: ladder.stack, policy, granularity, source, target,
    status: 'SUPPORTED', reason: null, edges: [], missing_capability: [], blocked_ecosystem: [],
  };
  const fail = (status, reason, missing = []) => Object.assign(result, { status, reason, missing_capability: missing });
  if (!LICENSE_POLICIES.includes(policy)) return fail('INVALID_REQUEST', `unknown licence policy "${policy}"`);
  if (!S || !T) return fail('INVALID_REQUEST', `cannot read the versions (source ${source}, target ${target})`);
  if (compareVersions(T, S) <= 0) return fail('NO_UPGRADE', `target ${target} is not later than the source ${source}`);
  const lines = (ladder.lines || []).map((l) => ({ ...l, v: parseVersion(l.line) })).sort((a, b) => compareVersions(a.v, b.v));
  for (const needed of [S.line, T.line]) {
    if (!rungFor(ladder, needed)) return fail('UNSUPPORTED_MIGRATION_PATH', `the ladder has no rung for Boot ${needed}`, [`ladder-rung:${ladder.id}-${needed}`]);
  }
  const linesOf = (major) => lines.filter((l) => l.v.major === major);
  const latest = (line) => (latestPatch(ladder, line, published) || {}).version || null;

  // Waypoints: every version the project will stand on, in order, after the source.
  const waypoints = [];
  const push = (version, role) => {
    const v = parseVersion(version);
    if (!v || compareVersions(v, T) > 0) return;
    const prev = waypoints.length ? waypoints[waypoints.length - 1].version : source;
    if (compareVersions(v, parseVersion(prev)) > 0) waypoints.push({ version, role });
  };
  const sameMajorSegment = (fromLine, toVersion, role) => {
    const to = parseVersion(toVersion);
    const inner = lines.filter((l) => l.v.major === to.major
      && compareVersions(l.v, parseVersion(fromLine)) > 0 && compareVersions(l.v, parseVersion(to.line)) < 0);
    if (granularity === 'minor') {
      inner.forEach((l) => push(latest(l.line), 'transit'));
    } else {
      // Licence frontier: if the destination rung's recipe is not allowed, stop first on the highest
      // line inside the segment whose recipe is, so the allowed upstream recipes still run.
      const destination = edgeRecipe(ladder, 'MINOR', to.line, to.major, policy);
      if (destination && destination.source !== 'upstream') {
        const frontier = [...inner].reverse().find((l) => {
          const r = edgeRecipe(ladder, 'MINOR', l.line, l.v.major, policy);
          return r && r.source === 'upstream';
        });
        if (frontier) push(latest(frontier.line), 'transit');
      }
    }
    push(toVersion, role);
  };

  if (S.major === T.major) {
    sameMajorSegment(S.line, target, 'landing');
  } else {
    const lastOfSource = linesOf(S.major).slice(-1)[0];
    if (lastOfSource) sameMajorSegment(S.line, latest(lastOfSource.line), 'transit');
    for (let m = S.major + 1; m <= T.major; m += 1) {
      const majorLines = linesOf(m);
      if (!majorLines.length) return fail('UNSUPPORTED_MIGRATION_PATH', `the ladder has no rung in major ${m}`, [`ladder-rung:${ladder.id}-${m}.x`]);
      const first = majorLines[0];
      if (m === T.major) {
        if (first.line === T.line) push(target, 'landing');
        else { push(latest(first.line), 'transit'); sameMajorSegment(first.line, target, 'landing'); }
      } else {
        push(latest(first.line), 'transit');
        const last = majorLines.slice(-1)[0];
        if (last.line !== first.line) sameMajorSegment(first.line, latest(last.line), 'transit');
      }
    }
  }
  if (!waypoints.length || waypoints[waypoints.length - 1].version !== target) push(target, 'landing');
  waypoints[waypoints.length - 1].role = 'landing';

  // Edges: one per waypoint, with its recipe, Java level, Cloud train and rules pack.
  let fromVersion = source;
  let java = javaFrom ? String(javaFrom) : null;
  waypoints.forEach((w, i) => {
    const from = parseVersion(fromVersion);
    const to = parseVersion(w.version);
    const edgeClass = to.major !== from.major ? 'MAJOR_BOUNDARY' : (to.line !== from.line ? 'MINOR' : 'PATCH');
    const rung = rungFor(ladder, to.line);
    const javaMin = rung && rung.java_min ? String(rung.java_min) : null;
    let edgeJava = java;
    if (javaMin && (!edgeJava || Number(edgeJava) < Number(javaMin))) edgeJava = javaMin;
    if (w.role === 'landing' && javaTarget && (!edgeJava || Number(javaTarget) >= Number(edgeJava))) edgeJava = String(javaTarget);
    const recipe = edgeRecipe(ladder, edgeClass, to.line, to.major, policy);
    const edge = {
      id: `E${i + 1}`, seq: i + 1, class: edgeClass, role: w.role,
      from: fromVersion, to: w.version, from_line: from.line, to_line: to.line,
      java_from: java, java: edgeJava,
      recipe_source: recipe ? recipe.source : null,
      recipe: recipe ? recipe.recipe : null,
      composite: recipe ? recipe.composite || null : null,
      stack: recipe ? recipe.stack : null,
      license: recipe ? recipe.license : null,
      open_source: recipe ? Boolean(recipe.open_source) : null,
      plugin_version: recipe ? recipe.plugin_version : null,
      gradle_plugin_version: recipe ? recipe.gradle_plugin_version : null,
      artifacts: recipe ? recipe.artifacts || [] : [],
      cloud_train: null,
      pack: null,
      notes: [],
    };
    if (!recipe) {
      const need = `openrewrite-recipe:${ladder.id}-${to.line} (${policy})`;
      result.missing_capability.push(need);
      edge.notes.push(`no ${policy} recipe or open-source composite for the ${edgeClass} into ${to.line}`);
    } else if (recipe.source === 'pin-only' && edgeClass === 'PATCH') {
      edge.notes.push(`patch edge within ${to.line}: pins the platform${cloud && cloud.used ? ' and Spring Cloud train' : ''} only — a patch release carries no framework migration`);
    } else if (recipe.source === 'pin-only') {
      edge.notes.push(`no ${policy} upstream recipe for ${to.line}: the edge pins the platform${cloud && cloud.used ? ', Spring Cloud train' : ''} and Java level; compiler-driven repair handles the rest`);
    }
    if (edgeClass === 'MAJOR_BOUNDARY') {
      const pack = packs.find((p) => majorOf(p.from) === from.major && majorOf(p.to) === to.major);
      edge.pack = pack ? pack.id : null;
      if (!pack) edge.notes.push(`no reference pack for ${from.major}.x → ${to.major}.x: residual repair has the recipe and the compiler, but no pack rules`);
    }
    if (cloud && cloud.used) {
      const series = ((ladder.cloud || {}).trains || {})[to.line] || null;
      const ga = series ? ((cloud.published || {})[series] || ((ladder.cloud || {}).latest_known || {})[series] || (/\.SR\d+$/.test(series) ? series : null)) : null;
      if (!ga) {
        edge.notes.push(`no GA Spring Cloud release train for Boot ${to.line}${series ? ` (${series} is not published as GA)` : ''}`);
        result.blocked_ecosystem.push({ edge: edge.id, line: to.line, train: series });
      } else {
        edge.cloud_train = ga;
      }
    }
    result.edges.push(edge);
    fromVersion = w.version;
    java = edgeJava;
  });

  if (result.missing_capability.length) {
    fail('UNSUPPORTED_MIGRATION_PATH', `${result.missing_capability.length} rung(s) have no recipe the ${policy} policy allows: ${result.missing_capability.join(', ')}`, result.missing_capability);
  } else if (result.blocked_ecosystem.length) {
    const ok = [...lines].reverse().find((l) => compareVersions(l.v, T) <= 0 && ((ladder.cloud || {}).trains || {})[l.line]
      && ((cloud.published || {})[ladder.cloud.trains[l.line]] || (ladder.cloud.latest_known || {})[ladder.cloud.trains[l.line]]));
    result.status = 'BLOCKED_ECOSYSTEM';
    result.reason = `the application uses Spring Cloud, and no GA release train targets Boot ${result.blocked_ecosystem.map((b) => b.line).join(', ')}`
      + (ok ? `; the highest reachable line with a GA train is ${ok.line} (${latest(ok.line)})` : '');
    result.closest_supported_target = ok ? latest(ok.line) : null;
  } else {
    const kinds = result.edges.map((e) => `${e.id} ${e.from}→${e.to} ${e.class} (${e.recipe_source}${e.recipe ? `: ${e.recipe.split('.').pop()}` : ''}, ${e.license})`);
    result.reason = `${result.edges.length} edge(s): ${kinds.join('; ')}`;
  }
  return result;
}

/**
 * The transformation one edge runs, in the same normalised shape as a pack transformation — so
 * selection, preview-before-apply, scope checks, provenance and the licence gate all treat it the
 * same way. Its recipe is the declarative `mars.migration.edge.<id>` that lib/openrewrite.js renders.
 */
function edgeTransformation(edge) {
  return normaliseTransformation({
    id: `edge-${edge.id}`,
    title: `Edge ${edge.id}: ${edge.from} → ${edge.to} (${edge.class})`,
    provider: 'openrewrite',
    policy: 'optional',
    phase: 'edge',
    build_tools: ['maven', 'gradle'],
    recipes: [`mars.migration.edge.${edge.id}`],
    artifacts: edge.artifacts || [],
    plugin_version: edge.plugin_version,
    gradle_plugin_version: edge.gradle_plugin_version,
    license: edge.license,
    license_note: edge.open_source ? null : 'Not OSI open source; allowed only because the session was started with --license-policy source-available.',
    recipe_target: edge.to,
    covers: [edge.class.toLowerCase()],
    notes: edge.notes.join(' '),
  }, 0);
}

function resolveReferencePack(idOrFile) {
  const packs = listReferencePacks();
  const wanted = String(idOrFile || '').replace(/^references\//, '');
  return packs.find((p) => p.id === wanted || p.file === wanted || p.file === `${wanted}.md`) || null;
}

module.exports = {
  parseFrontMatter, listReferencePacks, matchReferencePacks, resolveReferencePack, normalisePack,
  normaliseTransformation, transformationProblems, matchesCoordinate, FLOATING_VERSION,
  majorOf, platformCoordinatesOf, sourcePlatformVersion, packEligibility, assessReferencePacks,
  capabilityPrefix, requiredMigrationPath,
  LICENSE_POLICIES, loadLadder, isOpenSourceLicense, parseVersion, compareVersions, allowedStacks,
  latestPatch, edgeRecipe, planLadderPath, edgeTransformation,
};
