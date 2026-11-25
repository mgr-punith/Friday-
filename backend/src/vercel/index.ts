import { loadVercelConfig } from "../config";
import { createApi } from "../core/api";
import type { Clock, SafeLogger } from "../core/contracts";
import { EnvironmentSecretProvider } from "../env/secrets";
import { createVercelAssistant } from "../providers/vercel-assistant";
import { createRedisClient, RedisUsageLimitStore } from "../redis/usage-store";
import { createVercelHandler } from "./adapter";
import { AccountApi } from "../auth/api";
import { GoogleIdVerifier } from "../auth/google";
import { RedisAuthStore } from "../auth/store";
import { RedisAnswerReportStore } from "../core/answers";

const config = loadVercelConfig();
const secrets = new EnvironmentSecretProvider(config.assistant.provider === "gemini" ? config.assistant.apiKey : undefined, config.jwtSigningKey);
const redis = createRedisClient(config.redisRestUrl, config.redisRestToken);
const usage = new RedisUsageLimitStore(redis, config);
const assistant = createVercelAssistant(config.assistant, secrets);
const clock: Clock = { now: () => new Date() };
const logger: SafeLogger = {
  error(code, requestId) {
    console.error(JSON.stringify(requestId ? { level: "error", code, requestId } : { level: "error", code }));
  },
  usage(stages) {
    console.info(JSON.stringify({ event: "ai_usage", stages }));
  }
};
const accounts = new AccountApi({
  clientId: config.googleWebClientId,
  ...(config.googleWebClientSecret ? { clientSecret: config.googleWebClientSecret } : {}),
  ...(config.googleOauthRedirectUri ? { redirectUri: config.googleOauthRedirectUri } : {}),
  store: new RedisAuthStore(redis), verifier: new GoogleIdVerifier(config.googleWebClientId), secrets, usage, clock
});
const answers = config.assistant.provider === "groq" && config.assistant.answersEnabled && config.answerLimits ? {
  usage: new RedisUsageLimitStore(redis, { ...config,
    commandPerInstallMinute: config.answerLimits.perAccountMinute,
    commandPerInstallDaily: config.answerLimits.perAccountDaily,
    commandGlobalDaily: config.answerLimits.globalDaily }, "friday:answers:v1"),
  reports: new RedisAnswerReportStore(redis)
} : undefined;
const api = createApi({ assistant, secrets, usage, clock, logger, accounts, ...(answers ? { answers } : {}) });

export default { fetch: createVercelHandler(api) };
