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
 * Writes `.github/.pipeline-context/version-migration/<slug>/baseline.json`.
 * Touches nothing in the project.
 *
 * Usage:
 *   node scripts/detect-baseline.js --project ../../../../spring-boot-3-to-4-migration-demo-master
 *   node scripts/detect-baseline.js --project <path> --slug spring-boot-3-to-4 --to-java 21
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, sessionPaths, slugify, rel, writeJson,
  inventoryProject, findAncillaryFiles, resolveBuildTool, installedJdks, resolveJdk, runTool, envForJdk,
} = require('./lib/migration');
const { listReferencePacks, matchReferencePacks, resolveReferencePack } = require('./lib/references');

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--project' || a === '-p') args.project = argv[++i];
    else if (a === '--slug' || a === '-s') args.slug = argv[++i];
    else if (a === '--reference' || a === '-r') args.reference = argv[++i];
    else if (a === '--from-java') args.fromJava = argv[++i];
    else if (a === '--to-java') args.toJava = argv[++i];
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
  --help, -h       Show this message`);
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
  const buildTool = resolveBuildTool(projectDir);
  const jdks = installedJdks();
  const declaredJava = args.fromJava || inventory.javaVersion || null;

  const packs = args.reference
    ? [resolveReferencePack(args.reference)].filter(Boolean)
    : matchReferencePacks(inventory);
  if (args.reference && !packs.length) {
    console.error(`No reference pack with id "${args.reference}" in ${rel(path.join(__dirname, '..', 'references'))}.`);
    process.exitCode = 1;
    return;
  }

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
  };

  const out = writeJson(sessionPaths(slug).baseline, baseline);

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
  console.log(`\n  Reference packs matched:`);
  if (!baseline.reference_packs.length) {
    const all = listReferencePacks();
    console.log('    none — no pack in references/ recognises this project.');
    console.log(`    Available: ${all.map((p) => p.id).join(', ') || '(none)'}`);
    console.log('    Write a pack for this jump before migrating; do not migrate from memory.');
  } else {
    for (const p of baseline.reference_packs) {
      console.log(`    ${p.id} — ${p.title}`);
      console.log(`      ${p.file}  (matched on ${p.matched_on.join(', ')})`);
    }
  }
  if (declaredJava && !baseline.language.from_jdk) console.log(`\n  ! No JDK ${declaredJava} found locally — the baseline build cannot run on the declared version.`);
  if (targetJava && !baseline.language.to_jdk) console.log(`  ! No JDK ${targetJava} found locally — install it before starting the migration rounds.`);
  console.log(`\n  Written: ${rel(out)}`);
  console.log(`  Next:    node scripts/prepare-workspace.js --slug ${slug}\n`);
}

main();
