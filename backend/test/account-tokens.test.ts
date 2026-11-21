import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import test from "node:test";
import { ACCESS_SECONDS, signAccess, verifyAccess } from "../src/auth/tokens";
import { RedisAuthStore } from "../src/auth/store";
import type { RedisEvalSender } from "../src/redis/usage-store";
import { TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

test("account JWTs expire, reject tampering and use an audience separate from install tokens", () => {
  const principal = { accountId: randomBytes(32).toString("base64url"), installId: TEST_INSTALL_ID, sessionId: randomBytes(32).toString("base64url") };
  const token = signAccess(principal, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  assert.deepEqual(verifyAccess(token, TEST_SECRETS.jwtSigningKey, TEST_NOW), principal);
  assert.equal(verifyAccess(token, "other-signing-key-for-tests", TEST_NOW), null);
  assert.equal(verifyAccess(token, TEST_SECRETS.jwtSigningKey, new Date(TEST_NOW.getTime() + ACCESS_SECONDS * 1000)), null);
  assert.equal(verifyAccess(token, TEST_SECRETS.jwtSigningKey, new Date(TEST_NOW.getTime() - 61_000)), null);
  const pieces = token.split(".");
  const claims = JSON.parse(Buffer.from(pieces[1]!, "base64url").toString());
  claims.sub = randomBytes(32).toString("base64url");
  pieces[1] = Buffer.from(JSON.stringify(claims)).toString("base64url");
  assert.equal(verifyAccess(pieces.join("."), TEST_SECRETS.jwtSigningKey, TEST_NOW), null);
  assert.equal(verifyAccess("x".repeat(4097), TEST_SECRETS.jwtSigningKey, TEST_NOW), null);
});

test("Redis auth uses a distinct namespace, bounded TTLs and atomic compare-and-set", async () => {
  const calls: Array<{ script: string; keys: string[]; args: Array<string | number> }> = [];
  const values: unknown[] = ["OK", "stored", 1, "0", null, 2, false];
  const client: RedisEvalSender = { async eval<T>(script: string, keys: string[], args: Array<string | number>): Promise<T> { calls.push({ script, keys, args }); return values.shift() as T; } };
  const store = new RedisAuthStore(client);
  await store.put("login:public-id", "stored", 300);
  assert.equal(await store.get("login:public-id"), "stored");
  assert.equal(await store.compareAndSet("login:public-id", "stored", null, 1), true);
  assert.equal(await store.compareAndSet("login:public-id", "stored", "next", 300), false);
  assert.equal(await store.get("login:public-id"), null);
  assert.deepEqual(calls[0]?.keys, ["friday:auth:v1:login:public-id"]);
  assert.deepEqual(calls[0]?.args, ["stored", 300]);
  assert.deepEqual(calls[2]?.args, ["stored", "", 1]);
  assert.match(calls[2]!.script, /GET/);
  assert.match(calls[2]!.script, /DEL/);
  await assert.rejects(store.compareAndSet("login:public-id", "stored", null, 1), /auth_store_update_failed/);
  assert.equal(await store.get("missing"), null);
});

test("Redis auth rejects malformed or unavailable responses", async () => {
  for (const result of [undefined, {}, [], 123]) {
    const client: RedisEvalSender = { async eval<T>(): Promise<T> { return result as T; } };
    await assert.rejects(new RedisAuthStore(client).get("session:test"), /invalid_auth_store_response/);
    await assert.rejects(new RedisAuthStore(client).put("session:test", "value", 60), /auth_store_write_failed/);
  }
  const failed: RedisEvalSender = { async eval<T>(): Promise<T> { throw new Error("offline"); } };
  await assert.rejects(new RedisAuthStore(failed).get("session:test"), /offline/);
  await assert.rejects(new RedisAuthStore(failed).delete("session:test"), /offline/);
});
