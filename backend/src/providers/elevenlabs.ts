import type { FetchLike } from "./gemini";
import { MAX_SPEECH_AUDIO_BYTES } from "../core/speech-audio";
import { abortable, readSpeechBytes } from "./speech-http";

export interface ElevenLabsConfig {
  readonly apiKey: string;
  readonly voiceId: string;
  readonly model: string;
  readonly timeoutMs: number;
}

/** Request 16 kHz PCM so the existing phone player needs no codec or SDK. */
export class ElevenLabsProvider {
  readonly clientVersion = "elevenlabs-v1";
  constructor(private readonly config: ElevenLabsConfig, private readonly fetcher: FetchLike = fetch) {}

  async synthesize(text: string): Promise<Uint8Array> {
    if (!text.trim() || text.length > 600 || /[\u0000-\u001f\u007f]/.test(text)) throw new Error("speech_invalid_text");
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.timeoutMs);
    try {
      const response = await abortable(this.fetcher(`https://api.elevenlabs.io/v1/text-to-speech/${this.config.voiceId}?output_format=pcm_16000`, {
        method: "POST", redirect: "error", signal: controller.signal,
        headers: { "xi-api-key": this.config.apiKey, "content-type": "application/json" },
        body: JSON.stringify({ text, model_id: this.config.model, language_code: "en" })
      }), controller.signal);
      const mime = response.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
      if (!response.ok || (mime !== "audio/pcm" && mime !== "application/octet-stream")) {
        void response.body?.cancel().catch(() => {});
        throw new Error("speech_provider_failed");
      }
      const pcm = await readSpeechBytes(response, MAX_SPEECH_AUDIO_BYTES - 44, controller.signal);
      if (pcm.length === 0 || pcm.length % 2 !== 0) throw new Error("speech_invalid_audio");
      return pcmWav(pcm);
    } catch { throw new Error("speech_unavailable"); }
    finally { clearTimeout(timer); }
  }
}

function pcmWav(pcm: Buffer): Buffer {
  const wav = Buffer.alloc(44 + pcm.length);
  wav.write("RIFF", 0); wav.writeUInt32LE(36 + pcm.length, 4); wav.write("WAVEfmt ", 8);
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(16_000, 24); wav.writeUInt32LE(32_000, 28);
  wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34);
  wav.write("data", 36); wav.writeUInt32LE(pcm.length, 40); pcm.copy(wav, 44);
  return wav;
}
