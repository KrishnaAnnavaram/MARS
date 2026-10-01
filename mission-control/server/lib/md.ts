/**
 * Markdown helpers for MARS evidence. They read the same "At a glance" contract the pipeline's own
 * regexes read (e.g. 07a-merge-arbiter/scripts/lib/arbiter.js extractField: first `| **Label** | value |`
 * occurrence wins), and they are tolerant: a missing section yields null, never an exception.
 */

/** Exactly the arbiter's extraction: first `| **Label** | value |` anywhere in the text. */
export function extractField(text: string, label: string): string | null {
  const re = new RegExp(`\\|\\s*\\*\\*${escapeRe(label)}\\*\\*\\s*\\|\\s*([^|]+)\\|`);
  const m = re.exec(text);
  return m ? m[1].trim() : null;
}

export function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** All two-column bold-label rows, first occurrence per label wins (raw cell text). */
export function glance(text: string): Record<string, string> {
  const out: Record<string, string> = {};
  const re = /^\|\s*\*\*([^*|]+)\*\*\s*\|\s*(.*?)\s*\|\s*$/gm;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text))) {
    const key = m[1].trim();
    if (!(key in out)) out[key] = m[2].trim();
  }
  return out;
}

/** Strips inline Markdown: links → text, backticks, bold/italic markers. */
export function plain(s: string | null | undefined): string | null {
  if (s == null) return null;
  return s
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\*\*([^*]*)\*\*/g, '$1')
    .replace(/(^|\s)_([^_]+)_(?=\s|$|[.,;:])/g, '$1$2')
    .replace(/<br\s*\/?>/gi, ' ')
    .trim();
}

/** Link targets in a Markdown fragment, with any leading ../ removed (MARS links are depth-relative). */
export function linkTargets(s: string | null | undefined): string[] {
  if (!s) return [];
  const out: string[] = [];
  const re = /\]\(([^)\s]+)\)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(s))) out.push(m[1]);
  return out;
}

export function repoPathFromLink(link: string): string {
  return link.replace(/#.*$/, '').replace(/^(\.\.\/)+/, '').replace(/^\.\//, '');
}

export function h1(text: string): string | null {
  const m = /^#\s+(.+)$/m.exec(text);
  return m ? m[1].trim() : null;
}

/** The issue title line MARS renders as the first `## ` heading that does not start with a number. */
export function subtitle(text: string): string | null {
  const m = /^##\s+(?!\d)(?!At a glance)(?!Appendix)(.+)$/m.exec(text);
  return m ? m[1].trim() : null;
}

/** The plain-language headline: the first blockquote line. */
export function headline(text: string): string | null {
  const m = /^>\s+(.+)$/m.exec(text);
  if (!m) return null;
  return plain(m[1]);
}

/** Every ISO-8601 timestamp mentioned in the generator line(s) (`_Generated … on DATE. X collected ISO._`). */
export function generatorLine(text: string): { line: string | null; date: string | null; instants: string[] } {
  const m = /^_(?:Generated|Written)[^\n]*_\s*$/m.exec(text);
  if (!m) return { line: null, date: null, instants: [] };
  const line = m[0];
  const date = (/\bon (\d{4}-\d{2}-\d{2})/.exec(line) || [])[1] || null;
  const instants = line.match(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g) || [];
  return { line, date, instants };
}

export interface Section {
  heading: string;
  level: number;
  body: string;
}

export function sections(text: string): Section[] {
  const lines = text.split(/\r?\n/);
  const out: Section[] = [];
  let cur: Section | null = null;
  let inFence = false;
  for (const line of lines) {
    if (/^```/.test(line)) inFence = !inFence;
    const m = !inFence && /^(#{2,3})\s+(.+)$/.exec(line);
    if (m) {
      if (cur) out.push(cur);
      cur = { heading: m[2].trim(), level: m[1].length, body: '' };
    } else if (cur) {
      cur.body += `${line}\n`;
    }
  }
  if (cur) out.push(cur);
  return out;
}

/** Body of the first section whose heading matches (number prefixes like "3. " ignored). */
export function section(text: string, pattern: RegExp): string | null {
  const s = sections(text).find((x) => pattern.test(x.heading.replace(/^\d+\.\s*/, '')));
  return s ? s.body.trim() : null;
}

export interface Table {
  headers: string[];
  rows: string[][];
}

function splitRow(line: string): string[] {
  const inner = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  const cells: string[] = [];
  let cur = '';
  let inCode = false;
  for (let i = 0; i < inner.length; i += 1) {
    const ch = inner[i];
    if (ch === '`') inCode = !inCode;
    if (ch === '\\' && inner[i + 1] === '|') {
      cur += '|';
      i += 1;
      continue;
    }
    if (ch === '|' && !inCode) {
      cells.push(cur.trim());
      cur = '';
    } else cur += ch;
  }
  cells.push(cur.trim());
  return cells;
}

/** All pipe tables in a fragment. */
export function tables(fragment: string | null): Table[] {
  if (!fragment) return [];
  const lines = fragment.split(/\r?\n/);
  const out: Table[] = [];
  for (let i = 0; i < lines.length - 1; i += 1) {
    if (/^\s*\|.*\|\s*$/.test(lines[i]) && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      const headers = splitRow(lines[i]).map((h) => plain(h) || '');
      const rows: string[][] = [];
      let j = i + 2;
      while (j < lines.length && /^\s*\|.*\|\s*$/.test(lines[j])) {
        rows.push(splitRow(lines[j]));
        j += 1;
      }
      out.push({ headers, rows });
      i = j - 1;
    }
  }
  return out;
}

/** First table whose headers include all the given names (case-insensitive). */
export function findTable(fragment: string | null, ...names: string[]): Table | null {
  const want = names.map((n) => n.toLowerCase());
  return tables(fragment).find((t) => want.every((w) => t.headers.some((h) => h.toLowerCase() === w))) || null;
}

export function col(t: Table, name: string): number {
  return t.headers.findIndex((h) => h.toLowerCase() === name.toLowerCase());
}

/** Bulleted or numbered list items in a fragment (first line of each item, Markdown stripped). */
export function listItems(fragment: string | null): string[] {
  if (!fragment) return [];
  const out: string[] = [];
  for (const line of fragment.split(/\r?\n/)) {
    const m = /^\s*(?:[-*]|\d+\.)\s+(.+)$/.exec(line);
    if (m) out.push(plain(m[1]) || '');
  }
  return out.filter(Boolean);
}

/** First paragraph of a fragment (Markdown stripped). */
export function firstParagraph(fragment: string | null): string | null {
  if (!fragment) return null;
  const para = fragment.split(/\r?\n\s*\r?\n/).map((p) => p.trim()).find((p) => p && !p.startsWith('|') && !p.startsWith('```') && !p.startsWith('<'));
  return para ? plain(para.replace(/\r?\n/g, ' ')) : null;
}

/** "30 / 100" → 30; "85 (High severity)" → 85. */
export function leadingNumber(s: string | null | undefined): number | null {
  if (!s) return null;
  const m = /-?\d+(?:\.\d+)?/.exec(s);
  return m ? Number(m[0]) : null;
}
