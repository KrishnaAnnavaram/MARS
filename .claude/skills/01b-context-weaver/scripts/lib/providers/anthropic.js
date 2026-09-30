/**
 * Context Weaver — Anthropic provider.
 *
 * Uses structured outputs (output_config.format) so the response is pinned to the
 * schema server-side — no JSON-repair loop needed. Streaming because batches can run
 * long at high effort and a non-streaming request at this max_tokens risks an HTTP
 * timeout. The system prompt carries a cache breakpoint: identical across every batch
 * in a run, so only the per-batch node evidence is billed at full price.
 *
 * Reference: SKILL.md "claude-api" bundled in this workspace — python/claude-api and
 * typescript/claude-api sections cover the same shapes used here.
 */
const name = 'anthropic';
const envKeyName = 'ANTHROPIC_API_KEY';
const envModelName = 'ANTHROPIC_MODEL';
const defaultModel = 'claude-opus-5';
const packageName = '@anthropic-ai/sdk';

function isInstalled() {
  try {
    require.resolve(packageName);
    return true;
  } catch (err) {
    return false;
  }
}

/**
 * @param {object} opts
 * @param {string} opts.apiKey
 * @param {string} opts.model
 * @param {string} opts.systemPrompt   Stable across every call in the run — cached.
 * @param {string} opts.userPrompt     Varies per batch — not cached.
 * @param {object} opts.schema         JSON Schema the response must satisfy.
 * @param {string} [opts.effort]       low | medium | high | xhigh | max
 * @returns {Promise<{text: string|null, refused: boolean, refusalCategory: string|null, usage: object}>}
 */
async function generate({ apiKey, model, systemPrompt, userPrompt, schema, effort }) {
  const Anthropic = require(packageName);
  const client = new Anthropic({ apiKey });

  const stream = client.messages.stream({
    model,
    max_tokens: 32000,
    thinking: { type: 'adaptive' },
    output_config: {
      effort: effort || 'high',
      format: { type: 'json_schema', schema },
    },
    system: [
      { type: 'text', text: systemPrompt, cache_control: { type: 'ephemeral' } },
    ],
    messages: [{ role: 'user', content: userPrompt }],
  });

  const message = await stream.finalMessage();

  if (message.stop_reason === 'refusal') {
    return {
      text: null,
      refused: true,
      refusalCategory: message.stop_details?.category || null,
      usage: message.usage || {},
    };
  }

  const textBlock = message.content.find((b) => b.type === 'text');
  return {
    text: textBlock ? textBlock.text : null,
    refused: false,
    refusalCategory: null,
    usage: {
      inputTokens: message.usage?.input_tokens || 0,
      outputTokens: message.usage?.output_tokens || 0,
      cacheReadTokens: message.usage?.cache_read_input_tokens || 0,
      cacheWriteTokens: message.usage?.cache_creation_input_tokens || 0,
    },
  };
}

module.exports = { name, envKeyName, envModelName, defaultModel, packageName, isInstalled, generate };
