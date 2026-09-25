/**
 * Context Weaver — shared model, selection heuristics and fingerprinting.
 *
 * Code Cartographer answers "what exists". This skill answers "what does it mean":
 * the semantic layer that turns a structurally-correct graph into one a downstream
 * agent can reason over without re-reading source.
 *
 * Two rules shape everything here:
 *   1. Descriptions are INTERPRETATION, not AST fact. They are always labelled with
 *      an author and a confidence so a consumer can tell them apart from what the
 *      parser actually saw.
 *   2. Only architecturally significant nodes get described. Describing all 51 types
 *      and 57 methods would cost more than it returns and bury the signal — the
 *      scoring below picks the nodes whose meaning a Root Cause / Blast Radius / QA
 *      agent will actually need.
 *
 * Self-contained by design — this skill folder can be copied out on its own.
 */
const crypto = require('crypto');
const path = require('path');

const SKILL_DIR = path.resolve(__dirname, '..', '..');
// Optional: only present once `npm install` has run in this skill folder, and only
// needed at all when generate-descriptions.js is used — the primary path (the
// architect agent authoring descriptions itself) never reads an env var.
try {
  require('dotenv').config({ path: path.join(SKILL_DIR, '.env') });
} catch (err) {
  // dotenv not installed yet — fine, env-driven config just falls back to defaults.
}
const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');
const CONTEXT_DIR = path.join(DATA_DIR, 'context');

const PATHS = {
  SKILL_DIR,
  REPO_ROOT,
  DATA_DIR,
  CONTEXT_DIR,
  ARTIFACTS_FILE: path.join(DATA_DIR, 'artifacts.json'),
  WORKLOAD_JSON: path.join(CONTEXT_DIR, 'context-workload.json'),
  WORKLOAD_MD: path.join(CONTEXT_DIR, 'context-workload.md'),
  DESCRIPTIONS_FILE: path.join(CONTEXT_DIR, 'descriptions.json'),
  SCHEMA_FILE: path.join(SKILL_DIR, 'templates', 'descriptions.schema.json'),
};

/** Repo-relative, forward-slashed — for display and for markdown links. */
function rel(target) {
  return path.relative(REPO_ROOT, target).replace(/\\/g, '/');
}

// ---------------------------------------------------------------------------
// Shared vocabulary (kept identical to Graph Forge so ids line up)
// ---------------------------------------------------------------------------

const MAPPING_ANNOTATIONS = {
  GetMapping: 'GET',
  PostMapping: 'POST',
  PutMapping: 'PUT',
  DeleteMapping: 'DELETE',
  PatchMapping: 'PATCH',
  RequestMapping: 'ANY',
};

const HTTP_CLIENT_TYPES = ['WebClient', 'WebClient.Builder', 'RestTemplate', 'RestClient', 'HttpClient'];

/** Spring/JPA stereotypes — a type carrying one of these has an architectural role. */
const STEREOTYPES = [
  'RestController', 'Controller', 'Service', 'Repository', 'Component', 'Configuration',
  'SpringBootApplication', 'ControllerAdvice', 'RestControllerAdvice', 'Entity', 'Document',
  'FeignClient', 'EnableEurekaServer', 'EnableConfigServer', 'EnableDiscoveryClient',
];

/** Methods that carry no design intent — never worth an LLM call. */
const TRIVIAL_METHOD_NAMES = /^(get|set|is|has)[A-Z]|^(toString|equals|hashCode|builder|of|valueOf)$/;

function simpleName(typeText) {
  if (!typeText) return null;
  return typeText.split('<')[0].trim();
}

function joinPath(base, extra) {
  const b = (base || '').replace(/^\/|\/$/g, '');
  const e = (extra || '').replace(/^\/|\/$/g, '');
  return '/' + [b, e].filter(Boolean).join('/');
}

function methodIdOf(typeId, method) {
  return `${typeId}#${method.name}(${(method.params || []).map((p) => p.type).join(',')})`;
}

function signatureOf(method) {
  return `${method.returnType || 'void'} ${method.name}(${(method.params || []).map((p) => `${p.type} ${p.name}`).join(', ')})`;
}

function endpointOf(type, method) {
  const classMapping = (type.annotations || []).find((a) => a.name === 'RequestMapping');
  const basePath = classMapping ? classMapping.args.value || '' : '';
  const mapping = (method.annotations || []).find((a) => MAPPING_ANNOTATIONS[a.name]);
  if (!mapping) return null;
  return `${MAPPING_ANNOTATIONS[mapping.name]} ${joinPath(basePath, mapping.args.value || mapping.args.path || '')}`;
}

// ---------------------------------------------------------------------------
// Model — same resolution rules Graph Forge applies, plus the fan-in counts the
// selector scores on.
// ---------------------------------------------------------------------------

function buildModel(artifacts) {
  const { types, modules } = artifacts;
  const typesById = new Map(types.map((t) => [t.id, t]));
  const nameToId = new Map();
  for (const t of types) if (!nameToId.has(t.name)) nameToId.set(t.name, t.id);

  const methodsById = new Map();
  const methodsByType = new Map();
  for (const t of types) {
    const byName = new Map();
    for (const m of t.methods || []) {
      const id = methodIdOf(t.id, m);
      methodsById.set(id, { id, type: t, method: m, endpoint: endpointOf(t, m) });
      const list = byName.get(m.name) || [];
      list.push(id);
      byName.set(m.name, list);
    }
    methodsByType.set(t.id, byName);
  }

  const fieldTypeByType = new Map();
  for (const t of types) {
    const byField = new Map();
    for (const f of t.fields || []) {
      const targetId = nameToId.get(simpleName(f.type));
      if (targetId) byField.set(f.name, targetId);
    }
    fieldTypeByType.set(t.id, byField);
  }

  const callees = new Map();
  const callers = new Map();
  const link = (fromId, toId) => {
    const out = callees.get(fromId) || new Set();
    out.add(toId);
    callees.set(fromId, out);
    const inverse = callers.get(toId) || new Set();
    inverse.add(fromId);
    callers.set(toId, inverse);
  };

  for (const t of types) {
    for (const m of t.methods || []) {
      const fromId = methodIdOf(t.id, m);
      if (!callees.has(fromId)) callees.set(fromId, new Set());
      for (const call of m.calls || []) {
        if (!call.name) continue;
        let targetTypeId = null;
        if (call.receiverKind === 'none' || call.receiverKind === 'this') targetTypeId = t.id;
        else if (call.receiverKind === 'identifier' || call.receiverKind === 'this-field') {
          targetTypeId = fieldTypeByType.get(t.id)?.get(call.receiverName) || nameToId.get(call.receiverName) || null;
        }
        if (!targetTypeId) continue;
        for (const toId of methodsByType.get(targetTypeId)?.get(call.name) || []) link(fromId, toId);
      }
    }
  }

  // Interface -> implementation. Spring injects the implementation, so the interface
  // is the contract every caller sees and the implementation is where behaviour lives.
  // Both are worth describing, for different reasons.
  const implementationsOf = new Map();
  const externalBases = new Map(); // externalTypeName -> [implementorTypeId]
  for (const t of types) {
    for (const raw of [...(t.implements || []), ...(t.extends || [])]) {
      const name = simpleName(raw);
      const targetId = nameToId.get(name);
      if (targetId && targetId !== t.id) {
        const list = implementationsOf.get(targetId) || [];
        list.push(t.id);
        implementationsOf.set(targetId, list);
      } else if (!targetId && name) {
        const list = externalBases.get(name) || [];
        list.push(t.id);
        externalBases.set(name, list);
      }
    }
  }

  // Field-based USES fan-in — "how many types would notice if this changed".
  const usesFanIn = new Map();
  for (const t of types) {
    for (const f of t.fields || []) {
      const targetId = nameToId.get(simpleName(f.type));
      if (!targetId || targetId === t.id) continue;
      usesFanIn.set(targetId, (usesFanIn.get(targetId) || 0) + 1);
    }
  }

  const typesByPackage = new Map();
  for (const t of types) {
    const list = typesByPackage.get(t.package) || [];
    list.push(t);
    typesByPackage.set(t.package, list);
  }

  const typesByModule = new Map();
  for (const t of types) {
    const list = typesByModule.get(t.module) || [];
    list.push(t);
    typesByModule.set(t.module, list);
  }

  return {
    types, modules, typesById, nameToId, methodsById, methodsByType,
    fieldTypeByType, callees, callers, implementationsOf, externalBases,
    usesFanIn, typesByPackage, typesByModule,
  };
}

function httpClientFields(type) {
  return (type.fields || []).filter(
    (f) => HTTP_CLIENT_TYPES.includes(simpleName(f.type)) || HTTP_CLIENT_TYPES.includes(f.type)
  );
}

// ---------------------------------------------------------------------------
// Fingerprints — the staleness contract
// ---------------------------------------------------------------------------
//
// A description is written against a specific shape of code. When that shape changes
// the description may have silently become a lie, which is worse than having none at
// all. Each described node carries the fingerprint of the code it was written for;
// re-scanning recomputes it, and any mismatch marks the description stale rather than
// serving it as if it were still true.
//
// Graph Forge duplicates these functions so it can flag staleness at load time. Any
// change here must be mirrored there.

function sha1(text) {
  return crypto.createHash('sha1').update(text).digest('hex').slice(0, 16);
}

function fingerprintModule(mod) {
  const deps = (mod.dependencies || []).map((d) => `${d.groupId}:${d.artifactId}`).sort();
  return sha1(['module', mod.name, mod.path, mod.groupId, mod.version, mod.packaging, ...deps].join('|'));
}

function fingerprintPackage(packageName, typesInPackage) {
  return sha1(['package', packageName, ...typesInPackage.map((t) => t.id).sort()].join('|'));
}

function fingerprintType(type) {
  const annotations = (type.annotations || []).map((a) => a.name).sort();
  const inheritance = [...(type.extends || []), ...(type.implements || [])].map(simpleName).sort();
  const fields = (type.fields || []).map((f) => `${f.name}:${f.type}`).sort();
  const methods = (type.methods || []).map((m) => methodIdOf(type.id, m)).sort();
  return sha1(['type', type.id, type.kind, ...annotations, ...inheritance, ...fields, ...methods].join('|'));
}

function fingerprintMethod(typeId, method) {
  const annotations = (method.annotations || []).map((a) => a.name).sort();
  const body = (method.source || '').replace(/\s+/g, ' ').trim();
  return sha1(['method', methodIdOf(typeId, method), method.returnType || 'void', ...annotations, body].join('|'));
}

function fingerprintEndpoint(endpointId, typeId, methodName) {
  return sha1(['endpoint', endpointId, typeId, methodName].join('|'));
}

function fingerprintExternalType(name, implementorIds) {
  return sha1(['externalType', name, ...[...implementorIds].sort()].join('|'));
}

// ---------------------------------------------------------------------------
// Selection — which nodes are worth describing
// ---------------------------------------------------------------------------
//
// Scored rather than rule-listed, so the threshold is one tunable knob instead of a
// growing pile of special cases. Every candidate carries the reasons it scored, which
// go into the brief: an author who knows *why* a node was selected writes a more
// useful description than one handed a bare list.

/** Env var wins over the hardcoded fallback; an explicit CLI flag wins over both. */
function envInt(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = parseInt(raw, 10);
  return Number.isInteger(n) ? n : fallback;
}

const DEFAULTS = {
  typeThreshold: envInt('CONTEXT_TYPE_THRESHOLD', 3),
  methodThreshold: envInt('CONTEXT_METHOD_THRESHOLD', 3),
  minPackageTypes: envInt('CONTEXT_MIN_PACKAGE_TYPES', 2),
  maxNodes: envInt('CONTEXT_MAX_NODES', 400),
};

function scoreType(model, type) {
  const reasons = [];
  let score = 0;
  const annotations = (type.annotations || []).map((a) => a.name);

  const stereotype = annotations.find((a) => STEREOTYPES.includes(a));
  if (stereotype) { score += 3; reasons.push(`@${stereotype} stereotype`); }

  const endpoints = (type.methods || []).map((m) => endpointOf(type, m)).filter(Boolean);
  if (endpoints.length) { score += 3; reasons.push(`exposes ${endpoints.length} REST endpoint(s)`); }

  const scheduled = (type.methods || []).filter((m) => (m.annotations || []).some((a) => a.name === 'Scheduled'));
  if (scheduled.length) { score += 2; reasons.push(`runs ${scheduled.length} scheduled job(s)`); }

  const clients = httpClientFields(type);
  if (clients.length) { score += 2; reasons.push(`holds HTTP client field(s): ${clients.map((f) => f.name).join(', ')}`); }

  const fanIn = model.usesFanIn.get(type.id) || 0;
  if (fanIn >= 2) { score += 2; reasons.push(`${fanIn} type(s) depend on it`); }

  // An interface with an implementation is the type Spring actually injects, so it is
  // what every caller is coded against — the contract is at least as worth describing
  // as the class behind it. Scored to clear the threshold on its own: these carry no
  // stereotype annotation and would otherwise be missed entirely.
  const impls = model.implementationsOf.get(type.id) || [];
  if (type.kind === 'interface' && impls.length) {
    score += 3;
    reasons.push(`contract implemented by ${impls.map((id) => model.typesById.get(id)?.name).filter(Boolean).join(', ')}`);
  }

  // Framework base types contribute methods the parser never sees (MongoRepository's
  // findAll, ResponseEntityExceptionHandler's handlers). A call to one looks like a
  // dead end in the graph, so the type that inherits it needs saying out loud.
  const externalBase = [...(type.extends || []), ...(type.implements || [])]
    .map(simpleName)
    .filter((n) => n && !model.nameToId.has(n));
  if (externalBase.length) { score += 3; reasons.push(`extends framework type(s): ${externalBase.join(', ')}`); }

  const inRepoFields = (type.fields || []).filter((f) => model.nameToId.has(simpleName(f.type)));
  if (inRepoFields.length) { score += 1; reasons.push(`injects ${inRepoFields.length} in-repo collaborator(s)`); }

  return { score, reasons };
}

function isTrivialMethod(type, method) {
  if ((method.calls || []).length) return false;
  if (method.name === type.name) return true; // constructor with no delegation
  const span = (method.endLine || 0) - (method.startLine || 0);
  return TRIVIAL_METHOD_NAMES.test(method.name) && span <= 4;
}

function scoreMethod(model, type, method) {
  const reasons = [];
  let score = 0;
  const id = methodIdOf(type.id, method);

  const endpoint = endpointOf(type, method);
  if (endpoint) { score += 4; reasons.push(`REST entry point ${endpoint}`); }

  const scheduled = (method.annotations || []).find((a) => a.name === 'Scheduled');
  if (scheduled) {
    score += 4;
    reasons.push(`scheduled job (${scheduled.args.cron || scheduled.args.value || 'no schedule recorded'})`);
  }

  const callerCount = (model.callers.get(id) || new Set()).size;
  if (callerCount >= 2) { score += 3; reasons.push(`called from ${callerCount} site(s)`); }

  const calleeCount = (model.callees.get(id) || new Set()).size;
  if (calleeCount >= 2) { score += 2; reasons.push(`orchestrates ${calleeCount} downstream call(s)`); }

  const clientFields = new Set(httpClientFields(type).map((f) => f.name));
  if ((method.calls || []).some((c) => clientFields.has(c.receiverName))) {
    score += 2;
    reasons.push('makes an outbound HTTP call');
  }

  if (type.kind === 'interface' && (model.implementationsOf.get(type.id) || []).length) {
    score += 2;
    reasons.push('interface contract with an implementation behind it');
  }

  const span = (method.endLine || 0) - (method.startLine || 0);
  if (span > 15) { score += 1; reasons.push(`${span} lines of logic`); }

  return { score, reasons };
}

/**
 * Everything worth describing, scored and sorted. `known` maps id -> fingerprint of
 * an existing description, so re-runs can emit only what is new or stale.
 */
function selectNodes(model, options = {}) {
  const opts = { ...DEFAULTS, ...options };
  const candidates = [];

  // Modules — always. Six service boundaries is the cheapest, highest-leverage
  // context in the whole graph.
  for (const mod of model.modules || []) {
    const typesInModule = model.typesByModule.get(mod.name) || [];
    candidates.push({
      kind: 'Module',
      id: mod.name,
      label: mod.name,
      fingerprint: fingerprintModule(mod),
      score: 10,
      reasons: ['deployable service boundary'],
      facts: {
        path: mod.path,
        packaging: mod.packaging,
        typeCount: typesInModule.length,
        dependencies: (mod.dependencies || []).map((d) => `${d.groupId}:${d.artifactId}`),
        endpoints: typesInModule.flatMap((t) => (t.methods || []).map((m) => endpointOf(t, m)).filter(Boolean)),
      },
    });
  }

  // Packages — the layer map. Single-type packages carry no grouping intent.
  for (const [packageName, typesInPackage] of model.typesByPackage) {
    if (!packageName || typesInPackage.length < opts.minPackageTypes) continue;
    candidates.push({
      kind: 'Package',
      id: packageName,
      label: packageName,
      fingerprint: fingerprintPackage(packageName, typesInPackage),
      score: 6,
      reasons: [`groups ${typesInPackage.length} types`],
      facts: {
        module: typesInPackage[0].module,
        types: typesInPackage.map((t) => `${t.name} (${t.kind})`),
      },
    });
  }

  // Types
  for (const type of model.types) {
    const { score, reasons } = scoreType(model, type);
    if (score < opts.typeThreshold) continue;
    candidates.push({
      kind: 'Type',
      id: type.id,
      label: type.name,
      fingerprint: fingerprintType(type),
      score,
      reasons,
      facts: {
        kind: type.kind,
        module: type.module,
        package: type.package,
        file: `${type.file}:${type.startLine}`,
        annotations: (type.annotations || []).map((a) => `@${a.name}`),
        extends: [...(type.extends || []), ...(type.implements || [])].map(simpleName),
        implementations: (model.implementationsOf.get(type.id) || []).map((id) => model.typesById.get(id)?.name).filter(Boolean),
        fields: (type.fields || []).map((f) => `${f.type} ${f.name}`),
        methods: (type.methods || []).map((m) => signatureOf(m)),
        endpoints: (type.methods || []).map((m) => endpointOf(type, m)).filter(Boolean),
        dependedOnBy: model.usesFanIn.get(type.id) || 0,
      },
    });
  }

  // Methods
  for (const type of model.types) {
    for (const method of type.methods || []) {
      if (isTrivialMethod(type, method)) continue;
      const { score, reasons } = scoreMethod(model, type, method);
      if (score < opts.methodThreshold) continue;
      const id = methodIdOf(type.id, method);
      candidates.push({
        kind: 'Method',
        id,
        label: `${type.name}.${method.name}()`,
        fingerprint: fingerprintMethod(type.id, method),
        score,
        reasons,
        facts: {
          signature: signatureOf(method),
          module: type.module,
          owner: type.name,
          file: `${type.file}:${method.startLine}-${method.endLine}`,
          annotations: (method.annotations || []).map((a) => `@${a.name}`),
          endpoint: endpointOf(type, method),
          calls: [...(model.callees.get(id) || [])].map((c) => model.methodsById.get(c)?.id).filter(Boolean),
          calledBy: [...(model.callers.get(id) || [])].map((c) => model.methodsById.get(c)?.id).filter(Boolean),
          source: method.source || null,
        },
      });
    }
  }

  // Endpoints — the external contract. Few, and every downstream agent starts here.
  for (const type of model.types) {
    for (const method of type.methods || []) {
      const endpoint = endpointOf(type, method);
      if (!endpoint) continue;
      candidates.push({
        kind: 'Endpoint',
        id: endpoint,
        label: endpoint,
        fingerprint: fingerprintEndpoint(endpoint, type.id, method.name),
        score: 9,
        reasons: ['externally reachable entry point'],
        facts: {
          module: type.module,
          handler: `${type.name}.${method.name}()`,
          signature: signatureOf(method),
          file: `${type.file}:${method.startLine}`,
        },
      });
    }
  }

  // External framework base types. The graph has no methods for these, so a call to
  // an inherited method (MongoRepository.findAll) looks like a dead end. Describing
  // the base type is what tells a Root Cause agent the call is real.
  for (const [name, implementorIds] of model.externalBases) {
    candidates.push({
      kind: 'ExternalType',
      id: name,
      label: name,
      fingerprint: fingerprintExternalType(name, implementorIds),
      score: 5 + implementorIds.length,
      reasons: [`framework base type behind ${implementorIds.length} in-repo type(s)`],
      facts: {
        implementors: implementorIds.map((id) => model.typesById.get(id)?.name).filter(Boolean),
      },
    });
  }

  candidates.sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
  return candidates.slice(0, opts.maxNodes);
}

module.exports = {
  ...PATHS,
  DEFAULTS,
  envInt,
  MAPPING_ANNOTATIONS,
  HTTP_CLIENT_TYPES,
  STEREOTYPES,
  rel,
  simpleName,
  joinPath,
  methodIdOf,
  signatureOf,
  endpointOf,
  buildModel,
  httpClientFields,
  sha1,
  fingerprintModule,
  fingerprintPackage,
  fingerprintType,
  fingerprintMethod,
  fingerprintEndpoint,
  fingerprintExternalType,
  scoreType,
  scoreMethod,
  selectNodes,
};

