/** Read-only, path-safe access to a MARS workspace. Mission Control never writes evidence. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';

export interface WorkspaceFile {
  path: string;
  abs: string;
  size: number;
  mtimeMs: number;
}

export class Workspace {
  readonly root: string;
  private readonly requireFromRoot: NodeRequire;

  constructor(root: string) {
    this.root = path.resolve(root);
    this.requireFromRoot = createRequire(path.join(this.root, 'package.json'));
  }

  /** Resolves a repo-relative path and refuses anything that escapes the workspace. */
  resolve(rel: string): string {
    const clean = String(rel).replace(/\\/g, '/').replace(/^\/+/, '');
    const abs = path.resolve(this.root, clean);
    const back = path.relative(this.root, abs);
    if (back.startsWith('..') || path.isAbsolute(back)) throw new Error(`Path escapes the workspace: ${rel}`);
    return abs;
  }

  rel(abs: string): string {
    return path.relative(this.root, abs).replace(/\\/g, '/');
  }

  exists(rel: string): boolean {
    try {
      return fs.existsSync(this.resolve(rel));
    } catch {
      return false;
    }
  }

  read(rel: string): string | null {
    try {
      return fs.readFileSync(this.resolve(rel), 'utf8');
    } catch {
      return null;
    }
  }

  readBuffer(rel: string): Buffer | null {
    try {
      return fs.readFileSync(this.resolve(rel));
    } catch {
      return null;
    }
  }

  readJson<T = unknown>(rel: string): T | null {
    const t = this.read(rel);
    if (t == null) return null;
    try {
      return JSON.parse(t) as T;
    } catch {
      return null;
    }
  }

  sha256(rel: string): string | null {
    const b = this.readBuffer(rel);
    return b ? crypto.createHash('sha256').update(b).digest('hex') : null;
  }

  stat(rel: string): fs.Stats | null {
    try {
      return fs.statSync(this.resolve(rel));
    } catch {
      return null;
    }
  }

  /** Files directly under a directory (non-recursive), repo-relative, sorted. */
  list(dirRel: string, filter?: (name: string) => boolean): WorkspaceFile[] {
    let dir: string;
    try {
      dir = this.resolve(dirRel);
    } catch {
      return [];
    }
    if (!fs.existsSync(dir)) return [];
    return fs
      .readdirSync(dir, { withFileTypes: true })
      .filter((d) => d.isFile() && (!filter || filter(d.name)))
      .map((d) => {
        const abs = path.join(dir, d.name);
        const st = fs.statSync(abs);
        return { path: this.rel(abs), abs, size: st.size, mtimeMs: st.mtimeMs };
      })
      .sort((a, b) => a.path.localeCompare(b.path, undefined, { numeric: true }));
  }

  dirs(dirRel: string): string[] {
    try {
      const dir = this.resolve(dirRel);
      if (!fs.existsSync(dir)) return [];
      return fs.readdirSync(dir, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name).sort();
    } catch {
      return [];
    }
  }

  /** Recursive file walk (bounded). */
  walk(dirRel: string, max = 5000, skip: (name: string) => boolean = (n) => n === 'node_modules' || n === '.git'): WorkspaceFile[] {
    const out: WorkspaceFile[] = [];
    let start: string;
    try {
      start = this.resolve(dirRel);
    } catch {
      return out;
    }
    const stack = [start];
    while (stack.length && out.length < max) {
      const dir = stack.pop() as string;
      let entries: fs.Dirent[] = [];
      try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        continue;
      }
      for (const e of entries) {
        if (skip(e.name)) continue;
        const abs = path.join(dir, e.name);
        if (e.isDirectory()) stack.push(abs);
        else if (e.isFile()) {
          try {
            const st = fs.statSync(abs);
            out.push({ path: this.rel(abs), abs, size: st.size, mtimeMs: st.mtimeMs });
          } catch {
            /* ignore */
          }
        }
      }
    }
    return out.sort((a, b) => a.path.localeCompare(b.path));
  }

  /** Loads a CommonJS module from inside the workspace (the harness's own read-only libraries). */
  requireCjs<T = unknown>(rel: string): T | null {
    try {
      return this.requireFromRoot(this.resolve(rel)) as T;
    } catch {
      return null;
    }
  }

  gitHead(): { branch: string | null; head: string | null } {
    try {
      const gitDir = path.join(this.root, '.git');
      const head = fs.readFileSync(path.join(gitDir, 'HEAD'), 'utf8').trim();
      if (!head.startsWith('ref:')) return { branch: null, head: head.slice(0, 40) };
      const ref = head.slice(4).trim();
      const branch = ref.replace(/^refs\/heads\//, '');
      const loose = path.join(gitDir, ...ref.split('/'));
      if (fs.existsSync(loose)) return { branch, head: fs.readFileSync(loose, 'utf8').trim().slice(0, 40) };
      const packed = fs.readFileSync(path.join(gitDir, 'packed-refs'), 'utf8');
      const line = packed.split(/\r?\n/).find((l) => l.endsWith(` ${ref}`));
      return { branch, head: line ? line.slice(0, 40) : null };
    } catch {
      return { branch: null, head: null };
    }
  }
}

export function sha256(data: string | Buffer): string {
  return crypto.createHash('sha256').update(data).digest('hex');
}
