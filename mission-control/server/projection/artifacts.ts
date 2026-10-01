/** Evidence (artifact) list, per-issue lineage and safe content access. */
import type { ArtifactContent, ArtifactInfo, IntegrityFinding, LineageGraph, Provenance, StageId } from '../../shared/types.js';
import type { Evidence, FileRec } from '../sources/evidence.js';
import type { Workspace } from '../sources/workspace.js';
import type { DecisionRecord } from '../sources/decisions.js';

/** Which artifact types consume which (from each skill's documented inputs). */
const CONSUMES: Record<string, string[]> = {
  root_cause_report: ['issue_register', 'architecture_doc', 'function_reference'],
  blast_radius_report: ['root_cause_report', 'issue_register', 'architecture_doc', 'function_reference'],
  fix_plan: ['root_cause_report', 'blast_radius_report', 'issue_register'],
  decision: ['fix_plan'],
  fix_report: ['fix_plan', 'decision'],
  fix_diff: ['fix_plan', 'decision'],
  rescan_report: ['fix_report', 'fix_diff', 'root_cause_report', 'issue_register'],
  redteam_report: ['fix_report', 'fix_diff', 'fix_plan'],
  behavior_report: ['fix_report', 'fix_diff', 'fix_plan'],
  qa_report: ['fix_report', 'fix_diff'],
  build_report: ['fix_report', 'fix_diff'],
  verdict: ['rescan_report', 'redteam_report', 'behavior_report', 'qa_report', 'build_report'],
  pr_content: ['verdict'],
  audit_trail: ['verdict', 'root_cause_report', 'blast_radius_report', 'fix_plan', 'fix_report', 'fix_diff', 'rescan_report', 'redteam_report', 'behavior_report', 'qa_report', 'build_report', 'issue_register'],
};

const LABEL: Record<string, string> = {
  issue_register: 'Issue register row', architecture_doc: 'architecture.md', function_reference: 'function-reference.md', root_cause_report: 'Root cause', blast_radius_report: 'Blast radius',
  fix_plan: 'Fix plan', decision: 'Human decision', fix_report: 'Fix report', fix_diff: 'Patch (.diff)', rescan_report: 'Re-scan', redteam_report: 'Red-team', behavior_report: 'Behaviour guard',
  qa_report: 'QA gate', build_report: 'Build gate', verdict: 'Verdict', pr_content: 'PR content', audit_trail: 'Audit trail',
};

const STAGE_OF: Record<string, StageId> = {
  issue_register: 'intake', architecture_doc: 'architecture', function_reference: 'architecture', root_cause_report: 'rca', blast_radius_report: 'blast_radius', fix_plan: 'plan', decision: 'approval',
  fix_report: 'fix', fix_diff: 'fix', rescan_report: 'rescan', redteam_report: 'redteam', behavior_report: 'behavior', qa_report: 'qa', build_report: 'build', verdict: 'verdict', pr_content: 'writeup', audit_trail: 'writeup',
};

function fileFor(ev: Evidence, issueId: string, type: string): FileRec | null {
  if (type === 'issue_register') return ev.byPath.get('docs/agent_output/00-issues/issue-register.xlsx') || null;
  if (type === 'architecture_doc') return ev.architecture.doc;
  if (type === 'function_reference') return ev.architecture.functionRef;
  return ev.files.find((f) => f.issueId === issueId && f.type === type) || null;
}

export function lineage(ev: Evidence, issueId: string, decisions: DecisionRecord[], findings: IntegrityFinding[]): LineageGraph {
  const types = Object.keys(LABEL);
  const nodes: LineageGraph['nodes'] = types.map((t) => {
    const f = t === 'decision' ? null : fileFor(ev, issueId, t);
    const recs = decisions.filter((d) => d.issue_id === issueId);
    const plan = ev.plan.get(issueId);
    const exists = t === 'decision' ? Boolean(recs.length || (plan && /Approved|Rejected/.test(plan.status || ''))) : Boolean(f);
    const prov: Provenance = t === 'decision' ? 'human' : (f?.provenance || 'unknown');
    return {
      id: t,
      label: t === 'decision' ? (recs.length ? `Human decision (${recs.length} record${recs.length > 1 ? 's' : ''})` : plan && /Approved|Rejected/.test(plan.status || '') ? `${plan.status} — unattributed` : 'Human decision') : LABEL[t],
      type: t, stage: STAGE_OF[t] || null, provenance: prov, exists,
      findings: findings.filter((x) => x.issueId === issueId && (t === 'decision' ? x.stage === 'approval' : f && x.subjects.includes(f.path))).length,
    };
  });
  const edges: LineageGraph['edges'] = [];
  for (const [to, froms] of Object.entries(CONSUMES)) for (const from of froms) edges.push({ from, to, label: 'consumed by' });
  return { issueId, nodes, edges };
}

export function artifactList(ev: Evidence, findings: IntegrityFinding[]): ArtifactInfo[] {
  return ev.files.map((f) => {
    const consumers = Object.entries(CONSUMES).filter(([, froms]) => froms.includes(f.type)).map(([to]) => LABEL[to]);
    const inputs = (CONSUMES[f.type] || []).map((t) => LABEL[t]);
    return {
      path: f.path, type: f.type, issueId: f.issueId, stage: (STAGE_OF[f.type] || f.stage) as StageId | null, producer: f.producer, producerAgent: f.producerAgent,
      provenance: f.provenance, provenanceNote: f.provenanceNote, generatedAt: f.instants[0] || f.generatedDate, sha256: f.sha256, size: f.size,
      consumers, inputs, findings: findings.filter((x) => x.subjects.includes(f.path)).map((x) => x.id), parseOk: f.parseOk,
    };
  });
}

const ALLOWED = [
  /^docs\/agent_output\/[^\0]+$/,
  /^docs\/mission-control\/[^/]+\.md$/,
  /^\.claude\/agents\/[^/]+\.agent\.md$/,
  /^\.claude\/skills\/[0-9a-z-]+\/SKILL\.md$/,
  /^\.github\/skills\/[0-9a-z-]+\/SKILL\.md$/,
  /^\.claude\/skills\/[0-9a-z-]+\/(scoring\.json|ranking-weights\.json|catalog\/[^/]+\.json|knowledge\/[^/]+\.json|templates\/[^/]+\.json)$/,
  /^\.claude\/pipeline-contract\.md$/,
  /^\.claude\/README\.md$/,
  /^\.claude\/\.pipeline-context\/[^/]+\.json$/,
];

const MAX = 2 * 1024 * 1024;

export function artifactContent(ws: Workspace, rel: string): ArtifactContent | { error: string; status: number } {
  const clean = String(rel || '').replace(/\\/g, '/').replace(/^\/+/, '');
  if (!clean || clean.includes('..') || !ALLOWED.some((re) => re.test(clean))) return { error: 'This path is not served. Only MARS evidence and harness definitions are readable.', status: 403 };
  const buf = ws.readBuffer(clean);
  if (!buf) return { error: 'Not found.', status: 404 };
  const kind: ArtifactContent['kind'] = /\.md$/i.test(clean) ? 'markdown' : /\.diff$/i.test(clean) ? 'diff' : /\.json$/i.test(clean) ? 'json' : /\.xlsx$/i.test(clean) ? 'binary' : 'text';
  const sha = (ws.sha256(clean) as string);
  if (kind === 'binary') return { path: clean, kind, sha256: sha, size: buf.length, content: null, truncated: false };
  const truncated = buf.length > MAX;
  return { path: clean, kind, sha256: sha, size: buf.length, content: buf.subarray(0, MAX).toString('utf8'), truncated };
}
