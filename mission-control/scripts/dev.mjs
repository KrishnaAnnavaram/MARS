// Runs the API server (tsx watch) and the Vite dev server together; Ctrl+C stops both.
import { spawn } from 'node:child_process';

const npx = process.platform === 'win32' ? 'npx.cmd' : 'npx';
const extra = process.argv.slice(2);
const procs = [
  spawn(npx, ['tsx', 'watch', 'server/index.ts', '--workspace', '..', ...extra], { stdio: 'inherit', shell: process.platform === 'win32' }),
  spawn(npx, ['vite'], { stdio: 'inherit', shell: process.platform === 'win32' }),
];
const stop = () => {
  for (const p of procs) if (!p.killed) p.kill();
  process.exit(0);
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
for (const p of procs) p.on('exit', (code) => { if (code) stop(); });
