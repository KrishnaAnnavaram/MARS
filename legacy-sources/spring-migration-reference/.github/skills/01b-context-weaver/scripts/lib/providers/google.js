/**
 * Context Weaver — Google (Gemini) provider.
 *
 * Uses generationConfig.responseSchema / responseMimeType for structured output.
 * Gemini's schema dialect is a subset of OpenAPI 3.0, not full JSON Schema — notably
 * `additionalProperties` is not part of that subset, so it is stripped before the
 * schema is sent (Anthropic and OpenAI both accept it; Gemini does not need it
 * because it does not permit extra properties by default).
 *
 * Confidence note: unlike Anthropic, this repo carries no deep bundled reference for
 * the Gemini SDK, and Google has shifted SDK packages before (`@google/generative-ai`
 * -> `@google/genai`). This uses the widely-documented `@google/generative-ai`
 * package. If it has been superseded in your environment, install whichever package
 * `GOOGLE_MODEL` needs and adjust the two `require`/`generateContent` lines below —
 * the rest of this file (schema sanitizing, usage mapping) does not need to change.
 */
const name = 'google';
const envKeyName = 'GOOGLE_API_KEY';
const envModelName = 'GOOGLE_MODEL';
const defaultModel = 'gemini-3-flash';
const packageName = '@google/generative-ai';

function isInstalled() {
  try {
    require.resolve(packageName);
    return true;
  } catch (err) {
    return false;
  }
}

/** Drop schema keywords outside Gemini's OpenAPI-3.0 subset. Recurses into nested schemas. */
function sanitizeForGemini(schema) {
  if (Array.isArray(schema)) return schema.map(sanitizeForGemini);
  if (!schema || typeof schema !== 'object') return schema;
  const { additionalProperties, ...rest } = schema;
  const out = {};
  for (const [key, value] of Object.entries(rest)) out[key] = sanitizeForGemini(value);
  return out;
}

async function generate({ apiKey, model, systemPrompt, userPrompt, schema }) {
  const { GoogleGenerativeAI } = require(packageName);
  const genAI = new GoogleGenerativeAI(apiKey);
  const client = genAI.getGenerativeModel({
    model,
    systemInstruction: systemPrompt,
    generationConfig: {
      responseMimeType: 'application/json',
      responseSchema: sanitizeForGemini(schema),
    },
  });

  const result = await client.generateContent(userPrompt);
  const response = result.response;

  // Gemini reports a block via promptFeedback / candidate finishReason rather than a
  // dedicated stop_reason like Anthropic's refusal — no text is produced either way.
  const blockReason = response.promptFeedback?.blockReason;
  const finishReason = response.candidates?.[0]?.finishReason;
  if (blockReason || finishReason === 'SAFETY' || finishReason === 'RECITATION') {
    return { text: null, refused: true, refusalCategory: blockReason || finishReason, usage: {} };
  }

  const usageMeta = response.usageMetadata || {};
  return {
    text: response.text() || null,
    refused: false,
    refusalCategory: null,
    usage: {
      inputTokens: usageMeta.promptTokenCount || 0,
      outputTokens: usageMeta.candidatesTokenCount || 0,
      cacheReadTokens: usageMeta.cachedContentTokenCount || 0,
      cacheWriteTokens: 0,
    },
  };
}

module.exports = { name, envKeyName, envModelName, defaultModel, packageName, isInstalled, generate };
