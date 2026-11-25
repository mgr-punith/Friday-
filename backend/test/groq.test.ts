import assert from "node:assert/strict";
import test from "node:test";
import { loadVercelConfig } from "../src/config";
import { createApi } from "../src/core/api";
import { parseFunctionSelection } from "../src/core/action-schema";
import { AssistantProviderError } from "../src/core/contracts";
import { signInstallToken } from "../src/core/jwt";
import { EnvironmentSecretProvider } from "../src/env/secrets";
import type { FetchLike } from "../src/providers/gemini";
import { GroqProvider, type GroqRequestRuntime } from "../src/providers/groq";
import { createVercelAssistant } from "../src/providers/vercel-assistant";
import { createPcmWav, MockUsage, RecordingLogger, StaticClock, StaticSecrets, TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

const CONFIG = {
  apiKey: "groq-development-test-key",
  transcriptionModel: "whisper-large-v3-turbo",
  actionModel: "openai/gpt-oss-20b",
  timeoutMs: 1_000,
  maxCompletionTokens: 512
};
const INPUT = { kind: "text" as const, text: "Set a timer for two minutes" };
const NOW = "2026-11-17T14:00:00+05:30";
const TRANSCRIPT = {
  text: "Set a timer for two minutes",
  segments: [{ text: "Set a timer for two minutes", avg_logprob: -0.2, no_speech_prob: 0.02, compression_ratio: 1.2 }]
};
function tool(name = "set_timer", args = '{"seconds":120}'): Response {
  return Response.json({ choices: [{ finish_reason: "tool_calls", message: {
    tool_calls: [{ type: "function", function: { name, arguments: args } }]
  } }] });
}
class Runtime implements GroqRequestRuntime {
  at = 0;
  delays: number[] = [];
  now(): number { return this.at; }
  random(): number { return 0; }
  async wait(ms: number): Promise<void> { this.delays.push(ms); this.at += ms; }
}

test("Vercel selects Groq without requiring a Gemini key or model", async () => {
  const config = loadVercelConfig({
    AI_PROVIDER: "groq", GROQ_API_KEY: CONFIG.apiKey,
    GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com",
    JWT_SIGNING_KEY: TEST_SECRETS.jwtSigningKey,
    UPSTASH_REDIS_REST_URL: "https://test.upstash.io", UPSTASH_REDIS_REST_TOKEN: "replace-with-a-test-rest-token"
  });
  assert.deepEqual(config.assistant, { provider: "groq", ...CONFIG, timeoutMs: 10_000 });
  const secrets = new EnvironmentSecretProvider(undefined, TEST_SECRETS.jwtSigningKey);
  assert.deepEqual(await secrets.getSecrets(), { jwtSigningKey: TEST_SECRETS.jwtSigningKey });
  const urls: string[] = [];
  const provider = createVercelAssistant(config.assistant, secrets, async (url) => { urls.push(String(url)); return tool(); });
  await provider.select(INPUT, NOW);
  assert.deepEqual(urls, ["https://api.groq.com/openai/v1/chat/completions"]);
});

test("Vercel rejects unsafe model paths, a missing key and the old 100-token allowance", () => {
  const base = { AI_PROVIDER: "groq", GROQ_API_KEY: CONFIG.apiKey,
    GOOGLE_WEB_CLIENT_ID: "123456789-test.apps.googleusercontent.com", JWT_SIGNING_KEY: TEST_SECRETS.jwtSigningKey,
    UPSTASH_REDIS_REST_URL: "https://test.upstash.io", UPSTASH_REDIS_REST_TOKEN: "replace-with-a-test-rest-token" };
  assert.throws(() => loadVercelConfig({ ...base, AI_PROVIDER: "unknown" }), /invalid_ai_provider/);
  assert.throws(() => loadVercelConfig({ ...base, GROQ_API_KEY: "" }), /missing_groq_api_key/);
  assert.throws(() => loadVercelConfig({ ...base, GROQ_MAX_COMPLETION_TOKENS: "100" }), /invalid_groq_max_completion_tokens/);
  for (const value of ["../bad", "https://evil.example/model", "bad/model/extra", "model?api-key=bad"]) {
    assert.throws(() => loadVercelConfig({ ...base, GROQ_ACTION_MODEL: value }), /invalid_groq_model/);
  }
});

test("uploads WAV as multipart and classifies once with the corrected GPT OSS request", async () => {
  const runtime = new Runtime();
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const bytes = createPcmWav();
  const provider = new GroqProvider(CONFIG, async (url, init) => {
    requests.push({ url: String(url), ...(init ? { init } : {}) });
    runtime.at += 20;
    return String(url).endsWith("/transcriptions") ? Response.json(TRANSCRIPT) : tool();
  }, runtime);
  const result = await provider.select({ kind: "audio", bytes, mimeType: "audio/wav" }, NOW);
  assert.equal(requests.length, 2);
  const audio = requests[0]?.init;
  assert.equal(audio?.body instanceof FormData, true);
  const form = audio?.body as FormData;
  const file = form.get("file") as File;
  assert.equal(file.name, "command.wav");
  assert.equal(file.type, "audio/wav");
  assert.deepEqual(Buffer.from(await file.arrayBuffer()), bytes);
  assert.equal(form.get("language"), "en");
  assert.equal(form.get("temperature"), "0");
  assert.equal(form.get("response_format"), "verbose_json");
  assert.equal(form.get("model"), "whisper-large-v3-turbo");
  assert.equal((audio?.headers as Record<string, string>)["content-type"], undefined);
  const classification = requests[1]?.init;
  const body = JSON.parse(String(classification?.body));
  assert.equal(body.model, "openai/gpt-oss-20b");
  assert.equal(body.tool_choice, "required");
  assert.equal(body.parallel_tool_calls, false);
  assert.equal(body.max_completion_tokens, 512);
  assert.equal(body.reasoning_effort, "low");
  assert.equal(body.include_reasoning, false);
  assert.equal(body.reasoning_format, undefined);
  assert.equal(body.temperature, 0);
  assert.equal(body.tools.every((entry: Record<string, any>) => entry.function.parameters.additionalProperties === false), true);
  assert.equal(classification?.redirect, "error");
  assert.equal((classification?.headers as Record<string, string>).authorization, `Bearer ${CONFIG.apiKey}`);
  assert.ok(body.messages[1].content.includes(INPUT.text));
  assert.deepEqual(result.selection, { name: "set_timer", args: { seconds: 120 } });
  assert.equal(result.timingMetric, "groq");
  assert.deepEqual(result.stages, { transcription: 20, classification: 20 });
  assert.equal(result.durationMs, 40);
});

test("silence, uncertain speech and missing Whisper metadata cannot reach action classification", async () => {
  for (const value of [
    { text: "" }, { text: "Set a timer for two minutes" },
    { ...TRANSCRIPT, segments: [] },
    { ...TRANSCRIPT, segments: [{ avg_logprob: -0.1, no_speech_prob: 0.9 }] },
    { ...TRANSCRIPT, segments: [{ avg_logprob: -1.5, no_speech_prob: 0.02 }] },
    { ...TRANSCRIPT, segments: [{ avg_logprob: -0.1, no_speech_prob: 0.02, compression_ratio: 3.2 }] },
    { ...TRANSCRIPT, segments: [{ avg_logprob: "bad", no_speech_prob: 0.02 }] }
  ]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return Response.json(value); }, new Runtime());
    const result = await provider.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" }, NOW);
    assert.equal(calls, 1);
    assert.deepEqual(result.selection, { name: "unclear_command", args: {} });
  }
});

test("Groq exposes versioned tools only to clients that request them", async () => {
  const tools: string[][] = [];
  const provider = new GroqProvider(CONFIG, async (_, init) => {
    const body = JSON.parse(String(init?.body)) as { tools: { function: { name: string } }[] };
    const names = body.tools.map(entry => entry.function.name);
    tools.push(names);
    if (names.includes("play_media")) return tool("play_media", '{"query":"Believer","appName":"YouTube"}');
    return names.includes("run_actions") ? tool("run_actions", JSON.stringify({
      actions: [{ action: "flashlight", args: { on: true } }, { action: "open_app", args: { name: "Clock" } }]
    })) : tool("unsupported", "{}");
  }, new Runtime());
  const api = createApi({ assistant: provider, usage: new MockUsage(), secrets: new StaticSecrets(), logger: new RecordingLogger(), clock: new StaticClock() });
  const event = { rawPath: "/v1/command", headers: {
    authorization: "Bearer " + signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW),
    "content-type": "application/json", "x-now": NOW
  }, requestContext: { http: { method: "POST" } },
  body: JSON.stringify({ text: "Turn on the flashlight and open Clock" }) };
  const old = await api.handle(event);
  const expanded = await api.handle({ ...event, headers: { ...event.headers, "x-friday-actions": "2" } });
  const current = await api.handle({ ...event, headers: { ...event.headers, "x-friday-actions": "3" } });
  const namedSong = await api.handle({ ...event, headers: { ...event.headers, "x-friday-actions": "4" },
    body: JSON.stringify({ text: "Play Believer in YouTube" }) });
  assert.equal(tools[2]?.includes("play_media"), false);
  assert.deepEqual(JSON.parse(namedSong.body ?? "null"), {
    type: "action", action: "play_media", args: { query: "Believer", appName: "YouTube" }
  });
  assert.deepEqual(JSON.parse(old.body ?? "null"), { type: "unsupported" });
  assert.deepEqual(JSON.parse(expanded.body ?? "null"), {
    type: "actions",
    actions: [
      { type: "action", action: "flashlight", args: { on: true } },
      { type: "action", action: "open_app", args: { name: "Clock" } }
    ]
  });
  assert.equal(tools[0]?.includes("flashlight"), false);
  assert.equal(tools[1]?.includes("set_volume"), true);
  assert.equal(tools[1]?.includes("open_settings"), false);
  assert.equal(tools[2]?.includes("open_settings"), true);
  assert.deepEqual(JSON.parse(current.body ?? "null"), JSON.parse(expanded.body ?? "null"));
});

test("v3 describes Settings as a user handoff with allowlisted arguments", async () => {
  let request: Record<string, any> | undefined;
  const provider = new GroqProvider(CONFIG, async (_url, init) => {
    request = JSON.parse(String(init?.body));
    return tool("open_settings", '{"setting":"airplane_mode","state":"on"}');
  }, new Runtime());
  const result = await provider.select({ ...INPUT, actionVersion: 3 }, NOW);
  const declaration = request?.tools.find(
    (entry: Record<string, any>) => entry.function.name === "open_settings",
  )?.function;

  assert.deepEqual(declaration.parameters.properties.setting.enum, ["airplane_mode"]);
  assert.deepEqual(declaration.parameters.properties.state.enum, ["on", "off"]);
  assert.match(declaration.description, /user completes the switch in Settings/);
  assert.match(request?.messages[0].content, /user completes the change/);
  assert.deepEqual(result.selection, {
    name: "open_settings", args: { setting: "airplane_mode", state: "on" }
  });
});

test("exact spoken music and torch commands skip classification while compound and negated requests do not", async () => {
  const cases = [
    ["Turn on the flash light.", { name: "flashlight", args: { on: true } }],
    ["Could you switch my torch off, please?", { name: "flashlight", args: { on: false } }],
    ["Play a music.", { name: "open_music", args: {} }],
    ["Do not turn on the flashlight", { name: "unsupported", args: {} }],
    ["Turn on the flashlight and take a screenshot", { name: "unsupported", args: {} }],
    ["Play music in Spotify", { name: "open_app", args: { name: "spotify" } }]
  ] as const;
  for (const [phrase, expected] of cases) {
    let count = 0;
    const provider = new GroqProvider(CONFIG, async (url) => {
      count++;
      return String(url).endsWith("/transcriptions")
        ? Response.json({ ...TRANSCRIPT, text: phrase })
        : tool("unsupported", "{}");
    }, new Runtime());
    const result = await provider.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav", actionVersion: 2 }, NOW);
    assert.deepEqual(result.selection, expected);
    assert.equal(count, expected.name === "unsupported" ? 2 : 1);
  }
});

test("Groq keeps explicit media controls within the negotiated contract", async () => {
  const provider = new GroqProvider(CONFIG, async () => tool("unsupported", "{}"), new Runtime());
  const select = async (text: string, actionVersion: 4 | 5) =>
    (await provider.select({ kind: "text", text, actionVersion }, NOW)).selection;
  assert.deepEqual(await select("Pause music", 5), { name: "control_media", args: { operation: "pause" } });
  assert.deepEqual(await select("Go to the previous track", 5),
    { name: "control_media", args: { operation: "previous" } });
  for (const text of ["Don't pause music", "Pause music and go home", "Pause YouTube"]) {
    assert.equal((await select(text, 5)).name, "unsupported");
  }
  assert.equal((await select("Pause music", 4)).name, "unsupported");
});

test("repairs the reported tool_use_failed JSON error once without retranscribing", async () => {
  const runtime = new Runtime();
  const bodies: Record<string, any>[] = [];
  let transcriptions = 0;
  const provider = new GroqProvider(CONFIG, async (url, init) => {
    if (String(url).endsWith("/transcriptions")) { transcriptions++; return Response.json(TRANSCRIPT); }
    bodies.push(JSON.parse(String(init?.body)));
    return bodies.length === 1 ? Response.json({ error: {
      message: "Failed to parse tool call arguments as JSON", type: "invalid_request_error", param: "", code: "tool_use_failed"
    } }, { status: 400 }) : tool();
  }, runtime);
  const result = await provider.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" }, NOW);
  assert.equal(transcriptions, 1);
  assert.equal(bodies.length, 2);
  assert.equal(bodies[0]?.max_completion_tokens, 512);
  assert.equal(bodies[1]?.max_completion_tokens, 1_024);
  assert.deepEqual(bodies[1]?.messages, bodies[0]?.messages);
  assert.deepEqual(runtime.delays, [200]);
  assert.deepEqual(result.selection, { name: "set_timer", args: { seconds: 120 } });
});

test("repeated tool_use_failed returns one safe error instead of using failed_generation", async () => {
  let calls = 0;
  const provider = new GroqProvider(CONFIG, async () => {
    calls++;
    return Response.json({ error: { code: "tool_use_failed", message: "private transcript", failed_generation: "private tool arguments" } }, { status: 400 });
  }, new Runtime());
  await assert.rejects(provider.select(INPUT, NOW), (error: unknown) => {
    assert.ok(error instanceof AssistantProviderError);
    assert.equal(error.failure?.diagnosticCode, "groq_classification_tool_use_failed");
    assert.equal(error.message, "busy");
    assert.ok(!JSON.stringify(error).includes("private"));
    return true;
  });
  assert.equal(calls, 2);
});

test("blank optional Groq fields preserve Home navigation and alarms without accepting blank required targets", async () => {
  const cases = [
    { name: "close_app", args: '{"name":""}', expected: { type: "action", action: "close_app", args: {} } },
    { name: "set_alarm", args: '{"hour":6,"minute":30,"label":" "}', expected: { type: "action", action: "set_alarm", args: {hour:6,minute:30} } },
    { name: "open_app", args: '{"name":""}', expected: { type: "error", reason: "bad_audio" } }
  ];
  for (const value of cases) {
    const provider = new GroqProvider(CONFIG, async () => tool(value.name, value.args), new Runtime());
    const result = await provider.select(INPUT, NOW);
    assert.deepEqual(parseFunctionSelection(result.selection), value.expected);
  }
});

test("never retries missing models, invalid keys or an ordinary bad request", async () => {
  for (const status of [400, 401, 403, 404, 422]) {
    const runtime = new Runtime();
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return new Response("private details", { status }); }, runtime);
    await assert.rejects(provider.select(INPUT, NOW), (error: unknown) =>
      error instanceof AssistantProviderError && error.failure?.diagnosticCode === `groq_classification_http_${status}` && error.message === "busy");
    assert.equal(calls, 1);
    assert.deepEqual(runtime.delays, []);
  }
});

test("backoff recovers a transient 5xx and has one shared retry across both Groq stages", async () => {
  for (const status of [500, 502, 503, 504]) {
    const runtime = new Runtime();
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => {
      calls++;
      return calls === 1 ? new Response("unavailable", { status }) : tool();
    }, runtime);
    assert.equal((await provider.select(INPUT, NOW)).selection.name, "set_timer");
    assert.equal(calls, 2);
    assert.deepEqual(runtime.delays, [200]);
  }
  let calls = 0;
  const provider = new GroqProvider(CONFIG, async (url) => {
    calls++;
    if (calls === 1) return new Response("retry", { status: 503 });
    if (String(url).endsWith("/transcriptions")) return Response.json(TRANSCRIPT);
    return Response.json({ error: { code: "tool_use_failed" } }, { status: 400 });
  }, new Runtime());
  await assert.rejects(provider.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" }, NOW), /busy/);
  assert.equal(calls, 3);
});

test("a network failure backs off once and cannot expose the thrown provider details", async () => {
  const runtime = new Runtime();
  let calls = 0;
  const recovered = new GroqProvider(CONFIG, async () => {
    calls++;
    if (calls === 1) throw new TypeError("private connection details");
    return tool();
  }, runtime);
  assert.equal((await recovered.select(INPUT, NOW)).selection.name, "set_timer");
  assert.equal(calls, 2);
  assert.deepEqual(runtime.delays, [200]);
  calls = 0;
  const failed = new GroqProvider(CONFIG, async () => { calls++; throw new TypeError("private connection details"); }, new Runtime());
  await assert.rejects(failed.select(INPUT, NOW), (error: unknown) => error instanceof AssistantProviderError &&
    error.failure?.diagnosticCode === "groq_classification_network_error" && error.message === "busy");
  assert.equal(calls, 2);
});

test("429 honors a short Retry-After and returns long or missing cooldowns without a retry storm", async () => {
  const short = new Runtime();
  let calls = 0;
  const provider = new GroqProvider({ ...CONFIG, timeoutMs: 2_000 }, async () => {
    calls++;
    return calls === 1 ? new Response("limited", { status: 429, headers: { "retry-after": "0.5" } }) : tool();
  }, short);
  assert.equal((await provider.select(INPUT, NOW)).selection.name, "set_timer");
  assert.deepEqual(short.delays, [500]);
  for (const cooldown of [null, "60", "86400", "-1"]) {
    const runtime = new Runtime();
    let requests = 0;
    const limited = new GroqProvider(CONFIG, async () => {
      requests++;
      return new Response("quota private details", { status: 429,
        ...(cooldown ? { headers: { "retry-after": cooldown } } : {}) });
    }, runtime);
    await assert.rejects(limited.select(INPUT, NOW), (error: unknown) => error instanceof AssistantProviderError &&
      error.failure?.httpStatus === 429 && error.failure.retryAfterSeconds === Number(cooldown === "-1" ? "60" : cooldown ?? "60"));
    assert.equal(requests, 1);
    assert.deepEqual(runtime.delays, []);
  }
});

test("transcription and classification share the deadline and insufficient budget prevents retries", async () => {
  const runtime = new Runtime();
  let calls = 0;
  const provider = new GroqProvider(CONFIG, async (url) => {
    calls++;
    if (String(url).endsWith("/transcriptions")) { runtime.at = 800; return Response.json(TRANSCRIPT); }
    runtime.at = 900;
    return new Response("too late", { status: 503 });
  }, runtime);
  await assert.rejects(provider.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" }, NOW), /busy/);
  assert.equal(calls, 2);
  assert.deepEqual(runtime.delays, []);
  runtime.at = 0;
  calls = 0;
  const expired = new GroqProvider(CONFIG, async () => { calls++; runtime.at = 950; return Response.json(TRANSCRIPT); }, runtime);
  await assert.rejects(expired.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" }, NOW), (error: unknown) =>
    error instanceof AssistantProviderError && error.code === "timeout");
  assert.equal(calls, 1);
});

test("aborted requests return timeout and never call a second stage", async () => {
  let calls = 0;
  const fetcher: FetchLike = async (_url, init) => {
    calls++;
    return new Promise((_resolve, reject) => init?.signal?.addEventListener("abort", () => reject(new DOMException("private", "AbortError"))));
  };
  const provider = new GroqProvider({ ...CONFIG, timeoutMs: 120 }, fetcher);
  await assert.rejects(provider.select(INPUT, NOW), (error: unknown) => error instanceof AssistantProviderError && error.code === "timeout");
  assert.equal(calls, 1);
});

test("stalled response bodies stay inside the deadline and oversized responses run no action", async () => {
  for (const status of [200, 503]) {
    let calls = 0;
    const provider = new GroqProvider({ ...CONFIG, timeoutMs: 120 }, async (_url, init) => {
      calls++;
      return new Response(new ReadableStream({ start(controller) {
        controller.enqueue(new TextEncoder().encode('{"private":"'));
        init?.signal?.addEventListener("abort", () => controller.error(new DOMException("private body", "AbortError")));
      } }), { status });
    });
    await assert.rejects(provider.select(INPUT, NOW), (error: unknown) => error instanceof AssistantProviderError &&
      error.code === "timeout" && error.failure?.diagnosticCode === "groq_classification_timeout");
    assert.equal(calls, 1);
  }
  let calls = 0;
  const oversized = new GroqProvider(CONFIG, async () => { calls++; return new Response("x".repeat(65_537)); }, new Runtime());
  await assert.rejects(oversized.select(INPUT, NOW), (error: unknown) => error instanceof AssistantProviderError &&
    error.failure?.diagnosticCode === "groq_classification_invalid_response");
  assert.equal(calls, 1);
});

test("malformed, unknown, multiple and truncated tool calls never select a phone action", async () => {
  const cases: Array<() => Response> = [
    () => Response.json({ choices: [] }), () => tool("invented_action"), () => tool("set_timer", "not JSON"),
    () => tool("set_timer", "null"), () => tool("set_timer", "[]"),
    () => Response.json({ choices: [{ finish_reason: "length", message: { tool_calls: [{ function: { name: "set_timer", arguments: '{"seconds":120}' } }] } }] }),
    () => Response.json({ choices: [{ message: { tool_calls: [{ function: { name: "set_timer", arguments: '{}' } }, { function: { name: "open_app", arguments: '{}' } }] } }] }),
    () => new Response("invalid JSON", { status: 200 })
  ];
  for (const response of cases) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return response(); }, new Runtime());
    await assert.rejects(provider.select(INPUT, NOW), (error: unknown) => error instanceof AssistantProviderError &&
      error.failure?.diagnosticCode === "groq_classification_invalid_response");
    assert.equal(calls, 1);
  }
});

test("the API preserves one quota check, schema validation, Groq timings and safe error diagnostics", async () => {
  const usage = new MockUsage();
  const logger = new RecordingLogger();
  const secrets = new StaticSecrets();
  let calls = 0;
  let fail = false;
  let invalidArgs = false;
  const provider = new GroqProvider(CONFIG, async (url) => {
    calls++;
    if (fail) return new Response("private provider text", { status: 404 });
    if (String(url).endsWith("/transcriptions")) return Response.json(TRANSCRIPT);
    return tool("set_timer", invalidArgs ? '{"seconds":-1}' : '{"seconds":120}');
  }, new Runtime());
  const api = createApi({ assistant: provider, usage, secrets, logger, clock: new StaticClock() });
  const event = { rawPath: "/v1/command", headers: { "content-type": "audio/wav", "x-now": NOW,
    authorization: `Bearer ${signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW)}` },
    body: createPcmWav().toString("base64"), isBase64Encoded: true,
    requestContext: { requestId: "test-command", http: { method: "POST" } } };
  const result = await api.handle(event);
  assert.equal(result.statusCode, 200);
  assert.deepEqual(JSON.parse(result.body ?? ""), { type: "action", action: "set_timer", args: { seconds: 120 } });
  assert.equal(calls, 2);
  assert.equal(usage.commandCalls.length, 1);
  assert.equal(result.headers?.["server-timing"], "groq;dur=0.0, transcription;dur=0.0, classification;dur=0.0");
  invalidArgs = true;
  assert.equal((await api.handle(event)).statusCode, 400);
  fail = true;
  const failed = await api.handle(event);
  assert.equal(failed.statusCode, 503);
  assert.deepEqual(JSON.parse(failed.body ?? ""), { type: "error", reason: "busy" });
  assert.deepEqual(logger.entries, [{ code: "groq_transcription_http_404", requestId: "test-command" }]);
  usage.commandAllowed = false;
  const before = calls;
  assert.equal((await api.handle(event)).statusCode, 429);
  assert.equal(calls, before);
});

test("the API forwards provider rate-limit cooldowns using its existing command error contract", async () => {
  const provider = new GroqProvider(CONFIG, async () => new Response("private rate limit", { status: 429,
    headers: { "retry-after": "12" } }), new Runtime());
  const api = createApi({ assistant: provider, usage: new MockUsage(), secrets: new StaticSecrets(), logger: new RecordingLogger(), clock: new StaticClock() });
  const result = await api.handle({ rawPath: "/v1/command", headers: { "content-type": "application/json", "x-now": NOW,
    authorization: `Bearer ${signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW)}` },
    body: JSON.stringify({ text: INPUT.text }), requestContext: { http: { method: "POST" } } });
  assert.equal(result.statusCode, 429);
  assert.equal(result.headers?.["retry-after"], "12");
  assert.deepEqual(JSON.parse(result.body ?? ""), { type: "error", reason: "busy" });
});
