import { NvidiaGrpcClient, type RivaUnaryClient } from "./nvidia-grpc";
import { decodeRivaRecognize, encodeRivaRecognize } from "./riva-protobuf";
import { TranscriptionError, type AudioInput, type AudioTranscriber, type TranscriptionOptions } from "./transcription";

export const PARAKEET_V2_FUNCTION_ID = "d3fe9151-442b-4204-a70d-5fcc597fd610";
export interface ParakeetConfig {
  readonly apiKey: string;
  readonly functionId: string;
  /** Required owner-calibrated Riva score cutoff. Riva does not document a universal probability scale. */
  readonly minConfidence: number;
}

export class ParakeetTranscriber implements AudioTranscriber {
  private readonly client: RivaUnaryClient;
  constructor(private readonly config: ParakeetConfig, client?: RivaUnaryClient) {
    if (!Number.isFinite(config.minConfidence) || config.minConfidence === 0) throw new Error("invalid_nvidia_asr_min_confidence");
    this.client = client ?? new NvidiaGrpcClient(config.apiKey, config.functionId);
  }
  async transcribe(input: AudioInput, options: TranscriptionOptions): Promise<string> {
    if (input.mimeType !== "audio/wav") throw new TranscriptionError("bad_audio");
    const pcm = readPcm(input.bytes);
    // Mirror the phone's final energy check; this rejects silence, not arbitrary environmental noise.
    if (!hasAudioEnergy(pcm)) return "";
    const results = decodeRivaRecognize(await this.client.recognize(encodeRivaRecognize(pcm), options));
    const texts: string[] = [];
    const durationMs = pcm.byteLength * 1_000 / 32_000;
    for (const result of results) {
      const alternative = result.alternatives[0];
      if (!alternative || !alternative.transcript.trim() || alternative.confidence === undefined || alternative.confidence === 0 ||
        alternative.confidence < this.config.minConfidence ||
        alternative.languages.some(language => !/^en(?:-[A-Za-z]{2})?$/.test(language)) || alternative.words.length === 0) return "";
      let previous = 0;
      for (const word of alternative.words) {
        if (!word.word.trim() || /[\u0000-\u001f\u007f]/.test(word.word) || word.startMs < previous ||
          word.endMs < word.startMs || word.endMs > durationMs + 1_000) return "";
        previous = word.startMs;
      }
      texts.push(alternative.transcript.trim());
    }
    const text = texts.join(" ");
    return text.length <= 500 && !/[\u0000-\u001f\u007f]/.test(text) ? text : "";
  }
}

function readPcm(value: Uint8Array): Buffer {
  const bytes = Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  if (bytes.length < 46 || bytes.length > 1_000_000 || bytes.toString("ascii", 0, 4) !== "RIFF" ||
    bytes.toString("ascii", 8, 12) !== "WAVE" || bytes.readUInt32LE(4) !== bytes.length - 8) throw new TranscriptionError("bad_audio");
  let offset = 12; let format = false; let pcm: Buffer | undefined;
  while (offset + 8 <= bytes.length) {
    const name = bytes.toString("ascii", offset, offset + 4); const length = bytes.readUInt32LE(offset + 4); const start = offset + 8;
    if (length > bytes.length - start) throw new TranscriptionError("bad_audio");
    if (name === "fmt ") {
      if (format || length < 16 || bytes.readUInt16LE(start) !== 1 || bytes.readUInt16LE(start + 2) !== 1 ||
        bytes.readUInt32LE(start + 4) !== 16_000 || bytes.readUInt32LE(start + 8) !== 32_000 ||
        bytes.readUInt16LE(start + 12) !== 2 || bytes.readUInt16LE(start + 14) !== 16) throw new TranscriptionError("bad_audio");
      format = true;
    } else if (name === "data") {
      if (pcm || length === 0 || length % 2 !== 0) throw new TranscriptionError("bad_audio");
      pcm = bytes.subarray(start, start + length);
    }
    offset = start + length + length % 2;
  }
  if (!format || !pcm || offset !== bytes.length) throw new TranscriptionError("bad_audio");
  return pcm;
}
function hasAudioEnergy(pcm: Buffer): boolean {
  if (pcm.length < 6_400) return false;
  const frame = 320; let active = 0; let peak = 0;
  for (let offset = 0; offset + frame * 2 <= pcm.length; offset += frame * 2) {
    const samples = new Float64Array(frame); let mean = 0;
    for (let index = 0; index < frame; index++) mean += samples[index] = pcm.readInt16LE(offset + index * 2);
    mean /= frame; let energy = 0;
    for (let index = 0; index < frame; index++) {
      const centered = samples[index]! - mean;
      energy += centered * centered; peak = Math.max(peak, Math.abs(centered));
    }
    const rms = Math.sqrt(energy / frame) / 32_767;
    if (rms >= 0.009) active++;
  }
  return active >= 5 && peak >= 700;
}
