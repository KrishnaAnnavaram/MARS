/**
 * Component tests (jsdom): status is never colour-only, markdown is sanitised, board cells are
 * labelled, tabs are keyboard-operable, error/empty states render, and the decision form protects
 * against stale plans.
 */
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { ApiError } from '../../web/api';
import { EmptyState, ErrorState, FailureTag, StatusBadge, StatusGlyph, Tabs } from '../../web/components/ui';
import { SafeMarkdown } from '../../web/components/content';
import { RemediationBoard, StateLegend } from '../../web/components/board';
import { DecisionForm } from '../../web/pages/approvals';
import { STATE } from '../../web/domain/status';
import { STAGES } from '../../shared/stages';
import type { ApprovalInfo, Cell, CellState, IssueSummary, StageId } from '../../shared/types';
import { renderApp } from './render';

afterEach(() => vi.unstubAllGlobals());

const cell = (stage: StageId, state: CellState, label: string, extra: Partial<Cell> = {}): Cell => ({ stage, state, label, outcome: null, detail: null, failureClass: null, causeVerified: true, decidedBy: 'script', provenance: 'computed', time: null, evidence: null, modifiers: [], findings: [], ...extra });

describe('status primitives', () => {
  it('every state has a text label and a distinct glyph (never colour alone)', () => {
    const glyphs = new Set<string>();
    for (const [k, s] of Object.entries(STATE)) {
      expect(s.label.length, k).toBeGreaterThan(0);
      glyphs.add(s.glyph);
    }
    // Failure, blocked, tool error, human wait and pass must all be shape-distinct.
    for (const a of ['failed', 'blocked', 'tool_error', 'awaiting_human', 'passed', 'running'] as CellState[]) for (const b of ['failed', 'blocked', 'tool_error', 'awaiting_human', 'passed', 'running'] as CellState[]) if (a !== b) expect(STATE[a].glyph, `${a} vs ${b}`).not.toBe(STATE[b].glyph);
    expect(glyphs.size).toBeGreaterThanOrEqual(10);
  });

  it('StatusGlyph exposes its meaning to assistive tech; StatusBadge renders visible text', async () => {
    await renderApp(<><StatusGlyph state="blocked" /><StatusBadge state="awaiting_human" /></>);
    expect(screen.getByRole('img', { name: 'Blocked' })).toBeInTheDocument();
    expect(screen.getByText(STATE.awaiting_human.label)).toBeVisible();
  });

  it('FailureTag says when a cause is unverified', async () => {
    await renderApp(<FailureTag cls="compile.error" verified={false} />);
    expect(screen.getByText(/cause unverified/)).toBeInTheDocument();
  });
});

describe('SafeMarkdown', () => {
  it('strips scripts, event handlers and javascript: URLs, and keeps tables', async () => {
    const md = '| A | B |\n|---|---|\n| 1 | 2 |\n\n<script>window.__pwned = 1</script>\n<img src=x onerror="window.__pwned=2">\n<a href="javascript:alert(1)">click</a>\n<details><summary>More</summary>hidden</details>';
    const { container } = await renderApp(<SafeMarkdown text={md} />);
    expect(container.querySelector('script')).toBeNull();
    expect(container.innerHTML).not.toMatch(/onerror|javascript:/);
    expect((window as unknown as { __pwned?: number }).__pwned).toBeUndefined();
    expect(container.querySelector('table')).not.toBeNull();
    expect(container.querySelector('details summary')?.textContent).toBe('More');
    expect(screen.getByText(/image not loaded/)).toBeInTheDocument();
  });

  it('turns repo-relative evidence links into in-app links and external links into safe new-tab links', async () => {
    const { container } = await renderApp(<SafeMarkdown basePath="docs/agent_output/07-ship/verdict_ISSUE-003.md" text={'[qa](../06-test-gate/qa_ISSUE-003.md) [ext](https://example.com) [src](../../../src/A.java)'} />);
    const links = Array.from(container.querySelectorAll('a'));
    const qa = links.find((a) => a.textContent === 'qa')!;
    expect(decodeURIComponent(qa.getAttribute('href')!)).toMatch(/\/evidence\/view\?path=.*docs\/agent_output\/06-test-gate\/qa_ISSUE-003\.md/);
    const ext = links.find((a) => a.textContent?.startsWith('ext'))!;
    expect(ext.getAttribute('rel')).toMatch(/noopener/);
    expect(ext.getAttribute('target')).toBe('_blank');
    // Source files are not served: shown as a path, not a link.
    expect(links.find((a) => a.textContent === 'src')).toBeUndefined();
  });
});

describe('RemediationBoard', () => {
  const cells = Object.fromEntries(STAGES.map((s) => [s.id, cell(s.id, 'passed', 'Done')])) as Record<StageId, Cell>;
  const issue: IssueSummary = {
    id: 'ISSUE-009', title: 'Test issue', severity: 'High', type: 'Vulnerability', cwe: 'CWE-89', cve: null, owasp: null, services: ['svc'], registerStatus: 'Open', inRegister: true, priority: 'P1', headline: null,
    cells: { ...cells, qa: cell('qa', 'failed', 'Test failed', { failureClass: 'qa.test_failed', modifiers: ['conflict'] }), verdict: cell('verdict', 'blocked', 'Blocked') },
    verdict: { decision: 'Blocked', score: 60, threshold: 85, hardGates: [], overrideApplied: false, replayDecision: 'Blocked', replayMatches: true },
    blocker: { summary: 'Blocked — QA gate: test failed', causes: [] }, nextAction: { stage: 'fix', owner: '04_fix-generator', ownerKind: 'agent', text: 'Fix the test', basis: '' }, attention: 1, findings: 1,
  };

  it('labels each cell with stage, state, failure class and flags', async () => {
    await renderApp(<RemediationBoard issues={[issue]} stages={STAGES} />);
    const table = screen.getByRole('table', { name: 'Issue by stage board' });
    const qa = within(table).getByRole('button', { name: /^QA gate: Failed\. Test failed\. .*Evidence conflict/i });
    expect(qa).toBeInTheDocument();
    expect(table.textContent).toContain('Blocked — QA gate: test failed');
    expect(within(table).getByText('Fix the test')).toBeInTheDocument();
  });

  it('the legend names every state and modifier', async () => {
    await renderApp(<StateLegend />);
    for (const s of ['passed', 'failed', 'blocked', 'awaiting_human'] as const) expect(screen.getByText(STATE[s].label)).toBeInTheDocument();
  });
});

describe('Tabs, empty and error states', () => {
  it('arrow keys move between tabs', async () => {
    function T() {
      const [v, setV] = useState<'a' | 'b' | 'c'>('a');
      return <Tabs label="t" value={v} onChange={setV} tabs={[{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }, { id: 'c', label: 'C' }]} />;
    }
    await renderApp(<T />);
    const a = screen.getByRole('tab', { name: 'A' });
    a.focus();
    fireEvent.keyDown(a, { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'B' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'B' }), { key: 'End' });
    expect(screen.getByRole('tab', { name: 'C' })).toHaveAttribute('aria-selected', 'true');
  });

  it('ErrorState shows the typed error and a retry; EmptyState explains', async () => {
    const retry = vi.fn();
    await renderApp(<><ErrorState error={new ApiError(0, 'NETWORK', 'Server unreachable')} retry={retry} /><EmptyState title="Nothing yet" body="Run 02 first." /></>);
    expect(screen.getByRole('alert')).toHaveTextContent('Mission Control server unreachable');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(retry).toHaveBeenCalled();
    expect(screen.getByText('Run 02 first.')).toBeInTheDocument();
  });
});

describe('DecisionForm (stale-state protection)', () => {
  const base: ApprovalInfo = {
    issueId: 'ISSUE-001', title: 't', severity: 'High', state: 'pending', stateLabel: 'Awaiting decision', planPath: 'p', planSha256: 'a'.repeat(64), plan: null, decisions: [], implementation: null, services: [], priority: null,
    actions: { allowed: true, reason: '', available: ['APPROVED', 'REJECTED'], unavailable: [{ action: 'Request changes', reason: 'Not in the MARS contract.' }] },
  };

  beforeEach(() => sessionStorage.setItem('mc-decision-token', 't'.repeat(32)));
  afterEach(() => sessionStorage.clear());

  it('without the launch token (not opened from the terminal link) it explains instead of offering the form', async () => {
    sessionStorage.clear();
    await renderApp(<DecisionForm a={base} actor="dana" />);
    expect(screen.queryByRole('form')).toBeNull();
    expect(screen.getByText(/link printed in the terminal/)).toBeInTheDocument();
  });

  it('is read-only with an explanation when decisions are not allowed', async () => {
    await renderApp(<DecisionForm a={{ ...base, actions: { ...base.actions, allowed: false, reason: 'Recorded decisions are off.' } }} actor="dana" />);
    expect(screen.queryByRole('form')).toBeNull();
    expect(screen.getByText('Recorded decisions are off.')).toBeInTheDocument();
  });

  it('cannot submit until a decision, a rationale and the typed issue id are given; no auto-approve', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    await renderApp(<DecisionForm a={base} actor="dana" />);
    const submit = screen.getByRole('button', { name: /choose a decision/i });
    expect(submit).toBeDisabled();
    fireEvent.click(screen.getByLabelText('Approve'));
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: 'Approach is sound and scoped.' } });
    expect(screen.getByRole('button', { name: /record approval/i })).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/to confirm/), { target: { value: 'ISSUE-001' } });
    expect(screen.getByRole('button', { name: /record approval/i })).toBeEnabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('re-reads the plan before posting and refuses when its hash changed', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (String(url).endsWith('/decisions')) throw new Error('must not POST');
      return new Response(JSON.stringify({ approval: { ...base, planSha256: 'b'.repeat(64) } }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', fetchMock);
    await renderApp(<DecisionForm a={base} actor="dana" />);
    fireEvent.click(screen.getByLabelText('Reject'));
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: 'Does not cover the export path.' } });
    fireEvent.change(screen.getByLabelText(/to confirm/), { target: { value: 'ISSUE-001' } });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /record rejection/i }));
    });
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/plan changed/i));
    expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith('/decisions'))).toBe(false);
  });

  it('posts with the reviewed hash and shows that the Fixer is not started', async () => {
    const posted: unknown[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (String(url).endsWith('/decisions')) {
        posted.push(JSON.parse(String(init?.body)));
        expect((init?.headers as Record<string, string>)['X-MC-Request']).toBe('1');
        expect((init?.headers as Record<string, string>)['X-MC-Token']).toBe('t'.repeat(32));
        return new Response(JSON.stringify({ ok: true, record: { decision_id: 'DEC-1' }, path: 'x', next: 'Recorded. This does not start the Fixer.' }), { status: 201, headers: { 'Content-Type': 'application/json' } });
      }
      return new Response(JSON.stringify({ approval: base }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
    await renderApp(<DecisionForm a={base} actor="dana" />);
    fireEvent.click(screen.getByLabelText('Approve'));
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: 'Approach is sound and scoped.' } });
    fireEvent.change(screen.getByLabelText(/to confirm/), { target: { value: 'ISSUE-001' } });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /record approval/i }));
    });
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('DEC-1'));
    expect(posted[0]).toMatchObject({ decision: 'APPROVED', expectedSha256: base.planSha256, confirmIssueId: 'ISSUE-001' });
    expect(screen.getByRole('status')).toHaveTextContent(/does not start the Fixer/);
  });
});
