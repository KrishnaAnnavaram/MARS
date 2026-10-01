#!/usr/bin/env node
/**
 * Version Migration — Step 1: Baseline detection.
 *
 * Read-only inventory of what the project runs on *today* and what the machine can offer:
 * declared language level, build tool, framework/platform coordinates, dependency list,
 * container and CI files, and every JDK installed locally. Also suggests which reference
 * pack in `references/` covers the jump, by matching each pack's own `detect:` entries
 * against the project's coordinates.
 *
 * It also records, additively, what the migration will have to reason about before anything
 * changes: the requested target (exactly as requested — never "latest"), whether the project sits
 * on the line the reference pack expects a migration to start from, ecosystem BOMs whose
 * compatibility cannot be proven locally, the transformations the pack makes available, and a
 * file-level scan for the source surfaces the pack says matter. That scan is a regular-expression
 * pass over source text, labelled as such — it is a list of places to read, not a Java model.
 *
 * Writes `.github/.pipeline-context/version-migration/<slug>/baseline.json`.
 * Touches nothing in the project.
 *
 * Usage:
 *   node scripts/detect-baseline.js --project ../../../../spring-boot-3-to-4-migration-demo-master
 *   node scripts/detect-baseline.js --project <path> --slug spring-boot-3-to-4 --to-java 21
 *   node scripts/detect-baseline.js --project <path> --slug spring-boot-3-to-4 --to-java 21 --to-version 4.1.1
 *   node scripts/detect-baseline.js --issue ISSUE-005     # Stage 2 of 04_fix-generator: request read from the Approved plan
 *
 * A reference pack is used only when it is *eligible*: a detect entry nominates it, and the
 * project's declared platform version proves the source sits in the pack's `from` generation. When
 * no eligible pack covers the jump — a Boot 2.x source facing a 3→4 pack, say — the session is
 * recorded BLOCKED with the required path and the missing capability (UNSUPPORTED_MIGRATION_PATH),
 * and nothing downstream runs. Every invocation ends by writing the per-run summary.
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, sessionPaths, slugify, rel, writeJson, readJson,
  inventoryProject, findAncillaryFiles, resolveBuildTool, installedJdks, resolveJdk, runTool, envForJdk,
  declaredPlatformVersion, recordState,
} = require('./lib/migration');
const {
  listReferencePacks, resolveReferencePack, transformationProblems,
  assessReferencePacks, packEligibility, requiredMigrationPath,
} = require('./lib/references');
const { readMigrationPlan, gateMigrationPlan, approvalMode } = require('./lib/handoff');
const { noteSession, newRunId, runAndFinalize } = require('./lib/summary');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--project' || a === '-p') args.project = argv[++i];
    else if (a === '--slug' || a === '-s') args.slug = argv[++i];
    else if (a === '--reference' || a === '-r') args.reference = argv[++i];
    else if (a === '--from-java') args.fromJava = argv[++i];
    else if (a === '--to-java') args.toJava = argv[++i];
    else if (a === '--to-version') args.toVersion = argv[++i];
    else if (a === '--issue' || a === '-i') args.issue = argv[++i];
    else if (a === '--help' || a === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Version Migration — Baseline detection

  node scripts/detect-baseline.js --project <path-to-project>

Options:
  --project, -p    Project directory (absolute, or relative to the current directory).
                   Omitted: searches the repo root and its parent for a build descriptor.
  --slug, -s       Session name. Default: derived from the project directory name.
  --reference, -r  Force a reference pack id instead of auto-matching.
  --from-java      Record the source Java version explicitly (default: what the build declares).
  --to-java        Record the target Java version this migration is aiming at.
  --to-version     Record the exact platform version requested (e.g. 4.1.1). Never inferred:
                   without it the target is recorded as unresolved.
  --issue, -i      Stage 2 of 04_fix-generator: read the project, target version and target Java
                   from docs/agent_output/04-remediation/fix_plan_<ID>.md. Refused unless that plan
                   reads Status: Approved and Fix Type: VERSION_MIGRATION. The slug defaults to the id.
  --help, -h       Show this message`);
}

// ---------------------------------------------------------------------------
// Source scan — deterministic, regex-based, concise
// ---------------------------------------------------------------------------

const SOURCE_DIRS = [
  ['main', 'src/main/java'], ['main', 'src/main/kotlin'],
  ['test', 'src/test/java'], ['test', 'src/test/kotlin'],
];
const MAX_LINES_PER_FILE = 5;

function listSourceFiles(projectDir) {
  const out = [];
  for (const [scope, dir] of SOURCE_DIRS) {
    const root = path.join(projectDir, ...dir.split('/'));
    const walk = (current) => {
      let entries = [];
      try { entries = fs.readdirSync(current, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        const full = path.join(current, entry.name);
        if (entry.isDirectory()) walk(full);
        else if (/\.(java|kt)$/.test(entry.name)) out.push({ scope, file: rel(full, projectDir), full });
      }
    };
    walk(root);
  }
  return out.sort((a, b) => a.file.localeCompare(b.file));
}

function compilePatterns(patterns) {
  return patterns.map((p) => {
    try { return { source: p, re: new RegExp(p) }; } catch { return { source: p, re: null, invalid: true }; }
  });
}

/** Files matching each pack-declared surface, with the first few matching lines as evidence. */
function scanSurfaces(sources, surfaces) {
  return surfaces.map((surface) => {
    const compiled = compilePatterns(surface.patterns);
    const files = [];
    for (const src of sources) {
      if (surface.scope !== 'all' && surface.scope !== src.scope) continue;
      let text;
      try { text = fs.readFileSync(src.full, 'utf8'); } catch { continue; }
      const lines = text.split(/\r?\n/);
      const hits = [];
      lines.forEach((line, i) => {
        if (hits.length >= MAX_LINES_PER_FILE) return;
        if (compiled.some((c) => c.re && c.re.test(line))) hits.push({ line: i + 1, text: line.trim().slice(0, 160) });
      });
      if (hits.length) files.push({ file: src.file, scope: src.scope, matches: hits });
    }
    return {
      id: surface.id,
      label: surface.label,
      rule: surface.rule,
      invalid_patterns: compiled.filter((c) => c.invalid).map((c) => c.source),
      files,
    };
  });
}

function entryPoints(sources) {
  return sources
    .filter((s) => s.scope === 'main')
    .filter((s) => {
      try { return /public\s+static\s+void\s+main\s*\(|^\s*fun\s+main\s*\(/m.test(fs.readFileSync(s.full, 'utf8')); } catch { return false; }
    })
    .map((s) => s.file);
}

function configFiles(projectDir) {
  const out = [];
  for (const scope of ['main', 'test']) {
    const dir = path.join(projectDir, 'src', scope, 'resources');
    let entries = [];
    try { entries = fs.readdirSync(dir); } catch { continue; }
    for (const name of entries) {
      const m = /^(application|bootstrap)(?:-([\w.\-]+))?\.(ya?ml|properties)$/.exec(name);
      if (m) out.push({ file: `src/${scope}/resources/${name}`, scope, profile: m[2] || 'default' });
    }
  }
  return out;
}

function containerRuntime(projectDir, ancillary) {
  const out = [];
  for (const file of ancillary.filter((f) => /(^|\/)Dockerfile[^/]*$/i.test(f))) {
    let text = '';
    try { text = fs.readFileSync(path.join(projectDir, file), 'utf8'); } catch { continue; }
    text.split(/\r?\n/).forEach((line, i) => {
      const m = /^\s*FROM\s+(\S+)/i.exec(line);
      if (!m) return;
      const tag = m[1].split(':')[1] || '';
      const java = (/(?:^|[^0-9])(\d{1,2})(?:[.\-_]|$)/.exec(tag) || [, null])[1];
      out.push({ file, line: i + 1, image: m[1], java_guess: java });
    });
  }
  return out;
}

function ciJavaReferences(projectDir, ancillary) {
  const out = [];
  for (const file of ancillary.filter((f) => !/Dockerfile/i.test(f))) {
    let text = '';
    try { text = fs.readFileSync(path.join(projectDir, file), 'utf8'); } catch { continue; }
    text.split(/\r?\n/).forEach((line, i) => {
      if (/java-version|java_version|jdk\s*['"]?\s*\d|openjdk|temurin|\bjava\s*=\s*\d|JAVA_VERSION|jdk-?\d{2}/i.test(line)) {
        out.push({ file, line: i + 1, text: line.trim().slice(0, 160) });
      }
    });
  }
  return out.slice(0, 40);
}

function pinnedDependencies(inventory) {
  return (inventory.dependencies || [])
    .filter((d) => !d.managed && d.scope !== 'import')
    .map((d) => ({ coordinate: `${d.groupId}:${d.artifactId}`, version: d.version, declared: d.declaredVersion || d.version, scope: d.scope }));
}

function platformBoms(inventory) {
  const out = [];
  if (inventory.parent) out.push({ coordinate: `${inventory.parent.groupId}:${inventory.parent.artifactId}`, version: inventory.parent.version, kind: 'parent' });
  for (const d of inventory.dependencies || []) {
    if (d.scope === 'import') out.push({ coordinate: `${d.groupId}:${d.artifactId}`, version: d.version, kind: 'bom-import' });
  }
  return out;
}

function collectObservations(projectDir, inventory, ancillary, pack) {
  const sources = listSourceFiles(projectDir);
  const surfaces = pack ? scanSurfaces(sources, pack.surfaces || []) : [];
  const container = containerRuntime(projectDir, ancillary);
  const sensitive = new Set();
  for (const s of surfaces) for (const f of s.files) sensitive.add(f.file);
  if (inventory.descriptor) sensitive.add(inventory.descriptor);
  for (const c of container) sensitive.add(c.file);
  return {
    basis: 'machine-detected: file names and a regular-expression scan of source text. Not a semantic Java model — every entry is a place to read, not a conclusion.',
    entry_points: entryPoints(sources),
    config_files: configFiles(projectDir),
    pinned_dependencies: pinnedDependencies(inventory),
    platform_boms: platformBoms(inventory),
    container_runtime: container,
    ci_java_references: ciJavaReferences(projectDir, ancillary),
    pack_surfaces: surfaces,
    migration_sensitive_files: [...sensitive].sort(),
  };
}

/**
 * Where the project stands relative to the pack's expected path and the requested target.
 * Everything here is an observation or a question for the plan — nothing is changed because of it.
 */
function migrationPath(pack, inventory, request) {
  if (!pack) return null;
  const platform = declaredPlatformVersion(inventory, pack.detect);
  const warnings = [];
  const unresolved = [];
  const prep = pack.migration_path && pack.migration_path.preparation_line;
  const onPrep = platform && prep ? String(platform.version).startsWith(prep) : null;
  if (onPrep === false) {
    warnings.push(`source ${platform.coordinate} ${platform.version} is not on the ${prep}.x preparation line the pack expects — a planning input, not an automatic upgrade`);
  }
  if (!request.platformVersion) {
    unresolved.push('no exact target platform version was requested (--to-version); the target is not inferred');
  } else if (pack.to && !String(request.platformVersion).startsWith(String(pack.to))) {
    warnings.push(`requested target ${request.platformVersion} is outside the pack's "${pack.to}" generation`);
  }
  const tc = pack.target_constraints || {};
  if (request.language && tc.language_min && Number(request.language) < Number(tc.language_min)) {
    warnings.push(`requested Java ${request.language} is below the pack's language_min ${tc.language_min}`);
  }
  const props = inventory.properties || {};
  const present = [];
  for (const bom of pack.ecosystem_boms || []) {
    const [g, a] = String(bom.coordinate || '').split(':');
    const dep = (inventory.dependencies || []).find((d) => d.groupId === g && d.artifactId === a);
    const propValue = bom.property ? props[bom.property] : undefined;
    if (dep || propValue !== undefined) {
      const version = (dep && dep.version) || propValue || null;
      present.push({ coordinate: bom.coordinate, version, note: bom.note || null });
      unresolved.push(`compatibility of ${bom.coordinate}${version ? ` ${version}` : ''} with ${pack.stack} ${request.platformVersion || pack.to} is not proven locally — ${bom.note || 'verify before migrating'}`);
    }
  }
  return {
    pack: pack.id,
    platform,
    source_line: platform ? String(platform.version).split('.').slice(0, 2).join('.') : null,
    preparation_line: prep || null,
    on_preparation_line: onPrep,
    requested_target: request.platformVersion || null,
    ecosystem_boms_present: present,
    warnings,
    unresolved,
  };
}

function capabilities(pack, tool) {
  return {
    transformations: ((pack && pack.transformations) || []).map((t) => ({
      id: t.id,
      provider: t.provider,
      policy: t.policy,
      recipes: t.recipes,
      artifacts: t.artifacts,
      plugin_version: t.plugin_version,
      gradle_plugin_version: t.gradle_plugin_version,
      config: t.config ? `references/${t.config}` : null,
      license: t.license,
      recipe_target: t.recipe_target,
      build_tool_supported: !t.build_tools.length || t.build_tools.includes(tool.tool),
      configuration_problems: transformationProblems(t),
      availability: 'not checked — decided by a dry-run in the sandbox',
    })),
  };
}

function autoDetectProject() {
  const candidates = [REPO_ROOT, path.resolve(REPO_ROOT, '..')];
  const found = [];
  for (const root of candidates) {
    if (!fs.existsSync(root)) continue;
    for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const dir = path.join(root, entry.name);
      if (['pom.xml', 'build.gradle', 'build.gradle.kts'].some((f) => fs.existsSync(path.join(dir, f)))) found.push(dir);
    }
    if (['pom.xml', 'build.gradle', 'build.gradle.kts'].some((f) => fs.existsSync(path.join(root, f)))) found.push(root);
  }
  return [...new Set(found)];
}

function countSources(projectDir) {
  const counts = { main: 0, test: 0, resources: 0 };
  const walk = (dir, bucket) => {
    if (!fs.existsSync(dir)) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full, bucket);
      else if (/\.(java|kt|groovy)$/.test(entry.name)) counts[bucket] += 1;
      else if (/\.(ya?ml|properties|xml|sql)$/.test(entry.name)) counts.resources += 1;
    }
  };
  walk(path.join(projectDir, 'src', 'main'), 'main');
  walk(path.join(projectDir, 'src', 'test'), 'test');
  return counts;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  // Stage 2 of 04_fix-generator: the request comes from the Approved plan, never from the caller.
  let issue = null;
  if (args.issue) {
    const plan = readMigrationPlan(args.issue);
    const refusal = gateMigrationPlan(plan);
    const issueSlug = slugify(args.slug || args.issue);
    noteSession(issueSlug);
    if (refusal) {
      writeJson(sessionPaths(issueSlug).baseline, {
        slug: issueSlug, generated_at: new Date().toISOString(), run_id: newRunId(issueSlug), project: null,
        issue: { id: args.issue, plan: plan ? plan.relativeFile : null, fix_type: plan ? plan.fixType : null, refused: refusal },
      });
      startRun(issueSlug, 'BLOCKED', `Stage 2 refused for ${args.issue}: ${refusal}`);
      console.error(`Refused — ${args.issue}: ${refusal}`);
      process.exitCode = 1;
      return;
    }
    const request = plan.request;
    issue = {
      id: plan.id, plan: plan.relativeFile, fix_type: plan.fixType, cwe: plan.cwe, status: plan.status,
      approval_mode: approvalMode(plan), routing_evidence: plan.routingEvidence, request,
    };
    args.project = path.join(REPO_ROOT, request.project);
    args.toVersion = request.target_version;
    if (request.target_java) args.toJava = String(request.target_java);
    args.slug = issueSlug;
    console.log(`[04D] Routed by Agent 04 for ${plan.id} (Fix Type ${plan.fixType}, plan Status ${plan.status}, approval: ${issue.approval_mode})`);
  }

  let projectDir = args.project ? path.resolve(args.project) : null;
  if (!projectDir) {
    const found = autoDetectProject();
    if (found.length !== 1) {
      console.error('Could not decide which project to migrate. Pass --project <path>.');
      if (found.length) found.forEach((f) => console.error(`  candidate: ${f}`));
      process.exitCode = 1;
      return;
    }
    [projectDir] = found;
  }
  if (!fs.existsSync(projectDir)) {
    console.error(`Project directory not found: ${projectDir}`);
    process.exitCode = 1;
    return;
  }

  const inventory = inventoryProject(projectDir);
  if (inventory.buildTool === 'unknown') {
    console.error(`No pom.xml, build.gradle or build.gradle.kts under ${projectDir} — nothing to migrate.`);
    process.exitCode = 1;
    return;
  }

  const slug = slugify(args.slug || path.basename(projectDir));
  noteSession(slug);
  const buildTool = resolveBuildTool(projectDir);
  const jdks = installedJdks();
  const declaredJava = args.fromJava || inventory.javaVersion || null;

  // A detect entry only nominates a pack; the declared source version decides eligibility — even
  // for a pack forced with --reference.
  let packs;
  let eligibility;
  if (args.reference) {
    const forced = resolveReferencePack(args.reference);
    if (!forced) {
      console.error(`No reference pack with id "${args.reference}" in ${rel(path.join(__dirname, '..', 'references'))}.`);
      process.exitCode = 1;
      return;
    }
    const e = packEligibility(forced, inventory);
    eligibility = [e];
    packs = e.eligible || e.relation === 'unchecked' ? [{ ...forced, matched: e.matched_on, eligibility: e }] : [];
  } else {
    const assessed = assessReferencePacks(inventory);
    packs = assessed.eligible;
    eligibility = [...assessed.eligible.map((p) => p.eligibility), ...assessed.ineligible];
  }
  const pathGate = migrationPathGate(packs, eligibility, args.toVersion);

  const targetJava = args.toJava || (packs[0] && packs[0].language_to) || null;
  const fromJdk = declaredJava ? resolveJdk(declaredJava) : null;
  const toJdk = targetJava ? resolveJdk(targetJava) : null;

  let buildToolVersion = null;
  if (buildTool.command) {
    const probe = runTool(buildTool.command, ['-v'], { cwd: projectDir, env: envForJdk(fromJdk) });
    buildToolVersion = (probe.stdout || probe.stderr || '').split(/\r?\n/)[0].trim() || null;
  }

  const baseline = {
    slug,
    generated_at: new Date().toISOString(),
    run_id: newRunId(slug),
    project: {
      dir: projectDir.split(path.sep).join('/'),
      relative_to_repo: rel(projectDir),
      name: (inventory.coordinates && inventory.coordinates.name) || path.basename(projectDir),
      descriptor: inventory.descriptor,
      coordinates: inventory.coordinates || {},
      sources: countSources(projectDir),
      ancillary_files: findAncillaryFiles(projectDir),
    },
    build_tool: { ...buildTool, version: buildToolVersion },
    language: {
      name: 'Java',
      declared: declaredJava,
      target: targetJava,
      from_jdk: fromJdk ? { major: fromJdk.major, version: fromJdk.version, home: fromJdk.home } : null,
      to_jdk: toJdk ? { major: toJdk.major, version: toJdk.version, home: toJdk.home } : null,
    },
    platform: {
      parent: inventory.parent,
      properties: inventory.properties || {},
      dependencies: inventory.dependencies,
      plugins: inventory.plugins,
    },
    toolchain: { installed_jdks: jdks.map(({ home, major, version, source }) => ({ home, major, version, source })) },
    reference_packs: packs.map((p) => ({
      id: p.id, title: p.title, stack: p.stack, from: p.from, to: p.to,
      language_from: p.language_from, language_to: p.language_to,
      file: `references/${p.file}`, matched_on: p.matched || [],
    })),
    // Every pack a detect entry nominated, eligible or not, and why — the source version decides.
    reference_pack_eligibility: eligibility,
    ...(issue ? { issue } : {}),
  };

  // --- v2 additions. Every field above keeps its v1 name and shape; everything below is new. ---
  const pack = packs[0] || null;
  const nominated = eligibility.find((e) => e.relation === 'behind') || eligibility.find((e) => e.nominated) || null;
  baseline.target = {
    platform: {
      name: pack ? pack.stack : (nominated ? nominated.stack : null),
      version: args.toVersion || null,
      source: args.toVersion ? 'request' : 'unresolved',
    },
    language: {
      name: 'Java',
      version: targetJava,
      source: args.toJava ? 'request' : (targetJava ? 'reference-pack-default' : 'unresolved'),
    },
    constraints: pack ? pack.target_constraints : null,
  };
  baseline.migration_path = pack
    ? { ...migrationPath(pack, inventory, { platformVersion: args.toVersion, language: targetJava }), ...pathGate }
    : {
      pack: null,
      stack: nominated ? nominated.stack : null,
      platform: nominated ? nominated.source_platform : null,
      source: nominated && nominated.source_platform ? nominated.source_platform.version : null,
      requested_target: args.toVersion || null,
      warnings: [],
      unresolved: [],
      ...pathGate,
    };
  baseline.observations = collectObservations(projectDir, inventory, baseline.project.ancillary_files, pack);
  baseline.capabilities = capabilities(pack, buildTool);
  baseline.reference_provenance = pack ? pack.provenance : null;

  const out = writeJson(sessionPaths(slug).baseline, baseline);
  const blocked = pathGate.status !== 'SUPPORTED';
  if (blocked) startRun(slug, 'BLOCKED', `${pathGate.status}: ${pathGate.reason}`);
  else startRun(slug, 'BASELINE_DETECTED', `project ${baseline.project.relative_to_repo}`);

  const line = (label, value) => console.log(`  ${label.padEnd(20)} ${value}`);
  console.log(`\nBaseline — ${baseline.project.name}`);
  console.log(`  ${'-'.repeat(60)}`);
  line('Project', baseline.project.relative_to_repo);
  line('Build tool', `${buildTool.tool} (${buildTool.kind})${buildToolVersion ? ` — ${buildToolVersion}` : ''}`);
  line('Descriptor', inventory.descriptor || 'unknown');
  line('Java declared', declaredJava || 'unknown');
  line('Java target', targetJava || 'not set (pass --to-java)');
  if (inventory.parent) line('Platform parent', `${inventory.parent.groupId}:${inventory.parent.artifactId}:${inventory.parent.version}`);
  line('Dependencies', `${inventory.dependencies.length} declared (${inventory.dependencies.filter((d) => d.managed).length} version-managed)`);
  line('Sources', `${baseline.project.sources.main} main / ${baseline.project.sources.test} test`);
  line('Ancillary', baseline.project.ancillary_files.join(', ') || 'none');
  line('JDKs available', jdks.map((j) => `${j.major} (${j.version})`).join(', ') || 'none found');
  console.log(`\n  Reference packs eligible (a detect entry nominates; the declared source version decides):`);
  for (const e of eligibility.filter((x) => !x.eligible)) console.log(`    ✗ ${e.pack} — NOT ELIGIBLE: ${e.reason}`);
  if (!baseline.reference_packs.length) {
    const all = listReferencePacks();
    console.log('    none — no pack in references/ is eligible for this project.');
    console.log(`    Available: ${all.map((p) => p.id).join(', ') || '(none)'}`);
    console.log('    Write a pack for this jump before migrating; do not migrate from memory.');
  } else {
    for (const p of baseline.reference_packs) {
      console.log(`    ${p.id} — ${p.title}`);
      console.log(`      ${p.file}  (matched on ${p.matched_on.join(', ')}; ${packs.find((x) => x.id === p.id).eligibility.reason})`);
    }
  }
  if (declaredJava && !baseline.language.from_jdk) console.log(`\n  ! No JDK ${declaredJava} found locally — the baseline build cannot run on the declared version.`);
  if (targetJava && !baseline.language.to_jdk) console.log(`  ! No JDK ${targetJava} found locally — install it before starting the migration rounds.`);

  const mp = baseline.migration_path;
  if (mp) {
    console.log(`\n  Migration path (observations for the plan — nothing is changed because of them):`);
    line('Source platform', mp.platform ? `${mp.platform.coordinate} ${mp.platform.version}` : 'not detected');
    line('Requested target', mp.requested_target || 'NOT GIVEN — pass --to-version; the target is never inferred');
    if (mp.preparation_line) line('Preparation line', `${mp.preparation_line}.x — ${mp.on_preparation_line ? 'on it' : 'NOT on it'}`);
    for (const w of mp.warnings) console.log(`    ! ${w}`);
    for (const u of mp.unresolved) console.log(`    ? ${u}`);
  }
  const obs = baseline.observations;
  if (obs.pack_surfaces.length) {
    console.log(`\n  Source surfaces the pack flags (regex scan — places to read, not conclusions):`);
    for (const s of obs.pack_surfaces) console.log(`    ${String(s.files.length).padStart(3)} file(s)  ${s.label}${s.rule ? ` — ${s.rule}` : ''}`);
  }
  if (obs.pinned_dependencies.length) line('Pinned versions', obs.pinned_dependencies.map((d) => `${d.coordinate}:${d.version}`).join(', '));
  if (baseline.capabilities.transformations.length) {
    console.log(`\n  Deterministic transformations this pack offers (select them in migration-plan.json):`);
    for (const t of baseline.capabilities.transformations) {
      console.log(`    ${t.id} — ${t.provider} ${t.recipes.join(', ')} (${t.policy}${t.license ? `, ${t.license}` : ''})`);
      for (const p of t.configuration_problems) console.log(`      ! ${p}`);
    }
  }
  console.log(`\n  Migration path gate: ${pathGate.status}`);
  for (const st of (pathGate.required_path && pathGate.required_path.steps) || []) {
    console.log(`    ${st.from} → ${st.to}   ${st.capability}   ${st.available ? 'available' : 'MISSING'}`);
  }
  console.log(`\n  Written: ${rel(out)}`);
  if (blocked) {
    console.log(`\n  BLOCKED — ${pathGate.reason}`);
    if ((pathGate.missing_capability || []).length) console.log(`  Missing capability: ${pathGate.missing_capability.join(', ')}`);
    console.log('  Nothing downstream may run for this session. Add the missing reference pack(s) first; never migrate across a generation from memory.\n');
    process.exitCode = 2;
    return;
  }
  console.log(`  Next:    node scripts/prepare-workspace.js --slug ${slug}\n`);
}

/** Records the session's first state for this run and stamps the run id the per-run summary is filed under. */
function startRun(slug, state, detail) {
  const paths = sessionPaths(slug);
  const baseline = readJson(paths.baseline) || {};
  const current = recordState(slug, state, 'detect-baseline.js', detail);
  current.run_id = baseline.run_id || newRunId(slug);
  current.run_started_at = baseline.generated_at || new Date().toISOString();
  writeJson(paths.state, current);
}

/**
 * Can one session make the requested jump? A pack covers exactly one generation step; a jump that
 * needs a step no pack covers is UNSUPPORTED_MIGRATION_PATH, and a jump across several covered
 * steps is MULTI_STEP_REQUIRED (one session per step). Either way nothing is silently skipped.
 */
function migrationPathGate(packs, eligibility, toVersion) {
  const pack = packs[0];
  const describe = (p) => p.steps.map((st) => `${st.from} → ${st.to} (${st.capability}${st.available ? '' : ': MISSING'})`).join(', ');
  if (pack) {
    const src = pack.eligibility && pack.eligibility.source_platform ? pack.eligibility.source_platform.version : null;
    const reqPath = src && toVersion ? requiredMigrationPath(pack.stack, src, toVersion, pack.id) : null;
    if (!reqPath || reqPath.steps.length <= 1) {
      return { status: 'SUPPORTED', reason: `${pack.id} is eligible${src && toVersion ? ` for ${src} → ${toVersion}` : ''}`, required_path: reqPath, missing_capability: [] };
    }
    if (reqPath.missing_capability.length) {
      return { status: 'UNSUPPORTED_MIGRATION_PATH', reason: `${src} → ${toVersion} needs ${describe(reqPath)}`, required_path: reqPath, missing_capability: reqPath.missing_capability };
    }
    return { status: 'MULTI_STEP_REQUIRED', reason: `${src} → ${toVersion} spans ${reqPath.steps.length} generations — run one session per step: ${describe(reqPath)}`, required_path: reqPath, missing_capability: [] };
  }
  const behind = eligibility.find((e) => e.relation === 'behind');
  if (behind) {
    const src = behind.source_platform.version;
    const target = toVersion || `${behind.pack_to}.x`;
    const reqPath = requiredMigrationPath(behind.stack, src, target, behind.pack);
    const missing = reqPath ? reqPath.missing_capability : [];
    return {
      status: missing.length ? 'UNSUPPORTED_MIGRATION_PATH' : 'MULTI_STEP_REQUIRED',
      reason: `${behind.reason}; reaching ${target} requires ${reqPath ? describe(reqPath) : 'an unknown path'}`,
      required_path: reqPath,
      missing_capability: missing,
    };
  }
  const other = eligibility.find((e) => e.nominated);
  if (other) return { status: 'NO_ELIGIBLE_PACK', reason: other.reason, required_path: null, missing_capability: [] };
  return { status: 'NO_MATCHING_PACK', reason: 'no reference pack recognises this project — write one for the jump first', required_path: null, missing_capability: [] };
}

if (require.main === module) runAndFinalize(main, 'detect-baseline.js');

module.exports = {
  parseArgs, listSourceFiles, scanSurfaces, entryPoints, configFiles, containerRuntime, ciJavaReferences,
  pinnedDependencies, platformBoms, collectObservations, migrationPath, capabilities,
};
