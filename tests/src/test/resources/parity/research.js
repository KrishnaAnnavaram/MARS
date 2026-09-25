/**
 * Parity driver: VRH 04d generate-strategy.js, run unchanged as a CLI.
 *
 *   node research.js <dataDir> --fixture <NAME>   bundled legacy fixtures (skill fixtures/ folder)
 *   node research.js <dataDir> --issue <ID>       <dataDir>/research/<ID>.{understanding,analysis}.json
 *
 * PIPELINE_CONTEXT_DATA_DIR points at <dataDir>, so everything the script writes lands there.
 * Prints the exit code, the inputs it read, and the strategy / promotion candidate it wrote.
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { vrh, emit, readJson } = require('./legacy');

const SKILL = vrh('04d-remediation-research');

function optional(file) {
  return fs.existsSync(file) ? readJson(file) : null;
}

function main() {
  const [dataDir, mode, name] = process.argv.slice(2);
  const run = spawnSync(process.execPath, [path.join(SKILL, 'scripts', 'generate-strategy.js'), mode, name], {
    encoding: 'utf8',
    env: { ...process.env, PIPELINE_CONTEXT_DATA_DIR: dataDir },
  });
  const inputs = mode === '--fixture' ? path.join(SKILL, 'fixtures') : path.join(dataDir, 'research');
  const understanding = readJson(path.join(inputs, `${name}.understanding.json`));
  const analysis = readJson(path.join(inputs, `${name}.analysis.json`));
  const id = understanding.issue_id;
  emit({
    exitCode: run.status,
    stderr: run.stderr,
    understanding,
    analysis,
    strategy: optional(path.join(dataDir, 'fix-strategy', `${id}.strategy.json`)),
    promotion: optional(path.join(dataDir, 'research', `${id}.promotion-candidate.json`)),
    result: optional(path.join(dataDir, 'research', `${id}.research-result.json`)),
  });
}

main();
