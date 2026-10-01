/**
 * Fix Strategist — Stage 2 routing.
 *
 * Every plan carries one Fix Type, and Stage 2 of 04_fix-generator routes on it:
 *
 *   CODE_FIX            -> 04b-fixer               (a logic change for a diagnosed defect)
 *   DEPENDENCY_UPGRADE  -> 04c-dependency-upgrader (one coordinate bumped to a fixed version, CWE-1104)
 *   VERSION_MIGRATION   -> 04d-version-migration   (a coordinated framework-generation and/or Java jump)
 *
 * The classification is not a keyword match. A plan is a VERSION_MIGRATION only when the strategy
 * records explicit migration intent (`version_migration`) AND the evidence on disk agrees: the
 * project's build descriptor really declares the platform coordinate at the stated source version,
 * and the request really changes the platform's major generation or the Java level. A strategy
 * that claims a migration the evidence does not support is a validation error, never a silent
 * re-route. Conversely, a `dependency_upgrade` that would move a platform parent/BOM across a major
 * generation is refused: that is a coordinated migration, not a single-coordinate bump.
 */
const fs = require('fs');
const path = require('path');

const FIX_TYPES = {
  CODE_FIX: { skill: '04b-fixer' },
  DEPENDENCY_UPGRADE: { skill: '04c-dependency-upgrader' },
  VERSION_MIGRATION: { skill: '04d-version-migration' },
};

const MIGRATION_REQUEST_MARKER = '04d-migration-request';

function majorOf(version) {
  const m = /^\s*v?(\d+)/.exec(String(version === null || version === undefined ? '' : version));
  return m ? Number(m[1]) : null;
}

function stripXmlComments(xml) {
  return xml.replace(/<!--[\s\S]*?-->/g, '');
}

function tag(block, name) {
  const m = new RegExp(`<${name}>\\s*([^<]*?)\\s*</${name}>`).exec(block);
  return m ? m[1] : null;
}

/** Resolves ${prop} against the pom's own <properties>. */
function resolveProps(value, props) {
  return value ? value.replace(/\$\{([^}]+)\}/g, (all, key) => (props[key] !== undefined ? props[key] : all)) : value;
}

/**
 * Where a Maven descriptor declares a coordinate's version: the <parent>, a <dependency> (including
 * a BOM import inside <dependencyManagement>) or a <plugin>. Small and deliberately literal — it
 * reads the descriptor as written, it does not build an effective POM.
 */
function declaredCoordinates(pomText) {
  const xml = stripXmlComments(pomText);
  const props = {};
  const propsBlock = /<properties>([\s\S]*?)<\/properties>/.exec(xml);
  if (propsBlock) {
    for (const m of propsBlock[1].matchAll(/<([A-Za-z0-9_.\-]+)>\s*([^<]*?)\s*<\/\1>/g)) props[m[1]] = m[2];
  }
  const out = [];
  const parent = /<parent>([\s\S]*?)<\/parent>/.exec(xml);
  if (parent) out.push({ where: 'parent', groupId: tag(parent[1], 'groupId'), artifactId: tag(parent[1], 'artifactId'), version: tag(parent[1], 'version') });
  for (const m of xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const scope = tag(m[1], 'scope');
    out.push({ where: scope === 'import' ? 'bom-import' : 'dependency', groupId: tag(m[1], 'groupId'), artifactId: tag(m[1], 'artifactId'), version: resolveProps(tag(m[1], 'version'), props) });
  }
  for (const m of xml.matchAll(/<plugin>([\s\S]*?)<\/plugin>/g)) {
    out.push({ where: 'plugin', groupId: tag(m[1], 'groupId'), artifactId: tag(m[1], 'artifactId'), version: resolveProps(tag(m[1], 'version'), props) });
  }
  const javaVersion = props['java.version'] || props['maven.compiler.release'] || props['maven.compiler.target'] || null;
  return { coordinates: out, javaVersion };
}

/** A parent or BOM that defines a platform generation, as opposed to an ordinary library. */
function isPlatformCoordinate(coordinate, declared) {
  const artifactId = String(coordinate || '').split(':')[1] || '';
  if (/-(starter-parent|parent|dependencies|bom)$/.test(artifactId)) return true;
  return declared.coordinates.some((c) => c.where === 'parent' && `${c.groupId}:${c.artifactId}` === coordinate);
}

function readDescriptor(repoRoot, projectDir) {
  const dir = path.join(repoRoot, projectDir || '.');
  const pom = path.join(dir, 'pom.xml');
  if (fs.existsSync(pom)) return { file: pom, text: fs.readFileSync(pom, 'utf8') };
  return null;
}

const rel = (repoRoot, file) => path.relative(repoRoot, file).replace(/\\/g, '/');

function checkVersionMigration(vm, repoRoot) {
  const evidence = [];
  const errors = [];
  for (const key of ['project', 'platform', 'platform_coordinate', 'source_version', 'target_version']) {
    if (!vm[key] || typeof vm[key] !== 'string') errors.push(`version_migration.${key} is required`);
  }
  if (errors.length) return { evidence, errors };
  evidence.push(`Stage 1 recorded explicit migration intent: ${vm.platform} ${vm.source_version} → ${vm.target_version}${vm.target_java ? `, Java ${vm.source_java || '?'} → ${vm.target_java}` : ''}${vm.reason ? ` — ${vm.reason}` : ''}`);

  const descriptor = readDescriptor(repoRoot, vm.project);
  if (!descriptor) {
    errors.push(`no pom.xml under version_migration.project "${vm.project}" — the migration has nothing to act on`);
    return { evidence, errors };
  }
  const declared = declaredCoordinates(descriptor.text);
  const [g, a] = vm.platform_coordinate.split(':');
  const hit = declared.coordinates.find((c) => c.groupId === g && c.artifactId === a && c.version);
  if (!hit) {
    errors.push(`${rel(repoRoot, descriptor.file)} does not declare ${vm.platform_coordinate} with a version — the source platform is not proven`);
  } else if (hit.version !== vm.source_version) {
    errors.push(`${rel(repoRoot, descriptor.file)} declares ${vm.platform_coordinate} ${hit.version} (${hit.where}), not the stated source_version ${vm.source_version}`);
  } else {
    evidence.push(`${rel(repoRoot, descriptor.file)} declares ${vm.platform_coordinate} ${hit.version} as its ${hit.where} — source platform proven`);
  }
  if (vm.source_java && declared.javaVersion && String(declared.javaVersion) !== String(vm.source_java)) {
    errors.push(`${rel(repoRoot, descriptor.file)} declares Java ${declared.javaVersion}, not the stated source_java ${vm.source_java}`);
  } else if (declared.javaVersion) {
    evidence.push(`${rel(repoRoot, descriptor.file)} declares Java ${declared.javaVersion}`);
  }

  const majorJump = majorOf(vm.target_version) !== null && majorOf(vm.target_version) !== majorOf(vm.source_version);
  const javaJump = Boolean(vm.target_java) && String(vm.target_java) !== String(vm.source_java || declared.javaVersion || '');
  if (majorJump) evidence.push(`major framework generation changes: ${majorOf(vm.source_version)}.x → ${majorOf(vm.target_version)}.x`);
  if (javaJump) evidence.push(`Java target changes: ${vm.source_java || declared.javaVersion || '?'} → ${vm.target_java}`);
  if (!majorJump && !javaJump) {
    errors.push(`${vm.source_version} → ${vm.target_version} stays in one generation and does not change Java — that is a dependency_upgrade (04c), not a version migration`);
  }
  return { evidence, errors };
}

function checkDependencyUpgrade(du, strategy, repoRoot) {
  const evidence = [`Stage 1 recorded a single-coordinate upgrade: ${du.maven_coordinate} ${du.current_version} → ≥${du.minimum_fixed_version}`];
  const errors = [];
  const projects = [...new Set((strategy.affected_files || [])
    .map((f) => String(f.file || ''))
    .filter((f) => /(^|\/)pom\.xml$/.test(f))
    .map((f) => path.posix.dirname(f)))];
  for (const project of projects) {
    const descriptor = readDescriptor(repoRoot, project);
    if (!descriptor) continue;
    const declared = declaredCoordinates(descriptor.text);
    if (isPlatformCoordinate(du.maven_coordinate, declared) && majorOf(du.minimum_fixed_version) !== majorOf(du.current_version)) {
      errors.push(`${du.maven_coordinate} is the platform parent/BOM of ${project}, and ${du.current_version} → ${du.minimum_fixed_version} crosses a major generation — plan it as version_migration (routes to 04d-version-migration), not as a single-coordinate bump`);
    }
  }
  return { evidence, errors };
}

/**
 * Classifies a strategy into exactly one Fix Type, with the evidence for it. Throws nothing:
 * `errors` non-empty means the strategy must not be rendered as written.
 */
function classifyFixType(strategy, { repoRoot }) {
  const vm = strategy.version_migration;
  const du = strategy.dependency_upgrade;
  if (vm && du) {
    return { fix_type: null, skill: null, evidence: [], errors: ['a strategy carries either version_migration or dependency_upgrade, never both'] };
  }
  if (vm) {
    const { evidence, errors } = checkVersionMigration(vm, repoRoot);
    return { fix_type: 'VERSION_MIGRATION', skill: FIX_TYPES.VERSION_MIGRATION.skill, evidence, errors };
  }
  if (du) {
    const { evidence, errors } = checkDependencyUpgrade(du, strategy, repoRoot);
    return { fix_type: 'DEPENDENCY_UPGRADE', skill: FIX_TYPES.DEPENDENCY_UPGRADE.skill, evidence, errors };
  }
  return {
    fix_type: 'CODE_FIX',
    skill: FIX_TYPES.CODE_FIX.skill,
    evidence: ['no version_migration or dependency_upgrade recorded — a code change for the diagnosed defect'],
    errors: [],
  };
}

/** The machine-readable request 04d reads back from an Approved plan (an HTML comment, invisible when rendered). */
function migrationRequestComment(issueId, vm) {
  const request = {
    issue_id: issueId,
    project: vm.project,
    platform: vm.platform,
    platform_coordinate: vm.platform_coordinate,
    source_version: vm.source_version,
    target_version: vm.target_version,
    source_java: vm.source_java || null,
    target_java: vm.target_java || null,
  };
  return `<!-- ${MIGRATION_REQUEST_MARKER} ${JSON.stringify(request)} -->`;
}

module.exports = {
  FIX_TYPES, MIGRATION_REQUEST_MARKER, classifyFixType, migrationRequestComment, declaredCoordinates, majorOf,
};
