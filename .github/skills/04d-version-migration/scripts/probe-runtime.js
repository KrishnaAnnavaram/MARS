#!/usr/bin/env node
/**
 * Version Migration — runtime probe (run twice: before and after).
 *
 * A migration that compiles is not a migration that works. This script packages the sandbox
 * workspace on a chosen JDK, starts the application, waits for it to answer, replays a fixed
 * list of HTTP requests, records exactly what came back, and stops the process again.
 *
 * Run it once on the source JDK before touching anything (`--phase baseline`) and once on the
 * target JDK when the build is green (`--phase final`). The report compares the two request by
 * request — that comparison is the only evidence in this skill that behaviour was preserved.
 *
 * Probes are supplied by the agent, which reads the application's own routes first. The
 * default set is a bare liveness check and is not a substitute for real endpoints.
 *
 * What is recorded per request is raw first — status, content type, length, a hash of the
 * whitespace-normalised body and an excerpt — exactly as before. A request may add optional
 * fields that produce *additional* observations beside the raw ones, never instead of them:
 *   category          business-api | security-boundary | serialization | actuator | error-contract | …
 *   expect_status     the status the agent expects; recorded as met / not met
 *   ignore_json_paths dotted paths (a.b, list[*].c) removed before an extra `ignored_paths_hash`
 *   unordered_arrays  true: the extra semantic hash also ignores array order
 *   capture_headers   response headers to record verbatim
 * Nothing is ignored by default — not even timestamps.
 *
 * Usage:
 *   node scripts/probe-runtime.js --slug <slug> --phase baseline --jdk 17 --probes probes.json
 *   node scripts/probe-runtime.js --slug <slug> --phase final --jdk 21 --probes probes.json
 *   node scripts/probe-runtime.js --slug <slug> --discover     # draft probes from the endpoint inventory
 *
 * Endpoint preservation: every run also records the endpoints the running application serves
 * (/actuator/mappings, when exposed) and the endpoints its source maps (static scan of the sandbox
 * as it stands), so the report can show that every endpoint before the migration still exists after.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const {
  sessionPaths, readJson, writeJson, rel, runTool, run, tail, IS_WIN,
  resolveJdk, envForJdk, resolveBuildTool, buildArgs, stripRootFromText,
  recordState, changedSince, createCheckpoint, isSandboxRepo, scanEndpoints,
} = require('./lib/migration');

const DEFAULT_PROBES = {
  base_url: 'http://localhost:8080',
  readiness: { path: '/actuator/health', timeout_seconds: 120 },
  requests: [{ name: 'liveness', method: 'GET', path: '/actuator/health' }],
};

function parseArgs(argv) {
  const args = { phase: 'baseline', port: 8080, appArgs: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--slug' || a === '-s') args.slug = argv[++i];
    else if (a === '--phase') args.phase = argv[++i];
    else if (a === '--jdk' || a === '-j') args.jdk = argv[++i];
    else if (a === '--probes' || a === '-p') args.probes = argv[++i];
    else if (a === '--port') args.port = Number(argv[++i]);
    else if (a === '--no-port-arg') args.noPortArg = true;
    else if (a === '--arg') args.appArgs.push(argv[++i]);
    else if (a === '--rebuild') args.rebuild = true;
    else if (a === '--timeout') args.timeout = Number(argv[++i]);
    else if (a === '--discover') args.discover = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Version Migration — Runtime probe

  node scripts/probe-runtime.js --slug <slug> --phase <baseline|final> --jdk <major> [--probes <file>]

Options:
  --slug, -s      Session name
  --phase         baseline (before migrating) or final (after the build is green)
  --jdk, -j       JDK major version to run the application on
  --probes, -p    JSON file of requests to replay. Default: a single liveness check
  --port          Port to run and probe on (default 8080)
  --no-port-arg   Do not pass --server.port to the application
  --arg           Extra application argument, repeatable
  --rebuild       Repackage even if a jar already exists
  --timeout       Seconds to wait for the app to answer (default from probes, else 120)
  --help, -h      Show this message

Probe file shape:
  {
    "base_url": "http://localhost:8080",
    "auth": { "type": "basic", "username": "demo", "password": "demo123" },
    "readiness": { "path": "/actuator/health", "timeout_seconds": 120 },
    "requests": [
      { "name": "list employees", "method": "GET", "path": "/api/v1/employees" },
      { "name": "unauthenticated is rejected", "method": "GET", "path": "/api/v1/employees", "no_auth": true },
      { "name": "create employee", "method": "POST", "path": "/api/v1/employees",
        "headers": { "Content-Type": "application/json" }, "body": { "firstName": "A" } }
    ]
  }`);
}

function findArtifact(workspace, tool) {
  const dirs = tool === 'gradle'
    ? [path.join(workspace, 'build', 'libs')]
    : [path.join(workspace, 'target')];
  for (const dir of dirs) {
    if (!fs.existsSync(dir)) continue;
    // A Spring Boot executable war runs with `java -jar` just like a jar (war packaging is common for
    // apps also deployed to an external container); a jar is preferred when both exist.
    const runnable = (ext) => fs.readdirSync(dir)
      .filter((f) => f.endsWith(ext) && !new RegExp(`(\\.original|-sources|-javadoc|-plain)\\${ext}$`).test(f))
      .map((f) => path.join(dir, f))
      .sort((a, b) => fs.statSync(b).size - fs.statSync(a).size);
    const found = [...runnable('.jar'), ...runnable('.war')];
    if (found.length) return found[0];
  }
  return null;
}

function hashBody(text) {
  return crypto.createHash('sha256').update(String(text || '').replace(/\s+/g, ' ').trim()).digest('hex').slice(0, 16);
}

/** Canonical JSON: object keys sorted; arrays sorted too only when asked. */
function canonical(value, unorderedArrays = false) {
  if (Array.isArray(value)) {
    const items = value.map((v) => canonical(v, unorderedArrays));
    return unorderedArrays ? items.map((v) => JSON.stringify(v)).sort().map((s) => JSON.parse(s)) : items;
  }
  if (value && typeof value === 'object') {
    return Object.keys(value).sort().reduce((acc, k) => { acc[k] = canonical(value[k], unorderedArrays); return acc; }, {});
  }
  return value;
}

/** Removes an explicitly configured dotted path (`a.b`, `items[*].id`) from a parsed document. */
function removePath(doc, dotted) {
  const parts = String(dotted).split('.').filter(Boolean);
  const walk = (node, i) => {
    if (node === null || typeof node !== 'object') return;
    const raw = parts[i];
    const wildcard = /^(.*)\[\*\]$/.exec(raw);
    const key = wildcard ? wildcard[1] : raw;
    const last = i === parts.length - 1;
    if (wildcard) {
      const list = key ? node[key] : node;
      if (!Array.isArray(list)) return;
      if (last) { if (key) delete node[key]; return; }
      list.forEach((item) => walk(item, i + 1));
      return;
    }
    if (last) { delete node[key]; return; }
    walk(node[key], i + 1);
  };
  walk(doc, 0);
  return doc;
}

const hashJson = (value) => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex').slice(0, 16);

/** Extra, clearly separate observations about a body. The raw hash is computed elsewhere, always. */
function semanticObservations(body, request = {}) {
  let parsed;
  try { parsed = JSON.parse(body); } catch { return { json_parseable: false }; }
  const out = {
    json_parseable: true,
    semantic_hash: hashJson(canonical(parsed, Boolean(request.unordered_arrays))),
    semantic_basis: request.unordered_arrays ? 'sorted keys + sorted arrays' : 'sorted keys',
  };
  const ignore = Array.isArray(request.ignore_json_paths) ? request.ignore_json_paths : [];
  if (ignore.length) {
    const copy = JSON.parse(JSON.stringify(parsed));
    for (const p of ignore) removePath(copy, p);
    out.ignored_paths = ignore;
    out.ignored_paths_hash = hashJson(canonical(copy, Boolean(request.unordered_arrays)));
  }
  return out;
}

/**
 * Basic auth for the probes. Prefer `username_env` / `password_env` (names of environment variables)
 * so no secret is written into probes.json; a literal `username`/`password` is for credentials the
 * application's own source already commits (demo or test users), and is never echoed into a record.
 */
function authHeader(auth) {
  if (!auth || auth.type !== 'basic') return {};
  const username = auth.username_env ? process.env[auth.username_env] : auth.username;
  const password = auth.password_env ? process.env[auth.password_env] : auth.password;
  if (username === undefined || password === undefined) return {};
  const token = Buffer.from(`${username}:${password}`).toString('base64');
  return { Authorization: `Basic ${token}` };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function attempt(url, options, request = null, keepBody = false) {
  try {
    const response = await fetch(url, options);
    const body = await response.text();
    const raw = {
      ok: true,
      status: response.status,
      content_type: response.headers.get('content-type'),
      body_length: body.length,
      body_hash: hashBody(body),
      body_excerpt: body.length > 1500 ? `${body.slice(0, 1500)}…` : body,
    };
    if (keepBody) raw.body_full = body;
    if (!request) return raw;
    const extra = semanticObservations(body, request);
    if (request.expect_status !== undefined) extra.expect_status_met = response.status === Number(request.expect_status);
    if (Array.isArray(request.capture_headers) && request.capture_headers.length) {
      extra.headers = Object.fromEntries(request.capture_headers.map((h) => [h, response.headers.get(h)]));
    }
    return { ...raw, ...extra };
  } catch (error) {
    return { ok: false, status: null, error: error.message };
  }
}

/** Hash of the request definitions (never the credentials), so before/after can prove they match. */
function probeSetHash(probes) {
  const requests = (probes.requests || []).map((r) => ({ ...r }));
  return crypto.createHash('sha256').update(JSON.stringify(requests)).digest('hex').slice(0, 16);
}

function stopProcess(child) {
  if (!child || child.exitCode !== null) return;
  if (IS_WIN) run('taskkill', ['/PID', String(child.pid), '/T', '/F']);
  else {
    try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill('SIGTERM'); }
  }
}

/**
 * Drafts probes from the endpoint inventory detect-baseline.js recorded, so every endpoint the
 * application serves is either probed or explicitly listed as not probed, with the reason. Only
 * requests that cannot change state are drafted (GET with no path variable); a mutating or
 * parameterised endpoint needs a request the agent writes from the source. Writes
 * <session>/probes.discovered.json — never overwrites the agent's probes.json.
 */
function discoverProbes(slug, port = 8080) {
  const paths = sessionPaths(slug);
  const baseline = readJson(paths.baseline);
  if (!baseline || !baseline.observations) {
    console.error(`No baseline for "${slug}" — run detect-baseline.js first.`);
    process.exitCode = 1;
    return;
  }
  const endpoints = baseline.observations.endpoints || [];
  const requests = [];
  const notProbed = [];
  for (const e of endpoints) {
    const params = e.required_params || [];
    if (e.method === 'GET' && !/[{}*]/.test(e.path) && !params.length) {
      // Config-enabled framework endpoints (H2 console, exposed actuator endpoints) are probed too:
      // no controller maps them, and a major upgrade can drop them silently.
      const framework = e.source === 'config';
      requests.push({ name: `${e.method} ${e.path}`, method: 'GET', path: e.path, category: framework ? (/actuator/.test(e.handler || '') ? 'actuator' : 'framework-endpoint') : 'endpoint-inventory', handler: `${e.file}:${e.line}${framework ? ` (${e.handler})` : ''}` });
    } else {
      let reason = 'mutating or unspecified method — needs a request body and an expectation the agent writes from the source';
      if (/[{}*]/.test(e.path)) reason = 'path variable — needs a real id from the application\'s data';
      else if (params.length) reason = `required request parameter(s) ${params.join(', ')} — needs real values from the application's data`;
      notProbed.push({ endpoint: `${e.method} ${e.path}`, handler: `${e.file}:${e.line}`, reason, ...(params.length ? { required_params: params } : {}) });
    }
  }
  const draft = {
    $comment: 'Drafted by probe-runtime.js --discover from the static endpoint inventory. Add auth, real ids, request bodies and expect_status from the application\'s source, then pass it as --probes. not_probed must end empty or each entry must stay explained.',
    base_url: `http://localhost:${port}`,
    readiness: { path: '/actuator/health', timeout_seconds: 120 },
    requests: [{ name: 'endpoint inventory (actuator)', method: 'GET', path: '/actuator/mappings', category: 'endpoint-inventory' }, ...requests],
    not_probed: notProbed,
  };
  const file = writeJson(path.join(paths.root, 'probes.discovered.json'), draft);
  console.log(`\nProbe draft — ${endpoints.length} endpoint(s) in the inventory`);
  console.log(`  drafted    ${requests.length} safe GET probe(s) (+ /actuator/mappings)`);
  console.log(`  not probed ${notProbed.length} — listed with the reason; write those requests from the source`);
  console.log(`  written    ${rel(file)}\n`);
}

/**
 * The endpoints the running application actually serves, from /actuator/mappings when the app
 * exposes it: "METHOD pattern" per request mapping. Absent or secured is recorded as such — never
 * guessed — and the static inventory remains the fallback.
 */
async function runtimeEndpointInventory(baseUrl, auth) {
  const res = await attempt(`${baseUrl}/actuator/mappings`, { method: 'GET', headers: authHeader(auth) }, null, true);
  if (!res.ok || res.status !== 200) return { source: 'actuator', available: false, status: res.status || null, endpoints: [] };
  let doc;
  try { doc = JSON.parse(res.body_full || res.body_excerpt || ''); } catch { return { source: 'actuator', available: false, status: res.status, note: 'response was not complete JSON', endpoints: [] }; }
  const endpoints = new Set();
  const visit = (node) => {
    if (!node || typeof node !== 'object') return;
    const cond = node.details && node.details.requestMappingConditions;
    if (cond && Array.isArray(cond.patterns)) {
      const methods = cond.methods && cond.methods.length ? cond.methods : ['ANY'];
      for (const p of cond.patterns) for (const m of methods) endpoints.add(`${m} ${p}`);
    }
    for (const v of Object.values(node)) if (v && typeof v === 'object') visit(v);
  };
  visit(doc.contexts || doc);
  return { source: 'actuator', available: true, status: res.status, endpoints: [...endpoints].sort() };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();
  if (!args.slug) {
    console.error('--slug is required.');
    process.exitCode = 1;
    return;
  }
  if (args.discover) return discoverProbes(args.slug, args.port);
  if (!['baseline', 'final'].includes(args.phase)) {
    console.error('--phase must be "baseline" or "final".');
    process.exitCode = 1;
    return;
  }

  const paths = sessionPaths(args.slug);
  const baseline = readJson(paths.baseline);
  if (!baseline || !fs.existsSync(paths.workspace)) {
    console.error(`Session "${args.slug}" is not set up — run detect-baseline.js then prepare-workspace.js.`);
    process.exitCode = 1;
    return;
  }

  const jdkMajor = args.jdk || (args.phase === 'baseline' ? baseline.language.declared : baseline.language.target);
  const jdk = resolveJdk(jdkMajor);
  if (!jdk) {
    console.error(`No JDK ${jdkMajor} found on this machine.`);
    process.exitCode = 1;
    return;
  }

  let probes = DEFAULT_PROBES;
  let probeFile = null;
  if (args.probes) {
    probeFile = path.resolve(args.probes);
    if (!fs.existsSync(probeFile)) {
      // Falling back to the liveness default here would silently produce a comparison that
      // proves nothing, which is worse than stopping.
      console.error(`Probe file not found: ${probeFile}`);
      process.exitCode = 1;
      return;
    }
    probes = readJson(probeFile);
  }
  const probesSha = probeSetHash(probes);

  const meta = isSandboxRepo(paths.workspace) ? readJson(paths.workspaceMeta) : null;
  if (args.phase === 'baseline' && meta) {
    // The reference behaviour is the untouched project's. A sandbox that already differs from the
    // baseline commit cannot provide it.
    const edited = changedSince(paths.workspace, meta.baseline_commit);
    if (edited.length) {
      console.error('Refused: the baseline probe must observe the untouched project, but the sandbox already differs:');
      for (const c of edited.slice(0, 10)) console.error(`  ${c.state} ${c.file}`);
      process.exitCode = 1;
      return;
    }
  }
  if (args.phase === 'final') {
    const before = readJson(path.join(paths.runtimeDir, 'baseline.json'));
    if (before && before.probes_sha && before.probes_sha !== probesSha) {
      console.log('  ! The probe definitions differ from the ones replayed at baseline. The comparison will say so;');
      console.log('    replay the SAME probe file on both sides for a like-for-like comparison.');
    }
  }
  const baseUrl = (probes.base_url || `http://localhost:${args.port}`).replace(/\/$/, '');
  const readiness = probes.readiness || DEFAULT_PROBES.readiness;
  const readyTimeout = (args.timeout || readiness.timeout_seconds || 120) * 1000;

  const tool = resolveBuildTool(paths.workspace);
  if (!tool.command) {
    console.error(`No ${tool.tool} build tool found for the workspace.`);
    process.exitCode = 1;
    return;
  }

  console.log(`\nRuntime probe — phase ${args.phase} on JDK ${jdk.major} (${jdk.version})`);
  console.log(`  workspace ${rel(paths.workspace)}`);

  let artifact = findArtifact(paths.workspace, tool.tool);
  const packaging = { ran: false, exit_code: null, log_tail: null };
  if (!artifact || args.rebuild) {
    console.log('  packaging (tests skipped)…');
    const pkg = runTool(tool.command, buildArgs(tool.tool, 'package-skip-tests'), {
      cwd: paths.workspace, env: envForJdk(jdk), timeout: 900000,
    });
    packaging.ran = true;
    packaging.exit_code = pkg.status;
    packaging.log_tail = tail(`${pkg.stdout}\n${pkg.stderr}`, 4000);
    if (pkg.status !== 0) {
      const record = {
        slug: args.slug, phase: args.phase, generated_at: new Date().toISOString(),
        jdk: { major: jdk.major, version: jdk.version },
        started: false, failure: 'packaging failed', packaging, probes: [],
      };
      writeJson(path.join(paths.runtimeDir, `${args.phase}.json`), record);
      console.error('\n  Packaging failed — the application was never started. Record written anyway.');
      process.exitCode = 1;
      return;
    }
    artifact = findArtifact(paths.workspace, tool.tool);
  }
  if (!artifact) {
    console.error('  No runnable jar was produced — cannot start the application.');
    process.exitCode = 1;
    return;
  }
  console.log(`  artifact  ${rel(artifact, paths.workspace)}`);

  const appArgs = [...args.appArgs];
  if (!args.noPortArg) appArgs.push(`--server.port=${args.port}`);

  // Anything the running application writes into the sandbox (a file database, uploads, logs not
  // covered by .gitignore) would otherwise ride along into the migration diff unnoticed.
  const beforeRun = meta ? createCheckpoint(paths.workspace, `probe-${args.phase}-before`, `04D state before ${args.phase} probe`) : null;

  const startedAt = Date.now();
  const child = spawn(path.join(jdk.home, 'bin', IS_WIN ? 'java.exe' : 'java'), ['-jar', artifact, ...appArgs], {
    cwd: paths.workspace, env: envForJdk(jdk), detached: !IS_WIN, windowsHide: true,
  });
  let appLog = '';
  child.stdout.on('data', (d) => { appLog += d.toString(); });
  child.stderr.on('data', (d) => { appLog += d.toString(); });

  let ready = false;
  let readyAfterMs = null;
  let readinessStatus = null;
  console.log(`  starting  ${baseUrl}${readiness.path} (up to ${readyTimeout / 1000}s)…`);
  while (Date.now() - startedAt < readyTimeout) {
    if (child.exitCode !== null) break;
    // Any HTTP answer proves the server is up — 401 from a secured endpoint counts.
    const probe = await attempt(`${baseUrl}${readiness.path}`, { method: 'GET', headers: authHeader(probes.auth) });
    if (probe.ok) {
      ready = true;
      readyAfterMs = Date.now() - startedAt;
      readinessStatus = probe.status;
      break;
    }
    await sleep(1000);
  }

  const results = [];
  if (ready) {
    for (const request of probes.requests || DEFAULT_PROBES.requests) {
      const headers = {
        ...(request.no_auth ? {} : authHeader(probes.auth)),
        ...(request.body ? { 'Content-Type': 'application/json' } : {}),
        ...(request.headers || {}),
      };
      const init = { method: request.method || 'GET', headers };
      if (request.body !== undefined) init.body = typeof request.body === 'string' ? request.body : JSON.stringify(request.body);
      const at = Date.now();
      const outcome = await attempt(`${baseUrl}${request.path}`, init, request);
      results.push({
        name: request.name || `${request.method || 'GET'} ${request.path}`,
        method: request.method || 'GET',
        path: request.path,
        authenticated: !request.no_auth && Boolean(probes.auth),
        duration_ms: Date.now() - at,
        ...(request.category ? { category: request.category } : {}),
        ...(request.expect_status !== undefined ? { expect_status: Number(request.expect_status) } : {}),
        ...outcome,
      });
    }
  }

  // What the running application serves, before it is stopped (actuator /mappings when exposed).
  const endpointInventory = ready ? await runtimeEndpointInventory(baseUrl, probes.auth) : null;
  stopProcess(child);
  await sleep(500);
  const sideEffects = beforeRun ? changedSince(paths.workspace, beforeRun.commit) : [];

  const startupLine = (/Started [\w$.]+ in ([\d.]+) seconds/.exec(appLog) || [, null])[1];
  const record = {
    slug: args.slug,
    phase: args.phase,
    generated_at: new Date().toISOString(),
    jdk: { major: jdk.major, version: jdk.version, home: jdk.home },
    artifact: rel(artifact, paths.workspace),
    base_url: baseUrl,
    packaging,
    started: ready,
    startup_seconds: startupLine ? Number(startupLine) : (readyAfterMs ? readyAfterMs / 1000 : null),
    readiness: { path: readiness.path, status: readinessStatus, ready_after_ms: readyAfterMs },
    probes: results,
    app_log_tail: tail(stripRootFromText(appLog, paths.workspace), 6000),
    // v2 — additive
    probes_file: probeFile ? rel(probeFile) : null,
    probes_sha: probesSha,
    sandbox_side_effects: sideEffects,
    // v3 — endpoint preservation: what the app served at runtime, and what its source maps statically
    endpoint_inventory: endpointInventory,
    static_endpoints: scanEndpoints(paths.workspace),
  };
  const file = writeJson(path.join(paths.runtimeDir, `${args.phase}.json`), record);
  recordState(args.slug, args.phase === 'baseline' ? 'BASELINE_PROBED' : 'FINAL_PROBED', 'probe-runtime.js',
    `${ready ? 'started' : 'did not start'}; ${results.length} probe(s)`);

  console.log(`\n  Started     ${ready ? `yes (${record.startup_seconds ?? '?'}s, readiness ${readinessStatus})` : 'NO — did not answer in time'}`);
  if (results.length) {
    console.log(`\n  ${'Probe'.padEnd(38)} ${'Status'.padEnd(8)} Bytes`);
    for (const r of results) {
      console.log(`  ${r.name.slice(0, 37).padEnd(38)} ${String(r.ok ? r.status : 'ERR').padEnd(8)} ${r.body_length ?? '-'}`);
    }
  }
  if (!ready) console.log(`\n  Last log lines:\n${record.app_log_tail.split(/\r?\n/).slice(-15).map((l) => `    ${l}`).join('\n')}`);
  if (sideEffects.length) {
    console.log(`\n  ! The running application changed ${sideEffects.length} file(s) in the sandbox — they would join the migration diff:`);
    for (const s of sideEffects.slice(0, 10)) console.log(`    ${s.state} ${s.file}`);
  }
  console.log(`\n  Written ${rel(file)}\n`);
  if (!ready) process.exitCode = 1;
}

if (require.main === module) {
  require('./lib/summary').runAndFinalize(main, 'probe-runtime.js').catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}

module.exports = { hashBody, canonical, removePath, semanticObservations, probeSetHash };
