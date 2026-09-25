/**
 * Parity driver: the migration reference renderer's compareProbes and testComparison
 * (render-migration-report.js, loaded unchanged through legacy.internals).
 *
 *   node probes.js <cases.json>
 *     { probeCases: [{ name, runtime: { baseline, final } }],   runtime records as probe-runtime.js writes them
 *       testCases:  [{ name, rounds: [round records] }] }       round records as run-migration-build.js writes them
 *   node probes.js --recorded <sessionDir>                       runtime/*.json and rounds/*.json of a recorded session
 */
const fs = require('fs');
const path = require('path');
const { migrationReference, internals, emit, readJson } = require('./legacy');

const { compareProbes, testComparison } = internals(
  migrationReference('04d-version-migration', 'scripts', 'render-migration-report.js'),
  ['compareProbes', 'testComparison'],
);

function recorded(dir) {
  const optional = (f) => (fs.existsSync(f) ? readJson(f) : null);
  const rounds = fs.readdirSync(path.join(dir, 'rounds')).filter((f) => /^round-\d+\.json$/.test(f)).sort()
    .map((f) => readJson(path.join(dir, 'rounds', f)));
  return {
    probeCases: [{
      name: 'recorded',
      runtime: { baseline: optional(path.join(dir, 'runtime', 'baseline.json')), final: optional(path.join(dir, 'runtime', 'final.json')) },
    }],
    testCases: [{ name: 'recorded', rounds }],
  };
}

function main() {
  const args = process.argv.slice(2);
  const input = args[0] === '--recorded' ? recorded(args[1]) : readJson(args[0]);
  emit({
    probes: input.probeCases.map(({ name, runtime }) => ({ name, runtime, result: compareProbes(runtime) })),
    tests: input.testCases.map(({ name, rounds }) => ({ name, rounds, result: testComparison(rounds) })),
  });
}

main();
