import {
  SYSTEM_PROMPT,
  actionInstructionsFor,
  functionDeclarationsFor,
  functionNamesFor
} from "../core/action-schema";
import { AssistantProviderError, type AssistantProvider, type AssistantProviderResult,
  type CommandInput, type FunctionSelection } from "../core/contracts";
import type { FetchLike } from "./gemini";

export interface GroqProviderConfig {
  readonly apiKey: string;
  readonly transcriptionModel: string;
  readonly actionModel: string;
  readonly timeoutMs: number;
  readonly maxCompletionTokens: number;
}

export interface GroqRequestRuntime {
  now(): number;
  wait(milliseconds: number): Promise<void>;
  random(): number;
}

const DEFAULT_RUNTIME: GroqRequestRuntime = {
  now: () => performance.now(),
  wait: (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  random: () => Math.random()
};
const API = "https://api.groq.com/openai/v1";
const MAX_TRANSCRIPT_LENGTH = 500;
const MIN_RETRY_BUDGET_MS = 250;
type Stage = "transcription" | "classification";
interface RequestBudget { readonly startedAt: number; readonly deadline: number; retries: number }

/** One transcription and one forced tool call, sharing a deadline and one retry allowance. */
export class GroqProvider implements AssistantProvider {
  constructor(
    private readonly config: GroqProviderConfig,
    private readonly fetcher: FetchLike = fetch,
    private readonly runtime: GroqRequestRuntime = DEFAULT_RUNTIME
  ) {}

  async select(input: CommandInput, now: string): Promise<AssistantProviderResult> {
    const startedAt = this.runtime.now();
    const budget: RequestBudget = { startedAt, deadline: startedAt + this.config.timeoutMs, retries: 1 };
    const stages: Record<string, number> = {};
    let text = input.kind === "text" ? input.text.trim() : "";
    if (input.kind === "audio") {
      const started = this.runtime.now();
      const form = new FormData();
      form.append("file", new Blob([Buffer.from(input.bytes)], { type: input.mimeType }),
        input.mimeType === "audio/mp4" ? "command.m4a" : "command.wav");
      form.append("model", this.config.transcriptionModel);
      form.append("language", "en");
      form.append("temperature", "0");
      form.append("response_format", "verbose_json");
      const value = await this.request("transcription", () => ({ body: form }), budget);
      stages.transcription = this.runtime.now() - started;
      text = this.readTranscript(value, budget);
      if (!text) return { selection: { name: "unclear_command", args: {} }, durationMs: this.elapsed(budget), timingMetric: "groq", stages };
    }
    if (!text || text.length > MAX_TRANSCRIPT_LENGTH) {
      return { selection: { name: "unclear_command", args: {} }, durationMs: this.elapsed(budget), timingMetric: "groq", stages };
    }
    const started = this.runtime.now();
    const actionVersion = input.actionVersion ?? 1;
    const tools = functionDeclarationsFor(actionVersion);
    const actionInstructions = actionInstructionsFor(actionVersion);
    const value = await this.request("classification", (repair) => ({
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        model: this.config.actionModel,
        messages: [
          { role: "system", content: SYSTEM_PROMPT + actionInstructions + " Return one tool call with a valid JSON object. Omit optional fields that were not spoken. Do not output prose." },
          { role: "user", content: `Current phone time: ${now}\nCommand: ${text}` }
        ],
        tools: tools.map((declaration) => ({ type: "function", function: {
          ...declaration,
          parameters: { ...declaration.parameters, additionalProperties: false }
        } })),
        tool_choice: "required",
        parallel_tool_calls: false,
        temperature: 0,
        max_completion_tokens: repair ? Math.min(2_048, this.config.maxCompletionTokens * 2) : this.config.maxCompletionTokens,
        ...(this.config.actionModel.startsWith("openai/gpt-oss-") ? { reasoning_effort: "low", include_reasoning: false } : {})
      })
    }), budget);
    const selection = readToolCall(value, functionNamesFor(actionVersion));
    if (!selection) throw this.failure(budget, "classification", "invalid_response");
    stages.classification = this.runtime.now() - started;
    const exact = actionVersion >= 2 ? exactDeviceRequest(text, actionVersion >= 5) : null;
    return { selection: exact ?? selection, durationMs: this.elapsed(budget), timingMetric: "groq", stages };
  }

  private readTranscript(value: unknown, budget: RequestBudget): string {
    if (!isRecord(value) || typeof value.text !== "string") throw this.failure(budget, "transcription", "invalid_response");
    const text = value.text.trim();
    if (!text || text.length > MAX_TRANSCRIPT_LENGTH) return "";
    // Require the Whisper metadata requested above; uncertain/non-speech audio cannot become a phone action.
    if (!Array.isArray(value.segments) || value.segments.length === 0) return "";
    for (const segment of value.segments) {
      if (!isRecord(segment) || !finite(segment.avg_logprob) || !finite(segment.no_speech_prob)) return "";
      if (segment.no_speech_prob < 0 || segment.no_speech_prob > 1 || segment.avg_logprob < -1 || segment.no_speech_prob >= 0.6) return "";
      if (segment.compression_ratio !== undefined && (!finite(segment.compression_ratio) || segment.compression_ratio > 2.4)) return "";
    }
    return text;
  }

  private async request(stage: Stage, build: (repair: boolean) => Pick<RequestInit, "headers" | "body">,
    budget: RequestBudget): Promise<unknown> {
    let repair = false;
    while (true) {
      const remaining = budget.deadline - this.runtime.now();
      if (remaining < 100) throw this.failure(budget, stage, "timeout");
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), remaining);
      let retryDelay: number | undefined;
      try {
        const init = build(repair);
        const response = await this.fetcher(`${API}/${stage === "transcription" ? "audio/transcriptions" : "chat/completions"}`, {
          method: "POST", redirect: "error", ...init,
          headers: { ...init.headers, authorization: `Bearer ${this.config.apiKey}` },
          signal: controller.signal
        });
        if (response.ok) {
          const value = await readJson(response, 65_536);
          if (this.runtime.now() >= budget.deadline) throw this.failure(budget, stage, "timeout");
          return value;
        }
        const status = response.status;
        const retryAfter = retryAfterMs(response.headers.get("retry-after"));
        // Only this allowlisted provider code can trigger a repaired tool-call request.
        const errorBody = await readJson(response, 4_096).catch(() => null);
        if (controller.signal.aborted || this.runtime.now() >= budget.deadline) throw this.failure(budget, stage, "timeout");
        const toolFailure = stage === "classification" && status === 400 && isRecord(errorBody) &&
          isRecord(errorBody.error) && errorBody.error.code === "tool_use_failed";
        const transient = status === 408 || status === 500 || status === 502 || status === 503 || status === 504;
        const shortRateLimit = status === 429 && retryAfter !== null && retryAfter <= 1_000;
        const delay = Math.max(retryAfter ?? 0, 200 + Math.floor(this.runtime.random() * 100));
        if (budget.retries > 0 && (toolFailure || transient || shortRateLimit) &&
          this.runtime.now() + delay + MIN_RETRY_BUDGET_MS < budget.deadline) {
          repair = toolFailure;
          retryDelay = delay;
        } else {
          throw this.failure(budget, stage, toolFailure ? "tool_use_failed" : `http_${status}`, status,
            status === 429 ? Math.max(1, Math.ceil((retryAfter ?? 60_000) / 1_000)) : undefined);
        }
      } catch (error) {
        if (error instanceof AssistantProviderError) throw error;
        if (controller.signal.aborted || isAbort(error) || this.runtime.now() >= budget.deadline) {
          throw this.failure(budget, stage, "timeout");
        }
        if (error instanceof SyntaxError || (error instanceof Error && error.message === "provider_response_too_large")) {
          throw this.failure(budget, stage, "invalid_response");
        }
        const delay = 200 + Math.floor(this.runtime.random() * 100);
        if (budget.retries > 0 && this.runtime.now() + delay + MIN_RETRY_BUDGET_MS < budget.deadline) retryDelay = delay;
        else throw this.failure(budget, stage, "network_error");
      } finally {
        clearTimeout(timer);
      }
      if (retryDelay === undefined) throw this.failure(budget, stage, "invalid_response");
      budget.retries--;
      await this.runtime.wait(retryDelay);
    }
  }

  private elapsed(budget: RequestBudget): number { return this.runtime.now() - budget.startedAt; }

  private failure(budget: RequestBudget, stage: Stage, reason: string, status?: number,
    retryAfterSeconds?: number): AssistantProviderError {
    return new AssistantProviderError(reason === "timeout" || status === 408 || status === 504 ? "timeout" : "busy",
      this.elapsed(budget), { diagnosticCode: `groq_${stage}_${reason}`, timingMetric: "groq",
        ...(status === undefined ? {} : { httpStatus: status }),
        ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }) });
  }
}

// Pin common, explicit commands after the transcript check. Never match part of a compound or negated request.
function exactDeviceRequest(text: string, supportsMediaControls: boolean): FunctionSelection | null {
  const words = text.toLowerCase().replace(/[.!?]+$/, "").replace(/\s+/g, " ").trim()
    .replace(/^(?:(?:can|could|would) you )?(?:please )?/, "").replace(/,? please$/, "");
  if (supportsMediaControls) {
    const operation = /^(pause|resume) (?:(?:the|my) )?(?:music|playback|audio|song)$/.exec(words)
      ?? /^(?:skip to |go to )?(?:the )?(next|previous) (?:song|track)$/.exec(words);
    if (operation) return { name: "control_media", args: { operation: operation[1] } };
  }
  const torch = /^(?:turn|switch) (on|off) (?:(?:the|my) )?(?:flashlight|flash light|torch)$/.exec(words)
    ?? /^(?:turn|switch|put) (?:(?:the|my) )?(?:flashlight|flash light|torch) (on|off)$/.exec(words);
  if (torch) return { name: "flashlight", args: { on: torch[1] === "on" } };
  if (/^(?:play (?:music|a music|some music|a song)|open (?:the |a |my )?music (?:app|player))$/.test(words)) {
    return { name: "open_music", args: {} };
  }
  const musicApp = /^(?:play (?:music|a music|some music|a song) (?:in|on|using)|open) (spotify|youtube(?: music)?)$/.exec(words);
  if (musicApp) return { name: "open_app", args: { name: musicApp[1] } };
  return null;
}

function readToolCall(value: unknown, allowed: readonly string[]): FunctionSelection | null {
  if (!isRecord(value) || !Array.isArray(value.choices) || value.choices.length !== 1) return null;
  const choice = value.choices[0];
  if (!isRecord(choice) || choice.finish_reason === "length" || !isRecord(choice.message) ||
    !Array.isArray(choice.message.tool_calls) || choice.message.tool_calls.length !== 1) return null;
  const call = choice.message.tool_calls[0];
  if (!isRecord(call) || (call.type !== undefined && call.type !== "function") || !isRecord(call.function)) return null;
  const name = call.function.name;
  const args = call.function.arguments;
  if (typeof name !== "string" || !allowed.includes(name) || typeof args !== "string") return null;
  try {
    const parsed: unknown = JSON.parse(args);
    if (!isRecord(parsed)) return null;
    // A blank optional name/label conveys no target; required args remain strict in the action validator.
    const optional = name === "close_app" ? "name" : name === "set_alarm" ? "label" : null;
    if (optional && typeof parsed[optional] === "string" && parsed[optional].trim() === "") delete parsed[optional];
    return { name, args: parsed };
  } catch { return null; }
}

async function readJson(response: Response, limit: number): Promise<unknown> {
  const reader = response.body?.getReader();
  if (!reader) throw new SyntaxError("missing_provider_response");
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      bytes += chunk.value.byteLength;
      if (bytes > limit) throw new Error("provider_response_too_large");
      chunks.push(chunk.value);
    }
    return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

function retryAfterMs(value: string | null): number | null {
  if (!value?.trim()) return null;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return seconds >= 0 ? Math.min(86_400_000, seconds * 1_000) : null;
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.min(86_400_000, Math.max(0, date - Date.now())) : null;
}
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function finite(value: unknown): value is number { return typeof value === "number" && Number.isFinite(value); }
function isAbort(value: unknown): boolean { return value instanceof Error && value.name === "AbortError"; }
