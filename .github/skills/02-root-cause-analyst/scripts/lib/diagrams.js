/**
 * Root Cause Analyst — Mermaid diagram builders.
 *
 * A root cause report is read by whoever has to decide what to do about the defect,
 * not only by the engineer who will fix it. Three rules keep the diagrams readable:
 *   1. One idea per diagram — the contrast, the sequence, the location. Never all three.
 *   2. Short node labels. Detail belongs in the list underneath, not inside a box.
 *   3. One colour meaning throughout: green = correct, red = where it goes wrong.
 *
 * Self-contained by design — this skill folder can be copied out on its own.
 */

// Light fills with dark strokes and explicit text colour, so the diagrams stay legible
// in both the light and dark GitHub themes.
const CLASS_DEFS = [
  '  classDef broken fill:#ffe3e3,stroke:#c92a2a,stroke-width:2px,color:#1a1a1a',
  '  classDef warn fill:#fff3bf,stroke:#e8590c,stroke-width:2px,color:#1a1a1a',
  '  classDef ok fill:#e6fcf5,stroke:#2f9e44,stroke-width:1px,color:#1a1a1a',
  '  classDef neutral fill:#f1f3f5,stroke:#868e96,stroke-width:1px,color:#1a1a1a',
];

/** Mermaid node labels are quoted; strip what would break the quoting or the layout. */
function clean(text) {
  return String(text ?? '')
    .replace(/["`]/g, "'")
    .replace(/[\r\n]+/g, ' ')
    .replace(/[<>|{}[\]]/g, '-')
    .trim();
}

/**
 * Wrap onto several lines rather than cutting mid-sentence — a box that ends in "…"
 * tells the reader nothing, and Mermaid does not wrap for us.
 */
function label(text, { width = 30, maxLines = 3 } = {}) {
  const words = clean(text).split(/\s+/).filter(Boolean);
  if (!words.length) return '';

  const lines = [];
  let current = '';
  for (const word of words) {
    if (!current) current = word;
    else if (current.length + 1 + word.length <= width) current += ` ${word}`;
    else {
      lines.push(current);
      current = word;
      if (lines.length === maxLines) break;
    }
  }
  if (lines.length < maxLines && current) lines.push(current);

  // Only the overflow is elided, and only once the label has already had its full lines.
  const consumed = lines.join(' ').split(/\s+/).length;
  if (consumed < words.length) lines[lines.length - 1] += '…';
  return lines.join('<br/>');
}

function nodeId(prefix, value) {
  return `${prefix}_${String(value).replace(/[^A-Za-z0-9]/g, '_')}`;
}

/**
 * Diagram 1 — the correct flow above the broken one.
 * The fastest way to show a reader what went wrong is to show what should have happened
 * right next to it. Rendered only when the analysis supplies the expected flow.
 */
function expectedVsActualDiagram(expectedFlow, actualFlow) {
  if (!expectedFlow || !expectedFlow.length || !actualFlow || !actualFlow.length) return null;

  const lines = ['```mermaid', 'flowchart TB', ...CLASS_DEFS];

  lines.push('  subgraph EXPECTED["✅ What should happen"]');
  lines.push('    direction LR');
  expectedFlow.forEach((step, i) => {
    lines.push(`    E${i}["${label(step, { width: 26, maxLines: 3 })}"]:::ok`);
    if (i > 0) lines.push(`    E${i - 1} --> E${i}`);
  });
  lines.push('  end');

  lines.push('  subgraph ACTUAL["❌ What actually happens"]');
  lines.push('    direction LR');
  actualFlow.forEach((step, i) => {
    const isLast = i === actualFlow.length - 1;
    lines.push(`    A${i}["${label(step, { width: 26, maxLines: 3 })}"]:::${isLast ? 'broken' : 'neutral'}`);
    if (i > 0) lines.push(`    A${i - 1} --> A${i}`);
  });
  lines.push('  end');

  lines.push('```');
  return lines.join('\n');
}

/**
 * Diagram 2 — trigger to failure, one box per step.
 * The numbered list above it carries the detail, so the boxes stay short.
 */
function causalChainDiagram(steps) {
  const lines = ['```mermaid', 'flowchart TD', ...CLASS_DEFS];
  steps.forEach((s, i) => {
    const isLast = i === steps.length - 1;
    lines.push(`  S${i}["${i + 1}. ${label(s.label, { width: 34, maxLines: 3 })}"]:::${isLast ? 'broken' : 'neutral'}`);
    if (i > 0) lines.push(`  S${i - 1} --> S${i}`);
  });
  lines.push('```');
  return lines.join('\n');
}

/**
 * Diagram 3 — where the defect sits in the call graph.
 * Shows what leads into the defect site and, when the method calls itself, draws the
 * loop explicitly — that single edge explains the whole failure at a glance.
 */
function defectMapDiagram(focusList, { maxSites = 4, maxCallersPerSite = 3 } = {}) {
  const sites = focusList.filter((f) => f.resolved).slice(0, maxSites);
  if (!sites.length) return null;

  const lines = ['```mermaid', 'flowchart LR', ...CLASS_DEFS];
  const declared = new Set();

  sites.forEach((site, index) => {
    const siteNode = `D${index}`;
    lines.push(`  ${siteNode}["🔴 ${label(site.label, { width: 34, maxLines: 2 })}<br/>${label(site.module, { width: 30, maxLines: 1 })}"]:::broken`);

    if (site.endpoint) {
      const epNode = nodeId('EP', site.endpoint);
      if (!declared.has(epNode)) {
        lines.push(`  ${epNode}(["${label(site.endpoint, { width: 34, maxLines: 2 })}"]):::neutral`);
        declared.add(epNode);
      }
      lines.push(`  ${epNode} --> ${siteNode}`);
    }

    const callers = (site.directCallers || []).filter((c) => c.id !== site.id).slice(0, maxCallersPerSite);
    for (const caller of callers) {
      const callerNode = nodeId('C', caller.id);
      if (!declared.has(callerNode)) {
        lines.push(`  ${callerNode}["${label(caller.label, { width: 34, maxLines: 2 })}"]:::neutral`);
        declared.add(callerNode);
      }
      lines.push(`  ${callerNode} --> ${siteNode}`);
    }

    if (site.selfRecursive) {
      lines.push(`  ${siteNode} -->|"calls itself"| ${siteNode}`);
    }
  });

  lines.push('```');
  return lines.join('\n');
}

module.exports = {
  CLASS_DEFS,
  label,
  nodeId,
  expectedVsActualDiagram,
  causalChainDiagram,
  defectMapDiagram,
};
