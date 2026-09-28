import { useVirtualizer } from '@tanstack/react-virtual';
import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useLog, useLogs, useRun } from '../../api/queries';
import { Button, EmptyState, ErrorState, LoadingState, Tabs } from '../../components/ui';
import { useRunId } from '../../layout/RunLayout';
import { bytes, cn, formatTime } from '../../lib/format';

const LEVELS = ['', 'ERROR', 'WARN', 'INFO', 'DEBUG'] as const;

export function LogsPage() {
  const runId = useRunId();
  const run = useRun(runId).data!;
  const files = useLogs(runId);
  const [params, setParams] = useSearchParams();
  const path = params.get('path') ?? files.data?.[0]?.path;
  const [level, setLevel] = useState<(typeof LEVELS)[number]>('');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [follow, setFollow] = useState(true);
  const log = useLog(runId, path, { level, q: search });
  const parent = useRef<HTMLDivElement>(null);
  const lines = log.data?.lines ?? [];
  const virtual = useVirtualizer({ count: lines.length, getScrollElement: () => parent.current, estimateSize: () => 18, overscan: 40 });
  const advancing = run.liveness.state === 'ADVANCING';

  useEffect(() => {
    if (!follow || !advancing) return;
    const t = setInterval(() => void log.refetch(), 2000);
    return () => clearInterval(t);
  }, [follow, advancing, log]);

  useEffect(() => {
    if (follow && lines.length) virtual.scrollToIndex(lines.length - 1, { align: 'end' });
  }, [follow, lines.length, virtual]);

  return (
    <div className="flex h-full min-h-[500px]">
      <aside className="w-72 shrink-0 overflow-y-auto border-r border-border bg-panel" aria-label="Log files">
        <div className="border-b border-border px-3 py-2 text-[12px] text-muted">
          Tool output (builds, runtimes). Logs are supplementary; the event stream and artifacts are the record.
        </div>
        {files.isLoading && <LoadingState />}
        {files.error && <ErrorState error={files.error} />}
        {files.data?.length === 0 && <EmptyState title="No tool logs yet" />}
        <ul>
          {files.data?.map((f) => (
            <li key={f.path}>
              <button type="button" onClick={() => setParams({ path: f.path })}
                className={cn('block w-full border-l-2 px-3 py-1.5 text-left', f.path === path ? 'border-primary bg-panel-2' : 'border-transparent hover:bg-panel-2')}>
                <div className="mono truncate text-[12px] text-text">{f.path}</div>
                <div className="text-[11px] text-faint">{bytes(f.size)} · {formatTime(f.modified)}</div>
              </button>
            </li>
          ))}
        </ul>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <form className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-2" onSubmit={(e) => { e.preventDefault(); setSearch(q); }}>
          <Tabs label="Level" value={level} onChange={setLevel} options={LEVELS.map((l) => ({ value: l, label: l || 'All' }))} />
          <label className="sr-only" htmlFor="log-search">Search log</label>
          <input id="log-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search"
            className="h-7 w-56 rounded border border-border-strong bg-panel-2 px-2 text-[12px]" />
          <Button size="sm" type="submit">Search</Button>
          <Button size="sm" aria-pressed={follow} onClick={() => setFollow((f) => !f)}>{follow ? 'Pause follow' : 'Follow'}</Button>
          {log.data && <span className="ml-auto text-[12px] text-muted">{lines.length} shown of {log.data.total_lines} line(s){log.data.truncated ? ' (truncated)' : ''}</span>}
        </form>
        {!path && <EmptyState title="Choose a log" />}
        {path && log.isLoading && <LoadingState />}
        {path && log.error && <ErrorState error={log.error} />}
        {path && log.data && (
          <div ref={parent} className="mono min-h-0 flex-1 overflow-auto text-[12px]" role="log">
            <div style={{ height: virtual.getTotalSize(), position: 'relative', minWidth: '100%' }}>
              {virtual.getVirtualItems().map((item) => {
                const l = lines[item.index];
                return (
                  <div key={l.number} style={{ position: 'absolute', top: 0, left: 0, transform: `translateY(${item.start}px)` }}
                    className={cn('flex whitespace-pre px-2', l.level === 'ERROR' && 'bg-danger-soft', l.level === 'WARN' && 'bg-warning-soft')}>
                    <span className="w-14 shrink-0 select-none pr-3 text-right text-faint">{l.number}</span>
                    <span>{l.text}</span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
