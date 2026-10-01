import { Fragment, useState } from 'react';
import { Link } from '@tanstack/react-router';
import type { Cell, IssueSummary, StageDef, StageId } from '../../shared/types';
import { BOARD_STAGES, PHASE_LABEL, STAGE_BY_ID } from '../../shared/stages';
import { FAILURE, MODIFIER, PROVENANCE, ROLE_CLASSES, STATE } from '../domain/status';
import { Drawer, FailureTag, GlyphSvg, HashChip, KV, ModifierIcons, ProvenanceBadge, SeverityBadge, StatusBadge, Time } from './ui';
import { useIssue } from '../api';

export function cellAria(stage: StageDef, c: Cell): string {
  const parts = [`${stage.label}: ${STATE[c.state].label}`];
  if (c.label && c.label !== STATE[c.state].label) parts.push(c.label);
  if (c.failureClass) parts.push(FAILURE[c.failureClass].label);
  for (const m of c.modifiers) if (m !== 'live') parts.push(MODIFIER[m].label);
  return parts.join('. ');
}

export function StatusCell({ stage, cell, onClick, compact = false, showStage = false }: { stage: StageDef; cell: Cell; onClick?: () => void; compact?: boolean; showStage?: boolean }) {
  const s = STATE[cell.state];
  const r = ROLE_CLASSES[s.role];
  const emphasis = cell.state === 'awaiting_human' ? `${r.bg} ${r.border} border-2` : cell.state === 'failed' || cell.state === 'blocked' ? `${r.bg} border-transparent` : cell.state === 'running' ? `${r.bg} ${r.border}` : 'border-transparent hover:border-line';
  const flags = cell.modifiers.some((m) => m !== 'live') ? <ModifierIcons modifiers={cell.modifiers} /> : null;
  const tone = cell.state === 'passed' || cell.state === 'ready' || cell.state === 'waiting' || cell.state === 'not_applicable' ? 'text-muted' : `font-medium ${r.fg}`;
  if (showStage || compact) {
    return (
      <button type="button" onClick={onClick} aria-label={cellAria(stage, cell)} title={cellAria(stage, cell)}
        className={`flex w-full min-w-0 items-center gap-1 overflow-hidden rounded border px-1 py-1 text-left ${emphasis} ${cell.modifiers.includes('live') ? 'mc-flash' : ''}`}>
        <span className={`shrink-0 ${r.fg}`}><GlyphSvg glyph={s.glyph} size={14} /></span>
        {showStage && <span className="min-w-0 truncate text-[11px] font-medium text-fg">{stage.short}</span>}
        {flags && <span className="ml-auto shrink-0">{flags}</span>}
      </button>
    );
  }
  // Stacked: glyph + flags on top, the label below at the full cell width, so whole words fit even in
  // narrow (1280 px) columns; wrapping happens at spaces, never inside a word.
  return (
    <button type="button" onClick={onClick} aria-label={cellAria(stage, cell)} title={cellAria(stage, cell)}
      className={`flex w-full min-w-0 flex-col items-stretch gap-0.5 overflow-hidden rounded border px-1 py-1 text-left ${emphasis} ${cell.modifiers.includes('live') ? 'mc-flash' : ''}`}>
      <span className="flex items-center gap-1">
        <span className={`shrink-0 ${r.fg}`}><GlyphSvg glyph={s.glyph} size={14} /></span>
        {flags}
      </span>
      <span className={`line-clamp-2 text-[11px] leading-tight hyphens-none ${tone}`}>{cell.label}</span>
    </button>
  );
}

function CellDrawer({ issueId, stage, onClose }: { issueId: string; stage: StageId; onClose: () => void }) {
  const q = useIssue(issueId);
  const def = STAGE_BY_ID[stage];
  const c = q.data?.cells[stage];
  const findings = (q.data?.findingList || []).filter((f) => c?.findings.includes(f.id));
  return (
    <Drawer open onClose={onClose} title={<span>{issueId} · {def.label}</span>}>
      {!c ? <div className="text-sm text-muted">Loading…</div> : (
        <div className="space-y-4 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge state={c.state} label={c.label} />
            {c.failureClass && <FailureTag cls={c.failureClass} verified={c.causeVerified} />}
            <ProvenanceBadge p={c.provenance} />
          </div>
          {c.detail && <p className="text-fg">{c.detail}</p>}
          <KV items={[
            { k: 'Native outcome', v: c.outcome ? <span className="mono">{c.outcome}</span> : '—' },
            { k: 'Decided by', v: c.decidedBy === 'script' ? 'Deterministic script' : c.decidedBy === 'agent' ? 'Agent (AI judgement)' : c.decidedBy === 'human' ? 'Human' : 'Script facts + agent judgement' },
            { k: 'Time', v: <Time iso={c.time} source={c.time ? 'reconstructed' : 'unknown'} /> },
            { k: 'Evidence', v: c.evidence ? <span className="flex flex-col gap-1"><Link to="/evidence/view" search={{ path: c.evidence.path }} className="mono break-all text-xs">{c.evidence.path}</Link><HashChip sha={c.evidence.sha256} /></span> : 'none' },
            { k: 'Owner', v: def.owner },
            { k: 'Skills', v: def.skills.length ? def.skills.map((s) => <Link key={s} to="/harness/skills/$skillId" params={{ skillId: s }} className="mono mr-2 text-xs">{s}</Link>) : '—' },
          ]} />
          {c.modifiers.filter((m) => m !== 'live').length > 0 && (
            <div>
              <h3 className="mb-1 text-xs font-semibold uppercase text-muted">Flags</h3>
              <ul className="space-y-1">{c.modifiers.filter((m) => m !== 'live').map((m) => <li key={m} className="flex items-start gap-2"><ModifierIcons modifiers={[m]} /><span><b>{MODIFIER[m].label}.</b> {MODIFIER[m].description}</span></li>)}</ul>
            </div>
          )}
          {findings.length > 0 && (
            <div>
              <h3 className="mb-1 text-xs font-semibold uppercase text-muted">Integrity findings</h3>
              <ul className="space-y-2">{findings.map((f) => <li key={f.id} className="rounded border border-line p-2"><div className="font-medium">{f.rule} · {f.title}</div>{f.detail && <div className="mt-0.5 text-xs text-muted">{f.detail}</div>}</li>)}</ul>
            </div>
          )}
          <p className="text-xs text-subtle">{def.description} Provenance: {PROVENANCE[c.provenance].description}</p>
          <Link to="/issues/$issueId" params={{ issueId }} search={{ tab: ['rescan', 'redteam', 'behavior', 'qa', 'build', 'verdict'].includes(stage) ? 'verification' : stage === 'plan' || stage === 'approval' ? 'plan' : stage === 'fix' ? 'patch' : 'overview' }} className="inline-block text-sm font-medium">Open {issueId} →</Link>
        </div>
      )}
    </Drawer>
  );
}

export function RemediationBoard({ issues, stages }: { issues: IssueSummary[]; stages: StageDef[] }) {
  const [sel, setSel] = useState<{ issueId: string; stage: StageId } | null>(null);
  const cols = BOARD_STAGES.map((id) => stages.find((s) => s.id === id) || STAGE_BY_ID[id]);
  const phases = (['understand', 'fix', 'verify_ship'] as const).map((p) => ({ p, n: cols.filter((c) => c.phase === p).length }));
  return (
    <>
      <div className="hidden overflow-x-auto md:block">
        <table className="w-full min-w-[1000px] table-fixed border-collapse text-sm" aria-label="Issue by stage board">
          <thead>
            <tr>
              <th className="w-[160px] xl:w-[200px]" aria-hidden />
              {phases.map(({ p, n }) => <th key={p} colSpan={n} className="border-b border-line px-1 pb-0.5 pt-1 text-left text-[10px] font-semibold uppercase tracking-wider text-subtle">{PHASE_LABEL[p]}</th>)}
            </tr>
            <tr className="bg-surface-2">
              <th scope="col" className="border-b border-line px-3 py-1.5 text-left text-xs font-semibold uppercase text-muted">Issue</th>
              {cols.map((c) => <th key={c.id} scope="col" className="border-b border-line px-1 py-1.5 text-left text-[11px] font-semibold text-muted" title={c.description}>{c.short}</th>)}
            </tr>
          </thead>
          <tbody>
            {issues.map((i) => (
              <Fragment key={i.id}>
                <tr className="border-t border-line">
                  <th scope="row" className="px-3 pt-2 text-left align-top font-normal">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <Link to="/issues/$issueId" params={{ issueId: i.id }} className="mono font-semibold">{i.id}</Link>
                      <SeverityBadge s={i.severity} />
                      {i.cwe && <span className="mono text-[11px] text-muted">{i.cwe}</span>}
                    </div>
                    <div className="mt-0.5 line-clamp-2 text-xs text-muted" title={i.title}>{i.title}</div>
                  </th>
                  {cols.map((c) => <td key={c.id} className="px-px pt-2 align-top"><StatusCell stage={c} cell={i.cells[c.id]} onClick={() => setSel({ issueId: i.id, stage: c.id })} /></td>)}
                </tr>
                <tr>
                  <td colSpan={cols.length + 1} className="px-3 pb-2 pt-1">
                    <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1 text-xs">
                      {i.blocker && <span className="font-medium text-fg"><span className="text-fail">{i.blocker.summary.split(' — ')[0]}</span>{i.blocker.summary.includes(' — ') ? ` — ${i.blocker.summary.split(' — ').slice(1).join(' — ')}` : ''}</span>}
                      {i.nextAction && <span className="text-muted"><span className="font-semibold text-fg">Next:</span> {i.nextAction.text} <span className="text-subtle">({i.nextAction.owner})</span></span>}
                    </div>
                  </td>
                </tr>
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="divide-y divide-line md:hidden" aria-label="Issues">
        {issues.map((i) => (
          <li key={i.id} className="px-3 py-3">
            <div className="flex flex-wrap items-center gap-1.5">
              <Link to="/issues/$issueId" params={{ issueId: i.id }} className="mono font-semibold">{i.id}</Link>
              <SeverityBadge s={i.severity} />
              {i.verdict && <StatusBadge state={i.cells.verdict.state} label={i.verdict.decision} />}
            </div>
            <div className="mt-1 text-xs text-muted">{i.title}</div>
            <div className="mt-2 grid grid-cols-2 gap-1 min-[480px]:grid-cols-3">
              {cols.map((c) => <StatusCell key={c.id} stage={c} cell={i.cells[c.id]} compact showStage onClick={() => setSel({ issueId: i.id, stage: c.id })} />)}
            </div>
            {i.blocker && <div className="mt-2 text-xs font-medium">{i.blocker.summary}</div>}
            {i.nextAction && <div className="mt-1 text-xs text-muted">Next: {i.nextAction.text}</div>}
          </li>
        ))}
      </ul>
      {sel && <CellDrawer issueId={sel.issueId} stage={sel.stage} onClose={() => setSel(null)} />}
    </>
  );
}

export function StateLegend() {
  const shown = ['passed', 'failed', 'blocked', 'tool_error', 'inconclusive', 'awaiting_human', 'running', 'ready', 'waiting', 'blocked_upstream', 'not_applicable'] as const;
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted" aria-label="Legend">
      {shown.map((s) => <span key={s} className="inline-flex items-center gap-1"><span className={ROLE_CLASSES[STATE[s].role].fg}><GlyphSvg glyph={STATE[s].glyph} size={12} /></span>{STATE[s].label}</span>)}
      <span className="mx-1 h-3 w-px bg-line" aria-hidden />
      {(['conflict', 'unattributed', 'carried_over', 'declared_manual_edit', 'compile_failed_fix'] as const).map((m) => <span key={m} className="inline-flex items-center gap-1"><ModifierIcons modifiers={[m]} />{MODIFIER[m].label}</span>)}
    </div>
  );
}
