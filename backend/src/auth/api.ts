import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import type { FunctionUrlEvent, FunctionUrlResponse } from "../core/api";
import type { Clock, SecretProvider, UsageLimitStore } from "../core/contracts";
import { hashRegistrationIdentity, isInstallId } from "../core/jwt";
import type { IdentityVerifier, GoogleIdentity } from "./google";
import type { AuthStore } from "./store";
import { ACCESS_SECONDS, SESSION_SECONDS, hash, isOpaque, signAccess, verifyAccess, type AccountPrincipal } from "./tokens";

const LOGIN_SECONDS = 5 * 60;
const opaque = () => randomBytes(32).toString("base64url");
interface Login { installId: string; challenge: string; nonce: string; expiresAt: number; code?: string }
interface Session { accountId: string; installId: string; refreshHash: string; expiresAt: number; email: string; name: string }
export interface AccountGateway {
  handle(event: FunctionUrlEvent): Promise<FunctionUrlResponse>;
  authorize(token: string): Promise<AccountPrincipal | null>;
}
interface Options {
  clientId: string;
  clientSecret?: string;
  redirectUri?: string;
  store: AuthStore;
  verifier: IdentityVerifier;
  secrets: SecretProvider;
  usage: UsageLimitStore;
  clock: Clock;
  request?: typeof fetch;
}

export class AccountApi implements AccountGateway {
  constructor(private readonly options: Options) {}

  async handle(event: FunctionUrlEvent): Promise<FunctionUrlResponse> {
    const path = event.rawPath?.replace(/\/$/, "");
    const method = event.requestContext?.http?.method?.toUpperCase();
    try {
      if (path === "/v1/auth/config" && method === "GET") {
        return json(200, { googleWebClientId: this.options.clientId, browserAvailable: this.browserAvailable() });
      }
      if (path === "/v1/auth/google/callback" && method === "GET") return await this.callback(event);
      if (method !== "POST") return json(404, { error: "not_found" });
      const body = parseBody(event);
      if (!body) return json(400, { error: "bad_request" });
      switch (path) {
        case "/v1/auth/challenge": return await this.challenge(event, body);
        case "/v1/auth/google": return await this.finish(body, false);
        case "/v1/auth/browser": return await this.finish(body, true);
        case "/v1/auth/refresh": return await this.refresh(body);
        case "/v1/auth/logout": return await this.logout(body);
        case "/v1/auth/delete": return await this.deleteAccount(body);
        default: return json(404, { error: "not_found" });
      }
    } catch {
      // Provider errors can contain credentials; never expose or log their messages.
      return json(503, { error: "unavailable" });
    }
  }

  async authorize(token: string): Promise<AccountPrincipal | null> {
    const { jwtSigningKey } = await this.options.secrets.getSecrets();
    const now = this.options.clock.now();
    const principal = verifyAccess(token, jwtSigningKey, now);
    if (!principal) return null;
    const raw = await this.options.store.get("session:" + principal.sessionId);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return session.accountId === principal.accountId && session.installId === principal.installId && session.expiresAt > now.getTime() ? principal : null;
  }

  private async challenge(event: FunctionUrlEvent, body: Record<string, unknown>): Promise<FunctionUrlResponse> {
    if (!only(body, ["installId", "codeChallenge"]) || !isInstallId(body.installId) || !isOpaque(body.codeChallenge)) return json(400, { error: "bad_request" });
    const ip = event.requestContext?.http?.sourceIp;
    if (!ip) return json(503, { error: "unavailable" });
    const { jwtSigningKey } = await this.options.secrets.getSecrets();
    if (!await this.options.usage.consumeRegistration(hashRegistrationIdentity(ip, jwtSigningKey), this.options.clock.now())) return json(429, { error: "busy" });
    const loginId = opaque();
    const login: Login = { installId: body.installId, challenge: body.codeChallenge, nonce: opaque(), expiresAt: this.options.clock.now().getTime() + LOGIN_SECONDS * 1000 };
    await this.options.store.put("login:" + loginId, JSON.stringify(login), LOGIN_SECONDS);
    let browserUrl: string | null = null;
    if (this.browserAvailable()) {
      const query = new URLSearchParams({ client_id: this.options.clientId, redirect_uri: this.options.redirectUri!, response_type: "code", scope: "openid email profile", state: loginId, nonce: login.nonce, code_challenge: login.challenge, code_challenge_method: "S256", prompt: "select_account" });
      browserUrl = "https://accounts.google.com/o/oauth2/v2/auth?" + query.toString();
    }
    return json(200, { loginId, nonce: login.nonce, googleWebClientId: this.options.clientId, browserUrl, expiresIn: LOGIN_SECONDS });
  }

  private async finish(body: Record<string, unknown>, browser: boolean): Promise<FunctionUrlResponse> {
    if (!only(body, browser ? ["installId", "loginId", "codeVerifier"] : ["installId", "loginId", "codeVerifier", "idToken"]) || !isInstallId(body.installId) || !isOpaque(body.loginId) || !isOpaque(body.codeVerifier)) return json(400, { error: "bad_request" });
    if (!browser && (typeof body.idToken !== "string" || body.idToken.length > 16_384)) return json(400, { error: "bad_request" });
    const key = "login:" + body.loginId;
    const raw = await this.options.store.get(key);
    if (!raw) return json(401, { error: "unauthorized" });
    const login = JSON.parse(raw) as Login;
    if (login.expiresAt <= this.options.clock.now().getTime() || login.installId !== body.installId || createHash("sha256").update(body.codeVerifier).digest("base64url") !== login.challenge) return json(401, { error: "unauthorized" });
    let idToken = body.idToken as string;
    if (browser) {
      if (!this.browserAvailable() || !login.code) return json(400, { error: "login_incomplete" });
      // Claim the transaction before redeeming a single-use Google authorization code.
      if (!await this.options.store.compareAndSet(key, raw, null, 1)) return json(401, { error: "unauthorized" });
      const response = await (this.options.request ?? fetch)("https://oauth2.googleapis.com/token", {
        method: "POST", redirect: "error", signal: AbortSignal.timeout(5_000),
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ code: login.code, client_id: this.options.clientId, client_secret: this.options.clientSecret!, redirect_uri: this.options.redirectUri!, grant_type: "authorization_code", code_verifier: body.codeVerifier })
      });
      if (!response.ok) return json(401, { error: "unauthorized" });
      const result = await response.json() as { id_token?: unknown };
      if (typeof result.id_token !== "string") return json(401, { error: "unauthorized" });
      idToken = result.id_token;
    }
    const identity = await this.options.verifier.verify(idToken, login.nonce, this.options.clock.now());
    if (!identity) return json(401, { error: "unauthorized" });
    if (!browser && !await this.options.store.compareAndSet(key, raw, null, 1)) return json(401, { error: "unauthorized" });
    return this.newSession(identity, login);
  }

  private async newSession(identity: GoogleIdentity, login: Login): Promise<FunctionUrlResponse> {
    const { jwtSigningKey } = await this.options.secrets.getSecrets();
    const now = this.options.clock.now();
    const sessionId = opaque();
    const refreshToken = opaque();
    const session: Session = { accountId: hash("google-subject\0" + identity.subject, jwtSigningKey), installId: login.installId, refreshHash: hash(refreshToken, jwtSigningKey), expiresAt: now.getTime() + SESSION_SECONDS * 1000, email: identity.email, name: identity.name };
    const created = await this.options.store.createSession("session:" + sessionId, session.accountId, JSON.stringify(session), SESSION_SECONDS, login.expiresAt - LOGIN_SECONDS * 1000);
    if (!created) return json(401, { error: "unauthorized" });
    return this.sessionResponse(sessionId, refreshToken, session, jwtSigningKey, now);
  }

  private async refresh(body: Record<string, unknown>): Promise<FunctionUrlResponse> {
    const found = await this.findSession(body);
    if (!found) return json(401, { error: "unauthorized" });
    const { raw, session, key } = found;
    const { jwtSigningKey } = await this.options.secrets.getSecrets();
    const now = this.options.clock.now();
    const refreshToken = opaque();
    const next: Session = { ...session, refreshHash: hash(refreshToken, jwtSigningKey), expiresAt: now.getTime() + SESSION_SECONDS * 1000 };
    if (!await this.options.store.compareAndSet(key, raw, JSON.stringify(next), SESSION_SECONDS)) return json(401, { error: "unauthorized" });
    return this.sessionResponse(body.sessionId as string, refreshToken, next, jwtSigningKey, now);
  }

  private async logout(body: Record<string, unknown>): Promise<FunctionUrlResponse> {
    if (!only(body, ["installId", "sessionId", "logoutToken"]) || !isInstallId(body.installId) || !isOpaque(body.sessionId) || !isOpaque(body.logoutToken)) return json(400, { error: "bad_request" });
    const { jwtSigningKey } = await this.options.secrets.getSecrets();
    const expected = logoutToken(body.installId, body.sessionId, jwtSigningKey);
    // A distinct, stable credential lets sign-out win a race with refresh rotation.
    if (!timingSafeEqual(Buffer.from(expected), Buffer.from(body.logoutToken))) return json(401, { error: "unauthorized" });
    await this.options.store.delete("session:" + body.sessionId);
    return { statusCode: 204, headers: headers(), isBase64Encoded: false };
  }

  private async deleteAccount(body: Record<string, unknown>): Promise<FunctionUrlResponse> {
    if (!validSessionCredentials(body)) return json(400, { error: "bad_request" });
    const key = "session:" + body.sessionId;
    const { jwtSigningKey } = await this.options.secrets.getSecrets();
    const receipt = hash("friday-delete\0" + body.installId + "\0" + body.sessionId + "\0" + body.refreshToken, jwtSigningKey);
    const found = await this.findSession(body);
    const deleted = found
      ? await this.options.store.deleteAccount(key, found.session.accountId, found.raw, receipt, this.options.clock.now().getTime())
      : await this.options.store.deletionReceipt(key) === receipt;
    if (!deleted) return json(401, { error: "unauthorized" });
    return json(200, { deleted: true });
  }

  private async findSession(body: Record<string, unknown>): Promise<{ raw: string; session: Session; key: string } | null> {
    if (!validSessionCredentials(body)) return null;
    const key = "session:" + body.sessionId;
    const raw = await this.options.store.get(key);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    const { jwtSigningKey } = await this.options.secrets.getSecrets();
    if (session.installId !== body.installId || session.expiresAt <= this.options.clock.now().getTime() || session.refreshHash !== hash(body.refreshToken, jwtSigningKey)) return null;
    return { raw, session, key };
  }

  private sessionResponse(sessionId: string, refreshToken: string, session: Session, key: string, now: Date): FunctionUrlResponse {
    return json(200, { accessToken: signAccess({ sessionId, accountId: session.accountId, installId: session.installId }, key, now), expiresIn: ACCESS_SECONDS, sessionId, refreshToken, refreshExpiresIn: SESSION_SECONDS, logoutToken: logoutToken(session.installId, sessionId, key), email: session.email, displayName: session.name });
  }

  private async callback(event: FunctionUrlEvent): Promise<FunctionUrlResponse> {
    if (!this.browserAvailable()) return json(503, { error: "unavailable" });
    const query = new URLSearchParams(event.rawQueryString ?? "");
    const loginId = query.get("state");
    const code = query.get("code");
    if (!isOpaque(loginId) || !code || code.length > 4096 || query.getAll("state").length !== 1 || query.getAll("code").length !== 1) return json(400, { error: "bad_request" });
    const key = "login:" + loginId;
    const raw = await this.options.store.get(key);
    if (!raw) return json(401, { error: "unauthorized" });
    const login = JSON.parse(raw) as Login;
    if (login.code || login.expiresAt <= this.options.clock.now().getTime()) return json(401, { error: "unauthorized" });
    const ttl = Math.max(1, Math.ceil((login.expiresAt - this.options.clock.now().getTime()) / 1000));
    if (!await this.options.store.compareAndSet(key, raw, JSON.stringify({ ...login, code }), ttl)) return json(401, { error: "unauthorized" });
    // This link contains no token or Google code. Only the app with the verifier can finish.
    return { statusCode: 303, headers: { ...headers(), location: "com.puneet.friday://signin?loginId=" + loginId }, isBase64Encoded: false };
  }

  private browserAvailable(): boolean { return Boolean(this.options.clientSecret && this.options.redirectUri); }
}

function parseBody(event: FunctionUrlEvent): Record<string, unknown> | null {
  if (!Object.entries(event.headers ?? {}).some(([key, value]) => key.toLowerCase() === "content-type" && value?.split(";", 1)[0]?.trim().toLowerCase() === "application/json")) return null;
  try {
    const text = event.isBase64Encoded ? Buffer.from(event.body ?? "", "base64").toString("utf8") : event.body ?? "";
    if (Buffer.byteLength(text) > 24_576) return null;
    const value: unknown = JSON.parse(text);
    return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch { return null; }
}
function only(body: Record<string, unknown>, keys: string[]): boolean { return Object.keys(body).length === keys.length && keys.every(key => Object.hasOwn(body, key)); }
function validSessionCredentials(body: Record<string, unknown>): body is Record<string, unknown> & { installId: string; sessionId: string; refreshToken: string } {
  return only(body, ["installId", "sessionId", "refreshToken"]) && isInstallId(body.installId) && isOpaque(body.sessionId) && isOpaque(body.refreshToken);
}
function logoutToken(installId: string, sessionId: string, key: string): string { return hash("friday-logout\0" + installId + "\0" + sessionId, key); }
function headers(): Record<string, string> { return { "cache-control": "no-store", "x-content-type-options": "nosniff", "referrer-policy": "no-referrer" }; }
function json(statusCode: number, body: unknown): FunctionUrlResponse { return { statusCode, headers: { ...headers(), "content-type": "application/json; charset=utf-8" }, body: JSON.stringify(body), isBase64Encoded: false }; }
