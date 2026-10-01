import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, Outlet, useNavigate, useRouterState } from '@tanstack/react-router';
import { Activity, Boxes, ClipboardCheck, FileSearch, GitBranch, LayoutDashboard, Menu, Moon, Network, ScrollText, Search, ShieldAlert, Sun, Wrench, Eye } from 'lucide-react';
import { useOverview, useRegistry } from '../api';
import { useLive } from '../live';
import { relTime, useTick } from './ui';

const NAV = [
  { to: '/', label: 'Mission Control', icon: LayoutDashboard },
  { to: '/issues', label: 'Issues', icon: ShieldAlert },
  { to: '/approvals', label: 'Approvals', icon: ClipboardCheck },
  { to: '/runs', label: 'Runs', icon: Activity },
  { to: '/evidence', label: 'Evidence', icon: FileSearch },
  { to: '/audit', label: 'Audit', icon: ScrollText },
  { to: '/architecture', label: 'Architecture', icon: Network },
  { to: '/harness', label: 'Harness', icon: Boxes },
] as const;

function useTheme() {
  const [theme, setTheme] = useState<string>(() => {
    try {
      return localStorage.getItem('mc-theme') || 'system';
    } catch {
      return 'system';
    }
  });
  const [cvd, setCvd] = useState<boolean>(() => {
    try {
      return localStorage.getItem('mc-cvd') === 'on';
    } catch {
      return false;
    }
  });
  useEffect(() => {
    const el = document.documentElement;
    if (theme === 'system') delete el.dataset.theme;
    else el.dataset.theme = theme;
    el.dataset.cvd = cvd ? 'on' : 'off';
    try {
      localStorage.setItem('mc-theme', theme);
      localStorage.setItem('mc-cvd', cvd ? 'on' : 'off');
    } catch {
      /* preferences are optional */
    }
  }, [theme, cvd]);
  return { theme, setTheme, cvd, setCvd };
}

function ConnectionStatus() {
  const live = useLive();
  const now = useTick(5000);
  const label = live.status === 'live' ? 'Live' : live.status === 'reconnecting' ? 'Reconnecting' : live.status === 'connecting' ? 'Connecting' : 'Offline';
  const tone = live.status === 'live' ? 'text-pass' : live.status === 'offline' ? 'text-fail' : 'text-tool';
  return (
    <span className="inline-flex items-center gap-1.5 text-xs" title={`Event stream: ${label}${live.lastMessageAt ? ` · last message ${relTime(new Date(live.lastMessageAt).toISOString(), now)}` : ''}${live.reconnects ? ` · ${live.reconnects} reconnects` : ''}${live.gaps ? ` · ${live.gaps} gaps recovered` : ''}`}>
      <span className={`inline-block h-2 w-2 rounded-full ${live.status === 'live' ? 'bg-pass' : live.status === 'offline' ? 'bg-fail' : 'bg-tool'}`} aria-hidden />
      <span className={tone}>{label}</span>
      <span className="hidden text-subtle lg:inline">· seq {live.lastSeq}</span>
    </span>
  );
}

function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null);
  const [q, setQ] = useState('');
  const [idx, setIdx] = useState(0);
  const nav = useNavigate();
  const ov = useOverview();
  const reg = useRegistry();
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) {
      d.showModal();
      setQ('');
      setIdx(0);
    }
    if (!open && d.open) d.close();
  }, [open]);
  const items = useMemo(() => {
    const out: { label: string; hint: string; go: () => void }[] = NAV.map((n) => ({ label: n.label, hint: 'Go to', go: () => nav({ to: n.to }) }));
    for (const i of ov.data?.issues || []) out.push({ label: `${i.id} — ${i.title}`, hint: 'Issue', go: () => nav({ to: '/issues/$issueId', params: { issueId: i.id } }) });
    for (const a of reg.data?.agents || []) out.push({ label: a.id, hint: 'Agent', go: () => nav({ to: '/harness/agents/$agentId', params: { agentId: a.id } }) });
    for (const s of reg.data?.skills || []) out.push({ label: s.id, hint: 'Skill', go: () => nav({ to: '/harness/skills/$skillId', params: { skillId: s.id } }) });
    const ql = q.trim().toLowerCase();
    return (ql ? out.filter((x) => x.label.toLowerCase().includes(ql)) : out).slice(0, 40);
  }, [q, ov.data, reg.data, nav]);
  const pick = (i: number) => {
    const it = items[i];
    if (!it) return;
    it.go();
    onClose();
  };
  return (
    <dialog ref={ref} onClose={onClose} onCancel={onClose} aria-label="Command palette" className="m-auto mt-24 w-[min(640px,92vw)] rounded-lg border border-line bg-surface p-0 text-fg shadow-2xl backdrop:bg-black/40">
      <div className="flex items-center gap-2 border-b border-line px-3 py-2">
        <Search size={16} className="text-subtle" />
        <input autoFocus value={q} onChange={(e) => { setQ(e.target.value); setIdx(0); }} placeholder="Jump to an issue, agent, skill or screen…" aria-label="Search"
          className="w-full bg-transparent py-1 text-sm outline-none"
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setIdx((i) => Math.min(i + 1, items.length - 1)); }
            if (e.key === 'ArrowUp') { e.preventDefault(); setIdx((i) => Math.max(i - 1, 0)); }
            if (e.key === 'Enter') { e.preventDefault(); pick(idx); }
          }} />
        <kbd className="rounded border border-line px-1 text-[10px] text-subtle">Esc</kbd>
      </div>
      <ul role="listbox" aria-label="Results" className="max-h-80 overflow-y-auto py-1">
        {items.map((it, i) => (
          <li key={`${it.hint}-${it.label}`} role="option" aria-selected={i === idx}>
            <button type="button" onMouseEnter={() => setIdx(i)} onClick={() => pick(i)} className={`flex w-full items-center justify-between gap-2 px-3 py-1.5 text-left text-sm ${i === idx ? 'bg-surface-3' : ''}`}>
              <span className="truncate">{it.label}</span>
              <span className="text-xs text-subtle">{it.hint}</span>
            </button>
          </li>
        ))}
        {!items.length && <li className="px-3 py-2 text-sm text-muted">No matches.</li>}
      </ul>
    </dialog>
  );
}

export function Shell() {
  const ov = useOverview();
  const { theme, setTheme, cvd, setCvd } = useTheme();
  const [palette, setPalette] = useState(false);
  const [navOpen, setNavOpen] = useState(false);
  const path = useRouterState({ select: (s) => s.location.pathname });
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPalette(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  useEffect(() => setNavOpen(false), [path]);
  const ws = ov.data?.workspace;
  const decisions = ov.data?.summary.awaitingDecision ?? 0;
  const review = ov.data?.summary.approvalsToReview ?? 0;
  const crit = ov.data?.attention.filter((a) => a.severity === 'critical').length ?? 0;
  return (
    <div className="flex h-full min-h-screen flex-col">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-surface focus:px-2 focus:py-1">Skip to content</a>
      <header className="sticky top-0 z-30 flex h-12 items-center gap-3 border-b border-line bg-surface px-3">
        <button type="button" className="rounded p-1 text-muted hover:bg-surface-3 md:hidden" aria-label="Open navigation" aria-expanded={navOpen} onClick={() => setNavOpen((v) => !v)}><Menu size={18} /></button>
        <Link to="/" className="flex items-center gap-2 font-semibold text-fg hover:no-underline">
          <span className="grid h-6 w-6 place-items-center rounded bg-fg text-[11px] font-bold text-surface" aria-hidden>M</span>
          <span className="hidden sm:inline">MARS Mission Control</span>
        </Link>
        {ws && (
          <span className="hidden min-w-0 items-center gap-1.5 truncate text-xs text-muted lg:flex" title={ws.root}>
            <GitBranch size={13} />
            <span className="truncate">{ws.name} · {ws.branch || 'detached'}</span>
            <span className="mono text-subtle">{ws.head?.slice(0, 7)}</span>
          </span>
        )}
        <span className="rounded border border-line px-1.5 py-px text-[10px] font-semibold uppercase tracking-wide text-muted" title="Local deployment: bound to 127.0.0.1; identity is locally asserted">Local</span>
        <div className="ml-auto flex items-center gap-2 sm:gap-3">
          <ConnectionStatus />
          <button type="button" onClick={() => setPalette(true)} className="hidden items-center gap-2 rounded border border-line bg-surface-2 px-2 py-1 text-xs text-muted hover:text-fg sm:flex" aria-label="Open command palette (Ctrl+K)">
            <Search size={13} /> Search <kbd className="rounded border border-line px-1 text-[10px]">Ctrl K</kbd>
          </button>
          <Link to="/approvals" className={`inline-flex items-center gap-1 rounded px-1.5 py-1 text-xs hover:no-underline ${decisions || review ? 'bg-human text-surface' : 'text-muted hover:bg-surface-3'}`} title={`${decisions} plan decision(s) awaiting a human · ${review} approval(s) to re-review`}>
            <ClipboardCheck size={14} /> <span className="tnum">{decisions + review}</span><span className="sr-only"> plan decisions or approvals awaiting a human</span>
          </Link>
          {crit > 0 && <Link to="/" hash="attention" className="inline-flex items-center gap-1 rounded bg-fail-tint px-1.5 py-1 text-xs text-fail hover:no-underline" title="Critical items needing a human"><ShieldAlert size={14} /> {crit}<span className="sr-only"> critical items need a human</span></Link>}
          <button type="button" className="rounded p-1 text-muted hover:bg-surface-3" aria-label={`Theme: ${theme}. Change theme`} title={`Theme: ${theme}`}
            onClick={() => setTheme(theme === 'system' ? 'dark' : theme === 'dark' ? 'light' : 'system')}>
            {theme === 'dark' ? <Moon size={16} /> : theme === 'light' ? <Sun size={16} /> : <span className="text-[10px] font-semibold">AUTO</span>}
          </button>
          <button type="button" className={`rounded p-1 hover:bg-surface-3 ${cvd ? 'text-accent' : 'text-muted'}`} aria-pressed={cvd} aria-label="Colour-blind safe palette" title="Colour-blind safe palette (orange/blue)" onClick={() => setCvd(!cvd)}><Eye size={16} /></button>
          <span className="hidden text-xs text-muted xl:inline" title="No identity provider is configured; the OS user is shown as a locally asserted identity.">{ws?.decisions.actor || 'local user'} · <span className="text-subtle">locally asserted</span></span>
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        <nav aria-label="Primary" className={`${navOpen ? 'fixed inset-y-12 left-0 z-30 flex shadow-xl' : 'hidden'} w-52 shrink-0 flex-col gap-0.5 border-r border-line bg-surface p-2 md:static md:flex md:w-14 min-[1440px]:w-52`}>
          {NAV.map((n) => {
            const active = n.to === '/' ? path === '/' : path.startsWith(n.to);
            const Icon = n.icon;
            return (
              <Link key={n.to} to={n.to} className={`flex items-center gap-2.5 rounded px-2 py-1.5 text-sm hover:no-underline ${active ? 'bg-surface-3 font-semibold text-fg' : 'text-muted hover:bg-surface-2 hover:text-fg'}`} aria-current={active ? 'page' : undefined} title={n.label}>
                <Icon size={17} className="shrink-0" />
                <span className="md:sr-only min-[1440px]:not-sr-only">{n.label}</span>
                {n.to === '/approvals' && decisions > 0 && <span className="ml-auto rounded bg-human px-1 text-[10px] text-surface md:hidden min-[1440px]:inline">{decisions}</span>}
              </Link>
            );
          })}
          <div className="mt-auto px-2 pb-1 pt-4 text-[10px] leading-4 text-subtle md:hidden min-[1440px]:block">
            Read-only console. Evidence is authority; events are witnesses.
            <div className="mt-1 flex items-center gap-1"><Wrench size={10} /> v0.1</div>
          </div>
        </nav>
        <main id="main" className="min-w-0 flex-1 overflow-x-hidden">
          <Outlet />
        </main>
      </div>
      <CommandPalette open={palette} onClose={() => setPalette(false)} />
    </div>
  );
}

export function PageHeader({ title, subtitle, actions, crumbs }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode; crumbs?: ReactNode }) {
  return (
    <div className="border-b border-line bg-surface px-4 py-3 sm:px-6">
      {crumbs && <div className="mb-1 text-xs text-muted">{crumbs}</div>}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold leading-7">{title}</h1>
          {subtitle && <div className="mt-0.5 text-sm text-muted">{subtitle}</div>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </div>
  );
}
