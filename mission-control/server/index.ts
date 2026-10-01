#!/usr/bin/env node
/**
 * MARS Mission Control server.
 *
 *   node dist/server/server/index.js --workspace <repo> [--port 7440] [--host 127.0.0.1]
 *        [--ledger-dir <dir>] [--enable-decisions] [--static <dir>]
 *
 * Binds to 127.0.0.1 by default. There is no authentication in this version: decisions, when enabled,
 * are recorded as LOCALLY_ASSERTED and are refused when the server runs inside an agent session.
 */
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Model } from './model.js';
import { createApp } from './app.js';

function parse(argv: string[]): Record<string, string | boolean> {
  const out: Record<string, string | boolean> = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (!a.startsWith('--')) continue;
    const key = a.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) {
      out[key] = next;
      i += 1;
    } else out[key] = true;
  }
  return out;
}

const args = parse(process.argv.slice(2));
const here = path.dirname(fileURLToPath(import.meta.url));
const pkgRoot = path.resolve(here, here.includes(`${path.sep}dist${path.sep}`) ? '../../..' : '..');
const workspace = path.resolve(String(args.workspace || path.resolve(pkgRoot, '..')));
const port = Number(args.port || process.env.MC_PORT || 7440);
const host = String(args.host || process.env.MC_HOST || '127.0.0.1');
const staticDir = args.static ? path.resolve(String(args.static)) : path.join(pkgRoot, 'dist', 'web');

const loopback = ['127.0.0.1', 'localhost', '::1'].includes(host);
const wantDecisions = Boolean(args['enable-decisions']) || process.env.MC_ENABLE_DECISIONS === '1';
if (wantDecisions && !loopback) {
  // Decisions are LOCALLY_ASSERTED; without authentication they must not be reachable from the network.
  console.error(`Refusing --enable-decisions on non-loopback host ${host}: there is no authentication in this version.`);
  process.exit(2);
}
const model = new Model({
  root: workspace,
  ledgerDir: args['ledger-dir'] ? String(args['ledger-dir']) : undefined,
  enableDecisions: wantDecisions,
});
model.start();
const decisionToken = wantDecisions ? crypto.randomBytes(24).toString('base64url') : null;
const server = createApp(model, { staticDir, allowedHosts: loopback ? [] : [host.toLowerCase()], decisionToken });
server.listen(port, host, () => {
  const d = model.decisionsEnabled();
  console.log(`MARS Mission Control on http://${host}:${port}`);
  if (decisionToken && d.enabled) console.log(`  to record decisions, open: http://${host}:${port}/#mc-token=${decisionToken}  (this link is the only way to decide; keep it private)`);
  console.log(`  workspace: ${workspace}`);
  console.log(`  ledger:    ${model.ledger.dir}`);
  console.log(`  decisions: ${d.enabled ? 'enabled' : 'disabled'} — ${d.reason}`);
  if (!loopback) console.warn('  WARNING: bound to a non-loopback address with no authentication.');
});
model.healthChecks().catch(() => undefined);

const shutdown = () => {
  model.stop();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2000).unref();
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
