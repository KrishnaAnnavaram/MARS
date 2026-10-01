import type { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRootRoute, createRouter } from '@tanstack/react-router';

/** Renders `ui` inside a memory router and a fresh query client (components use Link and queries). */
export async function renderApp(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const root = createRootRoute({ component: () => <>{ui}</> });
  const router = createRouter({ routeTree: root, history: createMemoryHistory({ initialEntries: ['/'] }) });
  const r = render(<QueryClientProvider client={qc}><RouterProvider router={router} /></QueryClientProvider>);
  await router.load();
  return { ...r, qc, router };
}
