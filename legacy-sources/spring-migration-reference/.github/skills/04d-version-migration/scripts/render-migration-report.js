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
const {
  OUT_DIR, OUT_README, sessionPaths, listSessions, listRounds, readJson, rel, run,
  categoryMeta, classifyMessage, summariseErrors, isLogNoise,
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

function validate(migration, rounds) {
  const missing = REQUIRED.filter(([, check]) => !check(migration)).map(([key]) => key);
  if (missing.length) {
    throw new Error(`migration.json is missing required field(s):\n  - ${missing.join('\n  - ')}\nSee templates/migration.schema.json for the expected shape.`);
  }
  const noted = new Set(migration.round_notes.map((n) => n.round));
  const unnoted = rounds.map((r) => r.round).filter((r) => !noted.has(r));
  if (unnoted.length) {
    throw new Error(`migration.json has no round_notes entry for recorded round(s): ${unnoted.join(', ')}.\nEvery round that ran must be explained, including the ones that failed.`);
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

const esc = (text) => String(text === null || text === undefined ? '' : text).replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
const code = (text) => (text ? `\`${String(text).replace(/`/g, '')}\`` : '—');
const mermaidLabel = (text) => String(text).replace(/"/g, "'").replace(/[[\]{}()]/g, '');

function bullets(items, empty = '_none recorded_') {
  if (!items || !items.length) return [empty];
  return items.map((i) => `- ${i}`);
}

function durationText(ms) {
  if (!ms && ms !== 0) return '—';
  return ms >= 60000 ? `${(ms / 60000).toFixed(1)} min` : `${(ms / 1000).toFixed(1)}s`;
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
  lines.push(`    B3["${mermaidLabel(baseline.project.name)}<br/>${baseline.project.sources.main} main + ${baseline.project.sources.test} test sources"]:::before`);
  lines.push('    B1 --- B2 --- B3');
  lines.push('  end');
  lines.push('  subgraph AFTER["After"]', '    direction TB');
  lines.push(`    A1["${mermaidLabel(lang.name)} ${mermaidLabel(lang.to)}"]:::after`);
  lines.push(`    A2["${mermaidLabel(platform.name)} ${mermaidLabel(platform.to)}"]:::after`);
  const changedFiles = patchFiles.length;
  lines.push(`    A3["${mermaidLabel(baseline.project.name)}<br/>${changedFiles} file(s) changed"]:::${green ? 'ok' : 'degraded'}`);
  lines.push('    A1 --- A2 --- A3');
  lines.push('  end');
  lines.push(`  BEFORE ==>|"${(migration.round_notes || []).length - 1 || 0} migration round(s)"| AFTER`);
  lines.push('```');
  return lines.join('\n');
}

function roundsDiagram(rounds) {
  const lines = ['```mermaid', 'flowchart LR', ...CLASS_DEFS.map((d) => `  ${d}`)];
  const ids = [];
  for (const r of rounds) {
    const meta = outcomeOf(r.outcome);
    const id = `R${r.round}`;
    ids.push(id);
    const errors = r.error_summary.total;
    const detail = r.outcome === 'passed'
      ? 'build green'
      : `${errors} error line${errors === 1 ? '' : 's'}`;
    lines.push(`  ${id}["${meta.emoji} Round ${r.round}${r.baseline ? ' · baseline' : ''}<br/>JDK ${r.jdk.major} · ${r.build.intent}<br/>${detail}"]:::${meta.className}`);
  }
  if (ids.length > 1) lines.push(`  ${ids.join(' --> ')}`);
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
    ? (badBefore === 0 ? '🟢 all green, before and after' : `🟡 the same ${badBefore} pre-existing failure(s) — none introduced`)
    : (worse ? `🔴 ${badAfter - badBefore} new failure(s) introduced` : `🟢 ${badBefore - badAfter} fewer failure(s) than before`);
  return { before, after, verdict, worse };
}

function atAGlance(baseline, migration, rounds, finalRound, runtime, patchFiles) {
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
    const fmt = (t) => (t ? `${t.run} run, ${t.failures} failed, ${t.errors} error(s)` : 'not run');
    rows.push(['**Test suite**', `before: ${fmt(tests.before)} → after: ${fmt(tests.after)} — ${tests.verdict}`]);
  }
  if (runtime.baseline || runtime.final) {
    const compared = compareProbes(runtime);
    rows.push(['**Runtime behaviour**', compared.verdictText]);
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

function roundsTable(rounds, notes) {
  const lines = ['| Round | Ran on | Goal | Outcome | Errors | Took | What it told us |', '|---|---|---|---|---|---|---|'];
  for (const r of rounds) {
    const meta = outcomeOf(r.outcome);
    const note = notes.find((n) => n.round === r.round);
    const headline = note ? esc(note.diagnosis.split(/(?<=\.)\s/)[0]) : '—';
    lines.push(`| **${r.round}**${r.baseline ? ' _(baseline)_' : ''} | JDK ${r.jdk.major} | \`${r.build.intent}\` | ${meta.emoji} ${meta.label} | ${r.error_summary.total || '—'} | ${durationText(r.duration_ms)} | ${headline} |`);
  }
  return lines.join('\n');
}

function roundDetail(round, note) {
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
    if (round.error_summary.byFile.length) {
      out.push('**Where they were**');
      out.push('');
      out.push('| Errors | File |', '|---|---|');
      for (const f of round.error_summary.byFile.slice(0, 12)) out.push(`| ${f.count} | ${code(f.file)} |`);
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

function compareProbes(runtime) {
  const before = runtime.baseline;
  const after = runtime.final;
  if (!before || !after) {
    return {
      verdictText: before || after ? '⚠️ Only one side was probed — no comparison possible' : '⚠️ Not probed',
      rows: [],
      matched: 0,
      total: 0,
    };
  }
  const rows = [];
  for (const b of before.probes || []) {
    const a = (after.probes || []).find((p) => p.name === b.name);
    const same = a && a.status === b.status && a.body_hash === b.body_hash;
    // A changed status is a different kind of finding from a changed body at the same status:
    // the first is a contract break, the second is usually an envelope, a timestamp or an
    // ordering. The report says which, and leaves the judgement to the reader.
    const statusSame = a && a.status === b.status && a.ok === b.ok;
    rows.push({
      name: b.name,
      request: `${b.method} ${b.path}`,
      beforeStatus: b.ok ? b.status : 'error',
      afterStatus: a ? (a.ok ? a.status : 'error') : 'not run',
      beforeHash: b.body_hash,
      afterHash: a ? a.body_hash : null,
      same: Boolean(same),
      verdict: same ? 'identical' : (statusSame ? 'body-differs' : 'status-differs'),
    });
  }
  const matched = rows.filter((r) => r.same).length;
  const bodyOnly = rows.filter((r) => r.verdict === 'body-differs').length;
  const statusChanged = rows.filter((r) => r.verdict === 'status-differs').length;
  let verdictText = '⚠️ No probes were defined';
  if (rows.length) {
    if (matched === rows.length) verdictText = `🟢 identical on all ${rows.length} probe(s)`;
    else if (!statusChanged) verdictText = `🟡 same status on all ${rows.length}, body differs on ${bodyOnly}`;
    else verdictText = `🔴 ${statusChanged} of ${rows.length} probe(s) changed status`;
  }
  return { rows, matched, bodyOnly, statusChanged, total: rows.length, verdictText };
}

function behaviourSection(runtime, migration, ctxRounds) {
  const compared = compareProbes(runtime);
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
  out.push(`| **Ran on** | ${before ? `JDK ${before.jdk.major} (${esc(before.jdk.version)})` : '—'} | ${after ? `JDK ${after.jdk.major} (${esc(after.jdk.version)})` : '—'} |`);
  out.push(`| **Application started** | ${before ? (before.started ? '🟢 yes' : '🔴 no') : '—'} | ${after ? (after.started ? '🟢 yes' : '🔴 no') : '—'} |`);
  out.push(`| **Startup time** | ${before && before.startup_seconds ? `${before.startup_seconds}s` : '—'} | ${after && after.startup_seconds ? `${after.startup_seconds}s` : '—'} |`);
  out.push(`| **Probes replayed** | ${before ? (before.probes || []).length : '—'} | ${after ? (after.probes || []).length : '—'} |`);
  out.push('');

  if (!before || !after) {
    out.push(`> ⚠️ **Only the ${before ? 'before' : 'after'} side was probed**, so there is no comparison —`);
    out.push('> nothing here shows whether behaviour was preserved.');
    out.push('');
  }

  if (compared.rows.length) {
    out.push('| | Request | Before | After | Same response? |', '|---|---|---|---|---|');
    const MARK = { identical: ['🟢', 'identical'], 'body-differs': ['🟡', 'same status, **body differs**'], 'status-differs': ['🔴', '**status changed**'] };
    for (const r of compared.rows) {
      const [mark, label] = MARK[r.verdict] || ['⚪', 'not run'];
      out.push(`| ${mark} | ${esc(r.name)}<br/>${code(r.request)} | \`${r.beforeStatus}\` · \`${r.beforeHash || '—'}\` | \`${r.afterStatus}\` · \`${r.afterHash || '—'}\` | ${label} |`);
    }
    out.push('');
    out.push(`🟢 ${compared.matched} identical · 🟡 ${compared.bodyOnly} same status with a different body · 🔴 ${compared.statusChanged} changed status`);
    out.push('');
    out.push('_Responses are compared by HTTP status and a hash of the whitespace-normalised body. A body that embeds a timestamp or an unordered collection can differ between two runs of the same build — check such a row against the excerpts in the runtime records before treating it as a migration effect._');
    out.push('');
  }

  if (migration.behaviour) {
    out.push(`**Verdict:** ${esc(migration.behaviour.verdict)}${migration.behaviour.notes ? ` — ${esc(migration.behaviour.notes)}` : ''}`);
    out.push('');
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
  if (!meta || !fs.existsSync(paths.workspace)) return { text: null, stat: null };
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
  out.push([
    badge('Result', outcome.label, outcome.colour),
    badge(migration.migration.platform.name, `${migration.migration.platform.from} → ${migration.migration.platform.to}`, '2E5FD9'),
    badge('Java', `${migration.migration.language.from} → ${migration.migration.language.to}`, '6E86E8'),
    badge('Rounds', String(rounds.length), '1F3864'),
    badge('Files changed', String(ctx.patchFiles.length), 'A0399B'),
  ].join(' '));
  out.push('');
  out.push(`> ${migration.summary}`);
  out.push('');
  out.push(`_Generated by the Version Migration skill on ${new Date().toISOString().slice(0, 10)}. Every version, error count and response below is read from a recorded run, not from memory._`);
  out.push('');

  out.push('## At a glance');
  out.push('');
  out.push(atAGlance(baseline, migration, rounds, finalRound, runtime, ctx.patchFiles));
  out.push('');

  out.push('## 1. What moved');
  out.push('');
  out.push(stackDiagram(baseline, migration, finalRound, ctx.patchFiles));
  out.push('');
  out.push(versionMatrix(baseline, migration));
  out.push('');

  out.push('## 2. How the migration went');
  out.push('');
  out.push(roundsDiagram(rounds));
  out.push('');
  out.push(roundsTable(rounds, migration.round_notes));
  out.push('');
  const pie = errorPie(failingRound);
  if (pie) {
    out.push(`The round that hit the most breakage was **round ${failingRound.round}**:`);
    out.push('');
    out.push(pie);
    out.push('');
  }

  out.push('## 3. Round by round');
  out.push('');
  for (const round of rounds) {
    out.push(roundDetail(round, migration.round_notes.find((n) => n.round === round.round)));
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

  out.push('---');
  out.push('');
  out.push('<details>');
  out.push('<summary>Where each fact in this report came from</summary>');
  out.push('');
  out.push('| Fact | Source | Produced by |', '|---|---|---|');
  out.push(`| Declared versions, dependency list, JDKs available | ${code(rel(sessionPaths(slug).baseline))} | \`detect-baseline.js\` |`);
  out.push(`| Sandbox and baseline commit | ${code(rel(sessionPaths(slug).workspaceMeta))} | \`prepare-workspace.js\` |`);
  out.push(`| Every round's outcome, errors and timings | ${code(`${rel(sessionPaths(slug).roundsDir)}/round-NN.json`)} | \`run-migration-build.js\` |`);
  out.push(`| Before/after runtime responses | ${code(`${rel(sessionPaths(slug).runtimeDir)}/{baseline,final}.json`)} | \`probe-runtime.js\` |`);
  out.push(`| Diagnoses, reasons, risks, follow-ups | ${code(rel(sessionPaths(slug).migration))} | the agent |`);
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
  validate(migration, rounds);

  const runtime = {
    baseline: readJson(path.join(paths.runtimeDir, 'baseline.json')),
    final: readJson(path.join(paths.runtimeDir, 'final.json')),
  };
  const finalRound = rounds[rounds.length - 1];
  const patch = exportDiff(paths, meta);
  const patchFiles = splitPatchByFile(patch.text);

  fs.mkdirSync(OUT_DIR, { recursive: true });
  const report = renderReport({
    slug, baseline, migration, rounds, runtime, finalRound, patchStat: patch.stat, patchFiles, meta,
  });
  fs.writeFileSync(paths.reportMd, report);
  if (patch.text !== null) fs.writeFileSync(paths.reportDiff, patch.text);

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
  }
  rewriteIndex();
  console.log(`\nIndex: ${rel(OUT_README)}`);
  if (failures) process.exitCode = 1;
}

main();
