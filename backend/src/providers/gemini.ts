import {
  SYSTEM_PROMPT,
  actionInstructionsFor,
  functionDeclarationsFor,
  functionNamesFor
} from "../core/action-schema";
import {
  AssistantProviderError,
  type AssistantProvider,
  type AssistantProviderResult,
  type CommandInput,
  type SecretProvider
} from "../core/contracts";

export interface GeminiProviderConfig {
  readonly model: string;
  readonly thinkingLevel: "MINIMAL" | "LOW" | "MEDIUM" | "HIGH";
  readonly timeoutMs: number;
}

export type FetchLike = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

export class GeminiProvider implements AssistantProvider {
  constructor(
    private readonly config: GeminiProviderConfig,
    private readonly secrets: SecretProvider,
    private readonly fetcher: FetchLike = fetch
  ) {}

  async select(input: CommandInput, now: string): Promise<AssistantProviderResult> {
    const startedAt = performance.now();
    const deadline = startedAt + this.config.timeoutMs;
    const { geminiApiKey } = await this.secrets.getSecrets();
    if (!geminiApiKey) throw new AssistantProviderError("busy", performance.now() - startedAt, {
      diagnosticCode: "gemini_configuration_missing_key", timingMetric: "gemini"
    });
    const endpoint = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(this.config.model)}:generateContent`;
    const body = JSON.stringify(buildRequest(input, now, this.config.thinkingLevel));

    for (let attempt = 0; attempt < 2; attempt += 1) {
      const remainingMs = deadline - performance.now();
      if (remainingMs < 100) throw timeout(startedAt);

      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), remainingMs);
      try {
        const response = await this.fetcher(endpoint, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-goog-api-key": geminiApiKey
          },
          body,
          signal: controller.signal
        });

        if (response.ok) {
          const value: unknown = await response.json();
          return {
            selection: readFunctionCall(value) ?? { name: "unsupported", args: {} },
            durationMs: performance.now() - startedAt
          };
        }

        if (response.status >= 500 && attempt === 0 && deadline - performance.now() >= 250) {
          continue;
        }
        if (response.status === 408 || response.status === 504) throw timeout(startedAt);
        throw busy(startedAt);
      } catch (error) {
        if (error instanceof AssistantProviderError) throw error;
        if (isAbortError(error) || performance.now() >= deadline) throw timeout(startedAt);
        if (attempt === 0 && deadline - performance.now() >= 250) continue;
        throw busy(startedAt);
      } finally {
        clearTimeout(timer);
      }
    }
    throw busy(startedAt);
  }
}

function buildRequest(
  input: CommandInput,
  now: string,
  thinkingLevel: GeminiProviderConfig["thinkingLevel"]
): Record<string, unknown> {
  const parts: Array<Record<string, unknown>> = [
    { text: `Current phone time: ${now}` },
    input.kind === "text"
      ? { text: input.text }
      : { inlineData: { mimeType: input.mimeType, data: Buffer.from(input.bytes).toString("base64") } }
  ];
  const actionVersion = input.actionVersion ?? 1;
  const declarations = functionDeclarationsFor(actionVersion);

  return {
    systemInstruction: { parts: [{ text: SYSTEM_PROMPT + actionInstructionsFor(actionVersion) }] },
    contents: [{ role: "user", parts }],
    tools: [{ functionDeclarations: declarations }],
    toolConfig: {
      functionCallingConfig: {
        mode: "ANY",
        allowedFunctionNames: functionNamesFor(actionVersion)
      }
    },
    generationConfig: {
      temperature: 0,
      maxOutputTokens: 100,
      thinkingConfig: { thinkingLevel }
    }
  };
}

function readFunctionCall(value: unknown): { name: string; args: unknown } | null {
  if (!isRecord(value) || !Array.isArray(value.candidates)) return null;
  const calls: Array<{ name: string; args: unknown }> = [];
  for (const candidate of value.candidates) {
    if (!isRecord(candidate) || !isRecord(candidate.content) || !Array.isArray(candidate.content.parts)) {
      continue;
    }
    for (const part of candidate.content.parts) {
      if (!isRecord(part) || !isRecord(part.functionCall)) continue;
      const name = part.functionCall.name;
      if (typeof name === "string") calls.push({ name, args: part.functionCall.args ?? {} });
    }
  }
  return calls.length === 1 ? calls[0] ?? null : null;
}

function timeout(startedAt: number): AssistantProviderError {
  return new AssistantProviderError("timeout", performance.now() - startedAt);
}

function busy(startedAt: number): AssistantProviderError {
  return new AssistantProviderError("busy", performance.now() - startedAt);
}

function isAbortError(value: unknown): boolean {
  return value instanceof Error && value.name === "AbortError";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
