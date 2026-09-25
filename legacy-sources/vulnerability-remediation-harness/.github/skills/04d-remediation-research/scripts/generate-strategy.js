#!/usr/bin/env node
/**
 * 04d — Strategy assembler (deterministic).
 *
 * Reads the deterministic vulnerability understanding (Stage B) and the agent-authored analysis
 * (Stages C-J: root cause, threat, objective, candidates, evaluation, recommendation, validation),
 * then:
 *   - if research_status = "insufficient_evidence" -> prints a SAFE STOP and produces no strategy;
 *   - otherwise assembles a base-contract-compatible research strategy.json (confidence: Low,
 *     catalog_reference.title: null, remediation_source: 04d, derived_pattern.type: novel-research)
 *     plus a KB promotion candidate, and prints the full research output for review.
 *
 * The script invents nothing: it only validates and re-shapes what the analysis provided. It never
 * approves, never writes a diff, never touches the KB.
 *
 * Usage:
 *   node scripts/generate-strategy.js --fixture SAMPLE-CWE502     # demo from bundled fixtures
 *   node scripts/generate-strategy.js --issue ISSUE-005           # from .pipeline-context/research/
 */
const fs = require('fs');
const path = require('path');
const { plans } = require('./lib/research');

const SKILL_DIR = path.resolve(__dirname, '..');

function parseArgs(argv) {
  const a = {};
  for (let i = 0; i < argv.length; i += 1) {
    const x = argv[i];
    if (x === '--issue' || x === '-i') a.issue = argv[++i];
    else if (x === '--fixture') a.fixture = argv[++i];
    else if (x === '--json') a.json = true;
    else if (x === '--help' || x === '-h') a.help = true;
  }
  return a;
}

function loadInputs(args) {
  if (args.fixture) {
    const dir = path.join(SKILL_DIR, 'fixtures');
    return {
      understanding: JSON.parse(fs.readFileSync(path.join(dir, `${args.fixture}.understanding.json`), 'utf8')),
      analysis: JSON.parse(fs.readFileSync(path.join(dir, `${args.fixture}.analysis.json`), 'utf8')),
    };
  }
  const dir = path.join(plans.DATA_DIR, 'research');
  return {
    understanding: JSON.parse(fs.readFileSync(path.join(dir, `${args.issue}.understanding.json`), 'utf8')),
    analysis: JSON.parse(fs.readFileSync(path.join(dir, `${args.issue}.analysis.json`), 'utf8')),
  };
}

function assembleStrategy(u, a) {
  const rec = a.recommendation;
  const chosen = (a.candidates || []).find((c) => c.candidate_id === rec.candidate_id) || {};
  const rejected = (a.candidates || []).filter((c) => c.candidate_id !== rec.candidate_id);

  return {
    issue_id: u.issue_id,
    cwe: a.cwe || u.gap.primary_cwe,
    confidence: 'Low',
    remediation_source: '04d-remediation-research',
    plain_summary: `${rec.security_property_restored} (novel research — human review required).`,
    approach: `${chosen.approach || rec.why_selected} ${rec.why_selected}`,
    catalog_reference: { cwe: a.cwe || u.gap.primary_cwe, title: null },
    affected_files: (u.affected_files && u.affected_files.length ? u.affected_files : ['(confirm affected file)']).map((f) => ({
      file: f,
      planned_change: `${chosen.approach || 'Apply the recommended remediation'} (04b implements; smallest change that restores: ${a.security_objective}).`,
    })),
    verification_plan: (a.validation_requirements || []).map((t) => `${t.id} [${t.type}]: ${t.assertion}`),
    alternatives_considered: rejected.map((c) => `${c.candidate_id} (${c.approach.split('.')[0]}) — rejected: ${rec.why_alternatives_rejected}`),
    risk_notes: [
      `Residual risk: ${rec.residual_risk}`,
      ...(chosen.limitations || []).map((l) => `Limitation: ${l}`),
      ...(chosen.bypass_risks || []).map((b) => `Bypass risk: ${b}`),
    ],
    open_questions: [
      ...((a.root_cause && a.root_cause.hypotheses) || []).map((h) => `Unconfirmed: ${h}`),
      `${a.cwe} has no catalog or KB entry — this strategy is novel research and should be human-reviewed, then (if it ships) considered for KB promotion.`,
    ],
    research: {
      research_status: a.research_status,
      vulnerability: {
        statement: u.defect && u.defect.statement,
        location: u.defect && u.defect.location,
        entry_points: u.entry_points || [],
        data_flow: u.evidence && u.evidence.data_flow,
        source: u.defect && u.defect.source,
      },
      root_cause: a.root_cause,
      threat: a.threat,
      security_objective: a.security_objective,
      candidates: a.candidates,
      evaluation: a.evaluation,
      recommendation: rec,
      validation_requirements: a.validation_requirements,
      evidence_sources: u.evidence_sources,
    },
    derived_pattern: {
      type: 'novel-research',
      sources: (a.provenance && a.provenance.sources) || [],
      reasoning: (a.provenance && a.provenance.reasoning) || 'Internally derived.',
      candidate_count: (a.candidates || []).length,
      external_research_available: Boolean(a.provenance && a.provenance.external_research_available),
    },
    promotion_candidate: true,
  };
}

// Evidence-gap strategy — produced when research could not establish a confident fix. Instead of a
// dead stop, 04d states what IS known, what evidence is missing, and a conservative default, and
// routes it to a human as a Proposed plan (clearly flagged as NOT a confident fix).
function assembleEvidenceGap(u, a) {
  const missing = a.missing_evidence || [];
  const cd = a.conservative_default || {};
  const known = (u.defect && u.defect.statement) || u.title;
  return {
    issue_id: u.issue_id,
    cwe: a.cwe || u.gap.primary_cwe,
    confidence: 'Low',
    remediation_source: '04d-remediation-research',
    plain_summary: 'Evidence gap — 04d could not establish a confident remediation; this plan states what is known and what evidence is missing, for human investigation.',
    approach: `**Evidence gap — not a confident fix.** What is known: ${known} What is missing before a targeted remediation can be designed: ${missing.join('; ') || 'the vulnerable sink, the attacker-controlled input, and the trust boundary could not be established from the evidence.'} Conservative default while that evidence is gathered: ${cd.approach || 'apply defence-in-depth (input validation, least privilege, deny-by-default) at the affected component, pending precise localisation.'}`,
    catalog_reference: { cwe: a.cwe || u.gap.primary_cwe, title: null },
    affected_files: (u.affected_files && u.affected_files.length ? u.affected_files : ['(affected component not localised — human must confirm)']).map((f) => ({
      file: f,
      planned_change: cd.approach || 'Conservative hardening only, pending precise localisation of the defect. Do not implement a targeted fix until the missing evidence is gathered.',
    })),
    verification_plan: [
      ...missing.map((m, i) => `EVIDENCE-${String(i + 1).padStart(2, '0')}: gather — ${m}`),
      'Manually confirm the vulnerable sink, entry point and trust boundary against live source before any fix is designed.',
    ],
    risk_notes: [
      'EVIDENCE GAP: this is NOT a confident remediation. Do not approve it as a fix as-is — gather the missing evidence and re-run 04d first.',
      cd.note || 'The conservative default is generic hardening, not a targeted fix.',
    ],
    open_questions: missing.length ? missing.map((m) => `Missing evidence: ${m}`) : ['The evidence bundle could not localise the defect; more investigation is required.'],
    research: {
      research_status: 'insufficient_evidence',
      vulnerability: { statement: u.defect && u.defect.statement, location: u.defect && u.defect.location, entry_points: u.entry_points || [], data_flow: u.evidence && u.evidence.data_flow },
      root_cause: a.root_cause,
      missing_evidence: missing,
      conservative_default: cd,
      recommendation: a.recommendation,
    },
    derived_pattern: {
      type: 'novel-research',
      sources: (a.provenance && a.provenance.sources) || [],
      reasoning: (a.provenance && a.provenance.reasoning) || 'Evidence insufficient to design a targeted remediation; conservative default proposed for human review.',
      candidate_count: 0,
      external_research_available: Boolean(a.provenance && a.provenance.external_research_available),
    },
    promotion_candidate: false,
  };
}

const BASE_REQUIRED = [
  ['cwe', (s) => s.cwe],
  ['plain_summary', (s) => s.plain_summary && s.plain_summary.trim()],
  ['approach', (s) => s.approach && s.approach.trim()],
  ['catalog_reference.cwe', (s) => s.catalog_reference && s.catalog_reference.cwe],
  ['affected_files (non-empty)', (s) => Array.isArray(s.affected_files) && s.affected_files.length],
  ['verification_plan (non-empty)', (s) => Array.isArray(s.verification_plan) && s.verification_plan.length],
];

function bar(t) { return `\n${'='.repeat(74)}\n ${t}\n${'='.repeat(74)}`; }
function sub(t) { return `\n${'-'.repeat(74)}\n ${t}\n${'-'.repeat(74)}`; }

function printResearch(u, a, strategy) {
  console.log(bar(`04d Novel Remediation Research — ${u.issue_id} (${a.cwe})`));
  console.log(` Remediation source : 04d-remediation-research (NOVEL — not catalog, not KB)`);
  console.log(` Catalog / KB       : ${u.gap.catalog_status.toUpperCase()} / ${u.gap.kb_status.toUpperCase()}`);
  console.log(` Confidence         : Low (internally derived; external research available: ${strategy.derived_pattern.external_research_available})`);
  console.log(` Research status    : ${a.research_status}`);

  console.log(sub('Security threat (attack narrative)'));
  console.log(` Actor   : ${a.threat.actor}`);
  console.log(` Vector  : ${a.threat.vector}`);
  console.log(` Impact  : C=${a.threat.confidentiality_impact} I=${a.threat.integrity_impact} A=${a.threat.availability_impact}`);
  console.log(`\n ${a.threat.attack_narrative}`);

  console.log(sub('Security objective (invariant to restore)'));
  console.log(` ${a.security_objective}`);

  console.log(sub(`Remediation candidates (${a.candidates.length})`));
  for (const c of a.candidates) {
    const mark = c.candidate_id === a.recommendation.candidate_id ? '  <== RECOMMENDED' : '';
    console.log(`\n [${c.candidate_id}] ${c.affected_layer} · complexity ${c.implementation_complexity}${mark}`);
    console.log(`   approach : ${c.approach}`);
    console.log(`   mechanism: ${c.security_mechanism}`);
    console.log(`   + ${(c.advantages || []).join('\n   + ')}`);
    if ((c.limitations || []).length) console.log(`   - ${(c.limitations || []).join('\n   - ')}`);
    if ((c.bypass_risks || []).length) console.log(`   bypass: ${(c.bypass_risks || []).join('; ')}`);
  }

  console.log(sub('Recommendation'));
  console.log(` Chosen   : ${a.recommendation.candidate_id}`);
  console.log(` Why      : ${a.recommendation.why_selected}`);
  console.log(` Rejected : ${a.recommendation.why_alternatives_rejected}`);
  console.log(` Restores : ${a.recommendation.security_property_restored}`);
  console.log(` Residual : ${a.recommendation.residual_risk}`);

  console.log(sub('Validation requirements (for Agent 05 / 06)'));
  for (const t of a.validation_requirements) console.log(` ${t.id} [${t.type}]: ${t.assertion}`);

  console.log(sub('Provenance'));
  console.log(` Catalog        : NOT AVAILABLE`);
  console.log(` Internal KB    : NOT AVAILABLE / INSUFFICIENT`);
  console.log(` Source         : 04d Novel Remediation Research`);
  console.log(` Confidence     : LOW`);
  console.log(` External refs  : ${strategy.derived_pattern.sources.length ? strategy.derived_pattern.sources.join(', ') : 'none (internally derived — no citation claimed)'}`);
  console.log(` Reasoning      : ${strategy.derived_pattern.reasoning}`);
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || (!args.issue && !args.fixture)) {
    console.log('node scripts/generate-strategy.js --fixture SAMPLE-CWE502\nnode scripts/generate-strategy.js --issue <ID>');
    return;
  }

  const { understanding: u, analysis: a } = loadInputs(args);

  if (!u.gap || !u.gap.research_required) {
    console.error(`04d declines: ${u.issue_id} is not a double gap (catalog_status=${u.gap && u.gap.catalog_status}, kb_status=${u.gap && u.gap.kb_status}).`);
    process.exit(2);
  }

  const outDir = path.join(plans.DATA_DIR, 'research');
  fs.mkdirSync(outDir, { recursive: true });

  // Evidence-gap path: research could not establish a CONFIDENT fix. Instead of stopping, 04d
  // produces a Proposed evidence-gap plan (what is known + what is missing + a conservative default)
  // and routes it to a human — never a dead-end.
  if (a.research_status === 'insufficient_evidence') {
    const strategy = assembleEvidenceGap(u, a);
    const gapMissing = BASE_REQUIRED.filter(([, ok]) => !ok(strategy)).map(([k]) => k);
    if (gapMissing.length) throw new Error(`Evidence-gap strategy missing base field(s): ${gapMissing.join(', ')}`);
    const strategyFile = plans.strategyPathFor(u.issue_id);
    fs.mkdirSync(path.dirname(strategyFile), { recursive: true });
    fs.writeFileSync(strategyFile, JSON.stringify(strategy, null, 2));
    fs.writeFileSync(path.join(outDir, `${u.issue_id}.research-result.json`), JSON.stringify({ issue_id: u.issue_id, cwe: a.cwe, catalog_status: u.gap.catalog_status, kb_status: u.gap.kb_status, research_required: true, research_status: 'insufficient_evidence' }, null, 2));
    console.log(bar(`04d EVIDENCE GAP — ${u.issue_id} (${a.cwe})`));
    console.log(' research_status = insufficient_evidence');
    console.log(' 04d could not establish a confident fix, so instead of stopping it produced an');
    console.log(' EVIDENCE-GAP plan: what is known, what evidence is missing, and a conservative');
    console.log(' default — rendered as a Proposed plan and routed to a human (never a dead-end).');
    console.log(`\n wrote: ${plans.rel(strategyFile)}`);
    console.log(` next : render-fix-plan.js --issue ${u.issue_id}  ->  Proposed evidence-gap plan for human review.`);
    return;
  }

  const strategy = assembleStrategy(u, a);

  const missing = BASE_REQUIRED.filter(([, ok]) => !ok(strategy)).map(([k]) => k);
  if (missing.length) throw new Error(`Assembled strategy missing base contract field(s): ${missing.join(', ')}`);

  // Write the strategy where the EXISTING render-fix-plan.js reads it (the fix-strategy dir),
  // so 04d re-joins the normal Agent 4 flow with no renderer-specific path.
  const strategyFile = plans.strategyPathFor(u.issue_id);
  fs.mkdirSync(path.dirname(strategyFile), { recursive: true });
  fs.writeFileSync(strategyFile, JSON.stringify(strategy, null, 2));

  const promotion = {
    issue_id: u.issue_id, cwe: a.cwe, source: '04d-remediation-research',
    status: 'candidate', eligible_after: ['approved', 'implemented', '05-verified', '06-tested', '07-cleared'],
    proposed_kb_entry: { cwe: a.cwe, title: u.title, canonical_approach: a.recommendation.security_property_restored, anti_patterns: (strategy.risk_notes || []).filter((r) => /bypass|anti|limitation/i.test(r)) },
    note: 'NOT written to the KB automatically. A human promotes it only after the fix ships cleanly.',
  };
  fs.writeFileSync(path.join(outDir, `${u.issue_id}.promotion-candidate.json`), JSON.stringify(promotion, null, 2));

  if (args.json) { console.log(JSON.stringify(strategy, null, 2)); return; }

  printResearch(u, a, strategy);
  console.log(sub('Assembled strategy (base-contract compatible -> feeds render-fix-plan.js)'));
  console.log(` remediation_source : ${strategy.remediation_source}`);
  console.log(` confidence         : ${strategy.confidence}`);
  console.log(` catalog_reference  : cwe=${strategy.catalog_reference.cwe}, title=${strategy.catalog_reference.title}`);
  console.log(` affected_files     : ${strategy.affected_files.length}`);
  console.log(` verification_plan  : ${strategy.verification_plan.length} steps`);
  console.log(` derived_pattern    : type=${strategy.derived_pattern.type}, candidates=${strategy.derived_pattern.candidate_count}`);
  console.log(` promotion_candidate: ${strategy.promotion_candidate}`);
  console.log(`\n wrote: ${plans.rel(strategyFile)}  +  ${u.issue_id}.promotion-candidate.json`);
  console.log('\n NOTE: no plan rendered, no Status set, 04b NOT invoked. Next steps (render + Proposed +');
  console.log(' agent wiring + tests) come after you confirm this research output is accurate.');
}

try { main(); } catch (err) { console.error('generate-strategy failed:', err.message); process.exit(1); }
