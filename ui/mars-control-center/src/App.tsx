import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router';
import { useSession } from './api/queries';
import { ErrorState, LoadingState } from './components/ui';
import { AppShell } from './layout/AppShell';
import { RunLayout } from './layout/RunLayout';
import { LoginPage } from './pages/LoginPage';
import { RunsPage } from './pages/RunsPage';
import { SettingsPage } from './pages/SettingsPage';
import { OverviewPage } from './pages/run/OverviewPage';
import { ActivityPage } from './pages/run/ActivityPage';
import { ActionsPage } from './pages/run/ActionsPage';
import { SecurityPage } from './pages/run/SecurityPage';
import { FindingPage } from './pages/run/FindingPage';
import { MigrationPage } from './pages/run/MigrationPage';
import { ChangesPage } from './pages/run/ChangesPage';
import { EvidencePage } from './pages/run/EvidencePage';
import { ValidationPage } from './pages/run/ValidationPage';
import { VerdictPage } from './pages/run/VerdictPage';
import { LogsPage } from './pages/run/LogsPage';

const PipelinePage = lazy(() => import('./pages/run/PipelinePage').then((m) => ({ default: m.PipelinePage })));
const ProposalPage = lazy(() => import('./pages/run/ProposalPage').then((m) => ({ default: m.ProposalPage })));
const GraphPage = lazy(() => import('./pages/run/GraphPage').then((m) => ({ default: m.GraphPage })));

const lazyRoute = (element: ReactNode) => <Suspense fallback={<LoadingState />}>{element}</Suspense>;

export default function App() {
  const session = useSession();
  const location = useLocation();
  if (session.isLoading) return <LoadingState label="Connecting to the Control Center…" />;
  if (session.error) return <ErrorState error={session.error} title="The Control Center API cannot be reached" />;
  const signedIn = session.data?.authenticated;
  if (!signedIn && location.pathname !== '/login') {
    return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  }
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/runs" replace />} />
        <Route path="/runs" element={<RunsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/runs/:runId" element={<RunLayout />}>
          <Route index element={<Navigate to="overview" replace />} />
          <Route path="overview" element={<OverviewPage />} />
          <Route path="pipeline" element={lazyRoute(<PipelinePage />)} />
          <Route path="activity" element={<ActivityPage />} />
          <Route path="actions" element={<ActionsPage />} />
          <Route path="security" element={<SecurityPage />} />
          <Route path="security/:findingId" element={<FindingPage />} />
          <Route path="migration" element={<MigrationPage />} />
          <Route path="changes" element={<ChangesPage />} />
          <Route path="changes/:proposalId" element={lazyRoute(<ProposalPage />)} />
          <Route path="graph" element={lazyRoute(<GraphPage />)} />
          <Route path="evidence" element={<EvidencePage />} />
          <Route path="validation" element={<ValidationPage />} />
          <Route path="verdict" element={<VerdictPage />} />
          <Route path="logs" element={<LogsPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/runs" replace />} />
      </Route>
    </Routes>
  );
}
