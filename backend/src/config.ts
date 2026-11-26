import { createPrivateKey } from "node:crypto";
import type { WaveNetConfig } from "./providers/wavenet";
import type { ElevenLabsConfig } from "./providers/elevenlabs";
import type { SpeechLimits } from "./core/speech";
import { PARAKEET_V2_FUNCTION_ID, type ParakeetConfig } from "./providers/parakeet";

export interface UsageLimits {
  readonly registerPerIpDaily: number;
  readonly registerGlobalDaily: number;
  readonly commandPerInstallMinute: number;
  readonly commandPerInstallDaily: number;
  readonly commandGlobalDaily: number;
}

export interface RuntimeConfig extends UsageLimits {
  readonly geminiModel: string;
  readonly geminiThinkingLevel: "MINIMAL" | "LOW" | "MEDIUM" | "HIGH";
  readonly geminiTimeoutMs: number;
}

export interface BackendConfig extends RuntimeConfig {
  readonly tableName: string;
  readonly geminiApiKeyParameterName: string;
  readonly jwtSigningKeyParameterName: string;
}

export interface VercelBackendConfig extends UsageLimits {
  readonly googleWebClientId: string;
  readonly googleWebClientSecret?: string;
  readonly googleOauthRedirectUri?: string;
  readonly assistant: VercelAssistantConfig;
  readonly jwtSigningKey: string;
  readonly redisRestUrl: string;
  readonly redisRestToken: string;
  readonly answerLimits?: { readonly perAccountMinute: number; readonly perAccountDaily: number; readonly globalDaily: number };
  readonly speech?: {
    readonly provider: (WaveNetConfig & { readonly kind: "wavenet" }) | (ElevenLabsConfig & { readonly kind: "elevenlabs" });
    readonly limits: SpeechLimits;
  };
}

export type VercelAssistantConfig =
  | { readonly provider: "gemini"; readonly apiKey: string; readonly model: string;
      readonly thinkingLevel: RuntimeConfig["geminiThinkingLevel"]; readonly timeoutMs: number }
  | { readonly provider: "groq"; readonly apiKey: string; readonly transcriptionModel: string;
      readonly actionModel: string; readonly timeoutMs: number; readonly maxCompletionTokens: number;
      readonly answersEnabled?: boolean; readonly answerTimeoutMs?: number;
      readonly transcription?: ParakeetConfig & { readonly provider: "nvidia-parakeet" } };

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): BackendConfig {
  return {
    ...loadRuntimeConfig(environment),
    tableName: required(environment, "USAGE_TABLE_NAME"),
    geminiApiKeyParameterName: required(environment, "GEMINI_API_KEY_PARAMETER"),
    jwtSigningKeyParameterName: required(environment, "JWT_SIGNING_KEY_PARAMETER")
  };
}

export function loadVercelConfig(environment: NodeJS.ProcessEnv = process.env): VercelBackendConfig {
  if (environment.ANSWERS_ENABLED && !["true", "false"].includes(environment.ANSWERS_ENABLED)) throw new Error("invalid_answers_enabled");
  if (environment.ANSWERS_ENABLED === "true" && (environment.AI_PROVIDER?.trim().toLowerCase() !== "groq" ||
    !["openai/gpt-oss-20b", "openai/gpt-oss-120b", "openai/gpt-oss-safeguard-20b"].includes(
      environment.GROQ_ACTION_MODEL?.trim() || "openai/gpt-oss-20b"))) throw new Error("answers_require_browser_model");
  const googleWebClientId = required(environment, "GOOGLE_WEB_CLIENT_ID");
  if (!/^[0-9]+-[a-zA-Z0-9_-]+\.apps\.googleusercontent\.com$/.test(googleWebClientId)) throw new Error("invalid_google_web_client_id");
  const clientSecret = environment.GOOGLE_WEB_CLIENT_SECRET?.trim();
  const redirectUri = environment.GOOGLE_OAUTH_REDIRECT_URI?.trim();
  if (Boolean(clientSecret) !== Boolean(redirectUri)) throw new Error("incomplete_google_browser_config");
  if (redirectUri) {
    const url = new URL(redirectUri);
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || url.pathname !== "/v1/auth/google/callback") throw new Error("invalid_google_oauth_redirect_uri");
  }
  const assistant = loadAssistantConfig(environment);
  const groq20b = assistant.provider === "groq" && assistant.actionModel === "openai/gpt-oss-20b";
  return {
    googleWebClientId,
    ...(clientSecret ? { googleWebClientSecret: clientSecret } : {}),
    ...(redirectUri ? { googleOauthRedirectUri: redirectUri } : {}),
    ...loadUsageLimits(environment, groq20b ? { minute: 8, daily: 150 } : undefined),
    assistant,
    ...loadSpeechConfig(environment),
    ...(environment.ANSWERS_ENABLED === "true" ? { answerLimits: {
      perAccountMinute: integer(environment, "ANSWER_PER_ACCOUNT_MINUTE", groq20b ? 3 : 12, 1, 12),
      perAccountDaily: integer(environment, "ANSWER_PER_ACCOUNT_DAILY", 100, 1, 100),
      globalDaily: integer(environment, "ANSWER_GLOBAL_DAILY", 1_000, 1, 100_000)
    } } : {}),
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

function loadSpeechConfig(environment: NodeJS.ProcessEnv): Pick<VercelBackendConfig, "speech"> {
  const provider = environment.SPEECH_PROVIDER?.trim() || "device";
  if (provider === "device") return {};
  if (provider !== "wavenet" && provider !== "elevenlabs") throw new Error("invalid_speech_provider");
  if (environment.ANSWERS_ENABLED !== "true") throw new Error("speech_requires_answers");
  const timeoutMs = integer(environment, "SPEECH_TIMEOUT_MS", 3_000, 500, 5_000);
  if (provider === "elevenlabs") {
    const apiKey = secret(environment, "ELEVENLABS_API_KEY", 16);
    if (apiKey.length > 4_096 || /[^\x21-\x7e]/.test(apiKey)) throw new Error("invalid_elevenlabs_api_key");
    const voiceId = required(environment, "ELEVENLABS_VOICE_ID");
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(voiceId)) throw new Error("invalid_elevenlabs_voice_id");
    const model = environment.ELEVENLABS_MODEL?.trim() || "eleven_flash_v2_5";
    if (!/^[A-Za-z0-9_-]{1,100}$/.test(model)) throw new Error("invalid_elevenlabs_model");
    return { speech: { provider: { kind: "elevenlabs", apiKey, voiceId, model, timeoutMs },
      limits: loadSpeechLimits(environment, 10_000) } };
  }
  const voice = environment.GOOGLE_TTS_VOICE?.trim() || "en-IN-Wavenet-A";
  if (!/^en-(?:IN|US|GB|AU)-Wavenet-[A-J]$/.test(voice)) throw new Error("invalid_speech_voice");
  const serviceAccountEmail = required(environment, "GOOGLE_TTS_SERVICE_ACCOUNT_EMAIL");
  if (!/^[a-z0-9-]+@[a-z0-9-]+\.iam\.gserviceaccount\.com$/.test(serviceAccountEmail)) throw new Error("invalid_speech_account");
  const projectId = required(environment, "GOOGLE_TTS_PROJECT_ID");
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)) throw new Error("invalid_speech_project");
  const privateKey = required(environment, "GOOGLE_TTS_PRIVATE_KEY").replace(/\\n/g, "\n");
  try {
    const key = createPrivateKey(privateKey);
    if (key.asymmetricKeyType !== "rsa" || (key.asymmetricKeyDetails?.modulusLength ?? 0) < 2_048) throw new Error();
  } catch { throw new Error("invalid_speech_private_key"); }
  return { speech: { provider: { kind: "wavenet", serviceAccountEmail, privateKey, projectId, voice, timeoutMs },
    limits: loadSpeechLimits(environment, 3_500_000) } };
}

function loadSpeechLimits(environment: NodeJS.ProcessEnv, monthlyDefault: number): SpeechLimits {
  return {
    accountMinute: integer(environment, "SPEECH_PER_ACCOUNT_MINUTE", 12, 1, 60),
    accountDailyCharacters: integer(environment, "SPEECH_PER_ACCOUNT_DAILY_CHARACTERS", 10_000, 600, 100_000),
    globalMonthlyCharacters: integer(environment, "SPEECH_GLOBAL_MONTHLY_CHARACTERS", monthlyDefault, 600, 4_000_000)
  };
}

function loadRuntimeConfig(environment: NodeJS.ProcessEnv): RuntimeConfig {
  return {
    ...loadUsageLimits(environment),
    geminiModel: modelName(required(environment, "GEMINI_MODEL")),
    geminiThinkingLevel: thinkingLevel(environment.GEMINI_THINKING_LEVEL ?? "MINIMAL"),
    geminiTimeoutMs: integer(environment, "GEMINI_TIMEOUT_MS", 10_000, 500, 12_000)
  };
}

function loadUsageLimits(environment: NodeJS.ProcessEnv, commandDefaults = { minute: 12, daily: 100 }): UsageLimits {
  return {
    registerPerIpDaily: integer(environment, "REGISTER_PER_IP_DAILY", 200, 1, 100_000),
    registerGlobalDaily: integer(environment, "REGISTER_GLOBAL_DAILY", 10_000, 1, 10_000_000),
    commandPerInstallMinute: integer(environment, "COMMAND_PER_INSTALL_MINUTE", commandDefaults.minute, 1, 1_000),
    commandPerInstallDaily: integer(environment, "COMMAND_PER_INSTALL_DAILY", commandDefaults.daily, 1, 100_000),
    commandGlobalDaily: integer(environment, "COMMAND_GLOBAL_DAILY", 100_000, 1, 100_000_000)
  };
}

function loadAssistantConfig(environment: NodeJS.ProcessEnv): VercelAssistantConfig {
  // Existing deployments keep Gemini until the owner explicitly selects Groq.
  const provider = environment.AI_PROVIDER?.trim().toLowerCase() || "gemini";
  if (provider === "groq") {
    return {
      provider,
      apiKey: secret(environment, "GROQ_API_KEY", 16),
      transcriptionModel: groqModel(environment.GROQ_TRANSCRIPTION_MODEL?.trim() || "whisper-large-v3-turbo"),
      actionModel: groqModel(environment.GROQ_ACTION_MODEL?.trim() || "openai/gpt-oss-20b"),
      timeoutMs: integer(environment, "GROQ_TIMEOUT_MS", 10_000, 500, 12_000),
      maxCompletionTokens: integer(environment, "GROQ_MAX_COMPLETION_TOKENS", 256, 128, 2_048),
      ...loadTranscriptionConfig(environment),
      ...(environment.ANSWERS_ENABLED === "true" ? {
        answersEnabled: true,
        answerTimeoutMs: integer(environment, "GROQ_ANSWER_TIMEOUT_MS", 18_000, 1_000, 25_000)
      } : {})
    };
  }
  if (provider !== "gemini") throw new Error("invalid_ai_provider");
  if (environment.TRANSCRIPTION_PROVIDER && environment.TRANSCRIPTION_PROVIDER !== "groq") {
    throw new Error("separate_transcription_requires_groq");
  }
  return {
    provider,
    apiKey: secret(environment, "GEMINI_API_KEY", 16),
    model: modelName(required(environment, "GEMINI_MODEL")),
    thinkingLevel: thinkingLevel(environment.GEMINI_THINKING_LEVEL ?? "MINIMAL"),
    timeoutMs: integer(environment, "GEMINI_TIMEOUT_MS", 10_000, 500, 12_000)
  };
}

function loadTranscriptionConfig(environment: NodeJS.ProcessEnv): { transcription?: ParakeetConfig & { provider: "nvidia-parakeet" } } {
  const provider = environment.TRANSCRIPTION_PROVIDER?.trim() || "groq";
  if (provider === "groq") return {};
  if (provider !== "nvidia-parakeet") throw new Error("invalid_transcription_provider");
  const apiKey = secret(environment, "NVIDIA_API_KEY", 16);
  if (apiKey.length > 4_096 || /[^\x21-\x7e]/.test(apiKey)) throw new Error("invalid_nvidia_api_key");
  const functionId = environment.NVIDIA_ASR_FUNCTION_ID?.trim() || PARAKEET_V2_FUNCTION_ID;
  if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(functionId)) throw new Error("invalid_nvidia_asr_function_id");
  const minConfidence = Number(required(environment, "NVIDIA_ASR_MIN_CONFIDENCE"));
  if (!Number.isFinite(minConfidence) || minConfidence === 0) throw new Error("invalid_nvidia_asr_min_confidence");
  return { transcription: { provider, apiKey, functionId, minConfidence } };
}

function groqModel(value: string): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]*(?:\/[A-Za-z0-9][A-Za-z0-9._-]*)?$/.test(value) || value.length > 100) {
    throw new Error("invalid_groq_model");
  }
  return value;
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
