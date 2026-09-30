/**
 * Blast Radius Analyst — Mermaid diagram builders.
 *
 * These reports are read by people who did not write the code, so the diagrams carry
 * the message and the tables only back them up. Three rules keep them readable:
 *   1. One idea per diagram — spread, services, request path. Never all three at once.
 *   2. Short node labels. Detail belongs in the table underneath, not inside a box.
 *   3. The same three colours everywhere: red = broken, amber = degraded, green = fine.
 */

const STATUS = {
  BROKEN: 'Broken',
  DEGRADED: 'Degraded',
  AT_RISK: 'At risk',
  UNAFFECTED: 'Unaffected',
};

const STATUS_ICON = {
  [STATUS.BROKEN]: '🔴',
  [STATUS.DEGRADED]: '🟠',
  [STATUS.AT_RISK]: '🟡',
  [STATUS.UNAFFECTED]: '🟢',
};

const CLASS_OF = {
  [STATUS.BROKEN]: 'broken',
  [STATUS.DEGRADED]: 'degraded',
  [STATUS.AT_RISK]: 'atrisk',
  [STATUS.UNAFFECTED]: 'ok',
};

// Light fills with dark strokes and explicit text colour, so the diagrams stay legible
// in both the light and dark GitHub themes.
const CLASS_DEFS = [
  '  classDef broken fill:#ffe3e3,stroke:#c92a2a,stroke-width:2px,color:#1a1a1a',
  '  classDef degraded fill:#fff3bf,stroke:#e8590c,stroke-width:2px,color:#1a1a1a',
  '  classDef atrisk fill:#fff9db,stroke:#f08c00,stroke-width:1px,color:#1a1a1a',
  '  classDef ok fill:#e6fcf5,stroke:#2f9e44,stroke-width:1px,color:#1a1a1a',
  '  classDef neutral fill:#f1f3f5,stroke:#868e96,stroke-width:1px,color:#1a1a1a',
];

/** Mermaid node labels are quoted; strip what would break the quoting or the layout. */
function label(text) {
  return String(text ?? '')
    .replace(/["`]/g, "'")
    .replace(/[\r\n]+/g, ' ')
    .replace(/[<>|{}[\]]/g, '-')
    .trim();
}

function nodeId(prefix, value) {
  return `${prefix}_${String(value).replace(/[^A-Za-z0-9]/g, '_')}`;
}

/**
 * Diagram 1 — how far the damage spreads, as four tiers.
 * Answers "how big is this?" before any detail.
 */
function spreadDiagram({ defectSites, affectedServices, consumerServices, platformServices, userFacing }) {
  const lines = ['```mermaid', 'flowchart TD', ...CLASS_DEFS];

  const defectLabel = defectSites.length === 1
    ? label(defectSites[0].label)
    : `${defectSites.length} defect sites`;
  lines.push(`  D["🔴 Where it breaks<br/>${defectLabel}"]:::broken`);

  const servicesLabel = affectedServices.length
    ? affectedServices.map((s) => label(s)).join('<br/>')
    : 'none';
  lines.push(`  S["🔴 Service that fails<br/>${servicesLabel}"]:::broken`);

  const consumersLabel = consumerServices.length
    ? consumerServices.map((s) => label(s)).join('<br/>')
    : 'no other service calls it';
  lines.push(`  C["${consumerServices.length ? '🟠' : '🟢'} Services that call it<br/>${consumersLabel}"]:::${consumerServices.length ? 'degraded' : 'ok'}`);

  // These are endpoints that are down, so they carry the broken colour, not the
  // degraded one — the amber tier above is about services, this tier is about loss.
  const usersLabel = userFacing.length ? userFacing.map((u) => label(u)).join('<br/>') : 'no public endpoint on the failing path';
  lines.push(`  U["${userFacing.length ? '🔴' : '🟢'} What callers lose<br/>${usersLabel}"]:::${userFacing.length ? 'broken' : 'ok'}`);

  lines.push('  D --> S --> C --> U');

  if (platformServices.length) {
    lines.push(`  P["🟢 Shared platform, unaffected<br/>${platformServices.map((s) => label(s)).join('<br/>')}"]:::ok`);
    lines.push('  S -.-> P');
  }

  lines.push('```');
  return lines.join('\n');
}

/**
 * Diagram 2 — the service map, colour-coded by status.
 * Answers "which services do I need to care about?" at a glance.
 */
function serviceMapDiagram({ serviceStatus, httpEdges, platform }) {
  const lines = ['```mermaid', 'flowchart LR', ...CLASS_DEFS];

  for (const [service, status] of Object.entries(serviceStatus)) {
    const id = nodeId('svc', service);
    lines.push(`  ${id}["${STATUS_ICON[status]} ${label(service)}<br/>${status}"]:::${CLASS_OF[status]}`);
  }

  for (const edge of httpEdges) {
    lines.push(`  ${nodeId('svc', edge.from)} -->|"${label(edge.label || 'calls over HTTP')}"| ${nodeId('svc', edge.to)}`);
  }

  // Infrastructure shown with dotted edges so it never competes with the HTTP calls.
  if (platform.discoveryServer) {
    for (const client of platform.registersWithDiscovery) {
      if (!serviceStatus[client] || client === platform.discoveryServer) continue;
      lines.push(`  ${nodeId('svc', client)} -.->|registers| ${nodeId('svc', platform.discoveryServer)}`);
    }
  }
  if (platform.configServer) {
    for (const client of platform.readsConfig) {
      if (!serviceStatus[client] || client === platform.configServer) continue;
      lines.push(`  ${nodeId('svc', client)} -.->|config| ${nodeId('svc', platform.configServer)}`);
    }
  }

  lines.push('```');
  return lines.join('\n');
}

/**
 * Diagram 3 — one request from caller to failure point.
 * Answers "what actually happens when someone tries?"
 */
function requestPathDiagram({ entryPoint, chain, failureNote }) {
  const lines = ['```mermaid', 'flowchart LR', ...CLASS_DEFS];
  lines.push(`  CALLER(["Caller"]):::neutral`);
  lines.push(`  EP["${label(entryPoint || 'entry point')}"]:::neutral`);
  lines.push('  CALLER --> EP');

  let previous = 'EP';
  chain.forEach((step, index) => {
    const isLast = index === chain.length - 1;
    const id = `N${index}`;
    lines.push(`  ${id}["${label(step)}"]:::${isLast ? 'broken' : 'neutral'}`);
    lines.push(`  ${previous} --> ${id}`);
    previous = id;
  });

  lines.push(`  FAIL["❌ ${label(failureNote || 'fails here')}"]:::broken`);
  lines.push(`  ${previous} --> FAIL`);
  lines.push('```');
  return lines.join('\n');
}

module.exports = {
  STATUS,
  STATUS_ICON,
  CLASS_OF,
  CLASS_DEFS,
  label,
  nodeId,
  spreadDiagram,
  serviceMapDiagram,
  requestPathDiagram,
};
