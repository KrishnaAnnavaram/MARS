import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { InlineMarkdown, Markdown } from './Markdown';

const mermaid = vi.hoisted(() => ({
  initialize: vi.fn(),
  render: vi.fn<(id: string, source: string) => Promise<{ svg: string }>>(),
}));
vi.mock('mermaid', () => ({ default: mermaid }));

const REPORT = [
  '# Final Evidence Report',
  '',
  '**Final verdict: NEEDS_HUMAN**',
  '',
  '| Field | Value |',
  '|---|---|',
  '| RUN_ID | RUN-1 |',
  '| Strategy | `MIGRATE_FIRST` |',
  '',
  '```mermaid',
  'flowchart LR',
  '    S0[CREATED] --> S1[SOURCE_SNAPSHOTTED]',
  '```',
].join('\n');

describe('Markdown', () => {
  beforeEach(() => {
    mermaid.initialize.mockReset();
    mermaid.render.mockReset();
  });

  it('renders GitHub tables as tables, with header and body cells', async () => {
    mermaid.render.mockResolvedValue({ svg: '<svg aria-label="state flow"></svg>' });
    render(<Markdown>{REPORT}</Markdown>);
    expect(screen.getByRole('heading', { level: 1, name: 'Final Evidence Report' })).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('columnheader').map((c) => c.textContent)).toEqual(['Field', 'Value']);
    expect(within(table).getAllByRole('row')).toHaveLength(3);
    expect(within(table).getByText('MIGRATE_FIRST').tagName).toBe('CODE');
    expect(await screen.findByLabelText('state flow')).toBeInTheDocument();
  });

  it('draws mermaid blocks with the exact source, in strict security mode', async () => {
    mermaid.render.mockResolvedValue({ svg: '<svg aria-label="state flow"></svg>' });
    render(<Markdown>{REPORT}</Markdown>);
    await screen.findByLabelText('state flow');
    expect(mermaid.render).toHaveBeenCalledWith(expect.stringMatching(/^mermaid-/),
      'flowchart LR\n    S0[CREATED] --> S1[SOURCE_SNAPSHOTTED]');
    expect(mermaid.initialize).toHaveBeenCalledWith(expect.objectContaining({ securityLevel: 'strict', startOnLoad: false }));
  });

  it('shows the source, and says so, when a diagram cannot be drawn', async () => {
    mermaid.render.mockRejectedValue(new Error('Parse error on line 2'));
    render(<Markdown>{'```mermaid\nflowchart LR\n  A -->\n```'}</Markdown>);
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be drawn (Parse error on line 2)');
    expect(screen.getByText(/A -->/)).toBeInTheDocument();
  });

  it('switches a drawn diagram to its source on request', async () => {
    mermaid.render.mockResolvedValue({ svg: '<svg aria-label="state flow"></svg>' });
    render(<Markdown>{REPORT}</Markdown>);
    await screen.findByLabelText('state flow');
    await userEvent.click(screen.getByRole('tab', { name: 'Source' }));
    expect(screen.getByText(/S0\[CREATED\]/)).toBeInTheDocument();
    expect(screen.queryByLabelText('state flow')).not.toBeInTheDocument();
  });

  it('never renders raw HTML from the source', () => {
    render(<Markdown>{'before <img src=x onerror="alert(1)"> <script>alert(1)</script> after'}</Markdown>);
    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('script')).toBeNull();
  });

  it('opens only web links, in a new tab', () => {
    render(<Markdown>{'[docs](https://example.org/a) and [local](reports/final-report.md)'}</Markdown>);
    const link = screen.getByRole('link', { name: 'docs' });
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.queryByRole('link', { name: 'local' })).not.toBeInTheDocument();
    expect(screen.getByText('local')).toHaveAttribute('title', 'reports/final-report.md');
  });
});

describe('InlineMarkdown', () => {
  it('renders code spans and emphasis without block wrappers', () => {
    const { container } = render(<InlineMarkdown>{'Root cause of INV-101: `return jdbcTemplate.queryForList(sql);` is **reachable**'}</InlineMarkdown>);
    expect(container.querySelector('p')).toBeNull();
    expect(screen.getByText('return jdbcTemplate.queryForList(sql);').tagName).toBe('CODE');
    expect(screen.getByText('reachable').tagName).toBe('STRONG');
  });

  it('drops links inside interactive rows but keeps their text', () => {
    render(<InlineMarkdown noLinks>{'see [the advisory](https://example.org)'}</InlineMarkdown>);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.getByText(/the advisory/)).toBeInTheDocument();
  });
});
