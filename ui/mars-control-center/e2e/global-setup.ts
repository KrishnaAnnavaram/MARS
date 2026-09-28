import fs from 'node:fs';
import path from 'node:path';

/** Every E2E run starts from an empty runs root (the throwaway one the web server is pointed at). */
export default function globalSetup() {
  const runs = path.resolve(import.meta.dirname, '.runs');
  fs.rmSync(runs, { recursive: true, force: true });
  fs.mkdirSync(runs, { recursive: true });
}
