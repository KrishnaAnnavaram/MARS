#!/usr/bin/env node
/**
 * Version Migration — Final step: the migration report.
 *
 * Merges the facts collected by the other scripts (baseline.json, rounds/round-NN.json,
 * runtime/baseline.json, runtime/final.json) with the agent's judgement (migration.json,
 * validated against templates/migration.schema.json) into one report a non-engineer can read:
 *
 *   docs/agent_output/04-remediation/migration_<slug>.md    — the report
 *   docs/agent_output/04-remediation/migration_<slug>.diff  — the cumulative patch, git apply-able
 *   docs/agent_output/04-remediation/README.md              — its migration block, regenerated
 *
 * The output folder is shared with 04a-fix-strategist and 04b-fixer. Migration reports use the
 * migration_ prefix, which their index scan ignores, and the README's migration block sits above
 * their marker so neither rewrite can clobber the other.
 *
 * Outcomes are rendered exactly as recorded. A migration that ended on a red round renders as
 * red — this script never upgrades a failed round into a success.
 *
 * Usage:
 *   node scripts/render-migration-report.js --slug <slug>
 *   node scripts/render-migration-report.js --all
 */
const fs = require('fs');
const path = require('path');
const { finalizeRun, buildSummary, renderMarkdown } = require('./lib/summary');
const {
  OUT_DIR, OUT_README, sessionPaths, listSessions, listRounds, readJson, rel, run,
  categoryMeta, classifyMessage, summariseErrors, isLogNoise,
  compareProbeRecords, listTransformations, inferState, validateTemplate, groupErrors, recordState, isSandboxRepo,
} = require('./lib/migration');

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--slug' || a === '-s') args.slug = argv[++i];
    else if (a === '--all' || a === '-a') args.all = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Version Migration — Report renderer

  node scripts/render-migration-report.js --slug <slug>
  node scripts/render-migration-report.js --all

Options:
  --slug, -s   Render one session
  --all, -a    Render every session that has a migration.json
  --help, -h   Show this message`);
}

// ---------------------------------------------------------------------------
// Validation of the agent's judgement file
// ---------------------------------------------------------------------------

const REQUIRED = [
  ['slug', (m) => typeof m.slug === 'string' && m.slug.trim()],
  ['title', (m) => typeof m.title === 'string' && m.title.trim()],
  ['summary', (m) => typeof m.summary === 'string' && m.summary.trim().length > 40],
  ['migration.reference_pack', (m) => m.migration && typeof m.migration.reference_pack === 'string'],
  ['migration.language.from/to', (m) => m.migration && m.migration.language && m.migration.language.from && m.migration.language.to],
  ['migration.platform.name/from/to', (m) => m.migration && m.migration.platform && m.migration.platform.name && m.migration.platform.from && m.migration.platform.to],
  ['round_notes (one per recorded round)', (m) => Array.isArray(m.round_notes) && m.round_notes.length > 0],
];

function validate(migration, rounds, plan = null) {
  const missing = REQUIRED.filter(([, check]) => !check(migration)).map(([key]) => key);
  if (missing.length) {
    throw new Error(`migration.json is missing required field(s):\n  - ${missing.join('\n  - ')}\nSee templates/migration.schema.json for the expected shape.`);
  }
  const schemaErrors = validateTemplate(migration, 'migration.schema.json');
  if (schemaErrors.length) {
    throw new Error(`migration.json does not match templates/migration.schema.json:\n  - ${schemaErrors.slice(0, 20).join('\n  - ')}`);
  }
  const noted = new Set(migration.round_notes.map((n) => n.round));
  const unnoted = rounds.map((r) => r.round).filter((r) => !noted.has(r));
  if (unnoted.length) {
    throw new Error(`migration.json has no round_notes entry for recorded round(s): ${unnoted.join(', ')}.\nEvery round that ran must be explained, including the ones that failed.`);
  }
  if (plan) {
    const planErrors = validateTemplate(plan, 'migration-plan.schema.json');
    if (planErrors.length) {
      throw new Error(`migration-plan.json does not match templates/migration-plan.schema.json:\n  - ${planErrors.slice(0, 20).join('\n  - ')}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Presentation helpers
// ---------------------------------------------------------------------------

const OUTCOME = {
  passed: { emoji: '🟢', label: 'Passed', colour: '3DA35B', className: 'ok' },
  'compile-failed': { emoji: '🔴', label: 'Compile failed', colour: 'C92A2A', className: 'broken' },
  'dependency-failed': { emoji: '🔴', label: 'Dependencies unresolved', colour: 'C92A2A', className: 'broken' },
  'tests-failed': { emoji: '🟠', label: 'Tests failed', colour: 'E8590C', className: 'degraded' },
  'build-failed': { emoji: '🟠', label: 'Compiled; a build plugin failed', colour: 'E8590C', className: 'degraded' },
  'timed-out': { emoji: '🟠', label: 'Timed out', colour: 'E8590C', className: 'degraded' },
};

const outcomeOf = (id) => OUTCOME[id] || { emoji: '⚪', label: id || 'unknown', colour: '868E96', className: 'neutral' };

const CLASS_DEFS = [
  'classDef broken fill:#ffe3e3,stroke:#c92a2a,stroke-width:2px,color:#1a1a1a',
  'classDef degraded fill:#fff3bf,stroke:#e8590c,stroke-width:2px,color:#1a1a1a',
  'classDef ok fill:#e6fcf5,stroke:#2f9e44,stroke-width:2px,color:#1a1a1a',
  'classDef before fill:#e7f5ff,stroke:#1c7ed6,stroke-width:2px,color:#1a1a1a',
  'classDef after fill:#f3f0ff,stroke:#7048e8,stroke-width:2px,color:#1a1a1a',
  'classDef neutral fill:#f1f3f5,stroke:#868e96,stroke-width:1px,color:#1a1a1a',
];

function badgeText(text) {
  return String(text).replace(/-/g, '--').replace(/_/g, '__').replace(/ /g, '_');
}

function badge(label, message, colour) {
  return `![${label}](https://img.shields.io/badge/${badgeText(label)}-${badgeText(message)}-${colour}?style=for-the-badge)`;
}

const esc = (text) => String(text === null || text === undefined ? '' : text)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/\|/g, '\\|')
  .replace(/\r?\n/g, ' ');
const code = (text) => (text ? `\`${String(text).replace(/`/g, '')}\`` : '—');
// Mermaid renders label text as HTML and treats several characters as syntax, and these labels
// carry real build output — `expected:<200>`, `3.5.0 -> 4.1.1`, `Health(Builder)`. Everything
// risky is folded to a lookalike so the diagram never breaks on its own content; <br/> is added
// by the caller, after this.
const mermaidLabel = (text) => String(text === null || text === undefined ? '' : text)
  .replace(/\r?\n/g, ' ')
  .replace(/-{1,2}>/g, '→')
  .replace(/</g, '‹')
  .replace(/>/g, '›')
  .replace(/"/g, "'")
  .replace(/[[\]{}()]/g, '')
  .replace(/[;|`]/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();

const mermaidText = (...parts) => parts
  .filter((p) => p !== null && p !== undefined && p !== '')
  .map(mermaidLabel)
  .join('<br/>');

function bullets(items, empty = '_none recorded_') {
  if (!items || !items.length) return [empty];
  return items.map((i) => `- ${esc(i)}`);
}

function durationText(ms) {
  if (!ms && ms !== 0) return '—';
  return ms >= 60000 ? `${(ms / 60000).toFixed(1)} min` : `${(ms / 1000).toFixed(1)}s`;
}

// ---------------------------------------------------------------------------
// What actually failed — derived from the round records, never from prose
// ---------------------------------------------------------------------------

/** What each build goal actually proves, in words a non-engineer can read. */
const INTENT_GOAL = {
  compile: 'main sources compile',
  'test-compile': 'main + test sources compile',
  test: 'the test suite runs',
  package: 'the test suite runs and the jar builds',
  'package-skip-tests': 'the jar builds, tests skipped',
  verify: 'the full verify lifecycle passes',
  install: 'the artifact builds and installs locally',
};
const intentGoal = (intent) => INTENT_GOAL[intent] || `${intent} completes`;

const baseName = (file) => String(file || '').split('/').pop();

const plural = (n, one, many) => `${n} ${n === 1 ? one : (many || `${one}s`)}`;

/** "3, 4, 5" for a handful of lines, "3-142 (13 lines)" once a list stops being readable. */
function lineRange(lines) {
  if (!lines || !lines.length) return '—';
  if (lines.length <= 6) return lines.join(', ');
  return `${lines[0]}–${lines[lines.length - 1]} (${lines.length} lines)`;
}

function firstSentences(text, count = 2) {
  const sentences = String(text || '').split(/(?<=\.)\s+/).filter(Boolean);
  return sentences.slice(0, count).join(' ');
}

function shorten(text, max = 46) {
  const clean = String(text || '').trim();
  if (clean.length <= max) return clean;
  const cut = clean.slice(0, max);
  const space = cut.lastIndexOf(' ');
  return `${(space > max * 0.5 ? cut.slice(0, space) : cut).trim()}…`;
}

/**
 * javac and Maven report the same problem twice — once as `[ERROR] File.java:[22,12] cannot find
 * symbol` and again in javac's enriched form with the symbol name attached. Both are real error
 * lines, and both stay counted as such, but they are one problem; a report that says "27 errors"
 * where 13 things are wrong teaches the reader to distrust its own numbers. Everything that
 * describes *what broke* counts distinct problems, and the raw line count is always printed beside
 * it so the two reconcile. Only errors carrying a file are folded — a build-level message has no
 * coordinates to fold on.
 */
function distinctErrors(round) {
  const buckets = new Map();
  const out = [];
  for (const e of round.errors || []) {
    if (!e.file) { out.push({ ...e }); continue; }
    const key = `${e.file}|${e.line || ''}`;
    const bucket = buckets.get(key) || [];
    const overlap = bucket.find((p) => p.message.startsWith(e.message) || e.message.startsWith(p.message));
    if (overlap) {
      if (e.message.length > overlap.message.length) {
        overlap.message = e.message;
        if ((e.detail || []).length) overlap.detail = e.detail;
        overlap.column = overlap.column || e.column;
      }
      continue;
    }
    const copy = { ...e };
    bucket.push(copy);
    buckets.set(key, bucket);
    out.push(copy);
  }
  return out;
}

/** One entry per file the compiler named: how many problems, which lines, which messages. */
function fileDigest(round) {
  const byFile = new Map();
  for (const e of distinctErrors(round)) {
    if (!e.file) continue;
    let entry = byFile.get(e.file);
    if (!entry) {
      entry = { file: e.file, count: 0, lines: [], problems: new Map(), categories: new Map() };
      byFile.set(e.file, entry);
    }
    entry.count += 1;
    if (e.line) entry.lines.push(Number(e.line));
    const label = shorten(e.message, 120);
    entry.problems.set(label, (entry.problems.get(label) || 0) + 1);
    entry.categories.set(e.category, (entry.categories.get(e.category) || 0) + 1);
  }
  return [...byFile.values()]
    .map((entry) => ({
      ...entry,
      lines: [...new Set(entry.lines)].sort((a, b) => a - b),
      problems: [...entry.problems.entries()].sort((a, b) => b[1] - a[1]),
      topCategory: [...entry.categories.entries()].sort((a, b) => b[1] - a[1])[0][0],
    }))
    .sort((a, b) => b.count - a.count);
}

/** Build-level errors — a resolver or plugin failure has no file to hang off. */
function buildLevelMessages(round) {
  const seen = new Set();
  const out = [];
  for (const e of distinctErrors(round)) {
    if (e.file) continue;
    if (/^Tests run:/i.test(e.message)) continue;
    if (seen.has(e.message)) continue;
    seen.add(e.message);
    out.push(e);
  }
  return out;
}

// Surefire prints each failing test twice: once as it happens, with the fully-qualified name, and
// once in the closing "Results:" block, with the assertion and the line number. The first pass
// supplies the package, the second the reason; the two are joined on the simple class name.
const TEST_TIME_LINE = /^([\w.$]+)\.([\w$]+)\s+--\s+Time elapsed:.*<<<\s*(FAILURE|ERROR)!/i;
const RESULT_LINE = /^([A-Z][\w$]*)(?:\.([\w$]+))?(?::(\d+))?\s*(?:[^\w\s]+\s*)?(.*)$/;

function parseTestFailures(round) {
  const lines = String(round.log_tail || '')
    .split(/\r?\n/)
    .map((l) => l.replace(/^\[(?:ERROR|WARNING|INFO)\]\s?/, '').trim());

  const fqcnBySimple = new Map();
  for (const line of lines) {
    const m = TEST_TIME_LINE.exec(line);
    if (!m) continue;
    const fqcn = /^[A-Z]/.test(m[2]) ? `${m[1]}.${m[2]}` : m[1];
    if (fqcn.includes('.')) fqcnBySimple.set(fqcn.split('.').pop(), fqcn);
  }

  const found = new Map();
  const record = (simple, method, line, reason, kind) => {
    const key = method ? `${simple}.${method}` : simple;
    if (found.has(key) || !reason) return;
    found.set(key, {
      key,
      simple,
      method,
      fqcn: fqcnBySimple.get(simple) || simple,
      line: line ? Number(line) : null,
      reason: shorten(reason, 160),
      kind,
      category: classifyMessage(reason),
    });
  };

  let mode = null;
  for (const line of lines) {
    if (/^Failures:\s*$/i.test(line)) { mode = 'failure'; continue; }
    if (/^Errors:\s*$/i.test(line)) { mode = 'error'; continue; }
    if (!mode) continue;
    if (!line || /^(Tests run:|BUILD|Results|Skipped:|-{3,})/i.test(line)) { mode = null; continue; }
    const m = RESULT_LINE.exec(line);
    if (!m) continue;
    const method = m[2] && !/^[A-Z]/.test(m[2]) ? m[2] : null;
    record(m[1], method, m[3], (m[4] || '').trim(), mode);
  }

  // The results block sits at the very end of the log, so a tail that was cut short can lose it.
  // Fall back to the recorded error lines, which carry the same two shapes.
  if (!found.size) {
    for (const e of round.errors || []) {
      const assertion = /^([A-Z][\w$]*)\.([a-z][\w$]*):(\d+)\s+(.+)$/.exec(e.message);
      if (assertion) { record(assertion[1], assertion[2], assertion[3], assertion[4], 'failure'); continue; }
      const thrown = /^([A-Z][\w$]*)(?:\.([a-z][\w$]*))?\s+[^\w\s]+\s+(.+)$/.exec(e.message);
      if (thrown) record(thrown[1], thrown[2] || null, null, thrown[3], 'error');
    }
  }
  return [...found.values()];
}

const SOURCE_ROOTS = ['src/test/java', 'src/main/java', 'src/test/kotlin', 'src/main/kotlin'];
const SOURCE_INDEX = new WeakMap();

/** Every source file in the sandbox, indexed by its simple type name. Walked once per report. */
function sourceIndex(ctx) {
  if (!ctx) return new Map();
  if (SOURCE_INDEX.has(ctx)) return SOURCE_INDEX.get(ctx);
  const index = new Map();
  const root = ctx.meta && ctx.meta.workspace;
  const walk = (dir, prefix, depth) => {
    if (depth > 12) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (error) { return; }
    for (const entry of entries) {
      const child = `${prefix}/${entry.name}`;
      if (entry.isDirectory()) walk(path.join(dir, entry.name), child, depth + 1);
      else if (/\.(java|kt)$/.test(entry.name)) {
        const simple = entry.name.replace(/\.(java|kt)$/, '');
        if (!index.has(simple)) index.set(simple, child);
      }
    }
  };
  if (root) {
    for (const dir of SOURCE_ROOTS) {
      const abs = path.join(root, ...dir.split('/'));
      if (fs.existsSync(abs)) walk(abs, dir, 0);
    }
  }
  SOURCE_INDEX.set(ctx, index);
  return index;
}

/**
 * The test's own source file, confirmed to exist in the sandbox — never a guessed path. Surefire
 * only prints the package on the line where the test ran, which a truncated log tail can lose, so
 * a class that arrives with no package is resolved by simple name against the indexed sources.
 */
function sourcePathFor(fqcn, ctx) {
  const root = ctx && ctx.meta && ctx.meta.workspace;
  if (!root || !fqcn) return null;
  const parts = fqcn.replace(/\$.*$/, '').split('.');
  const simple = parts[parts.length - 1];
  if (parts.length > 1) {
    for (const dir of SOURCE_ROOTS) {
      const relPath = `${dir}/${parts.join('/')}.java`;
      if (fs.existsSync(path.join(root, ...relPath.split('/')))) return relPath;
    }
  }
  return sourceIndex(ctx).get(simple) || null;
}

/** Every test already failing in the pre-migration round, so later rounds can be read against it. */
function baselineTestFailures(rounds) {
  const baselineRound = rounds.find((r) => r.baseline);
  return new Set(baselineRound ? parseTestFailures(baselineRound).map((t) => t.key) : []);
}

/**
 * The shape of a round's breakage in one line. For a round that ran tests this counts *tests*, not
 * error lines — surefire prints several lines per failing test, and "Test failure ×8" printed beside
 * "2 failed" makes the report argue with itself.
 */
function failureShape(round) {
  const tests = parseTestFailures(round);
  if (tests.length) {
    const asserted = tests.filter((t) => t.kind !== 'error').length;
    const errored = tests.length - asserted;
    const environmental = tests.filter((t) => t.category === 'environment').length;
    const parts = [];
    if (asserted) parts.push(`${asserted} test${asserted === 1 ? '' : 's'} failed an assertion`);
    if (errored) parts.push(`${errored} test${errored === 1 ? '' : 's'} errored before asserting`);
    if (environmental) parts.push(`${environmental} of them environmental, not code`);
    return parts.join(' · ');
  }
  const counts = new Map();
  for (const e of distinctErrors(round)) counts.set(e.category, (counts.get(e.category) || 0) + 1);
  const ranked = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  const meaningful = ranked.filter(([id]) => id !== 'other' && id !== 'plugin-failure');
  return (meaningful.length ? meaningful : ranked)
    .slice(0, 3)
    .map(([id, count]) => `${categoryMeta(id).label} ×${count}`)
    .join(' · ');
}

/** Where a round broke, named: the files if the compiler named files, otherwise the tests. */
function failureLocations(round, limit = 3) {
  const files = fileDigest(round);
  if (files.length) {
    return {
      kind: 'files',
      total: files.length,
      items: files.slice(0, limit).map((f) => `${code(baseName(f.file))} (${f.count} problem${f.count === 1 ? '' : 's'})`),
      short: files.slice(0, limit).map((f) => `${baseName(f.file)} ×${f.count}`),
    };
  }
  const tests = parseTestFailures(round);
  if (tests.length) {
    return {
      kind: 'tests',
      total: tests.length,
      items: tests.slice(0, limit).map((t) => code(t.method ? `${t.simple}.${t.method}` : t.simple)),
      short: tests.slice(0, limit).map((t) => (t.method ? `${t.simple}.${t.method}` : t.simple)),
    };
  }
  return { kind: 'none', total: 0, items: [], short: [] };
}

// ---------------------------------------------------------------------------
// Diagrams
// ---------------------------------------------------------------------------

function stackDiagram(baseline, migration, finalRound, patchFiles) {
  const lang = migration.migration.language;
  const platform = migration.migration.platform;
  const green = finalRound && finalRound.outcome === 'passed';
  const lines = ['```mermaid', 'flowchart LR', ...CLASS_DEFS.map((d) => `  ${d}`)];
  lines.push('  subgraph BEFORE["Before"]', '    direction TB');
  lines.push(`    B1["${mermaidLabel(lang.name)} ${mermaidLabel(lang.from)}"]:::before`);
  lines.push(`    B2["${mermaidLabel(platform.name)} ${mermaidLabel(platform.from)}"]:::before`);
  lines.push(`    B3["${mermaidText(baseline.project.name, `${baseline.project.sources.main} main + ${baseline.project.sources.test} test sources`)}"]:::before`);
  lines.push('    B1 --- B2 --- B3');
  lines.push('  end');
  lines.push('  subgraph AFTER["After"]', '    direction TB');
  lines.push(`    A1["${mermaidLabel(lang.name)} ${mermaidLabel(lang.to)}"]:::after`);
  lines.push(`    A2["${mermaidLabel(platform.name)} ${mermaidLabel(platform.to)}"]:::after`);
  const changedFiles = patchFiles.length;
  lines.push(`    A3["${mermaidText(baseline.project.name, `${changedFiles} file${changedFiles === 1 ? '' : 's'} changed`)}"]:::${green ? 'ok' : 'degraded'}`);
  lines.push('    A1 --- A2 --- A3');
  lines.push('  end');
  const migrationRounds = Math.max((migration.round_notes || []).length - 1, 0);
  lines.push(`  BEFORE ==>|"${mermaidLabel(`${migrationRounds} migration round${migrationRounds === 1 ? '' : 's'}`)}"| AFTER`);
  lines.push('```');
  return lines.join('\n');
}

/**
 * Consecutive rounds that ran the same goal on the same JDK were chasing the same question, so the
 * diagram groups them into one phase. Derived from the records alone — no judgement needed, and a
 * migration with a different shape produces a differently shaped diagram.
 */
function roundPhases(rounds) {
  const phases = [];
  for (const r of rounds) {
    const key = r.baseline ? 'baseline' : `${r.build.intent}@${r.jdk.major}`;
    const last = phases[phases.length - 1];
    if (last && last.key === key) last.rounds.push(r);
    else phases.push({ key, rounds: [r], baseline: !!r.baseline, intent: r.build.intent, jdk: r.jdk.major });
  }
  return phases;
}

function phaseTitle(phase, index) {
  const count = phase.rounds.length;
  const rounds = count === 1 ? `round ${phase.rounds[0].round}` : `rounds ${phase.rounds[0].round}-${phase.rounds[count - 1].round}`;
  const what = phase.baseline
    ? `Reference build, before anything changed`
    : `Prove ${intentGoal(phase.intent)}`;
  return `Phase ${index} · ${what} · JDK ${phase.jdk} · ${rounds}`;
}

/** A round as a diagram node: outcome, what it ran, how it failed, and where. */
function roundNode(round) {
  const meta = outcomeOf(round.outcome);
  const totals = testTotals(round);
  const where = failureLocations(round, 2);
  const lines = [
    `${meta.emoji} Round ${round.round}${round.baseline ? ' · baseline' : ''} — ${meta.label}`,
    `${round.build.tool} ${round.build.intent} · JDK ${round.jdk.major} · ${durationText(round.duration_ms)}`,
  ];
  if (round.error_summary.total) {
    const distinct = distinctErrors(round).filter((e) => e.file).length;
    lines.push(`${plural(round.error_summary.total, 'error line')}${distinct ? ` · ${plural(distinct, 'distinct problem')}` : ''}`);
  } else {
    lines.push('no errors');
  }
  if (totals) {
    lines.push(`tests: ${totals.run} run · ${totals.failures} failed · ${totals.errors} errored`);
    const environmental = parseTestFailures(round).filter((t) => t.category === 'environment').length;
    if (environmental) lines.push(`${environmental} of them environmental, not code`);
  } else {
    const shape = failureShape(round);
    if (shape) lines.push(shape);
  }
  if (where.short.length) {
    const named = where.short.join(' · ');
    lines.push(`${named}${where.total > where.short.length ? ` +${where.total - where.short.length} more` : ''}`);
  }
  return `${roundId(round)}["${mermaidText(...lines)}"]:::${meta.className}`;
}

const roundId = (round) => `R${round.round}`;

/** What was changed between two rounds — the reason the next round exists. */
function edgeLabel(round, previous, notes) {
  const note = (notes || []).find((n) => n.round === round.round);
  const changes = (note && note.changes) || (round.label ? [round.label] : []);
  if (changes.length) {
    const head = shorten(changes[0], 52);
    return changes.length === 1 ? head : `${head} +${changes.length - 1} more`;
  }
  if (previous && previous.build.intent !== round.build.intent) {
    return `same code, next gate: ${round.build.tool} ${round.build.intent}`;
  }
  if (previous && previous.jdk.major !== round.jdk.major) return `same code, JDK ${previous.jdk.major} → ${round.jdk.major}`;
  return 'nothing changed — re-run';
}

/**
 * The migration as it actually ran: every round, grouped into the phase it belonged to, with the
 * change that provoked the next round written on the arrow between them. Top-down because the edge
 * labels carry real text — a left-to-right chain of eight rounds pushes them off the page.
 */
function roundsDiagram(rounds, notes) {
  const lines = ['```mermaid', 'flowchart TD', ...CLASS_DEFS.map((d) => `  ${d}`)];
  const styles = [];
  roundPhases(rounds).forEach((phase, index) => {
    const id = `P${index}`;
    lines.push(`  subgraph ${id}["${mermaidLabel(phaseTitle(phase, index))}"]`);
    lines.push('    direction TB');
    for (const round of phase.rounds) lines.push(`    ${roundNode(round)}`);
    lines.push('  end');
    styles.push(`  style ${id} fill:#f8f9fa,stroke:#ced4da,stroke-width:1px,color:#495057`);
  });
  for (let i = 1; i < rounds.length; i += 1) {
    const label = mermaidLabel(edgeLabel(rounds[i], rounds[i - 1], notes));
    lines.push(`  ${roundId(rounds[i - 1])} -->|"${label}"| ${roundId(rounds[i])}`);
  }
  lines.push(...styles);
  lines.push('```');
  return lines.join('\n');
}

function errorPie(round) {
  if (!round || !round.error_summary.total) return null;
  const lines = ['```mermaid', `pie showData title Round ${round.round} — ${round.error_summary.total} error lines by kind`];
  for (const c of round.error_summary.byCategory.slice(0, 8)) {
    lines.push(`  "${mermaidLabel(c.label)}" : ${c.count}`);
  }
  lines.push('```');
  return lines.join('\n');
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

const TEST_INTENTS = ['test', 'package', 'verify'];

/** Pulls Maven's final "Tests run: …" summary out of a round that actually ran tests. */
function testTotals(round) {
  if (!round || !TEST_INTENTS.includes(round.build.intent)) return null;
  const matches = [...String(round.log_tail || '').matchAll(/Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)(?:,\s*Skipped:\s*(\d+))?/g)];
  if (!matches.length) return null;
  const last = matches[matches.length - 1];
  return {
    round: round.round,
    run: Number(last[1]),
    failures: Number(last[2]),
    errors: Number(last[3]),
    skipped: Number(last[4] || 0),
  };
}

/**
 * Compares the test suite before and after. A migration that leaves the same tests failing for the
 * same pre-existing reasons is a different thing from one that breaks tests, and the report has to
 * be able to tell them apart without the reader taking anyone's word for it.
 */
function testComparison(rounds) {
  const before = testTotals(rounds.find((r) => r.baseline)) || testTotals(rounds.find((r) => TEST_INTENTS.includes(r.build.intent)));
  const after = [...rounds].reverse().map(testTotals).find((t) => t && (!before || t.round !== before.round));
  if (!before && !after) return null;
  if (!before || !after) {
    return { before, after, verdict: '⚠️ only one side ran the test suite', worse: false };
  }
  const badBefore = before.failures + before.errors;
  const badAfter = after.failures + after.errors;
  const worse = badAfter > badBefore;
  const verdict = badAfter === badBefore
    ? (badBefore === 0 ? '🟢 all green, before and after' : `🟡 the same ${plural(badBefore, 'pre-existing failure')} — none introduced`)
    : (worse ? `🔴 ${plural(badAfter - badBefore, 'new failure')} introduced` : `🟢 ${plural(badBefore - badAfter, 'fewer failure')} than before`);
  return { before, after, verdict, worse };
}

function atAGlance(baseline, migration, rounds, finalRound, runtime, patchFiles, ctx = {}) {
  const lang = migration.migration.language;
  const platform = migration.migration.platform;
  const meta = outcomeOf(finalRound && finalRound.outcome);
  const failed = rounds.filter((r) => r.outcome !== 'passed' && !r.baseline).length;
  const rows = [
    ['**Result**', `${meta.emoji} **${meta.label}** on the final build`],
    ['**Platform**', `${esc(platform.name)} \`${esc(platform.from)}\` → \`${esc(platform.to)}\``],
    ['**Language**', `${esc(lang.name)} \`${esc(lang.from)}\` → \`${esc(lang.to)}\``],
    ['**Build rounds**', `${rounds.length} recorded — 1 pre-migration baseline, ${failed} that failed and were read for what to change next, ${rounds.filter((r) => r.outcome === 'passed').length} green`],
    ['**Files changed**', patchFiles.length ? `${patchFiles.length} — ${patchFiles.map((f) => `\`${f.file}\``).join(', ')}` : 'none'],
    ['**Dependency changes**', `${(migration.dependency_changes || []).length}`],
    ['**Source changes forced by the upgrade**', `${(migration.code_changes || []).length}`],
  ];
  const tests = testComparison(rounds);
  if (tests) {
    const fmt = (t) => (t ? `${t.run} run, ${t.failures} failed, ${plural(t.errors, 'error')}` : 'not run');
    rows.push(['**Test suite**', `before: ${fmt(tests.before)} → after: ${fmt(tests.after)} — ${tests.verdict}`]);
  }
  if (runtime.baseline || runtime.final) {
    const compared = compareProbes(runtime, migration);
    rows.push(['**Runtime behaviour**', compared.verdictText]);
  }
  rows.push(['**Migration plan**', ctx.plan
    ? `recorded before any change — ${plural((ctx.plan.impact || []).length, 'predicted impact')}, ${plural((ctx.plan.deterministic_candidates || []).length, 'deterministic candidate')}`
    : '_none recorded — this session predates the plan step, or skipped it_']);
  rows.push(['**Deterministic transformations**', transformationsSummary(ctx.transformations || [])]);
  const requested = baseline.target && baseline.target.platform ? baseline.target.platform.version : null;
  if (requested) {
    const declared = finalRound && finalRound.declared && (finalRound.declared.platform || finalRound.declared.parent);
    const got = declared ? declared.version : null;
    rows.push(['**Requested target**', `${code(requested)} — final build declares ${code(got)} ${got === requested ? '🟢 matches' : '🔴 does not match'}`]);
  }
  rows.push(['**Reference followed**', code(migration.migration.reference_pack)]);
  rows.push(['**Build tool**', `${esc(baseline.build_tool.tool)}${baseline.build_tool.version ? ` — ${esc(baseline.build_tool.version)}` : ''}`]);
  return ['| | |', '|---|---|', ...rows.map(([k, v]) => `| ${k} | ${v} |`)].join('\n');
}

function versionMatrix(baseline, migration) {
  const rows = [];
  rows.push({
    what: `${migration.migration.language.name} (language level)`,
    from: migration.migration.language.from,
    to: migration.migration.language.to,
    why: 'Runtime baseline required by the new platform version.',
  });
  rows.push({
    what: `${migration.migration.platform.name} (platform)`,
    from: migration.migration.platform.from,
    to: migration.migration.platform.to,
    why: 'The version jump this migration is about.',
  });
  for (const d of migration.dependency_changes || []) {
    rows.push({ what: d.coordinate, from: d.from, to: d.to, why: d.reason, kind: d.kind });
  }
  const lines = ['| | What | Before | After | Why it moved |', '|---|---|---|---|---|'];
  for (const r of rows) {
    const mark = r.to === 'removed' ? '🗑️' : (r.kind === 'added' ? '➕' : (r.kind === 'rename' ? '🔀' : '⬆️'));
    lines.push(`| ${mark} | ${code(r.what)} | ${code(r.from)} | ${code(r.to)} | ${esc(r.why)} |`);
  }
  return lines.join('\n');
}

/** One paragraph framing the rounds before the reader meets the table. */
function roundsOverview(rounds, transformations = []) {
  const failed = rounds.filter((r) => r.outcome !== 'passed').length;
  const baselineRound = rounds.find((r) => r.baseline);
  const last = rounds[rounds.length - 1];
  const meta = outcomeOf(last.outcome);
  const totalMs = rounds.reduce((sum, r) => sum + (r.duration_ms || 0), 0);
  const applied = transformations.filter((t) => t.mode === 'apply' && t.status === 'applied');
  const reference = baselineRound
    ? `Round ${baselineRound.round} is the pre-migration reference build on JDK ${baselineRound.jdk.major}; every later round ran on JDK ${last.jdk.major}. `
    : '**No pre-migration reference build was recorded**, so nothing below can be compared against how the project behaved before the upgrade. ';
  const discipline = applied.length
    ? `Apart from the ${plural(applied.length, 'deterministic transformation')} in §2.4 — each previewed, inspected and then applied to the sandbox, and each built immediately — nothing was edited that a build, a test or a probe had not first complained about.`
    : 'Nothing was edited that a build had not first complained about.';
  return `**${plural(rounds.length, 'build round')}**, ${durationText(totalMs)} of build time in total. ${reference}`
    + `${failed} of them came back with errors, ${rounds.length - failed} came back clean. `
    + 'A red round is not a setback here — it is the output the migration is driven by: each one names the exact files or tests the next change has to touch. '
    + `${discipline} The migration ends on round ${last.round}, ${meta.emoji} **${meta.label}**.`;
}

// ---------------------------------------------------------------------------
// v2 sections: understanding, transformations, residual repairs, provenance
//
// Each keeps five things apart that a migration report must never blur: what was PREDICTED (the
// plan), what was OBSERVED (rounds, probes), what was TRANSFORMED (recorded OpenRewrite applies),
// what was VERIFIED (a green build, a compared probe) and what is still UNRESOLVED.
// ---------------------------------------------------------------------------

const TRANSFORMATION_STATUS = {
  previewed: ['🔍', 'previewed (dry-run, nothing changed)'],
  applied: ['🟢', 'applied to the sandbox'],
  'no-changes': ['⚪', 'ran, proposed no change'],
  unavailable: ['⛔', '**unavailable — did not run**'],
  failed: ['🔴', '**ran and failed**'],
  reverted: ['🔴', '**applied, then reverted** (outside the inspected preview)'],
  'rejected-config': ['🔴', '**refused before running** (configuration)'],
  'rejected-no-preview': ['🔴', '**refused before running** (no matching inspected preview)'],
};

function transformationsSummary(transformations) {
  if (!transformations.length) return '_none recorded_';
  const count = (pred) => transformations.filter(pred).length;
  const parts = [];
  const applied = count((t) => t.status === 'applied');
  const previewed = count((t) => t.status === 'previewed');
  const unavailable = count((t) => t.status === 'unavailable');
  const failed = count((t) => ['failed', 'reverted', 'rejected-config', 'rejected-no-preview'].includes(t.status));
  if (applied) parts.push(`🟢 ${applied} applied`);
  if (previewed) parts.push(`🔍 ${previewed} previewed`);
  if (unavailable) parts.push(`⛔ ${unavailable} unavailable (did not run)`);
  if (failed) parts.push(`🔴 ${failed} failed / reverted / refused`);
  const other = transformations.length - applied - previewed - unavailable - failed;
  if (other) parts.push(`⚪ ${other} with no change`);
  return `${parts.join(' · ')} — OpenRewrite`;
}

function roundsNamingFile(rounds, file) {
  return rounds.filter((r) => !r.baseline && (r.errors || []).some((e) => e.file && (e.file === file || e.file.endsWith(`/${file}`) || file.endsWith(e.file))))
    .map((r) => r.round);
}

function understandingSection(ctx) {
  const { plan, baseline, rounds, patchFiles, transformations, runtime } = ctx;
  const out = [];
  if (!plan) {
    out.push('_No migration plan was recorded for this session_ — it was run with the compiler-driven flow that predates the plan step, so there is nothing predicted to compare against. Every change below was discovered by a build.');
    out.push('');
    const mp = baseline.migration_path;
    if (mp && (mp.warnings.length || mp.unresolved.length)) {
      out.push('Observations `detect-baseline.js` recorded anyway:');
      out.push('');
      out.push(...mp.warnings.map((w) => `- ⚠️ ${esc(w)}`), ...mp.unresolved.map((u) => `- ❓ ${esc(u)}`));
      out.push('');
    }
    return out.join('\n');
  }

  out.push('> Everything in this section was written **before** any version or source changed — it is a prediction, and it is shown next to what the build, the tests and the probes then actually did. A predicted impact is never evidence that something failed.');
  out.push('');
  out.push('### 0.1 Target and path');
  out.push('');
  const mp = baseline.migration_path || {};
  out.push('| | |', '|---|---|');
  out.push(`| **Source** | ${esc(plan.source.platform.name)} ${code(plan.source.platform.version)} · ${esc(plan.source.language.name)} ${code(plan.source.language.version)} |`);
  out.push(`| **Requested target** | ${esc(plan.target.platform.name)} ${code(plan.target.platform.version)} · ${esc(plan.target.language.name)} ${code(plan.target.language.version)} — _as requested; never "latest", never a recipe's own target_ |`);
  if (plan.path && (plan.path.hops || []).length) {
    out.push(`| **Path** | ${plan.path.hops.map((h) => `${code(h.from)} → ${code(h.to)}${h.note ? ` (${esc(h.note)})` : ''}`).join(' · ')} |`);
  }
  if (mp.preparation_line) {
    out.push(`| **Preparation line** | ${code(`${mp.preparation_line}.x`)} — ${mp.on_preparation_line ? '🟢 the source is on it' : '🟡 the source is not on it'}${plan.path && plan.path.preparation ? `<br/>${esc(plan.path.preparation)}` : ''} |`);
  }
  out.push('');

  if ((plan.constraints || []).length) {
    out.push('### 0.2 Constraints and compatibility questions');
    out.push('');
    const MARK = { satisfied: '🟢', unresolved: '❓', violated: '🔴', 'not-applicable': '⚪' };
    const closing = new Map(((ctx.migration && ctx.migration.unresolved_constraints) || []).map((u) => [u.constraint, u]));
    out.push('| | Constraint | Status in the plan | At the end | Evidence |', '|---|---|---|---|---|');
    for (const c of plan.constraints) {
      const end = closing.get(c.id);
      out.push(`| ${MARK[c.status] || '⚪'} | **${esc(c.id)}** ${esc(c.statement)}${c.blocking ? ' _(blocking)_' : ''} | ${esc(c.status)} | ${end ? esc(end.status) : '—'} | ${esc((end && end.evidence) || c.evidence || '')} |`);
    }
    out.push('');
  }

  out.push('### 0.3 Predicted impact vs what actually happened');
  out.push('');
  const applied = transformations.filter((t) => t.status === 'applied');
  const transformedBy = (file) => applied.filter((t) => (t.changed_files || []).some((c) => c.file.split(' -> ').pop() === file)).map((t) => t.id);
  const inPatch = new Set(patchFiles.map((f) => f.file));
  const review = new Map(((ctx.migration && ctx.migration.impact_review) || []).map((r) => [r.impact, r]));
  out.push('_The build, transformation and patch columns are facts about the **file**; several impact entries can share one file (a build descriptor, say). Whether this particular impact was real is the agent review\'s column._');
  out.push('');
  out.push('| Impact | File | Area · rule | Risk | Predicted handling | File named by a failing build | File changed by a transformation | File in final patch | Agent review |', '|---|---|---|---|---|---|---|---|---|');
  for (const imp of plan.impact || []) {
    const erroredIn = roundsNamingFile(rounds, imp.file);
    const tx = transformedBy(imp.file);
    const r = review.get(imp.id);
    out.push(`| **${esc(imp.id)}** | ${code(imp.file)} | ${esc(imp.area)} · ${esc(imp.reference_rule)} | ${esc(imp.risk)} | ${esc(imp.handled_by || '—')} | ${erroredIn.length ? `rounds ${erroredIn.join(', ')}` : 'never named by a build'} | ${tx.length ? tx.join(', ') : '—'} | ${inPatch.has(imp.file) ? '✏️ yes' : 'no'} | ${r ? `${esc(r.outcome)}${r.note ? ` — ${esc(shorten(r.note, 120))}` : ''}` : '—'} |`);
  }
  const predictedFiles = new Set((plan.impact || []).map((i) => i.file));
  const unpredicted = patchFiles.filter((f) => !predictedFiles.has(f.file));
  out.push('');
  out.push(unpredicted.length
    ? `**Changed but not predicted:** ${unpredicted.map((f) => code(f.file)).join(', ')} — each is explained in §4/§5 or flagged there.`
    : '**Every changed file was predicted by the plan.**');
  out.push('');

  if ((plan.characterization || []).length) {
    out.push('### 0.4 Which probes protect which impact');
    out.push('');
    const ran = (name) => {
      const b = runtime.baseline && (runtime.baseline.probes || []).some((p) => p.name === name);
      const a = runtime.final && (runtime.final.probes || []).some((p) => p.name === name);
      return b && a ? '🟢 before and after' : (b || a ? '🟡 one side only' : '⚪ not run');
    };
    out.push('| Probe | Category | Protects | Replayed |', '|---|---|---|---|');
    for (const c of plan.characterization) {
      out.push(`| ${c.probe ? esc(c.probe) : '_none_'} | ${esc(c.category)} | ${(c.protects || []).map(esc).join(', ')} | ${c.probe ? ran(c.probe) : `⚪ not observable — ${esc(c.reason || 'no reason given')}`} |`);
    }
    out.push('');
  }

  if ((plan.out_of_scope || []).length || (plan.stop_conditions || []).length) {
    out.push('### 0.5 Out of scope, and when to stop');
    out.push('');
    if ((plan.out_of_scope || []).length) out.push('**Out of scope:**', '', ...bullets(plan.out_of_scope), '');
    if ((plan.stop_conditions || []).length) out.push('**Stop conditions:**', '', ...bullets(plan.stop_conditions), '');
  }
  return out.join('\n');
}

function transformationsSection(ctx) {
  const { transformations, plan, migration } = ctx;
  const out = [];
  const candidates = ((plan && plan.deterministic_candidates) || []).filter((c) => c.provider === 'openrewrite');
  if (!transformations.length) {
    out.push(candidates.length
      ? `_The plan named ${plural(candidates.length, 'OpenRewrite candidate')} (${candidates.map((c) => code(c.id)).join(', ')}), but **no transformation was run** — every change in this migration was made by hand as builds demanded it._`
      : '_No deterministic transformation was used — every change in this migration was made by hand as builds demanded it._');
    return out.join('\n');
  }
  out.push('OpenRewrite ran **inside the sandbox only**, as a provider 04D invoked — never as the orchestrator and never as proof. A dry-run is a preview that changes nothing; an apply is only accepted when every file it changed was in the inspected preview, and is built immediately. A transformation that was unavailable, failed or was reverted is listed here as exactly that.');
  out.push('');
  const decisions = new Map(((migration && migration.transformations) || []).map((d) => [d.record, d]));
  out.push('| Record | Mode | Outcome | Recipes | Artifact · plugin | Licence | Files | Built in | Agent decision |', '|---|---|---|---|---|---|---|---|---|');
  for (const t of transformations) {
    const [mark, label] = TRANSFORMATION_STATUS[t.status] || ['⚪', esc(t.status)];
    const files = t.mode === 'dry-run' ? `${(t.proposed_files || []).length} proposed` : `${(t.changed_files || []).length} changed`;
    const d = decisions.get(t.id);
    out.push(`| ${code(t.id)} | ${esc(t.mode)} | ${mark} ${label}${t.availability && t.availability.reason ? `<br/>_reason: ${esc(t.availability.reason)}_` : ''} | ${t.recipes.map((r) => code(r)).join('<br/>')} | ${t.artifacts.map((a) => code(a)).join('<br/>') || '—'}<br/>plugin ${code(t.plugin_version)} | ${esc(t.license || 'not recorded')} | ${files} | ${t.build_round !== null && t.build_round !== undefined ? `round ${t.build_round}` : '—'} | ${d ? `**${esc(d.decision)}** — ${esc(shorten(d.rationale, 140))}` : '—'} |`);
  }
  out.push('');
  for (const t of transformations) {
    const lines = [];
    if (t.reconciliation) {
      const rc = t.reconciliation;
      lines.push(`target reconciliation: recipe target ${code(rc.recipe_target)}, declared after apply ${code(rc.declared_after_apply)}, requested ${code(rc.requested_platform_version)} → **${esc(rc.status)}**`);
    }
    if (t.scope_check && !t.scope_check.ok) lines.push(`scope check failed — outside preview: ${(t.scope_check.out_of_preview || []).map((f) => code(f)).join(', ') || '—'}; infrastructure lines: ${(t.scope_check.infrastructure_lines || []).length}`);
    for (const n of t.notes || []) lines.push(esc(n));
    if (t.command) lines.push(`command (sanitised): ${code(t.command)}`);
    if (t.patch) lines.push(`patch: ${code(t.patch)}`);
    if (t.log) lines.push(`log: ${code(t.log)}`);
    if (lines.length) {
      out.push(`<details><summary>${t.id} — ${esc(t.mode)}, ${esc(t.status)}</summary>`, '', ...lines.map((l) => `- ${l}`), '', '</details>', '');
    }
  }
  return out.join('\n');
}

function residualSection(migration) {
  const changes = migration.code_changes || [];
  if (!changes.some((c) => c.origin)) {
    return '_The migration record does not say how each change was made (it predates the `origin` field); see §4 for the changes themselves._';
  }
  const ORIGIN = {
    openrewrite: 'OpenRewrite (deterministic)',
    'reference-rule': 'reference-pack rule, by hand',
    'harness-residual': 'residual repair, evidence-driven',
    'build-file': 'declared-version edit',
  };
  const out = ['| File | How it was changed | Evidence that required it | Rule |', '|---|---|---|---|'];
  for (const c of changes) {
    out.push(`| ${code(c.file)} | ${esc(ORIGIN[c.origin] || c.origin || '—')}${c.transformation ? ` (${code(c.transformation)})` : ''} | ${esc(c.evidence || (c.found_in_round !== undefined ? `round ${c.found_in_round}` : '—'))} | ${esc(c.reference_rule || '—')} |`);
  }
  return out.join('\n');
}

function groupsTable(round, note, ctx) {
  const groups = round.error_groups || groupErrors(round.errors || [], ctx && ctx.meta ? ctx.meta.workspace : null);
  if (!groups.length) return [];
  const out = ['**Errors grouped by shared subject** — parser facts: same category and same subject (a package, a symbol, an artifact)', ''];
  out.push('| Group | Lines | Kind | Subject | Package family | Files | Basis |', '|---|---|---|---|---|---|---|');
  for (const g of groups.slice(0, 15)) {
    out.push(`| ${g.id} | ${g.count} | ${esc(categoryMeta(g.category).label)} | ${code(g.subject)} | ${g.package_family ? code(g.package_family) : '—'} | ${g.files.map((f) => code(baseName(f))).join(', ') || '—'} | ${esc(g.basis)} |`);
  }
  out.push('');
  if (round.correlation && round.correlation.matches.length) {
    out.push(`_Heuristic correlation with the plan (${esc(round.correlation.basis)}):_ ${round.correlation.matches.map((m) => `${m.group} ↔ ${m.impact.join(', ')}`).join(' · ')}`);
    out.push('');
  }
  if (note && (note.root_causes || []).length) {
    out.push('**Root causes — agent judgement over those groups**', '');
    for (const rc of note.root_causes) {
      out.push(`- ${rc.groups.join(', ')} → ${esc(rc.judgement)}${rc.reference_rule ? ` _(rule: ${esc(rc.reference_rule)})_` : ''}${(rc.impact || []).length ? ` — confirms ${rc.impact.join(', ')}` : ''}`);
    }
    out.push('');
  }
  return out;
}

function provenanceSection(ctx) {
  const { slug, plan, transformations, rounds, runtime, baseline, migration, patchFiles } = ctx;
  const state = inferState(slug);
  const compared = compareProbes(runtime, migration);
  const last = rounds[rounds.length - 1];
  const unresolved = [
    ...((baseline.migration_path && baseline.migration_path.unresolved) || []),
    ...((plan && plan.constraints) || []).filter((c) => c.status === 'unresolved').map((c) => `${c.id}: ${c.statement}`),
  ];
  const closed = new Set(((migration.unresolved_constraints) || []).filter((u) => u.status !== 'unresolved').map((u) => u.constraint));
  const stillOpen = unresolved.filter((u) => !closed.has(String(u).split(':')[0]));
  const out = [];
  out.push('| Kind | What it covers here | Source |', '|---|---|---|');
  out.push(`| **Predicted** | ${plan ? `${plural((plan.impact || []).length, 'impact entry', 'impact entries')}, ${plural((plan.constraints || []).length, 'constraint')}` : '—'} | ${plan ? code('migration-plan.json') : '_no plan_'} |`);
  out.push(`| **Observed** | ${plural(rounds.length, 'build round')}; ${compared.total ? `${compared.total} probe(s) before and after` : 'no probe comparison'} | ${code('rounds/')} · ${code('runtime/')} |`);
  out.push(`| **Transformed** | ${transformations.filter((t) => t.status === 'applied').length} OpenRewrite apply(ies) accepted, ${transformations.filter((t) => t.status === 'unavailable').length} unavailable, ${transformations.filter((t) => ['failed', 'reverted'].includes(t.status)).length} failed/reverted | ${code('transformations/')} |`);
  out.push(`| **Verified** | final round ${last.round} ${outcomeOf(last.outcome).emoji} ${outcomeOf(last.outcome).label}; runtime ${compared.verdictText}; ${plural(patchFiles.length, 'file')} in the patch | round records, runtime records, the patch |`);
  out.push(`| **Unresolved** | ${stillOpen.length ? stillOpen.map(esc).join('<br/>') : 'nothing recorded as unresolved'} | ${code('baseline.json')} · plan · ${code('migration.json')} |`);
  out.push('');
  out.push(`**Session state:** ${code(state.state)}${state.legacy ? ' _(inferred from files — this session predates state.json)_' : ''} · reached ${state.reached.map((s) => code(s)).join(' → ') || '—'}`);
  out.push('');
  const pv = baseline.reference_provenance;
  if (pv) {
    out.push(`**Reference pack provenance:** reviewed ${esc(pv.last_reviewed || '—')}${pv.observed_run ? ` · observed run: ${esc(pv.observed_run)}` : ''}`);
    out.push('');
    out.push(...bullets(pv.sources || []));
    out.push('');
  }
  if (migration.provenance && (migration.provenance.sources || []).length) {
    out.push('**Additional sources the agent relied on:**');
    out.push('');
    out.push(...bullets(migration.provenance.sources));
    out.push('');
  }
  return out.join('\n');
}

/** The "What it told us" cell: what broke, where it broke, and the opening of the diagnosis. */
function toldUsCell(round, note) {
  const parts = [];
  if (round.outcome === 'passed') {
    parts.push(`**Nothing failed** — ${intentGoal(round.build.intent)} on JDK ${round.jdk.major}.`);
  } else {
    const shape = failureShape(round);
    if (shape) parts.push(`**${esc(shape)}**`);
    const where = failureLocations(round, 3);
    if (where.items.length) {
      const more = where.total > where.items.length ? ` +${where.total - where.items.length} more` : '';
      parts.push(`${where.kind === 'files' ? 'in' : 'failing tests:'} ${where.items.join(' · ')}${more}`);
    }
  }
  if (note && note.diagnosis) parts.push(esc(firstSentences(note.diagnosis, 2)));
  return parts.join('<br/>') || '—';
}

function roundsTable(rounds, notes) {
  const lines = ['| Round | Ran on | Goal | Outcome | Errors | Took | What it told us |', '|---|---|---|---|---|---|---|'];
  for (const r of rounds) {
    const meta = outcomeOf(r.outcome);
    const note = (notes || []).find((n) => n.round === r.round);
    const distinct = distinctErrors(r).filter((e) => e.file).length;
    const errorCell = r.error_summary.total
      ? `${r.error_summary.total}${distinct && distinct !== r.error_summary.total ? `<br/>_${distinct} distinct_` : ''}`
      : '—';
    lines.push(`| **${r.round}**${r.baseline ? '<br/>_(baseline)_' : ''} | JDK ${r.jdk.major} | ${code(r.build.intent)}<br/>_${esc(intentGoal(r.build.intent))}_ | ${meta.emoji} ${meta.label} | ${errorCell} | ${durationText(r.duration_ms)} | ${toldUsCell(r, note)} |`);
  }
  return lines.join('\n');
}

/**
 * The part of the report that answers "what is failing, in which file, and why" without the reader
 * having to open a build log. Everything in the tables is read off the round record; only the
 * "Why it failed" paragraph is the agent's, and it is labelled as such.
 */
function failureDetail(rounds, notes, ctx) {
  const failing = rounds.filter((r) => r.outcome !== 'passed');
  if (!failing.length) return '_Every round came back green — there is nothing to explain here._';
  const alreadyFailing = baselineTestFailures(rounds);
  const noteFor = (round) => (round ? (notes || []).find((n) => n.round === round.round) : null);
  const out = [];

  for (const round of failing) {
    const meta = outcomeOf(round.outcome);
    const note = noteFor(round);
    const nextRound = rounds[rounds.indexOf(round) + 1];
    const response = noteFor(nextRound);
    const files = fileDigest(round);
    const tests = parseTestFailures(round);
    const totals = testTotals(round);

    out.push(`#### Round ${round.round} — ${meta.emoji} ${meta.label}${round.baseline ? ' _(pre-migration reference, not migration damage)_' : ''}`);
    out.push('');
    const facts = [
      `${code(`${round.build.tool} ${round.build.intent}`)} on JDK ${round.jdk.major}`,
      plural(round.error_summary.total, 'error line'),
    ];
    if (files.length) facts.push(`${plural(files.reduce((n, f) => n + f.count, 0), 'distinct problem')} across ${plural(files.length, 'file')}`);
    if (totals) facts.push(`${totals.run} tests run · ${totals.failures} failed · ${totals.errors} errored`);
    out.push(facts.join(' · '));
    out.push('');

    if (files.length) {
      out.push('**Which files failed**');
      out.push('');
      out.push('| File | Problems | At line(s) | What the build said about it | Why this kind of error happens |', '|---|---|---|---|---|');
      for (const f of files) {
        const said = f.problems.slice(0, 4)
          .map(([message, count]) => `${esc(message)}${count > 1 ? ` _(×${count})_` : ''}`)
          .join('<br/>');
        const more = f.problems.length > 4 ? `<br/>_…and ${f.problems.length - 4} more_` : '';
        out.push(`| ${code(f.file)} | ${f.count} | ${lineRange(f.lines)} | ${said}${more} | ${esc(categoryMeta(f.topCategory).hint)} |`);
      }
      out.push('');
    }

    if (tests.length) {
      out.push('**Which tests failed**');
      out.push('');
      out.push('| Test | Source file | Why it failed | What kind of problem | Was it already failing? |', '|---|---|---|---|---|');
      for (const t of tests) {
        const source = sourcePathFor(t.fqcn, ctx);
        const where = source ? `${code(source)}${t.line ? `<br/>line ${t.line}` : ''}` : `${code(t.fqcn)}${t.line ? `<br/>line ${t.line}` : ''}`;
        const history = round.baseline
          ? '_this is the baseline_'
          : (alreadyFailing.has(t.key) ? '⚪ yes — already failing in the reference build' : '🔴 no — new since the reference build');
        out.push(`| ${code(t.method ? `${t.simple}.${t.method}` : t.simple)}${t.kind === 'error' ? '<br/>_(errored, not asserted)_' : ''} | ${where} | ${esc(t.reason)} | ${esc(categoryMeta(t.category).label)} — ${esc(categoryMeta(t.category).hint)} | ${history} |`);
      }
      out.push('');
    }

    if (!files.length && !tests.length) {
      const messages = buildLevelMessages(round);
      if (messages.length) {
        out.push('**What the build reported** — no file was named, so this failed before or after compilation:');
        out.push('');
        out.push('| Message | Kind |', '|---|---|');
        for (const m of messages.slice(0, 8)) out.push(`| ${esc(shorten(m.message, 200))} | ${esc(categoryMeta(m.category).label)} |`);
        out.push('');
      }
    }

    if (note && note.diagnosis) {
      out.push('**Why it failed**');
      out.push('');
      out.push(esc(note.diagnosis));
      out.push('');
    }

    if (response && (response.changes || []).length) {
      out.push(`**What was changed in response** — then re-run as round ${nextRound.round}`);
      out.push('');
      out.push(...response.changes.map((c) => `- ${esc(c)}`));
      if ((response.reference_rules || []).length) {
        out.push('');
        out.push(`Reference rule(s) applied: ${response.reference_rules.map((r) => code(r)).join(' · ')}`);
      }
      out.push('');
    } else if (!nextRound) {
      out.push('⚠️ **This was the last round recorded, and it was not green.** Nothing was changed after it.');
      out.push('');
    } else {
      out.push(`**No source change was needed** — round ${nextRound.round} re-ran ${code(`${nextRound.build.tool} ${nextRound.build.intent}`)} on the same code.`);
      out.push('');
    }

    out.push(`_Every error line, the exact command and the full build log for this round are in **section 3, Round ${round.round}**._`);
    out.push('');
  }
  return out.join('\n');
}

function roundDetail(round, note, ctx) {
  const meta = outcomeOf(round.outcome);
  const out = [];
  out.push(`### Round ${round.round} — ${meta.emoji} ${meta.label}${round.baseline ? ' _(pre-migration reference)_' : ''}`);
  out.push('');
  if (round.baseline) {
    out.push('> **Nothing had been changed yet.** This is the reference every later round is read against.');
    out.push('');
  } else if (note && note.changes && note.changes.length) {
    out.push(`> **Changed before this round:** ${esc(note.changes.join(' · '))}`);
    out.push('');
  } else if (round.label) {
    out.push(`> **Changed before this round:** ${esc(round.label)}`);
    out.push('');
  }
  out.push('| | |', '|---|---|');
  out.push(`| **Command** | ${code(round.build.command)} |`);
  out.push(`| **JDK** | ${round.jdk.major} (${esc(round.jdk.version)}) |`);
  out.push(`| **Declared in the build** | Java ${code(round.declared.java)}${round.declared.parent ? ` · ${code(`${round.declared.parent.artifactId} ${round.declared.parent.version}`)}` : ''} |`);
  out.push(`| **Exit code** | ${round.build.exit_code === null ? '—' : round.build.exit_code} |`);
  out.push(`| **Duration** | ${durationText(round.duration_ms)} |`);
  out.push(`| **Workspace at this point** | ${esc(round.workspace.diff_stat)} |`);
  out.push('');

  if (note) {
    out.push('**What the result meant**');
    out.push('');
    out.push(esc(note.diagnosis));
    out.push('');
  }

  if (round.error_summary.total) {
    out.push('**Errors by kind**');
    out.push('');
    out.push('| Count | Kind | What this kind of error means |', '|---|---|---|');
    for (const c of round.error_summary.byCategory) {
      const m = categoryMeta(c.id);
      out.push(`| ${c.count} | ${esc(c.label)} | ${esc(m.hint)} |`);
    }
    out.push('');
    const files = fileDigest(round);
    if (files.length) {
      out.push('**Which files, and what was wrong with each**');
      out.push('');
      out.push('| File | Problems | At line(s) | Dominant kind |', '|---|---|---|---|');
      for (const f of files) {
        out.push(`| ${code(f.file)} | ${f.count} | ${lineRange(f.lines)} | ${esc(categoryMeta(f.topCategory).label)} |`);
      }
      out.push('');
    }
    const failedTests = parseTestFailures(round);
    if (failedTests.length) {
      out.push('**Which tests failed**');
      out.push('');
      out.push('| Test | Source file | Line | Why it failed |', '|---|---|---|---|');
      for (const t of failedTests) {
        const source = sourcePathFor(t.fqcn, ctx);
        out.push(`| ${code(t.method ? `${t.simple}.${t.method}` : t.simple)} | ${source ? code(source) : code(t.fqcn)} | ${t.line || '—'} | ${esc(t.reason)} |`);
      }
      out.push('');
    }
    const distinct = [...new Set(round.errors.map((e) => e.message))];
    out.push('**Distinct messages**');
    out.push('');
    out.push('```text');
    for (const m of distinct.slice(0, 25)) out.push(m.length > 220 ? `${m.slice(0, 220)}…` : m);
    if (distinct.length > 25) out.push(`… and ${distinct.length - 25} more`);
    out.push('```');
    out.push('');
    out.push(...allErrorsTable(round));
    out.push(...groupsTable(round, note, ctx));
  }
  if (round.transformation) {
    out.push(`**This round built the OpenRewrite apply ${code(round.transformation)}** — see §2.4.`);
    out.push('');
  }

  if (note && note.reference_rules && note.reference_rules.length) {
    out.push(`**Reference rules applied:** ${note.reference_rules.map((r) => code(r)).join(' · ')}`);
    out.push('');
  }

  out.push('<details>');
  out.push('<summary>Build log tail</summary>');
  out.push('');
  out.push('```text');
  const logLines = String(round.log_tail || '(no output captured)').split(/\r?\n/);
  if (logLines.length > 60) out.push(`… first ${logLines.length - 60} line(s) omitted — the complete log is at rounds/round-${String(round.round).padStart(2, '0')}.log`);
  out.push(...logLines.slice(-60));
  out.push('```');
  out.push('');
  out.push('</details>');
  out.push('');
  return out.join('\n');
}

function codeChanges(migration) {
  const changes = migration.code_changes || [];
  if (!changes.length) return '_No source file needed changing — the upgrade was dependency-only._';
  const byCategory = new Map();
  for (const c of changes) {
    const key = c.category || 'Other';
    if (!byCategory.has(key)) byCategory.set(key, []);
    byCategory.get(key).push(c);
  }
  const out = [];
  out.push('| Category | Files | Forced by |', '|---|---|---|');
  for (const [category, items] of byCategory) {
    out.push(`| **${esc(category)}** | ${items.length} | ${esc(items[0].why)} |`);
  }
  out.push('');
  for (const [category, items] of byCategory) {
    out.push(`### ${category}`);
    out.push('');
    for (const c of items) {
      out.push(`**${code(c.file)}**${c.found_in_round !== undefined ? ` — surfaced by round ${c.found_in_round}` : ''}`);
      out.push('');
      out.push(`${esc(c.what)} ${esc(c.why)}`);
      out.push('');
      if (c.reference_rule) out.push(`_Reference rule: ${esc(c.reference_rule)}_`);
      if (c.before || c.after) {
        out.push('');
        out.push('```diff');
        for (const line of String(c.before || '').split(/\r?\n/)) if (line.trim()) out.push(`- ${line}`);
        for (const line of String(c.after || '').split(/\r?\n/)) if (line.trim()) out.push(`+ ${line}`);
        out.push('```');
      }
      out.push('');
    }
  }
  return out.join('\n');
}

/**
 * Splits the cumulative patch into one entry per file, with line counts. Everything the report
 * says about "what changed" is derived from this — the actual patch — so a file cannot be left
 * out of the report by being left out of the agent's judgement file.
 */
function splitPatchByFile(patchText) {
  if (!patchText) return [];
  return patchText
    .split(/^diff --git /m)
    .filter((part) => part.trim())
    .map((part) => {
      const chunk = `diff --git ${part.replace(/\s+$/, '')}`;
      const header = /^diff --git a\/(.+?) b\/(.+)$/m.exec(chunk);
      const file = header ? header[2].trim() : '(unknown)';
      const added = (chunk.match(/^\+(?!\+\+ )/gm) || []).length;
      const removed = (chunk.match(/^-(?!-- )/gm) || []).length;
      const status = /^new file mode/m.test(chunk) ? 'added'
        : (/^deleted file mode/m.test(chunk) ? 'deleted'
          : (/^rename from/m.test(chunk) ? 'renamed' : 'modified'));
      return { file, chunk, added, removed, status, binary: /^Binary files /m.test(chunk) };
    });
}

const STATUS_MARK = { added: '➕', deleted: '🗑️', renamed: '🔀', modified: '✏️' };

/**
 * Section 5: every file in the patch, with its own diff. The per-file note is matched from the
 * agent's judgement; a file with no note is called out rather than quietly listed, because an
 * unexplained change in a migration is exactly what a reviewer needs to see.
 */
function everyFileChanged(migration, patchFiles) {
  if (!patchFiles.length) return '_The patch is empty — nothing was changed._';
  const noteFor = (file) => {
    const code = (migration.code_changes || []).find((c) => c.file && file.endsWith(c.file.replace(/^\.\//, '')));
    if (code) return { text: `${code.what}`, explained: true };
    const isDescriptor = /(^|\/)(pom\.xml|build\.gradle(\.kts)?|gradle\.properties)$/.test(file);
    if (isDescriptor && (migration.dependency_changes || []).length) {
      return { text: `Version and coordinate changes — see §1 (${migration.dependency_changes.length} entries).`, explained: true };
    }
    const ancillary = /Dockerfile|docker-compose|\.ya?ml$|\.tool-versions|\.sdkmanrc|workflows\//.test(file);
    if (ancillary) return { text: 'Runtime/CI version change.', explained: false };
    return { text: '_not explained in the migration record_', explained: false };
  };

  const out = [];
  out.push(`All **${patchFiles.length}** file(s) below differ from the project as it stood before round 0. Line counts are from the patch itself.`);
  out.push('');
  out.push('| | File | + | − | What changed |', '|---|---|---|---|---|');
  for (const f of patchFiles) {
    const note = noteFor(f.file);
    out.push(`| ${STATUS_MARK[f.status]} | ${code(f.file)} | ${f.binary ? '—' : `+${f.added}`} | ${f.binary ? '—' : `−${f.removed}`} | ${esc(note.text)} |`);
  }
  out.push('');

  const unexplained = patchFiles.filter((f) => !noteFor(f.file).explained && !f.binary);
  if (unexplained.length) {
    out.push(`> ⚠️ **${unexplained.length} changed file(s) carry no entry in the migration record**: ${unexplained.map((f) => code(f.file)).join(', ')}.`);
    out.push('> Read their diffs below and confirm each one is genuinely required by the upgrade.');
    out.push('');
  }

  out.push('### Every change, file by file');
  out.push('');
  for (const f of patchFiles) {
    out.push('<details>');
    out.push(`<summary><b>${f.file}</b> — ${f.status}${f.binary ? ', binary' : ` (+${f.added} −${f.removed})`}</summary>`);
    out.push('');
    const note = noteFor(f.file);
    out.push(esc(note.text));
    out.push('');
    out.push('```diff');
    const lines = f.chunk.split(/\r?\n/);
    out.push(...(lines.length > 400
      ? [...lines.slice(0, 400), `… ${lines.length - 400} more line(s) — see the full patch file`]
      : lines));
    out.push('```');
    out.push('');
    out.push('</details>');
    out.push('');
  }
  return out.join('\n');
}

function allErrorsTable(round) {
  if (!round.error_summary.total) return [];
  const out = ['<details>'];
  out.push(`<summary>Every error line recorded in this round (${round.errors.length}${round.errors_truncated ? ` of ${round.error_summary.total}` : ''})</summary>`);
  out.push('');
  out.push('| # | File | Line | Kind | Message |', '|---|---|---|---|---|');
  round.errors.forEach((e, i) => {
    out.push(`| ${i + 1} | ${e.file ? code(e.file) : '_build_'} | ${e.line || '—'} | ${esc(categoryMeta(e.category).label)} | ${esc(e.message)} |`);
  });
  if (round.errors_truncated) out.push(`\n_${round.errors_truncated} further error line(s) were not recorded; the full log is below._`);
  out.push('');
  out.push('</details>');
  out.push('');
  return out;
}

/**
 * A changed status is a different kind of finding from a changed body at the same status: the
 * first is a contract break, the second is usually an envelope, a timestamp or an ordering. The
 * raw verdict is computed in lib/migration.js from the recorded status and hash alone; the extra
 * observations and the agent's classification ride beside it and never replace it.
 */
function compareProbes(runtime, migration = null) {
  const judgements = (migration && migration.behaviour && migration.behaviour.differences) || [];
  return compareProbeRecords(runtime.baseline, runtime.final, judgements);
}

const SEMANTIC_TEXT = {
  'same-json-ignoring-key-order': 'same JSON once key order is ignored',
  'same-after-configured-ignored-paths': 'same JSON once the configured paths are ignored',
  differs: 'JSON content differs',
};
const CLASSIFICATION_TEXT = {
  'expected-framework-change': '🟡 expected framework change',
  'non-deterministic': '⚪ non-deterministic between runs',
  regression: '🔴 regression',
  unexplained: '🟠 unexplained',
};

function behaviourSection(runtime, migration, ctxRounds) {
  const compared = compareProbes(runtime, migration);
  const out = [];
  const before = runtime.baseline;
  const after = runtime.final;

  if (!before && !after) {
    out.push('> ⚠️ **The application was never exercised.** Neither runtime probe ran, so nothing in');
    out.push('> this report says the migrated application still behaves as it did. A green build is');
    out.push('> not evidence of preserved behaviour.');
    out.push('');
    if (migration.behaviour) {
      out.push(`**Verdict:** ${esc(migration.behaviour.verdict)}${migration.behaviour.notes ? ` — ${esc(migration.behaviour.notes)}` : ''}`);
      out.push('');
    }
    return out.join('\n');
  }

  out.push('| | Before | After |', '|---|---|---|');
  const ranOn = (r) => (r && r.jdk ? `JDK ${r.jdk.major} (${esc(r.jdk.version)})` : '—');
  out.push(`| **Ran on** | ${ranOn(before)} | ${ranOn(after)} |`);
  out.push(`| **Application started** | ${before ? (before.started ? '🟢 yes' : '🔴 no') : '—'} | ${after ? (after.started ? '🟢 yes' : '🔴 no') : '—'} |`);
  out.push(`| **Startup time** | ${before && before.startup_seconds ? `${before.startup_seconds}s` : '—'} | ${after && after.startup_seconds ? `${after.startup_seconds}s` : '—'} |`);
  out.push(`| **Probes replayed** | ${before ? (before.probes || []).length : '—'} | ${after ? (after.probes || []).length : '—'} |`);
  out.push('');

  if (!before || !after) {
    out.push(`> ⚠️ **Only the ${before ? 'before' : 'after'} side was probed**, so there is no comparison —`);
    out.push('> nothing here shows whether behaviour was preserved.');
    out.push('');
  }

  if (compared.probeSetChanged) {
    out.push('> ⚠️ **The before and after runs replayed different probe definitions** (`probes_sha` differs). Rows below are matched by probe name; a request whose definition changed is not a like-for-like comparison.');
    out.push('');
  }

  if (compared.rows.length) {
    const extra = compared.rows.some((r) => r.semantic || r.classification || r.category);
    out.push(extra
      ? '| | Request | Before | After | Same response? (raw) | Extra observation | Agent classification |'
      : '| | Request | Before | After | Same response? |');
    out.push(extra ? '|---|---|---|---|---|---|---|' : '|---|---|---|---|---|');
    const MARK = { identical: ['🟢', 'identical'], 'body-differs': ['🟡', 'same status, **body differs**'], 'status-differs': ['🔴', '**status changed**'] };
    for (const r of compared.rows) {
      const [mark, label] = MARK[r.verdict] || ['⚪', 'not run'];
      const base = `| ${mark} | ${esc(r.name)}${r.category ? ` _(${esc(r.category)})_` : ''}<br/>${code(r.request)} | \`${r.beforeStatus}\` · \`${r.beforeHash || '—'}\` | \`${r.afterStatus}\` · \`${r.afterHash || '—'}\` | ${label} |`;
      if (!extra) { out.push(base); continue; }
      const observation = r.semantic ? esc(SEMANTIC_TEXT[r.semantic] || r.semantic) : '—';
      const judgement = r.classification ? `${CLASSIFICATION_TEXT[r.classification] || esc(r.classification)}${r.explanation ? `<br/>${esc(shorten(r.explanation, 160))}` : ''}` : (r.verdict === 'identical' ? '—' : '_not classified_');
      out.push(`${base} ${observation} | ${judgement} |`);
    }
    out.push('');
    out.push(`🟢 ${compared.matched} identical · 🟡 ${compared.bodyOnly} same status with a different body · 🔴 ${compared.statusChanged} changed status`);
    out.push('');
    out.push('_Responses are compared by HTTP status and a hash of the whitespace-normalised body. A body that embeds a timestamp or an unordered collection can differ between two runs of the same build — check such a row against the excerpts in the runtime records before treating it as a migration effect._');
    if (extra) {
      out.push('');
      out.push('_The **raw** column is the evidence and is never overridden. "Extra observation" is a second, order-insensitive (or explicitly-configured-path-insensitive) hash recorded alongside it; "Agent classification" is judgement from `migration.json`, not measurement._');
    }
    out.push('');

    const security = compared.rows.filter((r) => r.category === 'security-boundary');
    if (security.length) {
      const held = security.every((r) => r.beforeStatus === r.afterStatus);
      out.push(`**Security boundary** — ${held ? '🟢 every security-boundary probe returned the same status before and after' : '🔴 a security-boundary probe changed status'}: ${security.map((r) => `${esc(r.name)} \`${r.beforeStatus}\`→\`${r.afterStatus}\``).join(' · ')}`);
      out.push('');
    }
  }

  for (const [label, record] of [['before', before], ['after', after]]) {
    const effects = (record && record.sandbox_side_effects) || [];
    if (effects.length) {
      out.push(`> ⚠️ **The running application wrote ${effects.length} file(s) into the sandbox during the ${label} probe**: ${effects.slice(0, 8).map((e) => code(e.file)).join(', ')}. They are not migration changes; make sure none of them is in the patch.`);
      out.push('');
    }
  }

  if (migration.behaviour) {
    out.push(`**Verdict:** ${esc(migration.behaviour.verdict)}${migration.behaviour.notes ? ` — ${esc(migration.behaviour.notes)}` : ''}`);
    out.push('');
    if (migration.behaviour.security_boundary) {
      out.push(`**Security boundary (agent):** ${esc(migration.behaviour.security_boundary)}`);
      out.push('');
    }
    if ((migration.behaviour.unobserved_areas || []).length) {
      out.push('**Not observed — so not claimed as preserved:**');
      out.push('');
      out.push(...bullets(migration.behaviour.unobserved_areas));
      out.push('');
    }
  }

  const tests = testComparison(ctxRounds);
  if (tests) {
    out.push('### The test suite, before and after');
    out.push('');
    out.push('| | Round | Tests run | Failures | Errors | Skipped |', '|---|---|---|---|---|---|');
    for (const [label, totals] of [['Before', tests.before], ['After', tests.after]]) {
      if (!totals) {
        out.push(`| **${label}** | — | not run | — | — | — |`);
        continue;
      }
      const mark = totals.failures + totals.errors === 0 ? '🟢' : '🔴';
      out.push(`| **${label}** ${mark} | ${totals.round} | ${totals.run} | ${totals.failures} | ${totals.errors} | ${totals.skipped} |`);
    }
    out.push('');
    out.push(`**${tests.verdict}**`);
    out.push('');
    out.push('_Both rows come from the same build goal on the same suite, so the counts are directly comparable. A failure present on both sides was already failing before the migration started._');
    out.push('');
  }
  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Cumulative patch
// ---------------------------------------------------------------------------

function exportDiff(paths, meta) {
  // No sandbox repository (an archived session, say) means no patch — never a git command that
  // would fall through to whatever repository encloses the session directory.
  if (!meta || !isSandboxRepo(paths.workspace)) return { text: null, stat: null };
  const git = (...a) => run('git', ['-C', paths.workspace, ...a]);
  git('add', '-A');
  const diff = git('diff', '--cached', '--binary', meta.baseline_commit);
  const stat = git('diff', '--cached', '--stat', meta.baseline_commit).stdout.trim();
  return { text: diff.stdout || '', stat };
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

function renderReport(ctx) {
  const {
    slug, baseline, migration, rounds, runtime, finalRound, patchStat, meta,
  } = ctx;
  const outcome = outcomeOf(finalRound && finalRound.outcome);
  const failingRound = rounds
    .filter((r) => r.error_summary.total > 0)
    .sort((a, b) => b.error_summary.total - a.error_summary.total || a.round - b.round)[0];
  const out = [];

  out.push(`# Migration Report — ${migration.title}`);
  out.push('');
  out.push(`## ${baseline.project.name}`);
  out.push('');
  // The migration's status is the per-run summary's, computed by the same rules (status changes,
  // lost endpoints, unfinished edges, blocking conditions) — not the final build's outcome alone.
  let verdict = null;
  try { verdict = buildSummary(slug, { assumeRendered: true }); } catch { verdict = null; }
  const verdictColour = verdict ? ({ PASS: '3DA35B', 'PARTIAL PASS': 'E8A33D', FAIL: 'C92A2A', BLOCKED: 'C92A2A' }[verdict.final_status] || '868E96') : '868E96';
  out.push([
    ...(verdict ? [badge('Migration', verdict.final_status, verdictColour)] : []),
    badge('Final build', outcome.label, outcome.colour),
    badge(migration.migration.platform.name, `${migration.migration.platform.from} → ${migration.migration.platform.to}`, '2E5FD9'),
    badge('Java', `${migration.migration.language.from} → ${migration.migration.language.to}`, '6E86E8'),
    badge('Rounds', String(rounds.length), '1F3864'),
    badge('Files changed', String(ctx.patchFiles.length), 'A0399B'),
  ].join(' '));
  out.push('');
  out.push(`> ${migration.summary}`);
  out.push('');
  if (verdict) {
    out.push(`> **Migration status: ${verdict.final_status}** — ${verdict.status_reason}`);
    out.push('');
  }
  out.push(`_Generated by the Version Migration skill on ${new Date().toISOString().slice(0, 10)}. Every version, error count and response below is read from a recorded run, not from memory._`);
  out.push('');

  out.push('## At a glance');
  out.push('');
  out.push(atAGlance(baseline, migration, rounds, finalRound, runtime, ctx.patchFiles, ctx));
  out.push('');

  out.push('## 0. What was understood before anything changed');
  out.push('');
  out.push(understandingSection(ctx));
  out.push('');

  // The planned edges (ladder sessions) and endpoint preservation — the same facts the per-run
  // summary carries, generated from the same evidence by the same code.
  const pathAndEndpoints = (() => {
    try {
      const md = renderMarkdown(buildSummary(ctx.slug));
      const start = md.indexOf('## Migration path (edges)');
      const end = md.indexOf('## Planned transformations');
      return start !== -1 && end > start ? md.slice(start, end).replace(/^## /gm, '### ').trim() : null;
    } catch (error) {
      return `_Path and endpoint sections unavailable: ${error.message}_`;
    }
  })();
  if (pathAndEndpoints) {
    out.push('## 0.6 Migration path and endpoint preservation');
    out.push('');
    out.push(pathAndEndpoints);
    out.push('');
  }

  out.push('## 1. What moved');
  out.push('');
  out.push(stackDiagram(baseline, migration, finalRound, ctx.patchFiles));
  out.push('');
  out.push(versionMatrix(baseline, migration));
  out.push('');

  out.push('## 2. How the migration went');
  out.push('');
  out.push(roundsOverview(rounds, ctx.transformations));
  out.push('');
  out.push(roundsDiagram(rounds, migration.round_notes));
  out.push('');
  out.push('### 2.1 The round ledger');
  out.push('');
  out.push(roundsTable(rounds, migration.round_notes));
  out.push('');
  out.push('### 2.2 Exactly what failed, and why');
  out.push('');
  out.push('One block per round that did not come back green: the files or the tests the build named, the message it printed against each one, and what that meant. Paths are as the compiler reported them, relative to the project root, and the line numbers are the lines it pointed at. Everything in the tables is read out of the recorded build; only the **Why it failed** paragraph is the agent\'s reading of it.');
  out.push('');
  out.push(failureDetail(rounds, migration.round_notes, ctx));
  out.push('');
  const pie = errorPie(failingRound);
  if (pie) {
    out.push('### 2.3 The mix of breakage in the worst round');
    out.push('');
    out.push(`Round ${failingRound.round} carried more error lines than any other — ${failingRound.error_summary.total} of them, in these proportions:`);
    out.push('');
    out.push(pie);
    out.push('');
  }
  out.push('### 2.4 Deterministic transformations');
  out.push('');
  out.push(transformationsSection(ctx));
  out.push('');
  out.push('### 2.5 How each change was made');
  out.push('');
  out.push(residualSection(migration));
  out.push('');

  out.push('## 3. Round by round');
  out.push('');
  for (const round of rounds) {
    out.push(roundDetail(round, migration.round_notes.find((n) => n.round === round.round), ctx));
  }

  out.push('## 4. Source changes the upgrade forced');
  out.push('');
  out.push(codeChanges(migration));
  out.push('');

  out.push('## 5. Every file that changed');
  out.push('');
  out.push(everyFileChanged(migration, ctx.patchFiles));
  out.push('');

  out.push('## 6. Does it still behave the same?');
  out.push('');
  out.push(behaviourSection(runtime, migration, rounds));

  out.push('## 7. Changes that were *not* caused by the upgrade');
  out.push('');
  if ((migration.out_of_scope_changes || []).length) {
    out.push('Made or noticed along the way, and listed separately so the migration record stays honest — none of them is required by the version jump, and each should be reviewed on its own merits.');
    out.push('');
    out.push('| Change | Why it is separate |', '|---|---|');
    for (const c of migration.out_of_scope_changes) out.push(`| ${esc(c.change)} | ${esc(c.why_separate)} |`);
  } else {
    out.push('_None — everything in the patch traces to the version jump._');
  }
  out.push('');

  out.push('## 8. What still needs a human');
  out.push('');
  out.push('**Follow-ups**');
  out.push('');
  out.push(...bullets(migration.manual_follow_ups, '_none recorded_'));
  out.push('');
  out.push('**Residual risk**');
  out.push('');
  out.push(...bullets(migration.residual_risk, '_none recorded_'));
  out.push('');

  out.push('## 9. The patch');
  out.push('');
  out.push(`The whole migration is one cumulative patch against the project as it stood before round 0. It was produced and built inside a sandbox copy — **the project directory itself was never modified**.`);
  out.push('');
  if (patchStat) {
    out.push('```text');
    out.push(patchStat);
    out.push('```');
    out.push('');
  }
  out.push(`Patch file: [\`migration_${slug}.diff\`](./migration_${slug}.diff)`);
  out.push('');
  const diffPath = sessionPaths(slug).reportDiff.split(path.sep).join('/');
  out.push('Applying it, from `.github/skills/04d-version-migration/`. This copies the migrated files');
  out.push('out of the sandbox, works whether or not the project is version-controlled, and refuses');
  out.push('unless the final round was green:');
  out.push('');
  out.push('```powershell');
  out.push(`node scripts/apply-migration.js --slug ${slug}                # dry run: lists what would change`);
  out.push(`node scripts/apply-migration.js --slug ${slug} --to-project   # writes the project`);
  out.push('```');
  out.push('');
  out.push('Or, if the project is a git repository and you would rather apply the patch itself:');
  out.push('');
  out.push('```powershell');
  out.push(`cd "${baseline.project.dir}"`);
  out.push(`git apply --check "${diffPath}"   # dry run first`);
  out.push(`git apply "${diffPath}"`);
  out.push('```');
  out.push('');

  out.push('## 10. Evidence and provenance');
  out.push('');
  out.push(provenanceSection(ctx));
  out.push('');

  out.push('---');
  out.push('');
  out.push('<details>');
  out.push('<summary>Where each fact in this report came from</summary>');
  out.push('');
  out.push('| Fact | Source | Produced by |', '|---|---|---|');
  out.push(`| Declared versions, dependency list, JDKs available, source-surface scan | ${code(rel(sessionPaths(slug).baseline))} | \`detect-baseline.js\` |`);
  out.push(`| Sandbox and baseline commit | ${code(rel(sessionPaths(slug).workspaceMeta))} | \`prepare-workspace.js\` |`);
  out.push(`| Every round's outcome, errors, error groups and timings | ${code(`${rel(sessionPaths(slug).roundsDir)}/round-NN.json`)} | \`run-migration-build.js\` |`);
  out.push(`| OpenRewrite previews and applies — command, recipes, licence, patch, log | ${code(`${rel(sessionPaths(slug).transformationsDir)}/rewrite-NN.json`)} | \`run-migration-build.js\` → \`lib/openrewrite.js\` |`);
  out.push(`| Before/after runtime responses | ${code(`${rel(sessionPaths(slug).runtimeDir)}/{baseline,final}.json`)} | \`probe-runtime.js\` |`);
  out.push(`| Predicted impact, constraints, selected transformations | ${code(rel(sessionPaths(slug).plan))} | the agent, before any change |`);
  out.push(`| Diagnoses, reasons, classifications, risks, follow-ups | ${code(rel(sessionPaths(slug).migration))} | the agent |`);
  out.push(`| Session progress | ${code(rel(sessionPaths(slug).state))} (or inferred from the files above) | every script |`);
  out.push('');
  out.push(`Sandbox: ${code(meta ? rel(path.resolve(meta.workspace)) : 'not recorded')} · baseline commit ${code(meta ? meta.baseline_commit.slice(0, 10) : '—')}`);
  out.push('');
  out.push('</details>');
  out.push('');
  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Index
// ---------------------------------------------------------------------------

// docs/agent_output/04-remediation/README.md is shared with 04a-fix-strategist and 04b-fixer.
// Their renderers keep everything BEFORE their own marker and replace everything after it, so the
// migration block is self-delimited and always written *above* that marker: their rewrite then
// carries it through untouched, and this one replaces only what is between its own two markers.
const BLOCK_START = '<!-- AUTO-GENERATED MIGRATIONS — regenerated by 04_fix-generator (version migration), do not hand-edit between these markers -->';
const BLOCK_END = '<!-- END AUTO-GENERATED MIGRATIONS -->';
const FIX_INDEX_MARKER = '<!-- AUTO-GENERATED TABLE — regenerated by 04_fix-generator (plan + fix), do not hand-edit below this line -->';

function defaultReadmeContract() {
  return `# Remediation

Everything the **Fix Generator** agent (\`04_fix-generator\`) produces lives here.

`;
}

function migrationBlock() {
  const rows = (fs.existsSync(OUT_DIR) ? fs.readdirSync(OUT_DIR) : [])
    .map((f) => /^migration_(.+)\.md$/.exec(f))
    .filter(Boolean)
    .map((m) => {
      const slug = m[1];
      const text = fs.readFileSync(path.join(OUT_DIR, `migration_${slug}.md`), 'utf8');
      const cell = (label, fallback) => (new RegExp(`\\|\\s*\\*\\*${label}\\*\\*\\s*\\|\\s*([^|]+)\\|`).exec(text) || [, fallback])[1].trim();
      return {
        slug,
        title: (/^# Migration Report — (.+)$/m.exec(text) || [, slug])[1],
        result: cell('Result', 'unknown'),
        rounds: cell('Build rounds', '—'),
        files: cell('Files changed', '—'),
        behaviour: cell('Runtime behaviour', '_not probed_'),
      };
    })
    .sort((a, b) => a.slug.localeCompare(b.slug));

  const block = [BLOCK_START, '', '## Version migrations', ''];
  block.push('Framework-generation and language-level upgrades, written by the');
  block.push('[`04d-version-migration`](../../.github/skills/04d-version-migration/) skill. A migration runs in a');
  block.push('sandbox copy of the project under `.github/.pipeline-context/version-migration/`; the project');
  block.push('directory is never edited by the skill, and applying the patch is a separate, explicit step.');
  block.push('');
  if (!rows.length) {
    block.push('_No migrations rendered yet._', '');
  } else {
    block.push('| Migration | Result | Rounds | Files changed | Runtime behaviour | Report | Patch |', '|---|---|---|---|---|---|---|');
    for (const r of rows) {
      block.push(`| ${r.title} | ${r.result} | ${r.rounds} | ${r.files} | ${r.behaviour} | [report](./migration_${r.slug}.md) | [patch](./migration_${r.slug}.diff) |`);
    }
    block.push('');
  }
  block.push(BLOCK_END);
  return block.join('\n');
}

function rewriteIndex() {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const existing = fs.existsSync(OUT_README) ? fs.readFileSync(OUT_README, 'utf8') : defaultReadmeContract();
  const block = migrationBlock();
  let next;

  const start = existing.indexOf(BLOCK_START);
  const end = existing.indexOf(BLOCK_END);
  if (start !== -1 && end !== -1 && end > start) {
    next = existing.slice(0, start) + block + existing.slice(end + BLOCK_END.length);
  } else {
    const fixMarker = existing.indexOf(FIX_INDEX_MARKER);
    next = fixMarker !== -1
      ? `${existing.slice(0, fixMarker).trimEnd()}\n\n${block}\n\n${existing.slice(fixMarker)}`
      : `${existing.trimEnd()}\n\n${block}\n`;
  }
  fs.writeFileSync(OUT_README, next);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function renderOne(slug) {
  const paths = sessionPaths(slug);
  const baseline = readJson(paths.baseline);
  const migration = readJson(paths.migration);
  const meta = readJson(paths.workspaceMeta);
  if (!baseline) throw new Error(`No baseline.json for "${slug}" — run detect-baseline.js.`);
  if (!migration) throw new Error(`No migration.json for "${slug}" — write it per templates/migration.schema.json before rendering.`);
  const rounds = listRounds(slug).map((round) => {
    if (round.errors_truncated) return round;
    const errors = round.errors
      .filter((e) => !isLogNoise(e.message))
      .map((e) => ({ ...e, category: classifyMessage(`${e.message} ${(e.detail || []).join(' ')}`) }));
    return { ...round, errors, error_summary: summariseErrors(errors) };
  });
  if (!rounds.length) throw new Error(`No build rounds recorded for "${slug}" — run run-migration-build.js at least once.`);
  const plan = readJson(paths.plan);
  validate(migration, rounds, plan);

  const runtime = {
    baseline: readJson(path.join(paths.runtimeDir, 'baseline.json')),
    final: readJson(path.join(paths.runtimeDir, 'final.json')),
  };
  const finalRound = rounds[rounds.length - 1];
  const patch = exportDiff(paths, meta);
  const patchFiles = splitPatchByFile(patch.text);
  const transformations = listTransformations(slug);

  fs.mkdirSync(OUT_DIR, { recursive: true });
  const report = renderReport({
    slug, baseline, migration, rounds, runtime, finalRound, patchStat: patch.stat, patchFiles, meta, plan, transformations,
  });
  fs.writeFileSync(paths.reportMd, report);
  if (patch.text !== null) fs.writeFileSync(paths.reportDiff, patch.text);
  if (fs.existsSync(paths.state)) recordState(slug, 'RENDERED', 'render-migration-report.js', `final round ${finalRound.round} ${finalRound.outcome}`);

  return {
    slug,
    report: rel(paths.reportMd),
    diff: patch.text !== null ? rel(paths.reportDiff) : null,
    outcome: finalRound.outcome,
    rounds: rounds.length,
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  const slugs = args.all
    ? listSessions().filter((s) => fs.existsSync(sessionPaths(s).migration))
    : (args.slug ? [args.slug] : []);
  if (!slugs.length) {
    // Still refresh the index: a report removed by hand should disappear from it.
    if (args.all) rewriteIndex();
    console.error(args.all
      ? 'No session has a migration.json yet — write the judgement file first.'
      : 'Pass --slug <slug> or --all.');
    process.exitCode = 1;
    return;
  }

  let failures = 0;
  for (const slug of slugs) {
    try {
      const result = renderOne(slug);
      const meta = outcomeOf(result.outcome);
      console.log(`${meta.emoji} ${slug} — ${meta.label} after ${result.rounds} round(s)`);
      console.log(`   report ${result.report}`);
      if (result.diff) console.log(`   patch  ${result.diff}`);
    } catch (error) {
      failures += 1;
      console.error(`✗ ${slug} — ${error.message}`);
    }
    // finally: the per-run summary (and, for an Agent 04 issue, the standard fix handoff) from the evidence just rendered
    finalizeRun(slug, 'render-migration-report.js');
  }
  rewriteIndex();
  console.log(`\nIndex: ${rel(OUT_README)}`);
  if (failures) process.exitCode = 1;
}

if (require.main === module) main();

module.exports = { renderOne, validate, compareProbes, splitPatchByFile, transformationsSummary };
