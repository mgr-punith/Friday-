import { parseFunctionSelection, type CommandResponse } from "./action-schema";
import { actionProtocolVersion } from "./capabilities";
import {
  AssistantProviderError,
  type AssistantProvider,
  type Clock,
  type CommandInput,
  type SafeLogger,
  type SecretProvider,
  type UsageLimitStore
} from "./contracts";
import {
  hashRegistrationIdentity,
  isInstallId,
  signInstallToken,
  TOKEN_LIFETIME_SECONDS,
  verifyInstallToken
} from "./jwt";
import { isFridayWav } from "./wav";
import type { AccountGateway } from "../auth/api";

const MAX_AUDIO_BYTES = 1_000_000;
const MAX_TEXT_LENGTH = 500;

export interface FunctionUrlEvent {
  readonly rawPath?: string;
  readonly rawQueryString?: string;
  readonly headers?: Readonly<Record<string, string | undefined>>;
  readonly body?: string | null;
  readonly isBase64Encoded?: boolean;
  readonly requestContext?: {
    readonly requestId?: string;
    readonly http?: {
      readonly method?: string;
      readonly sourceIp?: string;
    };
  };
}

export interface FunctionUrlResponse {
  readonly statusCode: number;
  readonly headers?: Readonly<Record<string, string>>;
  readonly body?: string;
  readonly isBase64Encoded?: false;
}

export interface ApiDependencies {
  readonly assistant: AssistantProvider;
  readonly secrets: SecretProvider;
  readonly usage: UsageLimitStore;
  readonly clock: Clock;
  readonly logger: SafeLogger;
  readonly accounts?: AccountGateway;
}

export function createApi(dependencies: ApiDependencies) {
  return {
    async handle(event: FunctionUrlEvent): Promise<FunctionUrlResponse> {
      const method = event.requestContext?.http?.method?.toUpperCase() ?? "";
      const path = normalizePath(event.rawPath ?? "/");

      if (path.startsWith("/v1/auth/")) {
        return dependencies.accounts?.handle({ ...event, rawPath: path }) ?? json(503, { error: "unavailable" });
      }

      if (method === "POST" && path === "/v1/register") {
        if (dependencies.accounts) return json(401, { error: "sign_in_required" });
        return handleRegister(event, dependencies);
      }
      if (method === "GET" && path === "/v1/warm") {
        return handleWarm(event, dependencies);
      }
      if (method === "POST" && path === "/v1/command") {
        return handleCommand(event, dependencies);
      }
      return json(404, { error: "not_found" });
    }
  };
}

async function handleRegister(
  event: FunctionUrlEvent,
  dependencies: ApiDependencies
): Promise<FunctionUrlResponse> {
  const installId = parseRegistration(event);
  if (!installId) return json(400, { error: "bad_request" });

  const sourceIp = event.requestContext?.http?.sourceIp?.trim();
  if (!sourceIp) return serviceUnavailable();

  try {
    const secrets = await dependencies.secrets.getSecrets();
    const identityHash = hashRegistrationIdentity(sourceIp, secrets.jwtSigningKey);
    const now = dependencies.clock.now();
    const allowed = await dependencies.usage.consumeRegistration(identityHash, now);
    if (!allowed) return json(429, { error: "busy" });

    const token = signInstallToken(installId, secrets.jwtSigningKey, now);
    return json(200, { token, expiresIn: TOKEN_LIFETIME_SECONDS });
  } catch {
    dependencies.logger.error("register_failed", requestId(event));
    return serviceUnavailable();
  }
}

async function handleWarm(
  event: FunctionUrlEvent,
  dependencies: ApiDependencies
): Promise<FunctionUrlResponse> {
  try {
    await dependencies.secrets.getSecrets();
    return {
      statusCode: 204,
      headers: baseHeaders(),
      isBase64Encoded: false
    };
  } catch {
    dependencies.logger.error("warm_failed", requestId(event));
    return serviceUnavailable();
  }
}

async function handleCommand(
  event: FunctionUrlEvent,
  dependencies: ApiDependencies
): Promise<FunctionUrlResponse> {
  let secrets;
  try {
    secrets = await dependencies.secrets.getSecrets();
  } catch {
    dependencies.logger.error("secret_load_failed", requestId(event));
    return commandError(503, "busy");
  }

  const token = bearerToken(header(event, "authorization"));
  let installId: string | null;
  try {
    const account = dependencies.accounts && token ? await dependencies.accounts.authorize(token) : null;
    installId = dependencies.accounts ? account?.accountId ?? null
      : token ? verifyInstallToken(token, secrets.jwtSigningKey, dependencies.clock.now()) : null;
  } catch {
    dependencies.logger.error("account_check_failed", requestId(event));
    return commandError(503, "busy");
  }
  if (!installId) return commandError(401, "unauthorized");

  const input = parseCommandInput(event);
  const now = header(event, "x-now");
  if (!input || !now || !isOffsetDateTime(now)) return commandError(400, "bad_audio");

  try {
    const allowed = await dependencies.usage.consumeCommand(installId, dependencies.clock.now());
    if (!allowed) return commandError(429, "busy");
  } catch {
    dependencies.logger.error("usage_check_failed", requestId(event));
    return commandError(503, "busy");
  }

  try {
    const actionVersion = actionProtocolVersion(header(event, "x-friday-actions"));
    const result = await dependencies.assistant.select(
      actionVersion === 1 ? input : { ...input, actionVersion },
      now,
    );
    const response = parseFunctionSelection(result.selection, actionVersion);
    return response.type === "error"
      ? commandError(400, response.reason, result.durationMs, result.timingMetric, result.stages)
      : commandSuccess(response, result.durationMs, result.timingMetric, result.stages);
  } catch (error) {
    if (error instanceof AssistantProviderError) {
      dependencies.logger.error(error.failure?.diagnosticCode ?? `assistant_${error.code}`, requestId(event));
      const status = error.code === "timeout" ? 504 : error.failure?.httpStatus === 429 ? 429 : 503;
      const response = commandError(status, error.code, error.durationMs, error.failure?.timingMetric);
      return error.failure?.retryAfterSeconds === undefined ? response : {
        ...response, headers: { ...response.headers, "retry-after": String(error.failure.retryAfterSeconds) }
      };
    }
    dependencies.logger.error("assistant_failed", requestId(event));
    return commandError(503, "busy");
  }
}

function parseRegistration(event: FunctionUrlEvent): string | null {
  if (contentType(event) !== "application/json") return null;
  const value = parseJsonBody(event);
  if (!isRecord(value) || !hasOnlyKeys(value, ["installId"])) return null;
  return isInstallId(value.installId) ? value.installId : null;
}

function parseCommandInput(event: FunctionUrlEvent): CommandInput | null {
  const type = contentType(event);
  if (type === "audio/wav") {
    if (!event.isBase64Encoded || !event.body) return null;
    if (event.body.length > 1_333_336) return null;
    const bytes = decodeBase64(event.body);
    if (!bytes || bytes.byteLength > MAX_AUDIO_BYTES || !isFridayWav(bytes)) return null;
    return { kind: "audio", bytes, mimeType: "audio/wav" };
  }

  if (type === "application/json") {
    const value = parseJsonBody(event);
    if (!isRecord(value) || !hasOnlyKeys(value, ["text"]) || typeof value.text !== "string") {
      return null;
    }
    const text = value.text.trim();
    if (text.length === 0 || text.length > MAX_TEXT_LENGTH) return null;
    return { kind: "text", text };
  }
  return null;
}

function parseJsonBody(event: FunctionUrlEvent): unknown {
  if (!event.body) return null;
  try {
    const body = event.isBase64Encoded
      ? Buffer.from(event.body, "base64").toString("utf8")
      : event.body;
    if (Buffer.byteLength(body, "utf8") > 16_384) return null;
    return JSON.parse(body);
  } catch {
    return null;
  }
}

function decodeBase64(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(value) || value.length % 4 !== 0) return null;
  try {
    return Buffer.from(value, "base64");
  } catch {
    return null;
  }
}

function isOffsetDateTime(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|([+-])(\d{2}):(\d{2}))$/.exec(value);
  if (!match) return false;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const offsetHour = match[7] === "Z" ? 0 : Number(match[9]);
  const offsetMinute = match[7] === "Z" ? 0 : Number(match[10]);
  if (year < 2000 || year > 2100 || month < 1 || month > 12) return false;
  if (hour > 23 || minute > 59 || second > 59) return false;
  if (offsetHour > 14 || offsetMinute > 59 || (offsetHour === 14 && offsetMinute !== 0)) return false;

  const calendar = new Date(Date.UTC(year, month - 1, day));
  return (
    calendar.getUTCFullYear() === year &&
    calendar.getUTCMonth() === month - 1 &&
    calendar.getUTCDate() === day
  );
}

function bearerToken(value: string | undefined): string | null {
  if (!value) return null;
  const match = /^Bearer ([^\s]+)$/i.exec(value.trim());
  return match?.[1] ?? null;
}

function header(event: FunctionUrlEvent, name: string): string | undefined {
  const headers = event.headers ?? {};
  const target = name.toLowerCase();
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() === target) return value;
  }
  return undefined;
}

function contentType(event: FunctionUrlEvent): string {
  return header(event, "content-type")?.split(";", 1)[0]?.trim().toLowerCase() ?? "";
}

function normalizePath(path: string): string {
  if (path.length > 1 && path.endsWith("/")) return path.slice(0, -1);
  return path;
}

function requestId(event: FunctionUrlEvent): string | undefined {
  return event.requestContext?.requestId;
}

function commandSuccess(response: CommandResponse, durationMs: number, metric?: "gemini" | "groq",
  stages?: Readonly<Record<string, number>>): FunctionUrlResponse {
  return json(200, response, serverTiming(durationMs, metric, stages));
}

function commandError(
  statusCode: number,
  reason: "busy" | "timeout" | "bad_audio" | "unauthorized",
  durationMs?: number,
  metric?: "gemini" | "groq",
  stages?: Readonly<Record<string, number>>
): FunctionUrlResponse {
  return json(statusCode, { type: "error", reason }, durationMs === undefined ? undefined : serverTiming(durationMs, metric, stages));
}

function serviceUnavailable(): FunctionUrlResponse {
  return json(503, { error: "unavailable" });
}

function json(
  statusCode: number,
  value: unknown,
  extraHeaders?: Readonly<Record<string, string>>
): FunctionUrlResponse {
  return {
    statusCode,
    headers: { ...baseHeaders(), "content-type": "application/json; charset=utf-8", ...extraHeaders },
    body: JSON.stringify(value),
    isBase64Encoded: false
  };
}

function baseHeaders(): Record<string, string> {
  return {
    "cache-control": "no-store",
    "x-content-type-options": "nosniff"
  };
}

function serverTiming(durationMs: number, metric: "gemini" | "groq" = "gemini",
  stages: Readonly<Record<string, number>> = {}): Record<string, string> {
  const safeDuration = Number.isFinite(durationMs) ? Math.max(0, durationMs) : 0;
  const metrics = [`${metric};dur=${safeDuration.toFixed(1)}`];
  for (const name of ["transcription", "classification"]) {
    const duration = stages[name];
    if (duration !== undefined && Number.isFinite(duration) && duration >= 0) metrics.push(`${name};dur=${duration.toFixed(1)}`);
  }
  return { "server-timing": metrics.join(", ") };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  const keys = Object.keys(value);
  return keys.length === allowed.length && keys.every((key) => allowed.includes(key));
}
