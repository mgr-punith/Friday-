import {
  TRANSCRIPT_SYSTEM_PROMPT,
  actionInstructionsFor,
  functionDeclarationsFor,
  isMusicItemType
} from "../core/action-schema";
import { AssistantProviderError, type AssistantProvider, type AssistantProviderResult,
  type CommandInput, type FunctionSelection, type SearchAuthorization } from "../core/contracts";
import type { FetchLike } from "./gemini";
import { ANSWER_PROMPT, ANSWER_SETUP_TOOL, ANSWER_TOOL, localClockAnswer, localWeatherClarification,
  needsCurrentInformation, readBrowserAnswer, readKnowledgeAnswer } from "../core/answers";
import { ParakeetTranscriber, type ParakeetConfig } from "./parakeet";
import { TranscriptionError, type AudioInput, type AudioTranscriber } from "./transcription";
import { turnsOffQuietMode } from "../core/ringer-command";

export interface GroqProviderConfig {
  readonly apiKey: string;
  readonly transcriptionModel: string;
  readonly actionModel: string;
  readonly timeoutMs: number;
  readonly maxCompletionTokens: number;
  readonly answersEnabled?: boolean;
  readonly answerTimeoutMs?: number;
  readonly transcription?: ParakeetConfig & { readonly provider: "nvidia-parakeet" };
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
const ACTION_COMPLETION_TOKEN_CAP = 256;
const REPAIR_COMPLETION_TOKEN_CAP = 384;
const SEARCH_COMPLETION_TOKEN_CAP = 512;
type Stage = "transcription" | "classification" | "answer";
interface RequestBudget { readonly startedAt: number; deadline: number; retries: number;
  readonly usage: Record<string, { inputTokens: number; outputTokens: number; cachedTokens: number }> }

/** Skip selection for exact safe commands; otherwise share one retry across recognition and answering. */
export class GroqProvider implements AssistantProvider {
  private readonly transcriber: AudioTranscriber | undefined;
  constructor(
    private readonly config: GroqProviderConfig,
    private readonly fetcher: FetchLike = fetch,
    private readonly runtime: GroqRequestRuntime = DEFAULT_RUNTIME,
    transcriber?: AudioTranscriber
  ) {
    this.transcriber = transcriber ?? (config.transcription ? new ParakeetTranscriber(config.transcription) : undefined);
  }

  async select(input: CommandInput, now: string, context?: SearchAuthorization): Promise<AssistantProviderResult> {
    const startedAt = this.runtime.now();
    const budget: RequestBudget = { startedAt, deadline: startedAt + this.config.timeoutMs, retries: 1, usage: {} };
    const stages: Record<string, number> = {};
    let text = input.kind === "text" ? input.text.trim() : "";
    if (input.kind === "audio") {
      const started = this.runtime.now();
      if (this.transcriber) text = await this.transcribe(input, budget);
      else {
        const form = new FormData();
        form.append("file", new Blob([Buffer.from(input.bytes)], { type: input.mimeType }),
          input.mimeType === "audio/mp4" ? "command.m4a" : "command.wav");
        form.append("model", this.config.transcriptionModel);
        form.append("language", "en");
        form.append("temperature", "0");
        form.append("response_format", "verbose_json");
        const value = await this.request("transcription", () => ({ body: form }), budget);
        text = this.readTranscript(value, budget);
      }
      stages.transcription = this.runtime.now() - started;
      if (!text) return { selection: { name: "unclear_command", args: {} }, durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
    }
    if (!text || text.length > MAX_TRANSCRIPT_LENGTH) {
      return { selection: { name: "unclear_command", args: {} }, durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
    }
    const actionVersion = input.actionVersion ?? 1;
    const supportsAnswers = input.supportsAnswers === true && this.config.answersEnabled === true && context !== undefined;
    const answerSetup = !supportsAnswers && input.answerSetup !== undefined;
    const following = input.answerContext;
    if (following && !supportsAnswers) throw this.failure(budget, "classification", "invalid_context");
    // Clarification replies are answer-only, including replies that look like exact phone commands.
    const exact = !following && actionVersion >= 2 ? exactDeviceRequest(text, actionVersion, input.musicPlayer) : null;
    const needsMusicType = exact?.name === "play_media" && input.musicPlayer && isRecord(exact.args) && exact.args.mediaType === undefined;
    if (exact && !needsMusicType) return { selection: exact, durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
    const started = this.runtime.now();
    const answerTool = following?.remaining === 0 ? { ...ANSWER_TOOL, parameters: { ...ANSWER_TOOL.parameters,
      properties: { ...ANSWER_TOOL.parameters.properties, mode: { type: "string", enum: ["knowledge", "lookup", "unavailable"] } } } } : ANSWER_TOOL;
    const tools = following ? [answerTool] : [...compactGroqTools(actionVersion, input.musicPlayer).map(tool => (supportsAnswers || answerSetup) && tool.name === "unsupported"
      ? { ...tool, description: "Use for chat, essays, non-English speech or unsupported phone controls." } : tool),
      ...(supportsAnswers ? [answerTool] : answerSetup ? [ANSWER_SETUP_TOOL] : [])];
    const answerRequest = supportsAnswers && !following && isInformationRequest(text);
    const answerSetupRequest = answerSetup && isInformationRequest(text);
    const requestTools = following ? tools : answerRequest ? [answerTool] : answerSetupRequest
      ? [ANSWER_SETUP_TOOL] : focusedGroqTools(text, tools);
    const focused = requestTools.length < tools.length;
    const actionInstructions = answerRequest || answerSetupRequest || following ? "" : focused
      ? " Handle one clear request. Use the matching action only; use unsupported if it does not match. Never guess a target." +
        (requestTools.some(tool => tool.name === "play_media")
          ? " Keep named song and artist words exactly in the play_media query; include a player only when spoken."
          : "")
      : actionInstructionsFor(actionVersion);
    const question = following ? JSON.stringify({ originalQuestion: following.question, earlierReplies: following.turns,
      clarification: following.prompt, reply: text }) : text;
    const localClock = !following && supportsAnswers ? localClockAnswer(text, now) : null;
    const weatherClarification = !following && supportsAnswers ? localWeatherClarification(text) : null;
    const instructions = following
      ? "You give brief factual answers for an English voice assistant. Use the original question, earlier replies and latest reply together. " +
        "Do not ask again for details already provided. Only answer questions; phone commands, chat or non-English replies use unavailable. " +
        (following.remaining === 0 ? "No clarification questions remain. If essential detail is still missing, use unavailable and ask the user to start a new question with more detail. "
          : "If essential detail is still missing, ask one short clarification before searching. ")
      : (supportsAnswers ? TRANSCRIPT_SYSTEM_PROMPT.replace("Never answer questions.", "Use answer_question for factual questions. " +
          "Answer clear questions directly. You cannot see the screen or earlier conversations. " +
          "If the person, match, place or other essential subject is missing, use clarification with one short question. " +
          "Do not guess the missing subject or answer with a request for detail in knowledge mode.") : answerSetup
          ? TRANSCRIPT_SYSTEM_PROMPT.replace("Never answer questions.", "For factual questions use short_answers_required. Do not answer or search.")
          : TRANSCRIPT_SYSTEM_PROMPT) + actionInstructions;
    const value = await this.request("classification", (repair) => ({
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        model: this.config.actionModel,
        messages: [
          { role: "system", content: instructions + (supportsAnswers && !following
            ? " For intelligible factual questions, including incomplete questions about a person or match, choose answer_question. " +
              "Missing essential details use answer_question in clarification mode with one short question." : "") +
            (supportsAnswers ? " The answer modes are argument values, never function names." : "") +
            (answerSetup ? " For an English factual question choose short_answers_required with no arguments. " +
              "Use unsupported for unsupported phone controls, chat, essays or non-English speech." : "") +
            " Return one tool call. Omit optional fields that were not spoken. Do not output prose." +
            (repair ? ` The previous tool call was invalid. Choose one declared function name: ${requestTools.map(tool => tool.name).join(", ")}. ` +
              "Do not invent a function. Put the selected fields in that function's arguments." : "") },
          { role: "user", content: `Current phone time: ${now}\n${following ? "Question context" : "Command"}: ${question}` }
        ],
        tools: requestTools.map((declaration) => ({ type: "function", function: {
          ...declaration,
          parameters: { ...declaration.parameters, additionalProperties: false }
        } })),
        tool_choice: following || answerRequest
          ? { type: "function", function: { name: ANSWER_TOOL.name } }
          : answerSetupRequest
            ? { type: "function", function: { name: ANSWER_SETUP_TOOL.name } }
            : "required",
        parallel_tool_calls: false,
        temperature: 0,
        max_completion_tokens: repair
          ? Math.min(REPAIR_COMPLETION_TOKEN_CAP, Math.ceil(Math.min(ACTION_COMPLETION_TOKEN_CAP, this.config.maxCompletionTokens) * 1.5))
          : Math.min(ACTION_COMPLETION_TOKEN_CAP, this.config.maxCompletionTokens),
        ...(this.config.actionModel.startsWith("openai/gpt-oss-") ? { reasoning_effort: "low", include_reasoning: false } : {})
      })
    }), budget);
    let selection = readToolCall(value, requestTools.map(tool => tool.name));
    if (needsMusicType && exact && isRecord(exact.args)) {
      const hinted = selection?.name === "play_media" && isRecord(selection.args) ? selection.args : undefined;
      const sameTitle = typeof hinted?.query === "string" && typeof exact.args.query === "string" &&
        hinted.query.trim().toLowerCase() === exact.args.query.toLowerCase();
      selection = { ...exact, args: { ...exact.args, ...(sameTitle && isMusicItemType(hinted?.mediaType) ? { mediaType: hinted.mediaType } : {}) } };
    }
    if (!selection) throw this.failure(budget, "classification", "invalid_response");
    stages.classification = this.runtime.now() - started;
    if (selection.name === ANSWER_TOOL.name) {
      if (localClock) return { selection, answer: localClock, question: text,
        durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
      if (weatherClarification) return { selection, answer: weatherClarification, question: text,
        durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
      let directAnswer = readKnowledgeAnswer(selection.args);
      if (directAnswer?.kind === "clarification" && following?.remaining === 0) {
        directAnswer = { kind: "unavailable", text: "I couldn't narrow that down. Please ask the full question with more detail.", sources: [] };
      }
      const forceLookup = directAnswer?.kind === "knowledge" && needsCurrentInformation(question, directAnswer.text, now);
      if (directAnswer && !forceLookup) return { selection, answer: directAnswer, question: text,
        durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
      // Lookup text is never trusted as a rewritten query; always search the original request.
      if (!forceLookup && (!isRecord(selection.args) || selection.args.mode !== "lookup" ||
        (selection.args.text !== undefined && (typeof selection.args.text !== "string" || selection.args.text.length > 600)) ||
        Object.keys(selection.args).some(key => !["mode", "text"].includes(key)))) throw this.failure(budget, "answer", "invalid_response");
      if (!context) throw this.failure(budget, "answer", "invalid_context");
      const quota = await context.authorizeAnswer().catch(() => {
        throw this.failure(budget, "answer", "quota_store_failed");
      });
      if (!quota.allowed) {
        throw this.failure(budget, "answer", `quota_${quota.scope}`, 429, quota.retryAfterSeconds);
      }
      budget.deadline = startedAt + (this.config.answerTimeoutMs ?? 18_000);
      const answerStarted = this.runtime.now();
      const raw = await this.request("answer", () => ({
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ model: this.config.actionModel,
          messages: [{ role: "system", content: ANSWER_PROMPT },
            { role: "user", content: `Current phone time: ${now}\nQuestion: ${question}` }],
          tools: [{ type: "browser_search" }], tool_choice: "required", temperature: 0,
          max_completion_tokens: SEARCH_COMPLETION_TOKEN_CAP, reasoning_effort: "low", include_reasoning: false })
      }), budget);
      let answer = readBrowserAnswer(raw);
      if (!answer) throw this.failure(budget, "answer", "invalid_response");
      if (answer.kind === "clarification") answer = { kind: "unavailable",
        text: "I couldn't verify that. Please ask a new question with more detail.", sources: [] };
      stages.answer = this.runtime.now() - answerStarted;
      return { selection, answer, durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
    }
    return { selection, durationMs: this.elapsed(budget), timingMetric: "groq", stages, usage: budget.usage };
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

  private async transcribe(input: AudioInput, budget: RequestBudget): Promise<string> {
    while (true) {
      const remaining = budget.deadline - this.runtime.now();
      if (remaining < 100) throw this.transcriptionFailure(budget, "timeout");
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), remaining);
      let delay: number | undefined;
      try {
        const text = await this.transcriber!.transcribe(input, { signal: controller.signal, timeoutMs: remaining });
        if (controller.signal.aborted || this.runtime.now() >= budget.deadline) throw this.transcriptionFailure(budget, "timeout");
        return text.trim();
      } catch (error) {
        if (error instanceof AssistantProviderError) throw error;
        if (controller.signal.aborted || error instanceof TranscriptionError && error.reason === "timeout") {
          throw this.transcriptionFailure(budget, "timeout");
        }
        const failure = error instanceof TranscriptionError ? error : new TranscriptionError("network_error", true);
        const wait = Math.max(failure.retryAfterMs ?? 0, 200 + Math.floor(this.runtime.random() * 100));
        if (failure.retryable && budget.retries > 0 && this.runtime.now() + wait + MIN_RETRY_BUDGET_MS < budget.deadline) delay = wait;
        else throw this.transcriptionFailure(budget, failure.reason);
      } finally { clearTimeout(timer); }
      budget.retries--;
      await this.runtime.wait(delay!);
    }
  }

  private transcriptionFailure(budget: RequestBudget, reason: TranscriptionError["reason"]): AssistantProviderError {
    return new AssistantProviderError(reason === "timeout" ? "timeout" : "busy", this.elapsed(budget), {
      diagnosticCode: `nvidia_transcription_${reason}`, timingMetric: "groq", usage: budget.usage,
      ...(reason === "rate_limited" ? { httpStatus: 429, retryAfterSeconds: 60 } : {})
    });
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
          const value = await readJson(response, stage === "answer" ? 262_144 : 65_536);
          if (isRecord(value) && isRecord(value.usage)) {
            const count = (v: unknown) => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : 0;
            const previous = budget.usage[stage];
            budget.usage[stage] = { inputTokens: (previous?.inputTokens ?? 0) + count(value.usage.prompt_tokens),
              outputTokens: (previous?.outputTokens ?? 0) + count(value.usage.completion_tokens),
              cachedTokens: (previous?.cachedTokens ?? 0) + count(isRecord(value.usage.prompt_tokens_details) ? value.usage.prompt_tokens_details.cached_tokens : 0) };
          }
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
        usage: budget.usage,
        ...(status === undefined ? {} : { httpStatus: status }),
        ...(retryAfterSeconds === undefined ? {} : { retryAfterSeconds }) });
  }
}

/** Refer to the existing action tools instead of repeating every schema inside a sequence. */
export function compactGroqTools(version: NonNullable<CommandInput["actionVersion"]>, musicPlayer = false) {
  const declarations = functionDeclarationsFor(version, musicPlayer);
  const actions = declarations.filter(tool => !["run_actions", "unsupported", "unclear_command"].includes(tool.name)).map(tool => tool.name);
  return declarations.map(tool => tool.name === "unclear_command"
    ? { ...tool, description: "Use only when the transcript has no intelligible request. Unfamiliar song and artist names can be valid music requests." }
    : tool.name !== "run_actions" ? tool : {
    name: tool.name, description: "Run 2-3 requested actions in order. Use each action tool's arguments. Only flashlight, volume and ringer may precede another action.",
    parameters: { type: "object", properties: { actions: { type: "array", minItems: 2, maxItems: 3,
      items: { type: "object", properties: { action: { type: "string", enum: actions },
        args: { type: "object", description: "Same arguments as the selected action tool." } },
      required: ["action", "args"], additionalProperties: false } } }, required: ["actions"] }
  });
}

/** Narrow schemas only for an obvious standalone command; uncertain language keeps the complete tool set. */
function focusedGroqTools<T extends { readonly name: string }>(text: string, tools: readonly T[]): readonly T[] {
  if (/[\u0000-\u001f\u007f-\u009f]/.test(text)) return tools;
  const normalized = text.toLowerCase().replace(/[’']/g, "").replace(/[.!?]+$/, "").replace(/\s+/g, " ").trim();
  const command = normalized.replace(/^(?:(?:can|could|would|will) you\s+)?(?:please\s+)?/, "");
  if (/\b(?:and|or|then|also|but|if|unless|before|after|without|except|instead|not|never|dont|cannot|cant|wont)\b/.test(command)) return tools;
  const actionStart = /^(?:call|phone|dial|ring|set|start|open|launch|close|exit|go to|leave|switch|turn|put|change|mute|unmute|play|pause|resume|skip|lower|raise|increase|decrease|reduce|make)\b/.test(command);
  const question = /^(?:who|what|when|where|why|how|which|tell me|explain)\b/.test(command) || /\?$/.test(text.trim());
  const answerNames = new Set(["answer_question", "short_answers_required"]);
  const fallbackNames = new Set(["unsupported", "unclear_command", ...answerNames]);
  const narrow = (names: readonly string[]) => {
    const candidates = new Set([...fallbackNames, ...names]);
    const result = tools.filter(tool => candidates.has(tool.name));
    return result.length > 0 ? result : tools;
  };
  if (question && !actionStart) {
    const allowed = new Set([...fallbackNames].filter(name => tools.some(tool => tool.name === name)));
    return allowed.size > 0 ? tools.filter(tool => allowed.has(tool.name)) : tools;
  }
  if (!actionStart) return tools;

  const opening = /^(?:open|launch|go to)\b/.test(command);
  if (opening) {
    if (/\bairplane mode\b/.test(command)) return narrow(["open_settings"]);
    if (/\b(?:music|song|track|playlist|spotify|youtube)\b/.test(command)) {
      return narrow(["open_music", "open_app", "play_media", "control_media"]);
    }
    if (/\b(?:home screen|home)\b/.test(command)) return narrow(["close_app"]);
    return narrow(["open_app"]);
  }

  const groups: { match: RegExp; names: readonly string[] }[] = [
    { match: /\b(?:call|phone|dial|ring)\b/, names: ["call_contact", "call_number"] },
    { match: /\b(?:alarm|wake me|wake-up)\b/, names: ["set_alarm"] },
    { match: /\b(?:timer|countdown)\b/, names: ["set_timer"] },
    { match: /\b(?:ringer|silent|vibrat\w*|mute|unmute)\b/, names: ["set_ringer_mode"] },
    { match: /\b(?:flashlight|flash light|torch)\b/, names: ["flashlight"] },
    { match: /\b(?:volume|louder|quieter)\b/, names: ["set_volume", "adjust_volume"] },
    { match: /\bairplane mode\b/, names: ["open_settings"] },
    { match: /\b(?:home screen|go home|close|exit)\b/, names: ["close_app"] },
    { match: /\b(?:music|song|track|playlist|spotify|youtube|play|pause|resume|skip|previous)\b/,
      names: ["open_music", "open_app", "play_media", "control_media"] }
  ];
  const matches = groups.filter(group => group.match.test(command));
  if (matches.length !== 1) return tools;
  return narrow(matches[0]!.names);
}

function isInformationRequest(text: string): boolean {
  const normalized = text.toLowerCase().replace(/[’']/g, "").replace(/[.!?]+$/, "").trim();
  const command = normalized.replace(/^(?:(?:can|could|would|will) you\s+)?(?:please\s+)?/, "");
  if (/^(?:call|phone|dial|ring|set|start|open|launch|close|exit|go to|leave|switch|turn|put|change|mute|unmute|play|pause|resume|skip|lower|raise|increase|decrease|reduce|make)\b/.test(command)) {
    return false;
  }
  return /^(?:who|what|when|where|why|how|which)(?:s|re|ve|d|ll)?\b/.test(command) ||
    /^(?:tell me|explain)\b/.test(command) ||
    /\b(?:weather|forecast|temperature|rain|raining|news|latest|time|date|day)\b/.test(command) || text.trim().endsWith("?");
}

// Pin common, explicit commands after the transcript check. Never match part of a compound or negated request.
function exactDeviceRequest(text: string, version: NonNullable<CommandInput["actionVersion"]>, musicPlayer = false): FunctionSelection | null {
  if (/[\u0000-\u001f\u007f-\u009f]/.test(text)) return null;
  if (turnsOffQuietMode(text)) return { name: "set_ringer_mode", args: { mode: "normal", utterance: text } };
  const command = text.replace(/[.!?]+$/, "").replace(/\s+/g, " ").trim()
    .replace(/^(?:(?:can|could|would) you )?(?:please )?/i, "").replace(/,? please$/i, "");
  const words = command.toLowerCase();
  if (version >= 5) {
    const operation = /^(pause|resume) (?:(?:the|my) )?(?:music|playback|audio|song)$/.exec(words)
      ?? /^(?:skip to |go to )?(?:the )?(next|previous) (?:song|track)$/.exec(words);
    if (operation) return { name: "control_media", args: { operation: operation[1] } };
  }
  if (version >= 6) {
    const adjustment = /^(raise|increase|lower|decrease|reduce) (?:(?:the|my) )?(?:(media|ring|ringer|alarm) )?volume(?: by (\d{1,3})\s*(?:%|percent|per cent))?$/.exec(words);
    if (adjustment) {
      const [, verb, stream, percent] = adjustment;
      return { name: "adjust_volume", args: { stream: stream === "ringer" ? "ring" : stream ?? "media",
        direction: verb === "raise" || verb === "increase" ? "up" : "down",
        ...(percent === undefined ? {} : { percent: Number(percent) }) } };
    }
  }
  const torch = /^(?:turn|switch) (on|off) (?:(?:the|my) )?(?:flashlight|flash light|torch)$/.exec(words)
    ?? /^(?:turn|switch|put) (?:(?:the|my) )?(?:flashlight|flash light|torch) (on|off)$/.exec(words);
  if (torch) return { name: "flashlight", args: { on: torch[1] === "on" } };
  if (musicPlayer) {
    const generic = /^play (?:music|the music|my music|a music|some music|a song)(?: (?:in|on|from|using) (spotify|youtube(?: music)?))?$/.exec(words);
    if (generic) return { name: "open_music", args: { play: true, ...(generic[1] ? { appName: generic[1] } : {}) } };
  }
  if (/^(?:play (?:music|a music|some music|a song)|open (?:the |a |my )?music (?:app|player))$/.test(words)) {
    return { name: "open_music", args: {} };
  }
  const musicApp = /^(?:play (?:music|a music|some music|a song) (?:in|on|from|using)|open) (spotify|youtube(?: music)?)$/.exec(words);
  if (musicApp) return { name: "open_app", args: { name: musicApp[1] } };
  if (version >= 4) {
    const simpleTitle = (query: string) => query.length <= 160 &&
      !/[.!?;,&]|\b(?:and|or|then|but|if|unless|before|after|also|instead|plus|not|except|without|never|dont|cannot|cant|wont)\b/i.test(query.replace(/[’']/g, ""));
    const player = / (?:on|in|from|using) (spotify|you ?tube(?: music)?)$/i.exec(command);
    const request = player ? command.slice(0, player.index) : command;
    const appName = !player ? undefined : /^spotify$/i.test(player[1]!) ? "Spotify"
      : /music$/i.test(player[1]!) ? "YouTube Music" : "YouTube";
    if (musicPlayer) {
      const qualified = /^(?:play|open|show) (?:(?:(?:a|the|some) )?(?:songs?|music) by |(?:the )?artist profile (?:for |of )?|(?:the )?(artist|song|track|album) )(.+)$/i.exec(request);
      if (qualified && simpleTitle(qualified[2]!) && (appName || !/\s(?:on|in|from|using)\s/i.test(qualified[2]!))) {
        const kind = qualified[1]?.toLowerCase();
        return { name: "play_media", args: { query: qualified[2]!, ...(appName ? { appName } : {}),
          mediaType: kind === "album" ? "album" : kind === "song" || kind === "track" ? "track" : "artist" } };
      }
    }
    const query = /^play (?:the song )?(.+)$/i.exec(request)?.[1];
    if (appName && query && simpleTitle(query)) return { name: "play_media", args: { query, appName } };
  }
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
    const optional = name === "close_app" ? "name" : name === "set_alarm" ? "label" : name === "play_media" ? "appName" : null;
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
