/**
 * Version Migration — reference pack discovery.
 *
 * A reference pack is a Markdown file in `references/` carrying front matter that says
 * which stack and version jump it covers and how to recognise a project that needs it.
 * This module only reads that front matter and matches it against a project's declared
 * coordinates — it never encodes any framework's rules itself. Adding a new pack is
 * therefore a documentation change, not a code change.
 */
const fs = require('fs');
const path = require('path');
const { REFERENCES_DIR } = require('./migration');

/** Minimal front-matter reader: `key: value` and `key:` followed by `- item` lines. */
function parseFrontMatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---/.exec(text);
  if (!m) return {};
  const data = {};
  let listKey = null;
  for (const raw of m[1].split(/\r?\n/)) {
    const line = raw.replace(/\s+$/, '');
    if (!line.trim() || line.trim().startsWith('#')) continue;
    const item = /^\s*-\s+(.*)$/.exec(line);
    if (item && listKey) {
      data[listKey].push(item[1].trim().replace(/^['"]|['"]$/g, ''));
      continue;
    }
    const pair = /^([A-Za-z0-9_.\-]+):\s*(.*)$/.exec(line);
    if (!pair) continue;
    const [, key, value] = pair;
    if (!value.trim()) {
      listKey = key;
      data[key] = [];
    } else {
      listKey = null;
      data[key] = value.trim().replace(/^['"]|['"]$/g, '');
    }
  }
  return data;
}

function listReferencePacks() {
  if (!fs.existsSync(REFERENCES_DIR)) return [];
  return fs
    .readdirSync(REFERENCES_DIR)
    .filter((f) => f.endsWith('.md') && f.toLowerCase() !== 'readme.md')
    .map((file) => {
      const full = path.join(REFERENCES_DIR, file);
      const meta = parseFrontMatter(fs.readFileSync(full, 'utf8'));
      return {
        file,
        path: full,
        id: meta.id || file.replace(/\.md$/, ''),
        title: meta.title || file,
        stack: meta.stack || 'unknown',
        from: meta.from || null,
        to: meta.to || null,
        language_from: meta.language_from || null,
        language_to: meta.language_to || null,
        detect: Array.isArray(meta.detect) ? meta.detect : [],
      };
    })
    .sort((a, b) => a.id.localeCompare(b.id));
}

/**
 * A detect entry is `groupId:artifactId` or `groupId:artifactId:versionPrefix`.
 * A pack matches when any entry matches the project's parent, a dependency or a plugin.
 */
function matchesCoordinate(entry, inventory) {
  const [groupId, artifactId, versionPrefix] = entry.split(':');
  const candidates = [
    ...(inventory.parent ? [inventory.parent] : []),
    ...(inventory.dependencies || []),
    ...(inventory.plugins || []),
  ];
  return candidates.some((c) => {
    if (groupId && c.groupId && c.groupId !== groupId) return false;
    if (groupId && !c.groupId) return false;
    if (artifactId && c.artifactId !== artifactId) return false;
    if (!versionPrefix) return true;
    return typeof c.version === 'string' && c.version.startsWith(versionPrefix);
  });
}

function matchReferencePacks(inventory) {
  return listReferencePacks()
    .map((pack) => ({
      ...pack,
      matched: pack.detect.filter((entry) => matchesCoordinate(entry, inventory)),
    }))
    .filter((pack) => pack.matched.length > 0);
}

function resolveReferencePack(idOrFile) {
  const packs = listReferencePacks();
  return packs.find((p) => p.id === idOrFile || p.file === idOrFile || p.file === `${idOrFile}.md`) || null;
}

module.exports = { parseFrontMatter, listReferencePacks, matchReferencePacks, resolveReferencePack };
