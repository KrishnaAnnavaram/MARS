import { AlertTriangle } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { cn } from '../lib/format';
import { LoadingState, Tabs } from './ui';

type Mermaid = typeof import('mermaid')['default'];

let loader: Promise<Mermaid> | undefined;

/** Mermaid is large, so it is fetched only when a page actually shows a diagram. */
function loadMermaid(): Promise<Mermaid> {
  loader ??= import('mermaid').then((m) => m.default);
  return loader;
}

function token(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

/** Diagrams follow the design tokens of the current theme, read when the diagram is drawn. */
function configure(mermaid: Mermaid) {
  const dark = document.documentElement.dataset.theme !== 'light';
  mermaid.initialize({
    startOnLoad: false,
    // strict: no script, no click handlers, labels sanitized; diagrams come from run artifacts
    securityLevel: 'strict',
    suppressErrorRendering: true,
    theme: 'base',
    darkMode: dark,
    fontFamily: token('--font-sans', 'ui-sans-serif, system-ui, sans-serif'),
    fontSize: 13,
    flowchart: { useMaxWidth: false, htmlLabels: false },
    sequence: { useMaxWidth: false },
    themeVariables: {
      background: token('--panel-2', dark ? '#141b25' : '#f7f9fb'),
      primaryColor: token('--panel-3', dark ? '#1a2330' : '#eef2f6'),
      primaryBorderColor: token('--primary', dark ? '#22d3ee' : '#0e7490'),
      primaryTextColor: token('--text-strong', dark ? '#f1f5f9' : '#0b1220'),
      secondaryColor: token('--panel-2', dark ? '#141b25' : '#f7f9fb'),
      tertiaryColor: token('--panel', dark ? '#0f151d' : '#ffffff'),
      lineColor: token('--muted', dark ? '#8a97ab' : '#5b677a'),
      textColor: token('--text', dark ? '#d8dfe9' : '#1f2937'),
      mainBkg: token('--panel-3', dark ? '#1a2330' : '#eef2f6'),
      nodeBorder: token('--primary', dark ? '#22d3ee' : '#0e7490'),
      clusterBkg: token('--panel-2', dark ? '#141b25' : '#f7f9fb'),
      clusterBorder: token('--border-strong', dark ? '#2e3a4b' : '#c8d0da'),
      edgeLabelBackground: token('--panel', dark ? '#0f151d' : '#ffffff'),
    },
  });
}

let sequence = 0;

type Rendered = { svg: string } | { error: string };

/**
 * A Mermaid diagram from a report, drawn in the browser. When the source does not parse, the page
 * says so and shows the source instead: a report is never silently missing a diagram.
 */
export function MermaidDiagram({ source }: { source: string }) {
  const reactId = useId();
  const [rendered, setRendered] = useState<Rendered>();
  const [view, setView] = useState<'diagram' | 'source'>('diagram');
  const [fit, setFit] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const id = `mermaid-${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}-${++sequence}`;
    loadMermaid()
      .then(async (mermaid) => {
        configure(mermaid);
        const { svg } = await mermaid.render(id, source);
        if (!cancelled) setRendered({ svg });
      })
      .catch((e: unknown) => {
        if (!cancelled) setRendered({ error: e instanceof Error ? e.message : String(e) });
      })
      .finally(() => {
        // mermaid measures text in a temporary element; never leave it behind on failure
        document.getElementById(`d${id}`)?.remove();
      });
    return () => {
      cancelled = true;
    };
  }, [source, reactId]);

  const failed = rendered && 'error' in rendered;
  const showSource = view === 'source' || failed;

  return (
    <figure className="my-3 rounded-md border border-border bg-panel-2" aria-label="Diagram">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-2 py-1">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-faint">Diagram</span>
        <div className="flex items-center gap-2">
          {!showSource && rendered && (
            <Tabs label="Diagram size" value={fit ? 'fit' : 'actual'} onChange={(v) => setFit(v === 'fit')}
              options={[{ value: 'actual', label: 'Actual size' }, { value: 'fit', label: 'Fit width' }]} />
          )}
          {!failed && (
            <Tabs label="Diagram view" value={view} onChange={setView}
              options={[{ value: 'diagram', label: 'Diagram' }, { value: 'source', label: 'Source' }]} />
          )}
        </div>
      </div>
      {failed && (
        <div role="alert" className="flex items-start gap-2 border-b border-border bg-warning-soft px-3 py-2 text-[12px] text-text">
          <AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
          <span>This diagram could not be drawn ({rendered.error}). Its source is shown instead.</span>
        </div>
      )}
      {showSource ? (
        <pre className="mono overflow-auto p-3 text-[12px] whitespace-pre">{source}</pre>
      ) : rendered ? (
        <div className={cn('mars-mermaid overflow-auto p-3', fit && 'mars-mermaid-fit')}
          // the SVG comes from mermaid in strict mode, which sanitizes every label
          dangerouslySetInnerHTML={{ __html: (rendered as { svg: string }).svg }} />
      ) : (
        <LoadingState label="Drawing diagram…" />
      )}
    </figure>
  );
}
