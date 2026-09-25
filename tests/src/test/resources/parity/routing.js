/**
 * Parity driver: VRH 04c detect-gap.js (the YES/NO switch), unchanged.
 *
 *   node routing.js <cases.json>   cases: [{ text }] -> per case: detected CWEs with catalog/KB
 *                                  membership, 04c classifyGap, detect-gap's own verdictFor, and
 *                                  04d research.js gapStatus (the four-state classification)
 *   node routing.js --cwe <CWE>    -> the stdout line of `detect-gap.js --cwe <CWE>`
 */
const { execFileSync } = require('child_process');
const { vrh, internals, emit, readJson } = require('./legacy');

const DETECT_GAP = vrh('04c-remediation-intelligence', 'scripts', 'detect-gap.js');
const { plans, loadKb, classifyGap } = require(vrh('04c-remediation-intelligence', 'scripts', 'lib', 'fallback.js'));
const { verdictFor } = internals(DETECT_GAP, ['verdictFor']);
const research = require(vrh('04d-remediation-research', 'scripts', 'lib', 'research.js'));

function main() {
  const args = process.argv.slice(2);
  if (args[0] === '--cwe') {
    const line = execFileSync(process.execPath, [DETECT_GAP, '--cwe', args[1]], { encoding: 'utf8' }).trim();
    emit({ line });
    return;
  }
  const catalog = plans.loadCatalog();
  const kb = loadKb();
  const cases = readJson(args[0]).map(({ text }) => {
    const candidates = plans.detectCweMentions(text).map((cwe) => ({ cwe, inCatalog: Boolean(catalog[cwe]) }));
    return {
      text,
      candidates: candidates.map((c) => ({ ...c, inKb: Boolean(kb[c.cwe]) })),
      gap: classifyGap(candidates),
      verdict: verdictFor(candidates),
      gapStatus: research.gapStatus(research.classifyCwes(text, catalog, kb)),
    };
  });
  emit({ catalog: Object.keys(catalog), kb: Object.keys(kb), cases });
}

main();
