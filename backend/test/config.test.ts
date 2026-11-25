import assert from "node:assert/strict";
import test from "node:test";
import { generateKeyPairSync } from "node:crypto";
import { loadConfig, loadVercelConfig } from "../src/config";

function environment(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  return {
    GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com",
    USAGE_TABLE_NAME: "FridayUsage",
    GEMINI_API_KEY_PARAMETER: "/friday/test/gemini-api-key",
    JWT_SIGNING_KEY_PARAMETER: "/friday/test/jwt-signing-key",
    GEMINI_MODEL: "gemini-3.5-flash-lite",
    ...overrides
  };
}

test("loads secure defaults and required deployment configuration", () => {
  const config = loadConfig(environment());
  assert.equal(config.geminiModel, "gemini-3.5-flash-lite");
  assert.equal(config.geminiThinkingLevel, "MINIMAL");
  assert.equal(config.geminiTimeoutMs, 10_000);
  assert.equal(config.commandPerInstallMinute, 12);
  assert.equal(config.commandPerInstallDaily, 100);
  assert.equal(config.commandGlobalDaily, 100_000);
});

test("rejects missing and malformed configuration", () => {
  assert.throws(() => loadConfig(environment({ GEMINI_MODEL: "" })), /missing_gemini_model/);
  assert.throws(() => loadConfig(environment({ GEMINI_MODEL: "bad/model/name" })), /invalid_gemini_model/);
  assert.throws(() => loadConfig(environment({ COMMAND_PER_INSTALL_DAILY: "zero" })), /invalid_command/);
  assert.throws(() => loadConfig(environment({ GEMINI_THINKING_LEVEL: "XHIGH" })), /invalid_gemini/);
});

test("loads Vercel secrets and Redis configuration", () => {
  const config = loadVercelConfig({
    GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com",
    GEMINI_API_KEY: "development-gemini-key",
    JWT_SIGNING_KEY: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes",
    UPSTASH_REDIS_REST_URL: "https://example.upstash.io/",
    UPSTASH_REDIS_REST_TOKEN: "development-redis-token",
    GEMINI_MODEL: "gemini-3.5-flash-lite"
  });
  assert.equal(config.redisRestUrl, "https://example.upstash.io");
  assert.equal(config.assistant.provider, "gemini");
  if (config.assistant.provider === "gemini") assert.equal(config.assistant.thinkingLevel, "MINIMAL");
});

test("loads Redis configuration created by the Vercel Upstash integration", () => {
  const config = loadVercelConfig({
    GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com",
    GEMINI_API_KEY: "development-gemini-key",
    JWT_SIGNING_KEY: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes",
    UPSTASH_REDIS_REST_KV_REST_API_URL: "https://integration.upstash.io/",
    UPSTASH_REDIS_REST_KV_REST_API_TOKEN: "integration-redis-token",
    GEMINI_MODEL: "gemini-3.5-flash-lite"
  });
  assert.equal(config.redisRestUrl, "https://integration.upstash.io");
  assert.equal(config.redisRestToken, "integration-redis-token");
});

test("prefers the standard Redis variable names when both forms exist", () => {
  const config = loadVercelConfig({
    GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com",
    GEMINI_API_KEY: "development-gemini-key",
    JWT_SIGNING_KEY: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes",
    UPSTASH_REDIS_REST_URL: "https://standard.upstash.io",
    UPSTASH_REDIS_REST_TOKEN: "standard-redis-token",
    UPSTASH_REDIS_REST_KV_REST_API_URL: "https://integration.upstash.io",
    UPSTASH_REDIS_REST_KV_REST_API_TOKEN: "integration-redis-token",
    GEMINI_MODEL: "gemini-3.5-flash-lite"
  });
  assert.equal(config.redisRestUrl, "https://standard.upstash.io");
  assert.equal(config.redisRestToken, "standard-redis-token");
});

test("rejects short Vercel secrets and non-HTTPS Redis URLs", () => {
  const base = {
    GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com",
    GEMINI_API_KEY: "development-gemini-key",
    JWT_SIGNING_KEY: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes",
    UPSTASH_REDIS_REST_URL: "https://example.upstash.io",
    UPSTASH_REDIS_REST_TOKEN: "development-redis-token",
    GEMINI_MODEL: "gemini-3.5-flash-lite"
  };
  assert.throws(() => loadVercelConfig({ ...base, JWT_SIGNING_KEY: "short" }), /invalid_jwt/);
  assert.throws(
    () => loadVercelConfig({ ...base, UPSTASH_REDIS_REST_URL: "http://example.com" }),
    /invalid_upstash/
  );
});

test("Google login fails closed without a valid web client and a complete browser configuration", () => {
  const base = {
    ...environment(), GEMINI_API_KEY: "development-gemini-key",
    JWT_SIGNING_KEY: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes",
    UPSTASH_REDIS_REST_URL: "https://example.upstash.io",
    UPSTASH_REDIS_REST_TOKEN: "development-redis-token"
  };
  assert.throws(() => loadVercelConfig({ ...base, GOOGLE_WEB_CLIENT_ID: "" }), /missing_google_web_client_id/);
  assert.throws(() => loadVercelConfig({ ...base, GOOGLE_WEB_CLIENT_ID: "not-a-web-client" }), /invalid_google_web_client_id/);
  assert.equal(loadVercelConfig(base).googleWebClientSecret, undefined);
  assert.throws(() => loadVercelConfig({ ...base, GOOGLE_WEB_CLIENT_SECRET: "example-browser-secret" }), /incomplete_google_browser_config/);
  for (const redirect of ["http://example.com/v1/auth/google/callback", "https://example.com/wrong", "https://user@example.com/v1/auth/google/callback", "https://example.com/v1/auth/google/callback?next=evil", "https://example.com/v1/auth/google/callback#fragment"]) {
    assert.throws(() => loadVercelConfig({ ...base, GOOGLE_WEB_CLIENT_SECRET: "example-browser-secret", GOOGLE_OAUTH_REDIRECT_URI: redirect }), /invalid_google_oauth_redirect_uri/);
  }
  assert.equal(loadVercelConfig({ ...base, GOOGLE_WEB_CLIENT_SECRET: "example-browser-secret", GOOGLE_OAUTH_REDIRECT_URI: "https://example.com/v1/auth/google/callback" }).googleOauthRedirectUri, "https://example.com/v1/auth/google/callback");
});

test("answers require explicit enablement, a browser-capable model and bounded search budgets", () => {
  const base = { GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com", AI_PROVIDER: "groq",
    GROQ_API_KEY: "example-development-groq-key", JWT_SIGNING_KEY: "example-long-test-signing-key-with-thirty-two-bytes",
    UPSTASH_REDIS_REST_URL: "https://example.upstash.io", UPSTASH_REDIS_REST_TOKEN: "development-redis-token" };
  assert.equal(loadVercelConfig(base).answerLimits, undefined);
  const enabled = loadVercelConfig({ ...base, ANSWERS_ENABLED: "true" });
  assert.deepEqual(enabled.answerLimits, { perAccountMinute: 3, perAccountDaily: 20, globalDaily: 1_000 });
  if (enabled.assistant.provider === "groq") assert.equal(enabled.assistant.answerTimeoutMs, 18_000);
  assert.throws(() => loadVercelConfig({ ...base, ANSWERS_ENABLED: "yes" }), /invalid_answers_enabled/);
  assert.throws(() => loadVercelConfig({ ...base, ANSWERS_ENABLED: "true", AI_PROVIDER: "gemini" }), /answers_require_browser_model/);
  assert.throws(() => loadVercelConfig({ ...base, ANSWERS_ENABLED: "true", GROQ_ACTION_MODEL: "groq/compound-mini" }), /answers_require_browser_model/);
  for (const [name, value] of [["ANSWER_GLOBAL_DAILY", "0"], ["ANSWER_PER_ACCOUNT_MINUTE", "100"],
    ["GROQ_ANSWER_TIMEOUT_MS", "30000"]]) {
    assert.throws(() => loadVercelConfig({ ...base, ANSWERS_ENABLED: "true", [name!]: value }), /invalid_/);
  }
});

test("WaveNet requires explicit enablement, answer consent support and valid server-only credentials", () => {
  const base = { GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com", AI_PROVIDER: "groq",
    GROQ_API_KEY: "example-development-groq-key", JWT_SIGNING_KEY: "example-long-test-signing-key-with-thirty-two-bytes",
    UPSTASH_REDIS_REST_URL: "https://example.upstash.io", UPSTASH_REDIS_REST_TOKEN: "development-redis-token" };
  assert.equal(loadVercelConfig(base).speech, undefined);
  assert.equal(loadVercelConfig({ ...base, SPEECH_PROVIDER: "device" }).speech, undefined);
  assert.throws(() => loadVercelConfig({ ...base, SPEECH_PROVIDER: "unknown" }), /invalid_speech_provider/);
  assert.throws(() => loadVercelConfig({ ...base, SPEECH_PROVIDER: "wavenet" }), /speech_requires_answers/);
  const key = generateKeyPairSync("rsa", { modulusLength: 2_048 }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  const enabled = { ...base, ANSWERS_ENABLED: "true", SPEECH_PROVIDER: "wavenet",
    GOOGLE_TTS_PROJECT_ID: "friday-example", GOOGLE_TTS_SERVICE_ACCOUNT_EMAIL: "friday-voice@friday-example.iam.gserviceaccount.com",
    GOOGLE_TTS_PRIVATE_KEY: key };
  const config = loadVercelConfig(enabled).speech!;
  assert.equal(config.provider.voice, "en-IN-Wavenet-A");
  assert.equal(config.provider.privateKey, key.trim());
  assert.equal(config.provider.timeoutMs, 3_000);
  assert.deepEqual(config.limits, { accountMinute: 12, accountDailyCharacters: 10_000, globalMonthlyCharacters: 3_500_000 });
  assert.equal(loadVercelConfig({ ...enabled, GOOGLE_TTS_PRIVATE_KEY: key.replace(/\n/g, "\\n") }).speech!.provider.privateKey.trim(), key.trim());
  const weakKey = generateKeyPairSync("rsa", { modulusLength: 1_024 }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  const otherKey = generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
  for (const patch of [
    { GOOGLE_TTS_PROJECT_ID: "https://example.com" }, { GOOGLE_TTS_SERVICE_ACCOUNT_EMAIL: "user@example.com" },
    { GOOGLE_TTS_PRIVATE_KEY: "invalid" }, { GOOGLE_TTS_PRIVATE_KEY: weakKey }, { GOOGLE_TTS_PRIVATE_KEY: otherKey },
    { GOOGLE_TTS_VOICE: "en-IN-Neural2-A" }, { GOOGLE_TTS_VOICE: "hi-IN-Wavenet-A" },
    { SPEECH_GLOBAL_MONTHLY_CHARACTERS: "4000001" }, { SPEECH_PER_ACCOUNT_MINUTE: "0" },
    { SPEECH_PER_ACCOUNT_DAILY_CHARACTERS: "599" }, { SPEECH_TIMEOUT_MS: "5001" }
  ]) assert.throws(() => loadVercelConfig({ ...enabled, ...patch }), /invalid_speech_/);
});
