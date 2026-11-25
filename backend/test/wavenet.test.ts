import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import test from "node:test";
import { WaveNetProvider, type WaveNetConfig } from "../src/providers/wavenet";
import type { FetchLike } from "../src/providers/gemini";
import { createPcmWav, TEST_NOW } from "./helpers";

// Fresh test-only keys never enter source, output or fixtures.
const keys = generateKeyPairSync("rsa", { modulusLength: 2_048 });
const CONFIG: WaveNetConfig = { serviceAccountEmail: "friday-voice@friday-example.iam.gserviceaccount.com",
  privateKey: keys.privateKey.export({ type: "pkcs8", format: "pem" }).toString(),
  projectId: "friday-example", voice: "en-IN-Wavenet-A", timeoutMs: 100 };
const text = "Sachin Tendulkar is a former Indian cricketer.";
const audio = createPcmWav();
const token = () => Response.json({ access_token: "test-oauth-access-token", expires_in: 3_600 });
const speech = () => Response.json({ audioContent: audio.toString("base64") });

test("WaveNet uses signed service-account OAuth and requests bounded plain-text PCM audio", async () => {
  const calls: { url: string; init: RequestInit }[] = [];
  const provider = new WaveNetProvider(CONFIG, async (url, init = {}) => {
    calls.push({ url: String(url), init });
    return String(url).includes("oauth2") ? token() : speech();
  }, () => TEST_NOW.getTime());
  assert.deepEqual(await provider.synthesize(text), audio);
  assert.deepEqual(calls.map(call => call.url), ["https://oauth2.googleapis.com/token", "https://texttospeech.googleapis.com/v1/text:synthesize"]);
  const auth = calls[0]!.init;
  const form = new URLSearchParams(String(auth.body));
  assert.equal(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
  const assertion = form.get("assertion")!.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(assertion[0]!, "base64url").toString()), { alg: "RS256", typ: "JWT" });
  const claims = JSON.parse(Buffer.from(assertion[1]!, "base64url").toString());
  assert.equal(claims.iss, CONFIG.serviceAccountEmail);
  assert.equal(claims.aud, "https://oauth2.googleapis.com/token");
  assert.equal(claims.scope, "https://www.googleapis.com/auth/cloud-platform");
  assert.equal(claims.exp - claims.iat, 3_600);
  assert.equal(verify("RSA-SHA256", Buffer.from(assertion.slice(0, 2).join(".")), keys.publicKey, Buffer.from(assertion[2]!, "base64url")), true);
  const request = calls[1]!.init;
  assert.deepEqual(JSON.parse(String(request.body)), { input: { text }, voice: { languageCode: "en-IN", name: "en-IN-Wavenet-A" },
    audioConfig: { audioEncoding: "LINEAR16", sampleRateHertz: 16_000, speakingRate: 1 } });
  assert.equal(new Headers(request.headers).get("authorization"), "Bearer test-oauth-access-token");
  assert.equal(new Headers(request.headers).get("x-goog-user-project"), CONFIG.projectId);
  assert.equal(auth.redirect, "error"); assert.equal(request.redirect, "error");
  assert.ok(auth.signal && request.signal, "OAuth refresh and synthesis both have bounded abort signals");
});

test("concurrent synthesis shares OAuth refresh and refreshes before cached-token expiry", async () => {
  let authCalls = 0; let speechCalls = 0; let now = TEST_NOW.getTime();
  const provider = new WaveNetProvider(CONFIG, async url => {
    if (String(url).includes("oauth2")) { authCalls++; await new Promise(resolve => setTimeout(resolve, 5)); return token(); }
    speechCalls++; return speech();
  }, () => now);
  await Promise.all([provider.synthesize(text), provider.synthesize(text)]);
  assert.equal(authCalls, 1); assert.equal(speechCalls, 2);
  now += 3_500_000;
  await provider.synthesize(text);
  assert.equal(authCalls, 1);
  now += 50_000;
  await provider.synthesize(text);
  assert.equal(authCalls, 2);
});

test("provider failures are sanitized, release refresh state and never retry billable synthesis", async () => {
  for (const status of [401, 403, 429, 500]) {
    let calls = 0;
    const provider = new WaveNetProvider(CONFIG, async url => {
      calls++;
      return String(url).includes("oauth2") ? token() : new Response("private provider detail", { status });
    });
    await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
    assert.equal(calls, 2);
  }
  let calls = 0;
  const provider = new WaveNetProvider(CONFIG, async () => { calls++; throw new Error("private token detail"); });
  await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
  await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
  assert.equal(calls, 2, "A failed pending refresh does not poison later speech attempts");
});

test("authentication and synthesis stop within the shared timeout", async () => {
  for (const stallAuth of [true, false]) {
    let calls = 0;
    const fetcher: FetchLike = async (url, init) => {
      calls++;
      if (!stallAuth && String(url).includes("oauth2")) return token();
      return new Promise((_resolve, reject) => {
        init!.signal!.addEventListener("abort", () => reject(new Error("private timeout detail")), { once: true });
      });
    };
    const provider = new WaveNetProvider({ ...CONFIG, timeoutMs: 20 }, fetcher);
    await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
    assert.equal(calls, stallAuth ? 1 : 2);
  }
});

test("expired speech requests cannot continue through late shared OAuth or response bodies", async () => {
  let finishAuth: ((response: Response) => void) | undefined;
  let syntheses = 0;
  const provider = new WaveNetProvider({ ...CONFIG, timeoutMs: 20 }, async url => {
    if (String(url).includes("oauth2")) return new Promise<Response>(resolve => { finishAuth = resolve; });
    syntheses++; return speech();
  });
  const pending = [provider.synthesize(text), provider.synthesize(text)];
  const completed = await Promise.allSettled(pending);
  assert.ok(completed.every(result => result.status === "rejected"));
  finishAuth!(token());
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(syntheses, 0, "Late shared credentials never trigger synthesis for expired requests");

  for (const stallAuth of [true, false]) {
    let cancelled = false;
    const provider = new WaveNetProvider({ ...CONFIG, timeoutMs: 20 }, async url => {
      if (!stallAuth && String(url).includes("oauth2")) return token();
      return new Response(new ReadableStream<Uint8Array>({
        start(controller) { controller.enqueue(Buffer.from("{")); },
        cancel() { cancelled = true; }
      }));
    });
    await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
    await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal(cancelled, true, "A stalled token or audio body is cancelled at the deadline");
  }
});

test("speech WAV validation rejects headers and chunks that the Android player would reject", async () => {
  const corrupt = (edit: (bytes: Buffer) => void): Buffer => { const bytes = Buffer.from(audio); edit(bytes); return bytes; };
  const trailing = Buffer.concat([audio, Buffer.from([0])]); trailing.writeUInt32LE(trailing.length - 8, 4);
  const oddData = Buffer.concat([audio.subarray(0, audio.length - 1), Buffer.from([0])]);
  oddData.writeUInt32LE(audio.length - 45, 40);
  const duplicateData = Buffer.concat([audio, audio.subarray(36)]); duplicateData.writeUInt32LE(duplicateData.length - 8, 4);
  const duplicateFormat = Buffer.concat([audio.subarray(0, 36), audio.subarray(12)]); duplicateFormat.writeUInt32LE(duplicateFormat.length - 8, 4);
  for (const bytes of [corrupt(bytes => bytes.writeUInt32LE(1, 28)), corrupt(bytes => bytes.writeUInt16LE(1, 32)),
    corrupt(bytes => bytes.writeUInt32LE(0xffff_ffff, 40)), trailing, oddData, duplicateData, duplicateFormat]) {
    const provider = new WaveNetProvider(CONFIG, async url => String(url).includes("oauth2") ? token()
      : Response.json({ audioContent: bytes.toString("base64") }));
    await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
  }
  const metadata = Buffer.alloc(10); metadata.write("JUNK"); metadata.writeUInt32LE(1, 4); metadata[8] = 7;
  const padded = Buffer.concat([audio.subarray(0, 12), metadata, audio.subarray(12)]);
  padded.writeUInt32LE(padded.length - 8, 4);
  const provider = new WaveNetProvider(CONFIG, async url => String(url).includes("oauth2") ? token()
    : Response.json({ audioContent: padded.toString("base64") }));
  assert.deepEqual(await provider.synthesize(text), padded, "Padded metadata remains compatible with the phone parser");
});

test("invalid, oversized and unsafe speech inputs and provider payloads fail before playback", async () => {
  for (const value of ["", " ", "a".repeat(601), "line\nbreak", "text\u0000"]) {
    let calls = 0;
    const provider = new WaveNetProvider(CONFIG, async () => { calls++; return speech(); });
    await assert.rejects(provider.synthesize(value), { message: "speech_invalid_text" });
    assert.equal(calls, 0);
  }
  for (const value of [null, {}, { audioContent: "https://example.com/audio.wav" }, { audioContent: "AAAA" },
    { audioContent: audio.toString("base64").slice(1) },
    { audioContent: createPcmWav({ sampleRate: 8_000 }).toString("base64") },
    { audioContent: createPcmWav({ sampleCount: 960_001 }).toString("base64") }]) {
    const provider = new WaveNetProvider(CONFIG, async url => String(url).includes("oauth2") ? token() : Response.json(value));
    await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
  }
  for (const value of [null, {}, { access_token: "contains space", expires_in: 3_600 },
    { access_token: "test-token", expires_in: 0 }, { access_token: "test-token", expires_in: 3_601 }]) {
    let calls = 0;
    const provider = new WaveNetProvider(CONFIG, async () => { calls++; return Response.json(value); });
    await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
    assert.equal(calls, 1);
  }
  for (const response of [() => new Response("malformed JSON"), () => new Response(null),
    () => new Response(" ".repeat(2_700_001))]) {
    const provider = new WaveNetProvider(CONFIG, async url => String(url).includes("oauth2") ? token() : response());
    await assert.rejects(provider.synthesize(text), { message: "speech_unavailable" });
  }
});
