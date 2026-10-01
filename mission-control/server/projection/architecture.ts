/**
 * Application architecture view (the second graph): what part of the target application MARS is
 * reasoning about. Built from the code model (Neo4j is optional and not required); always bounded.
 */
import type { ArchEdge, ArchNode, ArchitectureView, IssueDetail } from '../../shared/types.js';
import type { CodeModel } from '../sources/codemodel.js';

export function architectureView(
  cm: CodeModel,
  issue: IssueDetail | null,
  opts: { focus?: string | null; depth?: number; limit?: number },
  recorded: Record<string, number> | null,
  neo4jConfigured: boolean,
): ArchitectureView {
  const limit = Math.min(opts.limit ?? 160, 400);
  const depth = Math.min(Math.max(opts.depth ?? 2, 1), 4);
  const base = {
    source: 'code-model' as const,
    generatedAt: cm.generatedAt,
    neo4j: { configured: neo4jConfigured, note: neo4jConfigured ? 'Neo4j credentials are configured for Graph Forge; Mission Control reads the code model, which the graph is loaded from.' : 'Neo4j is not configured in this workspace (no 01c-graph-forge/.env). Views use the parsed code model (artifacts.json) — the same data Graph Forge loads.' },
    counts: cm.counts,
    recordedGraphCounts: recorded,
    issueId: issue?.id || null,
  };
  if (!cm.available) return { ...base, focus: null, overlay: {}, nodes: [], edges: [], truncated: false, note: 'No code model found (.claude/.pipeline-context/artifacts.json). Run 01_architect (Code Cartographer) to produce it.' };

  const overlay: Record<string, string> = {};
  const focusIds = new Set<string>();
  if (opts.focus && cm.nodes.has(opts.focus)) focusIds.add(opts.focus);
  if (issue) {
    for (const r of issue.codeRefs) {
      if (!r.resolved) continue;
      if (r.kind === 'method') {
        focusIds.add(r.id);
        overlay[r.id] = overlay[r.id] === 'defect' ? 'defect' : (r.role === 'defect' ? 'defect' : r.role === 'call path' ? (overlay[r.id] || 'path') : (overlay[r.id] || 'reported'));
      }
      if (r.kind === 'endpoint') {
        overlay[r.id] = r.role.includes('broken') ? 'broken' : r.role.includes('degraded') ? 'degraded' : 'endpoint';
        focusIds.add(r.id);
      }
      if (r.kind === 'module') overlay[r.id] = r.role.includes('broken') ? 'broken' : r.role.includes('degraded') ? 'degraded' : r.role.includes('risk') ? 'at_risk' : 'module';
      if (r.kind === 'file') {
        const mark = r.role === 'changed by patch' ? 'changed' : 'planned_unchanged';
        for (const tid of cm.typesByFile.get(r.id) || []) {
          for (const mid of cm.methodsByType.get(tid) || []) {
            if (focusIds.has(mid) && overlay[mid] !== 'defect') overlay[mid] = mark;
          }
          overlay[tid] = mark;
        }
      }
    }
  }

  if (!focusIds.size) return overviewGraph(cm, base, limit);

  // Ego network over call edges, both directions, bounded by depth and limit.
  const callsOut = new Map<string, string[]>();
  const callsIn = new Map<string, string[]>();
  const exposes = new Map<string, string[]>();
  for (const e of cm.edges) {
    if (e.kind === 'calls') {
      callsOut.set(e.from, [...(callsOut.get(e.from) || []), e.to]);
      callsIn.set(e.to, [...(callsIn.get(e.to) || []), e.from]);
    }
    if (e.kind === 'exposes') exposes.set(e.to, [...(exposes.get(e.to) || []), e.from]);
  }
  const keep = new Set<string>(focusIds);
  let frontier = Array.from(focusIds).filter((id) => cm.nodes.get(id)?.kind === 'method');
  for (const id of focusIds) if (cm.nodes.get(id)?.kind === 'endpoint') for (const e of cm.edges) if (e.kind === 'exposes' && e.from === id) frontier.push(e.to);
  for (let d = 0; d < depth && frontier.length; d += 1) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const n of [...(callsOut.get(id) || []), ...(callsIn.get(id) || [])]) {
        if (keep.size >= limit) break;
        if (!keep.has(n)) {
          keep.add(n);
          next.push(n);
        }
      }
    }
    frontier = next;
  }
  for (const id of Array.from(keep)) for (const ep of exposes.get(id) || []) keep.add(ep);
  const modules = new Set<string>();
  for (const id of keep) {
    const n = cm.nodes.get(id);
    if (n?.module) modules.add(`module:${n.module}`);
  }
  for (const k of Object.keys(overlay)) if (k.startsWith('module:') && cm.nodes.has(k)) modules.add(k);
  for (const m of modules) keep.add(m);

  const nodes: ArchNode[] = Array.from(keep).map((id) => cm.nodes.get(id)).filter((n): n is ArchNode => Boolean(n) && n!.kind !== 'type');
  const ids = new Set(nodes.map((n) => n.id));
  const edges: ArchEdge[] = [];
  for (const e of cm.edges) if ((e.kind === 'calls' || e.kind === 'exposes') && ids.has(e.from) && ids.has(e.to)) edges.push(e);
  for (const n of nodes) {
    if (n.kind === 'endpoint' && n.module && ids.has(`module:${n.module}`)) edges.push({ from: `module:${n.module}`, to: n.id, kind: 'contains' });
  }
  // Cross-service HTTP dependency the call graph cannot see, taken from the blast-radius evidence.
  if (issue?.blast) {
    for (const deg of issue.blast.servicesDegraded) for (const br of issue.blast.servicesBroken) {
      if (ids.has(`module:${deg}`) && ids.has(`module:${br}`)) edges.push({ from: `module:${deg}`, to: `module:${br}`, kind: 'http' });
    }
  }
  return { ...base, focus: Array.from(focusIds)[0] || null, overlay, nodes, edges, truncated: keep.size >= limit, note: issue ? 'Focus: the issue\'s defect site, call path and affected endpoints, resolved deterministically from evidence through the code model.' : null };
}

function overviewGraph(cm: CodeModel, base: Omit<ArchitectureView, 'focus' | 'overlay' | 'nodes' | 'edges' | 'truncated' | 'note'>, limit: number): ArchitectureView {
  const nodes: ArchNode[] = [];
  const edges: ArchEdge[] = [];
  for (const n of cm.nodes.values()) if (n.kind === 'module' || n.kind === 'endpoint' || (n.kind === 'type' && ['controller', 'service', 'repository'].includes(n.role || ''))) nodes.push(n);
  const ids = new Set(nodes.slice(0, limit).map((n) => n.id));
  const typeOfMethod = (mid: string) => mid.split('#')[0];
  const seen = new Set<string>();
  for (const e of cm.edges) {
    if (e.kind === 'contains' && ids.has(e.from) && ids.has(e.to)) edges.push(e);
    if (e.kind === 'exposes') {
      const t = typeOfMethod(e.to);
      if (ids.has(e.from) && ids.has(t)) edges.push({ from: t, to: e.from, kind: 'exposes' });
    }
    if (e.kind === 'calls') {
      const a = typeOfMethod(e.from);
      const b = typeOfMethod(e.to);
      const key = `${a}>${b}`;
      if (a !== b && ids.has(a) && ids.has(b) && !seen.has(key)) {
        seen.add(key);
        edges.push({ from: a, to: b, kind: 'calls' });
      }
    }
  }
  return { ...base, focus: null, overlay: {}, nodes: nodes.filter((n) => ids.has(n.id)), edges, truncated: nodes.length > limit, note: 'Overview: modules, controllers, services, repositories and REST endpoints. Select an issue to focus on its defect site.' };
}
