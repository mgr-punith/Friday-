import { createPublicKey, verify, type JsonWebKey } from "node:crypto";

export interface GoogleIdentity {
  readonly subject: string;
  readonly email: string;
  readonly name: string;
}

export interface IdentityVerifier {
  verify(token: string, nonce: string, now: Date): Promise<GoogleIdentity | null>;
}

/** Only Google's fixed JWKS endpoint can supply signing keys. No token-provided URLs. */
export class GoogleIdVerifier implements IdentityVerifier {
  private keys: Array<JsonWebKey & { kid?: string; alg?: string }> = [];
  private expiresAt = 0;
  private lastFetch = 0;
  private loading: Promise<void> | undefined;

  constructor(private readonly clientId: string, private readonly request: typeof fetch = fetch) {}

  async verify(token: string, nonce: string, now: Date): Promise<GoogleIdentity | null> {
    if (token.length > 16_384) return null;
    const parts = token.split(".");
    if (parts.length !== 3 || !parts.every(part => /^[A-Za-z0-9_-]+$/.test(part))) return null;
    const [head, body, signature] = parts as [string, string, string];
    let header: Record<string, unknown>;
    let claims: Record<string, unknown>;
    try {
      header = JSON.parse(Buffer.from(head, "base64url").toString("utf8"));
      claims = JSON.parse(Buffer.from(body, "base64url").toString("utf8"));
    } catch { return null; }
    if (!header || !claims || header.alg !== "RS256" || typeof header.kid !== "string") return null;
    if (header.kid.length > 128 || header.crit !== undefined) return null;
    const seconds = Math.floor(now.getTime() / 1000);
    if (claims.aud !== this.clientId || !["accounts.google.com", "https://accounts.google.com"].includes(String(claims.iss))) return null;
    if (typeof claims.exp !== "number" || !Number.isInteger(claims.exp) || claims.exp <= seconds) return null;
    if (typeof claims.iat !== "number" || !Number.isInteger(claims.iat) || claims.iat > seconds + 60 || claims.exp <= claims.iat) return null;
    if (claims.nonce !== nonce || typeof claims.sub !== "string" || !/^[\x21-\x7e]{1,255}$/.test(claims.sub)) return null;
    await this.loadKeys(now.getTime(), header.kid);
    const key = this.keys.find(item => item.kid === header.kid && item.kty === "RSA" && (!item.alg || item.alg === "RS256"));
    if (!key) return null;
    try {
      if (!verify("RSA-SHA256", Buffer.from(`${head}.${body}`), createPublicKey({ key, format: "jwk" }), Buffer.from(signature, "base64url"))) return null;
    } catch { return null; }
    return {
      subject: claims.sub,
      email: claims.email_verified === true && typeof claims.email === "string" ? claims.email.slice(0, 254) : "",
      name: typeof claims.name === "string" ? claims.name.slice(0, 100) : ""
    };
  }

  private async loadKeys(now: number, kid: string): Promise<void> {
    if (this.expiresAt > now && this.keys.some(key => key.kid === kid)) return;
    if (this.loading) return this.loading;
    // Unknown key IDs cannot force an unbounded stream of Google requests.
    if (this.lastFetch && now - this.lastFetch < 60_000) return;
    this.lastFetch = now;
    this.loading = (async () => {
      const response = await this.request("https://www.googleapis.com/oauth2/v3/certs", { signal: AbortSignal.timeout(4_000), redirect: "error" });
      if (!response.ok) throw new Error("google_keys_unavailable");
      const text = await response.text();
      if (text.length > 65_536) throw new Error("google_keys_invalid");
      const value = JSON.parse(text) as { keys?: Array<JsonWebKey & { kid?: string }> };
      if (!Array.isArray(value.keys) || value.keys.length > 20 || !value.keys.every(key => key && typeof key === "object")) throw new Error("google_keys_invalid");
      this.keys = value.keys;
      const ttl = Number(/max-age=(\d+)/.exec(response.headers.get("cache-control") ?? "")?.[1] ?? "3600");
      this.expiresAt = now + Math.min(Math.max(ttl, 60), 21_600) * 1000;
    })();
    try { await this.loading; } finally { this.loading = undefined; }
  }
}
