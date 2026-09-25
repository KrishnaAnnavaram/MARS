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
 * Usage:
 *   node scripts/probe-runtime.js --slug <slug> --phase baseline --jdk 17 --probes probes.json
 *   node scripts/probe-runtime.js --slug <slug> --phase final --jdk 21 --probes probes.json
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const {
  sessionPaths, readJson, writeJson, rel, runTool, run, tail, IS_WIN,
  resolveJdk, envForJdk, resolveBuildTool, buildArgs, stripRootFromText,
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
    const jars = fs.readdirSync(dir)
      .filter((f) => f.endsWith('.jar') && !/(\.original|-sources|-javadoc|-plain)\.jar$/.test(f))
      .map((f) => path.join(dir, f))
      .sort((a, b) => fs.statSync(b).size - fs.statSync(a).size);
    if (jars.length) return jars[0];
  }
  return null;
}

function hashBody(text) {
  return crypto.createHash('sha256').update(String(text || '').replace(/\s+/g, ' ').trim()).digest('hex').slice(0, 16);
}

function authHeader(auth) {
  if (!auth || auth.type !== 'basic') return {};
  const token = Buffer.from(`${auth.username}:${auth.password}`).toString('base64');
  return { Authorization: `Basic ${token}` };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function attempt(url, options) {
  try {
    const response = await fetch(url, options);
    const body = await response.text();
    return {
      ok: true,
      status: response.status,
      content_type: response.headers.get('content-type'),
      body_length: body.length,
      body_hash: hashBody(body),
      body_excerpt: body.length > 1500 ? `${body.slice(0, 1500)}…` : body,
    };
  } catch (error) {
    return { ok: false, status: null, error: error.message };
  }
}

function stopProcess(child) {
  if (!child || child.exitCode !== null) return;
  if (IS_WIN) run('taskkill', ['/PID', String(child.pid), '/T', '/F']);
  else {
    try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill('SIGTERM'); }
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();
  if (!args.slug) {
    console.error('--slug is required.');
    process.exitCode = 1;
    return;
  }
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
  if (args.probes) {
    const probeFile = path.resolve(args.probes);
    if (!fs.existsSync(probeFile)) {
      // Falling back to the liveness default here would silently produce a comparison that
      // proves nothing, which is worse than stopping.
      console.error(`Probe file not found: ${probeFile}`);
      process.exitCode = 1;
      return;
    }
    probes = readJson(probeFile);
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
      const outcome = await attempt(`${baseUrl}${request.path}`, init);
      results.push({
        name: request.name || `${request.method || 'GET'} ${request.path}`,
        method: request.method || 'GET',
        path: request.path,
        authenticated: !request.no_auth && Boolean(probes.auth),
        duration_ms: Date.now() - at,
        ...outcome,
      });
    }
  }

  stopProcess(child);
  await sleep(500);

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
  };
  const file = writeJson(path.join(paths.runtimeDir, `${args.phase}.json`), record);

  console.log(`\n  Started     ${ready ? `yes (${record.startup_seconds ?? '?'}s, readiness ${readinessStatus})` : 'NO — did not answer in time'}`);
  if (results.length) {
    console.log(`\n  ${'Probe'.padEnd(38)} ${'Status'.padEnd(8)} Bytes`);
    for (const r of results) {
      console.log(`  ${r.name.slice(0, 37).padEnd(38)} ${String(r.ok ? r.status : 'ERR').padEnd(8)} ${r.body_length ?? '-'}`);
    }
  }
  if (!ready) console.log(`\n  Last log lines:\n${record.app_log_tail.split(/\r?\n/).slice(-15).map((l) => `    ${l}`).join('\n')}`);
  console.log(`\n  Written ${rel(file)}\n`);
  if (!ready) process.exitCode = 1;
}

main().catch((error) => {
  console.error(error.stack || error.message);
  process.exitCode = 1;
});
