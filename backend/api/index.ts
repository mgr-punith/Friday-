import { loadVercelConfig } from "../src/config";
import { createApi } from "../src/core/api";
import type { Clock, SafeLogger } from "../src/core/contracts";
import { EnvironmentSecretProvider } from "../src/env/secrets";
import { GeminiProvider } from "../src/providers/gemini";
import { createRedisClient, RedisUsageLimitStore } from "../src/redis/usage-store";
import { createVercelHandler } from "../src/vercel/adapter";

const config = loadVercelConfig();
const secrets = new EnvironmentSecretProvider(config.geminiApiKey, config.jwtSigningKey);
const usage = new RedisUsageLimitStore(
  createRedisClient(config.redisRestUrl, config.redisRestToken),
  config
);
const assistant = new GeminiProvider(
  {
    model: config.geminiModel,
    thinkingLevel: config.geminiThinkingLevel,
    timeoutMs: config.geminiTimeoutMs
  },
  secrets
);
const clock: Clock = { now: () => new Date() };
const logger: SafeLogger = {
  error(code, requestId) {
    console.error(JSON.stringify(requestId ? { level: "error", code, requestId } : { level: "error", code }));
  }
};
const api = createApi({ assistant, secrets, usage, clock, logger });

export default { fetch: createVercelHandler(api) };
