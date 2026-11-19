import assert from "node:assert/strict";
import test from "node:test";
import { AssistantProviderError } from "../src/core/contracts";
import { GeminiProvider, type FetchLike } from "../src/providers/gemini";
import { StaticSecrets, createPcmWav } from "./helpers";

const CONFIG = {
  model: "gemini-3.1-flash-lite",
  thinkingLevel: "MINIMAL" as const,
  timeoutMs: 500
};

test("sends a compact forced-function request for text", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const fetcher: FetchLike = async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return Response.json({
      candidates: [{ content: { parts: [{ functionCall: { name: "open_app", args: { name: "Camera" } } }] } }]
    });
  };
  const provider = new GeminiProvider(CONFIG, new StaticSecrets(), fetcher);
  const result = await provider.select({ kind: "text", text: "Open camera" }, "2026-11-17T14:00:00+05:30");

  assert.equal(capturedUrl.includes("gemini-3.1-flash-lite:generateContent"), true);
  const request = JSON.parse(String(capturedInit?.body)) as Record<string, any>;
  assert.equal(request.toolConfig.functionCallingConfig.mode, "ANY");
  assert.equal(request.generationConfig.temperature, 0);
  assert.equal(request.generationConfig.maxOutputTokens, 100);
  assert.equal(request.generationConfig.thinkingConfig.thinkingLevel, "MINIMAL");
  assert.match(request.systemInstruction.parts[0].text, /Indian English pronunciation and phrasing/);
  assert.match(request.systemInstruction.parts[0].text, /double and triple digit words/);
  assert.match(request.systemInstruction.parts[0].text, /Never guess an action/);
  assert.equal(
    request.tools[0].functionDeclarations.some((declaration: { name: string }) => declaration.name === "unclear_command"),
    true
  );
  assert.equal(request.contents[0].parts[1].text, "Open camera");
  assert.deepEqual(result.selection, { name: "open_app", args: { name: "Camera" } });
});

test("sends audio inline without logging or file storage", async () => {
  let request: Record<string, any> | undefined;
  const fetcher: FetchLike = async (_input, init) => {
    request = JSON.parse(String(init?.body));
    return Response.json({
      candidates: [{ content: { parts: [{ functionCall: { name: "unsupported", args: {} } }] } }]
    });
  };
  const provider = new GeminiProvider(CONFIG, new StaticSecrets(), fetcher);
  const wav = createPcmWav();
  await provider.select({ kind: "audio", bytes: wav, mimeType: "audio/wav" }, "2026-11-17T14:00:00+05:30");

  assert.equal(request?.contents[0].parts[1].inlineData.mimeType, "audio/wav");
  assert.equal(request?.contents[0].parts[1].inlineData.data, wav.toString("base64"));
});

test("retries one server error and then succeeds", async () => {
  let calls = 0;
  const fetcher: FetchLike = async () => {
    calls += 1;
    if (calls === 1) return new Response("temporary", { status: 503 });
    return Response.json({
      candidates: [{ content: { parts: [{ functionCall: { name: "set_timer", args: { seconds: 20 } } }] } }]
    });
  };
  const provider = new GeminiProvider(CONFIG, new StaticSecrets(), fetcher);
  const result = await provider.select({ kind: "text", text: "Twenty second timer" }, "2026-11-17T14:00:00+05:30");
  assert.equal(calls, 2);
  assert.equal(result.selection.name, "set_timer");
});

test("returns unsupported for a malformed Gemini response", async () => {
  const provider = new GeminiProvider(CONFIG, new StaticSecrets(), async () => Response.json({ candidates: [] }));
  const result = await provider.select({ kind: "text", text: "Hello" }, "2026-11-17T14:00:00+05:30");
  assert.deepEqual(result.selection, { name: "unsupported", args: {} });
});

test("rejects responses with more than one function call", async () => {
  const provider = new GeminiProvider(CONFIG, new StaticSecrets(), async () => Response.json({
    candidates: [{
      content: {
        parts: [
          { functionCall: { name: "open_app", args: { name: "Camera" } } },
          { functionCall: { name: "set_timer", args: { seconds: 10 } } }
        ]
      }
    }]
  }));
  const result = await provider.select({ kind: "text", text: "Do two things" }, "2026-11-17T14:00:00+05:30");
  assert.deepEqual(result.selection, { name: "unsupported", args: {} });
});

test("maps aborts to timeout and non-retryable failures to busy", async () => {
  const timeoutProvider = new GeminiProvider(CONFIG, new StaticSecrets(), async (_input, init) => {
    return new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    });
  });
  await assert.rejects(
    timeoutProvider.select({ kind: "text", text: "Open camera" }, "2026-11-17T14:00:00+05:30"),
    (error: unknown) => error instanceof AssistantProviderError && error.code === "timeout"
  );

  const busyProvider = new GeminiProvider(CONFIG, new StaticSecrets(), async () => new Response("bad", { status: 400 }));
  await assert.rejects(
    busyProvider.select({ kind: "text", text: "Open camera" }, "2026-11-17T14:00:00+05:30"),
    (error: unknown) => error instanceof AssistantProviderError && error.code === "busy"
  );
});
