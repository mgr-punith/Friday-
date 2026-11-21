import { createHmac, timingSafeEqual } from "node:crypto";
import { isInstallId } from "../core/jwt";

export const ACCESS_SECONDS = 15 * 60;
export const SESSION_SECONDS = 30 * 24 * 60 * 60;
export interface AccountPrincipal { readonly accountId: string; readonly installId: string; readonly sessionId: string }
export const isOpaque = (value: unknown): value is string => typeof value === "string" && /^[A-Za-z0-9_-]{43}$/.test(value);
export const hash = (value: string, key: string): string => createHmac("sha256", key).update(value).digest("base64url");

export function signAccess(principal: AccountPrincipal, key: string, now: Date): string {
  const iat = Math.floor(now.getTime() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const body = Buffer.from(JSON.stringify({ iss: "friday-backend", aud: "friday-account", sub: principal.accountId, install: principal.installId, sid: principal.sessionId, iat, exp: iat + ACCESS_SECONDS })).toString("base64url");
  const unsigned = `${header}.${body}`;
  return `${unsigned}.${hash(unsigned, hash("friday-account-access", key))}`;
}

export function verifyAccess(token: string, key: string, now: Date): AccountPrincipal | null {
  if (token.length > 4096) return null;
  const parts = token.split(".");
  if (parts.length !== 3 || !parts.every(part => /^[A-Za-z0-9_-]+$/.test(part))) return null;
  const [header, body, signature] = parts as [string, string, string];
  const expected = Buffer.from(hash(`${header}.${body}`, hash("friday-account-access", key)));
  const supplied = Buffer.from(signature);
  if (expected.length !== supplied.length || !timingSafeEqual(expected, supplied)) return null;
  try {
    const h = JSON.parse(Buffer.from(header, "base64url").toString());
    const p = JSON.parse(Buffer.from(body, "base64url").toString());
    const seconds = Math.floor(now.getTime() / 1000);
    if (!h || !p || h.alg !== "HS256" || h.typ !== "JWT" || p.iss !== "friday-backend" || p.aud !== "friday-account") return null;
    if (!isOpaque(p.sub) || !isOpaque(p.sid) || !isInstallId(p.install)) return null;
    if (!Number.isInteger(p.iat) || !Number.isInteger(p.exp) || p.iat > seconds + 60 || p.exp <= seconds || p.exp - p.iat !== ACCESS_SECONDS) return null;
    return { accountId: p.sub, sessionId: p.sid, installId: p.install };
  } catch { return null; }
}
