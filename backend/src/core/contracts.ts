import type { ActionProtocolVersion } from "./capabilities";
import type { AnswerContent } from "./answers";
import type { AnswerContext } from "./answer-continuation";

export type AudioMimeType = "audio/wav" | "audio/mp4";

export type CommandInput = (
  | { readonly kind: "audio"; readonly bytes: Uint8Array; readonly mimeType: AudioMimeType }
  | { readonly kind: "text"; readonly text: string }
) & { readonly actionVersion?: ActionProtocolVersion; readonly supportsAnswers?: boolean; readonly answerContext?: AnswerContext;
  readonly answerSetup?: "answers_disabled" | "answers_unavailable" };

export interface FunctionSelection {
  readonly name: string;
  readonly args: unknown;
}

export interface AssistantProviderResult {
  readonly selection: FunctionSelection;
  readonly answer?: AnswerContent;
  /** In-memory original question or latest reply, used only to issue a clarification continuation. Never log. */
  readonly question?: string;
  readonly durationMs: number;
  readonly timingMetric?: "gemini" | "groq";
  readonly stages?: Readonly<Record<string, number>>;
  readonly usage?: Readonly<Record<string, { readonly inputTokens: number; readonly outputTokens: number; readonly cachedTokens: number }>>;
}

export type AssistantProviderErrorCode = "busy" | "timeout";

export interface ProviderFailure {
  readonly diagnosticCode: string;
  readonly httpStatus?: number;
  readonly retryAfterSeconds?: number;
  readonly timingMetric?: "gemini" | "groq";
  readonly usage?: AssistantProviderResult["usage"];
}

export class AssistantProviderError extends Error {
  constructor(
    readonly code: AssistantProviderErrorCode,
    readonly durationMs: number,
    readonly failure?: ProviderFailure
  ) {
    super(code);
    this.name = "AssistantProviderError";
  }
}

export interface AssistantProvider {
  select(input: CommandInput, now: string, context?: { authorizeAnswer(): Promise<boolean> }): Promise<AssistantProviderResult>;
}

export interface SecretBundle {
  readonly geminiApiKey?: string;
  readonly jwtSigningKey: string;
}

export interface SecretProvider {
  getSecrets(): Promise<SecretBundle>;
}

export interface UsageLimitStore {
  consumeRegistration(identityHash: string, at: Date): Promise<boolean>;
  consumeCommand(installId: string, at: Date): Promise<boolean>;
}

export interface Clock {
  now(): Date;
}

export interface SafeLogger {
  error(code: string, requestId?: string): void;
  usage?(value: NonNullable<AssistantProviderResult["usage"]>): void;
}
