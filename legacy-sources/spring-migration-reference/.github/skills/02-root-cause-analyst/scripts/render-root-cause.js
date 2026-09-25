#!/usr/bin/env node
/**
 * Root Cause Analyst — Report Renderer
 *
 * Combines two things into the final deliverable:
 *   - .github/.pipeline-context/rca/<issue_id>.evidence.json   — facts, from collect-evidence.js
 *   - .github/.pipeline-context/rca/<issue_id>.analysis.json   — reasoning, written by the agent
 *
 * Output: docs/agent_output/02-root-cause/root_cause_<issue_id>.md
 *
 * Keeping the two apart is deliberate: every factual section (locations, call
 * chains, affected area, graph queries) is rendered straight from the evidence
 * bundle and cannot drift, while the analytical sections come from the agent
 * and are clearly attributed as judgement.
 *
 * Usage:
 *   node scripts/render-root-cause.js --all              # every issue that has both files
 *   node scripts/render-root-cause.js --issue ISSUE-001
 *   node scripts/render-root-cause.js --issue ISSUE-001 --analysis path/to/analysis.json
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, OUT_DIR,
  rel, listIssues, evidencePathFor, analysisPathFor, reportPathFor,
} = require('./lib/issues');
const {
  expectedVsActualDiagram, causalChainDiagram, defectMapDiagram,
} = require('./lib/diagrams');

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--issue' || arg === '-i') args.issue = argv[++i];
    else if (arg === '--all') args.all = true;
    else if (arg === '--analysis' || arg === '-a') args.analysis = argv[++i];
    else if (arg === '--evidence' || arg === '-e') args.evidence = argv[++i];
    else if (arg === '--out' || arg === '-o') args.out = argv[++i];
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Root Cause Analyst — Report Renderer

  node scripts/render-root-cause.js --all
  node scripts/render-root-cause.js --issue <ISSUE-ID> [options]

Options:
  --all            Render every issue in docs/agent_output/00-issues/ that has evidence + analysis
  --issue, -i      Issue id, e.g. ISSUE-001
  --analysis, -a   Analysis JSON path (default .github/.pipeline-context/rca/<id>.analysis.json)
  --evidence, -e   Evidence JSON path (default .github/.pipeline-context/rca/<id>.evidence.json)
  --out, -o        Output path (default docs/agent_output/02-root-cause/root_cause_<id>.md)
  --help, -h       Show this message

Exactly one of --all or --issue is required.`);
}

// ---------------------------------------------------------------------------
// Validation — fail loudly rather than emitting a half-empty report
// ---------------------------------------------------------------------------

const REQUIRED = [
  ['summary', (a) => typeof a.summary === 'string' && a.summary.trim().length > 0],
  ['root_cause.statement', (a) => a.root_cause && a.root_cause.statement],
  ['root_cause.explanation', (a) => a.root_cause && a.root_cause.explanation],
  ['causal_chain (non-empty array)', (a) => Array.isArray(a.causal_chain) && a.causal_chain.length > 0],
  ['impact.narrative', (a) => a.impact && a.impact.narrative],
  ['recommended_fix.approach', (a) => a.recommended_fix && a.recommended_fix.approach],
  ['verification (non-empty array)', (a) => Array.isArray(a.verification) && a.verification.length > 0],
];

function validateAnalysis(analysis) {
  const missing = REQUIRED.filter(([, check]) => !check(analysis)).map(([key]) => key);
  if (missing.length) {
    throw new Error(`Analysis JSON is missing required field(s):\n  - ${missing.join('\n  - ')}\nSee templates/analysis.schema.json for the expected shape.`);
  }
}

// ---------------------------------------------------------------------------
// Rendering helpers
// ---------------------------------------------------------------------------

function bullets(items, emptyText = '_none recorded_') {
  if (!items || !items.length) return [emptyText];
  return items.map((i) => `- ${typeof i === 'string' ? i : JSON.stringify(i)}`);
}

function chainStep(step, index) {
  if (typeof step === 'string') return { label: `Step ${index + 1}`, detail: step };
  return { label: step.step || `Step ${index + 1}`, detail: step.detail || '' };
}

// Depth-independent: derived from where the report is actually written, so moving
// the output folder can never silently break every link in every rendered report.
const UP_TO_ROOT = path.relative(OUT_DIR, REPO_ROOT).replace(/\\/g, '/');

function relativeFromOutput(repoRelativePath) {
  // docs/agent_output/02-root-cause/x.md -> ../../../<path>
  return `${UP_TO_ROOT}/${repoRelativePath}`.replace(/\\/g, '/');
}

function docLink(repoRelativePath, label) {
  return `[${label || repoRelativePath}](${relativeFromOutput(repoRelativePath)})`;
}

// ---------------------------------------------------------------------------
// Report
// ---------------------------------------------------------------------------

/**
 * The report is written to be read top-down by someone deciding what to do about the
 * defect: plain language and diagrams first, source and graph detail folded away in
 * <details> for whoever is going to fix it.
 */
function render(evidence, analysis) {
  // `evidence.affectedArea` is deliberately not rendered here. The agent uses it to keep
  // the impact narrative honest, but this report states what the defect means rather than
  // mapping how far it spreads.
  const { issue, focus, graph, sources } = evidence;
  const resolvedFocus = focus.filter((f) => f.resolved);
  const steps = analysis.causal_chain.map(chainStep);
  const out = [];

  // Sections are numbered as they are emitted, so an omitted optional section never
  // leaves a gap in the sequence.
  let sectionNumber = 0;
  const section = (heading) => {
    sectionNumber += 1;
    out.push(`## ${sectionNumber}. ${heading}`);
    out.push('');
  };

  // --- Header ---------------------------------------------------------------
  out.push(`# Root Cause Analysis — ${issue.id}`);
  out.push('');
  out.push(`## ${issue.title}`);
  out.push('');
  out.push(`> ${(analysis.plain_summary || analysis.summary).trim()}`);
  out.push('');
  out.push(`_Generated by the Root Cause Analyst agent on ${new Date().toISOString().slice(0, 10)}. Evidence collected ${evidence.generatedAt}._`);
  out.push('');

  // --- At a glance ----------------------------------------------------------
  const primaryLocation = analysis.root_cause.defect_location
    || (resolvedFocus[0] ? `${resolvedFocus[0].file}:${resolvedFocus[0].startLine}` : 'not resolved');
  out.push('## At a glance');
  out.push('');
  out.push('| | |');
  out.push('|---|---|');
  out.push(`| **What breaks** | ${issue.entryPoints.map((e) => `\`${e}\``).join(', ') || issue.title} |`);
  out.push(`| **Root cause** | ${analysis.root_cause.statement.trim()} |`);
  out.push(`| **Where** | \`${primaryLocation}\` |`);
  out.push(`| **Service** | ${issue.services.map((s) => `\`${s}\``).join(', ') || 'n/a'} |`);
  out.push(`| **Severity** | ${issue.severity || 'n/a'} (${issue.type || 'defect'}) |`);
  out.push(`| **Confidence** | ${analysis.confidence || 'not stated'} |`);
  out.push('');
  out.push(`Issue: ${docLink(issue.file, issue.file)} · Reported ${issue.reportedOn || 'n/a'}${issue.reportedBy ? ` by ${issue.reportedBy}` : ''} · Status ${issue.status || 'n/a'}`);
  out.push('');

  // The engineer-facing summary sits one click away, so the report opens plainly for
  // everyone else. The quote under the title is the plain-language version.
  out.push('<details><summary>Technical summary</summary>');
  out.push('');
  out.push(analysis.summary.trim());
  out.push('');
  out.push('</details>');
  out.push('');

  // --- 1. What was reported -------------------------------------------------
  section('What was reported');
  const symptomSection = /##\s+Observed Behavior\s*\r?\n([\s\S]*?)(?=\r?\n##\s)/.exec(issue.body);
  if (symptomSection) {
    out.push(symptomSection[1].trim());
  } else {
    out.push(`> ${issue.title}`);
  }
  out.push('');
  out.push(`Source: ${docLink(issue.file, issue.file)}`);
  out.push('');

  // --- 2. Expected vs actual ------------------------------------------------
  const comparison = expectedVsActualDiagram(analysis.expected_flow, steps.map((s) => s.label));
  if (comparison) {
    section('What should happen — and what happens instead');
    out.push(comparison);
    out.push('');
  }

  // --- 3. How it fails ------------------------------------------------------
  section('How it fails, step by step');
  steps.forEach((s, i) => out.push(`${i + 1}. **${s.label}** — ${s.detail}`));
  out.push('');
  out.push(causalChainDiagram(steps));
  out.push('');

  // --- 4. The defect --------------------------------------------------------
  section('Where the defect is');
  out.push(`> **${analysis.root_cause.statement.trim()}**`);
  out.push('');
  if (analysis.root_cause.defect_location) {
    out.push(`**Location:** \`${analysis.root_cause.defect_location}\``);
    out.push('');
  }
  const defectMap = defectMapDiagram(resolvedFocus);
  if (defectMap) {
    out.push(defectMap);
    out.push('');
  }
  out.push(analysis.root_cause.explanation.trim());
  out.push('');

  if (resolvedFocus.length) {
    out.push('**The code involved**');
    out.push('');
    out.push('| Method | Service | File | Calls | Called by |');
    out.push('|---|---|---|---|---|');
    for (const f of resolvedFocus) {
      const calls = f.directCallees.length
        ? f.directCallees.map((c) => `\`${c.label}\`${c.id === f.id ? ' ⚠️ itself' : ''}`).join(', ')
        : '_none_';
      const calledBy = f.directCallers.length
        ? f.directCallers.map((c) => `\`${c.label}\`${c.id === f.id ? ' ⚠️ itself' : ''}`).join(', ')
        : '_entry point_';
      out.push(`| \`${f.label}\` | \`${f.module}\` | [${f.file.split('/').pop()}:${f.startLine}-${f.endLine}](${relativeFromOutput(f.file)}#L${f.startLine}-L${f.endLine}) | ${calls} | ${calledBy} |`);
    }
    out.push('');

    // Source and call chains are for whoever fixes this — folded away so the report
    // reads cleanly for everyone else.
    out.push('<details><summary>Show the source of each method</summary>');
    out.push('');
    for (const f of resolvedFocus) {
      out.push(`**\`${f.label}\`** — \`${f.file}\` lines ${f.startLine}-${f.endLine}`);
      out.push('');
      out.push('```java');
      out.push(f.source);
      out.push('```');
      out.push('');
      if (f.upstreamChains.length) {
        out.push('Reaching paths:');
        out.push('');
        f.upstreamChains.slice(0, 8).forEach((chain) => out.push(`- ${chain.map((c) => `\`${c}\``).join(' → ')}`));
        out.push('');
      }
    }
    out.push('</details>');
    out.push('');
  } else {
    out.push('_No issue symbol could be resolved against the code scan._');
    out.push('');
  }

  // --- 5. Impact ------------------------------------------------------------
  // Scope note: this section says what the defect means, in prose. Mapping how far it
  // spreads — services, endpoints, jobs, who is affected — is out of scope for this report.
  section('What it means');
  out.push(analysis.impact.narrative.trim());
  out.push('');
  if (analysis.impact.severity_rationale) {
    out.push(`**Why ${issue.severity || 'this'} severity:** ${analysis.impact.severity_rationale.trim()}`);
    out.push('');
  }

  // --- 6. Contributing factors ----------------------------------------------
  section('Why it happened');
  bullets(analysis.contributing_factors, '_None identified._').forEach((b) => out.push(b));
  out.push('');

  // --- 7. Fix ---------------------------------------------------------------
  section('How to fix it');
  out.push(analysis.recommended_fix.approach.trim());
  out.push('');
  const changes = analysis.recommended_fix.changes || [];
  if (changes.length) {
    out.push('| File | Change |');
    out.push('|---|---|');
    for (const c of changes) {
      out.push(`| ${c.file ? docLink(c.file, c.file.split('/').pop()) : 'n/a'} | ${(c.change || '').replace(/\|/g, '\\|')} |`);
    }
    out.push('');
  }
  if (analysis.recommended_fix.code_sketch) {
    out.push('<details><summary>Sketch of the change</summary>');
    out.push('');
    out.push('```java');
    out.push(analysis.recommended_fix.code_sketch.trim());
    out.push('```');
    out.push('');
    out.push('</details>');
    out.push('');
  }

  // --- 8. Verification ------------------------------------------------------
  section('How to check the fix worked');
  analysis.verification.forEach((v, i) => out.push(`${i + 1}. ${v}`));
  out.push('');

  // --- 9. Prevention --------------------------------------------------------
  section('How to stop it happening again');
  bullets(analysis.prevention, '_None recorded._').forEach((b) => out.push(b));
  out.push('');

  if (analysis.open_questions && analysis.open_questions.length) {
    section('Open questions');
    bullets(analysis.open_questions).forEach((b) => out.push(b));
    out.push('');
  }

  // --- Appendix -------------------------------------------------------------
  out.push('## Appendix — how this was determined');
  out.push('');
  out.push('| Input | Detail |');
  out.push('|---|---|');
  out.push(`| Issue report | ${docLink(issue.file, issue.file)} |`);
  out.push(`| Architecture document | ${sources.architecture ? docLink('docs/agent_output/01-architecture/architecture.md', 'docs/agent_output/01-architecture/architecture.md') : 'not available'} |`);
  out.push(`| Function reference | ${sources.functionReference ? docLink('docs/agent_output/01-architecture/function-reference.md', 'docs/agent_output/01-architecture/function-reference.md') : 'not available'} |`);
  out.push(`| Code scan | \`${sources.artifacts}\` (generated ${sources.artifactsGeneratedAt}) |`);
  out.push(`| Knowledge graph | ${graph.live ? `Neo4j, traversal depth ${graph.depth}` : `not used — ${graph.reason}`} |`);
  out.push('');
  if (evidence.architectureContext && evidence.architectureContext.observations.length) {
    out.push('<details><summary>Architecture observations considered</summary>');
    out.push('');
    evidence.architectureContext.observations.forEach((o) => out.push(o));
    out.push('');
    out.push('</details>');
    out.push('');
  }
  if (!graph.live) {
    out.push(`_No Cypher was run: ${graph.reason}_ Call-graph facts came from the code scan, using the same resolution rules Graph Forge applies when it builds the graph.`);
    out.push('');
  } else {
    const unique = [];
    for (const q of graph.queries || []) if (!unique.some((u) => u.cypher === q.cypher)) unique.push(q);
    out.push('<details><summary>Graph queries executed</summary>');
    out.push('');
    for (const q of unique) {
      out.push(`**${q.name}**`);
      out.push('');
      out.push('```cypher');
      out.push(q.cypher);
      out.push('```');
      out.push('');
    }
    out.push('</details>');
    out.push('');
  }

  out.push('---');
  out.push('');
  out.push('The reported symptom, the code table, the source and the call paths are rendered verbatim from the issue, the code scan and the knowledge graph. The plain-language summary, the causal chain, the root cause, what it means, why it happened, the fix, the verification steps and the prevention measures are the judgement of the Root Cause Analyst agent.');
  out.push('');

  return out.join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

/** Render one issue. Paths may be overridden for a single-issue run. */
function renderOne({ issueId, evidencePath, analysisPath, outPath }) {
  if (!fs.existsSync(evidencePath)) {
    throw new Error(`No evidence bundle at ${rel(evidencePath)}. Run collect-evidence.js --issue ${issueId} first.`);
  }
  const evidence = JSON.parse(fs.readFileSync(evidencePath, 'utf8'));
  const resolvedId = evidence.issue.id;

  const analysisFile = analysisPath || analysisPathFor(resolvedId);
  if (!fs.existsSync(analysisFile)) {
    throw new Error(`No analysis file at ${rel(analysisFile)}.\nRead ${rel(evidencePathFor(resolvedId)).replace('.evidence.json', '.evidence.md')} and write your analysis there following templates/analysis.schema.json, then re-run.`);
  }
  let analysis;
  try {
    analysis = JSON.parse(fs.readFileSync(analysisFile, 'utf8'));
  } catch (err) {
    throw new Error(`Analysis file ${rel(analysisFile)} is not valid JSON: ${err.message}`);
  }
  validateAnalysis(analysis);

  if (analysis.issue_id && analysis.issue_id !== resolvedId) {
    throw new Error(`Analysis is for ${analysis.issue_id} but the evidence bundle is for ${resolvedId}.`);
  }

  const target = outPath || reportPathFor(resolvedId);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, render(evidence, analysis));

  return {
    id: resolvedId,
    severity: evidence.issue.severity || 'n/a',
    confidence: analysis.confidence || 'not stated',
    report: rel(target),
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  // --- Batch: one report per issue in the register --------------------------
  if (args.all) {
    const issues = listIssues();
    if (!issues.length) throw new Error(`No issues found in docs/agent_output/00-issues/. Nothing to render.`);

    const written = [];
    const skipped = [];
    for (const issue of issues) {
      try {
        const result = renderOne({ issueId: issue.id, evidencePath: evidencePathFor(issue.id) });
        written.push(result);
        console.log(`${result.id.padEnd(10)} ${result.severity.padEnd(9)} confidence ${result.confidence.padEnd(12)} -> ${result.report}`);
      } catch (err) {
        skipped.push({ id: issue.id, reason: err.message.split('\n')[0] });
        console.error(`${issue.id.padEnd(10)} SKIPPED  ${err.message.split('\n')[0]}`);
      }
    }

    console.log(`\nWrote ${written.length}/${issues.length} report(s) to ${rel(OUT_DIR)}.`);
    if (skipped.length) {
      console.log(`Still pending: ${skipped.map((s) => s.id).join(', ')} — collect evidence and write the analysis JSON for each, then re-run.`);
      process.exitCode = 1;
    }
    return;
  }

  // --- Single issue ---------------------------------------------------------
  if (!args.issue && !args.evidence) {
    throw new Error('Missing --issue. Pass an issue id (--issue ISSUE-001) or --all to render every issue in the register.');
  }
  const result = renderOne({
    issueId: args.issue,
    evidencePath: args.evidence ? path.resolve(REPO_ROOT, args.evidence) : evidencePathFor(args.issue),
    analysisPath: args.analysis ? path.resolve(REPO_ROOT, args.analysis) : null,
    outPath: args.out ? path.resolve(REPO_ROOT, args.out) : null,
  });

  console.log(`Root Cause Analyst — wrote ${result.report}`);
  console.log(`  issue      : ${result.id} (${result.severity})`);
  console.log(`  confidence : ${result.confidence}`);
}

try {
  main();
} catch (err) {
  console.error('Rendering failed:', err.message);
  process.exit(1);
}
