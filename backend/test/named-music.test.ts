import assert from "node:assert/strict";
import test from "node:test";
import { functionDeclarationsFor, parseFunctionSelection } from "../src/core/action-schema";
import { createApi } from "../src/core/api";
import type { CommandInput } from "../src/core/contracts";
import { signInstallToken } from "../src/core/jwt";
import { GroqProvider } from "../src/providers/groq";
import { createPcmWav, MockUsage, RecordingLogger, StaticClock, StaticSecrets, TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

const CONFIG = {
  apiKey: "groq-development-test-key",
  transcriptionModel: "whisper-large-v3-turbo",
  actionModel: "openai/gpt-oss-20b",
  timeoutMs: 1_000,
  maxCompletionTokens: 512
};
const NOW = "2026-11-25T21:30:00+05:30";
const unsupported = () => Response.json({ choices: [{ finish_reason: "tool_calls", message: {
  tool_calls: [{ type: "function", function: { name: "unsupported", arguments: "{}" } }]
} }] });
const transcript = (text: string) => Response.json({ text,
  segments: [{ avg_logprob: -0.2, no_speech_prob: 0.02, compression_ratio: 1.2 }] });

test("explicit named-player requests preserve the song or artist when the selector would reject them", async () => {
  const cases = [
    ["Play Believer on YouTube", "Believer", "YouTube"],
    ["Play Yo Yo Honey Singh on YouTube.", "Yo Yo Honey Singh", "YouTube"],
    ["Please play Believer by Imagine Dragons on YouTube.", "Believer by Imagine Dragons", "YouTube"],
    ["Could you play Believer in YouTube, please?", "Believer", "YouTube"],
    ["Play Believer using You Tube", "Believer", "YouTube"],
    ["Play Believer on YouTube Music", "Believer", "YouTube Music"],
    ["Play Believer on Spotify", "Believer", "Spotify"],
    ["Play the song Believer on YouTube", "Believer", "YouTube"],
    ["Play Summer of '69 on YouTube", "Summer of '69", "YouTube"],
    [`Play ${"x".repeat(160)} on YouTube`, "x".repeat(160), "YouTube"]
  ] as const;
  for (const [text, query, appName] of cases) for (const version of [4, 5] as const) {
    for (const kind of ["text", "audio"] as const) {
      let calls = 0;
      const provider = new GroqProvider(CONFIG, async url => {
        calls++;
        return String(url).endsWith("/transcriptions") ? transcript(text) : unsupported();
      });
      const input: CommandInput = kind === "text" ? { kind, text, actionVersion: version }
        : { kind, bytes: createPcmWav(), mimeType: "audio/wav", actionVersion: version };
      const result = await provider.select(input, NOW);
      assert.deepEqual(parseFunctionSelection(result.selection, version), {
        type: "action", action: "play_media", args: { query, appName }
      }, `${version}: ${kind}: ${text}`);
      assert.equal(calls, kind === "audio" ? 1 : 0);
    }
  }
});

test("named music shortcuts keep compound, conditional, negated and unsupported requests in selection", async () => {
  for (const text of [
    "Don't play Believer on YouTube", "Do not play Believer on YouTube",
    "Never play Believer on YouTube", "Can you not play Believer on YouTube?",
    "Play Believer on YouTube and call Mom", "Turn on the flashlight then play Believer on YouTube",
    "Play Believer and turn on the flashlight on YouTube", "Play Believer; call Mom on YouTube",
    "Play Believer. Call Mom on YouTube", "Play Believer if I am home on YouTube",
    "Play Believer but not on YouTube", "Play Believer instead of calling Mom on YouTube",
    "Play Believer, then set an alarm on YouTube", "Play Believer & call Mom on YouTube",
    "Play Believer or Radioactive on YouTube", "Play Believer on YouTube or Spotify",
    "Play anything except Believer on YouTube", "Play not Believer on YouTube",
    "Play anything without Believer on YouTube", "Play never Believer on YouTube",
    "Play Believer on an unsupported player", "Play on YouTube",
    `Play ${"x".repeat(161)} on YouTube`, "Play Believer\u0000 on YouTube",
    "Play Believer\ncall Mom on YouTube", "Play Believer\u0085 on YouTube"
  ]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return unsupported(); });
    const result = await provider.select({ kind: "text", text, actionVersion: 5 }, NOW);
    assert.equal(result.selection.name, "unsupported", text);
    assert.equal(calls, 1, text);
  }
  for (const actionVersion of [1, 2, 3] as const) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return unsupported(); });
    assert.equal((await provider.select({ kind: "text", text: "Play Believer on YouTube", actionVersion }, NOW)).selection.name, "unsupported");
    assert.equal(calls, 1);
  }
});

test("titles with conjunctions, punctuation or negation keep their original query through selection", async () => {
  for (const query of ["Rock and Roll", "Mr. Brightside", "I'm Not the Only One"]) {
    let calls = 0;
    const text = `Play ${query} on YouTube`;
    const provider = new GroqProvider(CONFIG, async (_url, init) => {
      calls++;
      const body = JSON.parse(String(init?.body));
      assert.ok(body.messages[1].content.endsWith(`Command: ${text}`));
      if (/\b(?:and|not)\b/i.test(query)) assert.match(body.messages[0].content, /Play Believer on YouTube/);
      else assert.match(body.messages[0].content, /Keep named song and artist words exactly/);
      assert.doesNotMatch(body.messages[0].content, /For audio|silence, noise, music/);
      const unclear = body.tools.find((entry: { function: { name: string } }) => entry.function.name === "unclear_command");
      assert.match(unclear.function.description, /transcript/);
      assert.doesNotMatch(unclear.function.description, /audio|silence, noise, music/);
      return Response.json({ choices: [{ finish_reason: "tool_calls", message: { tool_calls: [{ type: "function",
        function: { name: "play_media", arguments: JSON.stringify({ query, appName: "YouTube" }) }
      }] } }] });
    });
    assert.deepEqual(parseFunctionSelection((await provider.select({ kind: "text", text, actionVersion: 5 }, NOW)).selection, 5), {
      type: "action", action: "play_media", args: { query, appName: "YouTube" }
    });
    assert.equal(calls, 1);
  }
  assert.match(functionDeclarationsFor(5).find(tool => tool.name === "unclear_command")!.description, /audio/);
});

test("the NVIDIA transcript uses the same named music path without another provider request", async () => {
  let transcriptions = 0;
  const provider = new GroqProvider(CONFIG, async () => { throw new Error("unexpected_selector"); }, undefined, {
    async transcribe() { transcriptions++; return "Play Believer on YouTube"; }
  });
  const result = await provider.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav", actionVersion: 5 }, NOW);
  assert.deepEqual(parseFunctionSelection(result.selection, 5), {
    type: "action", action: "play_media", args: { query: "Believer", appName: "YouTube" }
  });
  assert.equal(transcriptions, 1);
});

test("authentication and account quota still precede named music transcription and routing", async () => {
  let transcriptions = 0;
  const provider = new GroqProvider(CONFIG, async () => { transcriptions++; return transcript("Play Believer on YouTube"); });
  const usage = new MockUsage();
  const api = createApi({ assistant: provider, usage, secrets: new StaticSecrets(), logger: new RecordingLogger(), clock: new StaticClock() });
  const event = { rawPath: "/v1/command", requestContext: { http: { method: "POST" } },
    headers: { "content-type": "audio/wav", "x-now": NOW, "x-friday-actions": "4" },
    body: createPcmWav().toString("base64"), isBase64Encoded: true };
  assert.equal((await api.handle(event)).statusCode, 401);
  assert.equal(transcriptions, 0);
  const signedIn = { ...event, headers: { ...event.headers,
    authorization: "Bearer " + signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW) } };
  usage.commandAllowed = false;
  assert.equal((await api.handle(signedIn)).statusCode, 429);
  assert.equal(transcriptions, 0);
  usage.commandAllowed = true;
  const response = await api.handle(signedIn);
  assert.equal(response.statusCode, 200);
  assert.deepEqual(JSON.parse(response.body!), {
    type: "action", action: "play_media", args: { query: "Believer", appName: "YouTube" }
  });
  assert.equal(transcriptions, 1);
  assert.equal(usage.commandCalls.length, 2);
});

test("named music never bypasses speech admission or an answer-only clarification", async () => {
  let calls = 0;
  const provider = new GroqProvider(CONFIG, async () => { calls++; return Response.json({ text: "Play Believer on YouTube",
    segments: [{ avg_logprob: -2, no_speech_prob: 0.8 }] }); });
  const audio = { kind: "audio" as const, bytes: createPcmWav(), mimeType: "audio/wav" as const, actionVersion: 5 as const };
  assert.equal((await provider.select(audio, NOW)).selection.name, "unclear_command");
  assert.equal(calls, 1);

  const following = new GroqProvider({ ...CONFIG, answersEnabled: true }, async (_url, init) => {
    const body = JSON.parse(String(init?.body));
    assert.deepEqual(body.tools.map((entry: { function: { name: string } }) => entry.function.name), ["answer_question"]);
    return Response.json({ choices: [{ finish_reason: "tool_calls", message: { tool_calls: [{ type: "function",
      function: { name: "answer_question", arguments: JSON.stringify({ mode: "unavailable", text: "Please start a new command." }) }
    }] } }] });
  });
  const result = await following.select({ kind: "text", text: "Play Believer on YouTube", actionVersion: 5, supportsAnswers: true,
    answerContext: { question: "Which song won?", prompt: "Which event?", turns: [], remaining: 1, expiresAt: 1_000 } }, NOW,
  { async authorizeAnswer() { throw new Error("unexpected_search"); } });
  assert.equal(result.selection.name, "answer_question");
  assert.equal(result.answer?.kind, "unavailable");
});
