/**
 * Parity driver: VRH 04c run-fallback.js (the NO branch), run unchanged as a CLI with --dry-run.
 *
 *   node kb-fallback.js <dataDir> <issueId> [<cwe>]
 *
 * The context bundle must already be at <dataDir>/fix-strategy/<issueId>.context.json, the
 * location run-fallback.js reads when PIPELINE_CONTEXT_DATA_DIR points at <dataDir>. Prints the
 * exit code, whether the optional local embedding model is set up, and the dry-run strategy JSON.
 */
const { spawnSync } = require('child_process');
const { vrh, emit } = require('./legacy');

const SCRIPT = vrh('04c-remediation-intelligence', 'scripts', 'run-fallback.js');
const { pythonAvailable } = require(vrh('04c-remediation-intelligence', 'scripts', 'lib', 'embeddings.js'));
const MARKER = '--dry-run: strategy NOT written. JSON below:';

function main() {
  const [dataDir, issue, cwe] = process.argv.slice(2);
  const args = [SCRIPT, '--issue', issue, '--dry-run', ...(cwe ? ['--cwe', cwe] : [])];
  const run = spawnSync(process.execPath, args, {
    encoding: 'utf8',
    env: { ...process.env, PIPELINE_CONTEXT_DATA_DIR: dataDir },
  });
  const at = run.stdout.indexOf(MARKER);
  emit({
    exitCode: run.status,
    stderr: run.stderr,
    embeddingAvailable: pythonAvailable(),
    strategy: at < 0 ? null : JSON.parse(run.stdout.slice(at + MARKER.length)),
  });
}

main();
