'use strict';
/**
 * Deterministic classification of runtime activity into MARS concepts.
 * Shared by the hook adapter (mars-hook.js) and Mission Control's server so that both attribute a
 * command or a file write to the same skill / script / stage / issue. No I/O, no dependencies.
 */

const SKILL_STAGE = {
  '00-issue-register': 'intake',
  '01a-code-cartographer': 'architecture',
  '01b-context-weaver': 'architecture',
  '01c-graph-forge': 'architecture',
  '01d-blueprint-scribe': 'architecture',
  '02-root-cause-analyst': 'rca',
  '03-blast-radius-analyst': 'blast_radius',
  '04a-fix-strategist': 'plan',
  '04a1-remediation-intelligence': 'plan',
  '04a2-remediation-research': 'plan',
  '04b-fixer': 'fix',
  '04c-dependency-upgrader': 'fix',
  '04d-version-migration': 'migration',
  '05-verify': 'verify',
  '06a-qa-runner': 'qa',
  '06b-build-gatekeeper': 'build',
  '07a-merge-arbiter': 'verdict',
  '07b-scribe': 'writeup',
};

const ROLE_BY_PREFIX = [
  ['list-', 'list'], ['collect-', 'collect'], ['render-', 'render'], ['run-', 'gate'], ['verify-', 'gate'],
  ['apply-', 'gate'], ['compute-', 'score'], ['validate-', 'validate'], ['lookup-', 'list'], ['generate-', 'render'],
  ['detect-', 'collect'], ['research-', 'collect'], ['test-', 'test'], ['scan', 'collect'], ['build-', 'collect'],
  ['embed', 'collect'], ['prepare-', 'collect'], ['probe-', 'gate'],
];

function scriptRole(script) {
  for (const [prefix, role] of ROLE_BY_PREFIX) if (script.startsWith(prefix)) return role;
  return 'support';
}

const VERIFY_CHECK = { 'collect-rescan': 'rescan', 'render-rescan': 'rescan', 'collect-redteam': 'redteam', 'render-redteam': 'redteam', 'collect-behavior': 'behavior', 'render-behavior': 'behavior' };

function stageForScript(skill, script) {
  if (skill === '05-verify') return VERIFY_CHECK[script] || 'verify';
  return SKILL_STAGE[skill] || null;
}

const SAFE_FLAGS = new Set(['--all', '-a', '--json', '--pending', '--keep', '--dry-run', '--approved', '--full', '--no-graph', '--help']);
const VALUE_FLAGS = new Set(['--issue', '-i', '--existing-test', '-e', '--test', '--cwe', '--depth', '--gap-check', '--provider', '--model', '--batch-size', '--decision']);

/** Allow-listed arguments only: flags we know, plus the values of flags that carry ids. */
function sanitizeArgs(tokens) {
  const out = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const t = tokens[i];
    if (SAFE_FLAGS.has(t)) out.push(t);
    else if (VALUE_FLAGS.has(t)) {
      const v = tokens[i + 1];
      if (v && /^[A-Za-z0-9_.:-]{1,80}$/.test(v)) out.push(t, v);
      else out.push(t, '<redacted>');
      i += 1;
    }
  }
  return out;
}

function tokenize(segment) {
  const tokens = [];
  const re = /"([^"]*)"|'([^']*)'|(\S+)/g;
  let m;
  while ((m = re.exec(segment))) tokens.push(m[1] ?? m[2] ?? m[3]);
  return tokens;
}

const SCRIPT_RE = /(?:^|[\s"'/\\])(?:\.\/)?(?:(\.claude|\.github)[/\\]skills[/\\]([0-9a-z-]+)[/\\])?scripts[/\\]([A-Za-z0-9_.-]+)\.(js|mjs|py)\b/;
const HARNESS_SCRIPT_RE = /(\.claude|\.github)[/\\]scripts[/\\]([A-Za-z0-9_.-]+)\.js\b/;
const CD_SKILL_RE = /(?:\.claude|\.github)[/\\]skills[/\\]([0-9a-z-]+)/;

/**
 * Classifies a Bash command. Returns null for commands that are not MARS scripts, otherwise
 * { skill_id, script, script_id, role, step_kind, stage_id, issue_ids, scope, args, runtime }.
 */
function classifyCommand(command, cwd) {
  if (!command || typeof command !== 'string') return null;
  const segments = command.split(/&&|\|\||;|\|/);
  let cdSkill = null;
  const cwdSkill = cwd ? (CD_SKILL_RE.exec(String(cwd)) || [])[1] || null : null;
  for (const raw of segments) {
    const seg = raw.trim();
    const cdMatch = /^cd\s+(.+)$/.exec(seg);
    if (cdMatch) {
      cdSkill = (CD_SKILL_RE.exec(cdMatch[1]) || [])[1] || cdSkill;
      continue;
    }
    const harness = HARNESS_SCRIPT_RE.exec(seg);
    if (harness && /\bnode\b/.test(seg)) {
      const script = harness[2];
      const tokens = tokenize(seg);
      return {
        skill_id: null,
        script,
        script_id: `harness/${script}`,
        role: script === 'pipeline-lint' ? 'validate' : 'support',
        step_kind: script === 'pipeline-lint' ? 'validate' : 'support',
        stage_id: null,
        issue_ids: issueIds(tokens),
        scope: scope(tokens),
        args: sanitizeArgs(tokens),
        runtime: harness[1] === '.github' ? 'copilot-harness' : 'claude-harness',
      };
    }
    const m = SCRIPT_RE.exec(seg);
    if (!m || !/\b(node|python3?|py)\b/.test(seg)) continue;
    const skill = m[2] || cdSkill || cwdSkill;
    if (!skill || !SKILL_STAGE[skill]) continue;
    const script = m[3];
    const tokens = tokenize(seg);
    const role = scriptRole(script);
    return {
      skill_id: skill,
      script,
      script_id: `${skill}/${script}`,
      role,
      step_kind: role,
      stage_id: stageForScript(skill, script),
      issue_ids: issueIds(tokens),
      scope: scope(tokens),
      args: sanitizeArgs(tokens),
      runtime: m[1] === '.github' ? 'copilot-harness' : 'claude-harness',
    };
  }
  return null;
}

function issueIds(tokens) {
  const ids = [];
  for (let i = 0; i < tokens.length; i += 1) {
    if ((tokens[i] === '--issue' || tokens[i] === '-i') && tokens[i + 1]) ids.push(tokens[i + 1]);
  }
  return ids.filter((x) => /^[A-Za-z0-9_.-]{1,40}$/.test(x));
}

function scope(tokens) {
  if (tokens.includes('--all') || tokens.includes('-a')) return 'all';
  if (tokens.includes('--issue') || tokens.includes('-i')) return 'issue';
  return null;
}

/** Coarse class of a non-MARS shell command — never the command itself. */
function commandClass(command) {
  // Drop leading `cd <dir> &&` (quoted or not) and `VAR=value` assignments to find the program.
  let rest = String(command || '').trim();
  for (let i = 0; i < 4; i += 1) {
    const before = rest;
    rest = rest.replace(/^cd\s+(?:"[^"]*"|'[^']*'|\S+)\s*(?:&&|;)\s*/, '').replace(/^[A-Za-z_][A-Za-z0-9_]*=(?:"[^"]*"|'[^']*'|\S*)\s+/, '');
    if (rest === before) break;
  }
  const m = /^(?:"([^"]+)"|'([^']+)'|(\S+))/.exec(rest);
  const first = m ? m[1] || m[2] || m[3] : '';
  const prog = first.replace(/^.*[/\\]/, '').replace(/\.(cmd|exe|bat|ps1)$/i, '').toLowerCase();
  if (['git'].includes(prog)) return { program: 'git', command_class: 'vcs' };
  if (['mvn', 'mvnw', 'gradle', 'gradlew', 'java', 'javac'].includes(prog)) return { program: prog, command_class: 'build' };
  if (['npm', 'npx', 'pnpm', 'yarn'].includes(prog)) return { program: prog, command_class: 'package' };
  if (['node', 'tsx', 'python', 'python3', 'py'].includes(prog)) return { program: prog, command_class: 'runtime' };
  if (['cat', 'head', 'tail', 'grep', 'rg', 'ls', 'find', 'sed', 'awk', 'wc', 'diff', 'echo', 'pwd', 'sha256sum'].includes(prog)) return { program: prog, command_class: 'read' };
  if (['gh'].includes(prog)) return { program: 'gh', command_class: 'vcs-remote' };
  if (['curl', 'wget'].includes(prog)) return { program: prog, command_class: 'network' };
  if (['mkdir', 'cp', 'mv', 'rm', 'touch', 'printf'].includes(prog)) return { program: prog, command_class: 'filesystem' };
  return { program: prog ? 'other' : 'unknown', command_class: 'other' };
}

const EVIDENCE_DIRS = {
  '00-issues': 'intake',
  '01-architecture': 'architecture',
  '02-root-cause': 'rca',
  '03-blast-radius': 'blast_radius',
  '04-remediation': 'plan',
  '05-verify': 'verify',
  '06-test-gate': 'qa',
  '07-ship': 'verdict',
  decisions: 'approval',
};

const INTERMEDIATE_DIRS = {
  rca: 'rca', 'blast-radius': 'blast_radius', 'fix-strategy': 'plan', research: 'plan', fixer: 'fix',
  'dependency-upgrader': 'fix', verify: 'verify', qa: 'qa', build: 'build', merge: 'verdict', scribe: 'writeup', context: 'architecture',
};

const ISSUE_IN_NAME = /(?:^|[_./-])((?:ISSUE|SAMPLE)-[A-Za-z0-9-]+?)(?=\.|$)/;

function artifactTypeOf(dir, base) {
  if (dir === '00-issues') return base.endsWith('.xlsx') ? 'issue_register' : 'readme';
  if (base === 'README.md') return 'stage_index';
  const m = /^(root_cause|blast_radius|fix_plan|fix|rescan|redteam|behavior|qa|build|verdict|pr|audit)_/.exec(base);
  if (m) {
    if (m[1] === 'fix' && base.endsWith('.diff')) return 'fix_diff';
    return { root_cause: 'root_cause_report', blast_radius: 'blast_radius_report', fix_plan: 'fix_plan', fix: 'fix_report', rescan: 'rescan_report', redteam: 'redteam_report', behavior: 'behavior_report', qa: 'qa_report', build: 'build_report', verdict: 'verdict', pr: 'pr_content', audit: 'audit_trail' }[m[1]];
  }
  if (base === 'architecture.md') return 'architecture_doc';
  if (base === 'function-reference.md') return 'function_reference';
  if (/^DEC-/.test(base)) return 'decision_record';
  return 'other';
}

/** Classifies a repo-relative path. */
function classifyPath(rel) {
  const p = String(rel || '').replace(/\\/g, '/').replace(/^\.\//, '');
  let m = /^docs\/agent_output\/([^/]+)\/(.+)$/.exec(p);
  if (m) {
    const dir = m[1];
    const base = m[2].split('/').pop();
    const type = artifactTypeOf(dir, base);
    let stage = EVIDENCE_DIRS[dir] || null;
    if (type === 'fix_report' || type === 'fix_diff') stage = 'fix';
    if (type === 'rescan_report') stage = 'rescan';
    if (type === 'redteam_report') stage = 'redteam';
    if (type === 'behavior_report') stage = 'behavior';
    if (type === 'build_report') stage = 'build';
    if (type === 'pr_content' || type === 'audit_trail') stage = 'writeup';
    return {
      area: dir === 'decisions' ? 'decision' : (dir === '00-issues' ? 'register' : 'evidence'),
      stage_id: stage,
      issue_id: (ISSUE_IN_NAME.exec(base) || [])[1] || null,
      artifact_type: type,
      protected: Boolean(EVIDENCE_DIRS[dir]),
    };
  }
  m = /^docs\/agent_output\/([^/]+)$/.exec(p);
  if (m) return { area: 'evidence', stage_id: null, issue_id: null, artifact_type: 'summary', protected: false };
  m = /^\.claude\/\.pipeline-context\/([^/]+)\/(.+)$/.exec(p);
  if (m) {
    const base = m[2].split('/').pop();
    const stage = INTERMEDIATE_DIRS[m[1]] || null;
    let stageId = stage;
    const check = /\.(rescan|redteam|behavior)\./.exec(base);
    if (m[1] === 'verify' && check) stageId = check[1];
    return { area: 'intermediate', stage_id: stageId, issue_id: (ISSUE_IN_NAME.exec(base) || [])[1] || null, artifact_type: intermediateType(base), protected: false };
  }
  if (/^\.claude\/\.pipeline-context\//.test(p)) return { area: 'intermediate', stage_id: 'architecture', issue_id: null, artifact_type: 'code_model', protected: false };
  if (/^(\.claude|\.github)\//.test(p)) return { area: 'harness', stage_id: null, issue_id: null, artifact_type: 'harness_definition', protected: false };
  if (/^src\//.test(p)) return { area: 'source', stage_id: null, issue_id: null, artifact_type: 'source', protected: false };
  return { area: 'other', stage_id: null, issue_id: null, artifact_type: 'other', protected: false };
}

function intermediateType(base) {
  if (/\.facts\.(json|md)$/.test(base) || /\.evidence\.(json|md)$/.test(base) || /\.context\.(json|md)$/.test(base)) return 'facts';
  if (/\.(analysis|narrative|strategy|rationale|verdict|arbitration|content|test-plan)\.json$/.test(base)) return 'judgement';
  if (/\.(patch|new-test)\.diff$/.test(base)) return 'draft_diff';
  if (/\.(result|score|verification)\.json$/.test(base)) return 'computed_result';
  return 'intermediate';
}

module.exports = {
  SKILL_STAGE,
  classifyCommand,
  classifyPath,
  commandClass,
  sanitizeArgs,
  scriptRole,
  stageForScript,
};
