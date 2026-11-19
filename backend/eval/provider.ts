import type { AssistantProviderResult, CommandInput } from "../src/core/contracts";

export interface EvaluationProviderResult extends AssistantProviderResult {
  readonly stages: Readonly<Record<string, number>>;
}

export interface EvaluationProvider {
  readonly name: string;
  readonly models: readonly string[];
  select(input: CommandInput, now: string): Promise<EvaluationProviderResult>;
}
