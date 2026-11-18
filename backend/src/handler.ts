import { createDynamoClient, DynamoUsageLimitStore } from "./aws/usage-store";
import { CachedSsmSecretProvider, createSsmClient } from "./aws/secrets";
import { loadConfig } from "./config";
import { createApi, type FunctionUrlEvent, type FunctionUrlResponse } from "./core/api";
import type { Clock, SafeLogger } from "./core/contracts";
import { GeminiProvider } from "./providers/gemini";

const config = loadConfig();
const secrets = new CachedSsmSecretProvider(
  createSsmClient(),
  config.geminiApiKeyParameterName,
  config.jwtSigningKeyParameterName
);
const usage = new DynamoUsageLimitStore(createDynamoClient(), config.tableName, config);
const assistant = new GeminiProvider(
  {
    model: config.geminiModel,
    thinkingLevel: config.geminiThinkingLevel,
    timeoutMs: config.geminiTimeoutMs
  },
  secrets
);

const clock: Clock = { now: () => new Date() };
const logger: SafeLogger = {
  error(code, requestId) {
    console.error(JSON.stringify(requestId ? { level: "error", code, requestId } : { level: "error", code }));
  }
};
const api = createApi({ assistant, secrets, usage, clock, logger });

export async function handler(event: FunctionUrlEvent): Promise<FunctionUrlResponse> {
  return api.handle(event);
}
