import {
  AlertOctagon,
  AlertTriangle,
  Ban,
  CheckCircle2,
  Circle,
  CircleDashed,
  CircleDot,
  CirclePause,
  Clock,
  HelpCircle,
  Loader2,
  MinusCircle,
  ShieldAlert,
  ShieldCheck,
  UserRound,
  XCircle,
  type LucideIcon,
} from 'lucide-react';

export type Tone = 'success' | 'active' | 'human' | 'warning' | 'danger' | 'idle' | 'muted';

export interface StatusStyle {
  tone: Tone;
  icon: LucideIcon;
  label: string;
  spin?: boolean;
}

/** Tailwind classes per tone: text, soft background and border. */
export const toneClasses: Record<Tone, { text: string; bg: string; border: string; dot: string }> = {
  success: { text: 'text-success', bg: 'bg-success-soft', border: 'border-success/40', dot: 'bg-success' },
  active: { text: 'text-primary', bg: 'bg-primary-soft', border: 'border-primary/50', dot: 'bg-primary' },
  human: { text: 'text-human', bg: 'bg-human-soft', border: 'border-human/50', dot: 'bg-human' },
  warning: { text: 'text-warning', bg: 'bg-warning-soft', border: 'border-warning/50', dot: 'bg-warning' },
  danger: { text: 'text-danger', bg: 'bg-danger-soft', border: 'border-danger/50', dot: 'bg-danger' },
  idle: { text: 'text-idle', bg: 'bg-idle-soft', border: 'border-idle/40', dot: 'bg-idle' },
  muted: { text: 'text-muted', bg: 'bg-panel-3', border: 'border-border', dot: 'bg-faint' },
};

const S = (tone: Tone, icon: LucideIcon, label: string, spin = false): StatusStyle => ({ tone, icon, label, spin });

/** Pipeline stage statuses (from the server's projection of the state-machine history). */
export const stageStatus: Record<string, StatusStyle> = {
  COMPLETED: S('success', CheckCircle2, 'Completed'),
  ACTIVE: S('active', Loader2, 'Running', true),
  WAITING: S('human', UserRound, 'Waiting for a human'),
  BLOCKED: S('danger', Ban, 'Blocked'),
  FAILED: S('danger', XCircle, 'Failed'),
  SKIPPED: S('muted', MinusCircle, 'Skipped'),
  PENDING: S('muted', Circle, 'Pending'),
  IDLE: S('idle', CirclePause, 'Idle (not advancing)'),
};

export const livenessStatus: Record<string, StatusStyle> = {
  ADVANCING: S('active', Loader2, 'Advancing', true),
  WAITING_FOR_HUMAN: S('human', UserRound, 'Waiting for a human'),
  FINISHED: S('success', CheckCircle2, 'Finished'),
  FAILED: S('danger', XCircle, 'Failed'),
  IDLE: S('idle', CirclePause, 'Idle'),
  EXTERNAL_ACTIVITY: S('warning', CircleDot, 'Activity from another process'),
  INTERRUPTED_ANALYSIS: S('warning', AlertTriangle, 'Analysis interrupted'),
  UNREADABLE: S('danger', AlertOctagon, 'Unreadable'),
};

export const verdictStatus: Record<string, StatusStyle> = {
  CLEARED: S('success', ShieldCheck, 'Cleared'),
  PARTIAL: S('warning', AlertTriangle, 'Partial'),
  BLOCKED: S('danger', Ban, 'Blocked'),
  NEEDS_HUMAN: S('human', UserRound, 'Needs human'),
  INSUFFICIENT_EVIDENCE: S('warning', HelpCircle, 'Insufficient evidence'),
};

export const trafficLight: Record<string, StatusStyle> = {
  GREEN: S('success', CheckCircle2, 'GREEN: not required'),
  YELLOW: S('warning', AlertTriangle, 'YELLOW: recommended'),
  RED: S('danger', AlertOctagon, 'RED: prerequisite'),
  UNKNOWN: S('muted', HelpCircle, 'UNKNOWN'),
};

export const severityStatus: Record<string, StatusStyle> = {
  CRITICAL: S('danger', ShieldAlert, 'Critical'),
  HIGH: S('danger', AlertTriangle, 'High'),
  MEDIUM: S('warning', AlertTriangle, 'Medium'),
  LOW: S('active', CircleDot, 'Low'),
  INFO: S('muted', Circle, 'Info'),
  UNKNOWN: S('muted', HelpCircle, 'Unknown'),
};

/** Validation dimension statuses. Only PASS and PASS_WITH_EXPLAINED_DIFFERENCES are passing. */
export const dimensionStatus: Record<string, StatusStyle> = {
  PASS: S('success', CheckCircle2, 'Pass'),
  PASS_WITH_EXPLAINED_DIFFERENCES: S('success', CheckCircle2, 'Pass (explained differences)'),
  FAIL: S('danger', XCircle, 'Fail'),
  NOT_RUN: S('muted', CircleDashed, 'Not run'),
  NOT_APPLICABLE: S('muted', MinusCircle, 'Not applicable'),
  NOT_COMPARED: S('warning', HelpCircle, 'Not compared'),
  TOOL_UNAVAILABLE: S('warning', AlertTriangle, 'Tool unavailable'),
  INSUFFICIENT_EVIDENCE: S('warning', HelpCircle, 'Insufficient evidence'),
};

export const eventStatus: Record<string, StatusStyle> = {
  STARTED: S('active', CircleDot, 'Started'),
  PROGRESS: S('active', Loader2, 'In progress'),
  COMPLETED: S('success', CheckCircle2, 'Completed'),
  WAITING: S('human', UserRound, 'Waiting'),
  FAILED: S('danger', XCircle, 'Failed'),
  SKIPPED: S('muted', MinusCircle, 'Skipped'),
  INFO: S('muted', Circle, 'Info'),
};

export const journeyStatus: Record<string, StatusStyle> = {
  DONE: S('success', CheckCircle2, 'Done'),
  CURRENT: S('active', CircleDot, 'Current'),
  WAITING: S('human', UserRound, 'Waiting'),
  PENDING: S('muted', Circle, 'Pending'),
  FAILED: S('danger', XCircle, 'Failed'),
  SKIPPED: S('muted', MinusCircle, 'Skipped'),
  NOT_APPLICABLE: S('muted', MinusCircle, 'Not applicable'),
};

export const proposalStatus: Record<string, StatusStyle> = {
  PROPOSED: S('human', Clock, 'Proposed'),
  AWAITING_APPROVAL: S('human', UserRound, 'Awaiting approval'),
  APPROVED: S('active', CheckCircle2, 'Approved'),
  APPLIED: S('active', CheckCircle2, 'Applied'),
  VALIDATED: S('success', ShieldCheck, 'Validated'),
  FAILED_VALIDATION: S('danger', XCircle, 'Failed validation'),
  REJECTED: S('danger', XCircle, 'Rejected'),
  DEFERRED: S('warning', CirclePause, 'Deferred'),
  STALE: S('warning', AlertTriangle, 'Stale'),
  REVERTED: S('danger', AlertOctagon, 'Rolled back'),
  BLOCKED_BY_PLATFORM: S('warning', Ban, 'Blocked by platform'),
};

export function lookup(table: Record<string, StatusStyle>, key?: string): StatusStyle {
  if (!key) return S('muted', Circle, '—');
  return table[key] ?? S('muted', Circle, key.replace(/_/g, ' '));
}
