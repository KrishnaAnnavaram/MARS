/**
 * Version Migration — OpenRewrite as an internal, deterministic transformation provider.
 *
 * OpenRewrite is not the orchestrator of a migration and it is not the proof of one. 04D decides
 * *whether* a transformation runs (the migration plan must name it), *where* it runs (the sandbox
 * workspace, never the project) and *what counts* as its result (the git diff it leaves behind,
 * checked against the dry-run that was inspected first). The compiler, the tests and the running
 * application stay the authority on whether the result is correct.
 *
 * Nothing here is specific to a framework. Which recipes to run, from which artifact, at which
 * pinned version and under which licence is data — a reference pack's `transformations:` entry,
 * narrowed by the migration plan — so a new migration pack needs no change to this file.
 *
 * The application's build files are never edited to install the tool. Maven gets the plugin by
 * its full coordinate on the command line; Gradle gets an init script written into the session
 * directory. Both leave the workspace exactly as the recipes alone would leave it.
 */
const fs = require('fs');
const path = require('path');
const {
  PATHS, sessionPaths, readJson, writeJson, rel, runTool, envForJdk, stripRootFromText, redact,
  workspaceTree, createCheckpoint, restoreCheckpoint, changedSince, diffSince, filesInPatch,
  inventoryProject, declaredPlatformVersion, listTransformations, gitIn,
} = require('./migration');
const { transformationProblems, normaliseTransformation } = require('./references');

const PROVIDER = 'openrewrite';
const MODES = ['dry-run', 'apply'];
const SUPPORTED_TOOLS = ['maven', 'gradle'];

// ---------------------------------------------------------------------------
// Selection: pack metadata, narrowed by the plan, narrowed again by the command line
// ---------------------------------------------------------------------------

/**
 * Picks the transformation to run and proves it is covered by the migration plan.
 * Returns { transformation, candidate } or { error }.
 *
 * - The plan must contain a deterministic candidate with provider "openrewrite" for it.
 * - The candidate may reference a pack transformation by id, and may narrow its recipes.
 * - Command-line overrides may narrow further, but never add a recipe the plan did not name.
 */
function selectTransformation({ pack, plan, id, overrides = {} }) {
  if (!plan) return { error: 'no migration-plan.json — write and validate the plan before any transformation' };
  const candidates = (plan.deterministic_candidates || []).filter((c) => (c.provider || PROVIDER) === PROVIDER);
  if (!candidates.length) return { error: 'the migration plan names no OpenRewrite candidate' };
  let candidate;
  if (id) candidate = candidates.find((c) => c.id === id || c.transformation === id);
  else if (candidates.length === 1) [candidate] = candidates;
  if (!candidate) {
    return { error: id
      ? `"${id}" is not an OpenRewrite candidate in the migration plan (candidates: ${candidates.map((c) => c.id).join(', ')})`
      : `the plan has ${candidates.length} OpenRewrite candidates — pass --rewrite-id (${candidates.map((c) => c.id).join(', ')})` };
  }
  const packEntry = candidate.transformation
    ? ((pack && pack.transformations) || []).find((t) => t.id === candidate.transformation)
    : null;
  if (candidate.transformation && !packEntry) {
    return { error: `plan candidate "${candidate.id}" references pack transformation "${candidate.transformation}", which the reference pack does not declare` };
  }
  const base = packEntry || {};
  const merged = normaliseTransformation({
    ...base,
    id: candidate.id,
    pack_transformation: candidate.transformation || null,
    recipes: candidate.recipes && candidate.recipes.length ? candidate.recipes : base.recipes,
    artifacts: candidate.artifacts && candidate.artifacts.length ? candidate.artifacts : base.artifacts,
    plugin_version: candidate.plugin_version || base.plugin_version,
    gradle_plugin_version: candidate.gradle_plugin_version || base.gradle_plugin_version,
    policy: candidate.policy || base.policy,
    license: candidate.license || base.license,
    license_note: base.license_note,
    config: base.config,
    source_repository: candidate.source_repository || base.source_repository,
    recipe_target: base.recipe_target,
    build_tools: base.build_tools,
  }, 0);
  merged.pack_transformation = candidate.transformation || null;

  const allowedRecipes = new Set([...(candidate.recipes || []), ...(base.recipes || [])]);
  if (overrides.recipes && overrides.recipes.length) {
    const extra = overrides.recipes.filter((r) => !allowedRecipes.has(r));
    if (extra.length) return { error: `recipe(s) not covered by the migration plan: ${extra.join(', ')}` };
    merged.recipes = overrides.recipes;
  }
  if (overrides.artifacts && overrides.artifacts.length) merged.artifacts = overrides.artifacts;
  if (overrides.pluginVersion) merged.plugin_version = overrides.pluginVersion;
  if (overrides.policy) merged.policy = overrides.policy;
  return { transformation: merged, candidate };
}

// ---------------------------------------------------------------------------
// Command construction
// ---------------------------------------------------------------------------

const SAFE_VALUE = /^[\w.\-]+$/;

/** Fills {{placeholder}} values in a pack's recipe config. Values are validated, never free text. */
function renderRecipeConfig(configRelPath, vars) {
  const source = path.join(PATHS.REFERENCES_DIR, ...configRelPath.split(/[\\/]/));
  if (!fs.existsSync(source)) throw new Error(`recipe config references/${configRelPath} not found`);
  let text = fs.readFileSync(source, 'utf8');
  text = text.replace(/\{\{\s*([a-z_]+)\s*\}\}/g, (whole, key) => {
    const value = vars[key];
    if (value === undefined || value === null || value === '') {
      throw new Error(`recipe config needs {{${key}}} but no value is recorded for it (pass the requested target to detect-baseline.js)`);
    }
    if (!SAFE_VALUE.test(String(value))) throw new Error(`value for {{${key}}} "${value}" is not a plain version string`);
    return String(value);
  });
  return text;
}

function gradleInitScript(t, configFile) {
  const deps = t.artifacts.map((a) => `        rewrite("${a}")`).join('\n');
  const recipes = t.recipes.map((r) => `"${r}"`).join(', ');
  const config = configFile ? `\n        configFile = file("${configFile.split(path.sep).join('/')}")` : '';
  return `// Written by 04d-version-migration for one sandbox run. Never copied into the project.
initscript {
    repositories { maven { url "https://plugins.gradle.org/m2" } }
    dependencies { classpath("org.openrewrite:plugin:${t.gradle_plugin_version}") }
}
rootProject {
    plugins.apply(org.openrewrite.gradle.RewritePlugin)
    dependencies {
${deps}
    }
    rewrite {
        activeRecipe(${recipes})${config}
    }
    afterEvaluate {
        if (repositories.isEmpty()) { repositories { mavenCentral() } }
    }
}
`;
}

/**
 * The exact process 04D will start for one OpenRewrite run. Pure: writes nothing, so it can be
 * inspected (and tested) before anything executes.
 */
function buildInvocation({ tool, mode, transformation: t, workspace, configFile = null, initScriptFile = null }) {
  if (!MODES.includes(mode)) throw new Error(`mode must be one of ${MODES.join(', ')}`);
  if (tool === 'maven') {
    const goal = mode === 'dry-run' ? 'dryRunNoFork' : 'runNoFork';
    const args = [
      '-B',
      `org.openrewrite.maven:rewrite-maven-plugin:${t.plugin_version}:${goal}`,
      `-Drewrite.activeRecipes=${t.recipes.join(',')}`,
    ];
    if (t.artifacts.length) args.push(`-Drewrite.recipeArtifactCoordinates=${t.artifacts.join(',')}`);
    if (configFile) args.push(`-Drewrite.configLocation=${configFile}`);
    return {
      tool, mode, cwd: workspace, args,
      goal,
      patchFile: path.join(workspace, 'target', 'rewrite', 'rewrite.patch'),
      initScript: null,
    };
  }
  if (tool === 'gradle') {
    const task = mode === 'dry-run' ? 'rewriteDryRun' : 'rewriteRun';
    return {
      tool, mode, cwd: workspace,
      args: ['--init-script', initScriptFile, task],
      goal: task,
      patchFile: path.join(workspace, 'build', 'reports', 'rewrite', 'rewrite.patch'),
      initScript: { path: initScriptFile, content: gradleInitScript(t, configFile) },
    };
  }
  throw new Error(`build tool "${tool}" is not supported for OpenRewrite`);
}

/** The command line as it may be recorded: credentials redacted, sandbox paths shortened. */
function replaceRoot(text, root, token) {
  if (!root) return text;
  const forward = String(root).split(path.sep).join('/');
  let out = String(text);
  for (const variant of [forward, forward.split('/').join('\\')]) {
    out = out.replace(new RegExp(variant.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'), token);
  }
  return out;
}

function sanitiseCommand(display, args, { workspace, session }) {
  const shorten = (text) => replaceRoot(replaceRoot(text, workspace, '<sandbox>'), session, '<session>');
  return redact([display, ...args].map(shorten).join(' '));
}

/**
 * Refuses to run anywhere but a 04D sandbox: the directory must be the `workspace/` of a session
 * under the version-migration data dir, carry its own git repository, and not be the project.
 */
function assertSandbox(workspace, slug, projectDir) {
  const expected = path.resolve(sessionPaths(slug).workspace);
  const actual = path.resolve(workspace);
  const norm = (p) => (process.platform === 'win32' ? p.toLowerCase() : p);
  if (norm(actual) !== norm(expected)) throw new Error(`refusing to run OpenRewrite outside the sandbox: ${actual}`);
  if (!fs.existsSync(path.join(actual, '.git'))) throw new Error(`sandbox ${actual} has no git repository — run prepare-workspace.js`);
  if (projectDir && norm(path.resolve(projectDir)) === norm(actual)) throw new Error('refusing to run OpenRewrite against the project directory');
  return true;
}

// ---------------------------------------------------------------------------
// Result classification
// ---------------------------------------------------------------------------

const UNAVAILABLE = [
  { reason: 'credentials', test: /status code:? 40[13]\b|\b401 Unauthorized|\b403 Forbidden|not authori[sz]ed|authentication (failed|required)|return code is: 40[13]/i },
  { reason: 'license', test: /licen[cs]e (key|check|required|invalid|expired|not found)|requires a (valid )?(moderne )?licen[cs]e/i },
  { reason: 'network', test: /UnknownHostException|Could not transfer artifact|Connect(ion)? (timed out|refused)|Network is unreachable|No route to host|in offline mode|Temporary failure in name resolution/i },
  { reason: 'plugin-resolution', test: /Plugin org\.openrewrite[^\n]*(could not be resolved|not found)|No plugin found for prefix '?rewrite|Could not find artifact org\.openrewrite\.maven:rewrite-maven-plugin|Plugin \[id: 'org\.openrewrite/i },
  { reason: 'recipe-resolution', test: /Recipes? not found|could not find recipe|No recipe found|Unable to (resolve|load) recipe|Could not resolve (dependencies|artifact)[^\n]*(openrewrite|rewrite-)|recipe .* (is not available|was not found)/i },
];

/**
 * A failed run is either the capability being unavailable here (network, credentials, licence,
 * resolution) — which an optional transformation may fall back from — or the tool running and
 * failing, which is a real result and is never hidden.
 */
function classifyFailure(log) {
  const hit = UNAVAILABLE.find((u) => u.test.test(log || ''));
  return hit ? { available: false, reason: hit.reason } : { available: true, reason: 'tool-failed' };
}

function nextSeq(slug) {
  const existing = listTransformations(slug);
  return existing.length ? Math.max(...existing.map((t) => t.seq)) + 1 : 0;
}

const recordId = (seq) => `rewrite-${String(seq).padStart(2, '0')}`;

/** Build-file lines that would install OpenRewrite into the application rather than migrate it. */
function infrastructureLines(patchText) {
  return String(patchText || '')
    .split(/\r?\n/)
    .filter((l) => /^\+(?!\+\+)/.test(l) && /rewrite-maven-plugin|org\.openrewrite(\.rewrite|:plugin)|id\s*\(?\s*['"]org\.openrewrite/.test(l))
    .map((l) => l.slice(1).trim());
}

/**
 * Which recipe changed which file, as the tool itself reported it. rewrite-maven-plugin prints a
 * block per file ("These recipes would make changes to X:" on a dry-run, "Changes have been made
 * to X by:" on a run) listing the recipe path that produced the change, indented by nesting depth.
 * Recorded as the tool's own statement — it is what lets a reviewer reject a sub-recipe precisely.
 */
function parseAttribution(log) {
  const out = {};
  let current = null;
  for (const raw of String(log || '').split(/\r?\n/)) {
    const line = raw.replace(/^\[(?:WARNING|INFO|ERROR)\]\s?/, '');
    const head = /^(?:These recipes would make changes to|Changes have been made to)\s+(.+?)(?:\s+by)?:\s*$/.exec(line.trim());
    if (head) {
      current = head[1].split('\\').join('/');
      out[current] = [];
      continue;
    }
    const item = /^(\s{4,})([\w.$]+(?::\s*\{.*\})?)\s*$/.exec(line);
    if (current && item) {
      out[current].push({ depth: Math.floor((item[1].length - 4) / 4), recipe: item[2] });
      continue;
    }
    if (current && line.trim()) current = null;
  }
  return out;
}

function sameRecipeSet(a, b) {
  const key = (t) => JSON.stringify({ r: [...(t.recipes || [])].sort(), a: [...(t.artifacts || [])].sort(), c: t.config_sha || null });
  return key(a) === key(b);
}

// ---------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------

/**
 * Runs one dry-run or apply in the sandbox and writes transformations/rewrite-NN.{json,patch,log}.
 * Returns the record. Never throws for a tool failure — a failure is a recorded outcome.
 *
 * Apply additionally requires `preview`: the id of an earlier dry-run record with the same recipes,
 * artifacts and config, which the agent has inspected. Any file the apply changes that the preview
 * did not propose, and any OpenRewrite build-infrastructure line it leaves behind, reverts the
 * whole apply to the checkpoint taken just before it.
 *
 * `exclude` lists preview files the agent rejected as outside the migration's scope: after the
 * apply they are restored from that same checkpoint, and the record says so. Only whole files can
 * be excluded; a file with both wanted and unwanted hunks needs a narrower recipe instead.
 */
function executeTransformation({
  slug, mode, transformation, tool, jdk, preview = null, vars = {}, detect = [], projectDir = null, timeoutMs = 1800000,
  exclude = [],
}) {
  const p = sessionPaths(slug);
  const seq = nextSeq(slug);
  const id = recordId(seq);
  fs.mkdirSync(p.transformationsDir, { recursive: true });
  const files = {
    json: path.join(p.transformationsDir, `${id}.json`),
    patch: path.join(p.transformationsDir, `${id}.patch`),
    log: path.join(p.transformationsDir, `${id}.log`),
  };
  const started = Date.now();
  const t = transformation;
  const record = {
    id,
    seq,
    provider: PROVIDER,
    mode,
    applied: false,
    transformation: t.id,
    pack_transformation: t.pack_transformation || null,
    policy: t.policy,
    phase: t.phase,
    build_tool: tool ? tool.tool : null,
    build_tool_kind: tool ? tool.kind : null,
    plugin_version: tool && tool.tool === 'gradle' ? t.gradle_plugin_version : t.plugin_version,
    recipes: t.recipes,
    artifacts: t.artifacts,
    config: t.config || null,
    config_sha: null,
    license: t.license || null,
    license_note: t.license_note || null,
    source_repository: t.source_repository || null,
    recipe_target: t.recipe_target || null,
    jdk: jdk ? { major: jdk.major, version: jdk.version } : null,
    started_at: new Date(started).toISOString(),
    finished_at: null,
    duration_ms: null,
    command: null,
    exit_code: null,
    status: null,
    availability: { available: null, reason: null },
    preview: preview ? preview.id : null,
    proposed_files: [],
    changed_files: [],
    excluded_files: [],
    attribution: {},
    patch: null,
    log: null,
    checkpoint: null,
    scope_check: null,
    reconciliation: null,
    build_round: null,
    notes: [],
  };
  const finish = (status) => {
    record.status = status;
    record.finished_at = new Date().toISOString();
    record.duration_ms = Date.now() - started;
    writeJson(files.json, record);
    return record;
  };

  assertSandbox(p.workspace, slug, projectDir);

  if (!tool || !SUPPORTED_TOOLS.includes(tool.tool)) {
    record.availability = { available: false, reason: 'unsupported-build-tool' };
    record.notes.push(`OpenRewrite is only wired for ${SUPPORTED_TOOLS.join('/')}; this project uses ${tool ? tool.tool : 'no known build tool'}.`);
    return finish('unavailable');
  }
  if (!tool.command) {
    record.availability = { available: false, reason: 'build-tool-missing' };
    return finish('unavailable');
  }
  const problems = transformationProblems(t);
  if (tool.tool === 'maven' && !t.plugin_version) problems.push('no pinned rewrite-maven-plugin version (plugin_version)');
  if (tool.tool === 'gradle' && !t.gradle_plugin_version) problems.push('no pinned OpenRewrite Gradle plugin version (gradle_plugin_version)');
  if (t.build_tools && t.build_tools.length && !t.build_tools.includes(tool.tool)) {
    record.availability = { available: false, reason: 'unsupported-build-tool' };
    record.notes.push(`transformation declares build_tools ${t.build_tools.join(', ')}`);
    return finish('unavailable');
  }
  if (problems.length) {
    record.notes.push(...problems);
    return finish('rejected-config');
  }

  let configFile = null;
  if (t.config) {
    try {
      const text = renderRecipeConfig(t.config, vars);
      configFile = path.join(p.transformationsDir, `${id}.rewrite.yml`);
      fs.writeFileSync(configFile, text);
      record.config_sha = require('crypto').createHash('sha256').update(text).digest('hex').slice(0, 16);
      record.rendered_config = rel(configFile);
    } catch (error) {
      record.notes.push(error.message);
      return finish('rejected-config');
    }
  }

  if (mode === 'apply') {
    if (!preview) {
      record.notes.push('apply refused: no inspected dry-run (pass --rewrite-preview <rewrite-NN>)');
      return finish('rejected-no-preview');
    }
    if (preview.mode !== 'dry-run' || preview.status !== 'previewed') {
      record.notes.push(`apply refused: ${preview.id} is not a successful dry-run (status ${preview.status})`);
      return finish('rejected-no-preview');
    }
    if (!sameRecipeSet(preview, record)) {
      record.notes.push(`apply refused: ${preview.id} previewed a different recipe/artifact/config set`);
      return finish('rejected-no-preview');
    }
    const unknown = exclude.filter((f) => !(preview.proposed_files || []).includes(f));
    if (unknown.length) {
      record.notes.push(`apply refused: --rewrite-exclude names file(s) the preview did not propose: ${unknown.join(', ')}`);
      return finish('rejected-no-preview');
    }
  }

  const initScriptFile = tool.tool === 'gradle' ? path.join(p.transformationsDir, `${id}.init.gradle`) : null;
  const invocation = buildInvocation({ tool: tool.tool, mode, transformation: t, workspace: p.workspace, configFile, initScriptFile });
  if (invocation.initScript) fs.writeFileSync(invocation.initScript.path, invocation.initScript.content);
  record.command = sanitiseCommand(tool.display || tool.command, invocation.args, { workspace: p.workspace, session: p.root });
  record.goal = invocation.goal;

  const treeBefore = workspaceTree(p.workspace);
  const checkpoint = createCheckpoint(p.workspace, `${id}-before`, `04D checkpoint before ${id} (${mode})`);
  record.checkpoint = { ref: checkpoint.ref, commit: checkpoint.commit };
  if (fs.existsSync(invocation.patchFile)) fs.rmSync(invocation.patchFile);

  const result = runTool(tool.command, invocation.args, { cwd: p.workspace, env: envForJdk(jdk), timeout: timeoutMs });
  const rawLog = `${result.stdout}\n${result.stderr}${result.error ? `\n[spawn] ${result.error}` : ''}`;
  fs.writeFileSync(files.log, redact(stripRootFromText(rawLog, p.workspace)));
  record.log = rel(files.log);
  record.exit_code = result.status;
  record.attribution = parseAttribution(rawLog);

  if (result.status !== 0) {
    const cls = result.error && /ETIMEDOUT|timed out/i.test(result.error)
      ? { available: true, reason: 'timed-out' }
      : classifyFailure(rawLog);
    record.availability = cls;
    if (workspaceTree(p.workspace) !== treeBefore) {
      restoreCheckpoint(p.workspace, checkpoint.commit);
      record.notes.push('the failed run had modified the sandbox; restored to the checkpoint');
    }
    return finish(cls.available ? 'failed' : 'unavailable');
  }
  record.availability = { available: true, reason: null };

  if (mode === 'dry-run') {
    const patchText = fs.existsSync(invocation.patchFile) ? fs.readFileSync(invocation.patchFile, 'utf8') : '';
    const treeAfter = workspaceTree(p.workspace);
    if (treeAfter !== treeBefore) {
      restoreCheckpoint(p.workspace, checkpoint.commit);
      record.notes.push('dry-run changed sandbox source (it must not); the sandbox was restored and the preview is void');
      return finish('failed');
    }
    if (!patchText.trim()) return finish('no-changes');
    fs.writeFileSync(files.patch, redact(patchText));
    record.patch = rel(files.patch);
    record.proposed_files = filesInPatch(patchText);
    return finish('previewed');
  }

  // apply
  if (exclude.length) {
    const git = gitIn(p.workspace);
    for (const file of exclude) {
      const existed = git('cat-file', '-e', `${checkpoint.commit}:${file}`).status === 0;
      if (existed) git('checkout', checkpoint.commit, '--', file);
      else if (fs.existsSync(path.join(p.workspace, file))) fs.rmSync(path.join(p.workspace, file));
      record.excluded_files.push(file);
    }
    record.notes.push(`restored ${exclude.length} out-of-scope file(s) from the checkpoint: ${exclude.join(', ')}`);
  }
  const changed = changedSince(p.workspace, checkpoint.commit);
  const patchText = diffSince(p.workspace, checkpoint.commit);
  record.changed_files = changed;
  if (patchText.trim()) {
    fs.writeFileSync(files.patch, patchText);
    record.patch = rel(files.patch);
  }
  const proposed = new Set(preview.proposed_files || []);
  const outOfPreview = changed.map((c) => c.file.split(' -> ').pop()).filter((f) => !proposed.has(f));
  const infra = infrastructureLines(patchText);
  record.scope_check = { ok: !outOfPreview.length && !infra.length, out_of_preview: outOfPreview, infrastructure_lines: infra };
  if (!record.scope_check.ok) {
    restoreCheckpoint(p.workspace, checkpoint.commit);
    record.notes.push('apply touched files outside the inspected preview or installed OpenRewrite build infrastructure; reverted to the checkpoint');
    return finish('reverted');
  }
  if (!changed.length) return finish('no-changes');

  record.applied = true;
  const declared = declaredPlatformVersion(inventoryProject(p.workspace), detect);
  const requested = vars.target_platform_version || null;
  record.reconciliation = {
    requested_platform_version: requested,
    declared_after_apply: declared ? declared.version : null,
    recipe_target: t.recipe_target || null,
    status: !requested ? 'no-request-recorded'
      : (declared && declared.version === requested ? 'matches-request' : 'reconcile-required'),
  };
  if (record.reconciliation.status === 'reconcile-required') {
    record.notes.push(`the recipe left ${declared ? declared.version : 'no platform version'}; the requested target is ${requested} — move it explicitly and verify with a build`);
  }
  return finish('applied');
}

/** Links a transformation record to the build round that verified it. */
function linkBuildRound(slug, recordIdValue, round) {
  const file = path.join(sessionPaths(slug).transformationsDir, `${recordIdValue}.json`);
  const record = readJson(file);
  if (!record) return null;
  record.build_round = round;
  writeJson(file, record);
  return record;
}

module.exports = {
  PROVIDER, MODES, SUPPORTED_TOOLS,
  selectTransformation, buildInvocation, sanitiseCommand, assertSandbox, gradleInitScript,
  renderRecipeConfig, classifyFailure, infrastructureLines, executeTransformation, linkBuildRound, recordId,
  parseAttribution,
};
