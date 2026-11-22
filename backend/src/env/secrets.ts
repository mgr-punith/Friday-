import type { SecretBundle, SecretProvider } from "../core/contracts";

export class EnvironmentSecretProvider implements SecretProvider {
  private readonly value: SecretBundle;

  constructor(geminiApiKey: string | undefined, jwtSigningKey: string) {
    this.value = validateSecrets(geminiApiKey, jwtSigningKey);
  }

  async getSecrets(): Promise<SecretBundle> {
    return this.value;
  }
}

export function validateSecrets(geminiApiKey: string | undefined, jwtSigningKey: string): SecretBundle {
  const trimmedGeminiKey = geminiApiKey?.trim();
  const trimmedJwtKey = jwtSigningKey.trim();
  if (geminiApiKey !== undefined && (!trimmedGeminiKey || trimmedGeminiKey.length < 16)) throw new Error("invalid_gemini_api_key");
  if (trimmedJwtKey.length < 32) throw new Error("invalid_jwt_signing_key");
  return { ...(trimmedGeminiKey ? { geminiApiKey: trimmedGeminiKey } : {}), jwtSigningKey: trimmedJwtKey };
}
