import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

const api = process.env.MC_API || 'http://127.0.0.1:7440';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      // SSE passes through the proxy unbuffered; the server sets X-Accel-Buffering: no.
      '/api': { target: api, changeOrigin: false, ws: false },
    },
  },
  build: {
    outDir: 'dist/web',
    emptyOutDir: true,
    // Not shipped by default (the server also refuses .map unless MC_SOURCEMAPS=1).
    sourcemap: process.env.MC_SOURCEMAPS === '1',
    chunkSizeWarningLimit: 1500,
  },
});
