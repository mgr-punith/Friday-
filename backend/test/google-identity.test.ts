import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";
import test from "node:test";
import { GoogleIdVerifier } from "../src/auth/google";
import { TEST_NOW } from "./helpers";

const clientId = "123456789-test.apps.googleusercontent.com";
const first = generateKeyPairSync("rsa", { modulusLength: 2048 });
const second = generateKeyPairSync("rsa", { modulusLength: 2048 });
const seconds = TEST_NOW.getTime() / 1000;
const claims = { aud: clientId, iss: "https://accounts.google.com", sub: "google-subject-1", nonce: "test-nonce", iat: seconds, exp: seconds + 3600, email: "person@example.com", email_verified: true, name: "Test Person" };

function token(overrides: Record<string, unknown> = {}, header: Record<string, unknown> = {}, key = first.privateKey): string {
  const encoded = [JSON.stringify({ alg: "RS256", kid: "key-1", ...header }), JSON.stringify({ ...claims, ...overrides })].map(value => Buffer.from(value).toString("base64url")).join(".");
  return encoded + "." + sign("RSA-SHA256", Buffer.from(encoded), key).toString("base64url");
}

function harness() {
  let calls = 0;
  let failure = false;
  let keys = [{ ...first.publicKey.export({ format: "jwk" }), kid: "key-1", alg: "RS256" }];
  const request = (async (url: string | URL | Request, init?: RequestInit) => {
    calls++;
    assert.equal(url, "https://www.googleapis.com/oauth2/v3/certs");
    assert.equal(init?.redirect, "error");
    assert.ok(init?.signal);
    return new Response(failure ? "unavailable" : JSON.stringify({ keys }), { status: failure ? 503 : 200, headers: { "cache-control": "public, max-age=3600" } });
  }) as typeof fetch;
  return { verifier: new GoogleIdVerifier(clientId, request), get calls() { return calls; }, fail() { failure = true; }, rotate() { keys = [{ ...second.publicKey.export({ format: "jwk" }), kid: "key-2", alg: "RS256" }]; } };
}

test("Google identity uses verified RSA signatures and cached fixed-endpoint keys", async () => {
  const h = harness();
  const expected = { subject: claims.sub, email: claims.email, name: claims.name };
  assert.deepEqual(await h.verifier.verify(token(), "test-nonce", TEST_NOW), expected);
  assert.deepEqual(await h.verifier.verify(token({ iss: "accounts.google.com", azp: "android-client.apps.googleusercontent.com" }), "test-nonce", TEST_NOW), expected);
  assert.equal(h.calls, 1);
  assert.deepEqual(await h.verifier.verify(token({ email_verified: false }), "test-nonce", TEST_NOW), { ...expected, email: "" });
});

test("rejects wrong audience, issuer, nonce, expiry and invalid token structures before key fetch", async () => {
  const h = harness();
  const invalid = [
    token({ aud: "other-client" }), token({ aud: [clientId, "other-client"] }), token({ iss: "https://attacker.example" }),
    token({ nonce: "other" }), token({ sub: "" }), token({ sub: 123 }), token({ exp: seconds }),
    token({ iat: seconds + 61 }), token({ exp: seconds + 0.5 }), token({ exp: seconds - 1 }),
    token({}, { alg: "none" }), token({}, { alg: "HS256" }), token({}, { crit: ["x"] }),
    "not.a.jwt", "e30=.e30.signature", "a".repeat(16_385), token({}, { kid: "a".repeat(129) })
  ];
  for (const value of invalid) assert.equal(await h.verifier.verify(value, "test-nonce", TEST_NOW), null);
  assert.equal(h.calls, 0);
});

test("rejects forged signatures and unknown keys without fetching for each bad key", async () => {
  const h = harness();
  assert.equal(await h.verifier.verify(token({}, {}, second.privateKey), "test-nonce", TEST_NOW), null);
  assert.equal(await h.verifier.verify(token({}, { kid: "unknown" }), "test-nonce", TEST_NOW), null);
  assert.equal(await h.verifier.verify(token({}, { kid: "another" }), "test-nonce", TEST_NOW), null);
  assert.equal(h.calls, 1);
  h.rotate();
  assert.equal(await h.verifier.verify(token({}, { kid: "key-2" }, second.privateKey), "test-nonce", new Date(TEST_NOW.getTime() + 61_000)) !== null, true);
  assert.equal(h.calls, 2);
});

test("shares concurrent key requests and fails closed when Google is unavailable", async () => {
  const h = harness();
  const identities = await Promise.all(Array.from({ length: 5 }, () => h.verifier.verify(token(), "test-nonce", TEST_NOW)));
  assert.equal(identities.every(Boolean), true);
  assert.equal(h.calls, 1);
  const failed = harness();
  failed.fail();
  await assert.rejects(failed.verifier.verify(token(), "test-nonce", TEST_NOW), /google_keys_unavailable/);
  assert.equal(await failed.verifier.verify(token(), "test-nonce", TEST_NOW), null);
  assert.equal(failed.calls, 1);
});
