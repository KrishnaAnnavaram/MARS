import { ArrowLeft } from 'lucide-react';
import { Link, useParams } from 'react-router';
import { useFinding } from '../../api/queries';
import type { FindingDetail, JourneyStep } from '../../api/types';
import { InlineMarkdown, Markdown } from '../../components/Markdown';
import { EmptyState, Hash, KeyValues, Panel, QueryView, StatusBadge, Tag } from '../../components/ui';
import { DecisionReceipt, ProposalStatusBadge } from '../../features/decisions';
import { useRunId } from '../../layout/RunLayout';
import { cn } from '../../lib/format';
import { journeyStatus, lookup, severityStatus, toneClasses } from '../../lib/status';

function Journey({ steps }: { steps: JourneyStep[] }) {
  return (
    <ol className="space-y-0" aria-label="Remediation journey">
      {steps.map((s, i) => {
        const st = lookup(journeyStatus, s.status);
        const Icon = st.icon;
        return (
          <li key={s.id} className="relative flex gap-3 pb-3">
            {i < steps.length - 1 && <span aria-hidden className="absolute left-[9px] top-5 h-full w-px bg-border" />}
            <Icon aria-hidden className={cn('relative z-10 mt-0.5 size-[18px] shrink-0 rounded-full bg-panel', toneClasses[st.tone].text,
              s.status === 'CURRENT' && 'mars-active')} />
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className={cn('font-medium', s.status === 'CURRENT' ? 'text-primary' : 'text-strong')}>{s.label}</span>
                <span className={cn('text-[11px]', toneClasses[st.tone].text)}>{st.label}</span>
              </div>
              {s.detail && <div className="break-words text-[12px] text-muted"><InlineMarkdown>{s.detail}</InlineMarkdown></div>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

function SourceContext({ ctx }: { ctx: NonNullable<FindingDetail['source_context']> }) {
  return (
    <Panel title={`Source: ${ctx.path}`} bodyClassName="p-0"
      actions={<span className="text-[11px] text-faint">{ctx.origin}; credential-like literals masked</span>}>
      <pre className="mono overflow-x-auto py-2 text-[12px]">
        {ctx.lines.map((line, i) => {
          const n = ctx.first_line + i;
          const hot = n >= ctx.highlight_start && n <= ctx.highlight_end;
          return (
            <div key={n} className={cn('flex', hot && 'bg-warning-soft')}>
              <span className="w-12 shrink-0 select-none pr-3 text-right text-faint">{n}</span>
              <span className="whitespace-pre">{line}</span>
            </div>
          );
        })}
      </pre>
    </Panel>
  );
}

export function FindingPage() {
  const runId = useRunId();
  const { findingId = '' } = useParams();
  const finding = useFinding(runId, findingId);
  return (
    <div className="space-y-3 p-4">
      <Link to={`/runs/${runId}/security`} className="inline-flex items-center gap-1 text-muted hover:text-text">
        <ArrowLeft aria-hidden className="size-4" /> Security
      </Link>
      <QueryView query={finding}>
        {(f) => (
          <>
            <header className="flex flex-wrap items-start gap-3">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="mono text-[16px] font-semibold text-strong">{f.source_finding_id}</h1>
                  <StatusBadge status={lookup(severityStatus, f.severity)} />
                  {f.cwe.map((c) => <Tag key={c}>{c}</Tag>)}
                  {f.item_status && <Tag tone="active">{f.item_status}</Tag>}
                </div>
                <p className="text-strong">{f.title}</p>
              </div>
            </header>
            {f.description && (
              <Panel title="Description" actions={<span className="text-[11px] text-faint">as reported by {f.source}</span>}>
                <Markdown className="max-w-5xl">{f.description}</Markdown>
              </Panel>
            )}
            <div className="grid gap-3 xl:grid-cols-3">
              <Panel title="Remediation journey">
                <Journey steps={f.journey} />
              </Panel>
              <div className="space-y-3 xl:col-span-2">
                {f.source_context && <SourceContext ctx={f.source_context} />}
                <Panel title="Identity anchor">
                  <KeyValues rows={[
                    ['Finding ID', <span key="f" className="mono">{f.finding_id}</span>],
                    ['Source / rule', `${f.source}${f.rule_id ? ` · ${f.rule_id}` : ''}`],
                    ['Location', f.location ? <span key="l" className="mono">{f.location.path}:{f.location.line_start}</span> : undefined],
                    ['Anchor quality', f.anchor_quality],
                    ['FILE_ID', <span key="fi" className="mono">{f.file_id}</span>],
                    ['PROGRAM_UNIT_ID', <span key="pu" className="mono">{f.program_unit_id}</span>],
                    ['SYMBOL_ID', <span key="sy" className="mono">{f.symbol_id}</span>],
                    ['STATEMENT_ID', <span key="st" className="mono">{f.statement_id}</span>],
                    ['Fingerprint', <Hash key="fp" value={f.fingerprint} label="fingerprint" />],
                    ['Evidence', f.evidence_refs.length ? <span key="e" className="mono text-[11px]">{f.evidence_refs.join(', ')}</span> : undefined],
                  ]} />
                </Panel>
                {f.platform_requirement && (
                  <Panel title="Platform requirement" className="border-warning/40">
                    <KeyValues rows={[
                      ['Component', `${f.platform_requirement.component} ${f.platform_requirement.current_version ?? ''}`],
                      ['Minimum fixed version', f.platform_requirement.minimum_fixed_version],
                      ['Requires', `${f.platform_requirement.requires_platform} ${f.platform_requirement.requires_platform_minimum}+`],
                      ['Java', f.platform_requirement.requires_java_minimum],
                      ['Basis', f.platform_requirement.basis],
                    ]} />
                  </Panel>
                )}
                <div className="grid gap-3 lg:grid-cols-2">
                  <Panel title="Root cause">
                    {f.root_cause ? (
                      <KeyValues rows={[
                        ['Statement', <InlineMarkdown key="s">{f.root_cause.statement}</InlineMarkdown>],
                        ['Location', f.root_cause.location],
                        ['Confidence', f.root_cause.confidence],
                        ['Entry points', f.root_cause.entry_points.join(', ') || undefined],
                        ['Data flow', f.root_cause.data_flow.join(' → ') || undefined],
                        ['How to fix', f.root_cause.how_to_fix ? <Markdown key="h">{f.root_cause.how_to_fix}</Markdown> : undefined],
                      ]} />
                    ) : <EmptyState title="No root-cause analysis" />}
                  </Panel>
                  <Panel title="Blast radius">
                    {f.blast_radius ? (
                      <KeyValues rows={[
                        ['Scope', f.blast_radius.scope],
                        ['Priority', f.blast_radius.priority],
                        ['Confidence', f.blast_radius.confidence],
                        ['Endpoints', f.blast_radius.affected_endpoints.join(', ') || 'none identified'],
                        ['Services', f.blast_radius.affected_services.join(', ') || undefined],
                        ['Symbols', `${f.blast_radius.affected_symbol_ids.length} affected symbol(s)`],
                      ]} />
                    ) : <EmptyState title="No blast-radius analysis" />}
                  </Panel>
                </div>
                <Panel title="Remediation plan">
                  {f.plan ? (
                    <div className="space-y-2">
                      <div className="flex flex-wrap gap-2">
                        <Tag tone="active">{f.plan.route}</Tag>
                        {f.plan.cwe && <Tag>{f.plan.cwe}</Tag>}
                        {f.plan.strategy_only && <Tag tone="warning">strategy only</Tag>}
                        {f.plan.blocked_by_platform && <Tag tone="warning">blocked by platform</Tag>}
                        <span className="text-[12px] text-muted">catalog {f.plan.catalog_status} · KB {f.plan.kb_status}
                          {f.plan.research_status ? ` · research ${f.plan.research_status}` : ''} · confidence {f.plan.confidence}</span>
                      </div>
                      {f.plan.catalog_title && <div className="font-medium text-strong">{f.plan.catalog_title} {f.plan.owasp && <span className="text-muted">({f.plan.owasp})</span>}</div>}
                      {f.plan.plain_summary && <Markdown className="text-muted">{f.plan.plain_summary}</Markdown>}
                      {f.plan.approach && <Markdown>{f.plan.approach}</Markdown>}
                      {f.plan.verification_plan.length > 0 && (
                        <div><div className="text-[11px] uppercase tracking-wide text-faint">Verification plan</div>
                          <ul className="list-disc pl-5 text-muted">{f.plan.verification_plan.map((v) => <li key={v}><InlineMarkdown>{v}</InlineMarkdown></li>)}</ul></div>
                      )}
                      {f.plan.risk_notes.length > 0 && (
                        <div><div className="text-[11px] uppercase tracking-wide text-faint">Risks</div>
                          <ul className="list-disc pl-5 text-muted">{f.plan.risk_notes.map((v) => <li key={v}><InlineMarkdown>{v}</InlineMarkdown></li>)}</ul></div>
                      )}
                      {f.plan.open_questions.length > 0 && (
                        <div><div className="text-[11px] uppercase tracking-wide text-faint">Open questions</div>
                          <ul className="list-disc pl-5 text-muted">{f.plan.open_questions.map((v) => <li key={v}><InlineMarkdown>{v}</InlineMarkdown></li>)}</ul></div>
                      )}
                    </div>
                  ) : <EmptyState title="Not planned">Remediation planning runs after Gate A when the strategy includes security.</EmptyState>}
                </Panel>
                {f.proposal && (
                  <Panel title="Proposal">
                    <div className="flex flex-wrap items-center gap-2">
                      <Link to={`/runs/${runId}/changes/${f.proposal.proposal_id}`} className="mono text-primary hover:underline">{f.proposal.proposal_id}</Link>
                      <ProposalStatusBadge status={f.proposal.status} />
                      <Hash value={f.proposal.proposal_hash} label="proposal hash" />
                    </div>
                    {f.proposal.reason && <Markdown className="mt-1 text-muted">{f.proposal.reason}</Markdown>}
                  </Panel>
                )}
                {f.decisions.map((d) => <DecisionReceipt key={d.decision_id} decision={d} />)}
                {f.verification && (
                  <Panel title="Verification and arbiter">
                    <KeyValues rows={[
                      ['Fix status', f.verification.fix_status],
                      ['Re-scan', `${f.verification.rescan ?? '—'}${f.verification.rescan_reason ? ` (${f.verification.rescan_reason})` : ''}`],
                      ['Red-team', `${f.verification.redteam ?? '—'}${f.verification.attempted_vectors.length ? ` · ${f.verification.attempted_vectors.length} vector(s)` : ''}`],
                      ['Behaviour', f.verification.behavior],
                      ['QA / build', `${f.verification.qa ?? '—'} / ${f.verification.build ?? '—'}`],
                      ['Arbiter', `${f.verification.decision} (score ${f.verification.score}/${f.verification.threshold})`],
                      ['Gates triggered', f.verification.gates_triggered.join(', ') || 'none'],
                    ]} />
                  </Panel>
                )}
              </div>
            </div>
          </>
        )}
      </QueryView>
    </div>
  );
}
