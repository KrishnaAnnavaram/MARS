import { useMemo } from 'react';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import type { ColumnDef } from '@tanstack/react-table';
import { useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import type { AgentInfo, HealthCheck, SkillInfo } from '../../shared/types';
import { STAGE_BY_ID } from '../../shared/stages';
import { getJson, useAgent, useHealth, useRegistry, useSkill } from '../api';
import { PageHeader } from '../components/shell';
import { SafeMarkdown } from '../components/content';
import { DataTable, EmptyState, ErrorState, HashChip, KV, Loading, Panel, Pill, StatusBadge, Tabs, Time, duration } from '../components/ui';

type Tab = 'agents' | 'skills' | 'health' | 'drift';

const HEALTH_STATE = { pass: 'passed', warn: 'inconclusive', fail: 'failed', unknown: 'unknown' } as const;

function Telemetry({ t }: { t: { runs?: number; executions?: number; lastRunAt?: string | null; lastAt?: string | null; failures: number; active: boolean } | null }) {
  if (!t || !(t.runs ?? t.executions)) return <span className="text-xs text-subtle">not observed yet</span>;
  return <span className="text-xs">{t.active && <Pill tone="run">active</Pill>} {t.runs ?? t.executions} observed{t.failures ? <span className="text-fail"> · {t.failures} failed</span> : ''} · last <Time iso={t.lastRunAt ?? t.lastAt} mode="rel" source="observed" /></span>;
}

export function HarnessPage() {
  const search = useSearch({ strict: false }) as { tab?: Tab };
  const nav = useNavigate();
  const tab = search.tab || 'agents';
  const q = useRegistry();
  const agentCols = useMemo<ColumnDef<AgentInfo, unknown>[]>(() => [
    { id: 'id', header: 'Agent', accessorKey: 'id', cell: (c) => <Link to="/harness/agents/$agentId" params={{ agentId: c.row.original.id }} className="mono font-semibold" onClick={(e) => e.stopPropagation()}>{c.row.original.id}</Link> },
    { id: 'stages', header: 'Stages', accessorFn: (r) => r.stages.join(','), cell: (c) => <span className="text-xs">{c.row.original.stages.map((s) => STAGE_BY_ID[s]?.short || s).join(', ')}</span> },
    { id: 'skills', header: 'Skills', accessorFn: (r) => r.skills.length, cell: (c) => <span className="mono text-xs">{c.row.original.skills.join(', ')}</span> },
    { id: 'tools', header: 'Tools', accessorFn: (r) => r.tools.join(','), cell: (c) => <span className="text-xs text-muted">{c.row.original.tools.join(', ')}</span> },
    { id: 'rt', header: 'Runtimes', enableSorting: false, cell: (c) => <span className="text-xs">{c.row.original.runtimes.claude ? 'Claude' : ''}{c.row.original.runtimes.github ? ' · Copilot' : ''}{c.row.original.runtimes.differs ? <span className="text-tool"> (differs)</span> : ''}</span> },
    { id: 'tel', header: 'Observed', enableSorting: false, cell: (c) => <Telemetry t={c.row.original.telemetry} /> },
  ], []);
  const skillCols = useMemo<ColumnDef<SkillInfo, unknown>[]>(() => [
    { id: 'id', header: 'Skill', accessorKey: 'id', cell: (c) => <Link to="/harness/skills/$skillId" params={{ skillId: c.row.original.id }} className="mono font-semibold" onClick={(e) => e.stopPropagation()}>{c.row.original.id}</Link> },
    { id: 'stage', header: 'Stage', accessorFn: (r) => r.stage || '', cell: (c) => <span className="text-xs">{c.row.original.stage ? STAGE_BY_ID[c.row.original.stage as keyof typeof STAGE_BY_ID]?.short || c.row.original.stage : '—'}</span> },
    { id: 'agents', header: 'Used by', accessorFn: (r) => r.agents.join(','), cell: (c) => <span className="mono text-xs">{c.row.original.agents.join(', ') || <span className="text-tool">no agent</span>}</span> },
    { id: 'scripts', header: 'Scripts', accessorFn: (r) => r.scripts.length, cell: (c) => <span className="tnum">{c.row.original.scripts.length}</span> },
    { id: 'deps', header: 'Needs', enableSorting: false, cell: (c) => { const d = c.row.original.dependencies; return <span className="text-xs">{[d.zeroDependency && 'none', d.neo4j && 'Neo4j', d.python && 'Python', d.llmApi && 'LLM API', ...d.npm].filter(Boolean).join(', ')}</span>; } },
    { id: 'tel', header: 'Observed', enableSorting: false, cell: (c) => <Telemetry t={c.row.original.telemetry} /> },
  ], []);
  if (q.isLoading) return <Loading rows={8} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const r = q.data;
  return (
    <div>
      <PageHeader title="Harness" subtitle={`The MARS harness as declared on disk: ${r.agents.length} agents, ${r.skills.length} skills, ${r.scripts.length} scripts. Contracts are read from agent and skill files; activity comes only from observed telemetry.`} />
      <div className="px-4 sm:px-6"><Tabs<Tab> label="Harness views" value={tab} onChange={(t) => nav({ to: '/harness', search: { tab: t }, replace: true })} tabs={[{ id: 'agents', label: 'Agents', count: r.agents.length }, { id: 'skills', label: 'Skills', count: r.skills.length }, { id: 'health', label: 'Health' }, { id: 'drift', label: 'Drift', count: r.drift.length, warn: r.drift.some((d) => d.severity === 'major') }]} /></div>
      <div className="p-4 sm:p-6" role="tabpanel">
        {tab === 'agents' && <Panel><DataTable label="Agents" data={r.agents} columns={agentCols} rowKey={(a) => a.id} onRowClick={(a) => nav({ to: '/harness/agents/$agentId', params: { agentId: a.id } })} /></Panel>}
        {tab === 'skills' && <Panel><DataTable label="Skills" data={r.skills} columns={skillCols} rowKey={(a) => a.id} onRowClick={(a) => nav({ to: '/harness/skills/$skillId', params: { skillId: a.id } })} /></Panel>}
        {tab === 'health' && <HealthPanel />}
        {tab === 'drift' && (
          <Panel subtitle="Places where the harness disagrees with itself: documentation vs files, Claude vs Copilot mirrors, contracts vs scripts.">
            {r.drift.length === 0 ? <EmptyState title="No drift detected" /> : <ul className="divide-y divide-line">{r.drift.map((d) => <li key={d.id} className="px-3 py-2 text-sm"><div className="flex items-center gap-2"><Pill tone={d.severity === 'major' ? 'tool' : 'neutral'}>{d.severity}</Pill><span className="font-medium">{d.title}</span></div><div className="mt-0.5 text-xs text-muted">{d.detail}</div></li>)}</ul>}
          </Panel>
        )}
      </div>
    </div>
  );
}

function HealthPanel() {
  const q = useHealth();
  const qc = useQueryClient();
  const refresh = async () => qc.setQueryData(['health'], await getJson<{ checks: HealthCheck[] }>('/api/v1/health?refresh=1'));
  if (q.isLoading) return <Loading rows={6} label="Running health checks…" />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  return (
    <Panel title="Preflight health" subtitle="Read-only checks of the environment MARS depends on. Nothing is installed or changed." actions={<button type="button" onClick={() => void refresh()} className="inline-flex h-7 items-center gap-1 rounded border border-line px-2 text-xs"><RefreshCw size={12} /> Re-run</button>}>
      <ul className="divide-y divide-line">
        {q.data.checks.map((c) => (
          <li key={c.id} className="px-3 py-2 text-sm">
            <div className="flex flex-wrap items-center gap-2"><StatusBadge state={HEALTH_STATE[c.status]} label={c.status} /><span className="font-medium">{c.label}</span><span className="text-muted">{c.summary}</span></div>
            {c.details.length > 0 && <ul className="mono mt-1 list-disc pl-5 text-[11px] text-muted">{c.details.slice(0, 12).map((d, i) => <li key={i}>{d}</li>)}</ul>}
            {c.affects.length > 0 && <div className="mt-0.5 text-xs text-subtle">Affects: {c.affects.join(', ')}</div>}
          </li>
        ))}
      </ul>
    </Panel>
  );
}

export function AgentPage() {
  const { agentId } = useParams({ strict: false }) as { agentId?: string };
  const q = useAgent(agentId);
  if (q.isLoading) return <Loading rows={6} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const { agent: a, skills, runs } = q.data;
  return (
    <div>
      <PageHeader crumbs={<><Link to="/harness">Harness</Link> / agents</>} title={<span className="mono">{a.id}</span>} subtitle={a.description} />
      <div className="grid gap-4 p-4 sm:p-6 xl:grid-cols-2">
        <Panel title="Contract">
          <div className="p-3 text-sm"><KV items={[
            { k: 'Definition', v: <Link to="/evidence/view" search={{ path: a.file }} className="mono text-xs">{a.file}</Link> },
            { k: 'Stages', v: a.stages.map((s) => STAGE_BY_ID[s]?.label || s).join(', ') || '—' },
            { k: 'Tools', v: a.tools.join(', ') },
            { k: 'Reads from', v: a.upstream.join(', ') || '—' },
            { k: 'Feeds', v: a.downstream.join(', ') || '—' },
            { k: 'Writes', v: <span className="mono text-xs">{a.outputs.join(', ') || '—'}</span> },
            { k: 'Runtimes', v: `${a.runtimes.claude ? 'Claude Code' : ''}${a.runtimes.github ? ' · Copilot mirror' : ''}${a.runtimes.differs ? ' (mirror differs)' : ''}` },
          ]} /></div>
        </Panel>
        <Panel title="Boundaries" subtitle="Rules this agent's contract states, and what (if anything) enforces them.">
          <ul className="divide-y divide-line">{a.constraints.map((c, i) => <li key={i} className="px-3 py-1.5 text-xs"><div>{c.text}</div><div className="mt-0.5 text-subtle">{c.enforcedBy ? `Enforced by ${c.enforcedBy}` : 'Not enforced by a script — relies on the agent following its instructions.'}</div>{c.contradicts && <div className="mt-0.5 text-tool">Contradicts: {c.contradicts}</div>}</li>)}</ul>
        </Panel>
        <Panel title={`Skills (${skills.length})`}>
          <ul className="divide-y divide-line">{skills.map((s) => <li key={s.id} className="px-3 py-1.5 text-sm"><Link to="/harness/skills/$skillId" params={{ skillId: s.id }} className="mono">{s.id}</Link> <span className="text-xs text-muted">{s.scripts.length} scripts</span></li>)}</ul>
        </Panel>
        <Panel title="Runs" subtitle={a.telemetry ? `${a.telemetry.runs} observed · ${a.telemetry.failures} failed · avg ${duration(a.telemetry.avgDurationMs)}` : 'No observed runs yet. Reconstructed runs are inferred from report timestamps.'}>
          {runs.length === 0 ? <EmptyState title="No runs" /> : <ul className="divide-y divide-line">{runs.slice(0, 20).map((r) => <li key={r.runId} className="flex flex-wrap items-center gap-2 px-3 py-1.5 text-sm"><Link to="/runs/$runId" params={{ runId: r.runId }}>{r.label}</Link><Pill tone={r.source === 'observed' ? 'run' : 'neutral'}>{r.source}</Pill><Time iso={r.startedAt} mode="rel" /></li>)}</ul>}
        </Panel>
      </div>
    </div>
  );
}

export function SkillPage() {
  const { skillId } = useParams({ strict: false }) as { skillId?: string };
  const q = useSkill(skillId);
  if (q.isLoading) return <Loading rows={6} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const { skill: s, skillMd, recent } = q.data;
  return (
    <div>
      <PageHeader crumbs={<><Link to="/harness" search={{ tab: 'skills' }}>Harness</Link> / skills</>} title={<span className="mono">{s.id}</span>} subtitle={s.description} />
      <div className="grid gap-4 p-4 sm:p-6 xl:grid-cols-[minmax(0,1fr)_380px]">
        <div className="space-y-4">
          <Panel title={`Scripts (${s.scripts.length})`} subtitle="Deterministic code this skill runs. Scripts decide facts; agents write judgement.">
            <ul className="divide-y divide-line">{s.scripts.map((x) => <li key={x.id} className="px-3 py-1.5 text-sm"><div className="flex flex-wrap items-center gap-2"><span className="mono">{x.name}</span><Pill>{x.role}</Pill></div>{x.usage && <div className="mono mt-0.5 text-[11px] text-muted">{x.usage}</div>}</li>)}</ul>
          </Panel>
          <Panel title="SKILL.md">
            <div className="p-4">{skillMd ? <SafeMarkdown text={skillMd} basePath={s.skillMdPath} /> : <EmptyState title="SKILL.md not readable" />}</div>
          </Panel>
        </div>
        <div className="space-y-4">
          <Panel title="Contract">
            <div className="p-3 text-sm"><KV items={[
              { k: 'Used by', v: s.agents.join(', ') || <span className="text-tool">no agent references this skill</span> },
              { k: 'Stage', v: s.stage ? (STAGE_BY_ID[s.stage as keyof typeof STAGE_BY_ID]?.label || s.stage) : '—' },
              { k: 'Argument', v: s.argumentHint || '—' },
              { k: 'Schemas', v: s.schemas.length ? <span className="mono text-xs">{s.schemas.join(', ')}</span> : '—' },
              { k: 'Policies', v: s.policies.length ? s.policies.map((p) => <div key={p.path} className="flex items-center gap-1"><span className="mono text-xs">{p.path.split('/').pop()}</span><HashChip sha={p.sha256} len={8} /></div>) : '—' },
              { k: 'Copilot mirror', v: s.mirror.exists ? (s.mirror.differs ? <span className="text-tool">SKILL.md differs</span> : <span>SKILL.md identical <span className="text-xs text-subtle">(scripts are compared under Health)</span></span>) : 'none' },
            ]} /></div>
          </Panel>
          <Panel title="Recent observed executions" subtitle={recent.length ? undefined : 'None observed yet.'}>
            {recent.length > 0 && <ol className="divide-y divide-line text-xs">{recent.slice().reverse().slice(0, 30).map((e) => <li key={e.event_id} className="flex flex-wrap gap-2 px-3 py-1"><span className="mono">{e.type}</span><span className="text-muted">{e.script_id || ''}</span><span className="ml-auto"><Time iso={e.time} mode="rel" source="observed" /></span></li>)}</ol>}
          </Panel>
        </div>
      </div>
    </div>
  );
}
