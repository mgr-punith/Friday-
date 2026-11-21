import { loadVercelConfig } from "../config";
import { createApi } from "../core/api";
import type { Clock, SafeLogger } from "../core/contracts";
import { EnvironmentSecretProvider } from "../env/secrets";
import { GeminiProvider } from "../providers/gemini";
import { createRedisClient, RedisUsageLimitStore } from "../redis/usage-store";
import { createVercelHandler } from "./adapter";
import { AccountApi } from "../auth/api";
import { GoogleIdVerifier } from "../auth/google";
import { RedisAuthStore } from "../auth/store";

const config = loadVercelConfig();
const secrets = new EnvironmentSecretProvider(config.geminiApiKey, config.jwtSigningKey);
const redis = createRedisClient(config.redisRestUrl, config.redisRestToken);
const usage = new RedisUsageLimitStore(redis, config);
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
const accounts = new AccountApi({
  clientId: config.googleWebClientId,
  ...(config.googleWebClientSecret ? { clientSecret: config.googleWebClientSecret } : {}),
  ...(config.googleOauthRedirectUri ? { redirectUri: config.googleOauthRedirectUri } : {}),
  store: new RedisAuthStore(redis), verifier: new GoogleIdVerifier(config.googleWebClientId), secrets, usage, clock
});
const api = createApi({ assistant, secrets, usage, clock, logger, accounts });

export default { fetch: createVercelHandler(api) };
