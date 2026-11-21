import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import test from "node:test";
import { AccountApi } from "../src/auth/api";
import type { GoogleIdentity, IdentityVerifier } from "../src/auth/google";
import type { AuthStore } from "../src/auth/store";
import { ACCESS_SECONDS, SESSION_SECONDS, verifyAccess } from "../src/auth/tokens";
import { createApi, type FunctionUrlEvent, type FunctionUrlResponse } from "../src/core/api";
import { signInstallToken } from "../src/core/jwt";
import { MockAssistant, MockUsage, RecordingLogger, StaticSecrets, TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

const clientId = "123456789-test.apps.googleusercontent.com";
const otherInstall = "123e4567-e89b-42d3-a456-426614174001";
const opaque = () => randomBytes(32).toString("base64url");

class MemoryAuthStore implements AuthStore {
  readonly values = new Map<string, { value: string; expires: number }>();
  fail = false;
  constructor(private readonly now: () => number) {}
  async get(key: string): Promise<string | null> {
    if (this.fail) throw new Error("test_store_unavailable");
    const found = this.values.get(key);
    return found && found.expires > this.now() ? found.value : null;
  }
  async put(key: string, value: string, ttl: number): Promise<void> {
    if (this.fail) throw new Error("test_store_unavailable");
    this.values.set(key, { value, expires: this.now() + ttl * 1000 });
  }
  async delete(key: string): Promise<void> {
    if (this.fail) throw new Error("test_store_unavailable");
    this.values.delete(key);
  }
  async compareAndSet(key: string, expected: string, replacement: string | null, ttl: number): Promise<boolean> {
    if (this.fail) throw new Error("test_store_unavailable");
    const found = this.values.get(key);
    if (!found || found.expires <= this.now() || found.value !== expected) return false;
    if (replacement === null) this.values.delete(key);
    else this.values.set(key, { value: replacement, expires: this.now() + ttl * 1000 });
    return true;
  }
}

function event(path: string, body?: unknown, rawQueryString?: string): FunctionUrlEvent {
  return {
    rawPath: path, headers: { "content-type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    ...(rawQueryString === undefined ? {} : { rawQueryString }),
    requestContext: { http: { method: body === undefined ? "GET" : "POST", sourceIp: "192.0.2.1" } }
  };
}

const value = (response: FunctionUrlResponse) => JSON.parse(response.body ?? "null");
function harness(browser = false) {
  let now = TEST_NOW.getTime();
  let identity: GoogleIdentity | null = { subject: "test-google-account", email: "person@example.com", name: "Test Person" };
  const store = new MemoryAuthStore(() => now);
  const usage = new MockUsage();
  const secrets = new StaticSecrets();
  const clock = { now: () => new Date(now) };
  const verified: Array<{ token: string; nonce: string }> = [];
  const requests: Array<{ url: unknown; init?: RequestInit }> = [];
  const verifier: IdentityVerifier = { async verify(token, nonce) { verified.push({ token, nonce }); return token === "test-google-token" ? identity : null; } };
  const accounts = new AccountApi({ clientId, store, usage, secrets, clock, verifier,
    ...(browser ? { clientSecret: "example-browser-secret", redirectUri: "https://friday.example/v1/auth/google/callback" } : {}),
    request: (async (url: unknown, init?: RequestInit) => { requests.push({ url, ...(init ? { init } : {}) }); return Response.json({ id_token: "test-google-token" }); }) as typeof fetch
  });
  const assistant = new MockAssistant();
  const logger = new RecordingLogger();
  const api = createApi({ accounts, usage, secrets, clock, assistant, logger });
  async function begin(installId = TEST_INSTALL_ID) {
    const verifier = opaque();
    const response = await api.handle(event("/v1/auth/challenge", { installId, codeChallenge: createHash("sha256").update(verifier).digest("base64url") }));
    assert.equal(response.statusCode, 200);
    return { ...value(response), codeVerifier: verifier, installId };
  }
  async function login(installId = TEST_INSTALL_ID) {
    const challenge = await begin(installId);
    const response = await accounts.handle(event("/v1/auth/google", { installId, loginId: challenge.loginId, codeVerifier: challenge.codeVerifier, idToken: "test-google-token" }));
    assert.equal(response.statusCode, 200);
    return { ...value(response), installId };
  }
  async function command(accessToken: string) {
    const input = event("/v1/command", { text: "Set a timer for two minutes" });
    return api.handle({ ...input, headers: { ...input.headers, authorization: "Bearer " + accessToken, "x-now": "2026-11-17T14:00:00+05:30" } });
  }
  const credentials = (session: ReturnType<typeof value>) => ({ installId: session.installId, sessionId: session.sessionId, refreshToken: session.refreshToken });
  const logoutCredentials = (session: ReturnType<typeof value>) => ({ installId: session.installId, sessionId: session.sessionId, logoutToken: session.logoutToken });
  return { accounts, api, store, usage, assistant, logger, verified, requests, begin, login, command, credentials, logoutCredentials,
    advance(seconds: number) { now += seconds * 1000; }, setIdentity(next: GoogleIdentity | null) { identity = next; }
  };
}

test("login issues bounded Friday credentials, checks its nonce and keeps provider tokens out of storage", async () => {
  const h = harness();
  const challenge = await h.begin();
  assert.equal(challenge.browserUrl, null);
  assert.equal(challenge.expiresIn, 300);
  const response = await h.accounts.handle(event("/v1/auth/google", { installId: TEST_INSTALL_ID, loginId: challenge.loginId, codeVerifier: challenge.codeVerifier, idToken: "test-google-token" }));
  assert.equal(response.statusCode, 200);
  assert.equal(response.headers?.["cache-control"], "no-store");
  const session = value(response);
  assert.equal(session.expiresIn, ACCESS_SECONDS);
  assert.equal(session.refreshExpiresIn, SESSION_SECONDS);
  assert.equal(session.email, "person@example.com");
  assert.deepEqual(h.verified, [{ token: "test-google-token", nonce: challenge.nonce }]);
  const principal = await h.accounts.authorize(session.accessToken);
  assert.equal(principal?.installId, TEST_INSTALL_ID);
  assert.equal(principal?.sessionId, session.sessionId);
  const stored = await h.store.get("session:" + session.sessionId);
  assert.ok(stored);
  assert.equal(stored.includes(session.refreshToken), false);
  assert.equal(stored.includes("test-google-token"), false);
  assert.equal(stored.includes("test-google-account"), false);
  assert.equal(await h.store.get("login:" + challenge.loginId), null);
});

test("requires an unexpired, install-bound verifier and consumes the challenge once under races", async () => {
  const h = harness();
  const challenge = await h.begin();
  const good = { installId: TEST_INSTALL_ID, loginId: challenge.loginId, codeVerifier: challenge.codeVerifier, idToken: "test-google-token" };
  for (const bad of [{ ...good, installId: otherInstall }, { ...good, codeVerifier: opaque() }, { ...good, idToken: "forged" }]) {
    assert.equal((await h.accounts.handle(event("/v1/auth/google", bad))).statusCode, 401);
  }
  const responses = await Promise.all([h.accounts.handle(event("/v1/auth/google", good)), h.accounts.handle(event("/v1/auth/google", good))]);
  assert.deepEqual(responses.map(result => result.statusCode).sort(), [200, 401]);
  assert.equal((await h.accounts.handle(event("/v1/auth/google", good))).statusCode, 401);
  const expired = await h.begin();
  h.advance(301);
  assert.equal((await h.accounts.handle(event("/v1/auth/google", { ...good, loginId: expired.loginId, codeVerifier: expired.codeVerifier }))).statusCode, 401);
});

test("refresh rotates credentials, binds the install, rejects replay and expires idle sessions", async () => {
  const h = harness();
  const session = await h.login();
  assert.equal((await h.accounts.handle(event("/v1/auth/refresh", { ...h.credentials(session), installId: otherInstall }))).statusCode, 401);
  const responses = await Promise.all([h.accounts.handle(event("/v1/auth/refresh", h.credentials(session))), h.accounts.handle(event("/v1/auth/refresh", h.credentials(session)))]);
  assert.deepEqual(responses.map(result => result.statusCode).sort(), [200, 401]);
  const next = value(responses.find(result => result.statusCode === 200)!);
  assert.notEqual(next.refreshToken, session.refreshToken);
  assert.equal((await h.accounts.handle(event("/v1/auth/refresh", h.credentials(session)))).statusCode, 401);
  h.advance(ACCESS_SECONDS);
  assert.equal(await h.accounts.authorize(session.accessToken), null);
  h.advance(SESSION_SECONDS);
  assert.equal((await h.accounts.handle(event("/v1/auth/refresh", { ...h.credentials(session), refreshToken: next.refreshToken }))).statusCode, 401);
});

test("sign-out revokes access at once and cannot revoke another session with guessed credentials", async () => {
  const h = harness();
  const session = await h.login();
  const other = await h.login(otherInstall);
  assert.equal((await h.command(session.accessToken)).statusCode, 200);
  assert.equal((await h.accounts.handle(event("/v1/auth/logout", { ...h.logoutCredentials(session), sessionId: other.sessionId }))).statusCode, 401);
  assert.ok(await h.accounts.authorize(other.accessToken));
  assert.equal((await h.accounts.handle(event("/v1/auth/logout", h.logoutCredentials(session)))).statusCode, 204);
  assert.equal((await h.accounts.handle(event("/v1/auth/logout", h.logoutCredentials(session)))).statusCode, 204);
  assert.equal(await h.accounts.authorize(session.accessToken), null);
  assert.equal((await h.command(session.accessToken)).statusCode, 401);
  assert.equal(h.assistant.calls.length, 1);
});

test("sign-out wins a race with refresh and does not depend on the rotated refresh credential", async () => {
  const h = harness();
  const session = await h.login();
  const responses = await Promise.all([
    h.accounts.handle(event("/v1/auth/refresh", h.credentials(session))),
    h.accounts.handle(event("/v1/auth/logout", h.logoutCredentials(session)))
  ]);
  assert.equal(responses[1]?.statusCode, 204);
  assert.equal(await h.accounts.authorize(session.accessToken), null);
  if (responses[0]?.statusCode === 200) assert.equal(await h.accounts.authorize(value(responses[0]).accessToken), null);
  assert.equal((await h.accounts.handle(event("/v1/auth/refresh", h.credentials(session)))).statusCode, 401);
});

test("account quotas apply across installs and anonymous credentials cannot bypass sign-in", async () => {
  const h = harness();
  const first = await h.login();
  const second = await h.login(otherInstall);
  await h.command(first.accessToken);
  await h.command(second.accessToken);
  assert.equal(h.usage.commandCalls[0]?.installId, h.usage.commandCalls[1]?.installId);
  assert.notEqual(h.usage.commandCalls[0]?.installId, TEST_INSTALL_ID);
  h.setIdentity({ subject: "different-google-account", email: "other@example.com", name: "Other Person" });
  await h.command((await h.login()).accessToken);
  assert.notEqual(h.usage.commandCalls[0]?.installId, h.usage.commandCalls[2]?.installId);
  assert.equal((await h.api.handle(event("/v1/register", { installId: TEST_INSTALL_ID }))).statusCode, 401);
  assert.equal((await h.command(signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW))).statusCode, 401);
  assert.equal(h.assistant.calls.length, 3);
});

test("Redis failures and registration caps stop login or commands without calling the model", async () => {
  const h = harness();
  const first = await h.login();
  h.usage.registrationAllowed = false;
  assert.equal((await h.accounts.handle(event("/v1/auth/challenge", { installId: TEST_INSTALL_ID, codeChallenge: opaque() }))).statusCode, 429);
  h.store.fail = true;
  assert.equal((await h.command(first.accessToken)).statusCode, 503);
  assert.equal((await h.accounts.handle(event("/v1/auth/refresh", h.credentials(first)))).statusCode, 503);
  assert.equal(h.assistant.calls.length, 0);
  assert.deepEqual(h.logger.entries, [{ code: "account_check_failed" }]);
});

test("browser OAuth uses state, nonce and PKCE; the callback contains no tokens or Google code", async () => {
  const h = harness(true);
  const login = await h.begin();
  const url = new URL(login.browserUrl);
  assert.equal(url.origin, "https://accounts.google.com");
  assert.equal(url.searchParams.get("state"), login.loginId);
  assert.equal(url.searchParams.get("nonce"), login.nonce);
  assert.equal(url.searchParams.get("code_challenge_method"), "S256");
  assert.equal(url.searchParams.get("code_challenge"), createHash("sha256").update(login.codeVerifier).digest("base64url"));
  assert.equal(url.searchParams.has("access_type"), false);
  const callback = event("/v1/auth/google/callback", undefined, new URLSearchParams({ state: login.loginId, code: "test-google-code" }).toString());
  const response = await h.api.handle(callback);
  assert.equal(response.statusCode, 303);
  assert.equal(response.headers?.location, "com.puneet.friday://signin?loginId=" + login.loginId);
  assert.equal(JSON.stringify(response).includes("test-google-code"), false);
  assert.equal((await h.api.handle(callback)).statusCode, 401);
  const body = { installId: TEST_INSTALL_ID, loginId: login.loginId, codeVerifier: login.codeVerifier };
  assert.equal((await h.accounts.handle(event("/v1/auth/browser", { ...body, codeVerifier: opaque() }))).statusCode, 401);
  assert.equal(h.requests.length, 0);
  assert.equal((await h.accounts.handle(event("/v1/auth/browser", body))).statusCode, 200);
  assert.equal((await h.accounts.handle(event("/v1/auth/browser", body))).statusCode, 401);
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0]?.url, "https://oauth2.googleapis.com/token");
  const sent = h.requests[0]?.init?.body as URLSearchParams;
  assert.equal(sent.get("code_verifier"), login.codeVerifier);
  assert.equal(sent.get("code"), "test-google-code");
  assert.equal(h.requests[0]?.init?.redirect, "error");
});

test("malformed auth requests, duplicate callback parameters and expired state fail closed", async () => {
  const h = harness(true);
  for (const body of [[], null, {}, { installId: TEST_INSTALL_ID, codeChallenge: "wrong" }, { installId: TEST_INSTALL_ID, codeChallenge: opaque(), accountId: "injected" }]) {
    assert.equal((await h.accounts.handle(event("/v1/auth/challenge", body))).statusCode, 400);
  }
  assert.equal((await h.accounts.handle({ ...event("/v1/auth/challenge", {}), body: "bad json" })).statusCode, 400);
  assert.equal((await h.accounts.handle({ ...event("/v1/auth/challenge", {}), body: "x".repeat(24_577) })).statusCode, 400);
  const login = await h.begin();
  const query = new URLSearchParams({ state: login.loginId, code: "test-code" });
  query.append("state", login.loginId);
  assert.equal((await h.accounts.handle(event("/v1/auth/google/callback", undefined, query.toString()))).statusCode, 400);
  h.advance(301);
  assert.equal((await h.accounts.handle(event("/v1/auth/google/callback", undefined, new URLSearchParams({ state: login.loginId, code: "test-code" }).toString()))).statusCode, 401);
  assert.equal(h.requests.length, 0);
  assert.equal(verifyAccess("invalid", TEST_SECRETS.jwtSigningKey, TEST_NOW), null);
});
