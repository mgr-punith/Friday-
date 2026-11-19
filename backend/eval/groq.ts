import { FUNCTION_DECLARATIONS, SYSTEM_PROMPT } from "../src/core/action-schema";
import type { CommandInput, FunctionSelection } from "../src/core/contracts";
import type { EvaluationProvider, EvaluationProviderResult } from "./provider";

export interface GroqEvaluationConfig {
  readonly apiKey: string;
  readonly transcriptionModel: string;
  readonly actionModel: string;
  readonly timeoutMs: number;
}

export type EvaluationFetch = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

export class GroqEvaluationProvider implements EvaluationProvider {
  readonly name = "groq-two-step";
  readonly models: readonly string[];

  constructor(
    private readonly config: GroqEvaluationConfig,
    private readonly fetcher: EvaluationFetch = fetch
  ) {
    this.models = [config.transcriptionModel, config.actionModel];
  }

  async select(input: CommandInput, now: string): Promise<EvaluationProviderResult> {
    const startedAt = performance.now();
    const deadline = startedAt + this.config.timeoutMs;
    const stages: Record<string, number> = {};
    let text: string;

    if (input.kind === "audio") {
      const transcriptionStartedAt = performance.now();
      text = await this.transcribe(input, deadline);
      stages.transcription = performance.now() - transcriptionStartedAt;
      if (!text) {
        return {
          selection: { name: "unclear_command", args: {} },
          durationMs: performance.now() - startedAt,
          stages
        };
      }
    } else {
      text = input.text;
    }

    const classificationStartedAt = performance.now();
    const selection = await this.classify(text, now, deadline);
    stages.classification = performance.now() - classificationStartedAt;

    return {
      selection,
      durationMs: performance.now() - startedAt,
      stages
    };
  }

  private async transcribe(
    input: Extract<CommandInput, { readonly kind: "audio" }>,
    deadline: number
  ): Promise<string> {
    const form = new FormData();
    const filename = input.mimeType === "audio/mp4" ? "command.m4a" : "command.wav";
    form.append("file", new Blob([Buffer.from(input.bytes)], { type: input.mimeType }), filename);
    form.append("model", this.config.transcriptionModel);
    form.append("language", "en");
    form.append("temperature", "0");
    form.append("response_format", "json");
    const value = await this.fetchJson(
      "https://api.groq.com/openai/v1/audio/transcriptions",
      { method: "POST", headers: this.headers(false), body: form },
      deadline,
      "groq_transcription"
    );
    if (!isRecord(value) || typeof value.text !== "string") {
      throw new Error("groq_transcription_invalid_response");
    }
    return value.text.trim();
  }

  private async classify(text: string, now: string, deadline: number): Promise<FunctionSelection> {
    const value = await this.fetchJson(
      "https://api.groq.com/openai/v1/chat/completions",
      {
        method: "POST",
        headers: this.headers(true),
        body: JSON.stringify({
          model: this.config.actionModel,
          messages: [
            { role: "system", content: SYSTEM_PROMPT },
            { role: "user", content: `Current phone time: ${now}\nCommand: ${text}` }
          ],
          tools: FUNCTION_DECLARATIONS.map((declaration) => ({
            type: "function",
            function: declaration
          })),
          tool_choice: "required",
          parallel_tool_calls: false,
          temperature: 0,
          // GPT OSS shares this allowance between reasoning and the function call.
          max_completion_tokens: this.config.actionModel.startsWith("openai/gpt-oss-") ? 512 : 100,
          ...(this.config.actionModel.startsWith("openai/gpt-oss-") ? { reasoning_effort: "low" } : {})
        })
      },
      deadline,
      "groq_classification"
    );
    const selection = readToolCall(value);
    if (!selection) throw new Error("groq_classification_invalid_response");
    return selection;
  }

  private headers(json: boolean): Record<string, string> {
    return json
      ? { authorization: `Bearer ${this.config.apiKey}`, "content-type": "application/json" }
      : { authorization: `Bearer ${this.config.apiKey}` };
  }

  private async fetchJson(
    url: string,
    init: RequestInit,
    deadline: number,
    stage: string
  ): Promise<unknown> {
    const remainingMs = deadline - performance.now();
    if (remainingMs < 100) throw new Error(`${stage}_timeout`);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), remainingMs);
    try {
      const response = await this.fetcher(url, { ...init, signal: controller.signal });
      if (!response.ok) throw new Error(`${stage}_http_${response.status}`);
      return await response.json() as unknown;
    } catch (error) {
      if (isAbortError(error) || performance.now() >= deadline) throw new Error(`${stage}_timeout`);
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }
}

function readToolCall(value: unknown): FunctionSelection | null {
  if (!isRecord(value) || !Array.isArray(value.choices) || value.choices.length !== 1) return null;
  const choice = value.choices[0];
  if (!isRecord(choice) || !isRecord(choice.message) || !Array.isArray(choice.message.tool_calls)) return null;
  if (choice.message.tool_calls.length !== 1) return null;
  const toolCall = choice.message.tool_calls[0];
  if (!isRecord(toolCall) || !isRecord(toolCall.function)) return null;
  const name = toolCall.function.name;
  const argumentsValue = toolCall.function.arguments;
  if (typeof name !== "string" || typeof argumentsValue !== "string") return null;
  try {
    return { name, args: JSON.parse(argumentsValue) as unknown };
  } catch {
    return null;
  }
}

function isAbortError(value: unknown): boolean {
  return value instanceof Error && value.name === "AbortError";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
