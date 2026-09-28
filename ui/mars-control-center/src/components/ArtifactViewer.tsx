import { useArtifactText } from '../api/queries';
import { ErrorState, LoadingState, Modal } from './ui';

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

/** Read-only view of one evidence artifact (source areas are never served; credentials are masked). */
export function ArtifactViewer({ runId, path, onClose }: { runId: string; path?: string; onClose: () => void }) {
  const text = useArtifactText(runId, path);
  return (
    <Modal open={!!path} onOpenChange={(o) => !o && onClose()} title={path ?? ''} description="Run artifact (read-only)" wide>
      {text.isLoading && <LoadingState />}
      {text.error && <ErrorState error={text.error} title="Artifact not available" />}
      {text.data !== undefined && (
        <pre className="mono max-h-[65vh] overflow-auto rounded border border-border bg-panel-2 p-3 text-[12px] whitespace-pre-wrap">
          {pretty(text.data, path ?? '')}
        </pre>
      )}
    </Modal>
  );
}
