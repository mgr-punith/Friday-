import type { CommandInput } from "../core/contracts";

export type AudioInput = Extract<CommandInput, { kind: "audio" }>;
export interface TranscriptionOptions {
  readonly signal: AbortSignal;
  readonly timeoutMs: number;
}
export interface AudioTranscriber {
  /** Returns an empty string for unclear/no-speech audio. Transcripts stay in memory. */
  transcribe(input: AudioInput, options: TranscriptionOptions): Promise<string>;
}

export class TranscriptionError extends Error {
  constructor(readonly reason: "timeout" | "network_error" | "unavailable" | "invalid_response" | "bad_audio" | "unauthorized" | "rate_limited",
    readonly retryable = false, readonly retryAfterMs?: number) {
    super("transcription_unavailable");
    this.name = "TranscriptionError";
  }
}
