/*
 * Wire types of the Control Center API (snake_case, nulls omitted). They mirror the server DTOs in
 * apps/control-center-api/src/main/java/com/mars/harness/controlcenter/api/dto. Optional fields are
 * absent when the server has no value; the UI shows that as "not available", never as a default.
 */

export type Maybe<T> = T | undefined;

export interface ApiErrorBody {
  code: string;
  message: string;
  run_id?: string;
  correlation_id?: string;
  details?: Record<string, unknown>;
}

// ------------------------------------------------------------------ session

export interface SessionView {
  authenticated: boolean;
  username?: string;
  display_name?: string;
  roles: string[];
  auth_mode: 'dev' | 'oidc';
  authentication?: string;
  warning?: string;
  permissions: string[];
}

export interface DevUserOption {
  username: string;
  display_name: string;
  roles: string[];
}

// ------------------------------------------------------------------ runs

export type LivenessState =
  | 'ADVANCING'
  | 'WAITING_FOR_HUMAN'
  | 'FINISHED'
  | 'FAILED'
  | 'IDLE'
  | 'EXTERNAL_ACTIVITY'
  | 'INTERRUPTED_ANALYSIS'
  | 'UNREADABLE';

export interface Job {
  kind: 'ANALYZE' | 'ADVANCE';
  triggered_by?: string;
  started_at: string;
  description?: string;
}

export interface LastJob {
  kind: string;
  started_at: string;
  finished_at: string;
  failed: boolean;
  error?: string;
}

export interface Liveness {
  state: LivenessState;
  explanation: string;
  job?: Job;
  last_job?: LastJob;
}

export interface RunSummary {
  run_id: string;
  application?: string;
  source?: string;
  created_at?: string;
  updated_at?: string;
  phase: string;
  phase_label: string;
  waiting_for_human: boolean;
  terminal: boolean;
  verdict?: string;
  strategy?: string;
  migration?: string;
  security?: string;
  findings: number;
  human_actions: number;
  gate?: string;
  liveness: Liveness;
  harness_version?: string;
}

export interface RunPage {
  runs: RunSummary[];
  total: number;
}

export type StageStatus = 'COMPLETED' | 'ACTIVE' | 'WAITING' | 'BLOCKED' | 'FAILED' | 'SKIPPED' | 'PENDING' | 'IDLE';

export interface PipelineStage {
  id: string;
  label: string;
  group: 'ANALYSIS' | 'GATE' | 'MIGRATION' | 'SECURITY' | 'FINAL';
  status: StageStatus;
  phases: string[];
  entered_at?: string;
  exited_at?: string;
  duration_ms?: number;
  reason?: string;
  summary?: string;
  next: string[];
  entries: number;
}

export interface StageProgress {
  completed: number;
  applicable: number;
  basis: string;
}

export interface Progress {
  mode: 'DETERMINATE' | 'INDETERMINATE';
  completed?: number;
  total?: number;
  unit?: string;
}

export interface SubjectRef {
  kind: string;
  id: string;
  label?: string;
}

export interface CurrentActivity {
  activity: string;
  component?: string;
  title?: string;
  message?: string;
  status: string;
  started_at: string;
  last_update_at: string;
  progress?: Progress;
  subjects: SubjectRef[];
  sequence: number;
  stale: boolean;
  capability?: string;
}

export interface MigrationSummary {
  traffic_light?: string;
  need?: string;
  current_version?: string;
  target_version?: string;
  framework?: string;
  status: string;
  status_detail?: string;
  rounds?: number;
  max_rounds?: number;
  reference_pack?: string;
}

export interface SecuritySummary {
  total: number;
  by_severity: Record<string, number>;
  analysed: number;
  planned: number;
  proposals_awaiting: number;
  applied: number;
  cleared: number;
  blocked: number;
  deferred: number;
  rejected: number;
  blocked_by_platform: number;
}

export interface ChangesSummary {
  total: number;
  by_status: Record<string, number>;
  by_capability: Record<string, number>;
}

export interface ValidationSummary {
  status: 'NOT_RUN' | 'PASSED' | 'FAILED' | 'INCOMPLETE';
  dimensions: number;
  passed: number;
  failed: number;
  unknown: number;
  mandatory_failed: string[];
  mandatory_unknown: string[];
}

export interface RunSnapshot {
  run_id: string;
  application?: string;
  source?: string;
  created_at?: string;
  updated_at?: string;
  phase: string;
  phase_label: string;
  phase_description: string;
  waiting_for_human: boolean;
  terminal: boolean;
  liveness: Liveness;
  verdict?: string;
  strategy?: string;
  execution_decision_id?: string;
  baseline_seal?: string;
  harness_version?: string;
  policy_version?: string;
  evaluation_date?: string;
  skip_build: boolean;
  last_event_sequence: number;
  pipeline: PipelineStage[];
  /** Stage IDs in the order the run entered them, from its state history. */
  pipeline_path: string[];
  stage_progress: StageProgress;
  current_activity?: CurrentActivity;
  human_actions: { count: number; gate?: string; headline?: string };
  migration: MigrationSummary;
  security: SecuritySummary;
  changes: ChangesSummary;
  validation: ValidationSummary;
  notes: string[];
  integrity: {
    decisions: number;
    tampered_decisions: string[];
    evidence_records: number;
    evidence_chain_violations: string[];
  };
  environment?: {
    java_version?: string;
    os?: string;
    build_tool?: string;
    build_tool_available?: boolean;
    build_tool_unavailable_reason?: string;
    network_enabled?: boolean;
  };
}

export interface Transition {
  index: number;
  from: string;
  to: string;
  at: string;
  reason?: string;
}

export interface Timeline {
  run_id: string;
  transitions: Transition[];
  decisions: DecisionView[];
}

export interface RepositoryOption {
  path: string;
  name: string;
  root: string;
  finding_inputs: string[];
  probes?: string;
}

// ------------------------------------------------------------------ events

export type ActivityStatus = 'STARTED' | 'PROGRESS' | 'COMPLETED' | 'WAITING' | 'FAILED' | 'SKIPPED' | 'INFO';
export type EventCategory = 'KERNEL' | 'MIGRATION' | 'SECURITY' | 'HUMAN' | 'MUTATION' | 'VALIDATION' | 'ERROR';

export interface HumanActionRef {
  gate: string;
  decision_type?: string;
  reason?: string;
  options: string[];
}

export interface ExecutionEvent {
  event_id: string;
  run_id: string;
  sequence: number;
  timestamp: string;
  type: string;
  category: EventCategory;
  phase?: string;
  component?: string;
  activity?: string;
  status: ActivityStatus;
  title?: string;
  message?: string;
  progress?: Progress;
  subjects: SubjectRef[];
  evidence_refs: string[];
  artifact_refs: string[];
  human_action?: HumanActionRef;
  attributes: Record<string, string>;
}

export interface WorkerStatus {
  advancing: boolean;
  kind?: string;
  started_at?: string;
  triggered_by?: string;
  description?: string;
  last_finished_at?: string;
  last_failed?: boolean;
  last_error?: string;
}

// ------------------------------------------------------------------ decisions and human actions

export interface DecisionView {
  decision_id: string;
  type: string;
  selected: string;
  recommendation?: string;
  proposal_id?: string;
  proposal_hash?: string;
  plan_id?: string;
  plan_hash?: string;
  assessment_hash?: string;
  baseline_seal?: string;
  ledger_head?: string;
  finding_ids: string[];
  actor: string;
  role: string;
  actor_authentication: string;
  authentication_note: string;
  rationale: string;
  timestamp: string;
  policy_version?: string;
  integrity_hash?: string;
  integrity: 'VERIFIED' | 'TAMPERED';
  superseded: boolean;
}

export interface DecisionRecorded {
  decision: DecisionView;
  advancing: boolean;
  next: string;
}

export interface HumanOption {
  value: string;
  label: string;
  consequence: string;
  recommended: boolean;
}

export interface HumanLink {
  kind: 'ARTIFACT' | 'VIEW' | string;
  id: string;
  label: string;
}

export interface HumanAction {
  id: string;
  gate: 'GATE_A' | 'GATE_A2' | 'GATE_B' | 'MIGRATION_PLAN' | 'NEEDS_HUMAN';
  decision_type?: string;
  title: string;
  why_stopped: string;
  what_to_decide: string;
  options: HumanOption[];
  recommendation?: HumanOption;
  recommendation_rationale?: string;
  inspect: HumanLink[];
  bound_to?: string;
  bound_hash?: string;
  requires_rationale: boolean;
  can_decide: boolean;
  cannot_decide_reason?: string;
  resumable: boolean;
  resume_note?: string;
  proposals: ProposalRow[];
  context: Record<string, unknown>;
  manual_steps: string[];
}

export interface HumanActionsView {
  run_id: string;
  phase: string;
  waiting: boolean;
  actions: HumanAction[];
}

// ------------------------------------------------------------------ security

export interface LocationView {
  path: string;
  line_start: number;
  line_end: number;
}

export interface FindingRow {
  finding_id: string;
  source_finding_id: string;
  source: string;
  severity: string;
  cwe: string[];
  title: string;
  location?: LocationView;
  anchor_quality?: string;
  route?: string;
  plan_id?: string;
  proposal_id?: string;
  proposal_status?: string;
  decision?: string;
  verification?: string;
  item_status?: string;
  blocked_by_platform: boolean;
  blast_radius_scope?: string;
}

export interface SecurityView {
  summary: SecuritySummary;
  findings: FindingRow[];
  gaps: string[];
  sources: string[];
  discovered: boolean;
}

export interface JourneyStep {
  id: string;
  label: string;
  status: 'DONE' | 'CURRENT' | 'WAITING' | 'PENDING' | 'FAILED' | 'SKIPPED' | 'NOT_APPLICABLE';
  detail?: string;
}

export interface FindingDetail {
  finding_id: string;
  source_finding_id: string;
  source: string;
  rule_id?: string;
  cwe: string[];
  cve: string[];
  severity: string;
  status: string;
  title: string;
  description?: string;
  location?: LocationView;
  code_flow: LocationView[];
  file_id?: string;
  file_path?: string;
  program_unit_id?: string;
  symbol_id?: string;
  statement_id?: string;
  anchor_quality?: string;
  fingerprint?: string;
  platform_requirement?: {
    component?: string;
    current_version?: string;
    minimum_fixed_version?: string;
    requires_platform?: string;
    requires_platform_minimum?: string;
    requires_java_minimum?: string;
    basis?: string;
    evidence_refs: string[];
  };
  evidence_refs: string[];
  root_cause?: {
    ref: string;
    statement?: string;
    location?: string;
    severity?: string;
    confidence?: string;
    entry_points: string[];
    data_flow: string[];
    how_to_fix?: string;
    evidence_refs: string[];
  };
  blast_radius?: {
    ref: string;
    priority?: string;
    scope?: string;
    affected_endpoints: string[];
    affected_services: string[];
    affected_symbol_ids: string[];
    confidence?: string;
    evidence_refs: string[];
  };
  plan?: {
    plan_id: string;
    issue_id?: string;
    route: string;
    cwe?: string;
    catalog_title?: string;
    owasp?: string;
    confidence?: string;
    plain_summary?: string;
    approach?: string;
    anti_patterns: string[];
    alternatives: string[];
    risk_notes: string[];
    verification_plan: string[];
    open_questions: string[];
    research_status?: string;
    catalog_status?: string;
    kb_status?: string;
    blocked_by_platform: boolean;
    proposal_id?: string;
    strategy_only: boolean;
    evidence_refs: string[];
  };
  proposal?: ProposalRow;
  decisions: DecisionView[];
  verification?: VerificationView;
  item_status?: string;
  item_reason?: string;
  journey: JourneyStep[];
  source_context?: {
    path: string;
    first_line: number;
    lines: string[];
    highlight_start: number;
    highlight_end: number;
    origin: string;
  };
  resolution?: Record<string, string>;
}

export interface VerificationView {
  plan_id: string;
  fix_status?: string;
  rescan?: string;
  rescan_reason?: string;
  redteam?: string;
  attempted_vectors: string[];
  behavior?: string;
  out_of_scope_changes: string[];
  qa?: string;
  build?: string;
  score: number;
  threshold: number;
  gates_triggered: string[];
  decision: string;
  evidence_refs: string[];
}

// ------------------------------------------------------------------ changes

export interface ProposalRow {
  proposal_id: string;
  capability: string;
  provider: string;
  provider_type: string;
  status?: string;
  strategy_only: boolean;
  finding_ids: string[];
  finding_labels: string[];
  files: string[];
  operations: string[];
  rule_id?: string;
  reason?: string;
  risk?: string;
  generated_at?: string;
  proposal_hash: string;
  decision?: string;
  decision_id?: string;
  awaiting_decision: boolean;
  mutation?: string;
  validation?: string;
}

export interface FileEditView {
  file_id?: string;
  path: string;
  new_path?: string;
  operation: string;
  unified_diff?: string;
  new_content?: string;
  new_content_truncated: boolean;
  added_lines: number;
  removed_lines: number;
  redacted: boolean;
}

export interface LineageView {
  sequence: number;
  kind: string;
  change_id?: string;
  decision_id?: string;
  actor?: string;
  file_id?: string;
  path_before?: string;
  path_after?: string;
  hash_before?: string;
  hash_after?: string;
  symbol_ids: string[];
  statement_ids_changed: string[];
  statement_ids_created: string[];
  statement_ids_deleted: string[];
  validation_refs: string[];
  status?: string;
  at?: string;
  reason?: string;
}

export interface MutationView {
  status?: string;
  batch?: string;
  authorized_by?: string;
  checks: string[];
  pre_checkpoint?: string;
  checkpoint_id?: string;
  change_ids: string[];
  identity_sync?: string;
  bypass_files?: number;
  reason?: string;
  reason_code?: string;
  at?: string;
  lineage: LineageView[];
}

export interface ProposalDetail {
  proposal_id: string;
  run_id: string;
  capability: string;
  provider: string;
  provider_type: string;
  provider_version?: string;
  status?: string;
  strategy_only: boolean;
  reason?: string;
  expected_outcome?: string;
  risk?: string;
  generated_at?: string;
  proposal_hash: string;
  hash_verified: boolean;
  baseline_seal?: string;
  baseline_matches: boolean;
  supersedes?: string;
  finding_ids: string[];
  finding_labels: string[];
  migration_refs: string[];
  evidence_refs: string[];
  knowledge_refs: string[];
  affected_file_ids: string[];
  affected_symbol_ids: string[];
  affected_statement_ids: string[];
  base_hashes: Record<string, string>;
  edits: FileEditView[];
  provenance?: {
    producer?: string;
    rule_id?: string;
    reference_section?: string;
    model?: string;
    model_version?: string;
    prompt_hash?: string;
    context_hash?: string;
    response_hash?: string;
    verification: string[];
  };
  decisions: DecisionView[];
  mutation?: MutationView;
  validation?: string;
  awaiting_decision: boolean;
  decidable: boolean;
  decidable_reason?: string;
  verdict_options: string[];
}

export interface ChangesView {
  proposals: ProposalRow[];
  summary: ChangesSummary;
}

// ------------------------------------------------------------------ migration

export interface DimensionView {
  dimension: string;
  status: string;
  detail?: string;
  summary?: string;
  evidence_refs: string[];
  producer?: string;
  mandatory: boolean;
}

export interface AssessmentView {
  assessment_id: string;
  generated_at?: string;
  traffic_light?: string;
  traffic_light_rationale?: string;
  need?: string;
  priority?: string;
  complexity?: string;
  effort_score?: number;
  effort_weights_version?: string;
  effort_factors: {
    name: string;
    raw_value: number;
    saturation: number;
    weight: number;
    contribution: number;
    known: boolean;
    note?: string;
  }[];
  evidence_confidence?: string;
  confidence_basis: string[];
  current?: {
    framework?: string;
    framework_version?: string;
    framework_line?: string;
    java_version?: string;
    build_system?: string;
    build_model_authoritative: boolean;
    lifecycle_quality?: string;
    support_ends?: string;
    end_of_life?: boolean;
    support_horizon_months?: number;
  };
  recommended_target?: { framework?: string; version?: string; line?: string; java_version?: string; basis?: string };
  recommended_sequence?: string;
  objectives: { objective_id: string; description?: string; requires_migration: boolean; required_platform?: string; why?: string }[];
  issues: { issue_id: string; rule_id?: string; reference_section?: string; mandatory: boolean; category?: string; subject?: string; location?: string }[];
  blockers: { id: string; description: string; kind?: string; evidence_refs: string[] }[];
  unknowns: { id: string; description: string; kind?: string; evidence_refs: string[] }[];
  reference_pack_id?: string;
  reference_pack_sha256?: string;
  evidence_refs: string[];
}

export interface RoundView {
  round: number;
  label?: string;
  intent?: string;
  outcome?: string;
  exit_code: number;
  duration_ms: number;
  jdk?: string;
  errors_by_category: Record<string, number>;
  error_count: number;
  errors: { file?: string; line?: number; category?: string; message?: string }[];
  errors_truncated: boolean;
  tests?: { run?: number; failures?: number; errors?: number; skipped?: number };
  applied_rules: string[];
  proposal_ids: string[];
  change_ids: string[];
  diagnosis?: string;
  log_ref?: string;
  evidence_refs: string[];
}

export interface MigrationView {
  status: string;
  status_detail?: string;
  selected: boolean;
  assessment?: AssessmentView;
  refreshed_assessment?: AssessmentView;
  plan?: {
    plan_id: string;
    plan_hash?: string;
    engine?: string;
    reference_pack_id?: string;
    reference_pack_sha256?: string;
    framework?: string;
    from_version?: string;
    to_version?: string;
    from_java?: string;
    to_java?: string;
    build_file_rules: { rule_id: string; section?: string; phase?: string; description?: string; symptoms: string[] }[];
    symptom_rules: { rule_id: string; section?: string; phase?: string; description?: string; symptoms: string[] }[];
    allowed_rule_ids: string[];
    max_rounds: number;
    stop_conditions: string[];
    generated_at?: string;
    evidence_refs: string[];
  };
  execution?: {
    plan_id: string;
    status: string;
    rounds: RoundView[];
    proposal_ids: string[];
    change_ids: string[];
    unmatched_errors: string[];
    needs_human_reason?: string;
    behaviour?: {
      overall?: string;
      verdict?: string;
      probes: { name: string; verdict?: string; before_status?: number; after_status?: number; note?: string }[];
      baseline_started: boolean;
      final_started: boolean;
      explanations: string[];
    };
    tests?: {
      verdict?: string;
      before?: { run?: number; failures?: number; errors?: number; skipped?: number };
      after?: { run?: number; failures?: number; errors?: number; skipped?: number };
      new_failures: string[];
      pre_existing_failures: string[];
    };
    report_ref?: string;
  };
  validation: DimensionView[];
  post_security?: {
    previous_traffic_light?: string;
    current_traffic_light?: string;
    previous_effort_score?: number;
    current_effort_score?: number;
    what_changed: string[];
    why?: string;
    current_assessment_hash?: string;
  };
  item_status?: string;
  item_reason?: string;
  blocker?: string;
  proposals: ProposalRow[];
}

// ------------------------------------------------------------------ validation and verdict

export interface ValidationView {
  status: string;
  validation_id?: string;
  scope?: string;
  generated_at?: string;
  final_validation: DimensionView[];
  not_run_dimensions: string[];
  migration_validation: DimensionView[];
  security_verification: VerificationView[];
  proposal_validation: { proposal_id: string; status: string; capability: string; finding_ids: string[] }[];
  baseline?: {
    seal?: string;
    sealed_at?: string;
    build_outcome?: string;
    tests?: unknown;
    pre_existing_failures: string[];
    runtime_started?: boolean;
  };
}

export interface VerdictView {
  available: boolean;
  outcome?: string;
  generated_at?: string;
  reasons: string[];
  hard_failures: string[];
  unknown_dimensions: string[];
  pending_decisions: string[];
  items: {
    item_id: string;
    label: string;
    kind: string;
    status: string;
    legacy_verdict?: string;
    reason?: string;
    evidence_refs: string[];
  }[];
  items_by_status: Record<string, number>;
  proposals_by_status: Record<string, number>;
  reports: string[];
  policy_version?: string;
  evidence_refs: string[];
}

// ------------------------------------------------------------------ evidence, graph, logs

export interface EvidenceView {
  evidence_id: string;
  chain_index: number;
  kind: string;
  summary?: string;
  observed?: string;
  where?: string;
  subjects: string[];
  basis?: string;
  reliability?: string;
  observed_at?: string;
  as_of?: string;
  artifact_ref?: string;
  artifact_sha256?: string;
  producer?: string;
  phase_group?: string;
}

export interface EvidencePage {
  evidence: EvidenceView[];
  total: number;
  offset: number;
  limit: number;
  chain_intact: boolean;
  chain_violations: string[];
  by_kind: Record<string, number>;
  by_producer: Record<string, number>;
}

export interface GraphNode {
  id: string;
  type: string;
  name?: string;
  origin: 'BASE' | 'OVERLAY';
  identity_id?: string;
  file_id?: string;
  fqn?: string;
  properties: Record<string, unknown>;
  highlights: string[];
  degree: number;
}

export interface GraphEdge {
  from: string;
  to: string;
  type: string;
  origin: 'BASE' | 'OVERLAY';
}

export interface GraphView {
  graph_source?: string;
  total_nodes: number;
  total_edges: number;
  node_types: Record<string, number>;
  edge_types: Record<string, number>;
  nodes: GraphNode[];
  edges: GraphEdge[];
  truncated: boolean;
  limit: number;
  focus?: string;
  depth: number;
  coverage_notes: string[];
  available: boolean;
}

export interface ArtifactEntry {
  path: string;
  size: number;
  modified: string;
  kind: string;
}

export interface LogFile {
  path: string;
  size: number;
  modified: string;
  origin: string;
}

export interface LogChunk {
  path: string;
  from_line: number;
  total_lines: number;
  lines: { number: number; level?: string; text: string }[];
  truncated: boolean;
}
