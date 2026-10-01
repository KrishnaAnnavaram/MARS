import { createRootRoute, createRoute, createRouter, lazyRouteComponent, Link, type RouteComponent } from '@tanstack/react-router';
import { Shell } from './components/shell';
import { EmptyState } from './components/ui';
import { HomePage } from './pages/home';
import { IssuesPage } from './pages/issues';
import { ApprovalPage, ApprovalsPage } from './pages/approvals';

/** Search params are plain optional strings; anything else in the URL is dropped. */
function strings<K extends string>(...keys: K[]) {
  return (raw: Record<string, unknown>): Partial<Record<K, string>> => {
    const out: Partial<Record<K, string>> = {};
    for (const k of keys) if (typeof raw[k] === 'string' && raw[k]) out[k] = raw[k] as string;
    return out;
  };
}

function NotFound() {
  return <div className="p-6"><EmptyState title="Page not found" body={<Link to="/">Back to Mission Control</Link>} /></div>;
}

// Heavy views (graphs, markdown, virtualised traces) load on demand; Home, Issues and Approvals are eager.
const IssuePage = lazyRouteComponent(() => import('./pages/issue'), 'IssuePage');
const RunsPage = lazyRouteComponent(() => import('./pages/runs'), 'RunsPage');
const RunPage = lazyRouteComponent(() => import('./pages/runs'), 'RunPage');
const EvidencePage = lazyRouteComponent(() => import('./pages/evidence'), 'EvidencePage');
const ArtifactPage = lazyRouteComponent(() => import('./pages/evidence'), 'ArtifactPage');
const AuditPage = lazyRouteComponent(() => import('./pages/audit'), 'AuditPage');
const ArchitecturePage = lazyRouteComponent(() => import('./pages/architecture'), 'ArchitecturePage');
const HarnessPage = lazyRouteComponent(() => import('./pages/harness'), 'HarnessPage');
const AgentPage = lazyRouteComponent(() => import('./pages/harness'), 'AgentPage');
const SkillPage = lazyRouteComponent(() => import('./pages/harness'), 'SkillPage');

const root = createRootRoute({ component: Shell, notFoundComponent: NotFound });
const r = <P extends string>(path: P, component: RouteComponent, keys: string[] = []) =>
  createRoute({ getParentRoute: () => root, path, component, validateSearch: strings(...keys) });

const routeTree = root.addChildren([
  r('/', HomePage),
  r('/issues', IssuesPage, ['tab', 'filter']),
  r('/issues/$issueId', IssuePage, ['tab']),
  r('/approvals', ApprovalsPage),
  r('/approvals/$issueId', ApprovalPage),
  r('/runs', RunsPage),
  r('/runs/$runId', RunPage, ['tab']),
  r('/evidence', EvidencePage, ['tab']),
  r('/evidence/view', ArtifactPage, ['path']),
  r('/audit', AuditPage),
  r('/architecture', ArchitecturePage, ['issue', 'focus', 'view']),
  r('/harness', HarnessPage, ['tab']),
  r('/harness/agents/$agentId', AgentPage),
  r('/harness/skills/$skillId', SkillPage),
]);

export const router = createRouter({ routeTree, defaultPreload: 'intent', scrollRestoration: true, defaultPendingMs: 150 });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
