#!/usr/bin/env node
/**
 * Context Weaver — Batch Description Generator (OPTIONAL)
 *
 * The architect agent normally authors descriptions itself, in-context, straight from
 * the brief — no API key, no network, same pattern as every other agent-authored
 * artifact in this repo. That path is the default and should stay the default.
 *
 * This script exists for the case that path cannot cover: a workload too large to fit
 * in one agent context. It sends the same brief to an LLM API in batches and writes
 * the same descriptions.json, so Graph Forge cannot tell which path produced it.
 *
 * Provider-agnostic by design — Anthropic, OpenAI and Google are equally supported.
 * Pick one via LLM_PROVIDER in .env (see .env.example); everything below this line
 * builds one prompt and one schema and hands both to whichever provider module
 * scripts/lib/providers/index.js resolves. Nothing here branches on provider name.
 *
 * Design notes:
 *   - The instruction block and schema are identical across batches and carry a cache
 *     breakpoint on providers that support one, so only per-batch node evidence is
 *     billed at full price.
 *   - Structured outputs pin the response to the schema, so there is no JSON repair
 *     loop, on every provider.
 *   - Batches run sequentially: concurrent requests sharing a cache prefix would each
 *     miss the cache the others are still writing.
 *   - Output still goes through validate-context.js. Nothing here is trusted.
 *
 * Setup:
 *   cd .github/skills/01b-context-weaver && npm install
 *   cp .env.example .env, set LLM_PROVIDER and that provider's API key
 *   npm install <the SDK package generate-descriptions.js reports missing>
 *
 * Usage:
 *   node scripts/generate-descriptions.js                    # whole workload
 *   node scripts/generate-descriptions.js --provider openai --model gpt-4o
 *   node scripts/generate-descriptions.js --batch-size 8 --effort medium
 *   node scripts/generate-descriptions.js --dry-run          # show batching + token shape only
 */
const fs = require('fs');
const {
  CONTEXT_DIR, WORKLOAD_JSON, DESCRIPTIONS_FILE, SCHEMA_FILE, rel, envInt,
} = require('./lib/context');
const { resolveProvider } = require('./lib/providers');

function parseArgs(argv) {
  const args = {
    provider: null,
    model: null,
    batchSize: envInt('CONTEXT_BATCH_SIZE', 10),
    effort: process.env.CONTEXT_EFFORT || 'high',
    dryRun: false,
    merge: true,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--provider') args.provider = argv[++i];
    else if (arg === '--batch-size') args.batchSize = parseInt(argv[++i], 10);
    else if (arg === '--effort') args.effort = argv[++i];
    else if (arg === '--model') args.model = argv[++i];
    else if (arg === '--dry-run') args.dryRun = true;
    else if (arg === '--no-merge') args.merge = false;
    else if (arg === '--help' || arg === '-h') args.help = true;
  }
  return args;
}

function usage() {
  console.log(`Context Weaver — Batch Description Generator (optional)

  node scripts/generate-descriptions.js [options]

Options:
  --provider NAME  anthropic | openai | google (default: LLM_PROVIDER in .env, else anthropic)
  --model ID       Override the provider's model (default: its <PROVIDER>_MODEL in .env)
  --batch-size N   Nodes per request (default: CONTEXT_BATCH_SIZE in .env, else 10)
  --effort LEVEL   low | medium | high | xhigh | max — Anthropic only, ignored elsewhere
                   (default: CONTEXT_EFFORT in .env, else high)
  --no-merge       Overwrite descriptions.json instead of merging with existing entries
  --dry-run        Print the batch plan and exit without calling any API
  --help, -h       Show this message

Provider, model, API key and the selection thresholds are configured in .env — copy
.env.example first. The architect agent authoring the brief directly is the primary
path; use this only when the workload is too large for one agent context.`);
}

// ---------------------------------------------------------------------------
// Prompt (provider-neutral — every provider receives the same two strings)
// ---------------------------------------------------------------------------

const INSTRUCTIONS = `You are documenting a Java/Spring-Cloud microservices codebase so that other automated
agents — a root cause analyst, a blast radius analyst, a QA test planner — can reason about it without
re-reading the source.

For each node you are given, write a contextual description. You are writing for a machine reader that
already has the AST: it knows the signature, the annotations, the call edges. It does not know what any
of it MEANS. Your job is only the meaning.

What each field is for, and who reads it:

- summary: what this is and what it is for, in the language of the domain. One to three sentences.
  Never restate the signature. "Owns the employee roster and is the only writer of the employees
  collection" is useful; "a service class with three methods" is not.
- failureModes: how this realistically breaks and what the caller observes when it does. The root cause
  analyst matches reported symptoms against these.
- invariants: what must hold true for this to be correct — ordering, nullability, uniqueness,
  idempotency, transaction boundaries. A violated invariant is usually the defect.
- sideEffects: writes, outbound calls, publishes, mutated state. The blast radius analyst uses these to
  decide what a change can reach. An empty array means genuinely pure, not merely unknown.
- testHints: the request to replay, the fixture needed, the boundary worth asserting, the collaborator
  worth stubbing. The QA agent builds test plans from these.
- criticality: how much damage a defect here does, not how complex the code is.
- dataTouched / upstream / downstream: name the collections, services and schedules involved, including
  cross-service HTTP callers that carry no Java call edge.

Rules:

1. Ground every claim in the evidence supplied for that node. Cite what you used in "evidence".
2. Never invent behaviour that the code does not show. If intent is genuinely unclear, set
   "confidence": "low" and put the unknown in "openQuestions". A hedged description is useful; an
   invented one poisons every agent downstream.
3. Copy "kind", "id" and "fingerprint" back verbatim. They are how the description binds to the graph.
4. Omit a node entirely rather than padding it with generic filler.
5. Omit any optional field you have nothing real to say for. Empty arrays are fine; invented content is not.

Respond with a JSON object matching the supplied schema — a top-level "nodes" array, one entry per node
you chose to describe.`;

/**
 * Structured-output schema, derived from descriptions.schema.json so the two cannot
 * drift. Constraints structured outputs does not support (minLength) are stripped —
 * validate-context.js still enforces them afterwards. Provider-specific dialect
 * differences (e.g. Gemini dropping additionalProperties) are handled inside each
 * provider module, not here — this is the one shared schema every provider starts from.
 */
function buildOutputSchema(schema) {
  const nodeSchema = JSON.parse(JSON.stringify(schema.properties.nodes.items));
  const strip = (obj) => {
    if (!obj || typeof obj !== 'object') return;
    delete obj.minLength;
    Object.values(obj).forEach(strip);
  };
  strip(nodeSchema);
  return {
    type: 'object',
    properties: { nodes: { type: 'array', items: nodeSchema } },
    required: ['nodes'],
    additionalProperties: false,
  };
}

function renderNodeForPrompt(node) {
  const facts = Object.entries(node.facts || {})
    .filter(([, v]) => v !== null && v !== undefined && !(Array.isArray(v) && !v.length))
    .map(([k, v]) => (k === 'source' ? `  source:\n\`\`\`java\n${v}\n\`\`\`` : `  ${k}: ${Array.isArray(v) ? v.join(', ') : v}`))
    .join('\n');
  return [
    `--- NODE ---`,
    `kind: ${node.kind}`,
    `id: ${node.id}`,
    `fingerprint: ${node.fingerprint}`,
    `selected because: ${node.reasons.join('; ')}`,
    facts,
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) return usage();

  if (!fs.existsSync(WORKLOAD_JSON)) {
    throw new Error(`No workload at ${rel(WORKLOAD_JSON)}. Run list-context-workload.js first.`);
  }
  const workload = JSON.parse(fs.readFileSync(WORKLOAD_JSON, 'utf8'));
  const schema = JSON.parse(fs.readFileSync(SCHEMA_FILE, 'utf8'));
  const nodes = workload.nodes || [];

  if (!nodes.length) {
    console.log('Workload is empty — every selected node already has a current description. Nothing to generate.');
    return;
  }

  const batches = [];
  for (let i = 0; i < nodes.length; i += args.batchSize) batches.push(nodes.slice(i, i + args.batchSize));

  const provider = resolveProvider(args.provider);
  const model = args.model || process.env[provider.envModelName] || provider.defaultModel;
  const apiKey = process.env[provider.envKeyName];

  console.log(`Context Weaver — batch generation`);
  console.log(`  provider : ${provider.name}`);
  console.log(`  model    : ${model}${provider.name === 'anthropic' ? ` (effort ${args.effort})` : ''}`);
  console.log(`  nodes    : ${nodes.length}`);
  console.log(`  batches  : ${batches.length} of up to ${args.batchSize}`);

  if (args.dryRun) {
    batches.forEach((batch, i) => {
      console.log(`  batch ${i + 1}: ${batch.map((n) => `${n.kind}:${n.label}`).join(', ')}`);
    });
    console.log('\n--dry-run: no API calls made.');
    return;
  }

  if (!provider.isInstalled()) {
    throw new Error(
      `Provider "${provider.name}" needs its SDK, which is not installed in this skill folder.\n` +
      `  cd .github/skills/01b-context-weaver && npm install ${provider.packageName}\n` +
      'Or skip this script entirely — the architect agent can author the same descriptions from ' +
      `${rel(WORKLOAD_JSON).replace('.json', '.md')} with no API key.`
    );
  }
  if (!apiKey) {
    throw new Error(
      `${provider.envKeyName} is not set. Copy .env.example to .env in this folder and fill it in, ` +
      `or set LLM_PROVIDER to a provider whose key you do have.`
    );
  }

  const outputSchema = buildOutputSchema(schema);
  const collected = [];

  for (const [index, batch] of batches.entries()) {
    process.stdout.write(`  batch ${index + 1}/${batches.length} (${batch.length} nodes) ... `);

    let result;
    try {
      result = await provider.generate({
        apiKey,
        model,
        systemPrompt: INSTRUCTIONS,
        userPrompt: `Write descriptions for the following ${batch.length} node(s).\n\n${batch.map(renderNodeForPrompt).join('\n\n')}`,
        schema: outputSchema,
        effort: args.effort,
      });
    } catch (err) {
      console.log('REQUEST FAILED');
      console.warn(`    skipped — ${err.message}`);
      continue;
    }

    if (result.refused) {
      console.log('REFUSED');
      console.warn(`    skipped — the request was declined (${result.refusalCategory || 'no category given'})`);
      continue;
    }
    if (!result.text) {
      console.log('EMPTY');
      continue;
    }

    let parsed;
    try {
      parsed = JSON.parse(result.text);
    } catch (err) {
      console.log('UNPARSEABLE');
      console.warn(`    skipped — response was not valid JSON: ${err.message}`);
      continue;
    }

    collected.push(...(parsed.nodes || []));
    const u = result.usage || {};
    console.log(`${(parsed.nodes || []).length} described (cache read ${u.cacheReadTokens || 0}, out ${u.outputTokens || 0})`);
  }

  // Nothing new to add. Do NOT touch the file: every batch failing (bad key, network,
  // rate limit, all refused) is not the same as "the workload is empty", and writing
  // in this state would relabel whatever is already on disk — possibly hand-authored
  // work — as `author: llm:<this provider>` even though this run contributed zero
  // nodes to it. Leaving the file alone is the only safe behavior here.
  if (!collected.length) {
    console.log(`\nNo new descriptions were produced (every batch failed, refused, or returned empty) — ${rel(DESCRIPTIONS_FILE)} left unchanged.`);
    return;
  }

  // Merge with anything already described, newest wins per node.
  let existing = { nodes: [], crossCutting: [] };
  if (args.merge && fs.existsSync(DESCRIPTIONS_FILE)) {
    try {
      existing = JSON.parse(fs.readFileSync(DESCRIPTIONS_FILE, 'utf8'));
    } catch (err) {
      console.warn(`Warning: could not merge with ${rel(DESCRIPTIONS_FILE)} (${err.message}) — writing fresh.`);
    }
  }
  const merged = new Map((existing.nodes || []).map((n) => [`${n.kind}:${n.id}`, n]));
  for (const node of collected) merged.set(`${node.kind}:${node.id}`, node);

  // The file-level `author` names who wrote the nodes it holds — accurate for a
  // fresh run, but a merge can combine agent-authored and LLM-authored nodes in one
  // file. Once that happens, note the mix rather than silently attributing
  // everything to whichever provider ran most recently.
  const priorAuthor = existing.author;
  const author = (args.merge && priorAuthor && priorAuthor !== `llm:${provider.name}:${model}`)
    ? `mixed (${priorAuthor} + llm:${provider.name}:${model})`
    : `llm:${provider.name}:${model}`;

  const output = {
    generatedAt: new Date().toISOString(),
    artifactsGeneratedAt: workload.artifactsGeneratedAt,
    author,
    nodes: [...merged.values()],
    crossCutting: existing.crossCutting || [],
  };

  fs.mkdirSync(CONTEXT_DIR, { recursive: true });
  fs.writeFileSync(DESCRIPTIONS_FILE, JSON.stringify(output, null, 2));

  console.log(`\nWrote ${output.nodes.length} description(s) to ${rel(DESCRIPTIONS_FILE)} (${collected.length} new/updated this run).`);
  console.log('Cross-cutting notes are not generated here — add them by hand or have the architect agent write them.');
  console.log('Next: node scripts/validate-context.js');
}

main().catch((err) => {
  console.error('Context Weaver generation failed:', err.message);
  process.exit(1);
});
