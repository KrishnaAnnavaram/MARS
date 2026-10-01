/**
 * Version Migration — the Agent 04 handoff.
 *
 * When 04_fix-generator's Stage 2 routes an Approved plan with Fix Type VERSION_MIGRATION here,
 * the migration runs exactly as a direct request would; this module only adds the two ends of the
 * contract every other Stage 2 skill already honours:
 *
 *   in   — read the plan back from docs/agent_output/04-remediation/fix_plan_<id>.md, and refuse
 *          unless its Status cell reads exactly `Approved` and its Fix Type is VERSION_MIGRATION.
 *          The migration request (project, target version, target Java) comes from the
 *          machine-readable block the Fix Strategist rendered into that plan.
 *   out  — write the standard fix_<id>.md + fix_<id>.diff pair that agents 05, 06 and 07 consume,
 *          with the same Status vocabulary (Compiled | Compile Failed | Refused). The diff is the
 *          cumulative migration patch re-rooted at the repository root, so it applies with
 *          `git apply` from there like every other fix. The detailed migration report, diff and
 *          per-run summary stay where they are and are linked, never copied or summarised away.
 *
 * Nothing here edits a plan, the project, or another skill's files.
 */
const fs = require('fs');
const path = require('path');
const {
  REPO_ROOT, sessionPaths, readJson, rel, run, isSandboxRepo,
} = require('./migration');

const PLANS_DIR = process.env.PIPELINE_OUTPUT_DIR
  ? path.resolve(process.env.PIPELINE_OUTPUT_DIR)
  : path.join(REPO_ROOT, 'docs', 'agent_output', '04-remediation');

const MIGRATION_REQUEST_MARKER = '04d-migration-request';

const planPathFor = (id) => path.join(PLANS_DIR, `fix_plan_${id}.md`);
const fixReportPathFor = (id) => path.join(PLANS_DIR, `fix_${id}.md`);
const fixDiffPathFor = (id) => path.join(PLANS_DIR, `fix_${id}.diff`);

function cell(text, label) {
  const m = new RegExp(`\\|\\s*\\*\\*${label}\\*\\*\\s*\\|\\s*([^|]+)\\|`).exec(text);
  return m ? m[1].trim() : null;
}

function sectionBullets(text, heading) {
  const m = new RegExp(`##\\s+${heading}\\s*\\r?\\n([\\s\\S]*?)(?=\\r?\\n##\\s)`).exec(text);
  if (!m) return [];
  return [...m[1].matchAll(/^-\s+(.+)$/gm)].map((b) => b[1].trim());
}

/** The plan as Stage 2 sees it, or null when there is no plan for this id. Read-only. */
function readMigrationPlan(issueId) {
  const file = planPathFor(issueId);
  if (!fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8');
  const fixTypeCell = cell(text, 'Fix Type');
  const fixType = fixTypeCell ? ((/`?([A-Z_]{4,})`?/.exec(fixTypeCell) || [])[1] || null) : null;
  let request = null;
  const marker = new RegExp(`<!--\\s*${MIGRATION_REQUEST_MARKER}\\s+(\\{[\\s\\S]*?\\})\\s*-->`).exec(text);
  if (marker) {
    try { request = JSON.parse(marker[1]); } catch { request = { parse_error: true }; }
  }
  const title = /^##\s+(?!\d)(.+)$/m.exec(text);
  const cwe = /\|\s*\*\*CWE\*\*\s*\|\s*`([^`]+)`/.exec(text);
  return {
    id: issueId,
    file,
    relativeFile: rel(file),
    title: title ? title[1].trim() : issueId,
    status: cell(text, 'Status'),
    approvedBy: cell(text, 'Approved by'),
    fixType,
    cwe: cwe ? cwe[1].trim() : null,
    request,
    routingEvidence: sectionBullets(text, 'Routing decision'),
    // Repo-relative files the plan's "Planned changes" table names (links are depth-agnostic).
    plannedFiles: (() => {
      const m = /##\s+\d*\.?\s*Planned changes\s*\r?\n([\s\S]*?)(?=\r?\n##\s)/.exec(text);
      return m ? [...new Set([...m[1].matchAll(/\]\((?:\.\.\/)+([^)]+)\)/g)].map((x) => x[1].trim()))] : [];
    })(),
  };
}

/** null when Stage 2 may act on the plan, otherwise the reason it may not. */
function gateMigrationPlan(plan) {
  if (!plan) return 'no fix plan exists for this issue — run the Fix Strategist (Stage 1) first';
  if (plan.status !== 'Approved') {
    return `fix plan Status is "${plan.status || 'unknown'}", not "Approved" — only a human edit of the Status cell (or an explicitly recorded controlled approval) authorises Stage 2`;
  }
  if (plan.fixType !== 'VERSION_MIGRATION') {
    return `fix plan Fix Type is "${plan.fixType || 'not recorded'}", not VERSION_MIGRATION — Stage 2 routes it to ${plan.fixType === 'DEPENDENCY_UPGRADE' ? '04c-dependency-upgrader' : '04b-fixer'}, not here`;
  }
  if (!plan.request || plan.request.parse_error) {
    return 'the plan carries no readable 04d-migration-request block — re-render it with the Fix Strategist';
  }
  const missing = ['project', 'target_version'].filter((k) => !plan.request[k]);
  if (missing.length) return `the plan's migration request is missing ${missing.join(', ')}`;
  return null;
}

function approvalMode(plan) {
  return plan && plan.approvedBy ? plan.approvedBy : 'HUMAN — Status cell edited to Approved';
}

// ---------------------------------------------------------------------------
// Out — the standard fix_<id>.md + fix_<id>.diff
// ---------------------------------------------------------------------------

/** The sandbox's cumulative patch, re-rooted at the repository root (paths prefixed with the project dir). */
function repoRootedPatch(slug, projectRel) {
  const paths = sessionPaths(slug);
  const meta = readJson(paths.workspaceMeta);
  if (!meta || !isSandboxRepo(paths.workspace)) return null;
  const prefix = projectRel && projectRel !== '.' && projectRel !== '' ? `${projectRel.replace(/\/+$/, '')}/` : '';
  run('git', ['-C', paths.workspace, 'add', '-A']);
  const diff = run('git', ['-C', paths.workspace, 'diff', '--cached', '--binary',
    `--src-prefix=a/${prefix}`, `--dst-prefix=b/${prefix}`, meta.baseline_commit]);
  return diff.stdout || '';
}

/**
 * Downstream agents apply fix_<id>.diff with `git apply` inside a worktree of HEAD, so the handoff
 * patch must apply to what the repository *stores*, not to the working copy the sandbox was copied
 * from. The sandbox copies the working tree verbatim; when that tree's line endings differ from the
 * index (a CRLF checkout of an `eol=lf` repository, say), the raw patch does not apply. This checks
 * the patch against the repository index — read-only (`git apply --cached --check`) — and, if only
 * the LF-normalised form applies, hands that one over and says so. If neither applies, the caller
 * reports the fix as Compile Failed rather than handing over a patch that cannot be applied.
 */
function applicableToHead(patchText) {
  const check = (text) => {
    const tmp = path.join(require('os').tmpdir(), `04d-handoff-${process.pid}-${Date.now()}.diff`);
    fs.writeFileSync(tmp, text);
    try {
      const r = run('git', ['-C', REPO_ROOT, 'apply', '--cached', '--check', tmp]);
      return { ok: r.status === 0, error: (r.stderr || r.stdout || '').trim().split(/\r?\n/).slice(0, 3).join(' ') };
    } finally {
      fs.rmSync(tmp, { force: true });
    }
  };
  const raw = check(patchText);
  if (raw.ok) return { text: patchText, applies: true, normalized: false };
  if (/\r\n/.test(patchText)) {
    const lf = patchText.replace(/\r\n/g, '\n');
    if (check(lf).ok) return { text: lf, applies: true, normalized: true };
  }
  return { text: patchText, applies: false, normalized: false, error: raw.error };
}

function filesInPatchText(text) {
  return [...String(text || '').matchAll(/^diff --git a\/(\S+) b\/(\S+)$/gm)].map((m) => m[2]);
}

const UP_TO_ROOT = path.relative(PLANS_DIR, REPO_ROOT).replace(/\\/g, '/');
const link = (abs, label) => {
  const target = path.isAbsolute(abs) ? abs : path.join(REPO_ROOT, abs);
  const repoRel = path.relative(REPO_ROOT, target).replace(/\\/g, '/');
  const href = repoRel.startsWith('..') ? target.replace(/\\/g, '/') : `${UP_TO_ROOT}/${repoRel}`;
  return `[${label || path.basename(target)}](${href})`;
};

/**
 * Writes fix_<id>.md (+ fix_<id>.diff when a patch exists) for an issue-linked session. Returns
 * null when the session is not issue-linked or not finished — an unfinished migration is never
 * handed downstream.
 */
function writeStandardHandoff(slug, summary) {
  const paths = sessionPaths(slug);
  const baseline = readJson(paths.baseline);
  if (!baseline || !baseline.issue || !baseline.issue.id) return null;
  const id = baseline.issue.id;
  const status = summary.final_status;
  if (status === 'IN_PROGRESS') return null;

  const plan = readMigrationPlan(id);
  const migration = readJson(paths.migration) || {};
  const refused = status === 'BLOCKED';
  const projectRel = (plan && plan.request && plan.request.project) || baseline.project.relative_to_repo;

  let patchText = null;
  let applyCheck = null;
  if (!refused) {
    patchText = repoRootedPatch(slug, projectRel);
    if (patchText) {
      applyCheck = applicableToHead(patchText);
      patchText = applyCheck.text;
    }
  }
  const files = filesInPatchText(patchText);
  const fixStatus = refused ? 'Refused'
    : (patchText && applyCheck && applyCheck.applies && summary.compiled_on_target && summary.target_reached ? 'Compiled' : 'Compile Failed');
  summary.validation = [...(summary.validation || []), ...(applyCheck ? [['Patch applies to repository HEAD (git apply --cached --check)',
    applyCheck.applies
      ? `yes${applyCheck.normalized ? ' — after normalising CRLF to LF (the sandbox was copied from a working tree whose line endings differ from the index)' : ''}`
      : `NO — ${applyCheck.error || 'git apply rejected the patch'}`]] : [])];

  fs.mkdirSync(PLANS_DIR, { recursive: true });
  const diffFile = fixDiffPathFor(id);
  if (patchText !== null && !refused) fs.writeFileSync(diffFile, patchText);

  const v = summary.versions || {};
  const src = `${v.source_platform_name || 'platform'} ${v.source_platform || '?'} · Java ${v.source_java || '?'}`;
  const tgt = `${v.target_platform_name || 'platform'} ${v.target_platform || '?'} · Java ${v.target_java || '?'}`;
  const out = [];
  out.push(`# Fix — ${id}`, '');
  out.push(`## ${(plan && plan.title) || id}`, '');
  out.push(refused
    ? `> Version migration of \`${projectRel}\` was **not performed**: ${summary.status_reason || 'the migration was blocked'}.`
    : `> Version migration of \`${projectRel}\`: ${src} → ${tgt}, performed by \`04d-version-migration\` in a sandbox. Migration result: **${status}**.`);
  out.push('');
  out.push(`_Written by 04d-version-migration (Stage 2 of 04_fix-generator, routed on Fix Type VERSION_MIGRATION) on ${new Date().toISOString().slice(0, 10)} from migration run \`${summary.run_id}\`._`, '');

  out.push('## At a glance', '');
  out.push('| | |', '|---|---|');
  out.push(`| **Status** | ${fixStatus} |`);
  out.push(`| **CWE** | ${plan && plan.cwe ? `\`${plan.cwe}\`` : 'n/a'} |`);
  out.push('| **Fix Type** | `VERSION_MIGRATION` |');
  out.push('| **Migration Skill** | `04d-version-migration` (04D) |');
  out.push(`| **Fix plan** | ${plan ? link(plan.file, path.basename(plan.file)) : 'n/a'} |`);
  out.push(`| **Files changed** | ${files.length} |`);
  out.push(`| **Verification level** | ${refused ? 'none — migration not performed' : `04D build rounds (${summary.compile_history.length}) on JDK ${v.target_java || '?'}${summary.runtime.final_probed ? ' + before/after runtime probes' : ''}`} |`);
  // Target always comes from the plan; the file set is compared with the plan's Planned changes.
  const beyondPlan = plan && plan.plannedFiles.length ? files.filter((f) => !plan.plannedFiles.includes(f)) : [];
  out.push(`| **Matches plan** | ${refused ? 'n/a' : (beyondPlan.length
    ? `no — target from the plan's migration request, but ${beyondPlan.length} changed file(s) are not in its Planned changes: ${beyondPlan.map((f) => `\`${f}\``).join(', ')} (see the migration report for the evidence that required them)`
    : 'yes — target from the plan\'s migration request; every changed file is in its Planned changes')} |`);
  if (!refused && patchText !== null) out.push(`| **Patch** | ${link(diffFile)} |`);
  out.push(`| **Migration Status** | ${status} |`);
  out.push(`| **Source Version** | ${src} |`);
  out.push(`| **Target Version** | ${tgt} |`);
  out.push(`| **Target Java** | ${v.target_java || 'n/a'} |`);
  out.push(`| **Migration Report** | ${summary.paths.report_md_exists ? link(summary.paths.report_md) : '_not rendered_'} |`);
  out.push(`| **Migration Diff** | ${summary.paths.report_diff_exists ? link(summary.paths.report_diff) : '_none_'} |`);
  out.push(`| **Migration Summary** | ${link(summary.paths.summary_md, 'MIGRATION_SUMMARY.md')} · ${link(summary.paths.summary_json, 'migration-summary.json')} |`);
  out.push(`| **Migration Run** | \`${summary.run_id}\` |`);
  out.push(`| **Approval** | ${(summary.metadata && summary.metadata.approval_mode) || 'n/a'} |`);
  out.push('');

  out.push('## 1. What changed', '');
  if (!files.length) out.push(refused ? '_Nothing — the migration did not start._' : '_No file changed._');
  const byFile = new Map((summary.file_changes || []).map((f) => [f.file, f]));
  for (const f of files) {
    const local = projectRel && f.startsWith(`${projectRel}/`) ? f.slice(projectRel.length + 1) : f;
    const info = byFile.get(local);
    out.push(`- ${link(f, f)}${info ? ` — ${info.change}${info.tool ? `, via ${info.tool}` : ''}${info.reason ? `: ${info.reason}` : ''}` : ''}`);
  }
  out.push('');

  out.push('## 2. The diff', '');
  out.push(refused
    ? 'No diff — the migration was refused before anything changed.'
    : `${patchText !== null ? link(diffFile) : '_no patch_'} is the 04D cumulative migration patch re-rooted at the repository root (paths prefixed with \`${projectRel}/\`) so it applies with \`git apply\` from the root like every other fix. The project-relative original is ${summary.paths.report_diff_exists ? link(summary.paths.report_diff) : '_not exported_'}.`);
  out.push('');

  out.push('## 3. Why this is the smallest correct diff', '');
  out.push(refused ? `Not applicable — ${summary.status_reason || 'blocked'}.` : (migration.summary || 'See the migration report.'));
  if ((migration.out_of_scope_changes || []).length) {
    out.push('', 'Rejected as out of scope (kept out of this patch):');
    for (const o of migration.out_of_scope_changes) out.push(`- ${typeof o === 'string' ? o : (o.change || o.description || JSON.stringify(o))}`);
  }
  out.push('');

  out.push('## 4. Verification evidence', '');
  out.push('| Check | Result |', '|---|---|');
  for (const [k, val] of summary.validation || []) out.push(`| ${k} | ${String(val).replace(/\|/g, '\\|')} |`);
  out.push('');

  out.push('## 5. Residual risk', '');
  const risks = [...[].concat(migration.residual_risk || []), ...[].concat(migration.manual_follow_ups || [])]
    .map((r) => (typeof r === 'string' ? r : (r.item || r.description || r.risk || JSON.stringify(r))));
  if (refused) risks.unshift(summary.status_reason || 'migration blocked');
  if (!risks.length) out.push('_None recorded._');
  risks.forEach((r) => out.push(`- ${r}`));
  out.push('');

  out.push('## 6. For downstream agents', '');
  out.push('- 04D\'s own result does **not** clear this patch. Agents 05, 06 and 07 validate it independently, exactly as they would any other fix.');
  out.push(`- **05** — the patch is a whole-project migration. Baseline evidence (round 0, baseline probes, plan) is in \`${rel(paths.root)}\`; the report's §0–§6 list predicted vs. actual impact.`);
  out.push(`- **06** — build and test the patched project on the **target** JDK ${v.target_java || '?'} (point \`MIGRATION_JDK_${v.target_java || 'N'}\` at it); the source JDK is ${v.source_java || '?'}.`);
  out.push('- **07** — include the migration report and summary in the audit trail; a Migration Status other than PASS must be visible in the verdict.');
  out.push('');

  out.push('## How to apply this patch', '');
  out.push(refused ? 'Nothing to apply.' : `From the repository root, after a Cleared verdict: \`git apply ${rel(diffFile)}\`.`);
  out.push('');

  fs.writeFileSync(fixReportPathFor(id), out.join('\n'));
  return { id, status: fixStatus, report: rel(fixReportPathFor(id)), diff: patchText !== null && !refused ? rel(diffFile) : null, files: files.length };
}

module.exports = {
  PLANS_DIR, MIGRATION_REQUEST_MARKER, planPathFor, fixReportPathFor, fixDiffPathFor,
  readMigrationPlan, gateMigrationPlan, approvalMode, writeStandardHandoff, repoRootedPatch,
};
