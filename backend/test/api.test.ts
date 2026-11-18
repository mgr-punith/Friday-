import assert from "node:assert/strict";
import test from "node:test";
import { createApi, type FunctionUrlEvent } from "../src/core/api";
import { AssistantProviderError } from "../src/core/contracts";
import { signInstallToken } from "../src/core/jwt";
import {
  createPcmWav,
  MockAssistant,
  MockUsage,
  RecordingLogger,
  StaticClock,
  StaticSecrets,
  TEST_INSTALL_ID,
  TEST_NOW,
  TEST_SECRETS
} from "./helpers";

function harness() {
  const assistant = new MockAssistant();
  const secrets = new StaticSecrets();
  const usage = new MockUsage();
  const clock = new StaticClock();
  const logger = new RecordingLogger();
  const api = createApi({ assistant, secrets, usage, clock, logger });
  return { api, assistant, secrets, usage, logger };
}

function event(method: string, path: string, overrides: Partial<FunctionUrlEvent> = {}): FunctionUrlEvent {
  return {
    rawPath: path,
    requestContext: {
      requestId: "request-1",
      http: { method, sourceIp: "192.0.2.1" }
    },
    ...overrides
  };
}

function token(): string {
  return signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW);
}

function body(response: { body?: string }): unknown {
  return response.body ? JSON.parse(response.body) : null;
}

test("register returns a signed 30-day token", async () => {
  const { api, usage } = harness();
  const response = await api.handle(
    event("POST", "/v1/register", {
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ installId: TEST_INSTALL_ID })
    })
  );

  assert.equal(response.statusCode, 200);
  const value = body(response) as { token: string; expiresIn: number };
  assert.equal(typeof value.token, "string");
  assert.equal(value.expiresIn, 2_592_000);
  assert.equal(usage.registrationCalls.length, 1);
  assert.equal(usage.registrationCalls[0]?.identityHash.includes("192.0.2.1"), false);
});

test("register rejects invalid bodies and rate-limit failures", async () => {
  const first = harness();
  const invalid = await first.api.handle(
    event("POST", "/v1/register", {
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ installId: "bad", extra: true })
    })
  );
  assert.equal(invalid.statusCode, 400);

  const limited = harness();
  limited.usage.registrationAllowed = false;
  const response = await limited.api.handle(
    event("POST", "/v1/register", {
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ installId: TEST_INSTALL_ID })
    })
  );
  assert.equal(response.statusCode, 429);
  assert.deepEqual(body(response), { error: "busy" });
});

test("warm initializes secrets without calling Gemini", async () => {
  const { api, assistant, secrets } = harness();
  const response = await api.handle(event("GET", "/v1/warm"));
  assert.equal(response.statusCode, 204);
  assert.equal(secrets.calls, 1);
  assert.equal(assistant.calls.length, 0);
});

test("authorized text command returns a validated action and timing", async () => {
  const { api, assistant, usage } = harness();
  const response = await api.handle(
    event("POST", "/v1/command", {
      headers: {
        authorization: `Bearer ${token()}`,
        "content-type": "application/json",
        "x-now": "2026-11-17T14:00:00+05:30"
      },
      body: JSON.stringify({ text: "Set a timer for five minutes" })
    })
  );

  assert.equal(response.statusCode, 200);
  assert.deepEqual(body(response), { type: "action", action: "set_timer", args: { seconds: 300 } });
  assert.equal(response.headers?.["server-timing"], "gemini;dur=12.5");
  assert.equal(assistant.calls[0]?.input.kind, "text");
  assert.equal(usage.commandCalls[0]?.installId, TEST_INSTALL_ID);
});

test("authorized WAV command reaches the provider as bytes", async () => {
  const { api, assistant } = harness();
  const wav = createPcmWav();
  const response = await api.handle(
    event("POST", "/v1/command", {
      headers: {
        authorization: `Bearer ${token()}`,
        "content-type": "audio/wav",
        "x-now": "2026-11-17T14:00:00+05:30"
      },
      body: wav.toString("base64"),
      isBase64Encoded: true
    })
  );

  assert.equal(response.statusCode, 200);
  const input = assistant.calls[0]?.input;
  assert.equal(input?.kind, "audio");
  if (input?.kind === "audio") assert.equal(input.bytes.byteLength, wav.byteLength);
});

test("command rejects missing auth, malformed time, and malformed audio", async () => {
  const missing = harness();
  const missingAuth = await missing.api.handle(
    event("POST", "/v1/command", {
      headers: { "content-type": "application/json", "x-now": "2026-11-17T14:00:00+05:30" },
      body: JSON.stringify({ text: "Open camera" })
    })
  );
  assert.equal(missingAuth.statusCode, 401);
  assert.deepEqual(body(missingAuth), { type: "error", reason: "unauthorized" });

  const invalidTime = harness();
  const timeResponse = await invalidTime.api.handle(
    event("POST", "/v1/command", {
      headers: {
        authorization: `Bearer ${token()}`,
        "content-type": "application/json",
        "x-now": "2026-02-31T14:00:00+05:30"
      },
      body: JSON.stringify({ text: "Open camera" })
    })
  );
  assert.equal(timeResponse.statusCode, 400);

  const invalidAudio = harness();
  const audioResponse = await invalidAudio.api.handle(
    event("POST", "/v1/command", {
      headers: {
        authorization: `Bearer ${token()}`,
        "content-type": "audio/wav",
        "x-now": "2026-11-17T14:00:00+05:30"
      },
      body: Buffer.from("not wav").toString("base64"),
      isBase64Encoded: true
    })
  );
  assert.equal(audioResponse.statusCode, 400);
  assert.equal(invalidAudio.assistant.calls.length, 0);
});

test("rate limiting fails closed before Gemini", async () => {
  const { api, usage, assistant } = harness();
  usage.commandAllowed = false;
  const response = await api.handle(
    event("POST", "/v1/command", {
      headers: {
        authorization: `Bearer ${token()}`,
        "content-type": "application/json",
        "x-now": "2026-11-17T14:00:00+05:30"
      },
      body: JSON.stringify({ text: "Open camera" })
    })
  );
  assert.equal(response.statusCode, 429);
  assert.equal(assistant.calls.length, 0);
});

test("provider timeout and invalid function arguments stay inside the API contract", async () => {
  const timedOut = harness();
  timedOut.assistant.failure = new AssistantProviderError("timeout", 6000);
  const timeoutResponse = await timedOut.api.handle(
    event("POST", "/v1/command", {
      headers: {
        authorization: `Bearer ${token()}`,
        "content-type": "application/json",
        "x-now": "2026-11-17T14:00:00+05:30"
      },
      body: JSON.stringify({ text: "Open camera" })
    })
  );
  assert.equal(timeoutResponse.statusCode, 504);
  assert.deepEqual(body(timeoutResponse), { type: "error", reason: "timeout" });

  const invalidAction = harness();
  invalidAction.assistant.result = {
    selection: { name: "set_alarm", args: { hour: 99, minute: 0 } },
    durationMs: 8
  };
  const invalidResponse = await invalidAction.api.handle(
    event("POST", "/v1/command", {
      headers: {
        authorization: `Bearer ${token()}`,
        "content-type": "application/json",
        "x-now": "2026-11-17T14:00:00+05:30"
      },
      body: JSON.stringify({ text: "Set an alarm" })
    })
  );
  assert.deepEqual(body(invalidResponse), { type: "unsupported" });
});

test("unknown routes return a small no-store response", async () => {
  const { api } = harness();
  const response = await api.handle(event("GET", "/anything"));
  assert.equal(response.statusCode, 404);
  assert.equal(response.headers?.["cache-control"], "no-store");
});
