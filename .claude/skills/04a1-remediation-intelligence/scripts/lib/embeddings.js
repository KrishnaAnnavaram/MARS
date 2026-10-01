/**
 * 04a1 -- local sentence-embedding bridge.
 *
 * Real semantic embeddings (not TF-IDF pseudo-vectors) via a local Python `sentence-transformers`
 * model, run inside this skill's own virtual environment (`.venv/`, gitignored, set up once per
 * machine -- see SKILL.md's Setup section). No network call at rank time: the model is fetched to
 * the local Hugging Face cache the first time it's used, then reused offline from there.
 *
 * Deliberately synchronous (execFileSync), matching every other script in this skill -- so callers
 * don't need to become async just for this one signal.
 *
 * Fails soft, on purpose: if the venv or sentence-transformers isn't set up on this machine,
 * embedTexts() returns `null` rather than throwing, and the caller (rankExamples) drops the
 * embedding_score component and ranks on keyword_score + tfidf_score alone -- exactly the same
 * "gracefully omit what isn't available" behaviour this harness already uses for an unconfigured
 * Neo4j instance elsewhere in the pipeline.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const SKILL_DIR = path.join(__dirname, '..', '..');
const VENV_PYTHON = path.join(
  SKILL_DIR,
  '.venv',
  process.platform === 'win32' ? path.join('Scripts', 'python.exe') : path.join('bin', 'python'),
);
const EMBED_SCRIPT = path.join(SKILL_DIR, 'scripts', 'embed.py');

function pythonAvailable() {
  return fs.existsSync(VENV_PYTHON) && fs.existsSync(EMBED_SCRIPT);
}

/**
 * Embed a batch of strings in ONE python process invocation (model load dominates latency, not
 * encoding -- batching everything a single rankExamples() call needs avoids paying that cost once
 * per string). Returns an array of unit-length vectors (plain number[][]), same order as `texts`,
 * or `null` if the local model isn't set up / the call fails for any reason -- never throws.
 */
function embedTexts(texts) {
  if (!pythonAvailable()) return null;
  try {
    const out = execFileSync(VENV_PYTHON, [EMBED_SCRIPT], {
      input: JSON.stringify(texts),
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      timeout: 120000,
      stdio: ['pipe', 'pipe', 'ignore'], // stdout captured for parsing; stderr (HF warnings, tqdm bars) silenced
    });
    const parsed = JSON.parse(out);
    if (!Array.isArray(parsed.vectors) || parsed.vectors.length !== texts.length) return null;
    return parsed.vectors;
  } catch {
    return null;
  }
}

/** Dot product of two equal-length, already-unit-normalized vectors == their cosine similarity. */
function dotProduct(a, b) {
  let sum = 0;
  for (let i = 0; i < a.length; i += 1) sum += a[i] * b[i];
  return sum;
}

module.exports = { pythonAvailable, embedTexts, dotProduct, VENV_PYTHON, EMBED_SCRIPT };
