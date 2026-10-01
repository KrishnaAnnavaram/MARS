import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';

if (typeof window !== 'undefined') {
  const { cleanup } = await import('@testing-library/react');
  afterEach(() => cleanup());
  // jsdom does not implement scrolling; the router's scroll restoration calls it.
  window.scrollTo = (() => undefined) as typeof window.scrollTo;
}
