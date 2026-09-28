import { useState } from 'react';
import { useArtifactText } from '../api/queries';
import { Markdown } from './Markdown';
import { ErrorState, LoadingState, Modal, Tabs } from './ui';

function pretty(text: string, path: string): string {
  if (path.endsWith('.json')) {
    try {
      return JSON.stringify(JSON.parse(text), null, 2);
    } catch {
      return text;
    }
  }
  return text;
}

const isMarkdown = (path?: string) => !!path && /\.(md|markdown)$/i.test(path);

/** Read-only view of one evidence artifact (source areas are never served; credentials are masked). */
export function ArtifactViewer({ runId, path, onClose }: { runId: string; path?: string; onClose: () => void }) {
  const text = useArtifactText(runId, path);
  const markdown = isMarkdown(path);
  const [view, setView] = useState<'rendered' | 'source'>('rendered');
  return (
    <Modal open={!!path} onOpenChange={(o) => !o && onClose()} title={path ?? ''} description="Run artifact (read-only)"
      wide={markdown ? 'xl' : true}>
      {text.isLoading && <LoadingState />}
      {text.error && <ErrorState error={text.error} title="Artifact not available" />}
      {text.data !== undefined && markdown && (
        <div className="mb-2 flex justify-end">
          <Tabs label="Document view" value={view} onChange={setView}
            options={[{ value: 'rendered', label: 'Rendered' }, { value: 'source', label: 'Markdown source' }]} />
        </div>
      )}
      {text.data !== undefined && (markdown && view === 'rendered' ? (
        <div className="max-h-[70vh] overflow-auto rounded border border-border bg-panel px-5 py-4">
          <Markdown>{text.data}</Markdown>
        </div>
      ) : (
        <pre className="mono max-h-[65vh] overflow-auto rounded border border-border bg-panel-2 p-3 text-[12px] whitespace-pre-wrap">
          {pretty(text.data, path ?? '')}
        </pre>
      ))}
    </Modal>
  );
}
