import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearch } from '@tanstack/react-router';
import type { ColumnDef } from '@tanstack/react-table';
import { AlertTriangle } from 'lucide-react';
import type { ArtifactInfo, IntegrityFinding } from '../../shared/types';
import { useArtifactContent, useArtifacts } from '../api';
import { PageHeader } from '../components/shell';
import { DiffView, JsonView, SafeMarkdown } from '../components/content';
import { DataTable, EmptyState, ErrorState, HashChip, KV, Loading, Panel, Pill, ProvenanceBadge, Tabs, Time } from '../components/ui';

const SEV_ORDER = { critical: 0, major: 1, minor: 2, info: 3 } as const;

export function FindingList({ findings }: { findings: IntegrityFinding[] }) {
  if (!findings.length) return <EmptyState title="No integrity findings" body="Every representation of the same fact agrees." />;
  return (
    <ul className="divide-y divide-line">
      {[...findings].sort((a, b) => SEV_ORDER[a.severity] - SEV_ORDER[b.severity] || a.rule.localeCompare(b.rule)).map((f) => (
        <li key={f.id} className="px-3 py-2 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <Pill tone={f.severity === 'critical' ? 'fail' : f.severity === 'major' ? 'tool' : 'neutral'}>{f.severity}</Pill>
            <span className="mono text-xs text-muted">{f.rule}</span>
            {f.issueId && <Link to="/issues/$issueId" params={{ issueId: f.issueId }} search={{ tab: 'findings' }} className="mono text-xs">{f.issueId}</Link>}
            <span className="font-medium">{f.title}</span>
          </div>
          <div className="mt-0.5 text-xs text-muted">{f.ruleTitle}. {f.detail}</div>
          <div className="mt-0.5 flex flex-wrap gap-2">{f.subjects.filter(Boolean).map((s) => <Link key={s} to="/evidence/view" search={{ path: s }} className="mono text-[11px]">{s}</Link>)}</div>
        </li>
      ))}
    </ul>
  );
}

export function EvidencePage() {
  const search = useSearch({ strict: false }) as { tab?: 'artifacts' | 'integrity' };
  const nav = useNavigate();
  const tab = search.tab || 'artifacts';
  const q = useArtifacts();
  const [text, setText] = useState('');
  const [sev, setSev] = useState<'all' | 'critical' | 'major' | 'minor' | 'info'>('all');
  const rows = useMemo(() => (q.data?.artifacts || []).filter((a) => !text || a.path.toLowerCase().includes(text.toLowerCase()) || a.type.includes(text.toLowerCase())), [q.data, text]);
  const cols = useMemo<ColumnDef<ArtifactInfo, unknown>[]>(() => [
    { id: 'path', header: 'Artifact', accessorKey: 'path', cell: (c) => <Link to="/evidence/view" search={{ path: c.row.original.path }} className="mono text-xs" onClick={(e) => e.stopPropagation()}>{c.row.original.path.replace(/^docs\/agent_output\//, '')}</Link> },
    { id: 'type', header: 'Type', accessorKey: 'type', cell: (c) => <span className="text-xs">{c.row.original.type}</span> },
    { id: 'issue', header: 'Issue', accessorFn: (r) => r.issueId || '', cell: (c) => <span className="mono text-xs">{c.row.original.issueId || '—'}</span> },
    { id: 'producer', header: 'Producer', accessorFn: (r) => r.producerAgent || '', cell: (c) => <span className="text-xs">{c.row.original.producerAgent || '—'}</span> },
    { id: 'prov', header: 'Provenance', accessorKey: 'provenance', cell: (c) => <ProvenanceBadge p={c.row.original.provenance} /> },
    { id: 'gen', header: 'Generated', accessorFn: (r) => r.generatedAt || '', cell: (c) => <Time iso={c.row.original.generatedAt} source="reconstructed" /> },
    { id: 'find', header: 'Findings', accessorFn: (r) => r.findings.length, cell: (c) => (c.row.original.findings.length ? <span className="inline-flex items-center gap-1 text-xs text-fail"><AlertTriangle size={12} />{c.row.original.findings.length}</span> : !c.row.original.parseOk ? <span className="text-xs text-tool">unparsed</span> : <span className="text-xs text-subtle">0</span>) },
  ], []);
  if (q.isLoading) return <Loading rows={8} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const findings = q.data.findings.filter((f) => sev === 'all' || f.severity === sev);
  return (
    <div>
      <PageHeader title="Evidence" subtitle="Every durable artifact MARS wrote, its hash, its producer, and whether its contents agree with the other artifacts that describe the same fact. Evidence is authority; events are witnesses." />
      <div className="px-4 sm:px-6"><Tabs label="Evidence views" value={tab} onChange={(t) => nav({ to: '/evidence', search: { tab: t }, replace: true })} tabs={[{ id: 'artifacts', label: 'Artifacts', count: q.data.artifacts.length }, { id: 'integrity', label: 'Integrity findings', count: q.data.findings.length, warn: q.data.findings.some((f) => f.severity === 'critical') }]} /></div>
      <div className="space-y-3 p-4 sm:p-6" role="tabpanel">
        {tab === 'artifacts' ? (
          <>
            <input type="search" aria-label="Filter artifacts" value={text} onChange={(e) => setText(e.target.value)} placeholder="Filter by path or type" className="h-8 w-72 max-w-full rounded border border-line bg-surface-2 px-2 text-sm" />
            <Panel><DataTable label="Artifacts" dense data={rows} columns={cols} rowKey={(r) => r.path} onRowClick={(r) => nav({ to: '/evidence/view', search: { path: r.path } })} initialSort={[{ id: 'path', desc: false }]} empty={<EmptyState title="No artifacts" body="docs/agent_output/ is empty — no MARS stage has run in this workspace yet." />} /></Panel>
          </>
        ) : (
          <>
            <div role="group" aria-label="Severity" className="flex flex-wrap gap-1">{(['all', 'critical', 'major', 'minor', 'info'] as const).map((s) => <button key={s} type="button" aria-pressed={sev === s} onClick={() => setSev(s)} className={`h-7 rounded border px-2 text-xs ${sev === s ? 'border-accent bg-surface-3 font-semibold' : 'border-line'}`}>{s} ({s === 'all' ? q.data.findings.length : q.data.findings.filter((f) => f.severity === s).length})</button>)}</div>
            <Panel subtitle="Contradictions between two representations of the same fact (report header vs its own table, verdict vs replay, claimed files vs diff, …). Mission Control flags them; it never edits evidence."><FindingList findings={findings} /></Panel>
          </>
        )}
      </div>
    </div>
  );
}

export function ArtifactPage() {
  const { path } = useSearch({ strict: false }) as { path?: string };
  const q = useArtifactContent(path);
  const list = useArtifacts();
  const [raw, setRaw] = useState(false);
  if (!path) return <div className="p-6"><EmptyState title="No artifact selected" body={<Link to="/evidence">Browse evidence</Link>} /></div>;
  const meta = list.data?.artifacts.find((a) => a.path === path);
  const fnd = (list.data?.findings || []).filter((f) => f.subjects.includes(path));
  return (
    <div>
      <PageHeader crumbs={<><Link to="/evidence">Evidence</Link> / {path.split('/').pop()}</>} title={<span className="mono break-all text-base">{path}</span>}
        actions={q.data && q.data.kind === 'markdown' ? <button type="button" onClick={() => setRaw(!raw)} aria-pressed={raw} className="h-8 rounded border border-line px-2 text-xs">{raw ? 'Rendered' : 'Raw source'}</button> : null} />
      <div className="grid gap-4 p-4 sm:p-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <Panel>
          <div className="p-4">
            {q.isLoading ? <Loading /> : q.error ? (
              (q.error as { status?: number }).status === 404 ? <EmptyState title="Artifact missing" body={<>The file <span className="mono">{path}</span> does not exist. A stage that should have written it may not have run, or it was removed. Downstream stages that depend on it are shown as blocked upstream.</>} /> : <ErrorState error={q.error} retry={() => q.refetch()} />
            ) : q.data ? (
              q.data.kind === 'binary' ? <EmptyState title="Binary file" body={`${q.data.size} bytes. Not rendered; verify it by its hash.`} />
                : q.data.content == null ? <EmptyState title="Empty" />
                : q.data.kind === 'markdown' && !raw ? <SafeMarkdown text={q.data.content} basePath={path} />
                : q.data.kind === 'diff' ? <DiffView text={q.data.content} />
                : q.data.kind === 'json' ? <JsonView text={q.data.content} />
                : <pre className="mono overflow-auto whitespace-pre-wrap text-xs">{q.data.content}</pre>
            ) : null}
            {q.data?.truncated && <p className="mt-2 text-xs text-tool">Truncated at 2 MB for display; the hash covers the whole file.</p>}
          </div>
        </Panel>
        <div className="space-y-4">
          <Panel title="Metadata">
            <div className="p-3 text-sm">
              <KV items={[
                { k: 'SHA-256', v: <HashChip sha={q.data?.sha256 || meta?.sha256} len={16} /> },
                { k: 'Size', v: q.data ? `${q.data.size} bytes` : '—' },
                { k: 'Type', v: meta?.type || '—' },
                { k: 'Issue', v: meta?.issueId ? <Link to="/issues/$issueId" params={{ issueId: meta.issueId }} className="mono">{meta.issueId}</Link> : '—' },
                { k: 'Producer', v: meta?.producer || '—' },
                { k: 'Provenance', v: meta ? <span className="flex flex-col gap-1"><ProvenanceBadge p={meta.provenance} />{meta.provenanceNote && <span className="text-xs text-muted">{meta.provenanceNote}</span>}</span> : '—' },
                { k: 'Generated', v: <Time iso={meta?.generatedAt} source="reconstructed" /> },
                { k: 'Parsed', v: meta ? (meta.parseOk ? 'yes' : <span className="text-tool">no — the expected fields were not found; shown as raw content</span>) : '—' },
                { k: 'Expected readers', v: meta?.consumers.length ? <span>{meta.consumers.join(', ')} <span className="text-xs text-subtle">(from the skill contracts; not observed)</span></span> : '—' },
              ]} />
            </div>
          </Panel>
          <Panel title={`Findings on this artifact (${fnd.length})`}><FindingList findings={fnd} /></Panel>
        </div>
      </div>
    </div>
  );
}
