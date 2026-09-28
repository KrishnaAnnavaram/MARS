import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { lookup, stageStatus } from '../lib/status';
import { normalizeDiff } from './DiffView';
import { ProgressBar, StatusBadge } from './ui';
import { between, formatDuration, shortHash } from '../lib/format';

describe('status presentation', () => {
  it('never relies on colour alone: every badge has text', () => {
    for (const key of Object.keys(stageStatus)) {
      const { unmount } = render(<StatusBadge status={lookup(stageStatus, key)} />);
      expect(screen.getByText(lookup(stageStatus, key).label)).toBeInTheDocument();
      unmount();
    }
  });

  it('keeps a text label for screen readers in compact badges', () => {
    render(<StatusBadge status={lookup(stageStatus, 'WAITING')} compact />);
    expect(screen.getByText('Waiting for a human')).toHaveClass('sr-only');
  });

  it('shows an unknown status verbatim instead of inventing one', () => {
    render(<StatusBadge status={lookup(stageStatus, 'SOMETHING_NEW')} />);
    expect(screen.getByText('SOMETHING NEW')).toBeInTheDocument();
  });
});

describe('ProgressBar', () => {
  it('reports determinate domain progress with its counts', () => {
    render(<ProgressBar progress={{ mode: 'DETERMINATE', completed: 7, total: 11, unit: 'findings' }} />);
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '7');
    expect(bar).toHaveAttribute('aria-valuemax', '11');
    expect(screen.getByText('7 / 11 findings')).toBeInTheDocument();
  });

  it('never invents a percentage when the total is unknown', () => {
    render(<ProgressBar progress={{ mode: 'INDETERMINATE', completed: 3, unit: 'rounds' }} />);
    const bar = screen.getByRole('progressbar');
    expect(bar).not.toHaveAttribute('aria-valuenow');
    expect(screen.getByText('3 rounds so far (total not known)')).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });
});

describe('formatting', () => {
  it('formats measured durations only', () => {
    expect(formatDuration(undefined)).toBe('—');
    expect(formatDuration(850)).toBe('850 ms');
    expect(formatDuration(65_000)).toBe('1m 5s');
    expect(between('2026-09-27T10:00:00Z', '2026-09-27T10:00:30Z')).toBe(30_000);
    expect(shortHash('abcdef0123456789', 6)).toBe('abcdef…');
  });

  it('prepares the kernel\'s plain unified diffs for the diff parser', () => {
    const diff = '--- a/pom.xml\n+++ b/pom.xml\n@@ -1,1 +1,1 @@\r\n-<v>3</v>\r\n+<v>4</v>\r\n';
    const normalized = normalizeDiff(diff, 'pom.xml');
    expect(normalized.startsWith('diff --git a/pom.xml b/pom.xml\n--- a/pom.xml')).toBe(true);
    expect(normalized).not.toContain('\r');
  });
});
