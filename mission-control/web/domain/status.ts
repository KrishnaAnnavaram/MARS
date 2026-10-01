/**
 * The single place where MARS semantics become UI semantics. Every screen renders states, failure
 * classes, provenance and modifiers through these tables so that, e.g., "Blocked by environment" and
 * "Blocked by security verification" never look alike anywhere.
 */
import type { CellModifier, CellState, FailureClass, Provenance } from '../../shared/types';

export type Role = 'fail' | 'tool' | 'human' | 'warn' | 'run' | 'neutral' | 'pass';

export type Glyph = 'triangle' | 'octagon' | 'diamond' | 'person' | 'square-q' | 'half' | 'ring' | 'dashed-ring' | 'ring-bar' | 'check' | 'slash' | 'dotted-q' | 'dashed-square';

export const STATE: Record<CellState, { label: string; role: Role; glyph: Glyph; rank: number; description: string }> = {
  failed: { label: 'Failed', role: 'fail', glyph: 'triangle', rank: 1, description: 'A check or gate ran and reported failure.' },
  blocked: { label: 'Blocked', role: 'fail', glyph: 'octagon', rank: 1, description: 'A deterministic gate or decision stopped progress.' },
  tool_error: { label: 'Tool error', role: 'tool', glyph: 'diamond', rank: 2, description: 'No trustworthy result: the toolchain or infrastructure failed.' },
  awaiting_human: { label: 'Awaiting human', role: 'human', glyph: 'person', rank: 0, description: 'A person must act before MARS can continue.' },
  inconclusive: { label: 'Inconclusive', role: 'warn', glyph: 'square-q', rank: 3, description: 'The evidence did not settle the question.' },
  running: { label: 'Running', role: 'run', glyph: 'half', rank: 4, description: 'In progress now (from live telemetry).' },
  ready: { label: 'Ready', role: 'neutral', glyph: 'ring', rank: 5, description: 'Prerequisites are met; the stage has not run.' },
  waiting: { label: 'Waiting', role: 'neutral', glyph: 'dashed-ring', rank: 6, description: 'Prerequisites are not met yet.' },
  blocked_upstream: { label: 'Blocked upstream', role: 'neutral', glyph: 'ring-bar', rank: 5, description: 'Will not run until an upstream stage changes.' },
  passed: { label: 'Passed', role: 'pass', glyph: 'check', rank: 7, description: 'Completed with a positive outcome.' },
  not_applicable: { label: 'Not applicable', role: 'neutral', glyph: 'slash', rank: 8, description: 'Not on this issue\'s path. Never counts as a pass.' },
  missing: { label: 'Missing', role: 'fail', glyph: 'dashed-square', rank: 2, description: 'Expected evidence is missing.' },
  unknown: { label: 'Unknown', role: 'neutral', glyph: 'dotted-q', rank: 6, description: 'The evidence could not be read.' },
};

export const ROLE_CLASSES: Record<Role, { fg: string; bg: string; border: string }> = {
  fail: { fg: 'text-fail', bg: 'bg-fail-tint', border: 'border-fail' },
  tool: { fg: 'text-tool', bg: 'bg-tool-tint', border: 'border-tool' },
  human: { fg: 'text-human', bg: 'bg-human-tint', border: 'border-human' },
  warn: { fg: 'text-warn', bg: 'bg-warn-tint', border: 'border-warn' },
  run: { fg: 'text-run', bg: 'bg-run-tint', border: 'border-run' },
  neutral: { fg: 'text-neutral', bg: 'bg-neutral-tint', border: 'border-line-strong' },
  pass: { fg: 'text-pass', bg: 'bg-pass-tint', border: 'border-pass' },
};

export const FAILURE: Record<FailureClass, { label: string; short: string; kind: 'security' | 'behaviour' | 'code' | 'test' | 'build' | 'environment' | 'infrastructure' | 'decision' | 'unknown' }> = {
  'security.still_vulnerable': { label: 'Vulnerability still present', short: 'Still vulnerable', kind: 'security' },
  'security.bypass': { label: 'Bypass found', short: 'Bypass', kind: 'security' },
  'behavior.regression': { label: 'Behavioural regression', short: 'Regression', kind: 'behaviour' },
  'compile.error': { label: 'Compilation failed — cause unverified', short: 'Compile error', kind: 'code' },
  'qa.test_failed': { label: 'Regression test failed', short: 'Test failed', kind: 'test' },
  'build.failed': { label: 'Build failed', short: 'Build failed', kind: 'build' },
  'environment.toolchain': { label: 'Build environment (toolchain)', short: 'Toolchain', kind: 'environment' },
  infrastructure: { label: 'Harness / infrastructure failure', short: 'Infrastructure', kind: 'infrastructure' },
  'patch.apply': { label: 'Patch does not apply', short: 'Apply failed', kind: 'code' },
  refused: { label: 'Refused by a gate', short: 'Refused', kind: 'decision' },
  rejected: { label: 'Rejected by a human', short: 'Rejected', kind: 'decision' },
  unclassified: { label: 'Failure, cause not classified', short: 'Unclassified', kind: 'unknown' },
};

export const PROVENANCE: Record<Provenance, { label: string; short: string; description: string }> = {
  computed: { label: 'Computed', short: 'Computed', description: 'Produced by a deterministic MARS script.' },
  ai_authored: { label: 'AI-authored', short: 'AI', description: 'Written by an agent (LLM). Verify before acting.' },
  human: { label: 'Human', short: 'Human', description: 'A human decision or input.' },
  mixed: { label: 'Mixed', short: 'Mixed', description: 'Script-collected facts plus agent-authored judgement, merged by a renderer.' },
  unknown: { label: 'Unknown', short: '?', description: 'Provenance not recorded.' },
};

export const MODIFIER: Record<CellModifier, { label: string; description: string }> = {
  conflict: { label: 'Evidence conflict', description: 'Two representations of this fact disagree (see integrity findings).' },
  stale: { label: 'Stale', description: 'Older than its upstream evidence.' },
  unattributed: { label: 'Unattributed', description: 'No record of who made this decision, when, or for which version.' },
  carried_over: { label: 'Carried over', description: 'The plan was re-proposed after the decision; the decision applies to an earlier version.' },
  compile_failed_fix: { label: 'Fix did not compile', description: 'This check analysed a patch that failed to compile.' },
  declared_manual_edit: { label: 'Edited after rendering', description: 'The report contains text the renderer never writes (hand or agent edit).' },
  live: { label: 'Live', description: 'State from live telemetry.' },
};

export function severityRank(s: string | null): number {
  return ({ Critical: 4, High: 3, Medium: 2, Low: 1 } as Record<string, number>)[s || ''] || 0;
}
