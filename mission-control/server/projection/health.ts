/**
 * Harness health — read-only synthetic checks of the MARS harness itself (proposal §30). Checks never
 * write evidence; the only processes spawned are `node .claude/scripts/pipeline-lint.js`, `java -version`
 * and `git worktree list`, each with a timeout.
 */
import { execFile } from 'node:child_process';
import type { HealthCheck, IntegrityFinding, Registry } from '../../shared/types.js';
import type { Workspace } from '../sources/workspace.js';
import type { CodeModel } from '../sources/codemodel.js';
import type { Evidence } from '../sources/evidence.js';

function run(cmd: string, args: string[], cwd: string, timeout = 15000): Promise<{ code: number | null; stdout: string; stderr: string; error: string | null }> {
  return new Promise((resolve) => {
    execFile(cmd, args, { cwd, timeout, windowsHide: true, maxBuffer: 1024 * 1024 }, (err, stdout, stderr) => {
      const e = err as (NodeJS.ErrnoException & { code?: number | string }) | null;
      resolve({ code: e ? (typeof e.code === 'number' ? e.code : null) : 0, stdout: String(stdout || ''), stderr: String(stderr || ''), error: e ? e.message : null });
    });
  });
}

function normalizeMirror(t: string): string {
  return t.replace(/\.github/g, '.claude').replace(/\r\n/g, '\n');
}

export interface HealthInputs {
  ws: Workspace;
  registry: Registry;
  cm: CodeModel;
  ev: Evidence;
  findings: IntegrityFinding[];
  ledger: { events: number; malformed: number; lastEventAt: string | null; dir: string };
  hooksConfigured: boolean;
}

export async function runHealth(h: HealthInputs): Promise<HealthCheck[]> {
  const now = new Date().toISOString();
  const checks: HealthCheck[] = [];
  const { ws } = h;

  checks.push({
    id: 'registry', label: 'Harness inventory (discovered)', status: 'pass', checkedAt: now, affects: [],
    summary: `${h.registry.agents.length} agents · ${h.registry.skills.length} skills · ${h.registry.scripts.length} entry scripts`,
    details: h.registry.skills.filter((s) => s.pointer).map((s) => `${s.id}: instructions live in ${s.pointer}`),
  });

  const drift = h.registry.drift;
  checks.push({
    id: 'drift', label: 'Documentation drift', status: drift.some((d) => d.severity === 'major') ? 'warn' : drift.length ? 'warn' : 'pass', checkedAt: now, affects: [],
    summary: drift.length ? `${drift.length} discrepancies between docs and the harness` : 'Docs agree with the filesystem',
    details: drift.map((d) => `${d.title}${d.detail ? ` — ${d.detail}` : ''}`),
  });

  // Mirror drift at script level (.claude vs .github, path-normalized).
  const diffs: string[] = [];
  for (const s of h.registry.skills) {
    for (const f of ws.walk(`.claude/skills/${s.id}/scripts`, 300)) {
      const other = f.path.replace(/^\.claude\//, '.github/');
      const a = ws.read(f.path);
      const b = ws.read(other);
      if (a != null && b != null && normalizeMirror(a) !== normalizeMirror(b)) diffs.push(f.path.replace(/^\.claude\/skills\//, ''));
    }
  }
  checks.push({
    id: 'mirror', label: '.claude ↔ .github harness scripts', status: diffs.length ? 'warn' : 'pass', checkedAt: now, affects: [],
    summary: diffs.length ? `${diffs.length} script(s) differ beyond path names` : 'Script copies match after path normalization',
    details: diffs.slice(0, 40).concat(diffs.some((d) => /gate\.js|verify-patch|apply-version-bump/.test(d)) ? ['Module resolution differs: the .github gate scripts resolve Maven modules at the repository root, the .claude copies under src/ — the Copilot harness would not find modules in this layout.'] : []),
  });

  const lint = await run(process.execPath, [ws.resolve('.claude/scripts/pipeline-lint.js')], ws.root);
  checks.push({
    id: 'lint', label: 'Pipeline contract lint', status: lint.code === 0 ? 'pass' : 'fail', checkedAt: now, affects: [],
    summary: (lint.stdout || lint.stderr || lint.error || '').trim().split('\n')[0] || 'no output', details: (lint.stderr || '').trim().split('\n').filter(Boolean).slice(0, 20),
  });

  // Toolchain: JDK vs the project's declared target.
  const targets = new Set<string>();
  for (const m of ws.dirs('src')) {
    const pom = ws.read(`src/${m}/pom.xml`) || '';
    const t = /<java\.version>\s*([0-9.]+)\s*<\/java\.version>/.exec(pom) || /<maven\.compiler\.(?:release|source)>\s*([0-9.]+)/.exec(pom);
    if (t) targets.add(t[1]);
  }
  const java = await run('java', ['-version'], ws.root, 8000);
  const ver = /version "([^"]+)"/.exec(`${java.stderr}${java.stdout}`);
  const major = ver ? Number(ver[1].startsWith('1.') ? ver[1].split('.')[1] : ver[1].split('.')[0]) : null;
  const targetList = Array.from(targets);
  const mismatch = major != null && targetList.length > 0 && targetList.every((t) => Number(t) !== major);
  const gateCompileFailures = Array.from(h.ev.gate.values()).flatMap((g) => [g.qa, g.build]).filter((g) => g && g.compileErrors.length).length;
  checks.push({
    id: 'toolchain', label: 'Build toolchain vs project target', status: major == null ? 'warn' : mismatch ? 'warn' : 'pass', checkedAt: now,
    affects: mismatch ? Array.from(h.ev.gate.keys()) : [],
    summary: major == null ? 'No JDK found on PATH for this server process' : `JDK ${major} on PATH; project targets Java ${targetList.join(', ') || 'unknown'}${process.env.JAVA_HOME ? ` (JAVA_HOME set)` : ' (JAVA_HOME not set)'}`,
    details: [
      ...(mismatch ? [`A JDK newer than the target can stop Lombok annotation processing, which produces "cannot find symbol" errors in files a patch never touched. ${gateCompileFailures} gate report(s) in the evidence show compiler errors.`] : []),
      'This probe describes the Mission Control host. The gate logs in the evidence were produced on a different machine; MARS does not record which JDK a gate used.',
    ],
  });

  const neo4jEnv = ws.exists('.claude/skills/01c-graph-forge/.env');
  const rec = h.ev.architecture.recordedGraphCounts;
  checks.push({
    id: 'graph', label: 'Knowledge graph', status: neo4jEnv ? (rec && rec.Type !== h.cm.counts.types ? 'warn' : 'pass') : 'warn', checkedAt: now, affects: [],
    summary: `${neo4jEnv ? 'Neo4j configured' : 'Neo4j not configured'} · code model ${h.cm.available ? `${h.cm.counts.types} types / ${h.cm.counts.methods} methods (generated ${h.cm.generatedAt || 'unknown'})` : 'missing'}`,
    details: rec ? [`architecture.md records Neo4j counts: ${Object.entries(rec).map(([k, v]) => `${k} ${v}`).join(', ')} — ${rec.Type !== h.cm.counts.types ? 'stale nodes likely (Graph Forge never prunes)' : 'consistent with the code model'}.`] : [],
  });

  const wt = await run('git', ['worktree', 'list', '--porcelain'], ws.root, 8000);
  const extra = (wt.stdout.match(/^worktree .+$/gm) || []).slice(1);
  checks.push({
    id: 'worktrees', label: 'Throwaway worktrees cleaned up', status: wt.code !== 0 ? 'unknown' : extra.length ? 'warn' : 'pass', checkedAt: now, affects: [],
    summary: wt.code !== 0 ? 'git worktree list failed' : extra.length ? `${extra.length} extra worktree(s) registered` : 'No leftover worktrees (gate scripts remove theirs)',
    details: extra.map((l) => l.replace(/^worktree /, '').replace(ws.root, '<workspace>')),
  });

  const crit = h.findings.filter((f) => f.severity === 'critical').length;
  const majorFindings = h.findings.filter((f) => f.severity === 'major').length;
  checks.push({
    id: 'integrity', label: 'Evidence integrity', status: crit ? 'fail' : majorFindings ? 'warn' : 'pass', checkedAt: now,
    affects: Array.from(new Set(h.findings.filter((f) => f.issueId && f.severity !== 'info').map((f) => f.issueId as string))),
    summary: `${crit} critical · ${majorFindings} major · ${h.findings.length - crit - majorFindings} minor/info findings`,
    details: h.findings.filter((f) => f.severity === 'critical').slice(0, 12).map((f) => `${f.issueId || 'workspace'} — ${f.title}`),
  });

  const age = h.ledger.lastEventAt ? Date.now() - Date.parse(h.ledger.lastEventAt) : null;
  checks.push({
    id: 'telemetry', label: 'Live telemetry (event ledger)', status: !h.hooksConfigured ? 'warn' : h.ledger.malformed ? 'warn' : 'pass', checkedAt: now, affects: [],
    summary: `${h.hooksConfigured ? 'Claude Code hooks configured' : 'Hooks not configured'} · ${h.ledger.events} events${h.ledger.malformed ? ` · ${h.ledger.malformed} malformed lines` : ''}${age != null ? ` · last event ${Math.round(age / 1000)} s ago` : ''}`,
    details: [`Ledger: ${h.ledger.dir.replace(ws.root, '<workspace>')}`, 'Runs started before telemetry existed are shown as reconstructed from evidence timestamps.'],
  });
  return checks;
}
