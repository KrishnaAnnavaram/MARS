import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import {
  flexRender, getCoreRowModel, getSortedRowModel, useReactTable, type ColumnDef, type SortingState,
} from '@tanstack/react-table';
import { AlertTriangle, Bot, Cog, Copy, Check, Info, UserRound, X, Layers, CircleSlash } from 'lucide-react';
import type { CellModifier, CellState, FailureClass, Provenance } from '../../shared/types';
import { FAILURE, MODIFIER, PROVENANCE, ROLE_CLASSES, STATE, type Glyph } from '../domain/status';
import { ApiError } from '../api';

// ------------------------------------------------------------------------------------- glyphs
export function GlyphSvg({ glyph, size = 14, className = '' }: { glyph: Glyph; size?: number; className?: string }) {
  const s = size;
  const common = { width: s, height: s, viewBox: '0 0 16 16', className: `shrink-0 ${className}`, 'aria-hidden': true as const };
  switch (glyph) {
    case 'triangle': return <svg {...common}><path d="M8 14.5 1 2.5h14z" fill="currentColor" /><path d="M8 5.5v4" stroke="var(--surface)" strokeWidth="1.6" strokeLinecap="round" /><circle cx="8" cy="11.4" r=".9" fill="var(--surface)" /></svg>;
    case 'octagon': return <svg {...common}><path d="M5.2 1h5.6L15 5.2v5.6L10.8 15H5.2L1 10.8V5.2z" fill="currentColor" /><path d="M4.5 8h7" stroke="var(--surface)" strokeWidth="1.8" strokeLinecap="round" /></svg>;
    case 'diamond': return <svg {...common}><path d="M8 1 15 8 8 15 1 8z" fill="currentColor" /><path d="M6 10l4-4M9 5.5l1.5 1.5" stroke="var(--surface)" strokeWidth="1.4" strokeLinecap="round" /></svg>;
    case 'person': return <svg {...common}><rect x="1" y="1" width="14" height="14" rx="3.5" fill="currentColor" /><circle cx="8" cy="6" r="2.2" fill="var(--surface)" /><path d="M4 12.6c.6-2 2.2-3.1 4-3.1s3.4 1.1 4 3.1" fill="var(--surface)" /></svg>;
    case 'square-q': return <svg {...common}><rect x="1.5" y="1.5" width="13" height="13" rx="1.5" fill="currentColor" /><path d="M6.3 6.2a1.8 1.8 0 1 1 2.5 1.7c-.6.3-.8.6-.8 1.2v.4" stroke="var(--surface)" strokeWidth="1.4" fill="none" strokeLinecap="round" /><circle cx="8" cy="11.6" r=".8" fill="var(--surface)" /></svg>;
    case 'half': return <svg {...common}><circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.6" /><path d="M8 1.5a6.5 6.5 0 0 1 0 13z" fill="currentColor" /></svg>;
    case 'ring': return <svg {...common}><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeWidth="1.8" /></svg>;
    case 'dashed-ring': return <svg {...common}><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeDasharray="2.6 2.2" /></svg>;
    case 'ring-bar': return <svg {...common}><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeWidth="1.6" /><path d="M4.8 8h6.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>;
    case 'check': return <svg {...common}><circle cx="8" cy="8" r="7" fill="currentColor" /><path d="m4.8 8.2 2.2 2.2 4.3-4.6" stroke="var(--surface)" strokeWidth="1.7" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>;
    case 'slash': return <svg {...common}><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeWidth="1.5" /><path d="m3.8 12.2 8.4-8.4" stroke="currentColor" strokeWidth="1.5" /></svg>;
    case 'dotted-q': return <svg {...common}><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="1 2" /><text x="8" y="11" fontSize="8" textAnchor="middle" fill="currentColor">?</text></svg>;
    case 'dashed-square': return <svg {...common}><rect x="2" y="2" width="12" height="12" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="2.4 2" /></svg>;
    default: return null;
  }
}

export function StatusGlyph({ state, size = 14, label }: { state: CellState; size?: number; label?: string }) {
  const s = STATE[state];
  return (
    <span className={`inline-flex ${ROLE_CLASSES[s.role].fg}`} role="img" aria-label={label || s.label} title={label || s.label}>
      <GlyphSvg glyph={s.glyph} size={size} />
    </span>
  );
}

export function StatusBadge({ state, label, className = '' }: { state: CellState; label?: string; className?: string }) {
  const s = STATE[state];
  const r = ROLE_CLASSES[s.role];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded border px-1.5 py-0.5 text-xs font-medium ${r.fg} ${r.bg} ${r.border} ${className}`}>
      <GlyphSvg glyph={s.glyph} size={12} />
      <span>{label || s.label}</span>
    </span>
  );
}

export function FailureTag({ cls, verified }: { cls: FailureClass | null; verified?: boolean }) {
  if (!cls) return null;
  const f = FAILURE[cls];
  return (
    <span className="inline-flex items-center gap-1 rounded bg-surface-3 px-1.5 py-0.5 text-xs text-muted" title={f.label}>
      <span className="font-medium text-fg">{f.short}</span>
      {verified === false && <span className="text-tool">· cause unverified</span>}
    </span>
  );
}

export function ModifierIcons({ modifiers, compact = true }: { modifiers: CellModifier[]; compact?: boolean }) {
  const shown = modifiers.filter((m) => m !== 'live');
  if (!shown.length) return null;
  return (
    <span className="inline-flex items-center gap-0.5">
      {shown.map((m) => {
        const meta = MODIFIER[m];
        const icon = m === 'conflict' ? <AlertTriangle size={12} className="text-fail" /> : m === 'unattributed' ? <UserRound size={12} className="text-warn" /> : m === 'carried_over' ? <Layers size={12} className="text-warn" /> : m === 'compile_failed_fix' ? <CircleSlash size={12} className="text-subtle" /> : m === 'declared_manual_edit' ? <Info size={12} className="text-tool" /> : <Info size={12} />;
        return (
          <span key={m} title={`${meta.label}: ${meta.description}`} aria-label={meta.label} role="img" className="inline-flex">
            {icon}
            {!compact && <span className="ml-0.5 text-xs">{meta.label}</span>}
          </span>
        );
      })}
    </span>
  );
}

// ------------------------------------------------------------------------------- provenance
export function ProvenanceBadge({ p, actor, className = '' }: { p: Provenance; actor?: string | null; className?: string }) {
  const meta = PROVENANCE[p];
  const icon = p === 'computed' ? <Cog size={11} /> : p === 'ai_authored' ? <Bot size={11} /> : p === 'human' ? <UserRound size={11} /> : p === 'mixed' ? <Layers size={11} /> : <Info size={11} />;
  return (
    <span title={meta.description} className={`inline-flex items-center gap-1 rounded-sm border border-line bg-surface-2 px-1.5 py-px text-[11px] font-medium uppercase tracking-wide text-muted ${className}`}>
      {icon}
      {meta.short}
      {actor ? <span className="normal-case tracking-normal text-subtle">· {actor}</span> : null}
    </span>
  );
}

// ------------------------------------------------------------------------------------- bits
export function HashChip({ sha, len = 10 }: { sha: string | null | undefined; len?: number }) {
  const [copied, setCopied] = useState(false);
  if (!sha) return <span className="text-xs text-subtle">no hash</span>;
  return (
    <button type="button" className="mono inline-flex items-center gap-1 rounded bg-surface-3 px-1.5 py-px text-[11px] text-muted hover:text-fg" title={`sha256 ${sha} — click to copy`}
      onClick={() => {
        void navigator.clipboard?.writeText(sha).then(() => setCopied(true));
        setTimeout(() => setCopied(false), 1200);
      }}>
      {sha.slice(0, len)}
      {copied ? <Check size={10} /> : <Copy size={10} />}
    </button>
  );
}

export function SeverityBadge({ s }: { s: string | null }) {
  if (!s) return null;
  const cls = s === 'Critical' ? 'text-fail border-fail' : s === 'High' ? 'text-tool border-tool' : s === 'Medium' ? 'text-warn border-warn' : 'text-neutral border-line-strong';
  return <span className={`rounded border px-1.5 py-px text-[11px] font-semibold ${cls}`}>{s}</span>;
}

export function relTime(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—';
  const t = Date.parse(iso.length === 10 ? `${iso}T00:00:00Z` : iso);
  if (!Number.isFinite(t)) return iso;
  const d = Math.round((now - t) / 1000);
  const abs = Math.abs(d);
  const fmt = (n: number, u: string) => `${n} ${u}${n === 1 ? '' : 's'}`;
  const v = abs < 60 ? fmt(abs, 'second') : abs < 3600 ? fmt(Math.round(abs / 60), 'minute') : abs < 86400 ? fmt(Math.round(abs / 3600), 'hour') : fmt(Math.round(abs / 86400), 'day');
  return d >= 0 ? `${v} ago` : `in ${v}`;
}

export function Time({ iso, mode = 'abs', source }: { iso: string | null | undefined; mode?: 'abs' | 'rel' | 'both'; source?: 'observed' | 'reconstructed' | 'unknown' }) {
  if (!iso) return <span className="text-subtle">{source === 'unknown' ? 'time unknown' : '—'}</span>;
  const dateOnly = iso.length === 10;
  const abs = dateOnly ? iso : iso.replace('T', ' ').replace(/\.\d+Z$/, 'Z').replace(/Z$/, ' UTC');
  return (
    <span className="mono tnum whitespace-nowrap text-xs" title={`${iso}${source ? ` (${source})` : ''}`}>
      {mode === 'rel' ? relTime(iso) : mode === 'both' ? `${abs} · ${relTime(iso)}` : abs}
      {source === 'reconstructed' && <span className="ml-1 font-sans text-[10px] uppercase text-subtle">recon</span>}
    </span>
  );
}

export function duration(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return '—';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  const s = ms / 1000;
  if (s < 59.5) return `${s.toFixed(s < 10 ? 1 : 0)} s`;
  // Round the total first so 119.6 s reads "2m 0s", never "1m 60s".
  const total = Math.round(s);
  const m = Math.floor(total / 60);
  if (m < 60) return `${m}m ${total % 60}s`;
  return `${Math.floor(m / 60)}h ${m % 60}m`;
}

export function Panel({ title, actions, children, className = '', id, subtitle }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; id?: string; subtitle?: ReactNode }) {
  return (
    <section className={`rounded-md border border-line bg-surface ${className}`} aria-labelledby={id}>
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-3 py-2">
          <div className="min-w-0">
            {title && <h2 id={id} className="text-[13px] font-semibold uppercase tracking-wide text-muted">{title}</h2>}
            {subtitle && <div className="text-xs text-subtle">{subtitle}</div>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      <div>{children}</div>
    </section>
  );
}

export function KV({ items, className = '' }: { items: { k: ReactNode; v: ReactNode }[]; className?: string }) {
  return (
    <dl className={`grid grid-cols-[minmax(110px,max-content)_1fr] gap-x-4 gap-y-1.5 text-sm ${className}`}>
      {items.map((it, i) => (
        <div key={i} className="contents">
          <dt className="text-muted">{it.k}</dt>
          <dd className="min-w-0 break-words">{it.v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function EmptyState({ title, body, action, icon }: { title: string; body?: ReactNode; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-start gap-1 px-4 py-6 text-sm">
      <div className="flex items-center gap-2 font-medium">{icon}{title}</div>
      {body && <div className="max-w-prose text-muted">{body}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const e = error as ApiError | Error | null;
  const status = e instanceof ApiError ? e.status : null;
  const title = status === 0 ? 'Mission Control server unreachable' : status === 404 ? 'Not found' : 'Could not load this view';
  return (
    <div role="alert" className="m-3 rounded-md border border-fail bg-fail-tint px-4 py-3 text-sm">
      <div className="flex items-center gap-2 font-semibold text-fail"><AlertTriangle size={16} />{title}</div>
      <div className="mt-1 text-fg">{e?.message || 'Unknown error.'}</div>
      {e instanceof ApiError && e.code && <div className="mono mt-1 text-xs text-muted">{e.code}{status ? ` · HTTP ${status}` : ''}</div>}
      {retry && <button type="button" className="mt-2 rounded border border-line bg-surface px-2 py-1 text-xs hover:bg-surface-3" onClick={retry}>Retry</button>}
    </div>
  );
}

export function Loading({ label = 'Loading…', rows = 4 }: { label?: string; rows?: number }) {
  return (
    <div className="space-y-2 p-3" aria-busy="true" aria-live="polite">
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }).map((_, i) => <div key={i} className="h-5 animate-pulse rounded bg-surface-3 motion-reduce:animate-none" style={{ width: `${90 - i * 12}%` }} />)}
    </div>
  );
}

// ------------------------------------------------------------------------------------- tabs
export function Tabs<T extends string>({ tabs, value, onChange, label }: { tabs: { id: T; label: ReactNode; count?: number; warn?: boolean }[]; value: T; onChange: (t: T) => void; label: string }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const onKey = (e: KeyboardEvent, i: number) => {
    let n = i;
    if (e.key === 'ArrowRight') n = (i + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') n = (i - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') n = 0;
    else if (e.key === 'End') n = tabs.length - 1;
    else return;
    e.preventDefault();
    refs.current[n]?.focus();
    onChange(tabs[n].id);
  };
  return (
    <div role="tablist" aria-label={label} className="flex gap-1 overflow-x-auto border-b border-line">
      {tabs.map((t, i) => (
        <button key={t.id} ref={(el) => { refs.current[i] = el; }} role="tab" type="button" aria-selected={value === t.id} tabIndex={value === t.id ? 0 : -1}
          onKeyDown={(e) => onKey(e, i)} onClick={() => onChange(t.id)}
          className={`-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm ${value === t.id ? 'border-accent font-semibold text-fg' : 'border-transparent text-muted hover:text-fg'}`}>
          {t.label}
          {t.count != null && <span className={`ml-1.5 rounded px-1 text-xs tnum ${t.warn ? 'bg-fail-tint text-fail' : 'bg-surface-3 text-muted'}`}>{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

// ----------------------------------------------------------------------------------- drawer
export function Drawer({ open, onClose, title, children, width = 440 }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; width?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const returnTo = useRef<HTMLElement | null>(null);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    returnTo.current = document.activeElement as HTMLElement;
    ref.current?.focus();
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      returnTo.current?.focus?.();
    };
  }, [open, onClose]);
  if (!open) return null;
  return (
    <aside ref={ref} tabIndex={-1} aria-labelledby={id} className="fixed inset-y-0 right-0 z-40 flex max-w-full flex-col border-l border-line bg-surface shadow-xl outline-none" style={{ width }}>
      <header className="flex items-start justify-between gap-2 border-b border-line px-4 py-3">
        <h2 id={id} className="min-w-0 text-sm font-semibold">{title}</h2>
        <button type="button" onClick={onClose} aria-label="Close panel" className="rounded p-1 text-muted hover:bg-surface-3 hover:text-fg"><X size={16} /></button>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">{children}</div>
    </aside>
  );
}

// -------------------------------------------------------------------------------- data table
export function DataTable<T>({ data, columns, onRowClick, empty, initialSort, dense = false, rowKey, label }: {
  data: T[]; columns: ColumnDef<T, unknown>[]; onRowClick?: (row: T) => void; empty?: ReactNode; initialSort?: SortingState; dense?: boolean; rowKey?: (row: T) => string; label: string;
}) {
  const [sorting, setSorting] = useState<SortingState>(initialSort || []);
  const table = useReactTable({ data, columns, state: { sorting }, onSortingChange: setSorting, getCoreRowModel: getCoreRowModel(), getSortedRowModel: getSortedRowModel(), getRowId: rowKey ? (r) => rowKey(r) : undefined });
  const rows = table.getRowModel().rows;
  const h = dense ? 'h-7' : 'h-9';
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm" aria-label={label}>
        <thead className="sticky top-0 z-10 bg-surface-2">
          {table.getHeaderGroups().map((hg) => (
            <tr key={hg.id}>
              {hg.headers.map((hd) => {
                const sorted = hd.column.getIsSorted();
                return (
                  <th key={hd.id} scope="col" aria-sort={sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : 'none'} className="border-b border-line px-3 py-1.5 text-left text-xs font-semibold uppercase tracking-wide text-muted">
                    {hd.isPlaceholder ? null : hd.column.getCanSort() ? (
                      <button type="button" className="inline-flex items-center gap-1 hover:text-fg" onClick={hd.column.getToggleSortingHandler()}>
                        {flexRender(hd.column.columnDef.header, hd.getContext())}
                        <span aria-hidden>{sorted === 'asc' ? '▲' : sorted === 'desc' ? '▼' : ''}</span>
                      </button>
                    ) : flexRender(hd.column.columnDef.header, hd.getContext())}
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr><td colSpan={columns.length}>{empty || <EmptyState title="Nothing to show" />}</td></tr>
          )}
          {rows.map((r) => (
            <tr key={r.id} className={`${h} border-b border-line ${onRowClick ? 'cursor-pointer hover:bg-surface-2' : ''}`}
              onClick={onRowClick ? () => onRowClick(r.original) : undefined}
              onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter') onRowClick(r.original); } : undefined}
              tabIndex={onRowClick ? 0 : undefined}>
              {r.getVisibleCells().map((c) => <td key={c.id} className="px-3 py-1 align-middle">{flexRender(c.column.columnDef.cell, c.getContext())}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function useTick(ms = 1000): number {
  const [n, setN] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setN(Date.now()), ms);
    return () => clearInterval(t);
  }, [ms]);
  return n;
}

export function useFilter<T>(items: T[] | undefined, pred: (t: T) => boolean, deps: unknown[]): T[] {
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => (items || []).filter(pred), [items, ...deps]);
}

export function Pill({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'warn' | 'fail' | 'pass' | 'run' | 'human' | 'tool' }) {
  const r = ROLE_CLASSES[tone];
  return <span className={`inline-flex items-center gap-1 rounded px-1.5 py-px text-xs ${r.bg} ${r.fg}`}>{children}</span>;
}
