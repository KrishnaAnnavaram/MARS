import * as Dialog from '@radix-ui/react-dialog';
import { AlertOctagon, Check, Copy, Inbox, Loader2, X } from 'lucide-react';
import { useState, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import type { Progress } from '../api/types';
import { cn, shortHash } from '../lib/format';
import { toneClasses, type StatusStyle, type Tone } from '../lib/status';

/** Icon + text + colour. Status is never communicated by colour alone. */
export function StatusBadge({ status, className, compact = false }: { status: StatusStyle; className?: string; compact?: boolean }) {
  const t = toneClasses[status.tone];
  const Icon = status.icon;
  return (
    <span
      className={cn('inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[11px] font-medium whitespace-nowrap',
        t.text, t.bg, t.border, className)}
    >
      <Icon aria-hidden className={cn('size-3.5 shrink-0', status.spin && 'animate-spin')} />
      {!compact && <span>{status.label}</span>}
      {compact && <span className="sr-only">{status.label}</span>}
    </span>
  );
}

export function Tag({ children, tone = 'muted', className }: { children: ReactNode; tone?: Tone; className?: string }) {
  const t = toneClasses[tone];
  return (
    <span className={cn('inline-flex items-center rounded px-1.5 py-0.5 text-[11px] font-medium', t.text, t.bg, className)}>
      {children}
    </span>
  );
}

export function Panel({ title, actions, children, className, bodyClassName, id }: {
  title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string; id?: string;
}) {
  return (
    <section id={id} className={cn('rounded-md border border-border bg-panel', className)} aria-label={typeof title === 'string' ? title : undefined}>
      {(title || actions) && (
        <header className="flex min-h-9 items-center justify-between gap-2 border-b border-border px-3 py-1.5">
          <h2 className="text-[12px] font-semibold uppercase tracking-wide text-muted">{title}</h2>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={cn('p-3', bodyClassName)}>{children}</div>
    </section>
  );
}

export function KeyValues({ rows, className }: { rows: [ReactNode, ReactNode][]; className?: string }) {
  return (
    <dl className={cn('grid grid-cols-[minmax(110px,max-content)_1fr] gap-x-4 gap-y-1.5', className)}>
      {rows.map(([k, v], i) => (
        <div key={i} className="contents">
          <dt className="text-muted">{k}</dt>
          <dd className="min-w-0 break-words text-text">{v ?? <span className="text-faint">—</span>}</dd>
        </div>
      ))}
    </dl>
  );
}

export function EmptyState({ title, children, icon }: { title: string; children?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-10 text-center" role="status">
      <div className="text-faint">{icon ?? <Inbox aria-hidden className="size-6" />}</div>
      <p className="font-medium text-text">{title}</p>
      {children && <div className="max-w-xl text-muted">{children}</div>}
    </div>
  );
}

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 px-3 py-8 text-muted" role="status" aria-live="polite">
      <Loader2 aria-hidden className="size-4 animate-spin" />
      {label}
    </div>
  );
}

export function ErrorState({ error, title = 'Could not load this view' }: { error: unknown; title?: string }) {
  const api = error instanceof ApiError ? error : undefined;
  return (
    <div role="alert" className="m-3 flex gap-3 rounded-md border border-danger/40 bg-danger-soft p-3 text-text">
      <AlertOctagon aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
      <div className="min-w-0">
        <p className="font-medium">{title}</p>
        <p className="text-muted">{api?.message ?? (error as Error)?.message ?? String(error)}</p>
        {api && (
          <p className="mono mt-1 text-faint">
            {api.code}
            {api.body?.correlation_id ? ` · correlation ${api.body.correlation_id}` : ''}
          </p>
        )}
      </div>
    </div>
  );
}

/** A query's three states in one place, so no panel is ever blank. */
export function QueryView<T>({ query, children, empty }: {
  query: { data?: T; isLoading: boolean; error: unknown }; children: (data: T) => ReactNode; empty?: ReactNode;
}) {
  if (query.isLoading && !query.data) return <LoadingState />;
  if (query.error && !query.data) return <ErrorState error={query.error} />;
  if (query.data === undefined || query.data === null) return <>{empty ?? <EmptyState title="Nothing to show" />}</>;
  return <>{children(query.data)}</>;
}

export function Hash({ value, length = 12, label }: { value?: string; length?: number; label?: string }) {
  const [copied, setCopied] = useState(false);
  if (!value) return <span className="text-faint">—</span>;
  return (
    <span className="inline-flex items-center gap-1">
      <code className="mono text-text" title={value}>{shortHash(value, length)}</code>
      <button
        type="button"
        className="rounded p-0.5 text-faint hover:text-text"
        aria-label={`Copy ${label ?? 'value'}`}
        onClick={() => {
          void navigator.clipboard?.writeText(value);
          setCopied(true);
          setTimeout(() => setCopied(false), 1200);
        }}
      >
        {copied ? <Check aria-hidden className="size-3" /> : <Copy aria-hidden className="size-3" />}
      </button>
    </span>
  );
}

/**
 * Domain progress only. Determinate when the executor reported both counts; otherwise an
 * indeterminate bar with the count done so far, never an invented percentage.
 */
export function ProgressBar({ progress, className }: { progress?: Progress; className?: string }) {
  if (!progress) return null;
  const determinate = progress.mode === 'DETERMINATE' && progress.total !== undefined && progress.total > 0;
  const pct = determinate ? Math.min(100, Math.round(((progress.completed ?? 0) / (progress.total ?? 1)) * 100)) : 0;
  const label = determinate
    ? `${progress.completed} / ${progress.total} ${progress.unit ?? ''}`.trim()
    : `${progress.completed ?? 0} ${progress.unit ?? ''} so far (total not known)`.trim();
  return (
    <div className={cn('flex items-center gap-2', className)}>
      <div
        className="relative h-1.5 flex-1 overflow-hidden rounded bg-panel-3"
        role="progressbar"
        aria-label={label}
        aria-valuemin={determinate ? 0 : undefined}
        aria-valuemax={determinate ? progress.total : undefined}
        aria-valuenow={determinate ? progress.completed : undefined}
      >
        {determinate ? (
          <div className="h-full bg-primary transition-[width]" style={{ width: `${pct}%` }} />
        ) : (
          <div className="mars-indeterminate h-full w-1/3 bg-primary/60" />
        )}
      </div>
      <span className="mono shrink-0 text-muted">{label}</span>
    </div>
  );
}

export function Button({ variant = 'default', size = 'md', className, loading, children, ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'default' | 'primary' | 'danger' | 'ghost' | 'human'; size?: 'sm' | 'md'; loading?: boolean }) {
  const variants = {
    default: 'border-border-strong bg-panel-2 text-text hover:bg-panel-3',
    primary: 'border-primary/60 bg-primary-soft text-primary-strong hover:bg-primary/20',
    danger: 'border-danger/50 bg-danger-soft text-danger hover:bg-danger/20',
    human: 'border-human/60 bg-human-soft text-human hover:bg-human/20',
    ghost: 'border-transparent bg-transparent text-muted hover:bg-panel-3 hover:text-text',
  };
  return (
    <button
      type="button"
      {...rest}
      disabled={rest.disabled || loading}
      className={cn('inline-flex items-center justify-center gap-1.5 rounded border font-medium transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-50', size === 'sm' ? 'h-7 px-2 text-[12px]' : 'h-8 px-3',
        variants[variant], className)}
    >
      {loading && <Loader2 aria-hidden className="size-3.5 animate-spin" />}
      {children}
    </button>
  );
}

export function Modal({ open, onOpenChange, title, description, children, wide }: {
  open: boolean; onOpenChange: (o: boolean) => void; title: string; description?: string; children: ReactNode; wide?: boolean;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/60" />
        <Dialog.Content
          className={cn('fixed left-1/2 top-1/2 z-50 max-h-[90vh] w-[calc(100vw-2rem)] -translate-x-1/2 -translate-y-1/2',
            'overflow-auto rounded-lg border border-border-strong bg-panel p-4 shadow-2xl', wide ? 'max-w-3xl' : 'max-w-lg')}
        >
          <div className="mb-3 flex items-start justify-between gap-4">
            <div>
              <Dialog.Title className="text-[15px] font-semibold text-strong">{title}</Dialog.Title>
              {description && <Dialog.Description className="mt-1 text-muted">{description}</Dialog.Description>}
            </div>
            <Dialog.Close className="rounded p-1 text-muted hover:text-text" aria-label="Close">
              <X aria-hidden className="size-4" />
            </Dialog.Close>
          </div>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function Tabs<T extends string>({ value, onChange, options, label }: {
  value: T; onChange: (v: T) => void; options: { value: T; label: ReactNode }[]; label: string;
}) {
  return (
    <div role="tablist" aria-label={label} className="inline-flex rounded border border-border bg-panel-2 p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          role="tab"
          type="button"
          aria-selected={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn('rounded px-2.5 py-1 text-[12px]', value === o.value ? 'bg-panel-3 text-strong' : 'text-muted hover:text-text')}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: Tone }) {
  return (
    <div className="min-w-0 rounded-md border border-border bg-panel px-3 py-2">
      <div className="text-[11px] uppercase tracking-wide text-faint">{label}</div>
      <div className={cn('mt-0.5 truncate text-[17px] font-semibold', tone ? toneClasses[tone].text : 'text-strong')}>{value}</div>
      {sub && <div className="truncate text-[12px] text-muted">{sub}</div>}
    </div>
  );
}

export function SectionNote({ children }: { children: ReactNode }) {
  return <p className="text-[12px] text-faint">{children}</p>;
}

export function Table({ children, label }: { children: ReactNode; label: string }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-left" aria-label={label}>{children}</table>
    </div>
  );
}

export const th = 'border-b border-border px-2 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-faint whitespace-nowrap';
export const td = 'border-b border-border/60 px-2 py-1.5 align-top';
