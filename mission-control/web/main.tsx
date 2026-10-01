import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import '@fontsource/ibm-plex-mono/400.css';
import './styles.css';
import { captureDecisionToken, getJson, queryClient } from './api';
import { initLive } from './live';
import { router } from './router';

captureDecisionToken();

initLive({
  EventSourceImpl: window.EventSource,
  fetchJson: (url) => getJson(url),
  invalidate: (keys) => {
    if (keys === 'all') void queryClient.invalidateQueries();
    else for (const k of keys) void queryClient.invalidateQueries({ queryKey: k });
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
