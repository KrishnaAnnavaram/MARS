/**
 * Version Migration — shared paths, toolchain resolution, project inventory,
 * build-output parsing and session state.
 *
 * Everything in this file is deliberately framework-agnostic. It knows how to find a JDK,
 * find a build tool, read what a project currently declares, run a build and classify what
 * came back — it knows nothing about Spring Boot, Jakarta EE, Quarkus or any other stack.
 * All framework-specific knowledge lives in `references/<pack>.md` and is applied by the
 * agent, never hard-coded here. That split is what lets one skill serve every migration.
 *
 * The real project directory is READ-ONLY to every script here. All builds and all edits
 * happen inside the sandbox workspace `prepare-workspace.js` creates under
 * `.github/.pipeline-context/version-migration/<slug>/workspace/`.
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const SKILL_DIR = path.resolve(__dirname, '..', '..');
const REPO_ROOT = path.resolve(SKILL_DIR, '..', '..', '..');
const DATA_DIR = process.env.PIPELINE_CONTEXT_DATA_DIR
  ? path.resolve(process.env.PIPELINE_CONTEXT_DATA_DIR)
  : path.join(REPO_ROOT, '.github', '.pipeline-context');

const PATHS = {
  SKILL_DIR,
  REPO_ROOT,
  DATA_DIR,
  WORK_DIR: path.join(DATA_DIR, 'version-migration'),
  REFERENCES_DIR: path.join(SKILL_DIR, 'references'),
  TEMPLATES_DIR: path.join(SKILL_DIR, 'templates'),
  // Migration reports share 04-remediation/ with the fix reports — one folder for everything
  // 04_fix-generator produces. The file prefix (migration_ vs fix_) keeps them apart, and the
  // shared README carries a separate, self-delimited migration block so 04a/04b's index rewrite
  // and this skill's never overwrite each other. MIGRATION_REPORT_DIR redirects it for a
  // disposable verification run, so a test never writes into the real report folder.
  OUT_DIR: process.env.MIGRATION_REPORT_DIR
    ? path.resolve(process.env.MIGRATION_REPORT_DIR)
    : path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
};
PATHS.OUT_README = path.join(PATHS.OUT_DIR, 'README.md');

// ---------------------------------------------------------------------------
// Session layout
// ---------------------------------------------------------------------------

function slugify(text) {
  return String(text || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60) || 'migration';
}

function sessionDir(slug) {
  return path.join(PATHS.WORK_DIR, slug);
}

const sessionPaths = (slug) => ({
  root: sessionDir(slug),
  baseline: path.join(sessionDir(slug), 'baseline.json'),
  workspaceMeta: path.join(sessionDir(slug), 'workspace.json'),
  workspace: path.join(sessionDir(slug), 'workspace'),
  roundsDir: path.join(sessionDir(slug), 'rounds'),
  runtimeDir: path.join(sessionDir(slug), 'runtime'),
  migration: path.join(sessionDir(slug), 'migration.json'),
  // v2 additions — all optional, so a session written before them still reads.
  probes: path.join(sessionDir(slug), 'probes.json'),
  plan: path.join(sessionDir(slug), 'migration-plan.json'),
  state: path.join(sessionDir(slug), 'state.json'),
  transformationsDir: path.join(sessionDir(slug), 'transformations'),
  reportMd: path.join(PATHS.OUT_DIR, `migration_${slug}.md`),
  reportDiff: path.join(PATHS.OUT_DIR, `migration_${slug}.diff`),
});

function rel(target, from = REPO_ROOT) {
  return path.relative(from, target).split(path.sep).join('/');
}

function readJson(file, fallback = null) {
  if (!fs.existsSync(file)) return fallback;
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${rel(file)} is not valid JSON: ${error.message}`);
  }
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
  return file;
}

function listSessions() {
  if (!fs.existsSync(PATHS.WORK_DIR)) return [];
  return fs
    .readdirSync(PATHS.WORK_DIR, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
}

function listRounds(slug) {
  const dir = sessionPaths(slug).roundsDir;
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((f) => /^round-\d+\.json$/.test(f))
    .map((f) => readJson(path.join(dir, f)))
    .filter(Boolean)
    .sort((a, b) => a.round - b.round);
}

function nextRoundNumber(slug) {
  const rounds = listRounds(slug);
  return rounds.length ? Math.max(...rounds.map((r) => r.round)) + 1 : 1;
}

// ---------------------------------------------------------------------------
// Process helpers
// ---------------------------------------------------------------------------

const IS_WIN = process.platform === 'win32';

function run(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, {
    encoding: 'utf8', shell: false, maxBuffer: 64 * 1024 * 1024, ...options,
  });
  return {
    status: result.status,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
    error: result.error ? result.error.message : null,
  };
}

/**
 * Runs an executable that may be a Windows .cmd/.bat (mvn.cmd, gradlew.bat, mvnw.cmd).
 * A .cmd cannot be exec'd directly on Windows, so it goes through `cmd.exe /d /s /c`.
 * `/s` strips the outermost pair of quotes, so every argument is quoted individually and
 * the whole command wrapped in one more pair — otherwise a path containing a space
 * (e.g. "D:\Office Research\...") is split by cmd. windowsVerbatimArguments stops Node
 * re-quoting on top of that. Same approach 04b-fixer uses for the Maven wrapper.
 */
function runTool(command, args, options = {}) {
  if (IS_WIN && /\.(cmd|bat)$/i.test(command)) {
    const line = [command, ...args].map((a) => `"${a}"`).join(' ');
    return run('cmd.exe', ['/d', '/s', '/c', `"${line}"`], { ...options, windowsVerbatimArguments: true });
  }
  return run(command, args, options);
}

function tail(text, n = 6000) {
  if (!text) return '';
  return text.length > n ? `…(truncated, showing last ${n} chars)…\n${text.slice(-n)}` : text;
}

function existsAny(candidates) {
  return candidates.find((c) => c && fs.existsSync(c)) || null;
}

function childDirsMatching(root, pattern) {
  if (!fs.existsSync(root)) return [];
  try {
    return fs
      .readdirSync(root, { withFileTypes: true })
      .filter((e) => e.isDirectory() && pattern.test(e.name))
      .map((e) => path.join(root, e.name))
      .sort()
      .reverse();
  } catch {
    return [];
  }
}

// ---------------------------------------------------------------------------
// JDK resolution
//
// A migration always runs at least two JDKs: the one the app builds on today and the one
// it must build on afterwards. Neither is assumed to be JAVA_HOME, and nothing here ever
// mutates the machine's JAVA_HOME or PATH — the chosen JDK is passed to each child
// process in its own env only.
// ---------------------------------------------------------------------------

function javaMajorFrom(versionText) {
  const m = /version "(\d+)(?:\.(\d+))?/.exec(versionText || '');
  if (!m) return null;
  const first = Number(m[1]);
  return first === 1 ? Number(m[2]) : first;
}

function probeJdk(home) {
  const javaExe = path.join(home, 'bin', IS_WIN ? 'java.exe' : 'java');
  if (!fs.existsSync(javaExe)) return null;
  const probe = run(javaExe, ['-version']);
  const text = `${probe.stderr}${probe.stdout}`;
  const major = javaMajorFrom(text);
  if (!major) return null;
  return {
    home,
    javaExe,
    major,
    version: (/version "([^"]+)"/.exec(text) || [, 'unknown'])[1],
    vendor: (text.split(/\r?\n/)[0] || '').trim(),
  };
}

function jdkSearchRoots() {
  if (IS_WIN) {
    return [
      'C:\\Program Files\\Eclipse Adoptium',
      'C:\\Program Files\\Java',
      'C:\\Program Files\\Microsoft',
      'C:\\Program Files\\Amazon Corretto',
      'C:\\Program Files\\Zulu',
      'C:\\Program Files\\BellSoft',
      'C:\\Program Files\\Semeru',
    ];
  }
  return [
    '/usr/lib/jvm',
    '/Library/Java/JavaVirtualMachines',
    path.join(process.env.HOME || '', '.sdkman', 'candidates', 'java'),
  ];
}

/**
 * Finds a JDK for a major version. Order: explicit env override, current JAVA_HOME if it
 * already matches, then the conventional install roots for the platform. Returns null
 * rather than throwing so callers can report a missing toolchain as a finding.
 */
function resolveJdk(major) {
  const wanted = Number(major);
  const override = process.env[`MIGRATION_JDK_${wanted}`];
  if (override) {
    const probed = probeJdk(override);
    if (probed) return { ...probed, source: `MIGRATION_JDK_${wanted}` };
  }
  if (process.env.JAVA_HOME) {
    const probed = probeJdk(process.env.JAVA_HOME);
    if (probed && probed.major === wanted) return { ...probed, source: 'JAVA_HOME' };
  }
  for (const root of jdkSearchRoots()) {
    for (const dir of childDirsMatching(root, new RegExp(`(^|[^0-9])${wanted}([^0-9]|$)`))) {
      const home = fs.existsSync(path.join(dir, 'Contents', 'Home'))
        ? path.join(dir, 'Contents', 'Home')
        : dir;
      const probed = probeJdk(home);
      if (probed && probed.major === wanted) return { ...probed, source: root };
    }
  }
  return null;
}

function installedJdks() {
  const found = new Map();
  const consider = (home, source) => {
    const probed = probeJdk(path.resolve(home));
    const key = probed ? (IS_WIN ? probed.home.toLowerCase() : probed.home) : null;
    if (probed && !found.has(key)) found.set(key, { ...probed, source });
  };
  for (const [key, value] of Object.entries(process.env)) {
    if (/^MIGRATION_JDK_\d+$/.test(key) && value) consider(value, key);
  }
  if (process.env.JAVA_HOME) consider(process.env.JAVA_HOME, 'JAVA_HOME');
  for (const root of jdkSearchRoots()) {
    for (const dir of childDirsMatching(root, /jdk|jre|java|temurin|zulu|corretto|graal/i)) {
      consider(fs.existsSync(path.join(dir, 'Contents', 'Home')) ? path.join(dir, 'Contents', 'Home') : dir, root);
    }
  }
  return [...found.values()].sort((a, b) => a.major - b.major);
}

/** Env for a child process pinned to one JDK. Never mutates this process's own env. */
function envForJdk(jdk, extra = {}) {
  if (!jdk) return { ...process.env, ...extra };
  const pathKey = Object.keys(process.env).find((k) => k.toLowerCase() === 'path') || 'PATH';
  return {
    ...process.env,
    ...extra,
    JAVA_HOME: jdk.home,
    [pathKey]: `${path.join(jdk.home, 'bin')}${path.delimiter}${process.env[pathKey] || ''}`,
  };
}

// ---------------------------------------------------------------------------
// Build tool resolution
// ---------------------------------------------------------------------------

function mavenSearchCandidates() {
  const roots = IS_WIN
    ? ['C:\\Tools', 'C:\\Program Files', 'C:\\Program Files (x86)', 'C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven']
    : ['/opt', '/usr/local', '/usr/share', path.join(process.env.HOME || '', '.sdkman', 'candidates', 'maven')];
  const bin = IS_WIN ? 'mvn.cmd' : 'mvn';
  const out = [];
  for (const envVar of ['M2_HOME', 'MAVEN_HOME']) {
    if (process.env[envVar]) out.push(path.join(process.env[envVar], 'bin', bin));
  }
  for (const root of roots) {
    out.push(path.join(root, 'bin', bin));
    for (const dir of childDirsMatching(root, /^(apache-)?maven/i)) out.push(path.join(dir, 'bin', bin));
  }
  return out;
}

/**
 * Prefers a project's own wrapper (mvnw/gradlew) — it pins the build-tool version the
 * project expects — and falls back to a system install. `kind` tells the caller which.
 */
function resolveBuildTool(projectDir) {
  const wrapperMvn = existsAny([path.join(projectDir, IS_WIN ? 'mvnw.cmd' : 'mvnw')]);
  const wrapperGradle = existsAny([path.join(projectDir, IS_WIN ? 'gradlew.bat' : 'gradlew')]);
  const hasPom = fs.existsSync(path.join(projectDir, 'pom.xml'));
  const hasGradle = fs.existsSync(path.join(projectDir, 'build.gradle'))
    || fs.existsSync(path.join(projectDir, 'build.gradle.kts'));

  if (hasPom || wrapperMvn) {
    if (wrapperMvn) return { tool: 'maven', kind: 'wrapper', command: wrapperMvn, display: rel(wrapperMvn, projectDir) };
    if (process.env.MIGRATION_MVN && fs.existsSync(process.env.MIGRATION_MVN)) {
      return { tool: 'maven', kind: 'env', command: process.env.MIGRATION_MVN, display: process.env.MIGRATION_MVN };
    }
    const onPath = run(IS_WIN ? 'where' : 'which', ['mvn']);
    if (onPath.status === 0 && onPath.stdout.trim()) {
      const first = onPath.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean)
        .find((p) => !IS_WIN || /\.(cmd|bat|exe)$/i.test(p)) || onPath.stdout.split(/\r?\n/)[0].trim();
      return { tool: 'maven', kind: 'path', command: first, display: first };
    }
    const found = existsAny(mavenSearchCandidates());
    if (found) return { tool: 'maven', kind: 'system', command: found, display: found };
    return { tool: 'maven', kind: 'missing', command: null, display: null };
  }

  if (hasGradle || wrapperGradle) {
    if (wrapperGradle) return { tool: 'gradle', kind: 'wrapper', command: wrapperGradle, display: rel(wrapperGradle, projectDir) };
    const onPath = run(IS_WIN ? 'where' : 'which', ['gradle']);
    if (onPath.status === 0 && onPath.stdout.trim()) {
      const first = onPath.stdout.split(/\r?\n/)[0].trim();
      return { tool: 'gradle', kind: 'path', command: first, display: first };
    }
    return { tool: 'gradle', kind: 'missing', command: null, display: null };
  }

  return { tool: 'unknown', kind: 'missing', command: null, display: null };
}

/** Goals/tasks by intent, so callers never hard-code a tool's CLI. */
function buildArgs(tool, intent, extra = []) {
  const maven = {
    compile: ['-B', 'clean', 'compile'],
    'test-compile': ['-B', 'clean', 'test-compile'],
    test: ['-B', 'clean', 'test'],
    package: ['-B', 'clean', 'package'],
    verify: ['-B', 'clean', 'verify'],
    'package-skip-tests': ['-B', 'clean', 'package', '-DskipTests'],
    tree: ['-B', 'dependency:tree'],
  };
  const gradle = {
    compile: ['classes'],
    'test-compile': ['testClasses'],
    test: ['test'],
    package: ['build'],
    verify: ['build'],
    'package-skip-tests': ['build', '-x', 'test'],
    tree: ['dependencies'],
  };
  const table = tool === 'gradle' ? gradle : maven;
  return [...(table[intent] || table.package), ...extra];
}

// ---------------------------------------------------------------------------
// Project inventory — what the project declares *today*
//
// Regex-based on purpose: this must work on any project without installing an XML parser,
// and it only ever reads. Anything it cannot parse is reported as unknown rather than
// guessed at.
// ---------------------------------------------------------------------------

function stripXmlComments(xml) {
  return xml.replace(/<!--[\s\S]*?-->/g, '');
}

function tagText(block, tag) {
  const m = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(block);
  return m ? m[1].trim() : null;
}

function parsePom(pomText) {
  const xml = stripXmlComments(pomText);
  const parentBlock = (/<parent>([\s\S]*?)<\/parent>/.exec(xml) || [, ''])[1];
  const propsBlock = (/<properties>([\s\S]*?)<\/properties>/.exec(xml) || [, ''])[1];

  const properties = {};
  const propRe = /<([a-zA-Z0-9_.\-]+)>([^<]*)<\/\1>/g;
  let pm;
  while ((pm = propRe.exec(propsBlock))) properties[pm[1]] = pm[2].trim();

  const resolve = (value) => {
    if (!value) return value;
    const m = /^\$\{(.+)\}$/.exec(value.trim());
    return m && properties[m[1]] !== undefined ? properties[m[1]] : value.trim();
  };

  const dependencies = [];
  const depRe = /<dependency>([\s\S]*?)<\/dependency>/g;
  let dm;
  while ((dm = depRe.exec(xml))) {
    const block = dm[1];
    dependencies.push({
      groupId: tagText(block, 'groupId'),
      artifactId: tagText(block, 'artifactId'),
      version: resolve(tagText(block, 'version')),
      declaredVersion: tagText(block, 'version'),
      scope: tagText(block, 'scope') || 'compile',
      managed: !tagText(block, 'version'),
    });
  }

  const plugins = [];
  const pluginRe = /<plugin>([\s\S]*?)<\/plugin>/g;
  let gm;
  while ((gm = pluginRe.exec(xml))) {
    const block = gm[1];
    plugins.push({
      groupId: tagText(block, 'groupId'),
      artifactId: tagText(block, 'artifactId'),
      version: resolve(tagText(block, 'version')),
    });
  }

  const compilerPlugin = plugins.find((p) => p.artifactId === 'maven-compiler-plugin');
  const compilerBlock = (/<artifactId>maven-compiler-plugin<\/artifactId>([\s\S]*?)<\/plugin>/.exec(xml) || [, ''])[1];

  return {
    buildTool: 'maven',
    coordinates: {
      groupId: tagText(xml.replace(/<parent>[\s\S]*?<\/parent>/, ''), 'groupId'),
      artifactId: tagText(xml.replace(/<parent>[\s\S]*?<\/parent>/, ''), 'artifactId'),
      version: tagText(xml.replace(/<parent>[\s\S]*?<\/parent>/, ''), 'version'),
      name: tagText(xml, 'name'),
    },
    parent: parentBlock
      ? {
        groupId: tagText(parentBlock, 'groupId'),
        artifactId: tagText(parentBlock, 'artifactId'),
        version: tagText(parentBlock, 'version'),
      }
      : null,
    properties,
    javaVersion: properties['java.version'] || properties['maven.compiler.release']
      || properties['maven.compiler.source'] || tagText(compilerBlock, 'release')
      || tagText(compilerBlock, 'source') || null,
    compilerPluginVersion: compilerPlugin ? compilerPlugin.version : null,
    dependencies,
    plugins,
  };
}

function parseGradle(text) {
  const dependencies = [];
  const depRe = /(?:implementation|api|testImplementation|runtimeOnly|compileOnly|testRuntimeOnly)[\s(]+['"]([^'":]+):([^'":]+)(?::([^'"]+))?['"]/g;
  let m;
  while ((m = depRe.exec(text))) {
    dependencies.push({ groupId: m[1], artifactId: m[2], version: m[3] || null, managed: !m[3], scope: 'compile' });
  }
  const plugins = [];
  const pluginRe = /id\s*[('"]+([^'"]+)['"]\)?\s*(?:version\s*['"]([^'"]+)['"])?/g;
  while ((m = pluginRe.exec(text))) plugins.push({ artifactId: m[1], version: m[2] || null });
  const javaVersion = (/(?:sourceCompatibility|targetCompatibility|languageVersion[^\n]*JavaLanguageVersion\.of\()\s*[=(]?\s*['"]?(?:JavaVersion\.VERSION_)?(\d+)/.exec(text) || [, null])[1];
  return { buildTool: 'gradle', coordinates: {}, parent: null, properties: {}, javaVersion, dependencies, plugins };
}

/** Read-only inventory of a project directory. Never writes, never builds. */
function inventoryProject(projectDir) {
  const pom = path.join(projectDir, 'pom.xml');
  if (fs.existsSync(pom)) return { descriptor: rel(pom, projectDir), ...parsePom(fs.readFileSync(pom, 'utf8')) };
  for (const g of ['build.gradle', 'build.gradle.kts']) {
    const file = path.join(projectDir, g);
    if (fs.existsSync(file)) return { descriptor: g, ...parseGradle(fs.readFileSync(file, 'utf8')) };
  }
  return { descriptor: null, buildTool: 'unknown', dependencies: [], plugins: [], javaVersion: null };
}

/** Walks the source tree for the container/runtime files a migration usually also touches. */
function findAncillaryFiles(projectDir) {
  const interesting = [];
  const wanted = /^(Dockerfile|docker-compose\.ya?ml|\.sdkmanrc|\.tool-versions|Jenkinsfile)$/i;
  const walk = (dir, depth) => {
    if (depth > 3) return;
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      if (entry.name === 'target' || entry.name === 'build' || entry.name === '.git' || entry.name === 'node_modules') continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full, depth + 1);
      else if (wanted.test(entry.name) || /^\.github[\\/]workflows/.test(rel(full, projectDir))) {
        interesting.push(rel(full, projectDir));
      }
    }
  };
  walk(projectDir, 0);
  const workflows = path.join(projectDir, '.github', 'workflows');
  if (fs.existsSync(workflows)) {
    for (const f of fs.readdirSync(workflows)) interesting.push(`.github/workflows/${f}`);
  }
  return [...new Set(interesting)].sort();
}

// ---------------------------------------------------------------------------
// Build-output parsing
//
// Classification is by compiler/​resolver message shape only — never by library name.
// The category tells the agent *what kind* of breakage it is; the reference pack tells it
// what to do about that breakage for this particular framework jump.
// ---------------------------------------------------------------------------

const ERROR_CATEGORIES = [
  { id: 'dependency-resolution', label: 'Dependency not resolvable', hint: 'The coordinate, version or repository changed — check the reference pack for renamed or split artifacts.', test: /could not resolve dependencies|could not find artifact|failed to read artifact descriptor|dependencies could not be resolved|could not determine the dependencies/i },
  { id: 'missing-package', label: 'Package does not exist', hint: 'A type moved to a new package or module. Look for a relocation entry in the reference pack.', test: /package [\w.]+ does not exist|error: package .* does not exist/i },
  { id: 'missing-symbol', label: 'Cannot find symbol', hint: 'A class, method or field was renamed or removed. Look for a replacement API in the reference pack.', test: /cannot find symbol/i },
  { id: 'incompatible-types', label: 'Incompatible types', hint: 'A signature changed shape (often a builder or callback). Check the reference pack for the new call form.', test: /incompatible types|bad return type|argument mismatch/i },
  { id: 'no-suitable-method', label: 'No suitable method / wrong arguments', hint: 'An overload was removed or its parameters changed.', test: /no suitable method found|method .* cannot be applied to given types|constructor .* cannot be applied/i },
  { id: 'abstract-not-implemented', label: 'Interface contract changed', hint: 'An interface gained, moved or changed a method — implement the new contract, or the type it came from has moved too.', test: /is not abstract and does not override abstract method|does not override abstract method|does not override or implement a method from a supertype/i },
  { id: 'removed-api', label: 'Removed or inaccessible API', hint: 'The API still exists in name but is no longer accessible from here.', test: /has private access|is not public|is not visible|is deprecated and marked for removal/i },
  { id: 'annotation-error', label: 'Annotation no longer valid', hint: 'An annotation was removed, renamed or moved module.', test: /annotation type not applicable|cannot find symbol\s+symbol:\s+class \w*(Bean|Test|Mock)/i },
  { id: 'java-release', label: 'Java release / toolchain mismatch', hint: 'The JDK running the build does not match what the build declares.', test: /invalid target release|invalid source release|release version .* not supported|has been compiled by a more recent version|unsupported class file major version/i },
  { id: 'plugin-failure', label: 'Build plugin failed', hint: 'A build plugin is too old for the new platform, or its configuration changed.', test: /failed to execute goal|plugin .* or one of its dependencies could not be resolved|execution .* of goal/i },
  { id: 'environment', label: 'Environment, not the code', hint: 'The machine is missing something the test or build needs (a container runtime, a service, a network route). Compare against the same goal on the pre-migration build before blaming the upgrade.', test: /could not find a valid docker environment|connection refused|unknownhost|no such host|daemon is not running|failed to start container/i },
  { id: 'test-failure', label: 'Test failure', hint: 'The code compiled but behaviour or test wiring changed.', test: /tests run:.*(failures|errors): [1-9]|there are test failures|but was:|<<< (failure|error)!|\w+Test\.\w+:\d+/i },
];

/** True for a line Maven prints under [ERROR] that is a banner or a pointer, not an error. */
function isLogNoise(message) {
  return LOG_NOISE.test(String(message || '').trim());
}

function classifyMessage(message) {
  const found = ERROR_CATEGORIES.find((c) => c.test.test(message));
  return found ? found.id : 'other';
}

function categoryMeta(id) {
  return ERROR_CATEGORIES.find((c) => c.id === id)
    || { id: 'other', label: 'Uncategorised', hint: 'Read the raw log — this shape was not recognised.' };
}

/** javac prints these under an error as extra context, not as errors of their own. */
const CONTINUATION = /^(symbol|location|required|found|reason|actual|expected|where [A-Z]\b)\s*:/i;
/** Maven's section banners and its "how to get more output" footer are not errors either. */
const LOG_NOISE = /^(COMPILATION ERROR|BUILD FAILURE|ERROR|Failures|Errors|Tests in error|Skipped)\s*:?\s*$|^-{5,}$|^-+>|^To see the full stack trace|^Re-run Maven|^For more information|^\[Help \d\]|^After correcting the problems|^See .* for the individual test results|^See dump files/i;

/**
 * Replaces the build root wherever it appears in free text with `.` — build logs are quoted in
 * the report, and a full sandbox path on every line makes them unreadable. Handles both slash
 * directions and javac's leading-slash form (`/D:/…`), case-insensitively for Windows.
 */
function stripRootFromText(text, root) {
  if (!text || !root) return text || '';
  const forward = String(root).split(path.sep).join('/');
  const backward = forward.split('/').join('\\');
  let out = String(text);
  for (const variant of [`/${forward}`, forward, backward]) {
    const escaped = variant.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(escaped, 'gi'), '.');
  }
  return out;
}

/** Absolute compiler paths are unreadable in a report; make them relative to the build root. */
function normaliseFile(file, root) {
  let normalised = String(file).trim().split(path.sep).join('/').replace(/^\/([A-Za-z]:)/, '$1');
  if (root) {
    const rootPath = String(root).split(path.sep).join('/').replace(/\/$/, '');
    const [a, b] = [normalised.toLowerCase(), rootPath.toLowerCase()];
    if (a.startsWith(`${b}/`)) normalised = normalised.slice(rootPath.length + 1);
  }
  return normalised;
}

/**
 * Pulls structured compiler/build errors out of a Maven or Gradle log.
 * Handles: `[ERROR] /path/File.java:[12,34] message`, javac's `/path/File.java:12: error: message`
 * and bare `[ERROR] message` lines from the resolver or a plugin.
 *
 * javac's follow-on `symbol:` / `location:` lines are folded into the error they belong to —
 * counting them separately would triple the error count and turn "cannot find symbol" into an
 * unusable message with no subject. `root` makes reported paths relative to the build root.
 */
function parseBuildErrors(output, root = null) {
  const lines = String(output || '').split(/\r?\n/);
  const collected = [];
  let last = null;

  const push = (entry) => {
    last = { ...entry, detail: [] };
    collected.push(last);
  };

  for (const line of lines) {
    const maven = /^\[ERROR\]\s+(.+?\.(?:java|kt)):\[(\d+),(\d+)\]\s+(.+)$/.exec(line);
    if (maven) {
      push({ file: normaliseFile(maven[1], root), line: Number(maven[2]), column: Number(maven[3]), message: maven[4].trim() });
      continue;
    }
    const javac = /^(?:\[ERROR\]\s+)?(.+?\.(?:java|kt)):(\d+):\s*error:\s*(.+)$/.exec(line);
    if (javac) {
      push({ file: normaliseFile(javac[1], root), line: Number(javac[2]), column: null, message: javac[3].trim() });
      continue;
    }
    const generic = /^\[ERROR\]\s*(.*)$/.exec(line);
    if (!generic) continue;
    const message = generic[1].trim();
    if (!message || LOG_NOISE.test(message)) continue;
    if (CONTINUATION.test(message)) {
      // Belongs to the error above it: "cannot find symbol" + "symbol: class ObjectMapper".
      if (last) last.detail.push(message.replace(/\s+/g, ' '));
      continue;
    }
    push({ file: null, line: null, column: null, message });
  }

  const seen = new Set();
  const errors = [];
  for (const entry of collected) {
    const symbol = entry.detail.find((d) => /^symbol\s*:/i.test(d));
    const message = symbol && /cannot find symbol/i.test(entry.message)
      ? `${entry.message} — ${symbol.replace(/^symbol\s*:\s*/i, '')}`
      : entry.message;
    const key = `${entry.file || ''}:${entry.line || ''}:${message}`;
    if (seen.has(key)) continue;
    seen.add(key);
    errors.push({ ...entry, message, category: classifyMessage(`${message} ${entry.detail.join(' ')}`) });
  }
  return errors;
}

function summariseErrors(errors) {
  const byCategory = {};
  const byFile = {};
  for (const e of errors) {
    byCategory[e.category] = (byCategory[e.category] || 0) + 1;
    if (e.file) byFile[e.file] = (byFile[e.file] || 0) + 1;
  }
  return {
    total: errors.length,
    byCategory: Object.entries(byCategory)
      .map(([id, count]) => ({ id, count, ...categoryMeta(id) }))
      .map(({ test, ...rest }) => rest)
      .sort((a, b) => b.count - a.count),
    byFile: Object.entries(byFile).map(([file, count]) => ({ file, count })).sort((a, b) => b.count - a.count),
  };
}

/** Maven prints a one-line reactor result; used to distinguish "compiled" from "tests failed". */
const COMPILE_CATEGORIES = ['missing-package', 'missing-symbol', 'incompatible-types', 'no-suitable-method', 'abstract-not-implemented', 'removed-api', 'annotation-error'];

function buildOutcome(result, errors) {
  if (result.status === 0) return 'passed';
  if (errors.some((e) => e.category === 'test-failure')) return 'tests-failed';
  if (errors.some((e) => e.category === 'dependency-resolution')) return 'dependency-failed';
  // The code compiled and a later plugin failed (e.g. an old coverage plugin reading Java 21 class
  // files): that is not a compile failure, and calling it one sends repair to the wrong place.
  const compileError = errors.some((e) => COMPILE_CATEGORIES.includes(e.category)
    || (e.category === 'java-release' && /invalid (target|source) release|release version .* not supported/i.test(e.message || '')));
  if (!compileError && errors.some((e) => e.category === 'plugin-failure')) return 'build-failed';
  return 'compile-failed';
}

/** Outcomes that prove the sources compiled on that round (tests or a later plugin may still have failed). */
const COMPILED_OUTCOMES = ['passed', 'tests-failed', 'build-failed'];

// ---------------------------------------------------------------------------
// Root-cause grouping
//
// Many error lines usually share one cause: twenty "cannot find symbol" lines can all be the same
// moved package. Grouping them is a *parser fact* — same category, same subject — and is recorded
// as such. Naming the framework surface behind a group ("that is the Jackson 3 move") is judgement,
// which belongs to the agent and the reference pack, so anything here that goes beyond the parser
// carries an explicit `basis` saying how it was derived.
// ---------------------------------------------------------------------------

/** The thing an error is about, read off the message shape only. */
function errorSubject(error) {
  const text = `${error.message || ''} ${(error.detail || []).join(' ')}`;
  const pkg = /package ([\w.]+) does not exist/i.exec(text);
  if (pkg) return { kind: 'package', name: pkg[1] };
  const sym = /symbol\s*:?\s*(class|interface|method|variable|static)\s+([\w$]+)/i.exec(text)
    || /cannot find symbol\s*—\s*(class|interface|method|variable|static)\s+([\w$]+)/i.exec(text);
  if (sym) return { kind: sym[1].toLowerCase(), name: sym[2] };
  const coord = /([\w.\-]+:[\w.\-]+:(?:jar|pom):[\w.\-]+|[\w.\-]+:[\w.\-]+:[\w.\-]+)/.exec(text);
  if (error.category === 'dependency-resolution' && coord) return { kind: 'artifact', name: coord[1] };
  if (['test-failure', 'environment'].includes(error.category)) {
    const testClass = /\b([A-Z][\w$]*(?:Test|Tests|IT|Spec))\b/.exec(text);
    if (testClass) return { kind: 'test', name: testClass[1] };
  }
  return { kind: 'message', name: String(error.message || '').replace(/\d+/g, 'N').slice(0, 100) };
}

/** First three package segments — `com.fasterxml.jackson.databind` → `com.fasterxml.jackson`. */
function packageFamily(pkg) {
  return String(pkg || '').split('.').slice(0, 3).join('.');
}

/**
 * For "cannot find symbol: class X" the parser knows the name but not where X came from. The file
 * that failed says it in its own imports, which is still a deterministic fact about the source
 * (basis `source-import`), not a guess about the framework.
 */
function importFor(workspace, file, simpleName) {
  if (!workspace || !file || !simpleName) return null;
  const abs = path.join(workspace, ...String(file).split('/'));
  let text = '';
  try { text = fs.readFileSync(abs, 'utf8'); } catch { return null; }
  const re = new RegExp(`^\\s*import\\s+(?:static\\s+)?([\\w.]+\\.${simpleName.replace(/\$/g, '\\$')})\\s*;`, 'm');
  const m = re.exec(text);
  return m ? m[1] : null;
}

function groupErrors(errors, workspace = null) {
  const groups = new Map();
  for (const e of errors || []) {
    const subject = errorSubject(e);
    let imported = null;
    if (['class', 'interface', 'variable', 'static'].includes(subject.kind) && e.file) {
      imported = importFor(workspace, e.file, subject.name);
    }
    const pkg = subject.kind === 'package' ? subject.name
      : (imported ? imported.split('.').slice(0, -1).join('.') : null);
    const key = `${e.category}|${subject.kind}|${subject.name}`;
    let g = groups.get(key);
    if (!g) {
      g = {
        category: e.category,
        subject: `${subject.kind} ${subject.name}`,
        package: pkg,
        package_family: pkg ? packageFamily(pkg) : null,
        basis: imported ? 'parser + source-import' : 'parser',
        count: 0,
        files: new Set(),
        sample: e.message,
      };
      groups.set(key, g);
    }
    g.count += 1;
    if (e.file) g.files.add(e.file);
  }
  return [...groups.values()]
    .map((g, i) => ({ ...g, files: [...g.files].sort() }))
    .sort((a, b) => b.count - a.count || a.subject.localeCompare(b.subject))
    .map((g, i) => ({ id: `G${i + 1}`, ...g }));
}

/**
 * Heuristic correlation of error groups with the migration plan's impact entries: a group
 * "matches" an impact entry when one of that entry's `expected_symptoms` appears in the group's
 * subject, package or sample text. This is text matching, recorded as such — never compiler truth.
 */
function correlateGroups(groups, plan) {
  const impacts = (plan && Array.isArray(plan.impact)) ? plan.impact : [];
  const matches = [];
  for (const g of groups) {
    const haystack = `${g.subject} ${g.package || ''} ${g.sample || ''} ${g.files.join(' ')}`.toLowerCase();
    const hits = impacts
      .filter((imp) => (imp.expected_symptoms || []).some((s) => s && haystack.includes(String(s).toLowerCase())))
      .map((imp) => imp.id);
    if (hits.length) matches.push({ group: g.id, impact: hits });
  }
  return {
    basis: 'plan-text-match (heuristic: expected_symptoms substring match, not compiler truth)',
    matches,
    unmatched_groups: groups.filter((g) => !matches.some((m) => m.group === g.id)).map((g) => g.id),
  };
}

// ---------------------------------------------------------------------------
// Redaction
//
// Commands and logs recorded by 04D are evidence and end up in a report. Credential-bearing values
// never belong there: values of secret-looking environment variables, URL userinfo, `-D…password=`
// style arguments and Authorization headers are replaced before anything is written.
// ---------------------------------------------------------------------------

const SECRET_ENV_KEY = /(password|passwd|secret|token|api[-_]?key|access[-_]?key|credential|auth)/i;
const SECRET_ASSIGNMENT = /([\w.\-]*(?:password|passwd|secret|token|api[-_]?key|access[-_]?key)[\w.\-]*\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,;]+)/gi;

function redact(text, env = process.env) {
  if (text === null || text === undefined) return text;
  let out = String(text);
  for (const [key, value] of Object.entries(env || {})) {
    if (SECRET_ENV_KEY.test(key) && typeof value === 'string' && value.length >= 6) out = out.split(value).join('***');
  }
  out = out.replace(/(\b[a-z][a-z0-9+.\-]*:\/\/)[^\/\s:@]+:[^\/\s@]+@/gi, '$1***:***@');
  out = out.replace(SECRET_ASSIGNMENT, '$1***');
  out = out.replace(/(Authorization\s*:\s*\w+\s+)[^\s"']+/gi, '$1***');
  return out;
}

// ---------------------------------------------------------------------------
// Sandbox checkpoints
//
// The workspace's own throwaway git repository already pins the baseline commit. A checkpoint is a
// commit object on refs/checkpoints/<name> built from the current tree without moving HEAD or the
// baseline, so a transformation can always be undone to exactly the state before it ran.
// ---------------------------------------------------------------------------

/**
 * True only for a directory that is itself the root of the sandbox's own git repository. Every
 * `git add -A` in this skill runs against a workspace; if that directory were not its own repo, git
 * would walk up to the enclosing repository and stage *it* — so nothing runs without this check.
 */
function isSandboxRepo(workspace) {
  return Boolean(workspace) && fs.existsSync(path.join(workspace, '.git'));
}

const gitIn = (workspace) => {
  if (!isSandboxRepo(workspace)) throw new Error(`${workspace} is not a sandbox git repository — refusing to run git there`);
  return (...a) => run('git', ['-C', workspace, ...a]);
};

/** Tree hash of everything in the workspace right now (build output is excluded by info/exclude). */
function workspaceTree(workspace) {
  const git = gitIn(workspace);
  git('add', '-A');
  return git('write-tree').stdout.trim();
}

function createCheckpoint(workspace, name, message) {
  const git = gitIn(workspace);
  const tree = workspaceTree(workspace);
  const head = git('rev-parse', 'HEAD').stdout.trim();
  const commit = git('commit-tree', tree, '-p', head, '-m', message || `checkpoint ${name}`).stdout.trim();
  if (!commit) throw new Error(`could not create checkpoint ${name} in ${workspace}`);
  git('update-ref', `refs/checkpoints/${name}`, commit);
  return { ref: `refs/checkpoints/${name}`, commit, tree };
}

/** Puts the workspace back to a checkpoint: tracked content, added files removed, deletions restored. */
function restoreCheckpoint(workspace, commit) {
  const git = gitIn(workspace);
  git('add', '-A');
  const result = git('read-tree', '-u', '--reset', commit);
  if (result.status !== 0) throw new Error(`restore to ${commit} failed: ${result.stderr}`);
  return workspaceTree(workspace);
}

/** Files that differ between a commit/tree and the workspace as it stands. */
function changedSince(workspace, commitish) {
  const git = gitIn(workspace);
  git('add', '-A');
  const status = git('diff', '--cached', '--name-status', commitish).stdout.trim();
  if (!status) return [];
  return status.split(/\r?\n/).map((line) => {
    const [state, ...rest] = line.split(/\t/);
    return { state: state.trim(), file: rest.join(' -> ').split(path.sep).join('/') };
  });
}

function diffSince(workspace, commitish) {
  const git = gitIn(workspace);
  git('add', '-A');
  return git('diff', '--cached', '--binary', commitish).stdout || '';
}

/** File paths named in a unified diff (`diff --git a/x b/y` headers, falling back to +++ lines). */
function filesInPatch(patchText) {
  const files = new Set();
  for (const m of String(patchText || '').matchAll(/^diff --git a\/(.+?) b\/(.+)$/gm)) files.add(m[2].trim());
  if (!files.size) {
    for (const m of String(patchText || '').matchAll(/^\+\+\+ b\/(.+)$/gm)) files.add(m[1].trim());
  }
  return [...files].sort();
}

// ---------------------------------------------------------------------------
// Minimal JSON-schema validation (draft-07 subset)
//
// Enough to hold the agent's judgement files to their templates without an npm dependency:
// type, required, properties, additionalProperties, items, enum, const, minItems, minLength and
// local $ref to #/definitions. Anything else in a schema is documentation only.
// ---------------------------------------------------------------------------

function typeOf(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (Number.isInteger(value)) return 'integer';
  return typeof value;
}

function validateAgainstSchema(value, schema, root = schema, at = '$') {
  const errors = [];
  if (!schema || typeof schema !== 'object') return errors;
  if (schema.$ref) {
    const target = schema.$ref.replace(/^#\//, '').split('/').reduce((node, key) => (node ? node[key] : null), root);
    return target ? validateAgainstSchema(value, target, root, at) : [`${at}: unresolvable $ref ${schema.$ref}`];
  }
  if (schema.type) {
    const allowed = Array.isArray(schema.type) ? schema.type : [schema.type];
    const actual = typeOf(value);
    const ok = allowed.some((t) => t === actual || (t === 'number' && actual === 'integer'));
    if (!ok) return [`${at}: expected ${allowed.join('|')}, got ${actual}`];
  }
  if (schema.const !== undefined && value !== schema.const) errors.push(`${at}: must equal ${JSON.stringify(schema.const)}`);
  if (schema.enum && !schema.enum.includes(value)) errors.push(`${at}: must be one of ${schema.enum.join(', ')} (got ${JSON.stringify(value)})`);
  if (typeof value === 'string' && schema.minLength && value.length < schema.minLength) errors.push(`${at}: shorter than ${schema.minLength}`);
  if (Array.isArray(value)) {
    if (schema.minItems && value.length < schema.minItems) errors.push(`${at}: needs at least ${schema.minItems} item(s)`);
    if (schema.items) value.forEach((item, i) => errors.push(...validateAgainstSchema(item, schema.items, root, `${at}[${i}]`)));
  }
  if (typeOf(value) === 'object') {
    for (const key of schema.required || []) {
      if (value[key] === undefined) errors.push(`${at}: missing required "${key}"`);
    }
    const props = schema.properties || {};
    for (const [key, child] of Object.entries(value)) {
      if (props[key]) errors.push(...validateAgainstSchema(child, props[key], root, `${at}.${key}`));
      else if (schema.additionalProperties === false) errors.push(`${at}: unexpected property "${key}"`);
      else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
        errors.push(...validateAgainstSchema(child, schema.additionalProperties, root, `${at}.${key}`));
      }
    }
  }
  return errors;
}

function validateTemplate(value, templateFile) {
  const schema = readJson(path.join(PATHS.TEMPLATES_DIR, templateFile));
  return validateAgainstSchema(value, schema);
}

// ---------------------------------------------------------------------------
// Session state
//
// Deliberately not a workflow engine. The authoritative progress of a session is the evidence on
// disk — a round record means a build ran, a runtime record means a probe ran — so the state is
// *inferred* from those files. That is also what keeps sessions created before state.json existed
// readable: nothing about them has to be migrated. state.json only adds what files cannot say on
// their own: a history of who moved the session forward, and an explicit BLOCKED/FAILED flag.
// ---------------------------------------------------------------------------

const STATES = [
  'BASELINE_DETECTED', 'WORKSPACE_PREPARED', 'BASELINE_BUILT', 'BASELINE_PROBED', 'PLAN_READY',
  'TRANSFORMATION_PREVIEWED', 'TRANSFORMATION_APPLIED', 'TARGET_COMPILED', 'TARGET_TESTED',
  'FINAL_PROBED', 'EVIDENCE_READY', 'RENDERED', 'READY_TO_APPLY',
];
const TERMINAL_STATES = ['BLOCKED', 'FAILED'];
const COMPILE_INTENTS = ['compile', 'test-compile', 'test', 'package', 'verify', 'package-skip-tests'];
const TEST_RUN_INTENTS = ['test', 'package', 'verify'];

function listTransformations(slug) {
  const dir = sessionPaths(slug).transformationsDir;
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir)
    .filter((f) => /^rewrite-\d+\.json$/.test(f))
    .map((f) => readJson(path.join(dir, f)))
    .filter(Boolean)
    .sort((a, b) => a.seq - b.seq);
}

function readState(slug) {
  return readJson(sessionPaths(slug).state, null);
}

/**
 * Every state the evidence on disk proves the session has reached, in order. `legacy` is true for a
 * session with neither a plan nor a state file — one written before the v2 flow existed.
 */
function inferState(slug) {
  const p = sessionPaths(slug);
  const reached = [];
  const rounds = listRounds(slug);
  const baselineRound = rounds.find((r) => r.baseline || r.round === 0);
  const targetRounds = rounds.filter((r) => !(r.baseline || r.round === 0));
  const transformations = listTransformations(slug);
  const runtimeBaseline = readJson(path.join(p.runtimeDir, 'baseline.json'));
  const runtimeFinal = readJson(path.join(p.runtimeDir, 'final.json'));
  const plan = readJson(p.plan);
  const migration = readJson(p.migration);

  if (fs.existsSync(p.baseline)) reached.push('BASELINE_DETECTED');
  if (fs.existsSync(p.workspaceMeta)) reached.push('WORKSPACE_PREPARED');
  if (baselineRound) reached.push('BASELINE_BUILT');
  if (runtimeBaseline) reached.push('BASELINE_PROBED');
  let planErrors = null;
  if (plan) {
    planErrors = validateTemplate(plan, 'migration-plan.schema.json');
    if (!planErrors.length) reached.push('PLAN_READY');
  }
  if (transformations.some((t) => t.mode === 'dry-run' && t.status === 'previewed')) reached.push('TRANSFORMATION_PREVIEWED');
  if (transformations.some((t) => t.mode === 'apply' && t.status === 'applied')) reached.push('TRANSFORMATION_APPLIED');
  if (targetRounds.some((r) => COMPILED_OUTCOMES.includes(r.outcome))) reached.push('TARGET_COMPILED');
  if (baselineRound && targetRounds.some((r) => TEST_RUN_INTENTS.includes(r.build.intent) && r.build.intent === baselineRound.build.intent)) {
    reached.push('TARGET_TESTED');
  }
  if (runtimeFinal) reached.push('FINAL_PROBED');
  if (migration) reached.push('EVIDENCE_READY');
  if (migration && fs.existsSync(p.reportMd) && fs.statSync(p.reportMd).mtimeMs >= fs.statSync(p.migration).mtimeMs) reached.push('RENDERED');

  const recorded = readState(slug);
  const flagged = recorded && TERMINAL_STATES.includes(recorded.state) ? recorded.state : null;
  const highest = reached.length ? reached[reached.length - 1] : null;
  return {
    slug,
    state: flagged || highest || 'NEW',
    reached,
    legacy: !plan && !recorded,
    blocked: flagged ? (recorded.reason || null) : null,
    plan_errors: planErrors && planErrors.length ? planErrors : null,
    history: recorded ? recorded.history || [] : [],
  };
}

/**
 * Appends a transition to state.json. `state` is one of STATES or TERMINAL_STATES; the recorded
 * `state` field is the latest transition, while inferState() stays the authority on what the
 * evidence proves.
 */
function recordState(slug, state, by, detail = null) {
  if (![...STATES, ...TERMINAL_STATES].includes(state)) throw new Error(`unknown state ${state}`);
  const file = sessionPaths(slug).state;
  const current = readJson(file, null) || { slug, history: [] };
  const at = new Date().toISOString();
  current.history = [...(current.history || []), { state, at, by, ...(detail ? { detail } : {}) }];
  current.state = state;
  current.updated_at = at;
  if (TERMINAL_STATES.includes(state)) current.reason = detail;
  else delete current.reason;
  writeJson(file, current);
  return current;
}

/** Clears a BLOCKED/FAILED flag once whatever blocked the session has been dealt with. */
function clearBlock(slug, by, detail) {
  const current = readState(slug);
  if (!current || !TERMINAL_STATES.includes(current.state)) return current;
  const inferred = inferState(slug);
  const back = inferred.reached.length ? inferred.reached[inferred.reached.length - 1] : 'BASELINE_DETECTED';
  return recordState(slug, back, by, detail || `cleared ${current.state}`);
}

// ---------------------------------------------------------------------------
// Platform version helpers (generic — the pack says which coordinate is "the platform")
// ---------------------------------------------------------------------------

/**
 * The version a project declares for the coordinate a reference pack detected it on. Returns the
 * first detect entry that names a declared, versioned coordinate — parent before dependencies.
 */
function declaredPlatformVersion(inventory, detectEntries) {
  const candidates = [
    ...(inventory.parent ? [{ ...inventory.parent, where: 'parent' }] : []),
    ...(inventory.dependencies || []).map((d) => ({ ...d, where: 'dependency' })),
    ...(inventory.plugins || []).map((p) => ({ ...p, where: 'plugin' })),
  ];
  for (const entry of detectEntries || []) {
    const [groupId, artifactId] = String(entry).split(':');
    const hit = candidates.find((c) => c.groupId === groupId && c.artifactId === artifactId && c.version);
    if (hit) return { coordinate: `${hit.groupId}:${hit.artifactId}`, version: hit.version, where: hit.where };
  }
  return null;
}

// ---------------------------------------------------------------------------
// Published versions — read from the artifact repository, never remembered
//
// The migration path is planned against what Maven Central actually publishes: every line of the
// platform and its latest GA patch, and every GA Spring Cloud train. One small GET each, through a
// child Node process so the scripts stay synchronous. MIGRATION_OFFLINE=1 (or no network) falls back
// to the ladder's recorded values, and the baseline says which source each value came from.
// ---------------------------------------------------------------------------

const PRERELEASE = /(-|\.)(M\d+|RC\d+|SNAPSHOT|BUILD-SNAPSHOT|alpha|beta)/i;

function httpGet(url, timeoutMs = 20000) {
  if (process.env.MIGRATION_OFFLINE === '1') return { ok: false, error: 'offline (MIGRATION_OFFLINE=1)' };
  const script = 'fetch(process.argv[1],{signal:AbortSignal.timeout(Number(process.argv[2]))})'
    + '.then(async r=>{process.stdout.write(JSON.stringify({status:r.status,body:await r.text()}))})'
    + '.catch(e=>{process.stdout.write(JSON.stringify({error:String(e&&e.message||e)}))})';
  const r = spawnSync(process.execPath, ['-e', script, url, String(timeoutMs)], { encoding: 'utf8', timeout: timeoutMs + 5000, maxBuffer: 32 * 1024 * 1024 });
  try {
    const out = JSON.parse(r.stdout || '{}');
    if (out.error) return { ok: false, error: out.error };
    return { ok: out.status === 200, status: out.status, body: out.body || '' };
  } catch {
    return { ok: false, error: (r.stderr || 'no response').slice(0, 200) };
  }
}

function metadataVersions(repository, groupId, artifactId) {
  const url = `${repository.replace(/\/$/, '')}/${groupId.replace(/\./g, '/')}/${artifactId}/maven-metadata.xml`;
  const res = httpGet(url);
  if (!res.ok) return { ok: false, url, error: res.error || `HTTP ${res.status}` };
  return { ok: true, url, versions: [...res.body.matchAll(/<version>([^<]+)<\/version>/g)].map((m) => m[1].trim()) };
}

/** { line: latest GA patch } for a platform artifact, e.g. { '2.7': '2.7.18', '2.3': '2.3.12.RELEASE' }. */
function publishedLines(repository, groupId, artifactId) {
  const meta = metadataVersions(repository, groupId, artifactId);
  if (!meta.ok) return { ok: false, source: meta.url, error: meta.error, lines: {} };
  const lines = {};
  const key = (v) => v.split(/[.\-]/).slice(0, 3).map((n) => Number(n) || 0);
  for (const v of meta.versions) {
    if (PRERELEASE.test(v)) continue;
    const m = /^(\d+)\.(\d+)\.(\d+)(\.RELEASE)?$/.exec(v);
    if (!m) continue;
    const line = `${m[1]}.${m[2]}`;
    const cur = lines[line];
    const [a, b] = [key(v), cur ? key(cur) : null];
    if (!cur || a[2] > b[2]) lines[line] = v;
  }
  return { ok: true, source: meta.url, lines };
}

/** { train series: latest GA } for Spring Cloud — numbered (2022.0 → 2022.0.5) and named (Hoxton.SR12 → itself). */
function publishedCloudTrains(repository, groupId = 'org.springframework.cloud', artifactId = 'spring-cloud-dependencies') {
  const meta = metadataVersions(repository, groupId, artifactId);
  if (!meta.ok) return { ok: false, source: meta.url, error: meta.error, trains: {} };
  const trains = {};
  for (const v of meta.versions) {
    if (PRERELEASE.test(v)) continue;
    const numbered = /^(\d{4}\.\d+)\.(\d+)$/.exec(v);
    if (numbered) {
      const cur = trains[numbered[1]];
      if (!cur || Number(numbered[2]) > Number(cur.split('.')[2])) trains[numbered[1]] = v;
    } else if (/^[A-Z][a-z]+\.(SR\d+|RELEASE)$/.test(v)) {
      trains[v] = v;
    }
  }
  return { ok: true, source: meta.url, trains };
}

/**
 * The Boot version a Spring Cloud train was built against, read from its spring-cloud-starter-parent
 * POM (which declares spring-boot-starter-parent as its parent) — evidence that the train and the
 * Boot line belong together, not a remembered table. From 2025.1 the train no longer publishes
 * spring-cloud-starter-parent; then the train's spring-cloud-dependencies POM names its
 * spring-cloud-build release (as the parent's version), whose POM declares <spring-boot.version>.
 */
function cloudTrainBootParent(repository, train) {
  const base = `${repository.replace(/\/$/, '')}/org/springframework/cloud`;
  const parentOf = (body) => (/<parent>([\s\S]*?)<\/parent>/.exec(stripXmlComments(body)) || [, ''])[1];
  const url = `${base}/spring-cloud-starter-parent/${train}/spring-cloud-starter-parent-${train}.pom`;
  const res = httpGet(url);
  if (res.ok) {
    const parent = parentOf(res.body);
    return { ok: true, url, via: `spring-cloud-starter-parent ${train}`, artifactId: tagText(parent, 'artifactId'), version: tagText(parent, 'version') };
  }
  const bomUrl = `${base}/spring-cloud-dependencies/${train}/spring-cloud-dependencies-${train}.pom`;
  const bom = httpGet(bomUrl);
  const buildVersion = bom.ok ? tagText(parentOf(bom.body), 'version') : null;
  if (!buildVersion) return { ok: false, url, error: `${res.error || `HTTP ${res.status}`}; ${bom.ok ? 'no parent in the train BOM' : `train BOM ${bom.error || `HTTP ${bom.status}`}`}` };
  const buildUrl = `${base}/spring-cloud-build/${buildVersion}/spring-cloud-build-${buildVersion}.pom`;
  const build = httpGet(buildUrl);
  const boot = build.ok ? (/<spring-boot\.version>\s*([^<\s]+)\s*<\/spring-boot\.version>/.exec(stripXmlComments(build.body)) || [])[1] : null;
  if (!boot) return { ok: false, url: buildUrl, error: build.ok ? 'spring-cloud-build declares no spring-boot.version' : (build.error || `HTTP ${build.status}`) };
  return { ok: true, url: buildUrl, via: `spring-cloud-build ${buildVersion} (the build of spring-cloud-dependencies ${train})`, artifactId: 'spring-boot', version: boot };
}

// ---------------------------------------------------------------------------
// Endpoint inventory — what the application serves, so a migration can prove it still does
//
// A regular-expression pass over controller sources (labelled as such): class-level @RequestMapping
// prefixes joined with method-level mappings. It cannot see endpoints built at runtime (router
// functions, programmatic registration); the runtime probe's /actuator/mappings read covers those
// when the actuator exposes it.
// ---------------------------------------------------------------------------

const MAPPING_ANNOTATIONS = {
  GetMapping: 'GET', PostMapping: 'POST', PutMapping: 'PUT', DeleteMapping: 'DELETE', PatchMapping: 'PATCH', RequestMapping: null,
};

function mappingPaths(args) {
  if (!args) return [''];
  const body = args.trim();
  const named = /(?:^|[,(\s])(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")/.exec(body);
  const raw = named ? named[1] : (/^\s*(\{[^}]*\}|"[^"]*")/.exec(body) || [, null])[1];
  if (!raw) return [''];
  const list = [...raw.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
  return list.length ? list : [''];
}

function mappingMethods(name, args) {
  if (MAPPING_ANNOTATIONS[name]) return [MAPPING_ANNOTATIONS[name]];
  const m = /method\s*=\s*(\{[^}]*\}|[\w.]+)/.exec(args || '');
  if (!m) return ['ANY'];
  const methods = [...m[1].matchAll(/(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)/g)].map((x) => x[1]);
  return methods.length ? methods : ['ANY'];
}

function joinPaths(prefix, suffix) {
  const joined = `/${[prefix, suffix].map((p) => String(p || '').replace(/^\/+|\/+$/g, '')).filter(Boolean).join('/')}`;
  return joined === '/' ? '/' : joined;
}

/**
 * The default-profile application properties as flat keys: { 'a.b.c': { value, file, line } }.
 * Reads application.properties and the first document of application.yml/.yaml with a small
 * indentation flattener (block lists become comma lists); later YAML documents are profile-specific.
 */
function readAppConfig(projectDir) {
  const dir = path.join(projectDir, 'src', 'main', 'resources');
  const props = {};
  for (const name of ['application.properties', 'application.yml', 'application.yaml']) {
    let text;
    try { text = fs.readFileSync(path.join(dir, name), 'utf8'); } catch { continue; }
    const file = `src/main/resources/${name}`;
    const lines = text.split(/\r?\n/);
    if (name.endsWith('.properties')) {
      lines.forEach((l, i) => {
        const m = /^\s*([\w.\-[\]]+)\s*[=:]\s*(.*?)\s*$/.exec(l);
        if (m && !/^\s*[#!]/.test(l)) props[m[1]] = { value: m[2], file, line: i + 1 };
      });
      continue;
    }
    const stack = [];
    let listKey = null;
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i];
      if (/^---/.test(l)) break;
      if (!l.trim() || /^\s*#/.test(l)) continue;
      const item = /^\s*-\s*(.+?)\s*$/.exec(l);
      if (item) {
        if (listKey) {
          const v = item[1].replace(/\s+#.*$/, '').replace(/^["']|["']$/g, '');
          props[listKey].value = props[listKey].value ? `${props[listKey].value},${v}` : v;
        }
        continue;
      }
      const m = /^(\s*)([\w.\-]+)\s*:\s*(.*?)\s*$/.exec(l);
      if (!m) continue;
      const indent = m[1].length;
      while (stack.length && stack[stack.length - 1].indent >= indent) stack.pop();
      stack.push({ indent, key: m[2] });
      const key = stack.map((s) => s.key).join('.');
      const value = m[3].replace(/\s+#.*$/, '');
      listKey = null;
      if (value !== '') props[key] = { value: value.replace(/^\[|\]$/g, '').replace(/^["']|["']$/g, ''), file, line: i + 1 };
      else { props[key] = { value: '', file, line: i + 1 }; listKey = key; }
    }
  }
  return props;
}

/**
 * Endpoints the framework serves because configuration switches them on — the H2 console and the
 * exposed actuator endpoints. No controller maps them, so a source scan alone misses them, yet a
 * major upgrade can drop them (Boot 4 moved the H2 console into its own module; observed in V2).
 */
function configEndpoints(projectDir) {
  const props = readAppConfig(projectDir);
  const out = [];
  const add = (p, handler, src) => out.push({ method: 'GET', path: p, handler, file: src.file, line: src.line, source: 'config' });
  const h2 = props['spring.h2.console.enabled'];
  if (h2 && /^true$/i.test(h2.value)) add(joinPaths((props['spring.h2.console.path'] || {}).value || '/h2-console', ''), 'H2 console (framework)', h2);
  const include = props['management.endpoints.web.exposure.include'];
  const separatePort = props['management.server.port'] && props['management.server.port'].value !== (props['server.port'] || {}).value;
  if (include && !separatePort) {
    const base = (props['management.endpoints.web.base-path'] || {}).value || '/actuator';
    const exclude = ((props['management.endpoints.web.exposure.exclude'] || {}).value || '').split(',').map((s) => s.trim());
    for (const id of include.value.split(',').map((s) => s.trim()).filter(Boolean)) {
      if (id === '*') add(joinPaths(base, ''), 'actuator discovery (framework; "*" exposes every endpoint)', include);
      else if (!exclude.includes(id)) add(joinPaths(base, id), `actuator ${id} endpoint (framework)`, include);
    }
  }
  return out;
}

/**
 * Static endpoint inventory: [{ method, path, handler, file, line, source? }], sorted, de-duplicated.
 * Controller mappings from the source, plus framework endpoints the configuration enables
 * (source: 'config').
 */
function scanEndpoints(projectDir) {
  const root = path.join(projectDir, 'src', 'main', 'java');
  const files = [];
  const walk = (dir) => {
    let entries = [];
    try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const e of entries) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) walk(full);
      else if (e.name.endsWith('.java')) files.push(full);
    }
  };
  walk(root);
  const out = [];
  const annotation = /@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\s*(\(([^()]*(?:\([^()]*\)[^()]*)*)\))?/g;
  for (const file of files) {
    let text;
    try { text = fs.readFileSync(file, 'utf8'); } catch { continue; }
    if (!/@(Rest)?Controller\b/.test(text)) continue;
    const classIdx = text.search(/\b(class|interface)\s+\w+/);
    const relFile = path.relative(projectDir, file).split(path.sep).join('/');
    let prefixes = [''];
    for (const m of text.matchAll(annotation)) {
      const args = m[3] || '';
      if (m.index < classIdx) { prefixes = mappingPaths(args); continue; }
      const after = text.slice(m.index + m[0].length, m.index + m[0].length + 600);
      const handler = (/\b(?:public|protected|private)?\s*[\w<>[\],.?\s]+?\s+(\w+)\s*\(/.exec(after) || [, null])[1];
      // Required query parameters: a probe without them only proves the 400, not the endpoint.
      const brace = after.indexOf('{');
      const signature = brace === -1 ? after : after.slice(0, brace);
      const requiredParams = [...signature.matchAll(/@RequestParam(?:\s*\(([^)]*)\))?\s+(?:final\s+)?[\w<>.,?[\]\s]+?\s+(\w+)\s*[,)]/g)]
        .filter((p) => !/required\s*=\s*false|defaultValue/.test(p[1] || ''))
        .map((p) => ((/(?:value|name)\s*=\s*"([^"]+)"/.exec(p[1] || '') || /^\s*"([^"]+)"/.exec(p[1] || '') || [])[1]) || p[2]);
      const line = text.slice(0, m.index).split(/\r?\n/).length;
      for (const prefix of prefixes) {
        for (const p of mappingPaths(args)) {
          for (const method of mappingMethods(m[1], args)) {
            out.push({ method, path: joinPaths(prefix, p), handler, file: relFile, line, ...(requiredParams.length ? { required_params: requiredParams } : {}) });
          }
        }
      }
    }
  }
  out.push(...configEndpoints(projectDir));
  const seen = new Set();
  return out
    .filter((e) => { const k = `${e.method} ${e.path}`; if (seen.has(k)) return false; seen.add(k); return true; })
    .sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
}

/** Before/after endpoint sets: preserved, missing (a contract break), added. Keys are "METHOD /path". */
function compareEndpoints(before = [], after = []) {
  const key = (e) => `${e.method} ${e.path}`;
  const a = new Set(after.map(key));
  const b = new Set(before.map(key));
  return {
    before: before.length,
    after: after.length,
    preserved: before.filter((e) => a.has(key(e))).map(key),
    missing: before.filter((e) => !a.has(key(e))).map(key),
    added: after.filter((e) => !b.has(key(e))).map(key),
  };
}

// ---------------------------------------------------------------------------
// Runtime comparison
//
// Raw evidence first, always: status, body hash and excerpt are compared exactly as recorded, and
// the raw verdict (identical / body-differs / status-differs) is never overwritten. Anything
// "smarter" — an order-insensitive JSON hash, explicitly configured ignored paths — is recorded
// alongside as an *additional* observation, and the agent's classification of a difference is a
// third, separately labelled layer.
// ---------------------------------------------------------------------------

function compareProbeRecords(before, after, judgements = []) {
  if (!before || !after) {
    return {
      verdictText: before || after ? '⚠️ Only one side was probed — no comparison possible' : '⚠️ Not probed',
      rows: [], matched: 0, bodyOnly: 0, statusChanged: 0, total: 0, probeSetChanged: false,
    };
  }
  const rows = [];
  for (const b of before.probes || []) {
    const a = (after.probes || []).find((p) => p.name === b.name);
    const same = a && a.status === b.status && a.body_hash === b.body_hash;
    const statusSame = a && a.status === b.status && a.ok === b.ok;
    const verdict = same ? 'identical' : (statusSame ? 'body-differs' : 'status-differs');
    let semantic = null;
    if (verdict === 'body-differs' && a) {
      if (b.semantic_hash && a.semantic_hash && b.semantic_hash === a.semantic_hash) semantic = 'same-json-ignoring-key-order';
      else if (b.ignored_paths_hash && a.ignored_paths_hash && b.ignored_paths_hash === a.ignored_paths_hash) semantic = 'same-after-configured-ignored-paths';
      else if (b.json_parseable || a.json_parseable) semantic = 'differs';
    }
    const judgement = (judgements || []).find((j) => j.probe === b.name) || null;
    rows.push({
      name: b.name,
      request: `${b.method} ${b.path}`,
      category: b.category || (a && a.category) || null,
      beforeStatus: b.ok ? b.status : 'error',
      afterStatus: a ? (a.ok ? a.status : 'error') : 'not run',
      beforeHash: b.body_hash,
      afterHash: a ? a.body_hash : null,
      beforeLength: b.body_length,
      afterLength: a ? a.body_length : null,
      same: Boolean(same),
      verdict,
      semantic,
      expectMet: { before: b.expect_status_met, after: a ? a.expect_status_met : undefined },
      classification: judgement ? judgement.classification : null,
      explanation: judgement ? judgement.explanation : null,
    });
  }
  const matched = rows.filter((r) => r.same).length;
  const bodyOnly = rows.filter((r) => r.verdict === 'body-differs').length;
  const statusChanged = rows.filter((r) => r.verdict === 'status-differs').length;
  let verdictText = '⚠️ No probes were defined';
  if (rows.length) {
    if (matched === rows.length) verdictText = `🟢 identical on all ${rows.length} probe(s)`;
    else if (!statusChanged) verdictText = `🟡 same status on all ${rows.length}, body differs on ${bodyOnly}`;
    else verdictText = `🔴 ${statusChanged} of ${rows.length} probe(s) changed status`;
  }
  const probeSetChanged = Boolean(before.probes_sha && after.probes_sha && before.probes_sha !== after.probes_sha);
  return { rows, matched, bodyOnly, statusChanged, total: rows.length, verdictText, probeSetChanged };
}

module.exports = {
  PATHS, ...PATHS,
  IS_WIN,
  slugify, sessionDir, sessionPaths, rel, readJson, writeJson,
  listSessions, listRounds, nextRoundNumber,
  run, runTool, tail, existsAny, childDirsMatching,
  javaMajorFrom, probeJdk, resolveJdk, installedJdks, envForJdk,
  resolveBuildTool, buildArgs,
  inventoryProject, parsePom, parseGradle, findAncillaryFiles,
  ERROR_CATEGORIES, classifyMessage, categoryMeta, isLogNoise, parseBuildErrors, summariseErrors, buildOutcome,
  normaliseFile, stripRootFromText,
  errorSubject, groupErrors, correlateGroups, importFor,
  redact,
  isSandboxRepo, gitIn, workspaceTree, createCheckpoint, restoreCheckpoint, changedSince, diffSince, filesInPatch,
  validateAgainstSchema, validateTemplate,
  STATES, TERMINAL_STATES, COMPILE_INTENTS, TEST_RUN_INTENTS,
  listTransformations, readState, inferState, recordState, clearBlock,
  declaredPlatformVersion, compareProbeRecords, COMPILED_OUTCOMES,
  httpGet, metadataVersions, publishedLines, publishedCloudTrains, cloudTrainBootParent,
  scanEndpoints, compareEndpoints, readAppConfig,
};
