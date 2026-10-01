#!/usr/bin/env node
/**
 * 04a2 — Research context / vulnerability-understanding extractor (deterministic slice).
 *
 * Given an issue id, it:
 *   1. Classifies the CWE gap (catalog match / catalog gap / KB gap) — the 04a2 trigger check.
 *   2. Assembles a "vulnerability understanding" from existing evidence ONLY (root cause report,
 *      issue register, blast radius, vulnerable source). It invents nothing and proposes no fix.
 *
 * This is the piece to check for ACCURACY: does 04a2 faithfully capture the vulnerability from the
 * evidence the upstream agents produced?
 *
 * Usage:
 *   node scripts/research-context.js --issue ISSUE-003          # print the understanding brief
 *   node scripts/research-context.js --issue ISSUE-003 --json   # emit the raw JSON
 *   node scripts/research-context.js --gap-check CWE-502         # just show catalog/KB status
 */
const fs = require('fs');
const path = require('path');
const { plans, loadClassifiers, classifyCwes, gapStatus, buildUnderstanding } = require('./lib/research');

function parseArgs(argv) {
  const a = {};
  for (let i = 0; i < argv.length; i += 1) {
    const x = argv[i];
    if (x === '--issue' || x === '-i') a.issue = argv[++i];
    else if (x === '--json') a.json = true;
    else if (x === '--gap-check') a.gapCheck = argv[++i];
    else if (x === '--help' || x === '-h') a.help = true;
  }
  return a;
}

function bar(t) { return `\n${'='.repeat(74)}\n ${t}\n${'='.repeat(74)}`; }
function sub(t) { return `\n${'-'.repeat(74)}\n ${t}\n${'-'.repeat(74)}`; }

function printBrief(u) {
  const g = u.gap;
  console.log(bar(`04a2 Vulnerability Understanding — ${u.issue_id}`));
  console.log(` Title      : ${u.title}`);
  console.log(` Severity   : ${u.severity || 'n/a'}`);
  console.log(` Services   : ${u.services.join(', ') || 'n/a'}`);
  console.log(` CWE(s)     : ${u.cwe.map((c) => `${c.id} [catalog:${c.inCatalog ? 'yes' : 'no'} kb:${c.inKb ? 'yes' : 'no'}]`).join(', ') || 'none detected'}`);

  console.log(sub('Gap classification (routing decision)'));
  console.log(` catalog_status    : ${g.catalog_status}`);
  console.log(` kb_status         : ${g.kb_status}`);
  console.log(` research_required : ${g.research_required}  ${g.research_required ? '-> 04a2 WOULD run' : '-> handled by 04a/04a1, 04a2 would NOT run'}`);
  console.log(` primary_cwe       : ${g.primary_cwe}`);

  console.log(sub('Defect (from the root cause report)'));
  console.log(` Statement : ${u.defect.statement || 'n/a'}`);
  console.log(` Location  : ${u.defect.location || 'n/a'}`);
  console.log(` RCA conf. : ${u.defect.confidence || 'n/a'}`);
  if (u.defect.source && u.defect.source.code) {
    console.log(`\n Vulnerable source (${u.defect.source.file}:${u.defect.source.lines}):\n`);
    console.log(u.defect.source.code.split('\n').map((l) => `   ${l}`).join('\n'));
  } else if (u.defect.source) {
    console.log(` Source    : ${u.defect.source.note || 'not shown'}`);
  }

  console.log(sub('Evidence extracted (verbatim from the issue report)'));
  for (const [k, v] of Object.entries(u.evidence)) {
    if (v) console.log(` [${k}]\n   ${v.replace(/\n/g, '\n   ')}\n`);
  }

  console.log(sub('Reach & scope'));
  console.log(` Affected files   : ${u.affected_files.join(', ') || 'n/a'}`);
  console.log(` Affected symbols : ${u.affected_symbols.join(', ') || 'n/a'}`);
  console.log(` Entry points     : ${u.entry_points.join(', ') || 'n/a (see data flow)'}`);
  console.log(` Blast radius     : ${u.blast_radius ? `${u.blast_radius.headline || ''} (priority ${u.blast_radius.priority || 'n/a'})` : 'no blast radius report'}`);

  console.log(sub('Evidence sources (provenance)'));
  for (const [k, v] of Object.entries(u.evidence_sources)) console.log(` ${k.padEnd(20)}: ${v || 'n/a'}`);

  console.log(sub('What this slice does NOT do yet'));
  console.log(' No threat model reasoning, no security objective, no remediation candidates,');
  console.log(' no strategy.json, no rendering. This is the deterministic understanding only —');
  console.log(' check it for accuracy against the reports above, then we decide the next stages.');
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log('node scripts/research-context.js --issue <ID> [--json]\nnode scripts/research-context.js --gap-check <CWE-ID>');
    return;
  }

  if (args.gapCheck) {
    const { catalog, kb } = loadClassifiers();
    const cwes = classifyCwes(args.gapCheck.toUpperCase(), catalog, kb);
    const g = gapStatus(cwes);
    console.log(`${args.gapCheck.toUpperCase()}: catalog=${cwes[0] ? cwes[0].inCatalog : false}, kb=${cwes[0] ? cwes[0].inKb : false}`);
    console.log(`  -> catalog_status=${g.catalog_status}, kb_status=${g.kb_status}, research_required=${g.research_required}`);
    console.log(`  -> ${g.research_required ? 'DOUBLE GAP: 04a2 would run (novel research)' : (g.catalog_status === 'match' ? '04a (catalogued)' : '04a1 (KB has it)')}`);
    return;
  }

  if (!args.issue) throw new Error('Missing --issue <ID> (or --gap-check <CWE>).');

  const rc = plans.resolveRootCauseReport(args.issue);
  const understanding = buildUnderstanding(rc);

  // Persist for later stages (gitignored working area).
  const outDir = path.join(plans.DATA_DIR, 'research');
  fs.mkdirSync(outDir, { recursive: true });
  const outFile = path.join(outDir, `${rc.id}.understanding.json`);
  fs.writeFileSync(outFile, JSON.stringify(understanding, null, 2));

  if (args.json) { console.log(JSON.stringify(understanding, null, 2)); return; }
  printBrief(understanding);
  console.log(`\n(understanding JSON written to ${plans.rel(outFile)})`);
}

try { main(); } catch (err) { console.error('research-context failed:', err.message); process.exit(1); }
