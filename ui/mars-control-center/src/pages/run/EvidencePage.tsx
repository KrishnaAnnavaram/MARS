import { Link2, Search, ShieldCheck, ShieldX, X } from 'lucide-react';
import { useState } from 'react';
import { useEvidence } from '../../api/queries';
import type { EvidenceView } from '../../api/types';
import { ArtifactViewer } from '../../components/ArtifactViewer';
import { Button, EmptyState, KeyValues, QueryView, Table, Tag, td, th } from '../../components/ui';
import { useRunId } from '../../layout/RunLayout';
import { formatDateTime, formatTime } from '../../lib/format';

const PHASES = ['', 'KERNEL', 'BASELINE', 'SECURITY', 'MIGRATION', 'MUTATION', 'VALIDATION', 'DECISION'];
const PAGE = 100;

export function EvidencePage() {
  const runId = useRunId();
  const [kind, setKind] = useState('');
  const [phase, setPhase] = useState('');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<EvidenceView>();
  const [artifact, setArtifact] = useState<string>();
  const page = useEvidence(runId, { kind, phase, q: search, offset, limit: PAGE });

  return (
    <div className="flex h-full min-h-[500px]">
      <div className="min-w-0 flex-1 space-y-3 overflow-y-auto p-4">
        <QueryView query={page}>
          {(p) => (
            <>
              <div className={`flex items-start gap-2 rounded-md border p-2.5 ${p.chain_intact ? 'border-success/40 bg-success-soft' : 'border-danger/50 bg-danger-soft'}`}>
                {p.chain_intact ? <ShieldCheck aria-hidden className="mt-0.5 size-4 text-success" /> : <ShieldX aria-hidden className="mt-0.5 size-4 text-danger" />}
                <div>
                  <div className="font-medium text-strong">
                    {p.chain_intact ? 'Evidence hash chain intact' : 'Evidence hash chain BROKEN'} ({p.total} record(s) match)
                  </div>
                  <div className="text-[12px] text-muted">
                    Recomputed from provenance/evidence.jsonl now. The chain proves no record was altered or removed; it does not
                    authenticate who produced a record.
                  </div>
                  {p.chain_violations.map((v) => <div key={v} className="mono text-[11px] text-danger">{v}</div>)}
                </div>
              </div>
              <form className="flex flex-wrap items-center gap-2" onSubmit={(e) => { e.preventDefault(); setSearch(q); setOffset(0); }}>
                <select aria-label="Kind" value={kind} onChange={(e) => { setKind(e.target.value); setOffset(0); }}
                  className="h-7 rounded border border-border-strong bg-panel-2 px-1.5 text-[12px]">
                  <option value="">All kinds</option>
                  {Object.entries(p.by_kind).map(([k, n]) => <option key={k} value={k}>{k} ({n})</option>)}
                </select>
                <select aria-label="Run phase" value={phase} onChange={(e) => { setPhase(e.target.value); setOffset(0); }}
                  className="h-7 rounded border border-border-strong bg-panel-2 px-1.5 text-[12px]">
                  {PHASES.map((ph) => <option key={ph} value={ph}>{ph || 'All areas'}</option>)}
                </select>
                <label className="relative">
                  <Search aria-hidden className="absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-faint" />
                  <span className="sr-only">Search evidence</span>
                  <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Finding, proposal, file, text"
                    className="h-7 w-64 rounded border border-border-strong bg-panel-2 pl-7 pr-2 text-[12px]" />
                </label>
                <Button size="sm" type="submit">Filter</Button>
              </form>
              {p.evidence.length === 0 ? <EmptyState title="Evidence has not yet been produced, or none matches" /> : (
                <Table label="Evidence">
                  <thead><tr>{['#', 'Evidence ID', 'Time', 'Kind', 'Producer', 'Summary', 'Reliability', 'Artifact'].map((h) => <th key={h} className={th}>{h}</th>)}</tr></thead>
                  <tbody>
                    {p.evidence.map((e) => (
                      <tr key={e.evidence_id} className={`cursor-pointer hover:bg-panel-2 ${selected?.evidence_id === e.evidence_id ? 'bg-panel-3' : ''}`}
                        onClick={() => setSelected(e)}>
                        <td className={`${td} mono text-faint`}>{e.chain_index}</td>
                        <td className={`${td} mono text-[11px]`}>{e.evidence_id}</td>
                        <td className={`${td} mono text-[11px] text-muted`}>{formatTime(e.observed_at)}</td>
                        <td className={td}><Tag>{e.kind}</Tag></td>
                        <td className={`${td} text-[11px] text-muted`}>{e.producer}</td>
                        <td className={`${td} max-w-[480px]`}>{e.summary}</td>
                        <td className={`${td} text-[11px]`}>{e.reliability}</td>
                        <td className={`${td} mono text-[11px] text-primary`}>{e.artifact_ref ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              )}
              <div className="flex items-center gap-2 text-[12px] text-muted">
                <Button size="sm" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</Button>
                <span>{p.total ? `${p.offset + 1}–${p.offset + p.limit} of ${p.total}` : '0'}</span>
                <Button size="sm" disabled={p.offset + p.limit >= p.total} onClick={() => setOffset(offset + PAGE)}>Next</Button>
              </div>
            </>
          )}
        </QueryView>
      </div>
      {selected && (
        <aside className="w-[420px] shrink-0 overflow-y-auto border-l border-border bg-panel p-3" aria-label="Evidence details">
          <div className="mb-2 flex items-start justify-between">
            <div>
              <div className="mono text-[11px] text-faint">chain position {selected.chain_index}</div>
              <div className="mono font-semibold text-strong">{selected.evidence_id}</div>
            </div>
            <button type="button" onClick={() => setSelected(undefined)} className="rounded p-1 text-muted hover:text-text" aria-label="Close evidence details">
              <X aria-hidden className="size-4" />
            </button>
          </div>
          <KeyValues rows={[
            ['Kind', selected.kind],
            ['Summary', selected.summary],
            ['Observed', <span key="o" className="whitespace-pre-wrap">{selected.observed}</span>],
            ['Where', <span key="w" className="mono text-[11px]">{selected.where}</span>],
            ['Subjects', <span key="s" className="mono text-[11px]">{selected.subjects.join(', ') || '—'}</span>],
            ['Basis', selected.basis],
            ['Reliability', selected.reliability],
            ['Observed at', formatDateTime(selected.observed_at)],
            ['As of', selected.as_of],
            ['Producer', selected.producer],
            ['Artifact', selected.artifact_ref ? (
              <button key="a" type="button" className="mono inline-flex items-center gap-1 text-[11px] text-primary hover:underline"
                onClick={() => setArtifact(selected.artifact_ref)}>
                <Link2 aria-hidden className="size-3" />{selected.artifact_ref}
              </button>
            ) : undefined],
            ['Artifact SHA-256', <span key="h" className="mono break-all text-[11px]">{selected.artifact_sha256}</span>],
          ]} />
        </aside>
      )}
      <ArtifactViewer runId={runId} path={artifact && !artifact.endsWith('/') && artifact.includes('.') ? artifact : undefined}
        onClose={() => setArtifact(undefined)} />
    </div>
  );
}
