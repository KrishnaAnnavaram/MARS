/**
 * 04a1 Remediation Intelligence — shared logic for the catalog-gap fallback.
 *
 * Activates only when a diagnosed CWE has NO entry in the main 04a catalog. It reuses 04a's own
 * path resolution, catalog loader and CWE detector (so the two stages agree on what a "gap" is),
 * searches this skill's local knowledge base, ranks the historical fixes by relevance to the root
 * cause, and derives a strategy object in the SAME shape 04a's render-fix-plan.js consumes — plus a
 * `derived_pattern` provenance block recording where the pattern came from.
 *
 * Nothing here reaches the network at rank time: every pattern cited resolves to a real file under
 * knowledge/, and the optional embedding signal (see scripts/lib/embeddings.js) runs a LOCAL
 * sentence-transformers model in this skill's own venv, not a hosted API. A CWE that is a gap in
 * BOTH the catalog and this KB is reported, never invented.
 */
const fs = require('fs');
const path = require('path');

// Reuse 04a's library so gap detection is identical on both sides of the branch.
const plans = require('../../../04a-fix-strategist/scripts/lib/plans');
const { embedTexts, dotProduct, pythonAvailable } = require('./embeddings');

const KB_FILE = path.join(__dirname, '..', '..', 'knowledge', 'remediation-kb.json');
const WEIGHTS_FILE = path.join(__dirname, '..', '..', 'ranking-weights.json');

const DEFAULT_WEIGHTS = {
  keyword_weight: 0.3,
  tfidf_weight: 0.3,
  embedding_weight: 0.4,
  exact_match_weight: 1.0,
  synonym_match_weight: 0.5,
};

function loadKb() {
  const raw = JSON.parse(fs.readFileSync(KB_FILE, 'utf8'));
  const { $comment, ...entries } = raw;
  return entries;
}

/**
 * Externalised ranking weights (same convention as 07a-merge-arbiter/scoring.json — never buried in
 * code). Falls back to sane defaults if the file is missing or a field is absent, so a partial
 * override still works.
 */
function loadRankingWeights() {
  try {
    const raw = JSON.parse(fs.readFileSync(WEIGHTS_FILE, 'utf8'));
    const { $comment, ...rest } = raw;
    return { ...DEFAULT_WEIGHTS, ...rest };
  } catch {
    return { ...DEFAULT_WEIGHTS };
  }
}

/**
 * Given the context bundle's `cwe` array (each { cwe, inCatalog }), split it into what the catalog
 * already covers and what is a gap, and choose the primary gap to remediate.
 *
 * Trigger contract (v1): the fallback is warranted only when NONE of the detected CWEs is in the
 * catalog. If any detected CWE is catalogued, that is the 04a happy path — this returns
 * warranted:false so the caller declines.
 */
function classifyGap(cweCandidates, preferredCwe) {
  const catalogued = cweCandidates.filter((c) => c.inCatalog).map((c) => c.cwe);
  const gaps = cweCandidates.filter((c) => !c.inCatalog).map((c) => c.cwe);
  const warranted = cweCandidates.length > 0 && catalogued.length === 0;

  let primaryGap = null;
  if (preferredCwe && gaps.includes(preferredCwe.toUpperCase())) primaryGap = preferredCwe.toUpperCase();
  else if (gaps.length) primaryGap = gaps[0];

  return { catalogued, gaps, warranted, primaryGap };
}

/** Lowercase alnum tokens, RAW (not deduped) — used for TF-IDF term frequency. */
function tokenizeRaw(text) {
  return (text || '').toLowerCase().match(/[a-z0-9]+/g) || [];
}

/** Lowercase alnum tokens, deduped — used for keyword/synonym overlap scoring. */
function tokenize(text) {
  return [...new Set(tokenizeRaw(text))];
}

function round4(n) {
  return Math.round(n * 10000) / 10000;
}

/** One "document" per historical fix, drawn from every CWE entry in the KB — the TF-IDF corpus. */
function buildCorpusDocs(kb) {
  const docs = [];
  for (const entry of Object.values(kb || {})) {
    for (const hf of entry.historical_fixes || []) {
      const text = [hf.pattern, hf.summary, (hf.keywords || []).join(' ')].filter(Boolean).join(' ');
      docs.push({ id: hf.id, tokens: tokenizeRaw(text) });
    }
  }
  return docs;
}

/** Document frequency: how many corpus documents each term appears in at least once. */
function computeDocumentFrequency(docs) {
  const df = new Map();
  for (const doc of docs) {
    for (const term of new Set(doc.tokens)) df.set(term, (df.get(term) || 0) + 1);
  }
  return df;
}

/** Smoothed IDF: ln(N / (1 + df)) + 1 — always positive, and a term unseen in the corpus (df=0)
 *  gets the maximum weight rather than blowing up or being ignored. */
function idfFor(term, df, corpusSize) {
  const d = df.get(term) || 0;
  return Math.log(corpusSize / (1 + d)) + 1;
}

/** tf * idf per term, as a sparse Map — only terms actually present carry a weight. */
function tfidfVector(tokens, df, corpusSize) {
  const tf = new Map();
  for (const t of tokens) tf.set(t, (tf.get(t) || 0) + 1);
  const vec = new Map();
  for (const [term, count] of tf) vec.set(term, count * idfFor(term, df, corpusSize));
  return vec;
}

/** Cosine similarity between two sparse tf-idf vectors. 0 if either is the zero vector. */
function cosineSimilarity(a, b) {
  let dot = 0;
  for (const [term, weightA] of a) {
    const weightB = b.get(term);
    if (weightB) dot += weightA * weightB;
  }
  const normA = Math.sqrt([...a.values()].reduce((s, v) => s + v * v, 0));
  const normB = Math.sqrt([...b.values()].reduce((s, v) => s + v * v, 0));
  if (!normA || !normB) return 0;
  return dot / (normA * normB);
}

/**
 * Keyword + synonym overlap for one historical fix, normalized to 0-1.
 * An exact keyword hit counts `exact_match_weight`; a synonym hit (via the KB entry's optional
 * `keyword_synonyms` map) counts `synonym_match_weight`. Normalized against the maximum a fix with
 * this many keywords could possibly score, so fixes with different-length keyword lists stay
 * comparable.
 */
function scoreKeywords(hf, haystack, synonymMap, weights) {
  const keywords = hf.keywords || [];
  const matched_keywords = [];
  const matched_synonyms = [];
  let raw = 0;
  for (const kw of keywords) {
    const k = kw.toLowerCase();
    if (haystack.has(k)) {
      matched_keywords.push(kw);
      raw += weights.exact_match_weight;
      continue;
    }
    const synonyms = (synonymMap && synonymMap[k]) || [];
    const hitSynonym = synonyms.find((s) => haystack.has(String(s).toLowerCase()));
    if (hitSynonym) {
      matched_synonyms.push(`${kw} -> ${hitSynonym}`);
      raw += weights.synonym_match_weight;
    }
  }
  const max = keywords.length * weights.exact_match_weight;
  return { keyword_score: max > 0 ? raw / max : 0, matched_keywords, matched_synonyms };
}

/**
 * Rank a KB entry's historical fixes against the root-cause / issue text using up to THREE signals:
 *   1. keyword_score   — exact + synonym overlap (lexical, literal words / declared paraphrases)
 *   2. tfidf_score     — TF-IDF cosine similarity, corpus = every historical fix across the whole KB
 *                         (lexical, but weights rare terms over common ones)
 *   3. embedding_score — TRUE semantic similarity: cosine similarity between real sentence
 *                         embeddings from a local sentence-transformers model (scripts/lib/
 *                         embeddings.js), the only signal that can recognise two DIFFERENTLY-WORDED
 *                         sentences mean the same thing without either sharing a literal word or
 *                         being hand-declared a synonym.
 *
 * score = weighted average of whichever signals are available, weights from ranking-weights.json.
 * embedding_score is OPTIONAL: if the local model isn't set up on this machine (see SKILL.md's
 * Setup section), embedTexts() returns null and this function transparently falls back to
 * keyword_score + tfidf_score alone (renormalized so `score` stays 0-1 either way) — it never
 * throws, and `embedding_score: null` in the result makes the omission visible, not silent.
 *
 * Deterministic given the model: same input, same weights, same ranking every time. (The embedding
 * step depends on a fixed local model file, not a live service that could change out from under a
 * re-run.) Ties break on keyword_score, then alphabetically by id.
 *
 * `kb` (the full loaded knowledge base, all CWE entries) supplies the TF-IDF corpus; pass just
 * `{ [gapCwe]: kbEntry }` if only the single entry is available.
 */
function rankExamples(kbEntry, rootCauseText, kb) {
  const weights = loadRankingWeights();
  const haystack = new Set(tokenize(rootCauseText));
  const synonymMap = kbEntry.keyword_synonyms || {};

  const corpusDocs = buildCorpusDocs(kb || { __entry: kbEntry });
  const df = computeDocumentFrequency(corpusDocs);
  const corpusSize = corpusDocs.length || 1;
  const queryVector = tfidfVector(tokenizeRaw(rootCauseText), df, corpusSize);

  const fixes = kbEntry.historical_fixes || [];
  const docTexts = fixes.map((hf) => [hf.pattern, hf.summary, (hf.keywords || []).join(' ')].filter(Boolean).join(' '));

  // One batched call for the whole ranking pass: [rootCauseText, ...every fix's doc text]. Model
  // LOAD time dominates latency, not encoding, so batching avoids paying it once per fix.
  const embeddings = fixes.length ? embedTexts([rootCauseText, ...docTexts]) : null;
  const queryEmbedding = embeddings ? embeddings[0] : null;

  return fixes
    .map((hf, i) => {
      const { keyword_score, matched_keywords, matched_synonyms } = scoreKeywords(hf, haystack, synonymMap, weights);
      const docVector = tfidfVector(tokenizeRaw(docTexts[i]), df, corpusSize);
      const tfidf_score = cosineSimilarity(queryVector, docVector);

      let embedding_score = null;
      if (queryEmbedding) {
        // Vectors are already unit-normalized (embed.py: normalize_embeddings=True), so their dot
        // product IS the cosine similarity. Clamp tiny negative floating-point noise to 0.
        embedding_score = Math.max(0, dotProduct(queryEmbedding, embeddings[i + 1]));
      }

      const parts = [
        [weights.keyword_weight, keyword_score],
        [weights.tfidf_weight, tfidf_score],
      ];
      if (embedding_score !== null) parts.push([weights.embedding_weight, embedding_score]);
      const weightSum = parts.reduce((s, [w]) => s + w, 0) || 1;
      const score = round4(parts.reduce((s, [w, v]) => s + w * v, 0) / weightSum);

      return {
        id: hf.id,
        pattern: hf.pattern,
        summary: hf.summary,
        source: hf.source,
        score,
        keyword_score: round4(keyword_score),
        tfidf_score: round4(tfidf_score),
        embedding_score: embedding_score === null ? null : round4(embedding_score),
        matched_keywords,
        matched_synonyms,
      };
    })
    .sort((a, b) => b.score - a.score || b.keyword_score - a.keyword_score || a.id.localeCompare(b.id));
}

/** Build the human-visible provenance sentence prepended to `approach`. */
function provenanceSentence(gapCwe, ranked, sources) {
  const top = ranked[0];
  const breakdown = top
    ? [
      `keyword/synonym ${top.keyword_score.toFixed(2)}`,
      `TF-IDF cosine ${top.tfidf_score.toFixed(2)}`,
      top.embedding_score !== null ? `local semantic embedding ${top.embedding_score.toFixed(2)}` : 'semantic embedding unavailable on this machine',
    ].join(' + ')
    : null;
  const via = top
    ? `historical fix ${top.id} ("${top.pattern}", hybrid score ${top.score.toFixed(2)} = ${breakdown})`
    : 'the curated knowledge base';
  return `> Derived by the 04a1 remediation-intelligence fallback: ${gapCwe} has no entry in the main CWE catalog, so this strategy was built from ${via}, ranked against every candidate historical fix for this CWE. Sources: ${sources.join(', ')}. Treat as a low-confidence, human-review-first proposal.`;
}

/**
 * Derive a render-compatible strategy object for a catalog-gap CWE.
 * Satisfies render-fix-plan.js's required fields and carries a derived_pattern provenance block.
 *
 * `kb` (optional): the full loaded knowledge base, used to build the TF-IDF corpus across every
 * CWE's historical fixes. Falls back to just `kbEntry` alone if not supplied.
 */
function deriveStrategy(context, gapCwe, kbEntry, kb) {
  const rootCauseText = [
    context.title,
    context.rootCause && context.rootCause.statement,
    context.rootCause && context.rootCause.recommendedFix,
    context.issue && context.issue.body,
  ].filter(Boolean).join('\n');

  const ranked = rankExamples(kbEntry, rootCauseText, kb || { [gapCwe]: kbEntry });
  const top = ranked[0];
  const sources = [
    'knowledge/remediation-kb.json',
    ...ranked.filter((r) => r.source).map((r) => r.source),
  ].filter((v, i, a) => a.indexOf(v) === i);

  const foundFiles = (context.files || []).filter((f) => f.found);
  const affected_files = (foundFiles.length ? foundFiles.map((f) => f.file)
    : [context.rootCause && context.rootCause.defectLocation].filter(Boolean)
  ).map((file) => ({
    file,
    planned_change: `Apply the "${top ? top.pattern : kbEntry.title}" pattern at this defect site: ${kbEntry.canonical_approach} The Fixer adapts it to the existing style of this file.`,
  }));

  const approach = [
    provenanceSentence(gapCwe, ranked, sources),
    '',
    `${kbEntry.canonical_approach} Applied to this defect (${(context.rootCause && context.rootCause.statement) || context.title}), it addresses the root cause rather than the reported symptom, because it removes the mechanism the weakness depends on instead of filtering one payload shape.`,
  ].join('\n');

  return {
    issue_id: context.id,
    cwe: gapCwe,
    confidence: 'Low',
    plain_summary: `${kbEntry.title.split('(')[0].trim()}: ${kbEntry.canonical_approach.split('.')[0]}.`,
    approach,
    catalog_reference: { cwe: gapCwe, title: null },
    alternatives_considered: (kbEntry.anti_patterns || []).map((ap) => `Rejected — ${ap}`),
    affected_files: affected_files.length ? affected_files : [{
      file: '(affected file not resolved from context — Fixer/human must confirm)',
      planned_change: kbEntry.canonical_approach,
    }],
    risk_notes: [
      'Derived via the 04a1 fallback from knowledge outside the vetted CWE catalog — a reviewer must confirm the source pattern genuinely fits this defect before approval.',
      ...(kbEntry.anti_patterns || []).map((ap) => `Do not regress into an anti-pattern: ${ap}`),
    ],
    verification_plan: kbEntry.verification_hints || [
      'Confirm the original reported symptom no longer reproduces.',
      'Confirm benign behaviour is unchanged.',
    ],
    open_questions: [
      `${gapCwe} has no entry in the main CWE catalog (catalog gap). This plan was derived by the 04a1 fallback and should either be approved with extra scrutiny or promoted into cwe-patterns.json as a vetted entry.`,
      'Confirm the intended safe boundary (base directory / allow-list / authorized projection) with the service owner before implementation.',
    ],
    derived_pattern: {
      source_type: 'expanded-catalog',
      sources,
      kb_entry: gapCwe,
      ranked_examples: ranked,
      note: `No catalog entry for ${gapCwe}; strategy derived from local knowledge base and ranked historical fixes. Human review required.`,
    },
  };
}

module.exports = {
  plans,
  KB_FILE,
  WEIGHTS_FILE,
  loadKb,
  loadRankingWeights,
  classifyGap,
  tokenize,
  tokenizeRaw,
  buildCorpusDocs,
  computeDocumentFrequency,
  tfidfVector,
  cosineSimilarity,
  scoreKeywords,
  rankExamples,
  deriveStrategy,
  pythonAvailable,
};
