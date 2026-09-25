#!/usr/bin/env node
/**
 * Blueprint Scribe — Function Reference
 * Reads Code Cartographer artifacts.json and writes docs/agent_output/01-architecture/function-reference.md:
 * a per-module, per-class, per-method breakdown (signature, source location,
 * REST mapping, resolved callers/callees, and the exact method source) meant
 * for deep-dive diagnosis rather than a high-level overview.
 */
const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');
const ARTIFACTS_FILE = path.join(DATA_DIR, 'artifacts.json');
const OUT_FILE = path.join(REPO_ROOT, 'docs', 'agent_output', '01-architecture', 'function-reference.md');

const MAPPING_ANNOTATIONS = {
  GetMapping: 'GET',
  PostMapping: 'POST',
  PutMapping: 'PUT',
  DeleteMapping: 'DELETE',
  PatchMapping: 'PATCH',
  RequestMapping: 'ANY',
};

function simpleName(typeText) {
  if (!typeText) return null;
  return typeText.split('<')[0].trim();
}

function joinPath(base, extra) {
  const b = (base || '').replace(/^\/|\/$/g, '');
  const e = (extra || '').replace(/^\/|\/$/g, '');
  return '/' + [b, e].filter(Boolean).join('/');
}

function classify(type) {
  const names = (type.annotations || []).map((a) => a.name);
  const implementsNames = (type.implements || []).map(simpleName);
  if (names.includes('RestController') || names.includes('Controller')) return 'Controller';
  if (names.includes('RestControllerAdvice') || names.includes('ControllerAdvice')) return 'Exception Handler';
  if (names.includes('Service')) return 'Service';
  if (names.includes('Repository') || implementsNames.some((n) => n && n.includes('Repository'))) return 'Repository';
  if (names.includes('Document') || names.includes('Entity')) return 'Entity/Document';
  if (names.includes('Configuration')) return 'Configuration';
  if (/dto/i.test(type.package || '') || /Dto$|DTO$/.test(type.name || '')) return 'DTO';
  if (type.kind === 'interface' && /Service$/.test(type.name || '')) return 'Service';
  if (/Exception$/.test(type.name || '')) return 'Exception';
  return 'Other';
}

function methodId(typeId, meth) {
  const paramSig = (meth.params || []).map((p) => p.type).join(',');
  return `${typeId}#${meth.name}(${paramSig})`;
}

function signature(meth) {
  const params = (meth.params || []).map((p) => `${p.type} ${p.name}`).join(', ');
  return `${meth.returnType || 'void'} ${meth.name}(${params})`;
}

function annotationText(a) {
  if (!a.args || Object.keys(a.args).length === 0) return `@${a.name}`;
  const args = Object.entries(a.args)
    .map(([k, v]) => (k === 'value' ? `"${v}"` : `${k} = "${v}"`))
    .join(', ');
  return `@${a.name}(${args})`;
}

function endpointFor(type, meth) {
  const classMapping = (type.annotations || []).find((a) => a.name === 'RequestMapping');
  const basePath = classMapping ? classMapping.args.value || '' : '';
  const mapping = (meth.annotations || []).find((a) => MAPPING_ANNOTATIONS[a.name]);
  if (!mapping) return null;
  const httpMethod = MAPPING_ANNOTATIONS[mapping.name];
  return `${httpMethod} ${joinPath(basePath, mapping.args.value || mapping.args.path || '')}`;
}

function main() {
  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts found at ${ARTIFACTS_FILE}. Run the Code Cartographer scan first.`);
  }
  const { types, generatedAt } = JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8'));

  const nameToId = new Map();
  for (const t of types) if (!nameToId.has(t.name)) nameToId.set(t.name, t.id);
  const typesById = new Map(types.map((t) => [t.id, t]));

  // methodsByType: typeId -> methodName -> [methodId, ...] (supports overloads)
  const methodsByType = new Map();
  for (const t of types) {
    const byName = new Map();
    for (const meth of t.methods) {
      const id = methodId(t.id, meth);
      const list = byName.get(meth.name) || [];
      list.push(id);
      byName.set(meth.name, list);
    }
    methodsByType.set(t.id, byName);
  }

  // fieldTypeByType: typeId -> fieldName -> targetTypeId
  const fieldTypeByType = new Map();
  for (const t of types) {
    const byField = new Map();
    for (const f of t.fields || []) {
      const targetId = nameToId.get(simpleName(f.type));
      if (targetId) byField.set(f.name, targetId);
    }
    fieldTypeByType.set(t.id, byField);
  }

  // Resolve every call site to concrete method ids, same heuristic as Graph Forge.
  const calleesByMethod = new Map(); // methodId -> [methodId, ...]
  const callersByMethod = new Map(); // methodId -> [methodId, ...] (inverse)
  for (const t of types) {
    for (const meth of t.methods) {
      const fromId = methodId(t.id, meth);
      const resolved = [];
      for (const call of meth.calls || []) {
        if (!call.name) continue;
        let targetTypeId = null;
        if (call.receiverKind === 'none' || call.receiverKind === 'this') {
          targetTypeId = t.id;
        } else if (call.receiverKind === 'identifier' || call.receiverKind === 'this-field') {
          targetTypeId = fieldTypeByType.get(t.id)?.get(call.receiverName) || nameToId.get(call.receiverName) || null;
        }
        if (!targetTypeId) continue;
        const candidates = methodsByType.get(targetTypeId)?.get(call.name) || [];
        for (const toId of candidates) {
          resolved.push(toId);
          const inverse = callersByMethod.get(toId) || [];
          inverse.push(fromId);
          callersByMethod.set(toId, inverse);
        }
      }
      calleesByMethod.set(fromId, resolved);
    }
  }

  function describeMethodRef(id, fromId) {
    const [typeId] = id.split('#');
    const t = typesById.get(typeId);
    const name = id.slice(typeId.length + 1, id.indexOf('('));
    const label = t ? `\`${t.name}.${name}()\`` : `\`${id}\``;
    return id === fromId ? `${label} ⚠️ self-call — check for unintended recursion` : label;
  }

  const modules = [...new Set(types.map((t) => t.module))].sort();
  const sections = [];

  for (const moduleName of modules) {
    sections.push(`\n## Module: \`${moduleName}\`\n`);
    const moduleTypes = types
      .filter((t) => t.module === moduleName)
      .sort((a, b) => a.package.localeCompare(b.package) || a.name.localeCompare(b.name));

    for (const t of moduleTypes) {
      const kind = classify(t);
      sections.push(`### \`${t.name}\` (${t.kind}, ${kind})`);
      sections.push(`- **File**: \`${t.file}\` (lines ${t.startLine}-${t.endLine})`);
      sections.push(`- **Package**: \`${t.package}\``);
      if (t.annotations.length) sections.push(`- **Annotations**: ${t.annotations.map(annotationText).join(', ')}`);
      if (t.extends.length) sections.push(`- **Extends**: ${t.extends.join(', ')}`);
      if (t.implements.length) sections.push(`- **Implements**: ${t.implements.join(', ')}`);
      if (t.fields.length) {
        sections.push(`- **Fields**: ${t.fields.map((f) => `\`${f.type} ${f.name}\``).join(', ')}`);
      }

      if (!t.methods.length) {
        sections.push('\n_No methods._\n');
        continue;
      }

      for (const meth of t.methods) {
        const id = methodId(t.id, meth);
        const endpoint = endpointFor(t, meth);
        sections.push(`\n#### \`${t.name}.${meth.name}()\``);
        sections.push(`- **Signature**: \`${signature(meth)}\``);
        sections.push(`- **Location**: \`${t.file}\` (lines ${meth.startLine}-${meth.endLine})`);
        if (meth.annotations.length) sections.push(`- **Annotations**: ${meth.annotations.map(annotationText).join(', ')}`);
        if (endpoint) sections.push(`- **REST endpoint**: \`${endpoint}\``);
        const callees = calleesByMethod.get(id) || [];
        sections.push(`- **Calls**: ${callees.length ? callees.map((c) => describeMethodRef(c, id)).join(', ') : '_none resolved_'}`);
        const callers = callersByMethod.get(id) || [];
        sections.push(`- **Called by**: ${callers.length ? callers.map((c) => describeMethodRef(c, id)).join(', ') : '_none resolved (likely an entry point or only called externally)_'}`);
        sections.push('\n```java\n' + meth.source + '\n```\n');
      }
    }
  }

  const doc = `# Function-Level Reference

_Generated by the Architect agent (Blueprint Scribe) from a scan on ${generatedAt}._

This document is a deep-dive companion to [architecture.md](./architecture.md): every class and method in the workspace, with its exact source location, signature, resolved call graph (who it calls / who calls it), and full source text. Use it to trace a bug or a request end-to-end without re-opening every file.

**Resolution note**: "Calls"/"Called by" are resolved by matching method names against same-class calls, field-typed calls (\`this.field.method()\`), and static calls on a known type. Call sites don't carry static argument types, so overloaded methods are matched by name only.
${sections.join('\n')}
`;

  fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });
  fs.writeFileSync(OUT_FILE, doc);
  console.log(`Blueprint Scribe: wrote ${OUT_FILE} (${types.reduce((n, t) => n + t.methods.length, 0)} methods documented)`);
}

main();
