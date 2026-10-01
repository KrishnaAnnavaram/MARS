/**
 * Evidence integrity rules (proposal §35). Each rule compares two representations of the same fact
 * that MARS already writes, and reports a finding when they disagree. Mission Control never "fixes"
 * evidence; it surfaces the contradiction and keeps the authoritative value per the source-of-truth matrix.
 */
import type { IntegrityFinding, LedgerEvent, StageId } from '../../shared/types.js';
import type { Evidence } from '../sources/evidence.js';
import type { RegisterIssue } from '../lib/harness.js';
import type { DecisionRecord } from '../sources/decisions.js';
import type { CodeModel } from '../sources/codemodel.js';
import { computeScore, type Scoring } from './scoring.js';
import { extractField } from '../lib/md.js';

export const RULES: Record<string, string> = {
  R1: 'Report header contradicts its own result table',
  R2: 'Report contradicts its stage index',
  R3: 'Recorded verdict is not reproducible from current evidence',
  R4: 'Approval carried over a re-proposed plan',
  R5: 'Fix report claims files the diff does not change',
  R6: 'Non-renderer content inside a rendered report',
  R7: 'Evidence produced in another checkout or by an older harness',
  R8: 'Downstream evidence older than its upstream',
  R9: 'Downstream evidence without its required inputs',
  R10: 'Evidence and register disagree on which issues exist',
  R11: 'Recorded knowledge-graph counts disagree with the code model',
  R12: 'Plan decision has no attributable record',
  R13: 'Decision record failed integrity verification',
  R14: 'Decision record is not corroborated as a human decision',
};

/**
 * R14 — detection for forged "human" decisions. Attribution is locally asserted, so a same-user
 * process could write a well-formed, hash-chained record. Each record must therefore be corroborated:
 * a human presence mode (TTY confirmation or Mission Control launch token), a matching ledger witness
 * (record-decision always writes one), and no agent shell activity around the moment it was written.
 */
export function corroborateDecisions(decisions: DecisionRecord[], ledger: LedgerEvent[]): IntegrityFinding[] {
  const out: IntegrityFinding[] = [];
  const AGENT_SHELL = new Set(['runtime', 'network', 'filesystem', 'other', 'package']);
  for (const d of decisions) {
    const problems: string[] = [];
    const presence = (d as DecisionRecord & { presence?: string }).presence;
    if (presence !== 'tty-confirmation' && presence !== 'launch-token') problems.push(`human presence was not established (presence: ${presence || 'not recorded'})`);
    const witness = ledger.find((e) => e.type === 'approval.recorded' && (e.attrs as { decision_id?: string } | undefined)?.decision_id === d.decision_id);
    if (!witness) problems.push('no matching approval.recorded witness in the event ledger');
    const t = Date.parse(d.timestamp);
    const near = ledger.filter((e) => e.source?.emitter === 'hook' && /^(operation\.|artifact\.written)/.test(e.type) && Math.abs(Date.parse(e.time) - t) <= 10_000
      && (AGENT_SHELL.has(String((e.attrs as { command_class?: string } | undefined)?.command_class || '')) || e.type === 'artifact.written'));
    if (near.length) problems.push(`${near.length} agent shell/file operation(s) within 10 s of the record (${Array.from(new Set(near.map((e) => e.agent_id || 'session'))).join(', ')})`);
    if (!problems.length) continue;
    const severe = problems.length > 1 || !witness || (presence !== 'tty-confirmation' && presence !== 'launch-token');
    out.push(f('R14', severe ? 'critical' : 'major', `Decision ${d.decision_id} (${d.decision} by ${d.actor}) is not corroborated as a human decision`,
      `${problems.join('; ')}. Attribution is LOCALLY_ASSERTED: confirm with ${d.actor} that they made this decision before relying on it.`, d.issue_id, 'approval', [d.file]));
  }
  return out;
}

function f(rule: string, severity: IntegrityFinding['severity'], title: string, detail: string, issueId: string | null, stage: StageId | null, subjects: string[]): IntegrityFinding {
  return { id: `${rule}:${issueId || 'workspace'}:${stage || ''}:${subjects[0] || ''}`, rule, ruleTitle: RULES[rule], severity, title, detail, issueId, stage, subjects };
}

export function runIntegrity(ev: Evidence, register: RegisterIssue[], decisions: DecisionRecord[], scoring: Scoring | null, cm: CodeModel): IntegrityFinding[] {
  const out: IntegrityFinding[] = [];
  const severityOf = new Map(register.map((r) => [r.id, r.severity || null]));

  // R1 / R6 — gate reports
  for (const [id, gates] of ev.gate) {
    for (const kind of ['qa', 'build'] as const) {
      const g = gates[kind];
      if (!g) continue;
      if (g.headerStatus && g.bodyStatus && g.headerStatus !== g.bodyStatus) {
        out.push(f('R1', 'critical', `${kind.toUpperCase()} report header says "${g.headerStatus}" but its own result table records ${g.bodyStatus === 'Failed' ? 'a non-zero exit code' : 'success'}`,
          `The renderer derives the header from the same result it tabulates, so the two cannot legitimately differ. The merge arbiter reads the header; the exit codes say ${g.bodyStatus}.`, id, kind, [g.file.path]));
      }
      if (g.insertedProse.length) {
        out.push(f('R6', kind === 'build' ? 'major' : 'minor', `${kind.toUpperCase()} report contains explanatory prose the renderer never writes`,
          `Found "${g.insertedProse.join('", "')}". ${kind === 'build' ? 'The build report is declared to contain no agent-authored content.' : 'QA reports carry agent text only in the test-plan sections.'} The claim it makes is shown as unverified.`, id, kind, [g.file.path]));
      }
    }
  }
  for (const [id, checks] of ev.verify) {
    for (const v of Object.values(checks)) {
      if (v && v.manualNote) out.push(f('R6', 'minor', `${v.check} report declares sections were reconstructed by hand after rendering`, v.manualNote, id, v.check, [v.file.path]));
    }
  }

  // R2 — stage indexes
  const idxRows = (key: keyof Evidence['indexes']) => new Map((ev.indexes[key]?.rows || []).map((r) => [r.ID, r]));
  const testIdx = idxRows('testgate');
  for (const [id, gates] of ev.gate) {
    const row = testIdx.get(id);
    if (!row) continue;
    for (const [kind, colName] of [['qa', 'QA Status'], ['build', 'Build Status']] as const) {
      const g = gates[kind];
      if (g && row[colName] && g.headerStatus && row[colName] !== g.headerStatus) {
        out.push(f('R2', 'major', `${kind.toUpperCase()} report says "${g.headerStatus}"; the 06-test-gate index says "${row[colName]}"`, 'The index is regenerated by the renderers; a mismatch means one of them was edited or is stale.', id, kind, [g.file.path, ev.indexes.testgate!.file.path]));
      }
    }
  }
  const verifyIdx = idxRows('verify');
  for (const [id, checks] of ev.verify) {
    const row = verifyIdx.get(id);
    if (!row) continue;
    for (const [check, colName] of [['rescan', 'Re-scan'], ['redteam', 'Red-team'], ['behavior', 'Behavior']] as const) {
      const v = checks[check];
      if (v && row[colName] && v.verdict && row[colName] !== v.verdict) out.push(f('R2', 'major', `${check} report says ${v.verdict}; the 05-verify index says ${row[colName]}`, '', id, check, [v.file.path]));
    }
  }
  const remIdx = idxRows('remediation');
  for (const [id, plan] of ev.plan) {
    const row = remIdx.get(id);
    if (row && row['Plan Status'] && plan.status && row['Plan Status'] !== plan.status) out.push(f('R2', 'minor', `Plan says "${plan.status}"; the 04-remediation index says "${row['Plan Status']}"`, 'The index was rendered before the Status cell was last edited, or the plan was edited after.', id, 'approval', [plan.file.path]));
  }
  const shipIdx = idxRows('ship');
  for (const [id, v] of ev.verdict) {
    const row = shipIdx.get(id);
    if (row && row.Decision && v.decision && row.Decision !== v.decision) out.push(f('R2', 'major', `Verdict says ${v.decision}; the 07-ship index says ${row.Decision}`, '', id, 'verdict', [v.file.path]));
  }

  // R3 — verdict replay (arbiter's exact rule: Verdict || Status header of each upstream report)
  if (scoring) {
    for (const [id, v] of ev.verdict) {
      const checks = ev.verify.get(id) || {};
      const gates = ev.gate.get(id) || {};
      const header = (t: string | null | undefined) => (t ? (extractField(t, 'Verdict') || extractField(t, 'Status')) : null);
      const replay = computeScore({
        rescan: header(checks.rescan?.file.text), redteam: header(checks.redteam?.file.text), behavior: header(checks.behavior?.file.text),
        qa: header(gates.qa?.file.text), build: header(gates.build?.file.text),
      }, severityOf.get(id) || null, scoring);
      if (v.decision && replay.decision !== v.decision) {
        out.push(f('R3', 'critical', `Recorded verdict is ${v.decision}; re-scoring the reports as they read now gives ${replay.decision} (${replay.score}/${replay.threshold}${replay.gates.length ? `, gates: ${replay.gates.join(', ')}` : ', no hard gate'})`,
          'compute-score.js reads each upstream report\'s header. If the arbiter re-ran today it would reach a different decision from the same files — the verdict is no longer reproducible from the evidence. Re-run the gates rather than trusting either value.', id, 'verdict', [v.file.path]));
      } else if (v.score != null && replay.score !== v.score) {
        out.push(f('R3', 'major', `Recorded score ${v.score} differs from re-scored ${replay.score} (decision unchanged)`, 'An upstream report changed after scoring.', id, 'verdict', [v.file.path]));
      }
    }
  }

  // R4 — approval carried across a re-proposal
  for (const [id, plan] of ev.plan) {
    if (plan.reproposed && /Approved|Rejected/.test(plan.status || '')) {
      out.push(f('R4', 'major', `Plan was re-proposed after the decision; Status "${plan.status}" was carried over`, `${plan.reproposedNote || ''} The human decision applies to an earlier version of this plan; the version on disk now has not been decided.`, id, 'approval', [plan.file.path]));
    }
  }

  // R5 — fix claims vs diff
  for (const [id, fix] of ev.fix) {
    const diff = ev.diff.get(id);
    if (!diff || !fix.claimed.length) continue;
    const inDiff = new Set(diff.files.map((x) => x.path));
    const claimedChanges = fix.claimed.filter((c) => c.file && !/^no (behavioural )?change/i.test(c.change));
    const missing = claimedChanges.filter((c) => !inDiff.has(c.file));
    if (missing.length) {
      out.push(f('R5', 'major', `Fix report lists ${claimedChanges.length} changed file(s); the diff changes ${diff.files.length}`, `Not in the diff: ${missing.map((m) => m.file.split('/').pop()).join(', ')}. ${missing.map((m) => `"${m.change}"`).join(' ')}`, id, 'fix', [fix.file.path, diff.file.path]));
    }
  }

  // R7 — foreign provenance
  for (const file of ev.files) {
    if (!file.text) continue;
    const foreign = /\/?[A-Za-z]:\/[^`)\n|]{0,200}?\.(?:claude|github)\/\.architect\//.exec(file.text) || /\.(?:claude|github)\/\.architect\//.exec(file.text);
    if (foreign) {
      out.push(f('R7', 'info', 'Evidence was produced in a different checkout by an older harness layout', `Paths such as "${foreign[0].slice(0, 90)}…" point outside this workspace and to the former ".architect" data directory.`, file.issueId, file.stage, [file.path]));
    }
  }

  // R8 / R9 — ordering and completeness
  for (const [id, v] of ev.verdict) {
    const gates = ev.gate.get(id) || {};
    const checks = ev.verify.get(id) || {};
    const missing = (['rescan', 'redteam', 'behavior'] as const).filter((c) => !checks[c]).concat((['qa', 'build'] as const).filter((k) => !gates[k]) as never[]);
    if (missing.length) out.push(f('R9', 'critical', `Verdict exists but ${missing.join(', ')} report(s) are missing`, 'The arbiter only scores when all five reports exist; a missing input means the verdict cannot be reproduced.', id, 'verdict', [v.file.path]));
    const times = [checks.rescan?.collectedAt, checks.redteam?.collectedAt, checks.behavior?.collectedAt, gates.qa?.runAt, gates.build?.runAt].filter(Boolean) as string[];
    const latest = times.sort().pop();
    if (latest && v.computedAt && v.computedAt < latest) out.push(f('R8', 'major', 'Verdict was computed before one of its inputs was produced', `Score computed ${v.computedAt}; latest input ${latest}.`, id, 'verdict', [v.file.path]));
  }
  for (const [id, fix] of ev.fix) if (!ev.plan.has(id)) out.push(f('R9', 'major', 'Fix exists without a plan', '', id, 'fix', [fix.file.path]));

  // R10 — register vs evidence
  const regIds = new Set(register.map((r) => r.id));
  for (const id of ev.issueIds) if (!regIds.has(id) && !/^SAMPLE-/.test(id)) out.push(f('R10', 'major', `Evidence exists for ${id}, which is not in the issue register`, '', id, null, []));

  // R11 — graph freshness
  const rec = ev.architecture.recordedGraphCounts;
  if (rec && cm.available && rec.Type != null && rec.Type !== cm.counts.types) {
    out.push(f('R11', 'minor', `architecture.md records ${rec.Type} Type nodes in Neo4j; the code model has ${cm.counts.types} types`, 'Graph Forge only MERGEs and never prunes, so nodes from earlier loads accumulate. Treat graph-derived context as possibly stale.', null, 'architecture', [ev.architecture.doc?.path || '']));
  }

  // R12 / R13 — decisions
  for (const [id, plan] of ev.plan) {
    if (!/Approved|Rejected/.test(plan.status || '')) continue;
    const recs = decisions.filter((d) => d.issue_id === id);
    if (!recs.length) out.push(f('R12', 'info', `"${plan.status}" with no decision record`, 'The Status cell was edited by hand; MARS records no approver, time or reviewed plan version.', id, 'approval', [plan.file.path]));
  }
  for (const d of decisions) if (d.integrity !== 'verified') out.push(f('R13', 'critical', `Decision ${d.decision_id} ${d.integrity === 'tampered' ? 'does not match its own hash' : 'breaks the record chain'}`, '', d.issue_id, 'approval', [d.file]));

  return out;
}
