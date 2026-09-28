import { Clock, Cpu } from 'lucide-react';
import { Link } from 'react-router';
import type { CurrentActivity, ExecutionEvent, PipelineStage, StageProgress } from '../api/types';
import { between, cn, formatDuration, formatTime, relative } from '../lib/format';
import { eventStatus, lookup, stageStatus, toneClasses } from '../lib/status';
import { EmptyState, Panel, ProgressBar, StatusBadge } from './ui';

/** Completed stages out of applicable ones. A count of stages, not a time estimate. */
export function StageStrip({ stages, progress, runId }: { stages: PipelineStage[]; progress: StageProgress; runId: string }) {
  const shown = stages.filter((s) => s.status !== 'SKIPPED');
  return (
    <div>
      <div className="flex gap-0.5" role="list" aria-label="Pipeline stages">
        {shown.map((s) => {
          const st = lookup(stageStatus, s.status);
          return (
            <Link
              role="listitem"
              key={s.id}
              to={`/runs/${runId}/pipeline?stage=${s.id}`}
              title={`${s.label}: ${st.label}${s.summary ? ` — ${s.summary}` : ''}`}
              aria-label={`${s.label}: ${st.label}`}
              className={cn('h-2.5 flex-1 rounded-sm', toneClasses[st.tone].dot,
                s.status === 'PENDING' && 'opacity-25', s.status === 'ACTIVE' && 'mars-active')}
            />
          );
        })}
      </div>
      <div className="mt-1 flex justify-between text-[11px] text-muted">
        <span>
          {progress.completed} of {progress.applicable} stages completed
        </span>
        <span className="text-faint">{progress.basis}</span>
      </div>
    </div>
  );
}

export function CurrentActivityPanel({ activity, advancing }: { activity?: CurrentActivity; advancing: boolean }) {
  return (
    <Panel title="Current activity">
      {!activity && (
        <EmptyState title={advancing ? 'Advancing between reported activities' : 'No activity in progress'} icon={<Cpu className="size-6" />}>
          {advancing
            ? 'MARS is advancing the run; the next activity it starts will appear here.'
            : 'Nothing reported as started is still open.'}
        </EmptyState>
      )}
      {activity && (
        <div className="space-y-2">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="text-[11px] uppercase tracking-wide text-faint">{activity.component}</div>
              <div className="font-medium text-strong">{activity.title}</div>
              {activity.message && <div className="text-muted">{activity.message}</div>}
            </div>
            <StatusBadge status={lookup(eventStatus, activity.status)} />
          </div>
          <ProgressBar progress={activity.progress} />
          {activity.subjects.length > 0 && (
            <div className="flex flex-wrap gap-1">
              {activity.subjects.map((s) => (
                <span key={`${s.kind}:${s.id}`} className="mono rounded bg-panel-3 px-1.5 py-0.5 text-[11px] text-muted">
                  {s.kind} {s.label ?? s.id}
                </span>
              ))}
            </div>
          )}
          <div className="flex flex-wrap gap-4 text-[12px] text-muted">
            <span className="inline-flex items-center gap-1">
              <Clock aria-hidden className="size-3.5" /> started {formatTime(activity.started_at)} (
              {formatDuration(between(activity.started_at, activity.last_update_at))} to last report)
            </span>
            <span>last report {relative(activity.last_update_at)}</span>
            {activity.capability && <span>capability {activity.capability}</span>}
          </div>
          {activity.stale && (
            <p className="rounded border border-warning/40 bg-warning-soft px-2 py-1 text-[12px] text-text">
              This server is not advancing the run. This is the last activity reported as started; its completion was not
              reported (the process that ran it may have stopped, or it is another process such as the CLI).
            </p>
          )}
        </div>
      )}
    </Panel>
  );
}

const categoryTone: Record<string, string> = {
  KERNEL: 'text-muted',
  MIGRATION: 'text-primary',
  SECURITY: 'text-warning',
  HUMAN: 'text-human',
  MUTATION: 'text-primary-strong',
  VALIDATION: 'text-success',
  ERROR: 'text-danger',
};

export function EventRow({ event, onSelect, selected }: { event: ExecutionEvent; onSelect?: (e: ExecutionEvent) => void; selected?: boolean }) {
  const st = lookup(eventStatus, event.status);
  const Icon = st.icon;
  return (
    <button
      type="button"
      onClick={() => onSelect?.(event)}
      className={cn('grid w-full grid-cols-[92px_84px_18px_1fr] items-start gap-2 border-b border-border/50 px-3 py-1 text-left',
        'hover:bg-panel-2', selected && 'bg-panel-3')}
      aria-label={`${formatTime(event.timestamp)} ${event.category} ${st.label}: ${event.title ?? event.type}`}
    >
      <span className="mono whitespace-nowrap text-faint">{formatTime(event.timestamp)}</span>
      <span className={cn('mono text-[11px] uppercase', categoryTone[event.category])}>{event.category}</span>
      <Icon aria-hidden className={cn('mt-0.5 size-3.5', toneClasses[st.tone].text, st.spin && 'animate-spin')} />
      <span className="min-w-0">
        <span className="text-text">{event.title ?? event.type}</span>
        {event.progress?.total !== undefined && (
          <span className="mono ml-2 text-faint">{event.progress.completed}/{event.progress.total} {event.progress.unit}</span>
        )}
        {event.component && <span className="ml-2 text-[11px] text-faint">{event.component}</span>}
      </span>
    </button>
  );
}
