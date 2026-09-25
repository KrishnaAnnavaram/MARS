#!/usr/bin/env node
/**
 * Merge Arbiter — Deterministic Score Computation
 *
 * Reads all five Phase C upstream reports plus scoring.json and computes a 0-100 confidence
 * score, entirely mechanically. Two hard gates (re-scanner STILL_VULNERABLE, build-gatekeeper
 * Failed) block regardless of score — no weight rescues either. This script decides the score
 * and the gate outcome; the agent may only contest the result afterward, explicitly and visibly
 * (see .github/.pipeline-context/merge/<id>.arbitration.json), never by editing this file.
 *
 * Usage:
 *   node scripts/compute-score.js --all
 *   node scripts/compute-score.js --issue ISSUE-001
 */
const fs = require('fs');
const path = require('path');
const {
  WORK_DIR, rel, listStep3Fixes, readUpstream, readIssueSeverity, loadScoring, scorePathFor,
} = require('./lib/arbiter');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--issue' || a === '-i') args.issue = argv[++i];
    else if (a === '--all' || a === '-a') args.all = true;
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Merge Arbiter — Deterministic Score Computation

  node scripts/compute-score.js --all
  node scripts/compute-score.js --issue <ISSUE-ID>`);
}

function scoreOne(fix, scoring) {
  const upstream = readUpstream(fix.id);
  const missing = Object.entries(upstream).filter(([, v]) => !v.present).map(([k]) => k);
  if (missing.length) {
    throw new Error(`${fix.id} is missing upstream report(s): ${missing.join(', ')}. Run the remaining Phase C checks first.`);
  }

  const gates = [];
  if (scoring.hard_gates.rescan_still_vulnerable_blocks && upstream.rescan.verdict === 'STILL_VULNERABLE') {
    gates.push({ gate: 're-scanner', reason: 'Re-scan verdict is STILL_VULNERABLE — the original finding still triggers.' });
  }
  if (scoring.hard_gates.build_failed_blocks && upstream.build.verdict === 'Failed') {
    gates.push({ gate: 'build-gatekeeper', reason: 'Build gate Status is Failed — the patch does not build cleanly.' });
  }

  const redteamPoints = scoring.weights.redteam[upstream.redteam.verdict] ?? 0;
  const behaviorPoints = scoring.weights.behavior[upstream.behavior.verdict] ?? 0;
  const qaPoints = scoring.weights.qa[upstream.qa.verdict] ?? 0;
  const totalScore = redteamPoints + behaviorPoints + qaPoints;

  const severity = readIssueSeverity(fix.id);
  const threshold = (severity && scoring.severity_thresholds[severity]) || scoring.default_threshold;

  const gateBlocked = gates.length > 0;
  const scoreCleared = totalScore >= threshold;

  return {
    generatedAt: new Date().toISOString(),
    id: fix.id,
    title: fix.title,
    cwe: fix.cwe,
    severity,
    threshold,
    upstream,
    breakdown: {
      redteam: { verdict: upstream.redteam.verdict, points: redteamPoints, max: 30 },
      behavior: { verdict: upstream.behavior.verdict, points: behaviorPoints, max: 30 },
      qa: { verdict: upstream.qa.verdict, points: qaPoints, max: 40 },
    },
    score: totalScore,
    gates,
    gateBlocked,
    scoreCleared,
    computedDecision: gateBlocked ? 'Blocked' : (scoreCleared ? 'Cleared' : 'Blocked'),
  };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  const scoring = loadScoring();

  let targets;
  if (args.all) {
    targets = listStep3Fixes();
    if (!targets.length) { console.log('No Compiled or Compile Failed fixes found.'); return; }
  } else {
    if (!args.issue) throw new Error('Missing --issue or --all.');
    targets = listStep3Fixes().filter((f) => f.id.toLowerCase() === args.issue.toLowerCase());
    if (!targets.length) throw new Error(`${args.issue} has no drafted fix (Compiled or Compile Failed) to score.`);
  }

  const done = []; const failed = [];
  for (const fix of targets) {
    try {
      const record = scoreOne(fix, scoring);
      fs.mkdirSync(WORK_DIR, { recursive: true });
      fs.writeFileSync(path.join(WORK_DIR, `${fix.id}.score.json`), JSON.stringify(record, null, 2));
      done.push(record);
      console.log(`\n${record.id} — score ${record.score}/100 (threshold ${record.threshold}) -> ${record.computedDecision}`);
      if (record.gates.length) record.gates.forEach((g) => console.log(`  HARD GATE: ${g.gate} — ${g.reason}`));
      console.log(`  red-team: ${record.breakdown.redteam.verdict} (${record.breakdown.redteam.points}/30)  behavior: ${record.breakdown.behavior.verdict} (${record.breakdown.behavior.points}/30)  qa: ${record.breakdown.qa.verdict} (${record.breakdown.qa.points}/40)`);
    } catch (err) {
      if (targets.length === 1) throw err;
      failed.push({ id: fix.id, message: err.message });
      console.error(`\n${fix.id} — SKIPPED: ${err.message}`);
    }
  }
  console.log(`\nNext: read each .github/.pipeline-context/merge/<id>.score.json, then write <id>.arbitration.json.`);
  if (failed.length) process.exitCode = 1;
}

try { main(); } catch (err) { console.error('Score computation failed:', err.message); process.exit(1); }
