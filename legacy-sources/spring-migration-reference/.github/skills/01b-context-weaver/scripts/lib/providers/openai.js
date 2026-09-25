/**
 * Context Weaver — OpenAI provider.
 *
 * Uses Chat Completions' structured outputs (response_format: json_schema, strict)
 * so the response is guaranteed to match the schema — same reasoning as the
 * Anthropic provider's output_config.format, different wire shape.
 *
 * Confidence note: this is the stable, long-documented Chat Completions surface, not
 * a newer API this repo carries deep reference material for (unlike Anthropic, which
 * has an extensive bundled skill). Verify against platform.openai.com/docs if a model
 * or field name here has moved on.
 *
 * "Effort" is deliberately not sent here — it is Claude-specific vocabulary
 * (thinking depth). Some OpenAI reasoning models accept a similarly-named
 * `reasoning_effort` parameter, but it errors on non-reasoning models, so guessing
 * whether to send it per model name is more likely to break a request than help one.
 * Leave it configured on the Anthropic side only.
 */
const name = 'openai';
const envKeyName = 'OPENAI_API_KEY';
const envModelName = 'OPENAI_MODEL';
const defaultModel = 'gpt-4o';
const packageName = 'openai';

function isInstalled() {
  try {
    require.resolve(packageName);
    return true;
  } catch (err) {
    return false;
  }
}

async function generate({ apiKey, model, systemPrompt, userPrompt, schema }) {
  const OpenAI = require(packageName);
  const client = new OpenAI({ apiKey });

  const completion = await client.chat.completions.create({
    model,
    messages: [
      { role: 'system', content: systemPrompt },
      { role: 'user', content: userPrompt },
    ],
    response_format: {
      type: 'json_schema',
      json_schema: { name: 'context_weaver_descriptions', strict: true, schema },
    },
  });

  const choice = completion.choices?.[0];
  if (choice?.finish_reason === 'content_filter') {
    return { text: null, refused: true, refusalCategory: 'content_filter', usage: {} };
  }

  return {
    text: choice?.message?.content || null,
    refused: false,
    refusalCategory: null,
    usage: {
      inputTokens: completion.usage?.prompt_tokens || 0,
      outputTokens: completion.usage?.completion_tokens || 0,
      cacheReadTokens: completion.usage?.prompt_tokens_details?.cached_tokens || 0,
      cacheWriteTokens: 0,
    },
  };
}

module.exports = { name, envKeyName, envModelName, defaultModel, packageName, isInstalled, generate };
