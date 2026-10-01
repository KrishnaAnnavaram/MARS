/**
 * Canonical MARS stage topology — derived from the harness code (agents, skills, gate scripts and the
 * pipeline contract), not from the README's stage numbering. See proposal §6.4. Until MARS ships a
 * machine-readable pipeline manifest, this table is Mission Control's "derived" stage registry and is
 * labelled as such in the UI.
 */
import type { StageDef, StageId } from './types.js';

export const STAGES: StageDef[] = [
  {
    id: 'intake', label: 'Issue intake', short: 'Intake', phase: 'understand', scope: 'issue', owner: 'reporter', skills: ['00-issue-register'],
    kind: 'human input', decidedBy: 'human', vocabulary: ['Open', 'In Progress', 'Fixed', 'Closed'], dependsOn: [],
    evidence: 'docs/agent_output/00-issues/issue-register.xlsx', description: 'A reporter appends a row to the Excel register. The register status is reporter-owned and never updated by the pipeline.',
  },
  {
    id: 'architecture', label: 'Architecture & knowledge graph', short: 'Arch', phase: 'understand', scope: 'workspace', owner: '01_architect',
    skills: ['01a-code-cartographer', '01b-context-weaver', '01c-graph-forge', '01d-blueprint-scribe'], kind: 'collect → author → load → render',
    decidedBy: 'mixed', vocabulary: ['fresh', 'stale'], dependsOn: [], evidence: 'docs/agent_output/01-architecture/', description: 'Parses source into a code model, writes the semantic layer, loads Neo4j and renders the architecture documents. Runs once per workspace.',
  },
  {
    id: 'rca', label: 'Root cause', short: 'RCA', phase: 'understand', scope: 'issue', owner: '02_root-cause-analyst', skills: ['00-issue-register', '02-root-cause-analyst'],
    kind: 'collect → author → render', decidedBy: 'agent', vocabulary: ['High', 'Medium', 'Low'], dependsOn: ['intake', 'architecture'],
    evidence: 'docs/agent_output/02-root-cause/root_cause_<id>.md', description: 'Diagnoses why the defect exists from the issue row, the architecture docs and graph traversal.',
  },
  {
    id: 'blast_radius', label: 'Blast radius', short: 'Blast', phase: 'understand', scope: 'issue', owner: '03_blast-radius-analyst', skills: ['03-blast-radius-analyst'],
    kind: 'collect → author → render', decidedBy: 'mixed', vocabulary: ['Broken', 'Degraded', 'At risk', 'Unaffected'], dependsOn: ['rca'],
    evidence: 'docs/agent_output/03-blast-radius/blast_radius_<id>.md', description: 'Measures reach: services, endpoints, scheduled jobs. Service status is computed by rule; the narrative is AI-authored.',
  },
  {
    id: 'plan', label: 'Fix plan', short: 'Plan', phase: 'fix', scope: 'issue', owner: '04_fix-generator', skills: ['04a-fix-strategist', '04a1-remediation-intelligence', '04a2-remediation-research'],
    kind: 'collect → author → render', decidedBy: 'agent', vocabulary: ['Proposed', 'Approved', 'Rejected'], dependsOn: ['rca'],
    evidence: 'docs/agent_output/04-remediation/fix_plan_<id>.md', description: 'Stage 1: a CWE-aligned strategy (catalog → KB → research fallbacks). Never a diff. Always starts at Proposed.',
  },
  {
    id: 'approval', label: 'Human plan decision', short: 'Approve', phase: 'fix', scope: 'issue', owner: 'human', skills: [],
    kind: 'human decision', decidedBy: 'human', vocabulary: ['Proposed', 'Approved', 'Rejected'], dependsOn: ['plan'],
    evidence: 'Status cell of fix_plan_<id>.md (+ decision record when recorded)', description: 'Only a human may change Proposed to Approved or Rejected. No code is drafted before this.',
  },
  {
    id: 'fix', label: 'Implement (patch)', short: 'Fix', phase: 'fix', scope: 'issue', owner: '04_fix-generator', skills: ['04b-fixer', '04c-dependency-upgrader'],
    kind: 'author → gate → render', decidedBy: 'mixed', vocabulary: ['Compiled', 'Compile Failed', 'Refused'], dependsOn: ['approval'],
    evidence: 'docs/agent_output/04-remediation/fix_<id>.md + .diff', description: 'Stage 2: the smallest diff for an Approved plan, applied and compiled in a throwaway git worktree.',
  },
  {
    id: 'rescan', label: 'Re-scan', short: 'Re-scan', phase: 'verify_ship', scope: 'issue', owner: '05_existing-app-test-agent', skills: ['05-verify'],
    kind: 'collect → author → render', decidedBy: 'agent', vocabulary: ['FIXED', 'STILL_VULNERABLE', 'INCONCLUSIVE'], dependsOn: ['fix'],
    evidence: 'docs/agent_output/05-verify/rescan_<id>.md', description: 'Does the originally reported finding still trigger against the patched code? AI judgement over script-collected facts.',
  },
  {
    id: 'redteam', label: 'Red-team', short: 'Red-team', phase: 'verify_ship', scope: 'issue', owner: '05_existing-app-test-agent', skills: ['05-verify'],
    kind: 'collect → author → render', decidedBy: 'agent', vocabulary: ['NO_BYPASS_FOUND', 'BYPASS_FOUND', 'INCONCLUSIVE'], dependsOn: ['fix'],
    evidence: 'docs/agent_output/05-verify/redteam_<id>.md', description: 'Can the patch be bypassed by a different vector? AI judgement grounded in the CWE catalog anti-patterns.',
  },
  {
    id: 'behavior', label: 'Behaviour guard', short: 'Behaviour', phase: 'verify_ship', scope: 'issue', owner: '05_existing-app-test-agent', skills: ['05-verify'],
    kind: 'collect → author → render', decidedBy: 'agent', vocabulary: ['BEHAVIOR_PRESERVED', 'BEHAVIOR_CHANGED', 'INCONCLUSIVE'], dependsOn: ['fix'],
    evidence: 'docs/agent_output/05-verify/behavior_<id>.md', description: 'Did behaviour change beyond the plan\'s stated scope? AI judgement over the materialized diff.',
  },
  {
    id: 'qa', label: 'QA gate', short: 'QA', phase: 'verify_ship', scope: 'issue', owner: '06_additional-test-execution', skills: ['06a-qa-runner'],
    kind: 'author → gate → render', decidedBy: 'mixed', vocabulary: ['Passed', 'Failed', 'Refused'], dependsOn: ['fix'],
    evidence: 'docs/agent_output/06-test-gate/qa_<id>.md', description: 'One AI-drafted regression test, executed for real in a worktree. Pass/fail is decided by the exit code.',
  },
  {
    id: 'build', label: 'Build gate', short: 'Build', phase: 'verify_ship', scope: 'issue', owner: '06_additional-test-execution', skills: ['06b-build-gatekeeper'],
    kind: 'gate → render', decidedBy: 'script', vocabulary: ['Passed', 'Failed', 'Refused'], dependsOn: ['fix'],
    evidence: 'docs/agent_output/06-test-gate/build_<id>.md', description: 'mvn verify plus a dependency-tree diff in a worktree. No agent-authored content by contract.',
  },
  {
    id: 'verdict', label: 'Merge verdict', short: 'Verdict', phase: 'verify_ship', scope: 'issue', owner: '07_audit-and-pr', skills: ['07a-merge-arbiter'],
    kind: 'score → author → render', decidedBy: 'script', vocabulary: ['Cleared', 'Blocked'], dependsOn: ['rescan', 'redteam', 'behavior', 'qa', 'build'],
    evidence: 'docs/agent_output/07-ship/verdict_<id>.md', description: 'The only release authority: hard gates first, then a weighted score against a severity threshold. The agent may only tighten Cleared to Blocked.',
  },
  {
    id: 'writeup', label: 'PR content & audit', short: 'Write-up', phase: 'verify_ship', scope: 'issue', owner: '07_audit-and-pr', skills: ['07b-scribe'],
    kind: 'collect → author → render', decidedBy: 'mixed', vocabulary: ['written'], dependsOn: ['verdict'],
    evidence: 'docs/agent_output/07-ship/pr_<id>.md, audit_<id>.md', description: 'PR-ready content and a chain-of-custody audit trail, written whether the verdict Cleared or Blocked.',
  },
  {
    id: 'publication', label: 'PR publication', short: 'PR', phase: 'verify_ship', scope: 'issue', owner: 'human', skills: [],
    kind: 'human-requested action', decidedBy: 'human', vocabulary: ['Not eligible', 'Eligible', 'Published'], dependsOn: ['verdict'],
    evidence: 'GitHub (no MARS record exists today)', description: 'Only for a Cleared verdict and only on an explicit human request; 07 Part 3 revalidates the diff in a worktree first.',
  },
];

export const BOARD_STAGES: StageId[] = ['rca', 'blast_radius', 'plan', 'approval', 'fix', 'rescan', 'redteam', 'behavior', 'qa', 'build', 'verdict', 'writeup', 'publication'];

export const STAGE_BY_ID: Record<StageId, StageDef> = Object.fromEntries(STAGES.map((s) => [s.id, s])) as Record<StageId, StageDef>;

export const PHASE_LABEL = { understand: 'Understand', fix: 'Fix', verify_ship: 'Verify & ship' } as const;

export const VERIFICATION_CHECKS = ['rescan', 'redteam', 'behavior', 'qa', 'build'] as const;
