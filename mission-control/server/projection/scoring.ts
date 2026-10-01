/**
 * A faithful, side-effect-free re-implementation of 07a-merge-arbiter/scripts/compute-score.js, used
 * only to CHECK recorded verdicts against the evidence currently on disk (integrity rule R3). It never
 * produces a verdict of its own; the recorded verdict file remains the release authority.
 */
export interface Scoring {
  hard_gates: { rescan_still_vulnerable_blocks: boolean; build_failed_blocks: boolean };
  weights: { redteam: Record<string, number>; behavior: Record<string, number>; qa: Record<string, number> };
  default_threshold: number;
  severity_thresholds: Record<string, number>;
}

export interface ScoreInput {
  rescan: string | null;
  redteam: string | null;
  behavior: string | null;
  qa: string | null;
  build: string | null;
}

export interface ScoreResult {
  decision: 'Cleared' | 'Blocked';
  score: number;
  threshold: number;
  gates: string[];
}

export function computeScore(input: ScoreInput, severity: string | null, scoring: Scoring): ScoreResult {
  const gates: string[] = [];
  if (scoring.hard_gates.rescan_still_vulnerable_blocks && input.rescan === 'STILL_VULNERABLE') gates.push('re-scanner');
  if (scoring.hard_gates.build_failed_blocks && input.build === 'Failed') gates.push('build-gatekeeper');
  const pts = (table: Record<string, number>, v: string | null) => (v != null && table[v] != null ? table[v] : 0);
  const score = pts(scoring.weights.redteam, input.redteam) + pts(scoring.weights.behavior, input.behavior) + pts(scoring.weights.qa, input.qa);
  const threshold = (severity && scoring.severity_thresholds[severity]) || scoring.default_threshold;
  return { decision: gates.length ? 'Blocked' : (score >= threshold ? 'Cleared' : 'Blocked'), score, threshold, gates };
}

export const DEFAULT_SCORING: Scoring = {
  hard_gates: { rescan_still_vulnerable_blocks: true, build_failed_blocks: true },
  weights: { redteam: { NO_BYPASS_FOUND: 30, INCONCLUSIVE: 15, BYPASS_FOUND: 0 }, behavior: { BEHAVIOR_PRESERVED: 30, INCONCLUSIVE: 15, BEHAVIOR_CHANGED: 0 }, qa: { Passed: 40, Failed: 0 } },
  default_threshold: 85,
  severity_thresholds: { Critical: 90, High: 85, Medium: 75, Low: 65 },
};
