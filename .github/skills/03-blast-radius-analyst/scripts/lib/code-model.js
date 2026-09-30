/**
 * Blast Radius Analyst — code model built from .github/.pipeline-context/artifacts.json.
 *
 * Where the Root Cause Analyst needs depth (the exact call chain into one defect),
 * this skill needs breadth: every endpoint a service exposes, every job it schedules,
 * which services talk to which over HTTP, and what infrastructure they share.
 *
 * Self-contained by design — this skill folder can be copied out on its own.
 */

const MAPPING_ANNOTATIONS = {
  GetMapping: 'GET',
  PostMapping: 'POST',
  PutMapping: 'PUT',
  DeleteMapping: 'DELETE',
  PatchMapping: 'PATCH',
  RequestMapping: 'ANY',
};

const HTTP_CLIENT_TYPES = ['WebClient', 'WebClient.Builder', 'RestTemplate', 'RestClient', 'HttpClient'];

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

/**
 * Call graph with interface -> implementation dispatch bridged in, so a path from a
 * controller reaches the implementation Spring actually injects rather than stopping
 * at the interface.
 */
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

  const implementationsOf = new Map();
  for (const t of types) {
    for (const raw of [...(t.implements || []), ...(t.extends || [])]) {
      const targetId = nameToId.get(simpleName(raw));
      if (!targetId || targetId === t.id) continue;
      const list = implementationsOf.get(targetId) || [];
      list.push(t.id);
      implementationsOf.set(targetId, list);
    }
  }
  let dispatchEdges = 0;
  for (const [interfaceId, implIds] of implementationsOf) {
    const interfaceType = typesById.get(interfaceId);
    if (!interfaceType) continue;
    for (const m of interfaceType.methods || []) {
      const fromId = methodIdOf(interfaceId, m);
      if (!methodsById.has(fromId)) continue;
      for (const implId of implIds) {
        for (const toId of methodsByType.get(implId)?.get(m.name) || []) {
          if (toId === fromId) continue;
          link(fromId, toId);
          dispatchEdges += 1;
        }
      }
    }
  }

  return { types, modules, typesById, nameToId, methodsById, methodsByType, callees, callers, implementationsOf, dispatchEdges };
}

/** Resolve "Type.method", "pkg.Type.method" or "Type" into concrete method entries. */
function resolveSymbol(model, spec) {
  const cleaned = String(spec).replace(/\(\)\s*$/, '').trim();
  const parts = cleaned.split('.');
  const last = parts[parts.length - 1];
  const maybeTypeName = parts.length > 1 ? parts[parts.length - 2] : null;

  const directTypeId = model.nameToId.get(last);
  if (directTypeId && !maybeTypeName) {
    const type = model.typesById.get(directTypeId);
    return (type.methods || []).map((m) => model.methodsById.get(methodIdOf(type.id, m)));
  }

  const typeId = maybeTypeName ? model.nameToId.get(maybeTypeName) : null;
  if (typeId) {
    const ids = model.methodsByType.get(typeId)?.get(last) || [];
    if (ids.length) return ids.map((id) => model.methodsById.get(id));
    const type = model.typesById.get(typeId);
    if (type) return (type.methods || []).map((m) => model.methodsById.get(methodIdOf(type.id, m)));
  }

  return [...model.methodsById.values()].filter((e) => e.method.name === last);
}

/** All method ids that can reach `startIds` by following CALLS edges backwards. */
function reachedFrom(model, startIds, direction = 'up', depth = 6) {
  const edges = direction === 'up' ? model.callers : model.callees;
  const seen = new Set(startIds);
  let frontier = [...startIds];
  for (let level = 0; level < depth && frontier.length; level += 1) {
    const next = [];
    for (const id of frontier) {
      for (const neighbour of edges.get(id) || []) {
        if (seen.has(neighbour)) continue;
        seen.add(neighbour);
        next.push(neighbour);
      }
    }
    frontier = next;
  }
  return seen;
}

/** Per-module inventory: what each service exposes and runs. */
function moduleInventory(model) {
  const inventory = new Map();
  for (const t of model.types) {
    const entry = inventory.get(t.module) || { module: t.module, types: [], endpoints: [], scheduledJobs: [], controllers: [] };
    entry.types.push(t.name);
    const annotations = (t.annotations || []).map((a) => a.name);
    if (annotations.includes('RestController') || annotations.includes('Controller')) entry.controllers.push(t.name);
    for (const m of t.methods || []) {
      const endpoint = endpointOf(t, m);
      if (endpoint) entry.endpoints.push({ endpoint, handler: `${t.name}.${m.name}()`, methodId: methodIdOf(t.id, m) });
      if ((m.annotations || []).some((a) => a.name === 'Scheduled')) {
        const cron = (m.annotations || []).find((a) => a.name === 'Scheduled');
        entry.scheduledJobs.push({ job: `${t.name}.${m.name}()`, schedule: (cron && cron.args && (cron.args.cron || cron.args.value)) || null, methodId: methodIdOf(t.id, m) });
      }
    }
    inventory.set(t.module, entry);
  }
  return inventory;
}

/**
 * Service-to-service HTTP calls. These carry no Java call edge, so without this the
 * blast radius would stop at the service boundary and understate the damage.
 */
function httpConsumers(model, targetModules) {
  const keywords = targetModules
    .map((m) => m.replace(/-service$/, '').replace(/[^a-z]/gi, '').toLowerCase())
    .filter(Boolean);
  const consumers = [];
  for (const t of model.types) {
    if (targetModules.includes(t.module)) continue;
    const clients = (t.fields || []).filter((f) => HTTP_CLIENT_TYPES.includes(simpleName(f.type)) || HTTP_CLIENT_TYPES.includes(f.type));
    if (!clients.length) continue;
    const hints = (t.fields || [])
      .map((f) => `${f.type} ${f.name}`)
      .filter((text) => keywords.some((k) => k && text.toLowerCase().includes(k)));

    // Only the methods that actually touch the HTTP client field make the outbound
    // call. Holding the field is not the same as using it, and the difference decides
    // whether one endpoint is degraded or the whole service looks degraded.
    const clientFieldNames = new Set(clients.map((f) => f.name));
    const callingMethods = (t.methods || [])
      .filter((m) => (m.calls || []).some((c) => clientFieldNames.has(c.receiverName)))
      .map((m) => ({ id: methodIdOf(t.id, m), name: m.name }));

    consumers.push({
      module: t.module,
      type: t.name,
      typeId: t.id,
      file: t.file,
      httpClients: clients.map((f) => `${f.type} ${f.name}`),
      confirmed: hints.length > 0,
      hints,
      callingMethods,
      endpoints: (t.methods || []).map((m) => endpointOf(t, m)).filter(Boolean),
    });
  }
  return consumers.sort((a, b) => Number(b.confirmed) - Number(a.confirmed));
}

/**
 * Platform each module sits on, from pom dependencies and bootstrap annotations:
 * who registers with discovery, who reads config, who shares the data store.
 */
function platformTopology(model) {
  const artifactIdsByModule = new Map(
    (model.modules || []).map((m) => [m.name, new Set((m.dependencies || []).map((d) => d.artifactId))])
  );
  const annotationsByModule = new Map();
  for (const t of model.types) {
    const set = annotationsByModule.get(t.module) || new Set();
    (t.annotations || []).forEach((a) => set.add(a.name));
    annotationsByModule.set(t.module, set);
  }

  const has = (module, artifactId) => artifactIdsByModule.get(module)?.has(artifactId) || false;
  const annotated = (module, annotation) => annotationsByModule.get(module)?.has(annotation) || false;

  const modules = (model.modules || []).map((m) => m.name);
  const discoveryServer = modules.find((m) => annotated(m, 'EnableEurekaServer') || has(m, 'spring-cloud-starter-netflix-eureka-server')) || null;
  const configServer = modules.find((m) => annotated(m, 'EnableConfigServer')) || null;

  return {
    modules,
    discoveryServer,
    configServer,
    registersWithDiscovery: modules.filter((m) => m !== discoveryServer && has(m, 'spring-cloud-starter-netflix-eureka-client')),
    readsConfig: modules.filter((m) => m !== configServer && has(m, 'spring-cloud-starter-config')),
    sharesDataStore: modules.filter((m) => has(m, 'spring-boot-starter-data-mongodb')),
  };
}

module.exports = {
  MAPPING_ANNOTATIONS,
  simpleName,
  methodIdOf,
  signatureOf,
  endpointOf,
  buildModel,
  resolveSymbol,
  reachedFrom,
  moduleInventory,
  httpConsumers,
  platformTopology,
};
