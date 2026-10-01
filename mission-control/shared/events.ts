/** Human-readable names for ledger events and operations, shared by the server projection and the UI. */
import type { LedgerEvent } from './types.js';

export function opName(e: LedgerEvent): string {
  if (e.script_id) {
    const args = Array.isArray(e.attrs?.args) ? (e.attrs?.args as string[]).join(' ') : '';
    return `${e.script_id.split('/').pop()}${args ? ` ${args}` : ''}`;
  }
  const a = e.attrs || {};
  if (a.command_class) {
    const prog = String(a.program || '');
    // Commands outside the MARS harness are named by program only; their text is never recorded.
    return prog && prog !== 'other' && prog !== 'unknown' ? `${a.tool || 'Bash'}: ${prog} (${a.command_class})` : `${a.tool || 'Bash'}: shell command (not a MARS script)`;
  }
  return String(a.tool || e.type);
}

export function eventTitle(e: LedgerEvent): string {
  const a = e.attrs || {};
  switch (e.type) {
    case 'gate.completed': return `${e.stage_id?.toUpperCase()} gate: ${e.outcome}${e.failure?.class ? ` (${e.failure.class})` : ''}`;
    case 'fix.verified': return `Patch verified: ${e.outcome}`;
    case 'fix.refused': return 'Fixer refused';
    case 'verdict.computed': return `Score computed: ${e.outcome} ${a.score ?? '?'}/${a.threshold ?? '?'}`;
    case 'approval.recorded': return `Plan ${String(e.outcome || '').toLowerCase()} by ${e.actor?.id}`;
    case 'guard.would_deny':
    case 'guard.denied': {
      if (a.rule === 'agent-ran-decision-command') return 'An agent tried to run the human decision command — denied';
      const what = a.path ? `agent edit of ${a.path}` : 'agent edit of protected evidence';
      return e.type === 'guard.denied' ? `Evidence guard: ${what} denied` : `Evidence guard: ${what} (observed, allowed)`;
    }
    case 'skill.loaded': return `Skill loaded: ${e.skill_id} (${a.via || 'skill'})`;
    case 'human.waiting': return `Waiting for a human (${a.kind || 'prompt'})`;
    case 'artifact.written': {
      const p = (e.outputs || [])[0]?.path;
      if (p && !p.startsWith('<')) return `Wrote ${p}`;
      return `Wrote a file outside MARS evidence${a.area ? ` (${a.area})` : ''}; path not recorded`;
    }
    case 'script.started': return `Script started: ${e.script_id}`;
    case 'script.completed': case 'script.failed': return `Script ${e.status}: ${e.script_id}`;
    case 'session.started': return 'Session started';
    case 'session.ended': return 'Session ended';
    case 'agent_run.started': return `Agent run started: ${e.agent_id}`;
    case 'agent_run.completed': return `Agent run completed: ${e.agent_id}`;
    case 'operation.started': return `Started ${opName(e)}`;
    case 'operation.completed': return `Finished ${opName(e)}`;
    case 'operation.failed': return `Failed ${opName(e)}${e.failure?.code ? ` (${e.failure.code})` : ''}`;
    default: return e.type;
  }
}

/** MARS pipeline agents are named 01_… to 07_…; anything else is ordinary Claude activity. */
export function isMarsAgent(agent: string | null | undefined): boolean {
  return Boolean(agent && /^0\d_[a-z0-9-]+$/i.test(agent));
}
