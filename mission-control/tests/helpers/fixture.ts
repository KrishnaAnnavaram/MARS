/**
 * Fixture workspaces for scenario tests. Each fixture is a copy of the real MARS evidence
 * (docs/agent_output + the harness definitions Mission Control reads) in the OS temp directory,
 * optionally mutated to produce a scenario the real data does not contain. Synthetic fixtures
 * are labelled as such in the tests that use them. The real workspace is never written to.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

const COPY = ['docs/agent_output', '.claude/agents', '.claude/scripts', '.claude/README.md', '.claude/pipeline-contract.md', '.claude/.pipeline-context/artifacts.json'];

function copySkills(dst: string): void {
  const src = path.join(REPO, '.claude/skills');
  for (const s of fs.readdirSync(src)) {
    const from = path.join(src, s);
    if (!fs.statSync(from).isDirectory()) continue;
    for (const f of ['SKILL.md', 'scoring.json']) {
      if (fs.existsSync(path.join(from, f))) {
        fs.mkdirSync(path.join(dst, '.claude/skills', s), { recursive: true });
        fs.copyFileSync(path.join(from, f), path.join(dst, '.claude/skills', s, f));
      }
    }
  }
}

export interface Fixture {
  root: string;
  ledgerDir: string;
  file(rel: string): string;
  read(rel: string): string;
  write(rel: string, text: string): void;
  edit(rel: string, fn: (t: string) => string): void;
  remove(rel: string): void;
  appendLedger(events: Record<string, unknown>[]): void;
  cleanup(): void;
}

export function makeFixture(opts: { empty?: boolean } = {}): Fixture {
  const root = fs.mkdtempSync(path.join(fs.realpathSync(os.tmpdir()), 'mc-fixture-'));
  if (!opts.empty) {
    for (const rel of COPY) {
      const from = path.join(REPO, rel);
      if (!fs.existsSync(from)) continue;
      fs.cpSync(from, path.join(root, rel), { recursive: true });
    }
    copySkills(root);
  } else {
    fs.mkdirSync(path.join(root, 'docs/agent_output'), { recursive: true });
    fs.cpSync(path.join(REPO, '.claude/scripts'), path.join(root, '.claude/scripts'), { recursive: true });
  }
  const ledgerDir = path.join(root, '.mars', 'ledger');
  fs.mkdirSync(ledgerDir, { recursive: true });
  const f: Fixture = {
    root,
    ledgerDir,
    file: (rel) => path.join(root, rel),
    read: (rel) => fs.readFileSync(path.join(root, rel), 'utf8'),
    write: (rel, text) => {
      fs.mkdirSync(path.dirname(path.join(root, rel)), { recursive: true });
      fs.writeFileSync(path.join(root, rel), text);
    },
    edit: (rel, fn) => f.write(rel, fn(f.read(rel))),
    remove: (rel) => fs.rmSync(path.join(root, rel), { force: true }),
    appendLedger: (events) => {
      const month = new Date().toISOString().slice(0, 7);
      fs.appendFileSync(path.join(ledgerDir, `events-${month}.jsonl`), events.map((e) => JSON.stringify(e)).join('\n') + '\n');
    },
    cleanup: () => fs.rmSync(root, { recursive: true, force: true }),
  };
  return f;
}

let seq = 0;
/** A minimal mars.event/1 envelope for ledger-driven scenarios. */
export function ev(type: string, extra: Record<string, unknown> = {}, time = new Date()): Record<string, unknown> {
  seq += 1;
  return { schema: 'mars.event/1', event_id: `01TEST${String(seq).padStart(20, '0')}`, seq, time: time.toISOString(), type, ...extra };
}

/** Rewrite the Status cell of a fix plan (fixture only). */
export function setPlanStatus(f: Fixture, id: string, status: string): void {
  f.edit(`docs/agent_output/04-remediation/fix_plan_${id}.md`, (t) => t.replace(/(\|\s*\*{0,2}Status\*{0,2}\s*\|\s*)[^|\n]+/, `$1${status} `));
}
