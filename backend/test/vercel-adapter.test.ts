import assert from "node:assert/strict";
import test from "node:test";
import type { FunctionUrlEvent, FunctionUrlResponse } from "../src/core/api";
import { createVercelHandler, type FridayApi } from "../src/vercel/adapter";
import { createPcmWav } from "./helpers";

class RecordingApi implements FridayApi {
  event: FunctionUrlEvent | undefined;
  response: FunctionUrlResponse = {
    statusCode: 200,
    headers: { "content-type": "application/json", "server-timing": "gemini;dur=5.0" },
    body: JSON.stringify({ type: "unsupported" }),
    isBase64Encoded: false
  };

  async handle(event: FunctionUrlEvent): Promise<FunctionUrlResponse> {
    this.event = event;
    return this.response;
  }
}

test("maps a rewritten JSON request into the shared API event", async () => {
  const api = new RecordingApi();
  const handler = createVercelHandler(api);
  const response = await handler(new Request("https://friday.example/api/index?fridayRoute=register", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-forwarded-for": "192.0.2.20",
      "x-vercel-id": "bom1::request-1"
    },
    body: JSON.stringify({ installId: "123e4567-e89b-42d3-a456-426614174000" })
  }));

  assert.equal(api.event?.rawPath, "/v1/register");
  assert.equal(api.event?.requestContext?.http?.sourceIp, "192.0.2.20");
  assert.equal(api.event?.isBase64Encoded, false);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("server-timing"), "gemini;dur=5.0");
});

test("passes WAV bytes as a Lambda-compatible base64 body", async () => {
  const api = new RecordingApi();
  const handler = createVercelHandler(api);
  const wav = createPcmWav();
  await handler(new Request("https://friday.example/api/index?fridayRoute=command", {
    method: "POST",
    headers: { "content-type": "audio/wav", "x-vercel-forwarded-for": "192.0.2.21" },
    body: wav
  }));

  assert.equal(api.event?.rawPath, "/v1/command");
  assert.equal(api.event?.isBase64Encoded, true);
  assert.deepEqual(Buffer.from(api.event?.body ?? "", "base64"), wav);
});

test("keeps unknown routes inside the shared router", async () => {
  const api = new RecordingApi();
  await createVercelHandler(api)(new Request("https://friday.example/unknown"));
  assert.equal(api.event?.rawPath, "/unknown");
});

test("preserves Google OAuth callback state and code through the auth rewrite", async () => {
  const api = new RecordingApi();
  api.response = { statusCode: 303, headers: { location: "com.puneet.friday://signin?loginId=test", "cache-control": "no-store" } };
  const response = await createVercelHandler(api)(new Request("https://friday.example/api/index?fridayRoute=auth/google/callback&state=opaque&code=4%2Fexample"));
  assert.equal(api.event?.rawPath, "/v1/auth/google/callback");
  const query = new URLSearchParams(api.event?.rawQueryString);
  assert.equal(query.get("state"), "opaque");
  assert.equal(query.get("code"), "4/example");
  assert.equal(response.status, 303);
  assert.equal(response.headers.get("location"), "com.puneet.friday://signin?loginId=test");
});

test("rejects route traversal in the auth rewrite parameter", async () => {
  const api = new RecordingApi();
  await createVercelHandler(api)(new Request("https://friday.example/api/index?fridayRoute=auth/../command"));
  assert.equal(api.event?.rawPath, "/api/index");
});

test("answer reports reach the shared router after Vercel rewriting", async () => {
  const api = new RecordingApi();
  api.response = { statusCode: 204 };
  const response = await createVercelHandler(api)(new Request("https://friday.example/api/index?fridayRoute=answers/report", {
    method: "POST", headers: { "content-type": "application/json" }, body: "{}"
  }));
  assert.equal(api.event?.rawPath, "/v1/answers/report");
  assert.equal(response.status, 204);
});

test("speech rewrite returns binary audio rather than base64 text", async () => {
  const api = new RecordingApi();
  const audio = Buffer.from([82, 73, 70, 70, 0, 255]);
  api.response = { statusCode: 200, headers: { "content-type": "audio/wav", "cache-control": "no-store" },
    body: audio.toString("base64"), isBase64Encoded: true };
  const response = await createVercelHandler(api)(new Request("https://friday.example/api/index?fridayRoute=speech", {
    method: "POST", headers: { "content-type": "application/json", "x-friday-speech": "wavenet-v1" }, body: "{}"
  }));
  assert.equal(api.event?.rawPath, "/v1/speech");
  assert.equal(api.event?.headers?.["x-friday-speech"], "wavenet-v1");
  assert.equal(response.headers.get("content-type"), "audio/wav");
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), audio);
});

test("speech request bodies are bounded before the shared router buffers them", async () => {
  for (const declaredSize of [undefined, "20000"]) {
    let cancelled = false;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(Buffer.alloc(16_385)); },
      cancel() { cancelled = true; }
    });
    const api = new RecordingApi();
    const init: RequestInit & { duplex: "half" } = { method: "POST", duplex: "half", body: stream,
      headers: { "content-type": "application/json", ...(declaredSize ? { "content-length": declaredSize } : {}) } };
    await createVercelHandler(api)(new Request("https://friday.example/api/index?fridayRoute=speech", init));
    assert.equal(api.event?.rawPath, "/v1/speech");
    assert.equal(api.event?.body, undefined);
    assert.equal(cancelled, true);
  }
  const api = new RecordingApi();
  const body = JSON.stringify({ text: "Small request" });
  await createVercelHandler(api)(new Request("https://friday.example/v1/speech", {
    method: "POST", headers: { "content-type": "application/json" }, body
  }));
  assert.equal(api.event?.body, body);
});
