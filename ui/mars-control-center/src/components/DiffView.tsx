import { useMemo } from 'react';
import { Diff, Hunk, parseDiff } from 'react-diff-view';
import 'react-diff-view/style/index.css';
import { EmptyState } from './ui';

/** git's parser wants a `diff --git` header; the kernel stores plain unified diffs with CRLF content. */
export function normalizeDiff(diff: string, path: string, newPath?: string): string {
  const body = diff.replace(/\r/g, '');
  if (body.startsWith('diff --git')) return body;
  return `diff --git a/${path} b/${newPath ?? path}\n${body}`;
}

/**
 * The exact unified diff carried by the proposal (the hash the approval binds to covers it).
 * Rendered split or unified; nothing here is editable.
 */
export function DiffView({ diff, path, newPath, viewType }: { diff?: string; path: string; newPath?: string; viewType: 'split' | 'unified' }) {
  const files = useMemo(() => {
    if (!diff) return [];
    try {
      return parseDiff(normalizeDiff(diff, path, newPath));
    } catch {
      return [];
    }
  }, [diff, path, newPath]);
  if (!diff) return <EmptyState title="This edit carries no diff" />;
  if (files.length === 0 || files.every((f) => f.hunks.length === 0)) {
    return <pre className="mono overflow-auto whitespace-pre-wrap p-3 text-[12px]">{diff.replace(/\r/g, '')}</pre>;
  }
  return (
    <div className="overflow-x-auto">
      {files.map((file) => (
        <Diff key={`${file.oldRevision}-${file.newRevision}-${file.newPath}`} viewType={viewType} diffType={file.type} hunks={file.hunks}>
          {(hunks) => hunks.map((h) => <Hunk key={h.content} hunk={h} />)}
        </Diff>
      ))}
    </div>
  );
}
