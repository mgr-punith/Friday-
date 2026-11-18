export interface RuntimeConfig {
  readonly geminiModel: string;
  readonly geminiThinkingLevel: "MINIMAL" | "LOW" | "MEDIUM" | "HIGH";
  readonly geminiTimeoutMs: number;
  readonly registerPerIpDaily: number;
  readonly registerGlobalDaily: number;
  readonly commandPerInstallMinute: number;
  readonly commandPerInstallDaily: number;
  readonly commandGlobalDaily: number;
}

export interface BackendConfig extends RuntimeConfig {
  readonly tableName: string;
  readonly geminiApiKeyParameterName: string;
  readonly jwtSigningKeyParameterName: string;
}

export interface VercelBackendConfig extends RuntimeConfig {
  readonly geminiApiKey: string;
  readonly jwtSigningKey: string;
  readonly redisRestUrl: string;
  readonly redisRestToken: string;
}

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): BackendConfig {
  return {
    ...loadRuntimeConfig(environment),
    tableName: required(environment, "USAGE_TABLE_NAME"),
    geminiApiKeyParameterName: required(environment, "GEMINI_API_KEY_PARAMETER"),
    jwtSigningKeyParameterName: required(environment, "JWT_SIGNING_KEY_PARAMETER")
  };
}

export function loadVercelConfig(environment: NodeJS.ProcessEnv = process.env): VercelBackendConfig {
  return {
    ...loadRuntimeConfig(environment),
    geminiApiKey: secret(environment, "GEMINI_API_KEY", 16),
    jwtSigningKey: secret(environment, "JWT_SIGNING_KEY", 32),
    redisRestUrl: httpsUrl(requiredFirst(environment, [
      "UPSTASH_REDIS_REST_URL",
      "UPSTASH_REDIS_REST_KV_REST_API_URL"
    ])),
    redisRestToken: secretFirst(environment, [
      "UPSTASH_REDIS_REST_TOKEN",
      "UPSTASH_REDIS_REST_KV_REST_API_TOKEN"
    ], 16)
  };
}

function loadRuntimeConfig(environment: NodeJS.ProcessEnv): RuntimeConfig {
  return {
    geminiModel: modelName(required(environment, "GEMINI_MODEL")),
    geminiThinkingLevel: thinkingLevel(environment.GEMINI_THINKING_LEVEL ?? "MINIMAL"),
    geminiTimeoutMs: integer(environment, "GEMINI_TIMEOUT_MS", 10_000, 500, 12_000),
    registerPerIpDaily: integer(environment, "REGISTER_PER_IP_DAILY", 200, 1, 100_000),
    registerGlobalDaily: integer(environment, "REGISTER_GLOBAL_DAILY", 10_000, 1, 10_000_000),
    commandPerInstallMinute: integer(environment, "COMMAND_PER_INSTALL_MINUTE", 12, 1, 1_000),
    commandPerInstallDaily: integer(environment, "COMMAND_PER_INSTALL_DAILY", 100, 1, 100_000),
    commandGlobalDaily: integer(environment, "COMMAND_GLOBAL_DAILY", 100_000, 1, 100_000_000)
  };
}

function required(environment: NodeJS.ProcessEnv, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`missing_${name.toLowerCase()}`);
  return value;
}

function secret(environment: NodeJS.ProcessEnv, name: string, minimumLength: number): string {
  const value = required(environment, name);
  if (value.length < minimumLength) throw new Error(`invalid_${name.toLowerCase()}`);
  return value;
}

function requiredFirst(environment: NodeJS.ProcessEnv, names: readonly string[]): string {
  for (const name of names) {
    const value = environment[name]?.trim();
    if (value) return value;
  }
  throw new Error(`missing_${names[0]?.toLowerCase() ?? "environment_value"}`);
}

function secretFirst(environment: NodeJS.ProcessEnv, names: readonly string[], minimumLength: number): string {
  const value = requiredFirst(environment, names);
  if (value.length < minimumLength) throw new Error(`invalid_${names[0]?.toLowerCase() ?? "environment_secret"}`);
  return value;
}

function httpsUrl(value: string): string {
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.username || url.password) throw new Error();
    return url.toString().replace(/\/$/, "");
  } catch {
    throw new Error("invalid_upstash_redis_rest_url");
  }
}

function integer(
  environment: NodeJS.ProcessEnv,
  name: string,
  fallback: number,
  minimum: number,
  maximum: number
): number {
  const raw = environment[name];
  if (raw === undefined || raw.trim() === "") return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`invalid_${name.toLowerCase()}`);
  }
  return value;
}

function modelName(value: string): string {
  const trimmed = value.trim().replace(/^models\//, "");
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(trimmed)) throw new Error("invalid_gemini_model");
  return trimmed;
}

function thinkingLevel(value: string): BackendConfig["geminiThinkingLevel"] {
  const normalized = value.trim().toUpperCase();
  if (normalized === "MINIMAL" || normalized === "LOW" || normalized === "MEDIUM" || normalized === "HIGH") {
    return normalized;
  }
  throw new Error("invalid_gemini_thinking_level");
}
