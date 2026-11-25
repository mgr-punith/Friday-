import assert from "node:assert/strict";
import test from "node:test";
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
