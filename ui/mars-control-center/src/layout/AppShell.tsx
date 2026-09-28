import { useQueryClient } from '@tanstack/react-query';
import {
  Activity,
  BadgeCheck,
  FileDiff,
  FlaskConical,
  GitBranch,
  LayoutDashboard,
  List,
  LogOut,
  Network,
  ScrollText,
  Settings,
  ShieldAlert,
  Stamp,
  UserRound,
  Workflow,
  Waypoints,
} from 'lucide-react';
import { NavLink, Outlet, useMatch, useNavigate } from 'react-router';
import { postJson } from '../api/client';
import { keys, useRun, useSession } from '../api/queries';
import { cn } from '../lib/format';

const runNav = [
  { to: 'overview', label: 'Overview', icon: LayoutDashboard },
  { to: 'pipeline', label: 'Pipeline', icon: Workflow },
  { to: 'actions', label: 'Human actions', icon: UserRound },
  { to: 'activity', label: 'Activity', icon: Activity },
  { to: 'security', label: 'Security', icon: ShieldAlert },
  { to: 'migration', label: 'Migration', icon: GitBranch },
  { to: 'changes', label: 'Changes', icon: FileDiff },
  { to: 'graph', label: 'Graph', icon: Network },
  { to: 'evidence', label: 'Evidence', icon: Waypoints },
  { to: 'validation', label: 'Validation', icon: FlaskConical },
  { to: 'verdict', label: 'Verdict', icon: Stamp },
  { to: 'logs', label: 'Logs', icon: ScrollText },
];

function RunNav({ runId }: { runId: string }) {
  const run = useRun(runId);
  const pending = run.data?.human_actions.count ?? 0;
  return (
    <nav aria-label="Run" className="mt-3">
      <div className="px-3 pb-1 text-[10px] font-semibold uppercase tracking-wider text-faint">Run</div>
      <div className="mono truncate px-3 pb-2 text-[11px] text-muted" title={runId}>
        {runId}
      </div>
      <ul>
        {runNav.map(({ to, label, icon: Icon }) => (
          <li key={to}>
            <NavLink
              to={`/runs/${runId}/${to}`}
              className={({ isActive }) =>
                cn('flex items-center gap-2 border-l-2 px-3 py-1.5 text-[13px]',
                  isActive ? 'border-primary bg-panel-2 text-strong' : 'border-transparent text-muted hover:bg-panel-2 hover:text-text')}
            >
              <Icon aria-hidden className="size-4" />
              <span className="flex-1">{label}</span>
              {to === 'actions' && pending > 0 && (
                <span className="rounded bg-human-soft px-1.5 text-[11px] font-semibold text-human" aria-label={`${pending} pending`}>
                  {pending}
                </span>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}

export function AppShell() {
  const match = useMatch('/runs/:runId/*');
  const runId = match?.params.runId;
  const session = useSession();
  const client = useQueryClient();
  const navigate = useNavigate();
  const s = session.data;

  const signOut = async () => {
    await postJson('/api/v1/session/logout', {});
    await client.invalidateQueries({ queryKey: keys.session });
    navigate('/login');
  };

  return (
    <div className="flex h-full">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:bg-panel focus:p-2">
        Skip to content
      </a>
      <aside className="flex w-56 shrink-0 flex-col border-r border-border bg-panel">
        <div className="flex h-12 items-center gap-2 border-b border-border px-3">
          <img src="/mars.svg" alt="" className="size-6" />
          <div className="leading-tight">
            <div className="text-[13px] font-semibold text-strong">MARS</div>
            <div className="text-[10px] uppercase tracking-wider text-faint">Control Center</div>
          </div>
        </div>
        <nav aria-label="Main" className="pt-2">
          <NavLink
            to="/runs"
            end
            className={({ isActive }) =>
              cn('flex items-center gap-2 border-l-2 px-3 py-1.5', isActive ? 'border-primary bg-panel-2 text-strong'
                : 'border-transparent text-muted hover:bg-panel-2 hover:text-text')}
          >
            <List aria-hidden className="size-4" /> Runs
          </NavLink>
        </nav>
        <div className="min-h-0 flex-1 overflow-y-auto">{runId && <RunNav runId={runId} />}</div>
        <div className="border-t border-border p-2">
          <NavLink
            to="/settings"
            className={({ isActive }) => cn('flex items-center gap-2 rounded px-2 py-1.5',
              isActive ? 'bg-panel-2 text-strong' : 'text-muted hover:bg-panel-2 hover:text-text')}
          >
            <Settings aria-hidden className="size-4" /> Settings
          </NavLink>
          {s?.authenticated && (
            <div className="mt-2 rounded border border-border bg-panel-2 p-2 text-[12px]">
              <div className="flex items-center gap-1.5 font-medium text-strong">
                <BadgeCheck aria-hidden className="size-3.5 text-muted" />
                <span className="truncate">{s.display_name ?? s.username}</span>
              </div>
              <div className="text-muted">{s.roles.join(', ')}</div>
              <div className="mt-1 text-[11px] text-faint" title={s.authentication}>
                {s.auth_mode === 'dev' ? 'Dev identity (not authenticated)' : 'OIDC'}
              </div>
              <button type="button" onClick={() => void signOut()} className="mt-1.5 flex items-center gap-1 text-muted hover:text-text">
                <LogOut aria-hidden className="size-3.5" /> Sign out
              </button>
            </div>
          )}
        </div>
      </aside>
      <div id="main" className="flex min-w-0 flex-1 flex-col">
        <Outlet />
      </div>
    </div>
  );
}
