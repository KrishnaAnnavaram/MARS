/**
 * Shared helpers for the parity drivers. Nothing here changes legacy code: modules are loaded
 * from legacy-sources/ by path, exactly as they are on disk.
 */
const fs = require('fs');
const path = require('path');
const Module = require('module');

const ROOT = process.env.HARNESS_ROOT || path.resolve(__dirname, '..', '..', '..', '..', '..');

function legacyPath(...parts) {
  return path.join(ROOT, 'legacy-sources', ...parts);
}

function vrh(...parts) {
  return legacyPath('vulnerability-remediation-harness', '.github', 'skills', ...parts);
}

function migrationReference(...parts) {
  return legacyPath('spring-migration-reference', '.github', 'skills', ...parts);
}

/**
 * Loads a legacy CLI script and returns the top-level functions it does not export. The script's
 * source is compiled unchanged as its own module, with `--help` as its arguments so its `main()`
 * only prints usage (silenced here); a trailing statement then hands back the named functions.
 */
function internals(file, names) {
  const source = fs.readFileSync(file, 'utf8').replace(/^#!.*/, '');
  const mod = new Module(file, module);
  mod.filename = file;
  mod.paths = Module._nodeModulePaths(path.dirname(file));
  const argv = process.argv;
  const log = console.log;
  process.argv = [argv[0], file, '--help'];
  console.log = () => {};
  try {
    mod._compile(`${source}\n;module.exports.__parity = { ${names.join(', ')} };`, file);
  } finally {
    process.argv = argv;
    console.log = log;
  }
  return mod.exports.__parity;
}

function emit(value) {
  process.stdout.write(JSON.stringify(value));
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

module.exports = { ROOT, legacyPath, vrh, migrationReference, internals, emit, readJson };
