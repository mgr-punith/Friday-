import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig, loadVercelConfig } from "../src/config";

function environment(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
  return {
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
    GEMINI_API_KEY: "development-gemini-key",
    JWT_SIGNING_KEY: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes",
    UPSTASH_REDIS_REST_URL: "https://example.upstash.io/",
    UPSTASH_REDIS_REST_TOKEN: "development-redis-token",
    GEMINI_MODEL: "gemini-3.5-flash-lite"
  });
  assert.equal(config.redisRestUrl, "https://example.upstash.io");
  assert.equal(config.geminiThinkingLevel, "MINIMAL");
});

test("loads Redis configuration created by the Vercel Upstash integration", () => {
  const config = loadVercelConfig({
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
