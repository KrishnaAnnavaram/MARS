import { ArrowRight, Radio, UserRound, WifiOff } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, Outlet, useLocation, useParams } from 'react-router';
import { useRun } from '../api/queries';
import type { RunSnapshot } from '../api/types';
import { ErrorState, LoadingState, StatusBadge } from '../components/ui';
import { RecentDecisionsProvider } from '../features/decisions';
import { LiveRunProvider, useLiveRun } from '../live/LiveRun';
import { between, cn, formatDuration } from '../lib/format';
import { livenessStatus, lookup, verdictStatus } from '../lib/status';

function ConnectionIndicator() {
  const live = useLiveRun();
  const map = {
    LIVE: { label: 'LIVE', cls: 'text-success', icon: Radio },
    CONNECTING: { label: 'CONNECTING', cls: 'text-muted', icon: Radio },
    RECONNECTING: { label: 'RECONNECTING', cls: 'text-warning', icon: Radio },
    OFFLINE: { label: 'OFFLINE', cls: 'text-danger', icon: WifiOff },
  } as const;
  const m = map[live.status];
  const Icon = m.icon;
  return (
    <span
      className={cn('inline-flex items-center gap-1 text-[11px] font-semibold', m.cls)}
      title={live.status === 'LIVE' ? 'Receiving execution events' : live.detail ??
        'The event stream is disconnected. This does not mean the run stopped; the snapshot is still refreshed.'}
      aria-live="polite"
    >
      <Icon aria-hidden className={cn('size-3.5', live.status === 'LIVE' && 'animate-pulse')} />
      {m.label}
    </span>
  );
}

function Elapsed({ run }: { run: RunSnapshot }) {
  const [, tick] = useState(0);
  const running = !run.terminal;
  useEffect(() => {
    if (!running) return;
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [running]);
  const end = run.terminal ? run.updated_at : undefined;
  return <span className="mono" title="Wall-clock time since the run was created">{formatDuration(between(run.created_at, end))}</span>;
}

function TopBar({ run }: { run: RunSnapshot }) {
  const liveness = lookup(livenessStatus, run.liveness.state);
  return (
    <header className="flex min-h-12 flex-wrap items-center gap-x-5 gap-y-1 border-b border-border bg-panel px-4 py-1.5">
      <div className="min-w-0">
        <div className="flex items-baseline gap-2">
          <span className="text-[14px] font-semibold text-strong">{run.application ?? 'Unknown application'}</span>
          <span className="mono text-[11px] text-faint">{run.run_id}</span>
        </div>
        <div className="truncate text-[11px] text-muted">{run.source}</div>
      </div>
      <div className="flex flex-col">
        <span className="text-[10px] uppercase tracking-wider text-faint">State</span>
        <span className="mono text-[12px] text-text" title={run.phase_description}>{run.phase}</span>
      </div>
      <div className="flex flex-col">
        <span className="text-[10px] uppercase tracking-wider text-faint">Run health</span>
        <StatusBadge status={liveness} />
      </div>
      {run.verdict && (
        <div className="flex flex-col">
          <span className="text-[10px] uppercase tracking-wider text-faint">Verdict</span>
          <StatusBadge status={lookup(verdictStatus, run.verdict)} />
        </div>
      )}
      <div className="flex flex-col">
        <span className="text-[10px] uppercase tracking-wider text-faint">Elapsed</span>
        <Elapsed run={run} />
      </div>
      <div className="ml-auto flex items-center gap-4">
        {run.human_actions.count > 0 && (
          <Link to={`/runs/${run.run_id}/actions`} className="inline-flex items-center gap-1 text-[12px] font-medium text-human">
            <UserRound aria-hidden className="size-4" />
            {run.human_actions.count} human action{run.human_actions.count === 1 ? '' : 's'}
          </Link>
        )}
        <ConnectionIndicator />
      </div>
    </header>
  );
}

/** Impossible to miss when MARS is waiting for a human. */
function HumanActionBanner({ run }: { run: RunSnapshot }) {
  const location = useLocation();
  if (!run.waiting_for_human || run.human_actions.count === 0 || location.pathname.endsWith('/actions')) return null;
  return (
    <div role="alert" className="flex items-center gap-3 border-b border-human/40 bg-human-soft px-4 py-2">
      <UserRound aria-hidden className="size-5 shrink-0 text-human" />
      <div className="min-w-0 flex-1">
        <div className="font-semibold text-strong">ACTION REQUIRED</div>
        <div className="text-muted">
          MARS is waiting: {run.human_actions.headline ?? run.phase_label}. Nothing executes until a human decides.
        </div>
      </div>
      <Link
        to={`/runs/${run.run_id}/actions`}
        className="inline-flex items-center gap-1 rounded border border-human/60 px-2.5 py-1 font-medium text-human hover:bg-human/20"
      >
        Review <ArrowRight aria-hidden className="size-4" />
      </Link>
    </div>
  );
}

function RunFrame({ runId }: { runId: string }) {
  const run = useRun(runId);
  if (run.isLoading) return <LoadingState label="Loading run…" />;
  if (run.error || !run.data) return <ErrorState error={run.error} title={`Run ${runId} could not be loaded`} />;
  return (
    <>
      <TopBar run={run.data} />
      <HumanActionBanner run={run.data} />
      <main className="min-h-0 flex-1 overflow-y-auto">
        <Outlet />
      </main>
    </>
  );
}

export function RunLayout() {
  const { runId = '' } = useParams();
  return (
    <LiveRunProvider runId={runId}>
      <RecentDecisionsProvider key={runId}>
        <RunFrame runId={runId} />
      </RecentDecisionsProvider>
    </LiveRunProvider>
  );
}

/** The route's run ID for pages under a run. */
export function useRunId(): string {
  return useParams().runId ?? '';
}
