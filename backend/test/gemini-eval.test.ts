import assert from "node:assert/strict";
import test from "node:test";
import { GeminiEvaluationProvider } from "../eval/gemini";
import { StaticSecrets } from "./helpers";

const CONFIG = {
  model: "gemini-3.1-flash-lite",
  thinkingLevel: "MINIMAL" as const,
  timeoutMs: 500
};

test("reports Gemini HTTP status without response content", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response("private provider error", { status: 429 });
  try {
    const provider = new GeminiEvaluationProvider(CONFIG, new StaticSecrets());
    await assert.rejects(
      provider.select({ kind: "text", text: "Open camera" }, "now"),
      /gemini_http_429/
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
