import { useVirtualizer } from '@tanstack/react-virtual';
import { Pause, Play, Search, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ExecutionEvent } from '../../api/types';
import { EventRow } from '../../components/run';
import { InlineMarkdown, Markdown } from '../../components/Markdown';
import { Button, EmptyState, KeyValues, Panel, ProgressBar, StatusBadge, Tabs } from '../../components/ui';
import { useLiveRun, useRunEvents } from '../../live/LiveRun';
import { formatDateTime } from '../../lib/format';
import { eventStatus, lookup } from '../../lib/status';

const FILTERS = ['ALL', 'KERNEL', 'MIGRATION', 'SECURITY', 'HUMAN', 'MUTATION', 'VALIDATION', 'ERROR'] as const;
type Filter = (typeof FILTERS)[number];

function matches(e: ExecutionEvent, filter: Filter, q: string): boolean {
  if (filter === 'ERROR' && !(e.category === 'ERROR' || e.status === 'FAILED')) return false;
  if (filter !== 'ALL' && filter !== 'ERROR' && e.category !== filter) return false;
  if (!q) return true;
  const hay = `${e.title} ${e.message} ${e.type} ${e.component} ${e.subjects.map((s) => `${s.id} ${s.label}`).join(' ')}`.toLowerCase();
  return hay.includes(q.toLowerCase());
}

function EventDetail({ event, onClose }: { event: ExecutionEvent; onClose: () => void }) {
  return (
    <aside className="w-[440px] shrink-0 overflow-y-auto border-l border-border bg-panel p-3" aria-label="Event details">
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <div className="mono text-[11px] text-faint">#{event.sequence} · {event.event_id}</div>
          <div className="font-semibold text-strong">{event.title ? <InlineMarkdown>{event.title}</InlineMarkdown> : event.type}</div>
        </div>
        <button type="button" onClick={onClose} aria-label="Close event details" className="rounded p-1 text-muted hover:text-text">
          <X aria-hidden className="size-4" />
        </button>
      </div>
      <div className="space-y-3">
        <KeyValues rows={[
          ['Type', <span key="t" className="mono">{event.type}</span>],
          ['Status', <StatusBadge key="s" status={lookup(eventStatus, event.status)} />],
          ['Executor', event.component],
          ['Activity', <span key="a" className="mono">{event.activity}</span>],
          ['Run state', <span key="p" className="mono">{event.phase}</span>],
          ['Time', formatDateTime(event.timestamp)],
          ['Message', event.message ? <Markdown key="m">{event.message}</Markdown> : undefined],
        ]} />
        {event.progress && <ProgressBar progress={event.progress} />}
        {event.human_action && (
          <Panel title="Human action">
            <KeyValues rows={[
              ['Gate', event.human_action.gate],
              ['Decision', event.human_action.decision_type],
              ['Reason', <InlineMarkdown key="r">{event.human_action.reason}</InlineMarkdown>],
              ['Options', event.human_action.options.join(', ')],
            ]} />
          </Panel>
        )}
        {event.subjects.length > 0 && (
          <Panel title="Subjects">
            <ul className="space-y-0.5">
              {event.subjects.map((s) => (
                <li key={`${s.kind}${s.id}`} className="mono text-[11px]"><span className="text-faint">{s.kind}</span> {s.id} {s.label && <span className="text-muted">({s.label})</span>}</li>
              ))}
            </ul>
          </Panel>
        )}
        {Object.keys(event.attributes).length > 0 && (
          <Panel title="Facts">
            <KeyValues rows={Object.entries(event.attributes).map(([k, v]) => [<span key={k} className="mono">{k}</span>, <span key={v} className="mono break-all">{v}</span>])} />
          </Panel>
        )}
        {(event.evidence_refs.length > 0 || event.artifact_refs.length > 0) && (
          <Panel title="Evidence and artifacts">
            {event.evidence_refs.map((r) => <div key={r} className="mono text-[11px] text-muted">{r}</div>)}
            {event.artifact_refs.map((r) => <div key={r} className="mono text-[11px] text-primary">{r}</div>)}
          </Panel>
        )}
      </div>
    </aside>
  );
}

export function ActivityPage() {
  const events = useRunEvents();
  const live = useLiveRun();
  const [filter, setFilter] = useState<Filter>('ALL');
  const [q, setQ] = useState('');
  const [follow, setFollow] = useState(true);
  const [selected, setSelected] = useState<ExecutionEvent>();
  const parent = useRef<HTMLDivElement>(null);
  const shown = useMemo(() => events.filter((e) => matches(e, filter, q)), [events, filter, q]);
  const virtual = useVirtualizer({ count: shown.length, getScrollElement: () => parent.current, estimateSize: () => 27, overscan: 20 });

  useEffect(() => {
    if (follow && shown.length > 0) virtual.scrollToIndex(shown.length - 1, { align: 'end' });
  }, [follow, shown.length, virtual]);

  return (
    <div className="flex h-full min-h-[500px]">
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-2">
          <Tabs label="Event category" value={filter} onChange={setFilter} options={FILTERS.map((f) => ({ value: f, label: f }))} />
          <label className="relative">
            <Search aria-hidden className="absolute left-2 top-1/2 size-3.5 -translate-y-1/2 text-faint" />
            <span className="sr-only">Search events</span>
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search events"
              className="h-7 w-60 rounded border border-border-strong bg-panel-2 pl-7 pr-2" />
          </label>
          <div className="ml-auto flex items-center gap-3 text-[12px] text-muted">
            <span>{shown.length} of {events.length} event(s){live.hello ? ` · server has ${Math.max(live.hello.last_sequence, events.at(-1)?.sequence ?? 0)}` : ''}</span>
            <Button size="sm" onClick={() => setFollow((f) => !f)} aria-pressed={follow}
              title="Pausing only stops this view from scrolling; MARS keeps running">
              {follow ? <><Pause aria-hidden className="size-3.5" /> Pause follow</> : <><Play aria-hidden className="size-3.5" /> Follow</>}
            </Button>
          </div>
        </div>
        {events.length === 0 ? (
          <EmptyState title="No execution events">
            {live.status === 'LIVE' ? 'The run has not emitted events yet, or it was recorded before the event stream existed.'
              : 'Connecting to the event stream…'}
          </EmptyState>
        ) : (
          <div ref={parent} className="min-h-0 flex-1 overflow-y-auto" role="log" aria-live={follow ? 'polite' : 'off'}
            onWheel={() => follow && setFollow(false)}>
            <div style={{ height: virtual.getTotalSize(), position: 'relative' }}>
              {virtual.getVirtualItems().map((item) => {
                const e = shown[item.index];
                return (
                  <div key={e.sequence} style={{ position: 'absolute', top: 0, left: 0, right: 0, transform: `translateY(${item.start}px)` }}
                    ref={virtual.measureElement} data-index={item.index}>
                    <EventRow event={e} onSelect={setSelected} selected={selected?.sequence === e.sequence} />
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
      {selected && <EventDetail event={selected} onClose={() => setSelected(undefined)} />}
    </div>
  );
}
