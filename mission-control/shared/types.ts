/** Mission Control API contract shared by the server and the web client. */

export type StageId =
  | 'intake' | 'architecture' | 'rca' | 'blast_radius' | 'plan' | 'approval' | 'fix'
  | 'rescan' | 'redteam' | 'behavior' | 'qa' | 'build' | 'verdict' | 'writeup' | 'publication';

export type Phase = 'understand' | 'fix' | 'verify_ship';
export type DecidedBy = 'script' | 'agent' | 'human' | 'mixed';
export type Provenance = 'computed' | 'ai_authored' | 'human' | 'mixed' | 'unknown';

/** Stage-state channel (proposal §40.3). */
export type CellState =
  | 'waiting' | 'ready' | 'running' | 'awaiting_human' | 'passed' | 'inconclusive' | 'failed'
  | 'tool_error' | 'blocked' | 'blocked_upstream' | 'not_applicable' | 'missing' | 'unknown';

export type FailureClass =
  | 'security.still_vulnerable' | 'security.bypass' | 'behavior.regression' | 'compile.error'
  | 'qa.test_failed' | 'build.failed' | 'environment.toolchain' | 'infrastructure' | 'patch.apply'
  | 'refused' | 'rejected' | 'unclassified';

export type CellModifier = 'conflict' | 'stale' | 'unattributed' | 'carried_over' | 'compile_failed_fix' | 'declared_manual_edit' | 'live';

export interface StageDef {
  id: StageId;
  label: string;
  short: string;
  phase: Phase;
  scope: 'issue' | 'workspace';
  owner: string;
  skills: string[];
  kind: string;
  decidedBy: DecidedBy;
  vocabulary: string[];
  dependsOn: StageId[];
  evidence: string;
  description: string;
}

export interface EvidenceRef {
  path: string;
  type: string;
  sha256: string | null;
  generatedAt: string | null;
  provenance: Provenance;
}

export interface Cell {
  stage: StageId;
  state: CellState;
  label: string;
  outcome: string | null;
  detail: string | null;
  failureClass: FailureClass | null;
  causeVerified: boolean;
  decidedBy: DecidedBy;
  provenance: Provenance;
  time: string | null;
  evidence: EvidenceRef | null;
  modifiers: CellModifier[];
  findings: string[];
}

export interface BlockerCause {
  label: string;
  kind: 'hard_gate' | 'score' | 'lane' | 'decision' | 'missing';
  failureClass: FailureClass | null;
  verified: boolean;
  stage: StageId;
}

export interface Blocker {
  summary: string;
  causes: BlockerCause[];
}

export interface NextAction {
  stage: StageId;
  owner: string;
  ownerKind: 'human' | 'agent' | 'operator';
  text: string;
  basis: string;
}

export interface VerdictSummary {
  decision: 'Cleared' | 'Blocked' | string;
  score: number | null;
  threshold: number | null;
  hardGates: string[];
  overrideApplied: boolean;
  replayDecision: string | null;
  replayMatches: boolean | null;
}

export interface IssueSummary {
  id: string;
  title: string;
  severity: string | null;
  type: string | null;
  cwe: string | null;
  cve: string | null;
  owasp: string | null;
  services: string[];
  registerStatus: string | null;
  inRegister: boolean;
  priority: string | null;
  headline: string | null;
  cells: Record<StageId, Cell>;
  verdict: VerdictSummary | null;
  blocker: Blocker | null;
  nextAction: NextAction | null;
  attention: number;
  findings: number;
}

export interface IntegrityFinding {
  id: string;
  rule: string;
  ruleTitle: string;
  severity: 'critical' | 'major' | 'minor' | 'info';
  title: string;
  detail: string;
  issueId: string | null;
  stage: StageId | null;
  subjects: string[];
}

export interface LedgerEvent {
  schema: string;
  event_id: string;
  seq: number | null;
  gseq: number;
  time: string;
  type: string;
  workspace_id?: string;
  harness?: { sha?: string | null };
  source?: { emitter?: string; id?: string; runtime?: string };
  session_id?: string;
  run_id?: string;
  trace_id?: string;
  span_id?: string;
  parent_span_id?: string;
  issue_ids?: string[];
  stage_id?: string;
  agent_id?: string;
  skill_id?: string;
  script_id?: string;
  step_kind?: string;
  actor?: { kind?: string; id?: string; authentication?: string };
  provenance?: Provenance;
  status?: string;
  outcome?: string;
  failure?: { class?: string; code?: string | null; summary?: string };
  inputs?: { path: string; sha256: string | null; artifact_type?: string }[];
  outputs?: { path: string; sha256: string | null; artifact_type?: string }[];
  duration_ms?: number;
  attrs?: Record<string, unknown>;
  summary?: string;
}

export interface OperationSummary {
  spanId: string;
  name: string;
  kind: 'script' | 'command' | 'write' | 'skill' | 'gate-event' | 'other';
  skill: string | null;
  script: string | null;
  stage: StageId | null;
  stepKind: string | null;
  issues: string[];
  status: 'running' | 'completed' | 'failed' | 'refused';
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  outcome: string | null;
  detail: string | null;
}

export interface ActiveRun {
  runId: string;
  agentId: string | null;
  isMarsAgent: boolean;
  kind: 'agent_run' | 'session';
  sessionId: string | null;
  startedAt: string;
  lastEventAt: string;
  elapsedMs: number;
  stage: StageId | null;
  skill: string | null;
  issues: string[];
  current: OperationSummary | null;
  previous: OperationSummary | null;
  nextExpected: string | null;
  nextExpectedBasis: string | null;
  artifacts: { path: string; sha256: string | null; time: string }[];
  failures: number;
  waitingHuman: boolean;
}

export interface NowState {
  status: 'running' | 'idle' | 'waiting_human' | 'stale';
  telemetry: 'live' | 'none';
  ledgerEvents: number;
  activeRuns: ActiveRun[];
  lastEvent: LedgerEvent | null;
  lastActivityAt: string | null;
  lastActivitySource: 'ledger' | 'evidence' | null;
  lastEvidence: { path: string; time: string; issueId: string | null; stage: StageId | null } | null;
}

export interface AttentionItem {
  kind: 'decision' | 'publication' | 'integrity' | 'invalidated_approval' | 'running_human_wait';
  issueId: string | null;
  title: string;
  detail: string;
  severity: 'critical' | 'major' | 'minor' | 'info';
  since: string | null;
  link: string;
}

export interface TrustItem {
  id: string;
  label: string;
  status: 'ok' | 'warn' | 'fail' | 'unknown';
  detail: string;
  link: string;
}

export interface BoardSummary {
  issues: number;
  awaitingDecision: number;
  /** Approved plans whose approval no longer covers the plan on disk or the patch (re-proposed / deviates). */
  approvalsToReview: number;
  blocked: number;
  cleared: number;
  published: number;
  running: number;
  blockedBy: { label: string; count: number; verified: boolean }[];
  integrityFindings: number;
}

export interface WorkspaceInfo {
  root: string;
  name: string;
  branch: string | null;
  head: string | null;
  runtimes: { claude: boolean; github: boolean };
  hooksConfigured: boolean;
  decisions: { enabled: boolean; reason: string; actor: string | null };
  scoringSha256: string | null;
}

export interface Overview {
  asOf: string;
  version: number;
  workspace: WorkspaceInfo;
  now: NowState;
  attention: AttentionItem[];
  stages: StageDef[];
  issues: IssueSummary[];
  summary: BoardSummary;
  trust: TrustItem[];
}

export interface CompileError {
  file: string;
  line: number;
  column: number;
  message: string;
  symbol: string | null;
  inPatchedFile: boolean;
}

export interface Lane {
  check: 'rescan' | 'redteam' | 'behavior' | 'qa' | 'build';
  label: string;
  decidedBy: DecidedBy;
  decidedByNote: string;
  state: CellState;
  outcome: string | null;
  failureClass: FailureClass | null;
  causeVerified: boolean;
  confidence: string | null;
  facts: { label: string; value: string }[];
  headerStatus: string | null;
  bodyStatus: string | null;
  tests: { name: string; status: string; exitCode: number | null }[];
  steps: { module: string; command: string; exitCode: number | null }[];
  compileErrors: CompileError[];
  compileErrorsTruncated: boolean;
  aiClaims: string[];
  time: string | null;
  evidence: EvidenceRef | null;
  findings: string[];
  headline: string | null;
}

export interface VerdictDetail {
  decision: string;
  score: number | null;
  threshold: number | null;
  severity: string | null;
  hardGates: { name: string; triggered: boolean }[];
  breakdown: { check: string; verdict: string; points: number | null; max: number | null }[];
  override: { applied: boolean; reason: string | null };
  narrative: string | null;
  computedAt: string | null;
  evidence: EvidenceRef | null;
  replay: { decision: string; score: number; threshold: number; gates: string[]; basis: string; matchesRecorded: boolean } | null;
  bodyReplay: { decision: string; score: number; threshold: number; gates: string[]; basis: string; matchesRecorded: boolean } | null;
  policySha256: string | null;
}

export interface DecisionRecordView {
  decisionId: string;
  decision: string;
  actor: string;
  actorAuthentication: string;
  channel: string;
  rationale: string;
  timestamp: string;
  shaBefore: string;
  shaAfter: string;
  appliesToCurrent: boolean;
  path: string;
}

export interface PlanInfo {
  status: string | null;
  cwe: string | null;
  cweName: string | null;
  owasp: string | null;
  dependency: string | null;
  confidence: string | null;
  route: 'catalog' | 'kb' | 'research' | 'evidence_gap' | 'unknown';
  headline: string | null;
  approach: string | null;
  alternatives: string[];
  plannedChanges: { file: string; change: string }[];
  risks: string[];
  verification: string[];
  reproposed: boolean;
  reproposedNote: string | null;
  generatedAt: string | null;
  evidence: EvidenceRef | null;
}

export type ApprovalState = 'pending' | 'approved' | 'rejected' | 'approved_unattributed' | 'rejected_unattributed' | 'invalidated' | 'no_plan';

export interface ApprovalInfo {
  issueId: string;
  title: string;
  severity: string | null;
  state: ApprovalState;
  stateLabel: string;
  planPath: string | null;
  planSha256: string | null;
  plan: PlanInfo | null;
  decisions: DecisionRecordView[];
  implementation: { status: string | null; filesInDiff: string[]; plannedNotChanged: string[]; deviation: string | null } | null;
  services: string[];
  priority: string | null;
  actions: { allowed: boolean; reason: string; available: ('APPROVED' | 'REJECTED')[]; unavailable: { action: string; reason: string }[] };
}

export interface FixInfo {
  status: string | null;
  verificationLevel: string | null;
  matchesPlan: string | null;
  claimedFiles: { file: string; change: string }[];
  dependency: string | null;
  headline: string | null;
  generatedAt: string | null;
  evidence: EvidenceRef | null;
}

export interface DiffInfo {
  path: string;
  sha256: string | null;
  files: { path: string; additions: number; deletions: number }[];
  text: string;
}

export interface TimelineItem {
  time: string | null;
  timeSource: 'observed' | 'reconstructed' | 'unknown';
  stage: StageId | null;
  actorKind: 'human' | 'agent' | 'script' | 'system' | 'unknown';
  actor: string;
  provenance: Provenance;
  title: string;
  detail: string | null;
  evidence: EvidenceRef | null;
  gap: boolean;
}

export interface CodeRef {
  kind: 'module' | 'type' | 'method' | 'endpoint' | 'file';
  id: string;
  label: string;
  role: string;
  resolved: boolean;
}

export interface IssueDetail extends IssueSummary {
  register: {
    reportedOn: string | null;
    reportedBy: string | null;
    symbols: string[];
    files: string[];
    entryPoints: string[];
    summary: string | null;
  } | null;
  rca: {
    headline: string | null;
    whatBreaks: string | null;
    rootCause: string | null;
    where: string | null;
    service: string | null;
    confidence: string | null;
    methods: { method: string; service: string; file: string }[];
    generatedAt: string | null;
    evidence: EvidenceRef | null;
  } | null;
  blast: {
    headline: string | null;
    priority: string | null;
    spread: string | null;
    servicesBroken: string[];
    servicesDegraded: string[];
    endpointsDown: string | null;
    scheduledJobs: string | null;
    confidence: string | null;
    services: { service: string; status: string; note: string }[];
    endpoints: { endpoint: string; service: string; status: string; note: string }[];
    generatedAt: string | null;
    evidence: EvidenceRef | null;
  } | null;
  plan: PlanInfo | null;
  approval: ApprovalInfo;
  fix: FixInfo | null;
  diff: DiffInfo | null;
  lanes: Lane[];
  verdictDetail: VerdictDetail | null;
  writeup: { prPath: string | null; auditPath: string | null; prBlockedBanner: boolean; prTitle: string | null };
  publication: { eligible: boolean; reason: string; records: number };
  timeline: TimelineItem[];
  evidence: EvidenceRef[];
  findingList: IntegrityFinding[];
  codeRefs: CodeRef[];
}

export interface SpanView {
  id: string;
  parentId: string | null;
  kind: 'run' | 'skill' | 'operation' | 'write' | 'event';
  name: string;
  detail: string | null;
  skill: string | null;
  script: string | null;
  stage: StageId | null;
  stepKind: string | null;
  issues: string[];
  status: 'running' | 'completed' | 'failed' | 'refused' | 'info';
  provenance: Provenance;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  outcome: string | null;
  failure: { class?: string; code?: string | null; summary?: string } | null;
  inputs: { path: string; sha256: string | null }[];
  outputs: { path: string; sha256: string | null }[];
  attrs: Record<string, unknown>;
  eventIds: string[];
}

export interface RunSummary {
  runId: string;
  source: 'observed' | 'reconstructed';
  kind: 'agent_run' | 'session' | 'reconstructed';
  agentId: string | null;
  isMarsAgent: boolean;
  sessionId: string | null;
  startedAt: string;
  endedAt: string | null;
  status: 'running' | 'completed' | 'failed' | 'stale' | 'unknown';
  durationMs: number | null;
  issues: string[];
  operations: number;
  failures: number;
  writes: number;
  label: string;
}

export interface RunDetail extends RunSummary {
  spans: SpanView[];
  events: LedgerEvent[];
  note: string | null;
}

export interface ScriptInfo {
  id: string;
  skill: string;
  name: string;
  role: string;
  usage: string | null;
  file: string;
}

export interface AgentInfo {
  id: string;
  file: string;
  description: string;
  tools: string[];
  skills: string[];
  stages: StageId[];
  runtimes: { claude: boolean; github: boolean; differs: boolean | null };
  constraints: { text: string; enforcedBy: string | null; contradicts: string | null }[];
  upstream: string[];
  downstream: string[];
  outputs: string[];
  telemetry: { runs: number; lastRunAt: string | null; failures: number; avgDurationMs: number | null; active: boolean } | null;
}

export interface SkillInfo {
  id: string;
  name: string;
  description: string;
  argumentHint: string | null;
  agents: string[];
  stage: StageId | 'migration' | 'verify' | null;
  scripts: ScriptInfo[];
  schemas: string[];
  policies: { path: string; sha256: string | null }[];
  dependencies: { npm: string[]; optional: string[]; neo4j: boolean; python: boolean; llmApi: boolean; zeroDependency: boolean };
  mirror: { exists: boolean; differs: boolean | null; onlyInGithub: boolean };
  pointer: string | null;
  telemetry: { executions: number; lastAt: string | null; failures: number; active: boolean } | null;
  skillMdPath: string;
}

export interface Registry {
  agents: AgentInfo[];
  skills: SkillInfo[];
  scripts: ScriptInfo[];
  stages: StageDef[];
  stagesSource: 'derived';
  drift: { id: string; title: string; detail: string; severity: 'major' | 'minor' | 'info' }[];
}

export interface ArtifactInfo {
  path: string;
  type: string;
  issueId: string | null;
  stage: StageId | null;
  producer: string | null;
  producerAgent: string | null;
  provenance: Provenance;
  provenanceNote: string | null;
  generatedAt: string | null;
  sha256: string;
  size: number;
  consumers: string[];
  inputs: string[];
  findings: string[];
  parseOk: boolean;
}

export interface ArtifactContent {
  path: string;
  kind: 'markdown' | 'diff' | 'json' | 'text' | 'binary';
  sha256: string;
  size: number;
  content: string | null;
  truncated: boolean;
}

export interface LineageGraph {
  issueId: string;
  nodes: { id: string; label: string; type: string; stage: StageId | null; provenance: Provenance; exists: boolean; findings: number }[];
  edges: { from: string; to: string; label: string }[];
}

export interface AuditItem extends TimelineItem {
  issueId: string | null;
  type: string;
  /** Critical/major integrity findings on this row's evidence file. */
  conflicts?: number;
}

export interface ArchNode {
  id: string;
  kind: 'module' | 'type' | 'method' | 'endpoint';
  label: string;
  module: string | null;
  file: string | null;
  lines: [number, number] | null;
  role: string | null;
  ctx: { summary: string | null; author: string | null; confidence: string | null } | null;
}

export interface ArchEdge {
  from: string;
  to: string;
  kind: 'contains' | 'has_method' | 'calls' | 'exposes' | 'http';
}

export interface ArchitectureView {
  source: 'code-model' | 'neo4j';
  generatedAt: string | null;
  neo4j: { configured: boolean; note: string };
  counts: { modules: number; types: number; methods: number; endpoints: number; calls: number };
  recordedGraphCounts: Record<string, number> | null;
  focus: string | null;
  issueId: string | null;
  overlay: Record<string, string>;
  nodes: ArchNode[];
  edges: ArchEdge[];
  truncated: boolean;
  note: string | null;
}

export interface HealthCheck {
  id: string;
  label: string;
  status: 'pass' | 'warn' | 'fail' | 'unknown';
  summary: string;
  details: string[];
  affects: string[];
  checkedAt: string;
}

export interface SessionInfo {
  actor: string | null;
  authentication: 'LOCALLY_ASSERTED';
  role: 'viewer' | 'approver';
  decisions: { enabled: boolean; reason: string };
}
