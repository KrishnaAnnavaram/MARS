/**
 * 04a2 Remediation Research — deterministic evidence extraction (first slice).
 *
 * This module does NOT reason about a vulnerability. It STRUCTURES what upstream agents already
 * established: it reuses 04a's catalog + report parsing and 04a1's KB, reads the diagnosed root
 * cause report, the issue register row, the (optional) blast radius report, and the vulnerable
 * source — and assembles a "vulnerability understanding" object purely by extraction.
 *
 * Everything here is fact-from-evidence: nothing is invented, no remediation is proposed. That is
 * exactly what makes the output checkable for accuracy against the source reports.
 */
const fs = require('fs');
const path = require('path');

// Reuse the existing pipeline contracts rather than re-implementing them.
const plans = require('../../../04a-fix-strategist/scripts/lib/plans');
const { loadKb } = require('../../../04a1-remediation-intelligence/scripts/lib/fallback');

// ---------------------------------------------------------------------------
// Gap classification (catalog + KB) — reuses 04a catalog and 04a1 KB
// ---------------------------------------------------------------------------

function loadClassifiers() {
  return { catalog: plans.loadCatalog(), kb: loadKb() };
}

/** Every detected CWE tagged with catalog/KB membership. */
function classifyCwes(text, catalog, kb) {
  return plans.detectCweMentions(text).map((id) => ({
    id,
    inCatalog: Boolean(catalog[id]),
    inKb: Boolean(kb[id]),
  }));
}

/**
 * The four-state classification, never collapsed:
 *   catalog_status: match | gap
 *   kb_status:      n/a (catalog match) | match | gap
 *   research_required: true only when catalog gap AND kb gap (the 04a2 trigger)
 */
function gapStatus(cwes) {
  if (!cwes.length) {
    return { catalog_status: 'unknown', kb_status: 'unknown', research_required: false, primary_cwe: null };
  }
  const anyCatalog = cwes.some((c) => c.inCatalog);
  const gaps = cwes.filter((c) => !c.inCatalog);
  const anyKb = gaps.some((c) => c.inKb);

  const catalog_status = anyCatalog ? 'match' : 'gap';
  let kb_status;
  if (anyCatalog) kb_status = 'n/a';
  else kb_status = anyKb ? 'match' : 'gap';

  const research_required = catalog_status === 'gap' && kb_status === 'gap';
  // The CWE 04a2 would research: the first gap CWE with no KB entry.
  const primary_cwe = research_required
    ? (gaps.find((c) => !c.inKb) || gaps[0]).id
    : (gaps[0] || cwes[0]).id;

  return { catalog_status, kb_status, research_required, primary_cwe };
}

// ---------------------------------------------------------------------------
// Evidence extraction helpers
// ---------------------------------------------------------------------------

/** Pull one `## Heading` section body out of the synthesized issue markdown. */
function extractSection(body, heading) {
  if (!body) return null;
  const esc = heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`##\\s+${esc}\\s*\\r?\\n([\\s\\S]*?)(?=\\r?\\n##\\s|$)`, 'i');
  const m = re.exec(body);
  return m ? m[1].trim() : null;
}

/** Parse "path/File.java:20-31" (or ":24") and read that region with a little context. */
function readSnippet(repoRoot, location) {
  if (!location) return null;
  const m = /^(.*?):(\d+)(?:-(\d+))?$/.exec(location.trim());
  if (!m) return { file: location, lines: null, code: null, note: 'location not in path:line form' };
  const file = m[1];
  const start = parseInt(m[2], 10);
  const end = m[3] ? parseInt(m[3], 10) : start;
  const abs = path.join(repoRoot, file);
  if (!fs.existsSync(abs)) return { file, lines: `${start}-${end}`, code: null, note: 'file not found on disk' };
  const all = fs.readFileSync(abs, 'utf8').split(/\r?\n/);
  const from = Math.max(1, start - 2);
  const to = Math.min(all.length, end + 2);
  const code = all.slice(from - 1, to).map((l, i) => `${String(from + i).padStart(4)} | ${l}`).join('\n');
  return { file, lines: `${start}-${end}`, code, shown: `${from}-${to}` };
}

// ---------------------------------------------------------------------------
// The vulnerability understanding (pure extraction)
// ---------------------------------------------------------------------------

function buildUnderstanding(rc) {
  const { catalog, kb } = loadClassifiers();
  const issue = rc.issue || {};
  const reportText = fs.readFileSync(rc.reportFile, 'utf8');
  const scanText = [issue.body, reportText].filter(Boolean).join('\n');

  const cwes = classifyCwes(scanText, catalog, kb);
  const gap = gapStatus(cwes);
  const blast = plans.readBlastRadiusReport(rc.id);
  const snippet = readSnippet(plans.REPO_ROOT, rc.rootCause && rc.rootCause.defectLocation);

  return {
    issue_id: rc.id,
    title: rc.title,
    severity: (issue.severity) || rc.severity || null,
    services: issue.services || [],
    cwe: cwes,
    gap,
    defect: {
      statement: rc.rootCause && rc.rootCause.statement,
      location: rc.rootCause && rc.rootCause.defectLocation,
      confidence: rc.rootCause && rc.rootCause.confidence,
      recommended_fix_from_rca: rc.rootCause && rc.rootCause.recommendedFix,
      source: snippet,
    },
    evidence: {
      summary: extractSection(issue.body, 'Summary'),
      data_flow: extractSection(issue.body, 'Data Flow (source → sink)') || extractSection(issue.body, 'Data Flow'),
      observed_behavior: extractSection(issue.body, 'Observed Behavior'),
      expected_behavior: extractSection(issue.body, 'Expected Behavior'),
      impact: extractSection(issue.body, 'Impact'),
      detection_notes: extractSection(issue.body, 'Detection Notes'),
    },
    affected_files: issue.files || [],
    affected_symbols: issue.symbols || [],
    entry_points: issue.entryPoints || [],
    blast_radius: blast ? { headline: blast.headline, priority: blast.priority, scope: blast.scope } : null,
    evidence_sources: {
      root_cause_report: rc.relativeReportFile,
      issue_register: issue.relativeFile || 'docs/agent_output/00-issues/issue-register.xlsx',
      blast_radius_report: blast ? blast.relativeFile : null,
      vulnerable_source: snippet ? `${snippet.file}:${snippet.lines}` : null,
    },
  };
}

module.exports = { loadClassifiers, classifyCwes, gapStatus, extractSection, readSnippet, buildUnderstanding, plans };
