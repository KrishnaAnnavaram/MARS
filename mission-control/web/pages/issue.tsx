import { useState } from 'react';
import { Link, useNavigate, useParams, useSearch } from '@tanstack/react-router';
import { AlertTriangle, ArrowRight, Bot, Cog, FileDiff, UserRound } from 'lucide-react';
import type { IssueDetail, Lane, StageId, TimelineItem } from '../../shared/types';
import { STAGES, STAGE_BY_ID } from '../../shared/stages';
import { useIssue, useLineage, useArchitecture } from '../api';
import { PageHeader } from '../components/shell';
import { PipelineGraph, LineageView, ArchGraph, OVERLAY_STYLE } from '../components/graphs';
import { DiffView } from '../components/content';
import { EmptyState, ErrorState, FailureTag, HashChip, KV, Loading, ModifierIcons, Panel, Pill, ProvenanceBadge, SeverityBadge, StatusBadge, StatusGlyph, Tabs, Time } from '../components/ui';
import { FAILURE, STATE } from '../domain/status';

type TabId = 'overview' | 'verification' | 'plan' | 'patch' | 'diagnosis' | 'impact' | 'timeline' | 'evidence' | 'architecture' | 'findings';

function EvidenceLink({ path, sha }: { path: string | null | undefined; sha?: string | null }) {
  if (!path) return <span className="text-subtle">no evidence</span>;
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <Link to="/evidence/view" search={{ path }} className="mono break-all text-xs">{path.split('/').pop()}</Link>
      {sha && <HashChip sha={sha} len={8} />}
    </span>
  );
}

function LaneCard({ lane }: { lane: Lane }) {
  const failed = lane.state === 'failed' || lane.state === 'blocked';
  const s = STATE[lane.state];
  return (
    <article className={`rounded-md border bg-surface ${failed ? 'border-fail' : 'border-line'}`} aria-label={`${lane.label}: ${s.label}`}>
      <header className="flex flex-wrap items-center gap-2 border-b border-line px-3 py-2">
        <StatusGlyph state={lane.state} size={16} />
        <h3 className="text-sm font-semibold">{lane.label}</h3>
        <span className="mono text-xs">{lane.outcome || '—'}</span>
        {lane.failureClass && <FailureTag cls={lane.failureClass} verified={lane.causeVerified} />}
        <span className="ml-auto flex items-center gap-1 text-[11px] text-muted">
          {lane.decidedBy === 'script' ? <><Cog size={12} /> Decided by script</> : <><Bot size={12} /> AI judgement</>}
        </span>
      </header>
      <div className="space-y-2 px-3 py-2 text-sm">
        {lane.headline && <p className="text-muted">{lane.headline}</p>}
        <div className="text-[11px] text-subtle">{lane.decidedByNote}</div>
        {(lane.facts.length > 0 || lane.confidence) && <KV items={[...(lane.confidence ? [{ k: 'Confidence', v: `${lane.confidence} (declared by the agent)` }] : []), ...lane.facts.map((f) => ({ k: f.label, v: <span className="mono text-xs">{f.value}</span> }))]} />}
        {lane.headerStatus && lane.bodyStatus && lane.headerStatus !== lane.bodyStatus && (
          <div className="rounded border border-fail bg-fail-tint p-2 text-xs" role="note">
            <div className="flex items-center gap-1 font-semibold text-fail"><AlertTriangle size={13} /> Report contradicts itself</div>
            Header says <b>{lane.headerStatus}</b>; its own result table records <b>{lane.bodyStatus}</b>. The merge arbiter reads the header. Mission Control shows the exit-code result.
          </div>
        )}
        {lane.tests.length > 0 && (
          <table className="w-full text-xs"><thead><tr className="text-left text-muted"><th className="py-0.5">Test</th><th>Status</th><th>Exit</th></tr></thead>
            <tbody>{lane.tests.map((t, i) => <tr key={i} className="border-t border-line"><td className="mono py-0.5">{t.name}</td><td className={t.status === 'FAIL' ? 'font-semibold text-fail' : t.status === 'PASS' ? 'text-pass' : 'text-muted'}>{t.status}</td><td className="mono">{t.exitCode ?? '—'}</td></tr>)}</tbody></table>
        )}
        {lane.steps.length > 0 && (
          <table className="w-full text-xs"><thead><tr className="text-left text-muted"><th className="py-0.5">Module</th><th>Command</th><th>Exit</th></tr></thead>
            <tbody>{lane.steps.map((t, i) => <tr key={i} className="border-t border-line"><td className="mono py-0.5">{t.module}</td><td className="mono">{t.command}</td><td className={`mono ${t.exitCode ? 'font-semibold text-fail' : ''}`}>{t.exitCode ?? '—'}</td></tr>)}</tbody></table>
        )}
        {lane.compileErrors.length > 0 && (
          <details className="rounded border border-line bg-surface-2 p-2 text-xs">
            <summary className="cursor-pointer font-medium">{lane.compileErrors.length} compiler error(s){lane.compileErrorsTruncated ? ' (log truncated)' : ''} — {lane.compileErrors.filter((c) => c.inPatchedFile).length} in files the patch changed</summary>
            <ul className="mono mt-1 space-y-0.5">{lane.compileErrors.map((c, i) => <li key={i}>{c.file}:[{c.line},{c.column}] {c.message}{c.symbol ? ` — ${c.symbol}` : ''}{c.inPatchedFile ? <span className="ml-1 font-sans text-tool">(patched file)</span> : ''}</li>)}</ul>
            <p className="mt-1 font-sans text-muted">Error location alone does not establish the cause: an error inside a patched file can still be environmental (e.g. Lombok not generating <code>log</code>). Only a baseline build of the unpatched module would settle it, and MARS does not record one.</p>
          </details>
        )}
        {lane.aiClaims.length > 0 && <ul className="space-y-1 text-xs">{lane.aiClaims.map((c, i) => <li key={i} className="flex gap-1.5"><Bot size={13} className="mt-0.5 shrink-0 text-subtle" /><span className="text-muted">{c}</span></li>)}</ul>}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
          <EvidenceLink path={lane.evidence?.path} sha={lane.evidence?.sha256} />
          {lane.time && <span>run <Time iso={lane.time} source="reconstructed" /></span>}
          {lane.findings.length > 0 && <Pill tone="fail">{lane.findings.length} finding(s)</Pill>}
        </div>
      </div>
    </article>
  );
}

function VerdictPanel({ d }: { d: IssueDetail }) {
  const v = d.verdictDetail;
  if (!v) return <Panel title="Merge verdict"><EmptyState title="No verdict yet" body="07_audit-and-pr arbitrates once all five reports exist." /></Panel>;
  const blocked = v.decision !== 'Cleared';
  return (
    <Panel title="Merge verdict — computation" id="verdict" subtitle="Hard gates first, then weighted points against the severity threshold (scoring.json). The agent may only tighten Cleared to Blocked.">
      <div className="space-y-3 p-3 text-sm">
        <div className="flex flex-wrap items-center gap-3">
          <span className={`inline-flex items-center gap-2 rounded-md border-2 px-3 py-1.5 text-base font-semibold ${blocked ? 'border-fail bg-fail-tint text-fail' : 'border-pass bg-pass-tint text-pass'}`}>
            <StatusGlyph state={blocked ? 'blocked' : 'passed'} size={18} /> {v.decision}
          </span>
          <span className="mono">{v.score ?? '?'} / 100 · threshold {v.threshold ?? '?'}{v.severity ? ` (${v.severity})` : ''}</span>
          {d.blocker && <span className="text-sm font-medium">{d.blocker.summary}</span>}
        </div>
        <div className="grid gap-3 lg:grid-cols-2">
          <div>
            <h4 className="mb-1 text-xs font-semibold uppercase text-muted">Hard gates (cannot be out-scored)</h4>
            <ul className="space-y-1">{v.hardGates.map((g) => <li key={g.name} className="flex items-center gap-2"><StatusGlyph state={g.triggered ? 'blocked' : 'passed'} /><span>{g.name}</span><span className={g.triggered ? 'font-semibold text-fail' : 'text-muted'}>{g.triggered ? 'TRIGGERED' : 'clear'}</span></li>)}</ul>
          </div>
          <div>
            <h4 className="mb-1 text-xs font-semibold uppercase text-muted">Weighted score</h4>
            <ul className="space-y-1.5">
              {v.breakdown.map((b) => (
                <li key={b.check}>
                  <div className="flex justify-between text-xs"><span>{b.check} <span className="mono text-muted">{b.verdict}</span></span><span className="mono tnum">{b.points ?? '?'} / {b.max ?? '?'}</span></div>
                  <div className="h-2 rounded bg-surface-3" role="img" aria-label={`${b.check}: ${b.points} of ${b.max}`}><div className={`h-2 rounded ${b.points === b.max ? 'bg-pass' : b.points ? 'bg-warn' : 'bg-fail'}`} style={{ width: `${b.max ? ((b.points || 0) / b.max) * 100 : 0}%` }} /></div>
                </li>
              ))}
            </ul>
          </div>
        </div>
        <div className="rounded border border-line bg-surface-2 p-2 text-xs">
          <div className="font-semibold">Override</div>
          <div className="text-muted">{v.override.applied ? `Applied: ${v.override.reason}` : 'None applied. Computed decision = final decision.'}</div>
        </div>
        {v.replay && (
          <div className={`rounded border p-2 text-xs ${v.replay.matchesRecorded ? 'border-line' : 'border-fail bg-fail-tint'}`}>
            <div className="font-semibold">{v.replay.matchesRecorded ? 'Reproducible from current evidence' : 'Not reproducible from current evidence'}</div>
            <div>Re-scoring the upstream reports as they read now ({v.replay.basis.toLowerCase()}): <b>{v.replay.decision}</b> {v.replay.score}/{v.replay.threshold}{v.replay.gates.length ? `, gates ${v.replay.gates.join(', ')}` : ', no hard gate'}.</div>
            {v.bodyReplay && <div className="mt-0.5 text-muted">Using the reports' own exit-code tables instead: <b>{v.bodyReplay.decision}</b> {v.bodyReplay.score}/{v.bodyReplay.threshold}{v.bodyReplay.matchesRecorded ? ' — matches the recorded verdict.' : '.'}</div>}
          </div>
        )}
        {v.narrative && (
          <div className="text-sm">
            <div className="mb-1 flex items-center gap-2"><ProvenanceBadge p="ai_authored" actor="07_audit-and-pr" /><span className="text-xs text-subtle">Narrative — opinion, not part of the computation</span></div>
            <p className="text-muted">{v.narrative}</p>
          </div>
        )}
        <div className="flex flex-wrap gap-3 text-xs text-muted"><EvidenceLink path={v.evidence?.path} sha={v.evidence?.sha256} />{v.computedAt && <span>score computed <Time iso={v.computedAt} source="reconstructed" /></span>}{v.policySha256 && <span>policy scoring.json <HashChip sha={v.policySha256} len={8} /></span>}</div>
      </div>
    </Panel>
  );
}

function TimelineList({ items }: { items: TimelineItem[] }) {
  return (
    <ol className="relative ml-2 border-l border-line">
      {items.map((t, i) => (
        <li key={i} className={`ml-4 py-2 ${t.gap ? 'text-tool' : ''}`}>
          <span className={`absolute -left-[7px] mt-1 grid h-3.5 w-3.5 place-items-center rounded-full border ${t.actorKind === 'human' ? 'border-human bg-human-tint' : t.actorKind === 'script' ? 'border-line-strong bg-surface' : t.actorKind === 'agent' ? 'border-run bg-run-tint' : 'border-line bg-surface-2'}`} aria-hidden />
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="font-medium">{t.title}</span>
            {t.gap && <Pill tone="tool">gap in the record</Pill>}
          </div>
          <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-muted">
            <Time iso={t.time} source={t.timeSource} />
            <span className="inline-flex items-center gap-1">{t.actorKind === 'human' ? <UserRound size={11} /> : t.actorKind === 'script' ? <Cog size={11} /> : t.actorKind === 'agent' ? <Bot size={11} /> : null}{t.actor}</span>
            <ProvenanceBadge p={t.provenance} />
            {t.stage && <span>{STAGE_BY_ID[t.stage]?.label}</span>}
            {t.evidence && <EvidenceLink path={t.evidence.path} />}
          </div>
          {t.detail && <div className="mt-0.5 text-xs text-subtle">{t.detail}</div>}
        </li>
      ))}
    </ol>
  );
}

export function IssuePage() {
  const { issueId } = useParams({ strict: false }) as { issueId?: string };
  const search = useSearch({ strict: false }) as { tab?: TabId };
  const nav = useNavigate();
  const tab: TabId = search.tab || 'overview';
  const setTab = (t: TabId) => nav({ to: '/issues/$issueId', params: { issueId: issueId as string }, search: { tab: t }, replace: true });
  const q = useIssue(issueId);
  const [stage, setStage] = useState<StageId | null>(null);
  if (q.isLoading) return <Loading rows={8} />;
  if (q.error || !q.data) return <ErrorState error={q.error} retry={() => q.refetch()} />;
  const d = q.data;
  // Same vocabulary as the trust strip: critical/major findings are the ones that need attention.
  const nonInfo = d.findingList.filter((f) => f.severity === 'critical' || f.severity === 'major').length;
  return (
    <div>
      <PageHeader
        crumbs={<><Link to="/issues">Issues</Link> / {d.id}</>}
        title={<span className="flex flex-wrap items-center gap-2"><span className="mono">{d.id}</span><SeverityBadge s={d.severity} /><span className="text-base font-normal">{d.title}</span></span>}
        subtitle={<span className="flex flex-wrap items-center gap-x-3 gap-y-1">
          {d.cwe && <span className="mono">{d.cwe}{d.owasp ? ` · ${d.owasp}` : ''}</span>}
          {d.cve && <span className="mono">{d.cve}</span>}
          {d.priority && <span>blast radius {d.priority}</span>}
          <span>services {d.services.join(', ') || '—'}</span>
          <span title="The register status is owned by the reporter and is not updated by the pipeline.">register status <b>{d.registerStatus || '—'}</b> (reporter-owned)</span>
        </span>}
      />
      <div className="space-y-3 border-b border-line bg-surface px-4 py-3 sm:px-6">
        <div className="flex flex-wrap items-center gap-3">
          {d.verdict ? <StatusBadge state={d.cells.verdict.state} label={`Verdict: ${d.verdict.decision}`} /> : <StatusBadge state={d.cells.verdict.state} label="No verdict yet" />}
          {d.blocker && <span className="text-sm font-medium">{d.blocker.summary}</span>}
          {nonInfo > 0 && <button type="button" onClick={() => setTab('findings')} className="inline-flex items-center gap-1 rounded bg-fail-tint px-2 py-0.5 text-xs font-medium text-fail"><AlertTriangle size={12} /> {nonInfo} critical/major finding{nonInfo > 1 ? 's' : ''} ({d.findingList.length} total)</button>}
        </div>
        {d.nextAction && <div className="text-sm"><span className="font-semibold">Next:</span> <span className={d.nextAction.ownerKind === 'human' ? 'text-human' : ''}>{d.nextAction.text}</span> <span className="text-xs text-subtle">— {d.nextAction.owner}. {d.nextAction.basis}</span></div>}
      </div>
      <div className="px-4 sm:px-6">
        <Tabs<TabId> label="Issue sections" value={tab} onChange={setTab} tabs={[
          { id: 'overview', label: 'Overview' }, { id: 'verification', label: 'Verification & gates' }, { id: 'plan', label: 'Plan & approval' }, { id: 'patch', label: 'Patch' },
          { id: 'diagnosis', label: 'Diagnosis' }, { id: 'impact', label: 'Impact' }, { id: 'timeline', label: 'Timeline', count: d.timeline.length }, { id: 'evidence', label: 'Evidence', count: d.evidence.length },
          { id: 'architecture', label: 'Architecture' }, { id: 'findings', label: 'Findings', count: d.findingList.length, warn: nonInfo > 0 },
        ]} />
      </div>
      <div className="space-y-4 p-4 sm:p-6" role="tabpanel">
        {tab === 'overview' && <Overview d={d} stage={stage} setStage={setStage} goto={setTab} />}
        {tab === 'verification' && (
          <>
            {d.cells.fix.state === 'failed' && <div className="rounded border border-tool bg-tool-tint px-3 py-2 text-sm"><b>The fix did not compile</b> in the Fixer's worktree. The static checks below analysed a real diff, but none of them is a claim that the patch builds.</div>}
            <div className="grid gap-3 xl:grid-cols-3">{d.lanes.filter((l) => ['rescan', 'redteam', 'behavior'].includes(l.check)).map((l) => <LaneCard key={l.check} lane={l} />)}</div>
            <div className="grid gap-3 xl:grid-cols-2">{d.lanes.filter((l) => ['qa', 'build'].includes(l.check)).map((l) => <LaneCard key={l.check} lane={l} />)}</div>
            <VerdictPanel d={d} />
          </>
        )}
        {tab === 'plan' && <PlanSection d={d} />}
        {tab === 'patch' && <PatchSection d={d} />}
        {tab === 'diagnosis' && <DiagnosisSection d={d} />}
        {tab === 'impact' && <ImpactSection d={d} />}
        {tab === 'timeline' && <Panel title="Timeline" subtitle="Observed = from the event ledger or decision records. Reconstructed = from timestamps inside reports. Gaps are things MARS does not record."><div className="p-3"><TimelineList items={d.timeline} /></div></Panel>}
        {tab === 'evidence' && <EvidenceSection d={d} />}
        {tab === 'architecture' && <ArchSection issueId={d.id} />}
        {tab === 'findings' && <FindingsSection d={d} />}
      </div>
    </div>
  );
}

function Overview({ d, stage, setStage, goto }: { d: IssueDetail; stage: StageId | null; setStage: (s: StageId | null) => void; goto: (t: TabId) => void }) {
  const st = stage ? d.cells[stage] : null;
  return (
    <>
      <Panel title="Lifecycle" subtitle="Derived stage graph. Click a stage for its evidence.">
        <div className="p-3">
          <PipelineGraph stages={STAGES} cells={d.cells} selected={stage} onSelect={setStage} height={300} />
          <ol className="mt-3 grid gap-1 sm:grid-cols-2 lg:grid-cols-3" aria-label="Stages (list)">
            {STAGES.map((s) => {
              const c = d.cells[s.id];
              return (
                <li key={s.id}>
                  <button type="button" onClick={() => setStage(s.id)} className={`flex w-full items-center gap-2 rounded px-2 py-1 text-left text-sm hover:bg-surface-2 ${stage === s.id ? 'bg-surface-3' : ''}`}>
                    <StatusGlyph state={c.state} /><span className="w-28 shrink-0 font-medium">{s.short}</span><span className="truncate text-muted">{c.label}</span><ModifierIcons modifiers={c.modifiers} />
                  </button>
                </li>
              );
            })}
          </ol>
        </div>
      </Panel>
      {st && stage && (
        <Panel title={`${STAGE_BY_ID[stage].label}`} subtitle={STAGE_BY_ID[stage].description}>
          <div className="space-y-2 p-3 text-sm">
            <div className="flex flex-wrap items-center gap-2"><StatusBadge state={st.state} label={st.label} />{st.failureClass && <FailureTag cls={st.failureClass} verified={st.causeVerified} />}<ProvenanceBadge p={st.provenance} /><ModifierIcons modifiers={st.modifiers} compact={false} /></div>
            {st.detail && <p>{st.detail}</p>}
            <KV items={[{ k: 'Outcome', v: <span className="mono">{st.outcome || '—'}</span> }, { k: 'Owner', v: STAGE_BY_ID[stage].owner }, { k: 'Evidence', v: <EvidenceLink path={st.evidence?.path} sha={st.evidence?.sha256} /> }, { k: 'Time', v: <Time iso={st.time} source="reconstructed" /> }]} />
          </div>
        </Panel>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <Panel title="What is wrong">
          <div className="space-y-2 p-3 text-sm">
            {d.headline && <p className="font-medium">{d.headline}</p>}
            <div className="flex items-center gap-2"><ProvenanceBadge p="ai_authored" actor="03_blast-radius-analyst" /><span className="text-xs text-subtle">Plain-language headline written by an agent</span></div>
            {d.rca?.rootCause && <p className="text-muted"><b className="text-fg">Root cause:</b> {d.rca.rootCause}</p>}
            {d.rca?.where && <p className="mono break-all text-xs">{d.rca.where}</p>}
            <button type="button" onClick={() => goto('diagnosis')} className="text-sm font-medium text-accent">Diagnosis <ArrowRight size={13} className="inline" /></button>
          </div>
        </Panel>
        <Panel title="Where it reaches">
          <div className="space-y-2 p-3 text-sm">
            {d.blast ? (
              <>
                <KV items={[{ k: 'Priority', v: d.blast.priority }, { k: 'Spread', v: d.blast.spread }, { k: 'Broken', v: d.blast.servicesBroken.join(', ') || 'none' }, { k: 'Degraded', v: d.blast.servicesDegraded.join(', ') || 'none' }, { k: 'Endpoints down', v: d.blast.endpointsDown }]} />
                <button type="button" onClick={() => goto('impact')} className="text-sm font-medium text-accent">Impact <ArrowRight size={13} className="inline" /></button>
              </>
            ) : <span className="text-muted">No blast radius report.</span>}
          </div>
        </Panel>
      </div>
    </>
  );
}

function PlanSection({ d }: { d: IssueDetail }) {
  const p = d.plan;
  const a = d.approval;
  if (!p) return <EmptyState title="No plan yet" body="04_fix-generator Stage 1 writes the plan after the root cause is diagnosed." />;
  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
      <Panel title="Proposed remediation" subtitle={<span className="flex items-center gap-2"><ProvenanceBadge p="ai_authored" actor="04_fix-generator" /> route <b>{p.route}</b> · confidence {p.confidence || '—'}</span>}>
        <div className="space-y-3 p-3 text-sm">
          {p.headline && <p className="font-medium">{p.headline}</p>}
          <KV items={[{ k: 'CWE', v: <span className="mono">{p.cwe}{p.cweName ? ` — ${p.cweName}` : ''}</span> }, { k: 'OWASP', v: p.owasp || '—' }, ...(p.dependency ? [{ k: 'Dependency', v: <span className="mono">{p.dependency}</span> }] : [])]} />
          {p.approach && <div><h4 className="text-xs font-semibold uppercase text-muted">Approach</h4><p>{p.approach}</p></div>}
          {p.plannedChanges.length > 0 && (
            <div><h4 className="text-xs font-semibold uppercase text-muted">Planned changes</h4>
              <ul className="mt-1 space-y-1">{p.plannedChanges.map((c, i) => {
                const notChanged = a.implementation?.plannedNotChanged.includes(c.file);
                return <li key={i} className="rounded border border-line p-2"><div className="mono flex items-center gap-2 text-xs">{c.file.split('/').pop()}{notChanged && <Pill tone="human">planned, not changed by the diff</Pill>}</div><div className="text-muted">{c.change}</div></li>;
              })}</ul>
            </div>
          )}
          {p.risks.length > 0 && <div><h4 className="text-xs font-semibold uppercase text-muted">Risks to watch</h4><ul className="list-disc pl-5 text-muted">{p.risks.map((r, i) => <li key={i}>{r}</li>)}</ul></div>}
          {p.verification.length > 0 && <div><h4 className="text-xs font-semibold uppercase text-muted">How the fix must be verified</h4><ol className="list-decimal pl-5 text-muted">{p.verification.map((r, i) => <li key={i}>{r}</li>)}</ol></div>}
          <EvidenceLink path={p.evidence?.path} sha={p.evidence?.sha256} />
        </div>
      </Panel>
      <Panel title="Human decision" subtitle="Only a human may move a plan from Proposed to Approved or Rejected.">
        <div className="space-y-3 p-3 text-sm">
          <StatusBadge state={d.cells.approval.state} label={a.stateLabel} />
          <KV items={[{ k: 'Status cell', v: <span className="mono">{p.status}</span> }, { k: 'Plan on disk', v: <HashChip sha={a.planSha256} /> }, { k: 'Decision records', v: a.decisions.length ? `${a.decisions.length}` : 'none' }]} />
          {a.state === 'approved_unattributed' && <p className="rounded border border-warn bg-warn-tint p-2 text-xs">The Status cell reads Approved, but MARS records no approver, time, or plan version. The decision cannot be attributed.</p>}
          {p.reproposed && <p className="rounded border border-warn bg-warn-tint p-2 text-xs"><b>Carried over a re-proposal.</b> {p.reproposedNote} The version on disk now was not the version a human decided.</p>}
          {a.implementation?.deviation && <p className="rounded border border-human bg-human-tint p-2 text-xs"><b>Implementation deviates from the plan.</b> {a.implementation.deviation}</p>}
          {a.decisions.map((r) => <div key={r.decisionId} className="rounded border border-line p-2 text-xs"><div className="font-semibold">{r.decision} by {r.actor} <span className="font-normal text-muted">({r.actorAuthentication}, via {r.channel})</span></div><Time iso={r.timestamp} source="observed" /><div className="text-muted">{r.rationale}</div>{!r.appliesToCurrent && <div className="text-fail">Applies to an earlier plan version.</div>}</div>)}
          <Link to="/approvals/$issueId" params={{ issueId: d.id }} className="inline-block font-medium">Open in Approval Center →</Link>
        </div>
      </Panel>
    </div>
  );
}

function PatchSection({ d }: { d: IssueDetail }) {
  if (!d.fix) return <EmptyState title="No patch" body={d.cells.fix.detail || 'The Fixer has not produced a patch for this issue.'} />;
  const inDiff = new Set((d.diff?.files || []).map((f) => f.path));
  return (
    <div className="space-y-4">
      <Panel title="Fix report" subtitle={<span className="flex items-center gap-2"><ProvenanceBadge p="mixed" /> diff AI-drafted · applied and compiled by script in a throwaway worktree</span>}>
        <div className="space-y-3 p-3 text-sm">
          <div className="flex flex-wrap items-center gap-2"><StatusBadge state={d.cells.fix.state} label={d.fix.status || '—'} />{d.cells.fix.failureClass && <FailureTag cls={d.cells.fix.failureClass} verified={d.cells.fix.causeVerified} />}</div>
          {d.fix.headline && <p>{d.fix.headline}</p>}
          <KV items={[{ k: 'Verification level', v: d.fix.verificationLevel }, { k: 'Matches plan (claimed)', v: d.fix.matchesPlan }, ...(d.fix.dependency ? [{ k: 'Dependency', v: <span className="mono">{d.fix.dependency}</span> }] : []), { k: 'Report', v: <EvidenceLink path={d.fix.evidence?.path} sha={d.fix.evidence?.sha256} /> }]} />
          <div>
            <h4 className="text-xs font-semibold uppercase text-muted">Files the report claims vs. files the diff changes</h4>
            <table className="mt-1 w-full text-xs"><thead><tr className="text-left text-muted"><th className="py-1">File</th><th>Claimed change</th><th>In diff?</th></tr></thead>
              <tbody>{d.fix.claimedFiles.map((c, i) => <tr key={i} className="border-t border-line"><td className="mono py-1">{c.file.split('/').pop()}</td><td className="text-muted">{c.change}</td><td>{inDiff.has(c.file) ? <span className="text-pass">yes</span> : /^no (behavioural )?change/i.test(c.change) ? <span className="text-muted">n/a (no change claimed)</span> : <span className="font-semibold text-fail">no</span>}</td></tr>)}</tbody></table>
          </div>
        </div>
      </Panel>
      {d.diff ? (
        <Panel title={<span className="flex items-center gap-2"><FileDiff size={14} /> {d.diff.path.split('/').pop()}</span>} actions={<HashChip sha={d.diff.sha256} />}>
          <div className="p-3"><div className="mb-2 flex flex-wrap gap-2 text-xs">{d.diff.files.map((f) => <span key={f.path} className="mono rounded bg-surface-3 px-1.5 py-px">{f.path.split('/').pop()} <span className="text-pass">+{f.additions}</span> <span className="text-fail">−{f.deletions}</span></span>)}</div><DiffView text={d.diff.text} /></div>
        </Panel>
      ) : <EmptyState title="Diff file missing" body="The fix report exists but its standalone .diff is missing." />}
    </div>
  );
}

function DiagnosisSection({ d }: { d: IssueDetail }) {
  const r = d.rca;
  if (!r) return <EmptyState title="Not diagnosed" body="02_root-cause-analyst has not produced a report for this issue." />;
  return (
    <Panel title="Root cause" subtitle={<span className="flex items-center gap-2"><ProvenanceBadge p="mixed" actor="02_root-cause-analyst" /> evidence collected by script · cause and causal chain written by the agent</span>}>
      <div className="space-y-3 p-3 text-sm">
        {r.headline && <p className="font-medium">{r.headline}</p>}
        <KV items={[{ k: 'What breaks', v: <span className="mono">{r.whatBreaks}</span> }, { k: 'Root cause', v: r.rootCause }, { k: 'Where', v: <span className="mono text-xs">{r.where}</span> }, { k: 'Service', v: r.service }, { k: 'Confidence', v: `${r.confidence} (declared by the agent)` }]} />
        {r.methods.length > 0 && <div><h4 className="text-xs font-semibold uppercase text-muted">Code involved (from the call graph)</h4><ul className="mono mt-1 space-y-0.5 text-xs">{r.methods.map((m, i) => <li key={i}>{m.method} <span className="font-sans text-muted">· {m.service}</span></li>)}</ul></div>}
        {d.register && <div><h4 className="text-xs font-semibold uppercase text-muted">As reported</h4><p className="text-muted">{d.register.summary}</p><div className="text-xs text-subtle">Reported {d.register.reportedOn} by {d.register.reportedBy}</div></div>}
        <EvidenceLink path={r.evidence?.path} sha={r.evidence?.sha256} />
      </div>
    </Panel>
  );
}

function ImpactSection({ d }: { d: IssueDetail }) {
  const b = d.blast;
  if (!b) return <EmptyState title="No blast radius" body="03_blast-radius-analyst has not measured this issue's reach." />;
  const tone = (s: string) => (/broken/i.test(s) ? 'failed' : /degraded/i.test(s) ? 'tool_error' : /risk/i.test(s) ? 'inconclusive' : 'passed') as 'failed';
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Panel title="Services" subtitle="Status is computed by rule from the call graph and HTTP clients; narrative is AI-authored.">
        <ul className="divide-y divide-line">{b.services.map((s) => <li key={s.service} className="flex items-start gap-2 px-3 py-2 text-sm"><StatusGlyph state={tone(s.status)} label={s.status} /><div><div className="mono font-medium">{s.service} <span className="font-sans text-xs text-muted">{s.status}</span></div><div className="text-xs text-muted">{s.note}</div></div></li>)}</ul>
      </Panel>
      <Panel title="Endpoints affected">
        <ul className="divide-y divide-line">{b.endpoints.map((e) => <li key={e.endpoint} className="flex items-start gap-2 px-3 py-2 text-sm"><StatusGlyph state={tone(e.status)} label={e.status} /><div><div className="mono">{e.endpoint}</div><div className="text-xs text-muted">{e.service} · {e.status} — {e.note}</div></div></li>)}</ul>
        <div className="border-t border-line p-3 text-xs text-muted">{b.headline}</div>
      </Panel>
    </div>
  );
}

function EvidenceSection({ d }: { d: IssueDetail }) {
  const lin = useLineage(d.id);
  const nav = useNavigate();
  return (
    <div className="space-y-4">
      <Panel title="Evidence lineage (expected inputs)" subtitle="Which artifact each stage is contracted to read (from the skill contracts), with this issue's files. It is not a record of what was actually read: MARS does not log input hashes. Dashed = missing. ⚠ = integrity findings on that artifact.">
        <div className="p-3">{lin.data ? <LineageView graph={lin.data} onSelect={(t) => { const f = d.evidence.find((e) => e.type === t); if (f) void nav({ to: '/evidence/view', search: { path: f.path } }); }} /> : <Loading rows={3} />}</div>
      </Panel>
      <Panel title="Artifacts for this issue">
        <ul className="divide-y divide-line">{d.evidence.map((e) => <li key={e.path} className="flex flex-wrap items-center gap-2 px-3 py-1.5 text-sm"><Link to="/evidence/view" search={{ path: e.path }} className="mono text-xs">{e.path}</Link><span className="text-xs text-muted">{e.type}</span><ProvenanceBadge p={e.provenance} /><HashChip sha={e.sha256} len={8} /><Time iso={e.generatedAt} source="reconstructed" /></li>)}</ul>
      </Panel>
    </div>
  );
}

function ArchSection({ issueId }: { issueId: string }) {
  const q = useArchitecture(issueId);
  if (q.isLoading) return <Loading rows={4} />;
  if (q.error || !q.data) return <ErrorState error={q.error} />;
  const v = q.data;
  const marked = Object.entries(v.overlay).filter(([id]) => v.nodes.some((n) => n.id === id));
  return (
    <div className="grid gap-4 2xl:grid-cols-[minmax(0,1fr)_320px]">
      <Panel title="Application architecture — this issue" subtitle={v.note}>
        <div className="p-3"><ArchGraph view={v} height={480} /></div>
        <div className="border-t border-line px-3 py-2 text-xs text-muted">{v.neo4j.note}</div>
      </Panel>
      <Panel title="Highlighted (list)">
        <ul className="divide-y divide-line">{marked.map(([id, role]) => { const n = v.nodes.find((x) => x.id === id)!; return <li key={id} className="px-3 py-1.5 text-xs"><div className="font-medium">{n.label}</div><div className="text-muted">{OVERLAY_STYLE[role]?.label || role}{n.lines ? ` · lines ${n.lines[0]}–${n.lines[1]}` : ''}</div></li>; })}</ul>
        <div className="border-t border-line p-3"><Link to="/architecture" search={{ issue: issueId }} className="text-sm font-medium">Open full architecture view →</Link></div>
      </Panel>
    </div>
  );
}

function FindingsSection({ d }: { d: IssueDetail }) {
  if (!d.findingList.length) return <EmptyState title="No integrity findings" body="All representations of this issue's evidence agree." />;
  const order = { critical: 0, major: 1, minor: 2, info: 3 };
  return (
    <Panel title="Integrity findings" subtitle="Contradictions between representations of the same fact. Mission Control does not fix evidence; it shows the authoritative value and the conflict.">
      <ul className="divide-y divide-line">
        {[...d.findingList].sort((a, b) => order[a.severity] - order[b.severity]).map((f) => (
          <li key={f.id} className="px-3 py-2 text-sm">
            <div className="flex flex-wrap items-center gap-2"><Pill tone={f.severity === 'critical' ? 'fail' : f.severity === 'major' ? 'tool' : 'neutral'}>{f.severity}</Pill><span className="mono text-xs text-muted">{f.rule}</span><span className="font-medium">{f.title}</span></div>
            <div className="mt-0.5 text-xs text-muted">{f.ruleTitle}. {f.detail}</div>
            <div className="mt-0.5 flex flex-wrap gap-2">{f.subjects.filter(Boolean).map((s) => <Link key={s} to="/evidence/view" search={{ path: s }} className="mono text-[11px]">{s}</Link>)}</div>
          </li>
        ))}
      </ul>
      <div className="border-t border-line px-3 py-2 text-xs text-subtle">Failure class reference: {Object.values(FAILURE).map((f) => f.short).join(' · ')}</div>
    </Panel>
  );
}
