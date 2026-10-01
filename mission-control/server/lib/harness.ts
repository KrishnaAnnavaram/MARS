/**
 * Locates the MARS harness's own CommonJS libraries (telemetry classifier, ledger, register reader).
 * Preference order: the inspected workspace's copy, then the copy in the repository Mission Control
 * ships in (found by walking up from this file). Mission Control never re-implements the register
 * column contract or the path classifier; it uses the harness's.
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import type { Workspace } from '../sources/workspace.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const req = createRequire(import.meta.url);

function findUp(rel: string): string | null {
  let dir = here;
  for (let i = 0; i < 8; i += 1) {
    const candidate = path.join(dir, rel);
    if (fs.existsSync(candidate)) return candidate;
    const parent = path.dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  return null;
}

const cache = new Map<string, unknown>();

export function harnessModule<T>(ws: Workspace | null, rel: string): T | null {
  if (ws) {
    const fromWs = ws.requireCjs<T>(rel);
    if (fromWs) return fromWs;
  }
  if (cache.has(rel)) return cache.get(rel) as T;
  const found = findUp(rel);
  const mod = found ? (req(found) as T) : null;
  cache.set(rel, mod);
  return mod;
}

export interface PathClass {
  area: string;
  stage_id: string | null;
  issue_id: string | null;
  artifact_type: string;
  protected: boolean;
}

export interface CommandClass {
  skill_id: string | null;
  script: string;
  script_id: string;
  role: string;
  step_kind: string;
  stage_id: string | null;
  issue_ids: string[];
  scope: string | null;
  args: string[];
}

export interface Classifier {
  classifyPath(rel: string): PathClass;
  classifyCommand(cmd: string, cwd?: string): CommandClass | null;
  scriptRole(script: string): string;
  stageForScript(skill: string, script: string): string | null;
  SKILL_STAGE: Record<string, string>;
}

export function classifier(ws: Workspace | null): Classifier {
  const c = harnessModule<Classifier>(ws, '.claude/scripts/telemetry/classify.js');
  if (!c) throw new Error('MARS telemetry classifier (.claude/scripts/telemetry/classify.js) not found.');
  return c;
}

export interface RegisterIssue {
  id: string;
  title: string;
  type: string;
  severity: string;
  status: string;
  reportedOn: string;
  reportedBy: string;
  services: string[];
  symbols: string[];
  files: string[];
  entryPoints: string[];
  body: string;
  data?: Record<string, string>;
}

export function readRegister(ws: Workspace): { issues: RegisterIssue[]; error: string | null } {
  const lib = harnessModule<{ listIssues(dir: string, rel: (f: string) => string): RegisterIssue[] }>(ws, '.claude/skills/00-issue-register/scripts/lib/register.js');
  if (!lib) return { issues: [], error: 'Register reader (.claude/skills/00-issue-register/scripts/lib/register.js) not found.' };
  try {
    return { issues: lib.listIssues(ws.resolve('docs/agent_output/00-issues'), (f) => ws.rel(f)), error: null };
  } catch (err) {
    return { issues: [], error: (err as Error).message };
  }
}
