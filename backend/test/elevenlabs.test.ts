import assert from "node:assert/strict";
import test from "node:test";
import { ElevenLabsProvider, type ElevenLabsConfig } from "../src/providers/elevenlabs";

const CONFIG: ElevenLabsConfig = { apiKey: "example-elevenlabs-api-key", voiceId: "JBFqnCBsd6RMkjVDRZzb",
  model: "eleven_flash_v2_5", timeoutMs: 3_000 };
const PCM = Uint8Array.from([0, 0, 255, 127, 0, 128]);

test("ElevenLabs sends English answer text using server credentials and returns mono PCM16 WAV", async () => {
  const provider = new ElevenLabsProvider(CONFIG, async (url, init) => {
    assert.equal(String(url), "https://api.elevenlabs.io/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb?output_format=pcm_16000");
    assert.equal(init?.method, "POST");
    assert.equal(init?.redirect, "error");
    assert.equal(new Headers(init?.headers).get("xi-api-key"), CONFIG.apiKey);
    assert.deepEqual(JSON.parse(String(init?.body)), { text: "The answer is 42.", model_id: "eleven_flash_v2_5", language_code: "en" });
    return new Response(PCM, { headers: { "content-type": "audio/pcm" } });
  });
  assert.equal(Buffer.from(await provider.synthesize("The answer is 42.")).toString("hex"),
    "524946462a00000057415645666d74201000000001000100803e0000007d00000200100064617461060000000000ff7f0080");
});

test("ElevenLabs fails without retrying or exposing provider errors and rejects non-PCM responses", async () => {
  for (const status of [401, 403, 422, 429, 500, 503]) {
    let attempts = 0;
    let cancelled = false;
    const provider = new ElevenLabsProvider(CONFIG, async () => {
      attempts++;
      return new Response(new ReadableStream({
        start(controller) { controller.enqueue(PCM); controller.close(); },
        cancel() { cancelled = true; }
      }), { status });
    });
    await assert.rejects(provider.synthesize("An answer."), { message: "speech_unavailable" });
    assert.equal(attempts, 1);
    assert.equal(cancelled, true);
  }
  const broken = new ElevenLabsProvider(CONFIG, async () => { throw new Error("private text and key"); });
  await assert.rejects(broken.synthesize("An answer."), { message: "speech_unavailable" });
  for (const mime of ["application/json", "audio/mpeg", "text/plain"]) {
    const provider = new ElevenLabsProvider(CONFIG, async () => new Response(PCM, { headers: { "content-type": mime } }));
    await assert.rejects(provider.synthesize("An answer."), { message: "speech_unavailable" });
  }
});

test("ElevenLabs bounds generated audio and rejects empty or incomplete PCM samples", async () => {
  for (const pcm of [new Uint8Array(), Uint8Array.of(0), Uint8Array.of(0, 0, 1), new Uint8Array(1_920_002)]) {
    const provider = new ElevenLabsProvider(CONFIG, async () => new Response(pcm, { headers: { "content-type": "audio/pcm" } }));
    await assert.rejects(provider.synthesize("An answer."), { message: "speech_unavailable" });
  }
  let cancelled = false;
  let reads = 0;
  const provider = new ElevenLabsProvider(CONFIG, async () => new Response(new ReadableStream({
    pull(controller) {
      reads++;
      controller.enqueue(new Uint8Array(1_000_000));
      if (reads === 4) controller.close();
    }, cancel() { cancelled = true; }
  }), { headers: { "content-type": "application/octet-stream" } }));
  await assert.rejects(provider.synthesize("An answer."), { message: "speech_unavailable" });
  assert.equal(cancelled, true);
  assert.ok(reads <= 3, "Stop reading an oversized stream before buffering all of it");
  const largest = new ElevenLabsProvider(CONFIG, async () => new Response(new Uint8Array(1_920_000), {
    headers: { "content-type": "application/octet-stream" }
  }));
  assert.equal((await largest.synthesize("An answer.")).length, 1_920_044);
});

test("ElevenLabs uses one deadline for response headers and audio streaming", async () => {
  for (const slowBody of [false, true]) {
    let signal: AbortSignal | undefined;
    let cancelled = false;
    const provider = new ElevenLabsProvider({ ...CONFIG, timeoutMs: 20 }, async (_url, init) => {
      signal = init?.signal as AbortSignal;
      await new Promise(resolve => setTimeout(resolve, slowBody ? 10 : 40));
      return new Response(slowBody ? new ReadableStream({
        start(controller) {
          const timer = setTimeout(() => { if (!cancelled) { controller.enqueue(PCM); controller.close(); } }, 40);
          signal?.addEventListener("abort", () => clearTimeout(timer), { once: true });
        }, cancel() { cancelled = true; }
      }) : PCM, { headers: { "content-type": "audio/pcm" } });
    });
    await assert.rejects(provider.synthesize("An answer."), { message: "speech_unavailable" });
    assert.equal(signal?.aborted, true);
    if (slowBody) assert.equal(cancelled, true);
  }
});

test("ElevenLabs rejects empty, oversized or control-character text before a paid request", async () => {
  let calls = 0;
  const provider = new ElevenLabsProvider(CONFIG, async () => { calls++; return new Response(PCM); });
  for (const text of ["", "  ", "a".repeat(601), "hello\u0000there", "hello\nthere", "hello\u007fthere"]) {
    await assert.rejects(provider.synthesize(text), { message: "speech_invalid_text" });
  }
  assert.equal(calls, 0);
});
