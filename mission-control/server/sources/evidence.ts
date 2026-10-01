/**
 * Evidence model: every file under docs/agent_output parsed into typed records. Parsing is
 * read-only and fault-tolerant: an unexpected file produces `parseOk: false` and a note, never a crash.
 */
import type { Provenance, StageId } from '../../shared/types.js';
import {
  col, extractField, findTable, firstParagraph, generatorLine, glance, headline, leadingNumber, linkTargets,
  listItems, plain, repoPathFromLink, section, subtitle, tables,
} from '../lib/md.js';
import type { Workspace } from './workspace.js';
import { sha256 } from './workspace.js';

import { classifier } from '../lib/harness.js';

export function classify(ws: Workspace) {
  return classifier(ws);
}

export const EVIDENCE_ROOT = 'docs/agent_output';

export interface FileRec {
  path: string;
  type: string;
  issueId: string | null;
  stage: StageId | null;
  sha256: string;
  size: number;
  mtimeMs: number;
  text: string | null;
  generatedDate: string | null;
  instants: string[];
  parseOk: boolean;
  parseNote: string | null;
  provenance: Provenance;
  provenanceNote: string | null;
  producer: string | null;
  producerAgent: string | null;
  links: string[];
}

export interface RcaRec { file: FileRec; title: string | null; headline: string | null; g: Record<string, string>; location: string | null; methods: { method: string; service: string; file: string; lines: string | null }[]; collectedAt: string | null }
export interface BlastRec { file: FileRec; headline: string | null; g: Record<string, string>; services: { service: string; status: string; note: string }[]; endpoints: { endpoint: string; service: string; status: string; note: string }[]; collectedAt: string | null }
export interface PlanRec {
  file: FileRec; title: string | null; headline: string | null; g: Record<string, string>; status: string | null; cwe: string | null; cweName: string | null;
  reproposed: boolean; reproposedNote: string | null; route: 'catalog' | 'kb' | 'research' | 'evidence_gap' | 'unknown'; approach: string | null; alternatives: string[];
  plannedChanges: { file: string; change: string }[]; risks: string[]; verification: string[]; collectedAt: string | null;
}
export interface FixRec { file: FileRec; headline: string | null; g: Record<string, string>; status: string | null; claimed: { file: string; change: string }[]; patchLink: string | null; verifiedAt: string | null }
export interface DiffRec { file: FileRec; files: { path: string; additions: number; deletions: number }[] }
export interface VerifyRec { file: FileRec; check: 'rescan' | 'redteam' | 'behavior'; headline: string | null; g: Record<string, string>; verdict: string | null; confidence: string | null; collectedAt: string | null; manualNote: string | null }
export interface GateRec {
  file: FileRec; kind: 'qa' | 'build'; g: Record<string, string>; headerStatus: string | null; bodyStatus: string | null;
  tests: { name: string; status: string; exitCode: number | null }[]; steps: { module: string; command: string; exitCode: number | null }[];
  compileErrors: { file: string; line: number; column: number; message: string; symbol: string | null }[]; compileErrorsTruncated: boolean;
  outputTruncated: boolean; insertedProse: string[]; runAt: string | null; newTest: string | null;
}
export interface VerdictRec {
  file: FileRec; g: Record<string, string>; decision: string | null; score: number | null; threshold: number | null; severityLabel: string | null; cwe: string | null;
  hardGatesTriggered: string[]; breakdown: { check: string; verdict: string; points: number | null; max: number | null }[];
  hardGateTable: { name: string; triggered: boolean }[]; upstream: { check: string; verdict: string; report: string | null }[];
  narrative: string | null; override: { applied: boolean; reason: string | null }; computedAt: string | null;
}
export interface PrRec { file: FileRec; blockedBanner: boolean; title: string | null }
export interface AuditRec { file: FileRec; chain: { stage: string; agent: string; date: string; finding: string; source: string | null }[] }
export interface IndexRec { file: FileRec; rows: Record<string, string>[] }

export interface Evidence {
  files: FileRec[];
  byPath: Map<string, FileRec>;
  rca: Map<string, RcaRec>;
  blast: Map<string, BlastRec>;
  plan: Map<string, PlanRec>;
  fix: Map<string, FixRec>;
  diff: Map<string, DiffRec>;
  verify: Map<string, Partial<Record<'rescan' | 'redteam' | 'behavior', VerifyRec>>>;
  gate: Map<string, Partial<Record<'qa' | 'build', GateRec>>>;
  verdict: Map<string, VerdictRec>;
  pr: Map<string, PrRec>;
  audit: Map<string, AuditRec>;
  indexes: Partial<Record<'remediation' | 'verify' | 'testgate' | 'ship', IndexRec>>;
  architecture: { doc: FileRec | null; functionRef: FileRec | null; recordedGraphCounts: Record<string, number> | null };
  issueIds: Set<string>;
}

const PRODUCER: Record<string, { script: string | null; agent: string | null; provenance: Provenance; note: string }> = {
  issue_register: { script: null, agent: 'reporter', provenance: 'human', note: 'Reporter-owned input; read-only to the pipeline.' },
  architecture_doc: { script: '01d-blueprint-scribe/generate-docs', agent: '01_architect', provenance: 'computed', note: 'Synthesized from the code model and (when reachable) live Neo4j counts.' },
  function_reference: { script: '01d-blueprint-scribe/generate-function-reference', agent: '01_architect', provenance: 'computed', note: 'Computed from artifacts.json.' },
  root_cause_report: { script: '02-root-cause-analyst/render-root-cause', agent: '02_root-cause-analyst', provenance: 'mixed', note: 'Symptom, code table, source and call paths are rendered from evidence; cause, chain, fix and prevention are AI-authored judgement.' },
  blast_radius_report: { script: '03-blast-radius-analyst/render-blast-radius', agent: '03_blast-radius-analyst', provenance: 'mixed', note: 'Reach and service status are measured by rule; headline, impact and narrative are AI-authored.' },
  fix_plan: { script: '04a-fix-strategist/render-fix-plan', agent: '04_fix-generator', provenance: 'mixed', note: 'Affected files and diagnosis are rendered from upstream; approach, risks and verification plan are AI-authored. The Status cell is human-edited.' },
  fix_report: { script: '04b-fixer/render-fix-report', agent: '04_fix-generator', provenance: 'mixed', note: 'Compile result is computed in a worktree; diff and rationale are AI-authored.' },
  fix_diff: { script: '04b-fixer/render-fix-report', agent: '04_fix-generator', provenance: 'ai_authored', note: 'AI-drafted patch; applied only inside throwaway worktrees.' },
  rescan_report: { script: '05-verify/render-rescan', agent: '05_existing-app-test-agent', provenance: 'mixed', note: 'Signature facts are collected by script; the verdict is AI judgement.' },
  redteam_report: { script: '05-verify/render-redteam', agent: '05_existing-app-test-agent', provenance: 'mixed', note: 'Inputs are collected by script; vectors and verdict are AI judgement.' },
  behavior_report: { script: '05-verify/render-behavior', agent: '05_existing-app-test-agent', provenance: 'mixed', note: 'Signature diff is mechanical; classification of changes is AI judgement.' },
  qa_report: { script: '06a-qa-runner/render-qa-report', agent: '06_additional-test-execution', provenance: 'mixed', note: 'The test is AI-drafted; pass/fail comes from the real exit code.' },
  build_report: { script: '06b-build-gatekeeper/render-build-report', agent: '06_additional-test-execution', provenance: 'computed', note: 'Declared fully computed: every line should come from run-build-gate.js.' },
  verdict: { script: '07a-merge-arbiter/render-verdict', agent: '07_audit-and-pr', provenance: 'mixed', note: 'Score, gates and links are computed by compute-score.js; the narrative and any override are AI-authored.' },
  pr_content: { script: '07b-scribe/render-scribe', agent: '07_audit-and-pr', provenance: 'mixed', note: 'Decision banner is mechanical; summary and test plan are AI-authored.' },
  audit_trail: { script: '07b-scribe/render-scribe', agent: '07_audit-and-pr', provenance: 'mixed', note: 'Chain-of-custody facts are collected; the narrative is AI-authored.' },
  stage_index: { script: null, agent: null, provenance: 'computed', note: 'Auto-generated index table, rewritten by the stage renderers.' },
  decision_record: { script: 'harness/record-decision', agent: 'human', provenance: 'human', note: 'Human decision record written only by record-decision.js.' },
  summary: { script: null, agent: null, provenance: 'human', note: 'Hand-written historical summary (the contract marks it historical).' },
  readme: { script: null, agent: null, provenance: 'human', note: 'Documentation.' },
};

function fileRec(ws: Workspace, rel: string): FileRec | null {
  const buf = ws.readBuffer(rel);
  if (!buf) return null;
  const st = ws.stat(rel);
  const cls = classify(ws).classifyPath(rel);
  const isText = !/\.xlsx$/i.test(rel);
  const text = isText ? buf.toString('utf8') : null;
  const gen = text ? generatorLine(text) : { date: null, instants: [] as string[] };
  const prod = PRODUCER[cls.artifact_type] || { script: null, agent: null, provenance: 'unknown' as Provenance, note: '' };
  return {
    path: rel,
    type: cls.artifact_type,
    issueId: cls.issue_id,
    stage: (cls.stage_id as StageId) || null,
    sha256: sha256(buf),
    size: buf.length,
    mtimeMs: st ? st.mtimeMs : 0,
    text,
    generatedDate: gen.date,
    instants: gen.instants,
    parseOk: true,
    parseNote: null,
    provenance: prod.provenance,
    provenanceNote: prod.note || null,
    producer: prod.script,
    producerAgent: prod.agent,
    links: text ? linkTargets(text).filter((l) => !/^https?:/.test(l)).map(repoPathFromLink) : [],
  };
}

function fail(f: FileRec, note: string): void {
  f.parseOk = false;
  f.parseNote = note;
}

const ISO = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/;

function rowsOf(t: ReturnType<typeof findTable>, ...names: string[]): Record<string, string>[] {
  if (!t) return [];
  const idx = names.map((n) => col(t, n));
  return t.rows.map((r) => Object.fromEntries(names.map((n, i) => [n, idx[i] >= 0 ? r[idx[i]] ?? '' : ''])));
}

function parseRca(f: FileRec): RcaRec {
  const t = f.text || '';
  const g = glance(t);
  if (!g['Root cause']) fail(f, 'No "Root cause" row in At a glance.');
  const loc = (/\*\*Location:\*\*\s*`([^`]+)`/.exec(t) || [])[1] || plain(g.Where) || null;
  const codeTable = findTable(section(t, /^Where the defect is/), 'Method', 'Service', 'File');
  const methods = rowsOf(codeTable, 'Method', 'Service', 'File').map((r) => {
    const link = linkTargets(r.File)[0] || '';
    const lines = (/#L(\d+)(?:-L(\d+))?/.exec(link) || []).slice(1).filter(Boolean).join('-') || null;
    return { method: plain(r.Method) || '', service: plain(r.Service) || '', file: repoPathFromLink(link), lines };
  });
  return { file: f, title: subtitle(t), headline: headline(t), g, location: loc, methods, collectedAt: f.instants[0] || null };
}

function parseBlast(f: FileRec): BlastRec {
  const t = f.text || '';
  const g = glance(t);
  if (!g.Priority) fail(f, 'No "Priority" row in At a glance.');
  const svc = findTable(t, 'Service', 'Status');
  const services = rowsOf(svc, 'Service', 'Status', 'What happens to it').map((r) => ({ service: plain(r.Service) || '', status: (plain(r.Status) || '').replace(/^[^A-Za-z]+/, ''), note: plain(r['What happens to it']) || '' }));
  const eps = findTable(t, 'Endpoint', 'Service', 'Status');
  const endpoints = rowsOf(eps, 'Endpoint', 'Service', 'Status', 'What a caller sees').map((r) => ({ endpoint: plain(r.Endpoint) || '', service: plain(r.Service) || '', status: (plain(r.Status) || '').replace(/^[^A-Za-z]+/, ''), note: plain(r['What a caller sees']) || '' }));
  return { file: f, headline: headline(t), g, services, endpoints, collectedAt: f.instants[0] || null };
}

function parsePlan(f: FileRec): PlanRec {
  const t = f.text || '';
  const g = glance(t);
  const status = extractField(t, 'Status');
  if (!status) fail(f, 'No Status cell.');
  const cweCell = g.CWE || '';
  const cwe = (/CWE-\d+/.exec(cweCell) || [])[0] || null;
  const cweName = plain(cweCell.split('—')[1] || '') || null;
  const reNote = /_This plan was re-proposed on ([0-9-]+); its Status \(([^)]+)\) was preserved[^_]*_/.exec(t);
  let route: PlanRec['route'] = 'unknown';
  if (/insufficient_evidence|EVIDENCE-GAP|evidence-gap plan/i.test(t)) route = 'evidence_gap';
  else if (/04a2-remediation-research|novel-research/i.test(t)) route = 'research';
  else if (/04a1|derived_pattern|Derived by the 04a1/i.test(t)) route = 'kb';
  else if (/Catalog pattern/i.test(t)) route = 'catalog';
  const changes = findTable(section(t, /^Planned changes/), 'File', 'Planned change');
  const plannedChanges = rowsOf(changes, 'File', 'Planned change').map((r) => ({ file: repoPathFromLink(linkTargets(r.File)[0] || plain(r.File) || ''), change: plain(r['Planned change']) || '' }));
  return {
    file: f, title: subtitle(t), headline: headline(t), g, status, cwe, cweName,
    reproposed: Boolean(reNote), reproposedNote: reNote ? plain(reNote[0].replace(/^_|_$/g, '')) : null, route,
    approach: firstParagraph(section(t, /^Remediation approach/)), alternatives: listItems(section(t, /^Alternatives considered/)),
    plannedChanges, risks: listItems(section(t, /^Risks to watch/)), verification: listItems(section(t, /^How the fix must be verified/)),
    collectedAt: f.instants[0] || null,
  };
}

function parseFix(f: FileRec): FixRec {
  const t = f.text || '';
  const g = glance(t);
  const status = extractField(t, 'Status');
  if (!status) fail(f, 'No Status cell.');
  const changed = findTable(section(t, /^What changed/), 'File', 'Change');
  const claimed = rowsOf(changed, 'File', 'Change').map((r) => ({ file: repoPathFromLink(linkTargets(r.File)[0] || plain(r.File) || ''), change: plain(r.Change) || '' }));
  const patchLink = linkTargets(g.Patch || '')[0] || null;
  return { file: f, headline: headline(t), g, status, claimed, patchLink: patchLink ? repoPathFromLink(patchLink) : null, verifiedAt: f.instants[0] || null };
}

function parseDiff(f: FileRec): DiffRec {
  const t = f.text || '';
  const files: DiffRec['files'] = [];
  let cur: DiffRec['files'][number] | null = null;
  for (const line of t.split(/\r?\n/)) {
    const m = /^\+\+\+ (?:b\/)?(.+?)\s*$/.exec(line);
    if (m) {
      if (m[1] === '/dev/null') {
        cur = null;
        continue;
      }
      cur = { path: m[1], additions: 0, deletions: 0 };
      files.push(cur);
      continue;
    }
    if (/^--- /.test(line) || /^diff --git/.test(line)) continue;
    if (cur && line.startsWith('+')) cur.additions += 1;
    else if (cur && line.startsWith('-')) cur.deletions += 1;
  }
  if (!files.length) fail(f, 'No file headers (+++ b/…) found in diff.');
  return { file: f, files };
}

function parseVerify(f: FileRec, check: VerifyRec['check']): VerifyRec {
  const t = f.text || '';
  const g = glance(t);
  const verdict = extractField(t, 'Verdict');
  if (!verdict) fail(f, 'No Verdict cell.');
  const manual = /reconstructed by hand[^.]*\.|after a rendering defect[^.]*\./i.exec(t);
  return { file: f, check, headline: headline(t), g, verdict, confidence: plain(g.Confidence) || null, collectedAt: f.instants[0] || null, manualNote: manual ? plain(manual[0]) : null };
}

const COMPILE_ERR = /([A-Za-z0-9_$]+\.java):\[(\d+),(\d+)\]\s+([^\n]+)(?:\n\[ERROR\]\s+symbol:\s+([^\n]+))?/g;

function parseGate(f: FileRec, kind: 'qa' | 'build'): GateRec {
  const t = f.text || '';
  const g = glance(t);
  const headerStatus = extractField(t, 'Status');
  if (!headerStatus) fail(f, 'No Status cell.');
  const tests: GateRec['tests'] = [];
  const steps: GateRec['steps'] = [];
  for (const table of tables(t)) {
    const hs = table.headers.map((h) => h.toLowerCase());
    if (kind === 'qa' && hs.includes('test') && hs.includes('status')) {
      const ti = hs.indexOf('test');
      const si = hs.indexOf('status');
      const ei = hs.indexOf('exit code');
      for (const r of table.rows) tests.push({ name: plain(r[ti]) || '', status: (plain(r[si]) || '').toUpperCase(), exitCode: ei >= 0 ? leadingNumber(r[ei]) : null });
    }
    if (hs.includes('module') && hs.includes('exit code') && hs.includes('command')) {
      const mi = hs.indexOf('module');
      const ci = hs.indexOf('command');
      const ei = hs.indexOf('exit code');
      for (const r of table.rows) steps.push({ module: plain(r[mi]) || '', command: (plain(r[ci]) || '').split(' (')[0], exitCode: leadingNumber(r[ei]) });
    }
  }
  let bodyStatus: string | null = null;
  if (kind === 'qa' && tests.length) {
    if (tests.some((x) => x.status === 'FAIL')) bodyStatus = 'Failed';
    else if (tests.some((x) => x.status === 'PASS')) bodyStatus = 'Passed';
  }
  if (kind === 'build' && steps.length) {
    if (steps.some((s) => s.exitCode !== 0)) bodyStatus = 'Failed';
    else if (steps.every((s) => s.exitCode === 0)) bodyStatus = 'Passed';
  }
  const compileErrors: GateRec['compileErrors'] = [];
  const seen = new Set<string>();
  let m: RegExpExecArray | null;
  COMPILE_ERR.lastIndex = 0;
  while ((m = COMPILE_ERR.exec(t)) && compileErrors.length < 100) {
    const key = `${m[1]}:${m[2]}:${m[3]}`;
    if (seen.has(key)) continue;
    seen.add(key);
    compileErrors.push({ file: m[1], line: Number(m[2]), column: Number(m[3]), message: m[4].trim(), symbol: m[5] ? m[5].trim() : null });
  }
  const inserted: string[] = [];
  const re = /^\*\*(Why this[^*]*)\*\*/gm;
  let w: RegExpExecArray | null;
  while ((w = re.exec(t))) inserted.push(w[1].replace(/:$/, ''));
  return {
    file: f, kind, g, headerStatus, bodyStatus, tests, steps, compileErrors, compileErrorsTruncated: /…\(truncated\)…/.test(t),
    outputTruncated: /…\(truncated\)…/.test(t), insertedProse: inserted, runAt: f.instants[0] || null,
    newTest: plain(g['New regression test']) || null,
  };
}

function parseVerdict(f: FileRec): VerdictRec {
  const t = f.text || '';
  const g = glance(t);
  const decision = extractField(t, 'Decision');
  if (!decision) fail(f, 'No Decision cell.');
  const breakdownT = findTable(section(t, /^Score breakdown/), 'Check', 'Verdict', 'Points');
  const breakdown = rowsOf(breakdownT, 'Check', 'Verdict', 'Points').filter((r) => !/total/i.test(plain(r.Check) || '')).map((r) => {
    const pm = /(\d+)\s*\/\s*(\d+)/.exec(r.Points || '');
    return { check: plain(r.Check) || '', verdict: plain(r.Verdict) || '', points: pm ? Number(pm[1]) : null, max: pm ? Number(pm[2]) : null };
  });
  const gateT = findTable(section(t, /^Score breakdown/), 'Hard gate', 'Result');
  const hardGateTable = rowsOf(gateT, 'Hard gate', 'Result').map((r) => ({ name: plain(r['Hard gate']) || '', triggered: /triggered/i.test(r.Result || '') }));
  const upT = findTable(section(t, /^Upstream reports/), 'Check', 'Verdict', 'Report');
  const upstream = rowsOf(upT, 'Check', 'Verdict', 'Report').map((r) => ({ check: plain(r.Check) || '', verdict: plain(r.Verdict) || '', report: linkTargets(r.Report)[0] ? repoPathFromLink(linkTargets(r.Report)[0]) : null }));
  const narrativeRaw = section(t, /^Narrative/);
  const narrative = narrativeRaw ? plain(narrativeRaw.split(/\n---\n/)[0].replace(/\r?\n/g, ' ')) : null;
  const ovSection = section(t, /override/i);
  const thr = g.Threshold || '';
  const gatesCell = plain(g['Hard gates triggered']) || '';
  return {
    file: f, g, decision, score: leadingNumber(g.Score), threshold: leadingNumber(thr), severityLabel: (/\(([^)]+) severity\)/i.exec(thr) || [])[1] || null,
    cwe: plain(g.CWE) || null,
    hardGatesTriggered: gatesCell && !/^none$/i.test(gatesCell) ? gatesCell.split(/\s*,\s*/).filter(Boolean) : [],
    breakdown, hardGateTable, upstream, narrative,
    override: { applied: Boolean(ovSection && /applied/i.test(ovSection) && !/not applied|applied:\s*false/i.test(ovSection)), reason: ovSection ? firstParagraph(ovSection) : null },
    computedAt: (ISO.exec((/Score computed ([^._]+(?:\.\d+Z)?)/.exec(t) || [])[1] || '') || [])[0] || f.instants[0] || null,
  };
}

function parsePr(f: FileRec): PrRec {
  const t = f.text || '';
  return { file: f, blockedBanner: /BLOCKED — do not open this PR/i.test(t), title: h1Or(t) };
}

function h1Or(t: string): string | null {
  const m = /^#\s+(.+)$/m.exec(t);
  return m ? plain(m[1]) : null;
}

function parseAudit(f: FileRec): AuditRec {
  const t = f.text || '';
  const tbl = findTable(section(t, /^Chain of custody/), 'Stage', 'Agent', 'Date');
  const chain = (tbl ? tbl.rows : []).map((r) => ({
    stage: plain(r[0]) || '', agent: plain(r[1]) || '', date: plain(r[2]) || '', finding: plain(r[3]) || '',
    source: linkTargets(r[4] || '')[0] ? repoPathFromLink(linkTargets(r[4] || '')[0]) : null,
  }));
  if (!tbl) fail(f, 'No chain-of-custody table.');
  return { file: f, chain };
}

function parseIndex(f: FileRec): IndexRec {
  const t = f.text || '';
  const after = t.split(/AUTO-GENERATED TABLE[^\n]*\n/)[1] || '';
  const tbl = tables(after)[0];
  const rows = tbl ? tbl.rows.map((r) => Object.fromEntries(tbl.headers.map((h, i) => [h, plain(r[i]) || '']))) : [];
  return { file: f, rows };
}

function recordedGraphCounts(text: string | null): Record<string, number> | null {
  if (!text) return null;
  const sec = section(text, /Live Graph Snapshot/);
  if (!sec) return null;
  const out: Record<string, number> = {};
  for (const m of sec.matchAll(/^-\s+([A-Za-z_]+):\s+(\d+)/gm)) out[m[1]] = Number(m[2]);
  return Object.keys(out).length ? out : null;
}

export function loadEvidence(ws: Workspace): Evidence {
  const ev: Evidence = {
    files: [], byPath: new Map(), rca: new Map(), blast: new Map(), plan: new Map(), fix: new Map(), diff: new Map(),
    verify: new Map(), gate: new Map(), verdict: new Map(), pr: new Map(), audit: new Map(), indexes: {},
    architecture: { doc: null, functionRef: null, recordedGraphCounts: null }, issueIds: new Set(),
  };
  const all = ws.walk(EVIDENCE_ROOT, 2000);
  for (const wf of all) {
    const f = fileRec(ws, wf.path);
    if (!f) continue;
    ev.files.push(f);
    ev.byPath.set(f.path, f);
    if (f.issueId) ev.issueIds.add(f.issueId);
    const id = f.issueId || '';
    try {
      switch (f.type) {
        case 'root_cause_report': ev.rca.set(id, parseRca(f)); break;
        case 'blast_radius_report': ev.blast.set(id, parseBlast(f)); break;
        case 'fix_plan': ev.plan.set(id, parsePlan(f)); break;
        case 'fix_report': ev.fix.set(id, parseFix(f)); break;
        case 'fix_diff': ev.diff.set(id, parseDiff(f)); break;
        case 'rescan_report': case 'redteam_report': case 'behavior_report': {
          const check = f.type.replace('_report', '') as VerifyRec['check'];
          const m = ev.verify.get(id) || {};
          m[check] = parseVerify(f, check);
          ev.verify.set(id, m);
          break;
        }
        case 'qa_report': case 'build_report': {
          const kind = f.type === 'qa_report' ? 'qa' : 'build';
          const m = ev.gate.get(id) || {};
          m[kind] = parseGate(f, kind);
          ev.gate.set(id, m);
          break;
        }
        case 'verdict': ev.verdict.set(id, parseVerdict(f)); break;
        case 'pr_content': ev.pr.set(id, parsePr(f)); break;
        case 'audit_trail': ev.audit.set(id, parseAudit(f)); break;
        case 'stage_index': {
          const dir = f.path.split('/')[2];
          const key = ({ '04-remediation': 'remediation', '05-verify': 'verify', '06-test-gate': 'testgate', '07-ship': 'ship' } as Record<string, 'remediation' | 'verify' | 'testgate' | 'ship'>)[dir];
          if (key) ev.indexes[key] = parseIndex(f);
          break;
        }
        case 'architecture_doc':
          ev.architecture.doc = f;
          ev.architecture.recordedGraphCounts = recordedGraphCounts(f.text);
          break;
        case 'function_reference': ev.architecture.functionRef = f; break;
        default: break;
      }
    } catch (err) {
      fail(f, `Unexpected structure: ${(err as Error).message}`);
    }
  }
  return ev;
}

export function evidenceRef(f: FileRec | null | undefined, generatedAt?: string | null): { path: string; type: string; sha256: string | null; generatedAt: string | null; provenance: Provenance } | null {
  if (!f) return null;
  return { path: f.path, type: f.type, sha256: f.sha256, generatedAt: generatedAt ?? f.instants[0] ?? f.generatedDate, provenance: f.provenance };
}
