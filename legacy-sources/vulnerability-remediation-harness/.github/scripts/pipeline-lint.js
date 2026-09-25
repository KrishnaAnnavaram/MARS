#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const repoRoot = path.resolve(__dirname, '..', '..');
const failures = [];

function read(relativePath) {
  return fs.readFileSync(path.join(repoRoot, relativePath), 'utf8');
}

function requireText(relativePath, text) {
  if (!read(relativePath).includes(text)) failures.push(`${relativePath}: missing ${JSON.stringify(text)}`);
}

function forbidText(relativePath, text) {
  if (read(relativePath).includes(text)) failures.push(`${relativePath}: contains stale ${JSON.stringify(text)}`);
}

const requiredDirectories = [
  'docs/agent_output/01-architecture',
  'docs/agent_output/02-root-cause',
  'docs/agent_output/03-blast-radius',
  'docs/agent_output/04-remediation',
  'docs/agent_output/05-verify',
  'docs/agent_output/06-test-gate',
  'docs/agent_output/07-ship',
];

for (const directory of requiredDirectories) {
  if (!fs.existsSync(path.join(repoRoot, directory))) failures.push(`missing output directory ${directory}`);
}

for (const name of [
  '01_architect',
  '02_root-cause-analyst',
  '03_blast-radius-analyst',
  '04_fix-generator',
  '05_existing-app-test-agent',
  '06_additional-test-execution',
  '07_audit-and-pr',
]) {
  requireText(`.github/agents/${name}.agent.md`, 'agents: []');
}

forbidText('.github/agents/01_architect.agent.md', ' web,');
requireText('.github/agents/01_architect.agent.md', 'npm run all');
requireText('.github/agents/06_additional-test-execution.agent.md', '`Compiled` or `Compile Failed`');
requireText('.github/agents/07_audit-and-pr.agent.md', 'the rendered verdict\'s Decision is exactly `Cleared`');
requireText('.github/skills/06a-qa-runner/scripts/lib/qa.js', "const STEP2_ELIGIBLE_STATUSES = ['Compiled', 'Compile Failed'];");
requireText('.github/skills/06b-build-gatekeeper/scripts/lib/gate.js', "const STEP2_ELIGIBLE_STATUSES = ['Compiled', 'Compile Failed'];");
requireText('.github/skills/07a-merge-arbiter/scripts/render-verdict.js', "a.override.decision === 'Blocked'");
requireText('.github/skills/07a-merge-arbiter/scripts/render-verdict.js', "score.computedDecision !== 'Cleared'");
forbidText('docs/agent_output/05-verify/README.md', '../fixes/');
forbidText('docs/agent_output/05-verify/README.md', 'verification-layer');

try {
  JSON.parse(read('.github/skills/07a-merge-arbiter/scoring.json'));
  JSON.parse(read('.github/skills/07a-merge-arbiter/templates/arbitration.schema.json'));
} catch (error) {
  failures.push(`invalid merge-arbiter JSON: ${error.message}`);
}

if (failures.length) {
  console.error('Pipeline contract lint failed:');
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log('Pipeline contract lint passed.');