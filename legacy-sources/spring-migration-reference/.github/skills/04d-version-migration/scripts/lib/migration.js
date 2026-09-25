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
  // Migration reports share 04-remediation/ with the fix reports — one folder for everything
  // 04_fix-generator produces. The file prefix (migration_ vs fix_) keeps them apart, and the
  // shared README carries a separate, self-delimited migration block so 04a/04b's index rewrite
  // and this skill's never overwrite each other.
  OUT_DIR: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation'),
  OUT_README: path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation', 'README.md'),
};

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
    const probed = probeJdk(home);
    if (probed && !found.has(probed.home)) found.set(probed.home, { ...probed, source });
  };
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
function buildOutcome(result, errors) {
  if (result.status === 0) return 'passed';
  if (errors.some((e) => e.category === 'test-failure')) return 'tests-failed';
  if (errors.some((e) => e.category === 'dependency-resolution')) return 'dependency-failed';
  return 'compile-failed';
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
};
