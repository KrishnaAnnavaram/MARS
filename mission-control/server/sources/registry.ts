/**
 * Harness registry, discovered from the filesystem: agents (.claude/agents, .github/agents), skills
 * (SKILL.md frontmatter, scripts, schemas, policies, package.json) and drift between what the docs
 * claim and what exists. Nothing here is a hardcoded count.
 */
import type { AgentInfo, Registry, ScriptInfo, SkillInfo, StageId } from '../../shared/types.js';
import { STAGES } from '../../shared/stages.js';
import { classifier } from '../lib/harness.js';
import { plain, findTable, col } from '../lib/md.js';
import type { Workspace } from './workspace.js';

export function frontmatter(text: string | null): { data: Record<string, string>; body: string } {
  if (!text) return { data: {}, body: '' };
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!m) return { data: {}, body: text };
  const data: Record<string, string> = {};
  for (const line of m[1].split(/\r?\n/)) {
    const kv = /^([A-Za-z0-9_-]+):\s*(.*)$/.exec(line);
    if (!kv) continue;
    let v = kv[2].trim();
    if ((v.startsWith("'") && v.endsWith("'")) || (v.startsWith('"') && v.endsWith('"'))) v = v.slice(1, -1).replace(/''/g, "'");
    data[kv[1]] = v;
  }
  return { data, body: text.slice(m[0].length) };
}

function parseTools(v: string | undefined): string[] {
  if (!v) return [];
  return v.replace(/^\[|\]$/g, '').split(',').map((s) => s.trim()).filter(Boolean);
}

function usageOf(text: string | null): string | null {
  if (!text) return null;
  const m = /Usage:\s*\n?\s*\*?\s*(node [^\n]+)/.exec(text);
  if (m) return m[1].replace(/^\*\s*/, '').trim();
  const first = /\/\*\*\s*\n\s*\*\s*([^\n]+)/.exec(text);
  return first ? first[1].trim() : null;
}

function normalizeMirror(t: string): string {
  return t.replace(/\.github/g, '.claude').replace(/\r\n/g, '\n');
}

/** Which stages an agent owns, from the derived stage registry. */
function stagesOf(agentId: string): StageId[] {
  return STAGES.filter((s) => s.owner === agentId).map((s) => s.id);
}

const ENFORCED: { re: RegExp; by: string }[] = [
  { re: /not exactly `?Approved`?|plan whose Status is not exactly/i, by: '04b-fixer/verify-patch.js and 04c-dependency-upgrader/apply-version-bump.js refuse unless the Status cell reads Approved' },
  { re: /edit any real source file|real working tree/i, by: 'gate scripts apply patches only inside `git worktree add --detach … HEAD`, removed in finally' },
  { re: /reclassify a Failed build|soften/i, by: 'render-build-report.js / render-qa-report.js derive Status from result.passed (exit codes)' },
  { re: /recompute the score|state a number that disagrees/i, by: 'render-verdict.js reads score.json written by compute-score.js' },
  { re: /override\.applied: true/i, by: 'render-verdict.js accepts an override only from computed Cleared to Blocked (checked by pipeline-lint.js)' },
];

export function loadRegistry(ws: Workspace): Registry {
  const cls = classifier(ws);
  const drift: Registry['drift'] = [];
  const contract = ws.read('.claude/pipeline-contract.md') || '';
  const ownership = findTable(contract, 'Agent', 'Reads', 'Writes');
  const writesByAgent = new Map<string, string>();
  if (ownership) {
    const ai = col(ownership, 'Agent');
    const wi = col(ownership, 'Writes');
    for (const r of ownership.rows) writesByAgent.set((plain(r[ai]) || '').trim(), plain(r[wi]) || '');
  }

  // ---- skills ---------------------------------------------------------------------------------
  const skillIds = Array.from(new Set([...ws.dirs('.claude/skills'), ...ws.dirs('.github/skills')])).sort();
  const scripts: ScriptInfo[] = [];
  const skills: SkillInfo[] = [];
  for (const id of skillIds) {
    const claudeMd = ws.read(`.claude/skills/${id}/SKILL.md`);
    const githubMd = ws.read(`.github/skills/${id}/SKILL.md`);
    const fm = frontmatter(claudeMd || githubMd);
    const pointerMatch = /Canonical instructions[^`]*`([^`]+)`/.exec(fm.body);
    const pointer = pointerMatch ? pointerMatch[1] : null;
    const baseDir = claudeMd && !pointer ? `.claude/skills/${id}` : (githubMd ? `.github/skills/${id}` : `.claude/skills/${id}`);
    const scriptFiles = ws.list(`${baseDir}/scripts`, (n) => /\.(js|mjs|py)$/.test(n));
    const skillScripts: ScriptInfo[] = scriptFiles.map((f) => {
      const name = f.path.split('/').pop()!.replace(/\.(js|mjs|py)$/, '');
      return { id: `${id}/${name}`, skill: id, name, role: cls.scriptRole(name), usage: usageOf(ws.read(f.path)), file: f.path };
    });
    scripts.push(...skillScripts);
    const schemas = ws.list(`${baseDir}/templates`, (n) => n.endsWith('.schema.json')).map((f) => f.path);
    const policyFiles = [
      ...ws.list(baseDir, (n) => n.endsWith('.json') && n !== 'package.json' && n !== 'package-lock.json'),
      ...ws.list(`${baseDir}/catalog`, (n) => n.endsWith('.json')),
      ...ws.list(`${baseDir}/knowledge`, (n) => n.endsWith('.json')),
    ].map((f) => ({ path: f.path, sha256: ws.sha256(f.path) }));
    const pkg = ws.readJson<{ dependencies?: Record<string, string>; optionalDependencies?: Record<string, string> }>(`${baseDir}/package.json`) || {};
    const npm = Object.keys(pkg.dependencies || {});
    const optional = Object.keys(pkg.optionalDependencies || {});
    const mirrorExists = Boolean(claudeMd) && Boolean(githubMd);
    let differs: boolean | null = null;
    if (mirrorExists) differs = normalizeMirror(claudeMd || '') !== normalizeMirror(githubMd || '');
    skills.push({
      id,
      name: fm.data.name || id,
      description: fm.data.description || '',
      argumentHint: fm.data['argument-hint'] || null,
      agents: [],
      stage: (cls.SKILL_STAGE[id] as SkillInfo['stage']) || null,
      scripts: skillScripts,
      schemas,
      policies: policyFiles,
      dependencies: {
        npm,
        optional,
        neo4j: npm.includes('neo4j-driver'),
        python: ws.exists(`${baseDir}/requirements.txt`) || skillScripts.some((s) => s.file.endsWith('.py')),
        llmApi: optional.some((d) => /anthropic|openai|google/.test(d)),
        zeroDependency: npm.length === 0,
      },
      mirror: { exists: mirrorExists, differs, onlyInGithub: !claudeMd && Boolean(githubMd) },
      pointer,
      telemetry: null,
      skillMdPath: claudeMd ? `.claude/skills/${id}/SKILL.md` : `.github/skills/${id}/SKILL.md`,
    });
  }

  // ---- agents ---------------------------------------------------------------------------------
  const agentFiles = ws.list('.claude/agents', (n) => n.endsWith('.agent.md'));
  const agents: AgentInfo[] = [];
  for (const f of agentFiles) {
    const text = ws.read(f.path) || '';
    const fm = frontmatter(text);
    const id = fm.data.name || f.path.split('/').pop()!.replace('.agent.md', '');
    const skillRefs = Array.from(new Set(Array.from(fm.body.matchAll(/\.claude\/skills\/([0-9a-z-]+)\//g)).map((m) => m[1]))).filter((s) => skillIds.includes(s)).sort();
    const githubPath = `.github/agents/${f.path.split('/').pop()}`;
    const gh = ws.read(githubPath);
    const constraints = Array.from(fm.body.matchAll(/^-\s+(DO NOT[^\n]+(?:\n {2}[^\n-][^\n]*)*)/gm)).map((m) => {
      const t = plain(m[1].replace(/\s*\n\s*/g, ' ')) || '';
      const enforced = ENFORCED.find((e) => e.re.test(t));
      return { text: t, enforcedBy: enforced ? enforced.by : null, contradicts: null as string | null };
    });
    const stages = stagesOf(id);
    agents.push({
      id,
      file: f.path,
      description: fm.data.description || '',
      tools: parseTools(fm.data.tools),
      skills: skillRefs,
      stages,
      runtimes: { claude: true, github: Boolean(gh), differs: gh ? normalizeMirror(text) !== normalizeMirror(gh) : null },
      constraints,
      upstream: [],
      downstream: [],
      outputs: writesByAgent.has(`${id}`) ? [writesByAgent.get(id) as string] : (writesByAgent.get(`\`${id}\``) ? [writesByAgent.get(`\`${id}\``) as string] : []),
      telemetry: null,
    });
  }
  // contract table cells are written as `01_architect` in backticks; plain() strips them
  for (const a of agents) if (!a.outputs.length && writesByAgent.has(a.id)) a.outputs = [writesByAgent.get(a.id) as string];

  // reverse map skills → agents
  for (const s of skills) s.agents = agents.filter((a) => a.skills.includes(s.id)).map((a) => a.id);
  // upstream / downstream from the stage dependency graph
  for (const a of agents) {
    const up = new Set<string>();
    const down = new Set<string>();
    for (const sid of a.stages) {
      const st = STAGES.find((s) => s.id === sid)!;
      for (const dep of st.dependsOn) {
        const owner = STAGES.find((s) => s.id === dep)?.owner;
        if (owner && owner !== a.id) up.add(owner);
      }
      for (const other of STAGES) if (other.dependsOn.includes(sid) && other.owner !== a.id) down.add(other.owner);
    }
    a.upstream = Array.from(up);
    a.downstream = Array.from(down);
  }

  // ---- drift (computed, never asserted) -----------------------------------------------------------
  const readmeCount = (rel: string, badge: string): number | null => {
    const m = new RegExp(`${badge}-(\\d+)`).exec(ws.read(rel) || '');
    return m ? Number(m[1]) : null;
  };
  const claudeSkillCount = ws.dirs('.claude/skills').length;
  for (const [rel, label] of [['.claude/README.md', '.claude'], ['.github/README.md', '.github']] as const) {
    const claimed = readmeCount(rel, 'Skills');
    const actual = label === '.claude' ? claudeSkillCount : ws.dirs('.github/skills').length;
    if (claimed != null && claimed !== actual) drift.push({ id: `readme-skills-${label}`, title: `${rel} claims ${claimed} skills; ${actual} skill folders exist`, detail: 'Counts in documentation are hand-maintained; Mission Control derives them from the filesystem.', severity: 'minor' });
    const claimedAgents = readmeCount(rel, 'Agents');
    if (claimedAgents != null && claimedAgents !== agents.length) drift.push({ id: `readme-agents-${label}`, title: `${rel} claims ${claimedAgents} agents; ${agents.length} exist`, detail: '', severity: 'minor' });
  }
  const unwired = skills.filter((s) => !s.agents.length && s.id !== '00-issue-register');
  for (const s of unwired) drift.push({ id: `unwired-${s.id}`, title: `Skill ${s.id} is not referenced by any agent`, detail: 'It can only be invoked directly by a user; it is not a pipeline stage.', severity: 'info' });
  const notInContract = skills.filter((s) => !contract.includes(s.id) && /^0[4-7]/.test(s.id));
  if (notInContract.length) drift.push({ id: 'contract-coverage', title: `${notInContract.length} skills are not mentioned in pipeline-contract.md`, detail: notInContract.map((s) => s.id).join(', '), severity: 'info' });
  // Agent 06 prose vs the gate scripts' eligibility constant (proposal drift D5).
  const a06 = agents.find((a) => a.id === '06_additional-test-execution');
  const qaLib = ws.read('.claude/skills/06a-qa-runner/scripts/lib/qa.js') || '';
  if (a06 && /refuses outright if the fix isn't `?Compiled`?/i.test(ws.read(a06.file) || '') && /STEP2_ELIGIBLE_STATUSES\s*=\s*\[[^\]]*'Compile Failed'/.test(qaLib)) {
    drift.push({ id: 'agent06-eligibility', title: 'Agent 06 says the gates refuse a fix that is not Compiled; the gate scripts accept Compiled and Compile Failed', detail: 'The scripts and the pipeline contract agree with each other; the agent prose is stale.', severity: 'major' });
    const c = a06.constraints.find((x) => /Refused/.test(x.text));
    if (c) c.contradicts = 'gate scripts accept Compile Failed fixes';
  }
  const mirrorDiffs = skills.filter((s) => s.mirror.differs).length + agents.filter((a) => a.runtimes.differs).length;
  if (mirrorDiffs) drift.push({ id: 'mirror-drift', title: `${mirrorDiffs} agent/skill definitions differ between .claude and .github beyond path names`, detail: 'The Copilot harness in .github is not a pure path-swapped mirror; see Harness → Health for script-level differences.', severity: 'minor' });

  return { agents, skills, scripts, stages: STAGES, stagesSource: 'derived', drift };
}
