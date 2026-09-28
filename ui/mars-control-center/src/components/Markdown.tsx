import type { Element, ElementContent } from 'hast';
import { lazy, Suspense, type ReactNode } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { cn } from '../lib/format';
import { LoadingState } from './ui';

const MermaidDiagram = lazy(() => import('./MermaidDiagram').then((m) => ({ default: m.MermaidDiagram })));

function textOf(node: ElementContent): string {
  if (node.type === 'text') return node.value;
  if (node.type === 'element') return node.children.map(textOf).join('');
  return '';
}

function codeLanguage(code: Element): string | undefined {
  const names = code.properties.className;
  const list = Array.isArray(names) ? names.map(String) : [];
  return list.find((c) => c.startsWith('language-'))?.slice('language-'.length);
}

/** Only web links leave the Control Center, in a new tab; anything else is shown as its text. */
function Link({ href, children }: { href?: string; children?: ReactNode }) {
  if (href && /^https?:\/\//i.test(href)) {
    return <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary underline-offset-2 hover:underline">{children}</a>;
  }
  return <span className="text-primary" title={href}>{children}</span>;
}

const blockComponents: Components = {
  h1: ({ children }) => <h1 className="mt-5 mb-2 border-b border-border pb-1 text-[18px] font-semibold text-strong first:mt-0">{children}</h1>,
  h2: ({ children }) => <h2 className="mt-5 mb-2 border-b border-border pb-1 text-[15px] font-semibold text-strong first:mt-0">{children}</h2>,
  h3: ({ children }) => <h3 className="mt-4 mb-1.5 text-[14px] font-semibold text-strong first:mt-0">{children}</h3>,
  h4: ({ children }) => <h4 className="mt-3 mb-1 text-[13px] font-semibold text-strong first:mt-0">{children}</h4>,
  h5: ({ children }) => <h5 className="mt-3 mb-1 text-[13px] font-semibold text-muted first:mt-0">{children}</h5>,
  h6: ({ children }) => <h6 className="mt-3 mb-1 text-[12px] font-semibold uppercase tracking-wide text-muted first:mt-0">{children}</h6>,
  p: ({ children }) => <p className="my-2 first:mt-0 last:mb-0">{children}</p>,
  ul: ({ children, className }) => (
    <ul className={cn('my-2 space-y-0.5 pl-5', className?.includes('contains-task-list') ? 'list-none pl-1' : 'list-disc')}>{children}</ul>
  ),
  ol: ({ children, start }) => <ol start={start} className="my-2 list-decimal space-y-0.5 pl-5">{children}</ol>,
  li: ({ children }) => <li className="marker:text-faint">{children}</li>,
  blockquote: ({ children }) => <blockquote className="my-2 border-l-2 border-border-strong pl-3 text-muted">{children}</blockquote>,
  hr: () => <hr className="my-4 border-border" />,
  a: ({ href, children }) => <Link href={href}>{children}</Link>,
  strong: ({ children }) => <strong className="font-semibold text-strong">{children}</strong>,
  del: ({ children }) => <del className="text-faint">{children}</del>,
  img: ({ alt }) => <span className="text-faint">[image: {alt || 'not shown'}]</span>,
  table: ({ children }) => (
    <div className="my-3 overflow-x-auto rounded border border-border">
      <table className="w-full border-collapse text-left text-[12.5px]">{children}</table>
    </div>
  ),
  thead: ({ children }) => <thead className="bg-panel-3">{children}</thead>,
  tr: ({ children }) => <tr className="border-b border-border/60 last:border-b-0 even:bg-panel-2/60">{children}</tr>,
  th: ({ children, style }) => (
    <th style={style} className="border-b border-border px-2.5 py-1.5 text-[11px] font-semibold uppercase tracking-wide whitespace-nowrap text-muted">{children}</th>
  ),
  td: ({ children, style }) => <td style={style} className="px-2.5 py-1.5 align-top break-words">{children}</td>,
  code: ({ children }) => <code className="mono rounded bg-panel-3 px-1 py-px text-[0.92em] text-strong">{children}</code>,
  pre: ({ node }) => {
    const code = node?.children.find((c): c is Element => c.type === 'element' && c.tagName === 'code');
    const text = code ? textOf(code).replace(/\n$/, '') : '';
    if (code && codeLanguage(code) === 'mermaid') {
      return (
        <Suspense fallback={<LoadingState label="Drawing diagram…" />}>
          <MermaidDiagram source={text} />
        </Suspense>
      );
    }
    return <pre className="mono my-3 overflow-auto rounded border border-border bg-panel-2 p-3 text-[12px] whitespace-pre">{text}</pre>;
  },
};

/**
 * Markdown as MARS writes it in reports, plans and finding descriptions: GitHub-flavoured (tables,
 * task lists, strikethrough), with `mermaid` code blocks drawn as diagrams. Raw HTML in the source
 * is never rendered, and nothing here fetches remote content.
 */
export function Markdown({ children, className }: { children: string; className?: string }) {
  return (
    <div className={cn('mars-markdown min-w-0 leading-relaxed text-text', className)}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={blockComponents}>{children}</ReactMarkdown>
    </div>
  );
}

const inlineComponents: Components = {
  p: ({ children }) => <>{children}</>,
  a: ({ href, children }) => <Link href={href}>{children}</Link>,
  strong: ({ children }) => <strong className="font-semibold text-strong">{children}</strong>,
  code: ({ children }) => <code className="mono rounded bg-panel-3 px-1 py-px text-[0.92em] text-strong">{children}</code>,
};

const INLINE = ['p', 'strong', 'em', 'del', 'code', 'a'];
const INLINE_NO_LINKS = ['p', 'strong', 'em', 'del', 'code'];

/**
 * One line of text that may carry inline Markdown (code spans, emphasis), as event titles, root
 * causes and proposal reasons do. Block syntax is flattened to its text. Inside an interactive
 * element (a row button), `noLinks` keeps link text but drops the link.
 */
export function InlineMarkdown({ children, noLinks }: { children?: string | null; noLinks?: boolean }) {
  if (!children) return null;
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={inlineComponents}
      allowedElements={noLinks ? INLINE_NO_LINKS : INLINE} unwrapDisallowed>
      {children}
    </ReactMarkdown>
  );
}
