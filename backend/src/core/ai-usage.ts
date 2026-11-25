import type { AssistantProviderResult } from "./contracts";

/** Only numeric provider counters reach logs; never forward arbitrary usage fields. */
export function safeAiUsage(value: AssistantProviderResult["usage"]): NonNullable<AssistantProviderResult["usage"]> {
  const result: Record<string, { inputTokens: number; outputTokens: number; cachedTokens: number }> = {};
  const count = (value: unknown) => typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : 0;
  for (const stage of ["transcription", "classification", "answer"]) {
    const usage = value?.[stage];
    if (usage) result[stage] = { inputTokens: count(usage.inputTokens), outputTokens: count(usage.outputTokens),
      cachedTokens: Math.min(count(usage.cachedTokens), count(usage.inputTokens)) };
  }
  return result;
}
