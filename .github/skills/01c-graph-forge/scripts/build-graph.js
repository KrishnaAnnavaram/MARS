#!/usr/bin/env node
/**
 * Graph Forge
 * Loads the artifacts.json produced by Code Cartographer into an existing
 * Neo4j instance, modeling modules, packages, types, methods and their
 * connections (inheritance, containment, REST endpoints, field-based usage).
 *
 * Structure is only half of what a downstream agent needs. If Context Weaver has
 * produced .github/.pipeline-context/context/descriptions.json, this loader also attaches the
 * semantic layer — what each significant node is for, how it fails, what it touches —
 * onto the same nodes, so one Cypher query returns both the shape and the meaning.
 *
 * That layer is interpretation, not parser output, and is loaded with its provenance
 * intact (author, confidence, staleness) so a consumer can always tell the two apart.
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
const neo4j = require('neo4j-driver');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');
const ARTIFACTS_FILE = path.join(DATA_DIR, 'artifacts.json');
const DESCRIPTIONS_FILE = path.join(DATA_DIR, 'context', 'descriptions.json');

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

function methodIdOf(typeId, method) {
  return `${typeId}#${method.name}(${(method.params || []).map((p) => p.type).join(',')})`;
}

// ---------------------------------------------------------------------------
// Context fingerprints
// ---------------------------------------------------------------------------
//
// A description is written against one shape of the code. When that shape changes the
// description may have quietly become false, which is worse than having none — so each
// carries the fingerprint it was written for, and the loader recomputes the current one
// to mark drift as `contextStale` rather than serving it as if it were still true.
//
// MUST stay identical to 01b-context-weaver/scripts/lib/context.js. Duplicated rather than
// imported so this skill folder stays independently copyable, the same way the analyst
// skills each carry their own copy of the model helpers.

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

/** Current fingerprint of every node this graph will hold, keyed "Kind:id". */
function indexFingerprints(modules, types) {
  const index = new Map();
  const nameToId = new Map();
  for (const t of types) if (!nameToId.has(t.name)) nameToId.set(t.name, t.id);

  for (const mod of modules) index.set(`Module:${mod.name}`, fingerprintModule(mod));

  const byPackage = new Map();
  for (const t of types) {
    const list = byPackage.get(t.package) || [];
    list.push(t);
    byPackage.set(t.package, list);
  }
  for (const [packageName, typesInPackage] of byPackage) {
    if (packageName) index.set(`Package:${packageName}`, fingerprintPackage(packageName, typesInPackage));
  }

  const externalBases = new Map();
  for (const t of types) {
    index.set(`Type:${t.id}`, fingerprintType(t));
    const classMapping = (t.annotations || []).find((a) => a.name === 'RequestMapping');
    const basePath = classMapping ? classMapping.args.value || '' : '';
    for (const m of t.methods || []) {
      index.set(`Method:${methodIdOf(t.id, m)}`, fingerprintMethod(t.id, m));
      const mapping = (m.annotations || []).find((a) => MAPPING_ANNOTATIONS[a.name]);
      if (mapping) {
        const id = `${MAPPING_ANNOTATIONS[mapping.name]} ${joinPath(basePath, mapping.args.value || mapping.args.path || '')}`;
        index.set(`Endpoint:${id}`, fingerprintEndpoint(id, t.id, m.name));
      }
    }
    for (const raw of [...(t.implements || []), ...(t.extends || [])]) {
      const name = simpleName(raw);
      if (name && !nameToId.has(name)) {
        const list = externalBases.get(name) || [];
        list.push(t.id);
        externalBases.set(name, list);
      }
    }
  }
  for (const [name, implementorIds] of externalBases) {
    index.set(`ExternalType:${name}`, fingerprintExternalType(name, implementorIds));
  }
  return index;
}

/** The property each label is keyed on, for matching a description to its node. */
const CONTEXT_KEY_PROPERTY = {
  Module: 'name',
  Package: 'name',
  Type: 'id',
  Method: 'id',
  Endpoint: 'id',
  ExternalType: 'name',
};

/**
 * Flatten one description into Neo4j-safe properties. Neo4j stores primitives and
 * arrays of primitives only — which is exactly what descriptions.schema.json allows,
 * so nothing here can fail to serialize.
 *
 * Every property is namespaced `ctx*`, and that prefix is load-bearing rather than
 * cosmetic. Nodes in this graph may already carry a generated `description` that only
 * restates the AST ("GenderType — enum in module sheduler-service. 0 methods"), which
 * is a different kind of claim entirely. Keeping the semantic layer in its own
 * namespace means it never overwrites generated text, never gets mistaken for parser
 * output, and can be full-text indexed on its own without the boilerplate drowning it.
 */
function contextProperties(node, currentFingerprint, author, generatedAt) {
  return {
    key: node.id,
    ctxSummary: node.summary,
    ctxRole: node.role || null,
    ctxCriticality: node.criticality || null,
    ctxResponsibilities: node.responsibilities || [],
    ctxSideEffects: node.sideEffects || [],
    ctxInvariants: node.invariants || [],
    ctxFailureModes: node.failureModes || [],
    ctxDataTouched: node.dataTouched || [],
    ctxUpstream: node.upstream || [],
    ctxDownstream: node.downstream || [],
    ctxTestHints: node.testHints || [],
    ctxOpenQuestions: node.openQuestions || [],
    ctxEvidence: node.evidence || [],
    ctxAuthor: author,
    ctxConfidence: node.confidence,
    ctxUpdatedAt: generatedAt,
    ctxFingerprint: node.fingerprint,
    ctxStale: Boolean(currentFingerprint && currentFingerprint !== node.fingerprint),
  };
}

/** Optional input — a graph without context is still a valid, useful graph. */
function loadDescriptions() {
  if (!fs.existsSync(DESCRIPTIONS_FILE)) return null;
  try {
    return JSON.parse(fs.readFileSync(DESCRIPTIONS_FILE, 'utf8'));
  } catch (err) {
    console.warn(`Warning: ${DESCRIPTIONS_FILE} could not be parsed (${err.message}) — loading structure only.`);
    return null;
  }
}

async function main() {
  if (!fs.existsSync(ARTIFACTS_FILE)) {
    throw new Error(`No artifacts found at ${ARTIFACTS_FILE}. Run the Code Cartographer scan first.`);
  }
  const { modules, types } = JSON.parse(fs.readFileSync(ARTIFACTS_FILE, 'utf8'));

  const uri = process.env.NEO4J_URI;
  const user = process.env.NEO4J_USERNAME;
  const password = process.env.NEO4J_PASSWORD;
  if (!uri || !user || !password) {
    throw new Error('Missing NEO4J_URI / NEO4J_USERNAME / NEO4J_PASSWORD. Copy .env.example to .env and fill in your instance details.');
  }
  const database = process.env.NEO4J_DATABASE || 'neo4j';

  const driver = neo4j.driver(uri, neo4j.auth.basic(user, password));
  await driver.verifyConnectivity();
  const session = driver.session({ database });

  const nameToId = new Map();
  for (const t of types) {
    if (!nameToId.has(t.name)) nameToId.set(t.name, t.id);
  }

  try {
    await session.executeWrite((tx) => tx.run(`
      CREATE CONSTRAINT module_name IF NOT EXISTS FOR (m:Module) REQUIRE m.name IS UNIQUE;
    `));
    await session.executeWrite((tx) => tx.run(`
      CREATE CONSTRAINT type_id IF NOT EXISTS FOR (t:Type) REQUIRE t.id IS UNIQUE;
    `));
    await session.executeWrite((tx) => tx.run(`
      CREATE CONSTRAINT external_type_name IF NOT EXISTS FOR (x:ExternalType) REQUIRE x.name IS UNIQUE;
    `));
    await session.executeWrite((tx) => tx.run(`
      CREATE CONSTRAINT package_name IF NOT EXISTS FOR (p:Package) REQUIRE p.name IS UNIQUE;
    `));
    await session.executeWrite((tx) => tx.run(`
      CREATE CONSTRAINT dependency_ga IF NOT EXISTS FOR (d:MavenDependency) REQUIRE d.ga IS UNIQUE;
    `));
    await session.executeWrite((tx) => tx.run(`
      CREATE CONSTRAINT endpoint_id IF NOT EXISTS FOR (e:Endpoint) REQUIRE e.id IS UNIQUE;
    `));

    // Modules + their Maven dependencies.
    await session.executeWrite((tx) => tx.run(
      `
      UNWIND $modules AS mod
      MERGE (m:Module {name: mod.name})
      SET m.path = mod.path, m.groupId = mod.groupId, m.version = mod.version, m.packaging = mod.packaging
      WITH m, mod
      UNWIND mod.dependencies AS dep
      MERGE (d:MavenDependency {ga: dep.groupId + ':' + dep.artifactId})
      SET d.groupId = dep.groupId, d.artifactId = dep.artifactId
      MERGE (m)-[:DEPENDS_ON]->(d)
      `,
      { modules }
    ));

    // Packages + Types (+ containment).
    await session.executeWrite((tx) => tx.run(
      `
      UNWIND $types AS t
      MERGE (p:Package {name: t.package})
      MERGE (ty:Type {id: t.id})
      SET ty.name = t.name, ty.kind = t.kind, ty.module = t.module, ty.package = t.package, ty.file = t.file,
          ty.annotations = [a IN t.annotations | a.name]
      MERGE (m:Module {name: t.module})
      MERGE (m)-[:CONTAINS]->(p)
      MERGE (p)-[:CONTAINS]->(ty)
      MERGE (m)-[:CONTAINS]->(ty)
      `,
      { types: types.map((t) => ({ id: t.id, name: t.name, kind: t.kind, module: t.module, package: t.package, file: t.file, annotations: t.annotations })) }
    ));

    // Inheritance: extends -> EXTENDS, implements -> EXTENDS (interface) or IMPLEMENTS (class).
    const inheritanceRows = [];
    for (const t of types) {
      for (const raw of t.extends || []) {
        inheritanceRows.push({ fromId: t.id, targetName: simpleName(raw), relType: 'EXTENDS' });
      }
      for (const raw of t.implements || []) {
        inheritanceRows.push({ fromId: t.id, targetName: simpleName(raw), relType: t.kind === 'interface' ? 'EXTENDS' : 'IMPLEMENTS' });
      }
    }
    for (const relType of ['EXTENDS', 'IMPLEMENTS']) {
      const rows = inheritanceRows
        .filter((r) => r.relType === relType)
        .map((r) => ({ fromId: r.fromId, targetId: nameToId.get(r.targetName) || null, targetName: r.targetName }));
      await session.executeWrite((tx) => tx.run(
        `
        UNWIND $rows AS r
        MATCH (from:Type {id: r.fromId})
        FOREACH (_ IN CASE WHEN r.targetId IS NOT NULL THEN [1] ELSE [] END |
          MERGE (to:Type {id: r.targetId})
          MERGE (from)-[:${relType}]->(to)
        )
        FOREACH (_ IN CASE WHEN r.targetId IS NULL THEN [1] ELSE [] END |
          MERGE (to:ExternalType {name: r.targetName})
          MERGE (from)-[:${relType}]->(to)
        )
        `,
        { rows }
      ));
    }

    // Methods, per type (method id includes a param signature to disambiguate overloads).
    const methodRows = [];
    for (const t of types) {
      for (const meth of t.methods) {
        const paramSig = (meth.params || []).map((p) => p.type).join(',');
        methodRows.push({ typeId: t.id, methodId: `${t.id}#${meth.name}(${paramSig})`, name: meth.name, returnType: meth.returnType });
      }
    }
    await session.executeWrite((tx) => tx.run(
      `
      UNWIND $rows AS r
      MATCH (ty:Type {id: r.typeId})
      MERGE (me:Method {id: r.methodId})
      SET me.name = r.name, me.returnType = r.returnType
      MERGE (ty)-[:HAS_METHOD]->(me)
      `,
      { rows: methodRows }
    ));

    // Method-level call graph: resolve method_invocation call sites captured by
    // Code Cartographer into CALLS edges between Method nodes (same-class calls,
    // field-based calls, and static calls on a known type — best-effort by name).
    const methodsByType = new Map(); // typeId -> name -> [methodId, ...]
    for (const row of methodRows) {
      const byName = methodsByType.get(row.typeId) || new Map();
      const list = byName.get(row.name) || [];
      list.push(row.methodId);
      byName.set(row.name, list);
      methodsByType.set(row.typeId, byName);
    }
    const fieldTypeByType = new Map(); // typeId -> fieldName -> targetTypeId
    for (const t of types) {
      const byField = new Map();
      for (const f of t.fields || []) {
        const targetId = nameToId.get(simpleName(f.type));
        if (targetId) byField.set(f.name, targetId);
      }
      fieldTypeByType.set(t.id, byField);
    }

    const callRows = [];
    for (const t of types) {
      for (const meth of t.methods) {
        const paramSig = (meth.params || []).map((p) => p.type).join(',');
        const fromId = `${t.id}#${meth.name}(${paramSig})`;
        for (const call of meth.calls || []) {
          if (!call.name) continue;
          let targetTypeId = null;
          if (call.receiverKind === 'none' || call.receiverKind === 'this') {
            targetTypeId = t.id;
          } else if (call.receiverKind === 'identifier' || call.receiverKind === 'this-field') {
            targetTypeId = fieldTypeByType.get(t.id)?.get(call.receiverName) || nameToId.get(call.receiverName) || null;
          }
          if (!targetTypeId) continue; // 'other' receivers or unresolved externals are skipped
          const candidates = methodsByType.get(targetTypeId)?.get(call.name) || [];
          for (const toId of candidates) {
            callRows.push({ fromId, toId });
          }
        }
      }
    }
    await session.executeWrite((tx) => tx.run(
      `
      UNWIND $rows AS r
      MATCH (from:Method {id: r.fromId})
      MATCH (to:Method {id: r.toId})
      MERGE (from)-[:CALLS]->(to)
      `,
      { rows: callRows }
    ));

    // REST endpoints derived from method + class-level mapping annotations.
    const endpointRows = [];
    for (const t of types) {
      const classMapping = (t.annotations || []).find((a) => a.name === 'RequestMapping');
      const basePath = classMapping ? classMapping.args.value || '' : '';
      for (const meth of t.methods) {
        const mapping = (meth.annotations || []).find((a) => MAPPING_ANNOTATIONS[a.name]);
        if (!mapping) continue;
        const httpMethod = MAPPING_ANNOTATIONS[mapping.name];
        const fullPath = joinPath(basePath, mapping.args.value || mapping.args.path || '');
        endpointRows.push({
          id: `${httpMethod} ${fullPath}`,
          method: httpMethod,
          path: fullPath,
          typeId: t.id,
          methodName: meth.name,
        });
      }
    }
    await session.executeWrite((tx) => tx.run(
      `
      UNWIND $rows AS r
      MERGE (e:Endpoint {id: r.id})
      SET e.method = r.method, e.path = r.path
      WITH e, r
      MATCH (ty:Type {id: r.typeId})
      MERGE (ty)-[:EXPOSES]->(e)
      `,
      { rows: endpointRows }
    ));

    // Field-based USES edges between known types (best-effort dependency signal).
    const usesRows = [];
    for (const t of types) {
      for (const f of t.fields || []) {
        const targetName = simpleName(f.type);
        const targetId = nameToId.get(targetName);
        if (targetId && targetId !== t.id) usesRows.push({ fromId: t.id, toId: targetId });
      }
    }
    await session.executeWrite((tx) => tx.run(
      `
      UNWIND $rows AS r
      MATCH (from:Type {id: r.fromId})
      MATCH (to:Type {id: r.toId})
      MERGE (from)-[:USES]->(to)
      `,
      { rows: usesRows }
    ));

    // -----------------------------------------------------------------------
    // Semantic layer — Context Weaver descriptions, if any exist.
    // -----------------------------------------------------------------------
    // Everything above is derived from the AST and is fact. Everything below is an
    // interpretation of it, and is loaded with the provenance a reader needs to treat
    // it as such: who wrote it, how confident they were, and whether the code has
    // changed underneath it since.
    const descriptions = loadDescriptions();
    let contextSummary = 'no descriptions found — structure only';

    if (descriptions) {
      const currentFingerprints = indexFingerprints(modules, types);
      const author = descriptions.author || 'unknown';
      const generatedAt = descriptions.generatedAt || new Date().toISOString();

      const byLabel = new Map();
      let staleCount = 0;
      let unmatched = 0;
      for (const node of descriptions.nodes || []) {
        if (!CONTEXT_KEY_PROPERTY[node.kind]) {
          unmatched += 1;
          continue;
        }
        const current = currentFingerprints.get(`${node.kind}:${node.id}`);
        if (current === undefined) {
          // Validation should have caught this. Skip rather than MERGE — inventing a
          // node here would put a description in the graph with nothing behind it.
          unmatched += 1;
          continue;
        }
        const props = contextProperties(node, current, author, generatedAt);
        if (props.ctxStale) staleCount += 1;
        const rows = byLabel.get(node.kind) || [];
        rows.push(props);
        byLabel.set(node.kind, rows);
      }

      // MATCH, never MERGE: a description can only annotate a node the parser found.
      let applied = 0;
      for (const [label, rows] of byLabel) {
        const keyProperty = CONTEXT_KEY_PROPERTY[label];
        await session.executeWrite((tx) => tx.run(
          `
          UNWIND $rows AS r
          MATCH (n:${label} {${keyProperty}: r.key})
          SET n.ctxSummary = r.ctxSummary,
              n.ctxRole = r.ctxRole,
              n.ctxCriticality = r.ctxCriticality,
              n.ctxResponsibilities = r.ctxResponsibilities,
              n.ctxSideEffects = r.ctxSideEffects,
              n.ctxInvariants = r.ctxInvariants,
              n.ctxFailureModes = r.ctxFailureModes,
              n.ctxDataTouched = r.ctxDataTouched,
              n.ctxUpstream = r.ctxUpstream,
              n.ctxDownstream = r.ctxDownstream,
              n.ctxTestHints = r.ctxTestHints,
              n.ctxOpenQuestions = r.ctxOpenQuestions,
              n.ctxEvidence = r.ctxEvidence,
              n.ctxAuthor = r.ctxAuthor,
              n.ctxConfidence = r.ctxConfidence,
              n.ctxUpdatedAt = r.ctxUpdatedAt,
              n.ctxFingerprint = r.ctxFingerprint,
              n.ctxStale = r.ctxStale
          `,
          { rows }
        ));
        applied += rows.length;
      }

      // Cross-cutting notes: facts that belong to no single node — a shared collection,
      // a service dependency with no Java call edge. Modeled as their own nodes so they
      // can be reached from every node they concern.
      const notes = descriptions.crossCutting || [];
      if (notes.length) {
        await session.executeWrite((tx) => tx.run(`
          CREATE CONSTRAINT context_note_id IF NOT EXISTS FOR (c:ContextNote) REQUIRE c.id IS UNIQUE;
        `));
        await session.executeWrite((tx) => tx.run(
          `
          UNWIND $notes AS note
          MERGE (c:ContextNote {id: note.id})
          SET c.topic = note.topic, c.ctxSummary = note.text,
              c.ctxConfidence = note.confidence, c.ctxEvidence = note.evidence,
              c.ctxAuthor = $author, c.ctxUpdatedAt = $generatedAt
          WITH c, note
          CALL {
            WITH c, note
            UNWIND note.modules AS moduleName
            MATCH (m:Module {name: moduleName})
            MERGE (c)-[:ABOUT]->(m)
          }
          CALL {
            WITH c, note
            UNWIND note.types AS typeId
            MATCH (t:Type {id: typeId})
            MERGE (c)-[:ABOUT]->(t)
          }
          `,
          {
            author,
            generatedAt,
            notes: notes.map((n) => ({
              id: n.id,
              topic: n.topic,
              text: n.text,
              confidence: n.confidence,
              evidence: n.evidence || [],
              modules: n.modules || [],
              types: n.types || [],
            })),
          }
        ));
      }

      // Full-text index over the semantic layer only, so an agent can find the right
      // node from a symptom described in prose ("salary lookup returns nothing")
      // instead of having to already know the class name. Failure modes are indexed
      // alongside summaries because a reported symptom usually matches those first.
      await session.executeWrite((tx) => tx.run(`
        CREATE FULLTEXT INDEX context_search IF NOT EXISTS
        FOR (n:Module|Package|Type|Method|Endpoint|ExternalType|ContextNote)
        ON EACH [n.ctxSummary, n.ctxFailureModes, n.ctxResponsibilities, n.ctxRole];
      `));

      contextSummary =
        `${applied} node(s) annotated by ${author}` +
        (staleCount ? `, ${staleCount} STALE` : '') +
        (unmatched ? `, ${unmatched} skipped (no matching graph node)` : '') +
        (notes.length ? `, ${notes.length} cross-cutting note(s)` : '');
    }

    console.log(`Graph Forge: loaded ${modules.length} module(s), ${types.length} type(s), ${inheritanceRows.length} inheritance edge(s), ${endpointRows.length} endpoint(s), ${usesRows.length} uses edge(s), ${callRows.length} method-call edge(s) into Neo4j.`);
    console.log(`Graph Forge: context — ${contextSummary}.`);
    if (descriptions && contextSummary.includes('STALE')) {
      console.log('  Stale descriptions were written against older code. Re-run Context Weaver to refresh them.');
    }
  } finally {
    await session.close();
    await driver.close();
  }
}

main().catch((err) => {
  console.error('Graph Forge failed:', err.message);
  process.exit(1);
});
