import assert from "node:assert/strict";
import test from "node:test";
import { GroqEvaluationProvider, type EvaluationFetch } from "../eval/groq";
import { createPcmWav } from "./helpers";

const CONFIG = {
  apiKey: "groq-test-key",
  transcriptionModel: "whisper-large-v3-turbo",
  actionModel: "openai/gpt-oss-20b",
  timeoutMs: 500
};

test("runs Groq transcription before required action classification", async () => {
  const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
  const fetcher: EvaluationFetch = async (input, init) => {
    requests.push({ url: String(input), init });
    if (String(input).endsWith("/audio/transcriptions")) return Response.json({ text: "Open camera" });
    return Response.json({
      choices: [{
        message: {
          tool_calls: [{ function: { name: "open_app", arguments: JSON.stringify({ name: "Camera" }) } }]
        }
      }]
    });
  };
  const provider = new GroqEvaluationProvider(CONFIG, fetcher);
  const result = await provider.select(
    { kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" },
    "2026-11-17T09:30:00+05:30"
  );

  assert.equal(requests.length, 2);
  const transcription = requests[0];
  assert.equal(transcription?.url, "https://api.groq.com/openai/v1/audio/transcriptions");
  assert.equal(transcription?.init?.body instanceof FormData, true);
  const form = transcription?.init?.body as FormData;
  assert.equal(form.get("model"), "whisper-large-v3-turbo");
  assert.equal(form.get("language"), "en");
  assert.equal(form.get("temperature"), "0");

  const classification = requests[1];
  assert.equal(classification?.url, "https://api.groq.com/openai/v1/chat/completions");
  const body = JSON.parse(String(classification?.init?.body)) as Record<string, any>;
  assert.equal(body.model, "openai/gpt-oss-20b");
  assert.equal(body.tool_choice, "required");
  assert.equal(body.parallel_tool_calls, false);
  assert.equal(body.temperature, 0);
  assert.equal(body.max_completion_tokens, 512);
  assert.equal(body.reasoning_effort, "low");
  assert.equal(body.messages[1].content.includes("Open camera"), true);
  assert.deepEqual(result.selection, { name: "open_app", args: { name: "Camera" } });
  assert.equal(result.stages.transcription !== undefined, true);
  assert.equal(result.stages.classification !== undefined, true);
});

test("skips transcription for text cases", async () => {
  const urls: string[] = [];
  const provider = new GroqEvaluationProvider(CONFIG, async (input) => {
    urls.push(String(input));
    return Response.json({
      choices: [{
        message: {
          tool_calls: [{ function: { name: "set_timer", arguments: "{\"seconds\":120}" } }]
        }
      }]
    });
  });
  const result = await provider.select({ kind: "text", text: "Set a timer for two minutes" }, "now");

  assert.deepEqual(urls, ["https://api.groq.com/openai/v1/chat/completions"]);
  assert.deepEqual(result.selection, { name: "set_timer", args: { seconds: 120 } });
  assert.equal(result.stages.transcription, undefined);
});

test("maps an empty Groq transcript to unclear audio without classification", async () => {
  const urls: string[] = [];
  const provider = new GroqEvaluationProvider(CONFIG, async (input) => {
    urls.push(String(input));
    return Response.json({ text: "   " });
  });
  const result = await provider.select(
    { kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" },
    "now"
  );

  assert.deepEqual(urls, ["https://api.groq.com/openai/v1/audio/transcriptions"]);
  assert.deepEqual(result.selection, { name: "unclear_command", args: {} });
  assert.equal(result.stages.classification, undefined);
});

test("uploads benchmark M4A with the matching MIME type and filename", async () => {
  const uploadedFiles: File[] = [];
  const provider = new GroqEvaluationProvider(CONFIG, async (input, init) => {
    if (String(input).endsWith("/audio/transcriptions")) {
      uploadedFiles.push((init?.body as FormData).get("file") as File);
      return Response.json({ text: "Open camera" });
    }
    return Response.json({
      choices: [{ message: { tool_calls: [{ function: { name: "open_app", arguments: "{\"name\":\"Camera\"}" } }] } }]
    });
  });
  await provider.select(
    { kind: "audio", bytes: Uint8Array.from([1, 2, 3]), mimeType: "audio/mp4" },
    "now"
  );

  assert.equal(uploadedFiles[0]?.name, "command.m4a");
  assert.equal(uploadedFiles[0]?.type, "audio/mp4");
});

test("rejects Groq HTTP failures without exposing response content", async () => {
  const provider = new GroqEvaluationProvider(CONFIG, async () => new Response("secret error", { status: 429 }));
  await assert.rejects(
    provider.select({ kind: "text", text: "Open camera" }, "now"),
    /groq_classification_http_429/
  );
});

test("rejects a malformed Groq tool response instead of counting it as unsupported", async () => {
  const provider = new GroqEvaluationProvider(CONFIG, async () => Response.json({ choices: [] }));
  await assert.rejects(
    provider.select({ kind: "text", text: "What is the weather?" }, "now"),
    /groq_classification_invalid_response/
  );
});
