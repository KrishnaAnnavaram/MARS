/** Prints the projection for a workspace. Usage: npx tsx scripts/smoke.ts [workspace] */
import path from 'node:path';
import os from 'node:os';
import { Model } from '../server/model.js';

const root = path.resolve(process.argv[2] || '..');
const m = new Model({ root, watch: false, ledgerDir: path.join(os.tmpdir(), 'mc-smoke-empty-ledger') });
const t0 = Date.now();
const o = m.overview();
console.log('built in', Date.now() - t0, 'ms; issues', o.issues.length, 'summary', JSON.stringify(o.summary));
for (const i of o.issues) {
  const cells = Object.entries(i.cells).filter(([k]) => !['intake', 'architecture'].includes(k)).map(([k, c]) => `${k}:${c.state}${c.modifiers.length ? `[${c.modifiers.join(',')}]` : ''}`).join(' ');
  console.log(i.id, i.severity, i.cwe, '|', cells, '\n   blocker:', i.blocker?.summary, '\n   next:', i.nextAction?.text);
}
console.log('attention:');
for (const a of o.attention) console.log('  ', a.severity, a.title, '—', a.detail.slice(0, 110));
console.log('trust:', o.trust.map((t) => `${t.id}=${t.status}:${t.label}`).join(' | '));
const f = m.findings();
const byRule: Record<string, number> = {};
for (const x of f) byRule[x.rule] = (byRule[x.rule] || 0) + 1;
console.log('findings', f.length, JSON.stringify(byRule));
const d = m.issue('ISSUE-003');
if (d) {
  console.log('lanes:', d.lanes.map((l) => `${l.check}:${l.state}/${l.failureClass}/verified=${l.causeVerified} hdr=${l.headerStatus} body=${l.bodyStatus} errs=${l.compileErrors.length} inPatched=${l.compileErrors.filter((c) => c.inPatchedFile).length}`).join('\n       '));
  console.log('verdict replay', JSON.stringify(d.verdictDetail?.replay), '\n  body', JSON.stringify(d.verdictDetail?.bodyReplay));
  console.log('approval', d.approval.state, d.approval.stateLabel, JSON.stringify(d.approval.implementation));
  console.log('codeRefs', d.codeRefs.map((r) => `${r.role}:${r.label}:${r.resolved}`).join(' ; '));
  console.log('timeline', d.timeline.length, '\n   ' + d.timeline.slice(0, 8).map((t) => `${t.time}|${t.timeSource}|${t.actorKind}|${t.title}`).join('\n   '));
}
const reg = m.registry();
console.log('registry agents', reg.agents.length, 'skills', reg.skills.length, 'scripts', reg.scripts.length, '\n  drift:', reg.drift.map((x) => x.title).join('\n   '));
console.log('agent 04 skills', reg.agents.find((a) => a.id === '04_fix-generator')?.skills.join(','), 'outputs', JSON.stringify(reg.agents[0].outputs));
const runs = m.runs();
console.log('runs', runs.runs.length, runs.runs.slice(0, 6).map((r) => `${r.label} ${r.startedAt} ops=${r.operations}`).join(' ; '));
