/**
 * Context Weaver — provider registry.
 *
 * generate-descriptions.js is provider-agnostic: it builds one prompt and one schema,
 * then hands both to whichever provider `LLM_PROVIDER` names. Every provider module
 * exports the same shape (name, envKeyName, envModelName, defaultModel, packageName,
 * isInstalled, generate) so adding a fourth provider later is one new file plus one
 * line in the registry below — nothing in generate-descriptions.js has to change.
 */
const anthropic = require('./anthropic');
const openai = require('./openai');
const google = require('./google');

const REGISTRY = { anthropic, openai, google };

/**
 * Resolve the active provider from environment (CLI flags may override the pieces
 * that make sense to override — see generate-descriptions.js parseArgs). Throws with
 * an actionable message rather than defaulting silently, since a silent fallback to
 * the wrong provider would send a request to a service the user did not configure.
 */
function resolveProvider(providerName) {
  const key = (providerName || process.env.LLM_PROVIDER || 'anthropic').toLowerCase();
  const provider = REGISTRY[key];
  if (!provider) {
    throw new Error(
      `Unknown LLM_PROVIDER "${key}". Valid values: ${Object.keys(REGISTRY).join(', ')}.`
    );
  }
  return provider;
}

module.exports = { REGISTRY, resolveProvider };
