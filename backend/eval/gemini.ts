import {
  AssistantProviderError,
  type CommandInput,
  type SecretProvider
} from "../src/core/contracts";
import { GeminiProvider, type GeminiProviderConfig } from "../src/providers/gemini";
import type { EvaluationProvider, EvaluationProviderResult } from "./provider";

export class GeminiEvaluationProvider implements EvaluationProvider {
  readonly name = "gemini-direct-audio";
  readonly models: readonly string[];
  private readonly provider: GeminiProvider;
  private lastHttpStatus: number | null = null;

  constructor(config: GeminiProviderConfig, secrets: SecretProvider) {
    this.models = [config.model];
    this.provider = new GeminiProvider(config, secrets, async (input, init) => {
      const response = await fetch(input, init);
      this.lastHttpStatus = response.ok ? null : response.status;
      return response;
    });
  }

  async select(input: CommandInput, now: string): Promise<EvaluationProviderResult> {
    this.lastHttpStatus = null;
    try {
      const result = await this.provider.select(input, now);
      return { ...result, stages: { model: result.durationMs } };
    } catch (error) {
      if (error instanceof AssistantProviderError && error.code === "busy" && this.lastHttpStatus !== null) {
        throw new Error(`gemini_http_${this.lastHttpStatus}`);
      }
      throw error;
    }
  }
}
