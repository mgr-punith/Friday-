import { createPrivateKey, sign } from "node:crypto";
import { isSpeechWav } from "../core/speech-audio";
import type { FetchLike } from "./gemini";

export interface WaveNetConfig {
  readonly serviceAccountEmail: string;
  readonly privateKey: string;
  readonly projectId: string;
  readonly voice: string;
  readonly timeoutMs: number;
}

/** Plain REST with cached OAuth credentials; text and generated audio stay in memory. */
export class WaveNetProvider {
  private token: { value: string; expiresAt: number } | undefined;
  private tokenRequest: Promise<string> | undefined;
  private readonly key;

  constructor(private readonly config: WaveNetConfig, private readonly fetcher: FetchLike = fetch,
    private readonly now: () => number = Date.now) {
    this.key = createPrivateKey(config.privateKey);
  }

  async synthesize(text: string): Promise<Uint8Array> {
    if (!text.trim() || text.length > 600 || /[\u0000-\u001f\u007f]/.test(text)) throw new Error("speech_invalid_text");
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.config.timeoutMs);
    try {
      const token = await this.accessToken(controller.signal);
      controller.signal.throwIfAborted();
      const response = await abortable(this.fetcher("https://texttospeech.googleapis.com/v1/text:synthesize", {
        method: "POST", redirect: "error", signal: controller.signal,
        headers: { authorization: `Bearer ${token}`, "content-type": "application/json",
          "x-goog-user-project": this.config.projectId },
        body: JSON.stringify({ input: { text }, voice: { languageCode: this.config.voice.slice(0, 5), name: this.config.voice },
          audioConfig: { audioEncoding: "LINEAR16", sampleRateHertz: 16_000, speakingRate: 1 } })
      }), controller.signal);
      if (!response.ok) {
        void response.body?.cancel().catch(() => {});
        if (response.status === 401) this.token = undefined;
        throw new Error("speech_provider_failed");
      }
      const value = await readJson(response, 2_700_000, controller.signal);
      if (!record(value) || typeof value.audioContent !== "string" ||
        !/^[A-Za-z0-9+/]+={0,2}$/.test(value.audioContent) || value.audioContent.length % 4 !== 0) throw new Error("speech_invalid_audio");
      const bytes = Buffer.from(value.audioContent, "base64");
      if (!isSpeechWav(bytes)) throw new Error("speech_invalid_audio");
      return bytes;
    } catch { throw new Error("speech_unavailable"); }
    finally { clearTimeout(timer); }
  }

  private async accessToken(signal: AbortSignal): Promise<string> {
    signal.throwIfAborted();
    if (this.token && this.token.expiresAt > this.now() + 60_000) return this.token.value;
    if (!this.tokenRequest) {
      // One refresh has its own bound; each waiting speech request retains its earlier deadline.
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), this.config.timeoutMs);
      const request: Promise<string> = this.fetchToken(controller.signal).finally(() => {
        clearTimeout(timer);
        if (this.tokenRequest === request) this.tokenRequest = undefined;
      });
      this.tokenRequest = request;
    }
    return abortable(this.tokenRequest, signal);
  }

  private async fetchToken(signal: AbortSignal): Promise<string> {
    const at = Math.floor(this.now() / 1_000);
    const part = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64url");
    const unsigned = `${part({ alg: "RS256", typ: "JWT" })}.${part({ iss: this.config.serviceAccountEmail,
      scope: "https://www.googleapis.com/auth/cloud-platform", aud: "https://oauth2.googleapis.com/token", iat: at, exp: at + 3_600 })}`;
    const assertion = `${unsigned}.${sign("RSA-SHA256", Buffer.from(unsigned), this.key).toString("base64url")}`;
    const response = await abortable(this.fetcher("https://oauth2.googleapis.com/token", { method: "POST", redirect: "error", signal,
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }).toString() }), signal);
    if (!response.ok) { void response.body?.cancel().catch(() => {}); throw new Error("speech_auth_failed"); }
    const value = await readJson(response, 8_192, signal);
    if (!record(value) || typeof value.access_token !== "string" || !value.access_token || value.access_token.length > 4_096 ||
      /[\s\u0000-\u001f]/.test(value.access_token) || typeof value.expires_in !== "number" ||
      !Number.isInteger(value.expires_in) || value.expires_in < 60 || value.expires_in > 3_600) throw new Error("speech_auth_failed");
    this.token = { value: value.access_token, expiresAt: this.now() + value.expires_in * 1_000 };
    return value.access_token;
  }
}

async function readJson(response: Response, maximum: number, signal: AbortSignal): Promise<unknown> {
  if (!response.body) throw new Error("speech_empty_response");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  let complete = false;
  try {
    while (true) {
      const next = await abortable(reader.read(), signal);
      if (next.done) break;
      length += next.value.byteLength;
      if (length > maximum) throw new Error("speech_large_response");
      chunks.push(next.value);
    }
    complete = true;
  } finally {
    if (!complete) void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  signal.throwIfAborted();
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}
function abortable<T>(operation: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(new Error("speech_timeout"));
    if (signal.aborted) { operation.catch(() => {}); abort(); return; }
    signal.addEventListener("abort", abort, { once: true });
    operation.then(value => { signal.removeEventListener("abort", abort); resolve(value); }, error => {
      signal.removeEventListener("abort", abort); reject(error);
    });
  });
}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
