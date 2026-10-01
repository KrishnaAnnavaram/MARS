/**
 * Code model from .claude/.pipeline-context/artifacts.json (Code Cartographer output) and the
 * semantic layer (descriptions.json). IDs follow Graph Forge's conventions so that Mission Control and
 * Neo4j agree: Type = FQN, Method = `TypeId#name(paramTypes)`, Endpoint = `METHOD /path`, Module = name.
 * The call graph is resolved the way Graph Forge does it (best effort, name-based).
 */
import type { ArchEdge, ArchNode } from '../../shared/types.js';
import type { Workspace } from './workspace.js';

interface RawAnnotation { name: string; args?: Record<string, unknown> }
interface RawMethod { name: string; returnType?: string; params?: { type: string; name: string }[]; annotations?: RawAnnotation[]; calls?: { name: string; receiverKind?: string; receiverName?: string }[]; startLine?: number; endLine?: number }
interface RawType { id: string; name: string; kind: string; module: string; package: string; file: string; startLine?: number; endLine?: number; annotations?: RawAnnotation[]; extends?: string | string[] | null; implements?: string[]; fields?: { name: string; type: string }[]; methods?: RawMethod[] }
interface RawArtifacts { generatedAt?: string; modules?: { name: string; path: string }[]; types?: RawType[] }
interface Description { kind: string; id: string; summary?: string; author?: string; confidence?: string; criticality?: string }

export interface CodeModel {
  available: boolean;
  generatedAt: string | null;
  nodes: Map<string, ArchNode>;
  edges: ArchEdge[];
  typesBySimple: Map<string, string[]>;
  methodsByType: Map<string, string[]>;
  methodByFileLine: (file: string, line: number) => string | null;
  typesByFile: Map<string, string[]>;
  endpointsByHandler: Map<string, string[]>;
  counts: { modules: number; types: number; methods: number; endpoints: number; calls: number };
  descriptionsAuthor: string | null;
}

const MAPPINGS: Record<string, string> = { GetMapping: 'GET', PostMapping: 'POST', PutMapping: 'PUT', DeleteMapping: 'DELETE', PatchMapping: 'PATCH' };

function annValue(a: RawAnnotation | undefined): string {
  if (!a || !a.args) return '';
  const v = a.args.value ?? a.args.path ?? '';
  if (Array.isArray(v)) return String(v[0] ?? '');
  return String(v);
}

function joinPath(...parts: string[]): string {
  const p = parts.map((x) => String(x || '').replace(/^\/+|\/+$/g, '')).filter(Boolean).join('/');
  return `/${p}`;
}

export function methodId(typeId: string, m: RawMethod): string {
  return `${typeId}#${m.name}(${(m.params || []).map((p) => p.type).join(',')})`;
}

export function loadCodeModel(ws: Workspace): CodeModel {
  const raw = ws.readJson<RawArtifacts>('.claude/.pipeline-context/artifacts.json');
  const desc = ws.readJson<{ author?: string; nodes?: Description[] }>('.claude/.pipeline-context/context/descriptions.json');
  const descById = new Map<string, Description>();
  for (const d of desc?.nodes || []) descById.set(d.id, d);
  const ctxOf = (id: string) => {
    const d = descById.get(id);
    return d ? { summary: d.summary || null, author: d.author || desc?.author || null, confidence: d.confidence || null } : null;
  };

  const nodes = new Map<string, ArchNode>();
  const edges: ArchEdge[] = [];
  const typesBySimple = new Map<string, string[]>();
  const methodsByType = new Map<string, string[]>();
  const typesByFile = new Map<string, string[]>();
  const endpointsByHandler = new Map<string, string[]>();
  const methodRanges: { id: string; file: string; start: number; end: number }[] = [];
  const empty: CodeModel = {
    available: false, generatedAt: null, nodes, edges, typesBySimple, methodsByType, methodByFileLine: () => null, typesByFile, endpointsByHandler,
    counts: { modules: 0, types: 0, methods: 0, endpoints: 0, calls: 0 }, descriptionsAuthor: desc?.author || null,
  };
  if (!raw || !Array.isArray(raw.types)) return empty;

  for (const m of raw.modules || []) nodes.set(`module:${m.name}`, { id: `module:${m.name}`, kind: 'module', label: m.name, module: m.name, file: m.path, lines: null, role: null, ctx: ctxOf(m.name) });
  const types = raw.types;
  for (const t of types) {
    nodes.set(t.id, { id: t.id, kind: 'type', label: t.name, module: t.module, file: t.file, lines: t.startLine ? [t.startLine, t.endLine || t.startLine] : null, role: roleOf(t), ctx: ctxOf(t.id) });
    typesBySimple.set(t.name, [...(typesBySimple.get(t.name) || []), t.id]);
    typesByFile.set(t.file, [...(typesByFile.get(t.file) || []), t.id]);
    if (nodes.has(`module:${t.module}`)) edges.push({ from: `module:${t.module}`, to: t.id, kind: 'contains' });
    const base = annValue((t.annotations || []).find((a) => a.name === 'RequestMapping'));
    const isController = (t.annotations || []).some((a) => a.name === 'RestController' || a.name === 'Controller');
    for (const m of t.methods || []) {
      const mid = methodId(t.id, m);
      nodes.set(mid, { id: mid, kind: 'method', label: `${t.name}.${m.name}()`, module: t.module, file: t.file, lines: m.startLine ? [m.startLine, m.endLine || m.startLine] : null, role: null, ctx: ctxOf(mid) });
      methodsByType.set(t.id, [...(methodsByType.get(t.id) || []), mid]);
      edges.push({ from: t.id, to: mid, kind: 'has_method' });
      if (m.startLine) methodRanges.push({ id: mid, file: t.file, start: m.startLine, end: m.endLine || m.startLine });
      if (isController) {
        for (const a of m.annotations || []) {
          let verb = MAPPINGS[a.name];
          if (!verb && a.name === 'RequestMapping') verb = String((a.args && (a.args.method as string)) || 'GET').replace(/^.*\./, '');
          if (!verb) continue;
          const eid = `${verb} ${joinPath(base, annValue(a))}`;
          if (!nodes.has(eid)) nodes.set(eid, { id: eid, kind: 'endpoint', label: eid, module: t.module, file: t.file, lines: null, role: 'endpoint', ctx: ctxOf(eid) });
          edges.push({ from: eid, to: mid, kind: 'exposes' });
          endpointsByHandler.set(mid, [...(endpointsByHandler.get(mid) || []), eid]);
        }
      }
    }
  }

  // Call graph: same-type calls, field-receiver calls (including interface → implementation), static type calls.
  const typeById = new Map(types.map((t) => [t.id, t]));
  const implsOf = (simple: string): string[] => types.filter((t) => (t.implements || []).some((i) => i.replace(/<.*$/, '') === simple)).map((t) => t.id);
  const resolveTypes = (simple: string, module: string): string[] => {
    const cands = typesBySimple.get(simple.replace(/<.*$/, '')) || [];
    const same = cands.filter((c) => typeById.get(c)?.module === module);
    const chosen = same.length ? same : cands;
    const withImpls = new Set(chosen);
    for (const c of chosen) for (const impl of implsOf(typeById.get(c)!.name)) if (typeById.get(impl)?.module === module || !same.length) withImpls.add(impl);
    return Array.from(withImpls);
  };
  let calls = 0;
  const seen = new Set<string>();
  for (const t of types) {
    for (const m of t.methods || []) {
      const from = methodId(t.id, m);
      for (const c of m.calls || []) {
        let targets: string[] = [];
        if (!c.receiverName || c.receiverKind === 'this' || c.receiverKind === 'none') targets = [t.id];
        else {
          const field = (t.fields || []).find((f) => f.name === c.receiverName);
          if (field) targets = resolveTypes(field.type, t.module);
          else if (typesBySimple.has(c.receiverName)) targets = resolveTypes(c.receiverName, t.module);
        }
        for (const tid of targets) {
          for (const mid of methodsByType.get(tid) || []) {
            if (!mid.includes(`#${c.name}(`)) continue;
            const key = `${from}>${mid}`;
            if (seen.has(key) || mid === from) continue;
            seen.add(key);
            edges.push({ from, to: mid, kind: 'calls' });
            calls += 1;
          }
        }
      }
    }
  }

  const methodByFileLine = (file: string, line: number): string | null => {
    const f = file.replace(/\\/g, '/');
    const hits = methodRanges.filter((r) => (r.file === f || f.endsWith(r.file) || r.file.endsWith(f)) && line >= r.start && line <= r.end);
    hits.sort((a, b) => (a.end - a.start) - (b.end - b.start));
    return hits[0]?.id || null;
  };

  return {
    available: true,
    generatedAt: raw.generatedAt || null,
    nodes, edges, typesBySimple, methodsByType, methodByFileLine, typesByFile, endpointsByHandler,
    counts: {
      modules: (raw.modules || []).length,
      types: types.length,
      methods: Array.from(nodes.values()).filter((n) => n.kind === 'method').length,
      endpoints: Array.from(nodes.values()).filter((n) => n.kind === 'endpoint').length,
      calls,
    },
    descriptionsAuthor: desc?.author || null,
  };
}

function roleOf(t: RawType): string | null {
  const names = (t.annotations || []).map((a) => a.name);
  if (names.includes('RestController') || names.includes('Controller')) return 'controller';
  if (names.includes('Service')) return 'service';
  if (names.includes('Repository') || /Repository$/.test(t.name)) return 'repository';
  if (names.includes('Configuration')) return 'configuration';
  if (names.includes('Document') || names.includes('Entity')) return 'entity';
  if (t.kind === 'interface') return 'interface';
  return null;
}

/** Resolves "EmployeeSearchRepository.searchEmployees" (or "...searchEmployees()") to method ids. */
export function resolveSymbol(cm: CodeModel, symbol: string): string[] {
  const s = symbol.replace(/\(\)$/, '').trim();
  const dot = s.lastIndexOf('.');
  if (dot < 0) return (cm.typesBySimple.get(s) || []);
  const typeName = s.slice(0, dot);
  const method = s.slice(dot + 1);
  const out: string[] = [];
  for (const tid of cm.typesBySimple.get(typeName.split('.').pop() || typeName) || []) {
    for (const mid of cm.methodsByType.get(tid) || []) if (mid.includes(`#${method}(`)) out.push(mid);
  }
  return out;
}
