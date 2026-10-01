/**
 * Renderers for untrusted, LLM-authored content. Markdown: raw HTML is parsed (MARS reports use
 * <details>/<br>) and then sanitized with the GitHub schema; images are never fetched (prompt-injected
 * image URLs can exfiltrate data); links stay inside Mission Control or open externally with noopener.
 * Mermaid: strict security level, size limits, render timeout, output sanitized again with DOMPurify.
 * Diffs: rendered as text nodes only.
 */
import { useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize';
import DOMPurify from 'dompurify';
import { Link } from '@tanstack/react-router';

const schema = {
  ...defaultSchema,
  tagNames: [...(defaultSchema.tagNames || []), 'details', 'summary'],
  attributes: { ...defaultSchema.attributes, code: [...((defaultSchema.attributes || {}).code || []), ['className', /^language-./]] },
};

function repoPath(href: string, basePath: string): string | null {
  if (/^[a-z]+:/i.test(href) || href.startsWith('#')) return null;
  const clean = href.split('#')[0];
  const baseDir = basePath.split('/').slice(0, -1);
  const parts = [...baseDir, ...clean.split('/')];
  const out: string[] = [];
  for (const p of parts) {
    if (p === '..') out.pop();
    else if (p && p !== '.') out.push(p);
  }
  return out.join('/');
}

let mermaidLoader: Promise<typeof import('mermaid').default> | null = null;

export function Mermaid({ code }: { code: string }) {
  const id = useId().replace(/[^a-zA-Z0-9]/g, '');
  const [svg, setSvg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [show, setShow] = useState(false);
  useEffect(() => {
    let alive = true;
    if (code.length > 20000) {
      setErr('Diagram too large to render safely.');
      return;
    }
    const dark = document.documentElement.dataset.theme === 'dark' || (!document.documentElement.dataset.theme && window.matchMedia?.('(prefers-color-scheme: dark)').matches);
    mermaidLoader ||= import('mermaid').then((m) => m.default);
    const timeout = new Promise<never>((_r, rej) => setTimeout(() => rej(new Error('Diagram render timed out.')), 6000));
    Promise.race([
      mermaidLoader.then(async (mermaid) => {
        mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'neutral', maxTextSize: 20000, maxEdges: 300, flowchart: { htmlLabels: false } });
        const r = await mermaid.render(`m${id}${Math.random().toString(36).slice(2, 8)}`, code);
        return r.svg;
      }),
      timeout,
    ]).then((s) => {
      if (alive) setSvg(DOMPurify.sanitize(s, { USE_PROFILES: { svg: true, svgFilters: true } }));
    }).catch((e) => {
      if (alive) setErr((e as Error).message || 'Could not render diagram.');
    });
    return () => {
      alive = false;
    };
  }, [code, id]);
  return (
    <figure className="my-2 rounded border border-line bg-surface p-2">
      {svg ? <div className="mermaid-svg overflow-x-auto" dangerouslySetInnerHTML={{ __html: svg }} /> : err ? <div className="text-xs text-muted">Diagram not rendered: {err}</div> : <div className="text-xs text-subtle">Rendering diagram…</div>}
      <figcaption className="mt-1 text-[11px] text-subtle">
        Mermaid diagram from the report · <button type="button" className="underline" onClick={() => setShow((v) => !v)}>{show ? 'hide' : 'show'} source</button>
      </figcaption>
      {show && <pre className="mt-1 max-h-60 overflow-auto rounded bg-surface-3 p-2 text-[11px]"><code>{code}</code></pre>}
    </figure>
  );
}

export function SafeMarkdown({ text, basePath = 'docs/agent_output/x.md' }: { text: string; basePath?: string }) {
  const components = useMemo<Components>(() => ({
    img: ({ alt }) => <span className="rounded bg-surface-3 px-1 text-xs text-subtle">[image not loaded{alt ? `: ${alt}` : ''}]</span>,
    a: ({ href, children }) => {
      const h = String(href || '');
      const rp = repoPath(h, basePath);
      if (rp && /^(docs\/agent_output\/|\.claude\/(agents|skills|pipeline-contract|README))/.test(rp)) {
        return <Link to="/evidence/view" search={{ path: rp }}>{children}</Link>;
      }
      if (rp) return <span className="underline decoration-dotted" title={`Repository path: ${rp}`}>{children}</span>;
      if (/^https?:/i.test(h)) return <a href={h} target="_blank" rel="noopener noreferrer nofollow">{children}<span className="sr-only"> (opens in a new tab)</span></a>;
      return <span>{children}</span>;
    },
    code: ({ className, children }) => {
      const lang = /language-(\w+)/.exec(className || '')?.[1];
      const content = String(children ?? '');
      if (lang === 'mermaid') return <Mermaid code={content.replace(/\n$/, '')} />;
      return <code className={className}>{children}</code>;
    },
    pre: ({ children }) => {
      const child = Array.isArray(children) ? children[0] : children;
      const isMermaid = (child as { props?: { className?: string } })?.props?.className?.includes('language-mermaid');
      return isMermaid ? <>{children}</> : <pre>{children}</pre>;
    },
  }), [basePath]);
  return (
    <div className="md">
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeRaw, [rehypeSanitize, schema]]} components={components}>{text}</ReactMarkdown>
    </div>
  );
}

export function DiffView({ text, maxLines = 4000 }: { text: string; maxLines?: number }) {
  const lines = text.split(/\r?\n/);
  let oldNo = 0;
  let newNo = 0;
  const rows: ReactNode[] = [];
  lines.slice(0, maxLines).forEach((line, i) => {
    let cls = '';
    let a: number | string = '';
    let b: number | string = '';
    const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/.exec(line);
    if (hunk) {
      oldNo = Number(hunk[1]);
      newNo = Number(hunk[2]);
      cls = 'bg-run-tint text-run';
    } else if (/^(diff --git|index |--- |\+\+\+ )/.test(line)) cls = 'bg-surface-3 font-semibold text-muted';
    else if (line.startsWith('+')) {
      cls = 'bg-pass-tint';
      b = newNo++;
    } else if (line.startsWith('-')) {
      cls = 'bg-fail-tint';
      a = oldNo++;
    } else {
      a = oldNo++;
      b = newNo++;
    }
    rows.push(
      <tr key={i} className={cls}>
        <td className="w-10 select-none border-r border-line px-1 text-right text-subtle">{a}</td>
        <td className="w-10 select-none border-r border-line px-1 text-right text-subtle">{b}</td>
        <td className="whitespace-pre px-2"><span aria-hidden className="select-none">{line[0] === '+' || line[0] === '-' ? line[0] : ' '}</span>{line.slice(line[0] === '+' || line[0] === '-' || line[0] === ' ' ? 1 : 0)}</td>
      </tr>,
    );
  });
  return (
    <div className="overflow-x-auto rounded border border-line">
      <table className="mono w-full border-collapse text-[12px] leading-[18px]" aria-label="Unified diff">
        <tbody>{rows}</tbody>
      </table>
      {lines.length > maxLines && <div className="p-2 text-xs text-muted">Diff truncated at {maxLines} lines.</div>}
    </div>
  );
}

export function JsonView({ text }: { text: string }) {
  let pretty = text;
  try {
    pretty = JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    /* show raw */
  }
  return <pre className="mono max-h-[70vh] overflow-auto rounded border border-line bg-surface-2 p-3 text-[12px]">{pretty}</pre>;
}
