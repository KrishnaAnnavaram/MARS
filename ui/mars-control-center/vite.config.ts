/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The API is the authority for every value shown; in development Vite proxies to it so the browser
// talks to one origin (session cookie, CSRF cookie and SSE all stay same-origin).
const api = process.env.MARS_CC_API ?? 'http://127.0.0.1:8080';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: api, changeOrigin: false },
      '/actuator': { target: api, changeOrigin: false },
      '/v3': { target: api, changeOrigin: false },
      '/swagger-ui': { target: api, changeOrigin: false },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    // heavy screens (graph, pipeline, diff) are lazy routes, so they split naturally
    chunkSizeWarningLimit: 700,
  },
  test: {
    // React's test utilities exist only in its development build; the machine-wide NODE_ENV must not leak in
    env: { NODE_ENV: 'test' },
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
