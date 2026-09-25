#!/usr/bin/env node
/**
 * Context Weaver — Validator
 *
 * The guard between an authored description set and the graph. Checks three things,
 * in increasing order of what they protect against:
 *
 *   1. Shape      — does descriptions.json satisfy descriptions.schema.json?
 *   2. Reference  — does every `id` name a node that actually exists in artifacts.json?
 *                   This is the one that matters: an invented id would silently load a
 *                   phantom node into Neo4j and every downstream agent would inherit it.
 *   3. Freshness  — was each description written against the code that is there now?
 *
 * Nothing is loaded into the graph until this passes.
 *
 * Usage:
 *   node scripts/validate-context.js            # errors fail, staleness warns
 *   node scripts/validate-context.js --strict   # staleness fails too
 */
const fs = require('fs');
const {
  ARTIFACTS_FILE, DESCRIPTIONS_FILE, SCHEMA_FILE, WORKLOAD_JSON,
  rel, buildModel, methodIdOf, endpointOf,
  fingerprintModule, fingerprintPackage, fingerprintType, fingerprintMethod,
  fingerprintEndpoint, fingerprintExternalType,
} = require('./lib/context');

function parseArgs(argv) {
  return {
    strict: argv.includes('--strict'),
    help: argv.includes('--help') || argv.includes('-h'),
  };
}

// ---------------------------------------------------------------------------
// Minimal JSON Schema check — the draft-07 subset descriptions.schema.json uses.
// Hand-rolled on purpose: this skill stays dependency-free so it can be copied out.
// ---------------------------------------------------------------------------

function validateAgainstSchema(value, schema, pointer, errors) {
  if (schema.type === 'object') {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
      errors.push(`${pointer}: expected an object`);
      return;
    }
    for (const key of schema.required || []) {
      if (value[key] === undefined) errors.push(`${pointer}: missing required field "${key}"`);
    }
    if (schema.additionalProperties === false) {
      for (const key of Object.keys(value)) {
        if (!schema.properties || !schema.properties[key]) {
          errors.push(`${pointer}: unknown field "${key}" (not in the schema)`);
        }
      }
    }
    for (const [key, subSchema] of Object.entries(schema.properties || {})) {
      if (value[key] !== undefined) validateAgainstSchema(value[key], subSchema, `${pointer}/${key}`, errors);
    }
    return;
  }

  if (schema.type === 'array') {
    if (!Array.isArray(value)) {
      errors.push(`${pointer}: expected an array`);
      return;
    }
    if (schema.items) {
      value.forEach((item, i) => validateAgainstSchema(item, schema.items, `${pointer}[${i}]`, errors));
    }
    return;
  }

  if (schema.type === 'string') {
    if (typeof value !== 'string') {
      errors.push(`${pointer}: expected a string`);
      return;
    }
    if (schema.minLength && value.trim().length < schema.minLength) {
      errors.push(`${pointer}: too short (${value.trim().length} chars, minimum ${schema.minLength}) — say something a reader could not get from the signature`);
    }
  }

  if (schema.enum && !schema.enum.includes(value)) {
    errors.push(`${pointer}: "${value}" is not one of ${schema.enum.join(', ')}`);
  }
}

// ---------------------------------------------------------------------------
// Referential + freshness checks against the live scan
// ---------------------------------------------------------------------------

/** Every id the graph will actually contain, with the fingerprint it currently has. */
function indexGraphNodes(artifacts, model) {
  const index = new Map(); // "Kind:id" -> fingerprint

  for (const mod of artifacts.modules || []) {
    index.set(`Module:${mod.name}`, fingerprintModule(mod));
  }
  for (const [packageName, typesInPackage] of model.typesByPackage) {
    if (packageName) index.set(`Package:${packageName}`, fingerprintPackage(packageName, typesInPackage));
  }
  for (const type of model.types) {
    index.set(`Type:${type.id}`, fingerprintType(type));
    for (const method of type.methods || []) {
      index.set(`Method:${methodIdOf(type.id, method)}`, fingerprintMethod(type.id, method));
      const endpoint = endpointOf(type, method);
      if (endpoint) index.set(`Endpoint:${endpoint}`, fingerprintEndpoint(endpoint, type.id, method.name));
    }
  }
  for (const [name, implementorIds] of model.externalBases) {
    index.set(`ExternalType:${name}`, fingerprintExternalType(name, implementorIds));
  }
  return index;
}

function checkReferences(descriptions, index, model, errors, warnings) {
  const seen = new Set();

  for (const node of descriptions.nodes || []) {
    const key = `${node.kind}:${node.id}`;
    if (seen.has(key)) {
      errors.push(`nodes: duplicate entry for ${key} — one description per node`);
      continue;
    }
    seen.add(key);

    if (!index.has(key)) {
      errors.push(
        `nodes: ${key} does not exist in ${rel(ARTIFACTS_FILE)}. ` +
        'Copy `kind` and `id` verbatim from the workload — an id that matches no node would load a phantom into the graph.'
      );
      continue;
    }

    const current = index.get(key);
    if (node.fingerprint !== current) {
      warnings.push(`${key}: description was written against different code (fingerprint ${node.fingerprint} vs current ${current}) — it will load marked STALE`);
    }
  }

  const validModules = new Set((model.modules || []).map((m) => m.name));
  for (const note of descriptions.crossCutting || []) {
    for (const moduleName of note.modules || []) {
      if (!validModules.has(moduleName)) {
        errors.push(`crossCutting[${note.id}]: unknown module "${moduleName}"`);
      }
    }
    for (const typeId of note.types || []) {
      if (!model.typesById.has(typeId)) {
        errors.push(`crossCutting[${note.id}]: unknown type id "${typeId}"`);
      }
    }
  }
}

function reportCoverage(descriptions) {
  if (!fs.existsSync(WORKLOAD_JSON)) return null;
  let workload;
  try {
    workload = JSON.parse(fs.readFileSync(WORKLOAD_JSON, 'utf8'));
  } catch (err) {
    return null;
  }
  const described = new Set((descriptions.nodes || []).map((n) => `${n.kind}:${n.id}`));
  const requested = (workload.nodes || []).map((n) => `${n.kind}:${n.id}`);
  const missing = requested.filter((key) => !described.has(key));
  return { requested: requested.length, covered: requested.length - missing.length, missing };
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(`Context Weaver — Validator

  node scripts/validate-context.js [--strict]

  --strict   Treat stale descriptions as errors instead of warnings.

Validates .github/.pipeline-context/context/descriptions.json against the schema, against the ids in
artifacts.json, and against the current fingerprint of each described node.`);
    return;
  }

  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts at ${rel(ARTIFACTS_FILE)}. Run the Code Cartographer scan first.`);
  }
  if (!fs.existsSync(DESCRIPTIONS_FILE)) {
    throw new Error(
      `No descriptions at ${rel(DESCRIPTIONS_FILE)}.\n` +
      'Read .github/.pipeline-context/context/context-workload.md and write them there (or run generate-descriptions.js).'
    );
  }

  const artifacts = JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8'));
  const schema = JSON.parse(fs.readFileSync(SCHEMA_FILE, 'utf8'));
  let descriptions;
  try {
    descriptions = JSON.parse(fs.readFileSync(DESCRIPTIONS_FILE, 'utf8'));
  } catch (err) {
    throw new Error(`${rel(DESCRIPTIONS_FILE)} is not valid JSON: ${err.message}`);
  }

  const model = buildModel(artifacts);
  const errors = [];
  const warnings = [];

  validateAgainstSchema(descriptions, schema, 'descriptions', errors);
  if (descriptions.artifactsGeneratedAt && descriptions.artifactsGeneratedAt !== artifacts.generatedAt) {
    warnings.push(
      `descriptions were written against a scan taken ${descriptions.artifactsGeneratedAt}, but the current scan is ${artifacts.generatedAt} — ` +
      'per-node fingerprints below decide what is actually stale'
    );
  }
  checkReferences(descriptions, indexGraphNodes(artifacts, model), model, errors, warnings);

  const coverage = reportCoverage(descriptions);

  console.log(`Context Weaver — validating ${rel(DESCRIPTIONS_FILE)}`);
  console.log(`  author        : ${descriptions.author || '(unset)'}`);
  console.log(`  nodes         : ${(descriptions.nodes || []).length}`);
  console.log(`  cross-cutting : ${(descriptions.crossCutting || []).length}`);
  if (coverage) {
    // The workload holds what still needed writing when it was generated, so on an
    // incremental run this counts only the new/stale nodes — not the whole graph.
    console.log(`  coverage      : ${coverage.covered}/${coverage.requested} of the pending workload`);
  }

  if (warnings.length) {
    console.log(`\n${warnings.length} warning(s):`);
    warnings.forEach((w) => console.log(`  ! ${w}`));
  }
  if (coverage && coverage.missing.length) {
    console.log(`\n${coverage.missing.length} requested node(s) left undescribed (allowed — a missing description beats a wrong one):`);
    coverage.missing.slice(0, 15).forEach((k) => console.log(`  - ${k}`));
    if (coverage.missing.length > 15) console.log(`  ... and ${coverage.missing.length - 15} more`);
  }

  if (errors.length) {
    console.error(`\n${errors.length} error(s):`);
    errors.forEach((e) => console.error(`  x ${e}`));
    console.error('\nFix these and re-run. Nothing is loaded into the graph until validation passes.');
    process.exit(1);
  }

  if (args.strict && warnings.length) {
    console.error('\n--strict: stale or mismatched descriptions are errors. Re-author them from a fresh workload.');
    process.exit(1);
  }

  console.log('\nValid. Next: run Graph Forge to load the graph with this context attached.');
}

try {
  main();
} catch (err) {
  console.error('Context Weaver validation failed:', err.message);
  process.exit(1);
}
