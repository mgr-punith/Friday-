import type { VercelAssistantConfig } from "../config";
import type { AssistantProvider, SecretProvider } from "../core/contracts";
import { GeminiProvider, type FetchLike } from "./gemini";
import { GroqProvider } from "./groq";

export function createVercelAssistant(config: VercelAssistantConfig, secrets: SecretProvider,
  fetcher: FetchLike = fetch): AssistantProvider {
  return config.provider === "groq" ? new GroqProvider(config, fetcher) : new GeminiProvider(config, secrets, fetcher);
}
