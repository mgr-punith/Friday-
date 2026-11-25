import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { connect, createServer, type ClientHttp2Session, type IncomingHttpHeaders, type ServerHttp2Stream } from "node:http2";
import test from "node:test";
import { loadVercelConfig } from "../src/config";
import { createApi } from "../src/core/api";
import { signInstallToken } from "../src/core/jwt";
import { GroqProvider } from "../src/providers/groq";
import { NvidiaGrpcClient } from "../src/providers/nvidia-grpc";
import { PARAKEET_V2_FUNCTION_ID, ParakeetTranscriber } from "../src/providers/parakeet";
import { decodeRivaRecognize, encodeRivaRecognize } from "../src/providers/riva-protobuf";
import { TranscriptionError, type AudioTranscriber } from "../src/providers/transcription";
import { createPcmWav, MockAssistant, MockUsage, RecordingLogger, StaticClock, StaticSecrets, TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

const CONFIG = { apiKey: "nvidia-development-test-key", functionId: PARAKEET_V2_FUNCTION_ID, minConfidence: -0.4 };
const NOW = "2026-11-17T14:00:00+05:30";
const text = "Play Yo Yo Honey Singh on YouTube";
const selectorText = "Play music by Yo Yo Honey Singh in the YouTube app";
const varint = (value: number) => {
  const values = []; do { const n = value % 128; value = Math.floor(value / 128); values.push(n | (value > 0 ? 128 : 0)); } while (value > 0);
  return Buffer.from(values);
};
const field = (id: number, value: Uint8Array) => Buffer.concat([varint(id * 8 + 2), varint(value.length), value]);
const scalar = (id: number, value: number) => Buffer.concat([varint(id * 8), varint(value)]);
const float = (id: number, value: number) => { const buffer = Buffer.alloc(5); buffer[0] = id * 8 + 5; buffer.writeFloatLE(value, 1); return buffer; };
function response(options: { confidence?: number; missingConfidence?: boolean; transcript?: string; language?: string; missingWords?: boolean; end?: number } = {}): Buffer {
  const word = Buffer.concat([scalar(1, 0), scalar(2, options.end ?? 100), field(3, Buffer.from("Play"))]);
  const alternative = Buffer.concat([field(1, Buffer.from(options.transcript ?? text)),
    ...(options.missingConfidence ? [] : [float(2, options.confidence ?? -0.2)]),
    ...(options.missingWords ? [] : [field(3, word)]), field(4, Buffer.from(options.language ?? "en-US"))]);
  return field(1, field(1, alternative));
}
function wav(): Buffer {
  const bytes = createPcmWav({ sampleCount: 16_000 });
  for (let index = 0; index < 16_000; index++) {
    const envelope = 2_000 + 2_000 * (1 + Math.sin(index * 2 * Math.PI * 5 / 16_000));
    bytes.writeInt16LE(Math.round(Math.sin(index * 2 * Math.PI * 180 / 16_000) * envelope), 44 + index * 2);
  }
  return bytes;
}
const options = () => ({ signal: new AbortController().signal, timeoutMs: 1_000 });

test("Parakeet configuration is explicit and keeps its fixed hosted endpoint and key on the backend", () => {
  const base = { AI_PROVIDER: "groq", GROQ_API_KEY: "example-groq-test-key", GOOGLE_WEB_CLIENT_ID: "123-test.apps.googleusercontent.com",
    JWT_SIGNING_KEY: TEST_SECRETS.jwtSigningKey, UPSTASH_REDIS_REST_URL: "https://example.upstash.io", UPSTASH_REDIS_REST_TOKEN: "replace-with-a-test-rest-token" };
  assert.equal(loadVercelConfig(base).assistant.provider, "groq");
  const config = loadVercelConfig({ ...base, TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey,
    NVIDIA_ASR_MIN_CONFIDENCE: "-0.4" }).assistant;
  assert.equal(config.provider, "groq");
  if (config.provider !== "groq") throw new Error("expected_groq");
  assert.deepEqual(config.transcription, { provider: "nvidia-parakeet", ...CONFIG });
  assert.equal(config.actionModel, "openai/gpt-oss-20b");
  const calibrated = loadVercelConfig({ ...base, TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey, NVIDIA_ASR_MIN_CONFIDENCE: "-0.4" }).assistant;
  if (calibrated.provider === "groq") assert.equal(calibrated.transcription?.minConfidence, -0.4);
  assert.throws(() => loadVercelConfig({ ...base, TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey }),
    /missing_nvidia_asr_min_confidence/);
  assert.throws(() => loadVercelConfig({ ...base, TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey,
    NVIDIA_ASR_MIN_CONFIDENCE: " " }), /missing_nvidia_asr_min_confidence/);
  for (const patch of [
    { TRANSCRIPTION_PROVIDER: "unknown" }, { TRANSCRIPTION_PROVIDER: "nvidia-parakeet" },
    { TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey, NVIDIA_ASR_FUNCTION_ID: "https://other.example" },
    { TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey + String.fromCharCode(10) + "invalid" },
    { TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey, NVIDIA_ASR_MIN_CONFIDENCE: "NaN" },
    { TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey, NVIDIA_ASR_MIN_CONFIDENCE: "Infinity" },
    { TRANSCRIPTION_PROVIDER: "nvidia-parakeet", NVIDIA_API_KEY: CONFIG.apiKey, NVIDIA_ASR_MIN_CONFIDENCE: "0" }
  ]) assert.throws(() => loadVercelConfig({ ...base, ...patch }), /(?:invalid|missing)_/);
  for (const minimum of [0, NaN, Infinity, -Infinity, undefined]) {
    assert.throws(() => new ParakeetTranscriber({ ...CONFIG, minConfidence: minimum as number }), /invalid_nvidia_asr_min_confidence/);
  }
});

test("Riva request wire format sends raw PCM with 16 kHz mono English config", () => {
  const pcm = Buffer.from([0, 0, 1, 0]);
  assert.equal(encodeRivaRecognize(pcm).toString("hex"), "0a14080110807d1a05656e2d55532001380140015801120400000100");
  const parsed = decodeRivaRecognize(response());
  assert.equal(parsed[0]?.alternatives[0]?.transcript, text);
  assert.ok(Math.abs(parsed[0]!.alternatives[0]!.confidence! + .2) < 0.00001);
  assert.deepEqual(parsed[0]?.alternatives[0]?.words, [{ startMs: 0, endMs: 100, word: "Play" }]);
});

test("Riva codec bounds unknown fields and rejects malformed wire types, lengths and UTF-8", () => {
  assert.equal(decodeRivaRecognize(Buffer.concat([response(), field(100, Buffer.from([1, 2]))])).length, 1);
  for (const invalid of [Buffer.from([0]), Buffer.from([10, 20]), Buffer.from([0x0b]), Buffer.from([0x0d, 0]),
    Buffer.alloc(65_537), field(1, field(1, field(1, Buffer.from([0xff])))),
    Buffer.concat(Array.from({ length: 11 }, () => response())), response({ confidence: NaN }), response({ confidence: Infinity })]) {
    assert.throws(() => decodeRivaRecognize(invalid), TranscriptionError);
  }
});

test("Parakeet strips the WAV header, rejects silence and requires confidence and valid word timings", async () => {
  let calls = 0; let sent: Uint8Array | undefined;
  const provider = new ParakeetTranscriber(CONFIG, { async recognize(message) { calls++; sent = message; return response(); } });
  const audio = wav();
  assert.equal(await provider.transcribe({ kind: "audio", bytes: audio, mimeType: "audio/wav" }, options()), text);
  assert.deepEqual(sent, encodeRivaRecognize(audio.subarray(44)));
  assert.equal(await provider.transcribe({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" }, options()), "");
  assert.equal(calls, 1, "Silent audio never reaches NVIDIA");
  const noise = wav(); let random = 17;
  for (let index = 44; index < noise.length; index += 2) {
    random = (Math.imul(random, 1_664_525) + 1_013_904_223) >>> 0;
    noise.writeInt16LE((random >>> 16) - 32_768, index);
  }
  const untrustedNoise = new ParakeetTranscriber(CONFIG, { async recognize() { return response({ missingConfidence: true }); } });
  assert.equal(await untrustedNoise.transcribe({ kind: "audio", bytes: noise, mimeType: "audio/wav" }, options()), "",
    "Noisy input still requires actual recognition metadata; energy is not a speech classifier");
  for (const bad of [response({ confidence: 0 }), response({ missingConfidence: true }), response({ transcript: "" }),
    response({ language: "hi-IN" }), response({ missingWords: true }), response({ end: 4_000 }), response({ transcript: "line\nbreak" }),
    response({ transcript: "x".repeat(501) })]) {
    const transcriber = new ParakeetTranscriber(CONFIG, { async recognize() { return bad; } });
    assert.equal(await transcriber.transcribe({ kind: "audio", bytes: audio, mimeType: "audio/wav" }, options()), "");
  }
  const calibrated = new ParakeetTranscriber({ ...CONFIG, minConfidence: -0.1 }, { async recognize() { return response(); } });
  assert.equal(await calibrated.transcribe({ kind: "audio", bytes: audio, mimeType: "audio/wav" }, options()), "");
  for (const bad of [createPcmWav({ sampleRate: 8_000 }), createPcmWav({ channels: 2 }), audio.subarray(0, audio.length - 1),
    Buffer.concat([audio, Buffer.from([0])]), audio.map((byte, index) => index === 28 ? 1 : byte)]) {
    await assert.rejects(provider.transcribe({ kind: "audio", bytes: bad, mimeType: "audio/wav" }, options()), TranscriptionError);
  }
  await assert.rejects(provider.transcribe({ kind: "audio", bytes: audio, mimeType: "audio/mp4" }, options()), TranscriptionError);
});

test("energy admission preserves short, quiet, high-pitched and paused clips without stricter sensitivity", async () => {
  const make = (count: number, pitch: number, amplitude: number, silentFrom = count) => {
    const bytes = createPcmWav({ sampleCount: count });
    for (let index = 0; index < count; index++) bytes.writeInt16LE(index < silentFrom
      ? Math.round(Math.sin(index * 2 * Math.PI * pitch / 16_000) * amplitude) : 0, 44 + index * 2);
    return bytes;
  };
  for (const audio of [make(3_200, 180, 1_000), make(16_000, 300, 1_000), make(16_000, 180, 1_000, 3_200)]) {
    let calls = 0;
    const transcriber = new ParakeetTranscriber(CONFIG, { async recognize() { calls++; return response({ transcript: "Next" }); } });
    assert.equal(await transcriber.transcribe({ kind: "audio", bytes: audio, mimeType: "audio/wav" }, options()), "Next");
    assert.equal(calls, 1);
  }
});

class Runtime {
  at = 0; now() { return this.at; } random() { return 0; } async wait(ms: number) { this.at += ms; }
}
const GROQ = { apiKey: "groq-development-test-key", transcriptionModel: "whisper-large-v3-turbo", actionModel: "openai/gpt-oss-20b",
  timeoutMs: 1_000, maxCompletionTokens: 512, transcription: { provider: "nvidia-parakeet" as const, ...CONFIG } };
function tool() { return Response.json({ choices: [{ finish_reason: "tool_calls", message: { tool_calls: [
  { type: "function", function: { name: "play_media", arguments: JSON.stringify({ query: "Yo Yo Honey Singh", appName: "YouTube" }) } }
] } }] }); }

test("Parakeet audio keeps one phone request, uses one NVIDIA call then the Groq selector", async () => {
  const runtime = new Runtime(); const calls: { url: string; body: unknown }[] = [];
  let recognitions = 0;
  const transcriber: AudioTranscriber = { async transcribe(_input, opts) { recognitions++; assert.equal(opts.timeoutMs, 1_000); runtime.at += 100; return selectorText; } };
  const provider = new GroqProvider(GROQ, async (url, init) => { calls.push({ url: String(url), body: init?.body }); runtime.at += 50; return tool(); }, runtime, transcriber);
  const result = await provider.select({ kind: "audio", bytes: wav(), mimeType: "audio/wav", actionVersion: 5 }, NOW);
  assert.equal(recognitions, 1); assert.equal(calls.length, 1);
  assert.equal(calls[0]?.url, "https://api.groq.com/openai/v1/chat/completions");
  assert.equal(JSON.parse(String(calls[0]?.body)).messages[1].content.includes(selectorText), true);
  assert.deepEqual(result.selection, { name: "play_media", args: { query: "Yo Yo Honey Singh", appName: "YouTube" } });
  assert.deepEqual(result.stages, { transcription: 100, classification: 50 });
  assert.equal(result.durationMs, 150);
});

test("NVIDIA and Groq share one recovery allowance and one total provider deadline", async () => {
  const runtime = new Runtime(); let asrCalls = 0; let selectors = 0;
  const transcriber: AudioTranscriber = { async transcribe() { asrCalls++; runtime.at += 50; if (asrCalls === 1) throw new TranscriptionError("unavailable", true); return selectorText; } };
  const provider = new GroqProvider(GROQ, async () => { selectors++; return new Response("", { status: 500 }); }, runtime, transcriber);
  await assert.rejects(provider.select({ kind: "audio", bytes: wav(), mimeType: "audio/wav", actionVersion: 5 }, NOW),
    error => error instanceof Error && error.message === "busy");
  assert.equal(asrCalls, 2); assert.equal(selectors, 1, "Selector cannot recover after ASR consumed the one extra request");
  let lateSelectors = 0;
  const late = new GroqProvider(GROQ, async () => { lateSelectors++; return tool(); }, runtime,
    { async transcribe() { runtime.at += 1_000; return text; } });
  await assert.rejects(late.select({ kind: "audio", bytes: wav(), mimeType: "audio/wav", actionVersion: 5 }, NOW),
    error => error instanceof Error && error.message === "timeout");
  assert.equal(lateSelectors, 0);
});

test("empty recognition, unauthorized service and rate limits cannot become phone actions", async () => {
  for (const failure of ["", new TranscriptionError("unauthorized"), new TranscriptionError("rate_limited")]) {
    let calls = 0; let selections = 0;
    const provider = new GroqProvider(GROQ, async () => { selections++; return tool(); }, new Runtime(), {
      async transcribe() { calls++; if (failure instanceof Error) throw failure; return failure; }
    });
    const pending = provider.select({ kind: "audio", bytes: wav(), mimeType: "audio/wav", actionVersion: 5 }, NOW);
    if (failure instanceof Error) await assert.rejects(pending, error => error instanceof Error && error.message === "busy");
    else assert.deepEqual((await pending).selection, { name: "unclear_command", args: {} });
    assert.equal(calls, 1); assert.equal(selections, 0);
  }
});

test("NVIDIA audio requires an authenticated updated client before usage and provider work", async () => {
  const assistant = new MockAssistant(); const usage = new MockUsage(); const logger = new RecordingLogger();
  const api = createApi({ assistant, usage, logger, secrets: new StaticSecrets(), clock: new StaticClock(), transcriptionClient: "nvidia-v1" });
  const base = { rawPath: "/v1/command", requestContext: { http: { method: "POST" } }, isBase64Encoded: true, body: wav().toString("base64"),
    headers: { "content-type": "audio/wav", "x-now": NOW, authorization: "Bearer " + signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW) } };
  assert.equal((await api.handle(base)).statusCode, 503);
  assert.equal(usage.commandCalls.length, 0); assert.equal(assistant.calls.length, 0);
  assert.equal(logger.entries[0]?.code, "unsupported_transcription_client");
  assert.equal((await api.handle({ ...base, headers: { ...base.headers, "x-friday-transcription": "nvidia-v1", authorization: "Bearer invalid" } })).statusCode, 401);
  assert.equal(usage.commandCalls.length, 0); assert.equal(assistant.calls.length, 0);
  assert.equal((await api.handle({ ...base, headers: { ...base.headers, "x-friday-transcription": "nvidia-v1" } })).statusCode, 200);
  assert.equal(usage.commandCalls.length, 1); assert.equal(assistant.calls.length, 1);
});

class FakeStream extends EventEmitter {
  closed = false; body?: Uint8Array;
  close() { this.closed = true; this.emit("close"); }
  end(body: Uint8Array) { this.body = body; }
}
class FakeSession extends EventEmitter {
  closed = false; destroyed = false; stream = new FakeStream(); headers?: IncomingHttpHeaders;
  streams: FakeStream[] = [];
  unref() {} destroy() { this.destroyed = true; this.emit("close"); } close() { this.closed = true; }
  request(headers: IncomingHttpHeaders) {
    this.headers = headers;
    if (this.streams.length > 0) this.stream = new FakeStream();
    this.streams.push(this.stream); return this.stream;
  }
}
function framed(message = response()) { const prefix = Buffer.alloc(5); prefix.writeUInt32BE(message.length, 1); return Buffer.concat([prefix, message]); }
test("NVIDIA unary transport bounds frames, reads trailers and cancels failed or stalled calls", async () => {
  const session = new FakeSession(); let origin = "";
  const client = new NvidiaGrpcClient(CONFIG.apiKey, CONFIG.functionId, value => { origin = value; return session as unknown as ClientHttp2Session; });
  const pending = client.recognize(encodeRivaRecognize(Buffer.from([0, 1])), options());
  assert.equal(origin, "https://grpc.nvcf.nvidia.com");
  assert.equal(session.headers?.[":path"], "/nvidia.riva.asr.RivaSpeechRecognition/Recognize");
  assert.equal(session.headers?.["function-id"], CONFIG.functionId);
  assert.equal(session.headers?.["grpc-timeout"], "1000m");
  assert.equal(session.headers?.authorization, `Bearer ${CONFIG.apiKey}`);
  assert.equal(session.stream.body?.[0], 0);
  session.stream.emit("response", { ":status": 200, "content-type": "application/grpc" });
  const bytes = framed(); session.stream.emit("data", bytes.subarray(0, 3)); session.stream.emit("data", bytes.subarray(3));
  session.stream.emit("trailers", { "grpc-status": "0" }); session.stream.emit("end");
  assert.deepEqual(await pending, response());
  client.close();
  for (const failure of ["missing-trailers", "compressed", "oversized", "http", "unavailable", "unauthorized", "abort"]) {
    const fake = new FakeSession(); const grpc = new NvidiaGrpcClient(CONFIG.apiKey, CONFIG.functionId, () => fake as unknown as ClientHttp2Session);
    const controller = new AbortController(); const run = grpc.recognize(Buffer.from([1]), { ...options(), signal: controller.signal });
    if (failure === "abort") controller.abort();
    else {
      fake.stream.emit("response", { ":status": failure === "http" ? 429 : 200, "content-type": "application/grpc" });
      if (failure === "unavailable" || failure === "unauthorized") fake.stream.emit("trailers", { "grpc-status": failure === "unavailable" ? "14" : "16" });
      else {
        const body = failure === "oversized" ? Buffer.alloc(65_542) : framed(); if (failure === "compressed") body[0] = 1;
        fake.stream.emit("data", body);
        if (failure !== "missing-trailers") fake.stream.emit("trailers", { "grpc-status": "0" });
        fake.stream.emit("end");
      }
    }
    await assert.rejects(run, TranscriptionError);
    assert.equal(fake.stream.closed, true); grpc.close();
  }
});

test("HTTP/2 sessions multiplex calls, survive a stream cancellation and reconnect after GOAWAY", async () => {
  const first = new FakeSession(); const second = new FakeSession(); let connections = 0;
  const client = new NvidiaGrpcClient(CONFIG.apiKey, CONFIG.functionId, () => (++connections === 1 ? first : second) as unknown as ClientHttp2Session);
  const controller = new AbortController();
  const cancelled = client.recognize(Buffer.from([1]), { ...options(), signal: controller.signal });
  const running = client.recognize(Buffer.from([2]), options());
  assert.equal(connections, 1); assert.equal(first.streams.length, 2);
  controller.abort();
  await assert.rejects(cancelled, error => error instanceof TranscriptionError && error.reason === "timeout");
  assert.equal(first.streams[0]?.closed, true); assert.equal(first.streams[1]?.closed, false);
  const complete = (stream: FakeStream) => {
    stream.emit("response", { ":status": 200, "content-type": "application/grpc" }); stream.emit("data", framed());
    stream.emit("trailers", { "grpc-status": "0" }); stream.emit("end");
  };
  complete(first.streams[1]!); assert.deepEqual(await running, response());
  first.emit("goaway"); assert.equal(first.closed, true);
  const next = client.recognize(Buffer.from([3]), options());
  assert.equal(connections, 2); complete(second.stream); await next; client.close();
  assert.equal(second.destroyed, true);
});

test("NVIDIA transport rejects missing status, duplicate frames, broken sessions and deadline stalls", async () => {
  for (const scenario of ["bad-content-type", "encoding", "duplicate-frame", "headers-status", "session-error", "timeout", "closed", "request-error"] as const) {
    const session = new FakeSession();
    const client = new NvidiaGrpcClient(CONFIG.apiKey, CONFIG.functionId, () => session as unknown as ClientHttp2Session);
    const run = client.recognize(Buffer.from([1]), { ...options(), timeoutMs: 10 });
    if (scenario === "session-error") { session.emit("error", new Error("private service detail")); session.stream.emit("error", new Error("private stream detail")); }
    else if (scenario === "closed") session.stream.emit("close");
    else if (scenario === "request-error") session.stream.emit("error", new Error("private stream detail"));
    else if (scenario !== "timeout") {
      session.stream.emit("response", { ":status": 200, "content-type": scenario === "bad-content-type" ? "application/json" : "application/grpc",
        ...(scenario === "encoding" ? { "grpc-encoding": "gzip" } : {}), ...(scenario === "headers-status" ? { "grpc-status": "16" } : {}) });
      session.stream.emit("data", scenario === "duplicate-frame" ? Buffer.concat([framed(), framed()]) : framed());
      session.stream.emit("trailers", { "grpc-status": "0" }); session.stream.emit("end");
    }
    await assert.rejects(run, error => error instanceof TranscriptionError && error.message === "transcription_unavailable");
    client.close();
  }
  let connections = 0;
  const client = new NvidiaGrpcClient(CONFIG.apiKey, CONFIG.functionId, () => { connections++; throw new Error("private network detail"); });
  const controller = new AbortController(); controller.abort();
  await assert.rejects(client.recognize(Buffer.from([1]), { ...options(), signal: controller.signal }), TranscriptionError);
  assert.equal(connections, 0);
  await assert.rejects(client.recognize(Buffer.from([1]), options()), TranscriptionError);
  assert.equal(connections, 1);
});

test("NVIDIA transport works over real HTTP/2 framing and releases the connection", async () => {
  const server = createServer();
  let path: unknown; let body: Buffer | undefined;
  server.on("stream", (stream: ServerHttp2Stream, headers) => {
    path = headers[":path"]; const chunks: Buffer[] = [];
    stream.on("data", chunk => chunks.push(Buffer.from(chunk)));
    stream.on("end", () => {
      body = Buffer.concat(chunks);
      stream.respond({ ":status": 200, "content-type": "application/grpc+proto" }, { waitForTrailers: true });
      stream.on("wantTrailers", () => stream.sendTrailers({ "grpc-status": "0" })); stream.end(framed());
    });
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address(); if (!address || typeof address === "string") throw new Error("test_server_missing");
  const client = new NvidiaGrpcClient(CONFIG.apiKey, CONFIG.functionId, () => connect(`http://127.0.0.1:${address.port}`));
  try {
    assert.deepEqual(await client.recognize(Buffer.from([1, 2, 3]), options()), response());
    assert.equal(path, "/nvidia.riva.asr.RivaSpeechRecognition/Recognize");
    assert.deepEqual(body, Buffer.from([0, 0, 0, 0, 3, 1, 2, 3]));
  } finally { client.close(); await new Promise<void>(resolve => server.close(() => resolve())); }
});
