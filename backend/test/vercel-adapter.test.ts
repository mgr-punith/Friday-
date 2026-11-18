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
