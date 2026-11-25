import assert from "node:assert/strict";
import test from "node:test";
import { RedisUsageLimitStore, type RedisEvalSender } from "../src/redis/usage-store";
import { TEST_INSTALL_ID, TEST_NOW } from "./helpers";

const LIMITS = {
  geminiModel: "gemini-3.5-flash-lite",
  geminiThinkingLevel: "MINIMAL" as const,
  geminiTimeoutMs: 6_000,
  registerPerIpDaily: 200,
  registerGlobalDaily: 10_000,
  commandPerInstallMinute: 12,
  commandPerInstallDaily: 100,
  commandGlobalDaily: 100_000
};

test("registration sends two counters in one Redis script", async () => {
  const calls: Array<{ script: string; keys: string[]; args: Array<string | number> }> = [];
  const client: RedisEvalSender = {
    async eval<TData>(script: string, keys: string[], args: Array<string | number>): Promise<TData> {
      calls.push({ script, keys, args });
      return 1 as TData;
    }
  };
  const store = new RedisUsageLimitStore(client, LIMITS);
  assert.equal(await store.consumeRegistration("identity-hash", TEST_NOW), true);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0]?.keys, [
    "friday:v1:registration:ip:identity-hash:2026-11-17",
    "friday:v1:registration:global:2026-11-17"
  ]);
  assert.equal(calls[0]?.args[0], 200);
  assert.equal(calls[0]?.args[2], 10_000);
});

test("command script checks the block key and three counters", async () => {
  const calls: Array<{ keys: string[]; args: Array<string | number> }> = [];
  const client: RedisEvalSender = {
    async eval<TData>(_script: string, keys: string[], args: Array<string | number>): Promise<TData> {
      calls.push({ keys, args });
      return "1" as TData;
    }
  };
  const store = new RedisUsageLimitStore(client, LIMITS);
  assert.equal(await store.consumeCommand(TEST_INSTALL_ID, TEST_NOW), true);
  assert.equal(calls[0]?.keys[0], `friday:v1:install:${TEST_INSTALL_ID}:blocked`);
  assert.equal(calls[0]?.keys[1]?.includes(":minute:2026-11-17T08:30"), true);
  assert.equal(calls[0]?.keys[2]?.includes(":day:2026-11-17"), true);
  assert.equal(calls[0]?.keys[3], "friday:v1:command:global:day:2026-11-17");
  assert.deepEqual(calls[0]?.args.filter((_value, index) => index % 2 === 0), [12, 100, 100_000]);
});

test("Redis limit responses fail closed", async () => {
  const limited: RedisEvalSender = { async eval<TData>(): Promise<TData> { return 0 as TData; } };
  assert.equal(await new RedisUsageLimitStore(limited, LIMITS).consumeCommand(TEST_INSTALL_ID, TEST_NOW), false);

  const malformed: RedisEvalSender = { async eval<TData>(): Promise<TData> { return 2 as TData; } };
  await assert.rejects(
    new RedisUsageLimitStore(malformed, LIMITS).consumeRegistration("identity-hash", TEST_NOW),
    /invalid_redis_limit_result/
  );
});

test("live search has separate atomic counters but shares blocked-account checks", async () => {
  let keys: string[] = [];
  const store = new RedisUsageLimitStore({ async eval<T>(_script: string, k: string[]) { keys = k; return 1 as T; } },
    { ...LIMITS, commandPerInstallMinute: 3, commandPerInstallDaily: 20, commandGlobalDaily: 1_000 }, "friday:answers:v1");
  await store.consumeCommand(TEST_INSTALL_ID, TEST_NOW);
  assert.equal(keys[0], `friday:v1:install:${TEST_INSTALL_ID}:blocked`);
  assert.equal(keys[1]?.startsWith("friday:answers:v1:"), true);
  assert.equal(keys[3], "friday:answers:v1:command:global:day:2026-11-17");
});
