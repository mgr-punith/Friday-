import { createHmac, timingSafeEqual } from "node:crypto";

const ISSUER = "friday-backend";
const AUDIENCE = "friday-app";
const MAX_TOKEN_SECONDS = 31 * 24 * 60 * 60;

export const TOKEN_LIFETIME_SECONDS = 30 * 24 * 60 * 60;

export function isInstallId(value: unknown): value is string {
  return (
    typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
  );
}

export function signInstallToken(
  installId: string,
  secret: string,
  now: Date,
  lifetimeSeconds = TOKEN_LIFETIME_SECONDS
): string {
  if (!isInstallId(installId)) throw new Error("invalid_install_id");
  if (!Number.isInteger(lifetimeSeconds) || lifetimeSeconds < 1 || lifetimeSeconds > MAX_TOKEN_SECONDS) {
    throw new Error("invalid_token_lifetime");
  }

  const issuedAt = Math.floor(now.getTime() / 1000);
  const header = encodeJson({ alg: "HS256", typ: "JWT" });
  const payload = encodeJson({
    iss: ISSUER,
    aud: AUDIENCE,
    sub: installId,
    iat: issuedAt,
    exp: issuedAt + lifetimeSeconds
  });
  const unsigned = `${header}.${payload}`;
  return `${unsigned}.${sign(unsigned, secret)}`;
}

export function verifyInstallToken(token: string, secret: string, now: Date): string | null {
  if (token.length === 0 || token.length > 4096) return null;
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const [encodedHeader, encodedPayload, suppliedSignature] = parts;
  if (!encodedHeader || !encodedPayload || !suppliedSignature) return null;

  const expectedSignature = sign(`${encodedHeader}.${encodedPayload}`, secret);
  const supplied = Buffer.from(suppliedSignature);
  const expected = Buffer.from(expectedSignature);
  if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return null;

  const header = decodeJson(encodedHeader);
  const payload = decodeJson(encodedPayload);
  if (!isRecord(header) || header.alg !== "HS256" || header.typ !== "JWT") return null;
  if (!isRecord(payload)) return null;

  const nowSeconds = Math.floor(now.getTime() / 1000);
  const { iss, aud, sub, iat, exp } = payload;
  if (iss !== ISSUER || aud !== AUDIENCE || !isInstallId(sub)) return null;
  if (!Number.isInteger(iat) || !Number.isInteger(exp)) return null;
  if (typeof iat !== "number" || typeof exp !== "number") return null;
  if (iat > nowSeconds + 60 || exp <= nowSeconds || exp <= iat || exp - iat > MAX_TOKEN_SECONDS) {
    return null;
  }
  return sub;
}

export function hashRegistrationIdentity(sourceIp: string, secret: string): string {
  return createHmac("sha256", secret)
    .update("friday-registration-ip\0", "utf8")
    .update(sourceIp, "utf8")
    .digest("base64url");
}

function sign(unsigned: string, secret: string): string {
  return createHmac("sha256", secret).update(unsigned, "utf8").digest("base64url");
}

function encodeJson(value: unknown): string {
  return Buffer.from(JSON.stringify(value), "utf8").toString("base64url");
}

function decodeJson(value: string): unknown {
  try {
    return JSON.parse(Buffer.from(value, "base64url").toString("utf8"));
  } catch {
    return null;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
