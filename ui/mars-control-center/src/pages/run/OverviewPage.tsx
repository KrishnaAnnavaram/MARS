import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router';
import { useRun } from '../../api/queries';
import { CurrentActivityPanel, EventRow, StageStrip } from '../../components/run';
import { EmptyState, KeyValues, Panel, Stat, StatusBadge, Tag } from '../../components/ui';
import { useRunId } from '../../layout/RunLayout';
import { useRunEvents } from '../../live/LiveRun';
import { formatDateTime, humanize, shortHash } from '../../lib/format';
import { dimensionStatus, livenessStatus, lookup, severityStatus, trafficLight, verdictStatus } from '../../lib/status';

export function OverviewPage() {
  const runId = useRunId();
  const run = useRun(runId).data!;
  const events = useRunEvents();
  const recent = events.slice(-12).reverse();
  const advancing = run.liveness.state === 'ADVANCING';
  const sec = run.security;
  const mig = run.migration;
  const val = run.validation;
  const liveness = lookup(livenessStatus, run.liveness.state);

  return (
    <div className="space-y-3 p-4">
      <section aria-label="Run summary" className="rounded-md border border-border bg-panel p-3">
        <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="text-[11px] uppercase tracking-wide text-faint">Current state</div>
            <div className="mono text-[15px] font-semibold text-strong">{run.phase}</div>
            <div className="max-w-3xl text-muted">{run.phase_description}</div>
          </div>
          <div className="text-right">
            <StatusBadge status={liveness} />
            <div className="mt-1 max-w-md text-[12px] text-muted">{run.liveness.explanation}</div>
          </div>
        </div>
        <StageStrip stages={run.pipeline} progress={run.stage_progress} runId={runId} />
      </section>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-8">
        <Stat label="Strategy" value={run.strategy ? humanize(run.strategy) : 'Not decided'} sub={run.strategy ? 'Gate A' : 'Gate A pending'} />
        <Stat label="Migration" value={mig.status ? humanize(mig.status) : '—'}
          sub={mig.traffic_light ? `${mig.traffic_light} · ${mig.current_version ?? '?'} → ${mig.target_version ?? '?'}` : undefined}
          tone={mig.status === 'GREEN' ? 'success' : mig.status === 'BLOCKED' || mig.status === 'FAILED' ? 'danger' : undefined} />
        <Stat label="Findings" value={sec.total} sub={Object.entries(sec.by_severity).map(([k, v]) => `${v} ${k.toLowerCase()}`).join(', ') || 'none'} />
        <Stat label="Analysed" value={`${sec.analysed}/${sec.total}`} sub="root cause + blast radius" />
        <Stat label="Approvals" value={sec.proposals_awaiting} sub="proposals awaiting a decision" tone={sec.proposals_awaiting ? 'human' : undefined} />
        <Stat label="Fixes cleared" value={`${sec.cleared}/${sec.cleared + sec.blocked}`} sub="by the merge arbiter" />
        <Stat label="Validation" value={humanize(val.status)} sub={val.dimensions ? `${val.passed} pass · ${val.failed} fail · ${val.unknown} unknown` : 'final validation not run'}
          tone={val.status === 'PASSED' ? 'success' : val.status === 'FAILED' ? 'danger' : undefined} />
        <Stat label="Verdict" value={run.verdict ? lookup(verdictStatus, run.verdict).label : 'Not yet'}
          tone={run.verdict ? lookup(verdictStatus, run.verdict).tone : undefined} />
      </div>

      <div className="grid gap-3 xl:grid-cols-3">
        <div className="space-y-3 xl:col-span-2">
          <CurrentActivityPanel activity={run.current_activity} advancing={advancing} />

          {run.human_actions.count > 0 && (
            <Panel title="Human action required" className="border-human/50">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <div className="font-medium text-strong">{run.human_actions.headline}</div>
                  <div className="text-muted">Nothing executes until a human records a decision.</div>
                </div>
                <Link to={`/runs/${runId}/actions`} className="inline-flex items-center gap-1 font-medium text-human">
                  Review <ArrowRight aria-hidden className="size-4" />
                </Link>
              </div>
            </Panel>
          )}

          <div className="grid gap-3 md:grid-cols-2">
            <Panel title="Migration" actions={<Link className="text-[12px] text-primary" to={`/runs/${runId}/migration`}>Open</Link>}>
              <KeyValues rows={[
                ['Assessment', mig.traffic_light ? <StatusBadge status={lookup(trafficLight, mig.traffic_light)} /> : 'not assessed'],
                ['Need', humanize(mig.need)],
                ['Platform', mig.framework ? `${mig.framework} ${mig.current_version ?? '?'}` : undefined],
                ['Target', mig.target_version],
                ['Reference pack', mig.reference_pack],
                ['Status', <span key="s">{humanize(mig.status)}{mig.status_detail ? <span className="text-muted"> — {mig.status_detail}</span> : null}</span>],
                ['Rounds', mig.rounds !== undefined ? `${mig.rounds} recorded (limit ${mig.max_rounds})` : undefined],
              ]} />
            </Panel>
            <Panel title="Security" actions={<Link className="text-[12px] text-primary" to={`/runs/${runId}/security`}>Open</Link>}>
              <div className="mb-2 flex flex-wrap gap-1">
                {Object.entries(sec.by_severity).map(([k, v]) => (
                  <StatusBadge key={k} status={{ ...lookup(severityStatus, k), label: `${v} ${lookup(severityStatus, k).label}` }} />
                ))}
                {sec.total === 0 && <span className="text-muted">No findings</span>}
              </div>
              <KeyValues rows={[
                ['Planned', `${sec.planned} remediation plan(s)`],
                ['Awaiting decision', sec.proposals_awaiting],
                ['Applied', sec.applied],
                ['Cleared / blocked', `${sec.cleared} / ${sec.blocked}`],
                ['Deferred / rejected', `${sec.deferred} / ${sec.rejected}`],
                ['Blocked by platform', sec.blocked_by_platform],
              ]} />
            </Panel>
          </div>

          <Panel title="Validation" actions={<Link className="text-[12px] text-primary" to={`/runs/${runId}/validation`}>Open</Link>}>
            {val.status === 'NOT_RUN' ? (
              <p className="text-muted">Final validation has not run yet. No dimension is counted as passed until it does.</p>
            ) : (
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge status={lookup(dimensionStatus, val.status === 'PASSED' ? 'PASS' : val.status === 'FAILED' ? 'FAIL' : 'INSUFFICIENT_EVIDENCE')} />
                <span className="text-muted">{val.passed} pass, {val.failed} fail, {val.unknown} unknown of {val.dimensions}</span>
                {val.mandatory_failed.map((d) => <Tag key={d} tone="danger">{d} failed (mandatory)</Tag>)}
                {val.mandatory_unknown.map((d) => <Tag key={d} tone="warning">{d} unknown (mandatory)</Tag>)}
              </div>
            )}
          </Panel>
        </div>

        <div className="space-y-3">
          <Panel title="Recent activity" bodyClassName="p-0"
            actions={<Link className="text-[12px] text-primary" to={`/runs/${runId}/activity`}>All events</Link>}>
            {recent.length === 0 ? (
              <EmptyState title="No execution events yet">
                Runs created before the event stream existed are shown from their state history only.
              </EmptyState>
            ) : (
              recent.map((e) => <EventRow key={e.sequence} event={e} />)
            )}
          </Panel>
          <Panel title="Run facts">
            <KeyValues rows={[
              ['Created', formatDateTime(run.created_at)],
              ['Last state change', formatDateTime(run.updated_at)],
              ['Baseline seal', <span key="b" className="mono" title={run.baseline_seal}>{shortHash(run.baseline_seal, 16)}</span>],
              ['Evaluation date', run.evaluation_date],
              ['Baseline build', run.skip_build ? 'skipped (--skip-build)' : 'run'],
              ['Harness / policy', `${run.harness_version ?? '?'} / ${run.policy_version ?? '?'}`],
              ['Decisions', `${run.integrity.decisions} (${run.integrity.tampered_decisions.length ? `${run.integrity.tampered_decisions.length} TAMPERED` : 'integrity verified'})`],
              ['Evidence', `${run.integrity.evidence_records} record(s), chain ${run.integrity.evidence_chain_violations.length ? 'BROKEN' : 'intact'}`],
              ['Build tool', run.environment?.build_tool_available === false
                ? `${run.environment?.build_tool} unavailable: ${run.environment?.build_tool_unavailable_reason ?? ''}` : run.environment?.build_tool],
            ]} />
            <p className="mt-2 text-[11px] text-faint">
              Integrity is recomputed from disk on every read. It shows that a record was not modified; it does not say who made it.
            </p>
          </Panel>
          {run.notes.length > 0 && (
            <Panel title="Engine notes">
              <ul className="space-y-1 text-[12px] text-muted">
                {run.notes.slice(-6).map((n, i) => <li key={i} className="break-words">{n}</li>)}
              </ul>
            </Panel>
          )}
        </div>
      </div>
    </div>
  );
}
