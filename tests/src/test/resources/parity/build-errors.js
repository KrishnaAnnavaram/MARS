/**
 * Parity driver: the migration reference's build-output handling, unchanged —
 * lib/migration.js parseBuildErrors / buildOutcome / summariseErrors, and the renderer's
 * testTotals (render-migration-report.js, loaded through legacy.internals).
 *
 *   node build-errors.js cases <cases.json>   cases: [{ name, log, root, status }]
 *   node build-errors.js rounds <roundsDir>   every recorded round-NN.json, on its log_tail
 */
const fs = require('fs');
const path = require('path');
const { migrationReference, internals, emit, readJson } = require('./legacy');

const SCRIPTS = migrationReference('04d-version-migration', 'scripts');
const migration = require(path.join(SCRIPTS, 'lib', 'migration.js'));
const { testTotals } = internals(path.join(SCRIPTS, 'render-migration-report.js'), ['testTotals']);

function analyse({ name, log, root, status, intent, round, recorded }) {
  const errors = migration.parseBuildErrors(log, root || null);
  return {
    name,
    log,
    status,
    errors,
    outcome: migration.buildOutcome({ status }, errors),
    summary: migration.summariseErrors(errors),
    totals: testTotals({ round: round || 0, build: { intent: intent || 'test' }, log_tail: log }),
    recorded: recorded || null,
  };
}

function main() {
  const [mode, target] = process.argv.slice(2);
  let cases;
  if (mode === 'cases') {
    cases = readJson(target);
  } else if (mode === 'rounds') {
    cases = fs.readdirSync(target).filter((f) => /^round-\d+\.json$/.test(f)).sort().map((f) => {
      const r = readJson(path.join(target, f));
      return {
        name: f,
        log: r.log_tail,
        root: null,
        status: r.build.exit_code,
        intent: r.build.intent,
        round: r.round,
        recorded: { outcome: r.outcome, intent: r.build.intent, errorCount: r.error_summary.total },
      };
    });
  } else {
    throw new Error(`unknown mode ${mode}`);
  }
  emit(cases.map(analyse));
}

main();
