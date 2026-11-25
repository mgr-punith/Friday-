import assert from "node:assert/strict";
import test from "node:test";
import { functionDeclarationsFor, parseFunctionSelection } from "../src/core/action-schema";
import { AssistantProviderError } from "../src/core/contracts";
import { compactGroqTools, GroqProvider, type GroqRequestRuntime } from "../src/providers/groq";
import { createPcmWav } from "./helpers";
import type { AnswerContext } from "../src/core/answer-continuation";

const CONFIG = { apiKey: "test-development-groq-key", transcriptionModel: "whisper-large-v3-turbo",
  actionModel: "openai/gpt-oss-20b", timeoutMs: 1_000, maxCompletionTokens: 512,
  answersEnabled: true, answerTimeoutMs: 2_000 };
const NOW = "2026-11-25T14:00:00+05:30";
const INPUT = { kind: "text" as const, text: "Who is Sachin?", supportsAnswers: true, actionVersion: 5 as const };
const KNOWLEDGE = { mode: "knowledge", text: "Sachin Tendulkar is a former Indian cricketer. He scored 100 international centuries." };
const ALLOWED = { async authorizeAnswer() { return true; } };
const FOLLOW_UP: AnswerContext = { question: "Who is Gandhi?", turns: [],
  prompt: "Mahatma Gandhi or Indira Gandhi?", remaining: 1, expiresAt: 2_000_000_000 };
class Runtime implements GroqRequestRuntime {
  at = 0;
  waits: number[] = [];
  now() { return this.at; }
  random() { return 0; }
  async wait(ms: number) { this.waits.push(ms); this.at += ms; }
}
function tool(args: unknown = KNOWLEDGE, name = "answer_question") {
  return Response.json({ usage: { prompt_tokens: 800, completion_tokens: 60, prompt_tokens_details: { cached_tokens: 200 } },
    choices: [{ finish_reason: "tool_calls", message: { tool_calls: [
      { type: "function", function: { name, arguments: JSON.stringify(args) } }
    ] } }] });
}
function searched() {
  return Response.json({ usage: { prompt_tokens: 600, completion_tokens: 110 }, choices: [{ finish_reason: "stop", message: {
    content: JSON.stringify({ kind: "information", text: "Preview match information from a test source." }),
    executed_tools: [{ type: "browser_search", browser_results: [{ title: "Match", url: "https://example.com/match" }] }]
  } }] });
}

test("stable answers reuse classification without a second completion or search quota", async () => {
  let calls = 0;
  const provider = new GroqProvider(CONFIG, async (_url, init) => {
    calls++;
    const body = JSON.parse(String(init?.body));
    assert.equal(body.max_completion_tokens, 512);
    assert.equal(body.reasoning_effort, "low");
    assert.equal(body.include_reasoning, false);
    assert.equal(body.tools.some((t: any) => t.function?.name === "answer_question"), true);
    assert.equal(body.messages[0].content.includes("Never answer questions"), false);
    assert.equal(body.tools.find((t: any) => t.function?.name === "unsupported").function.description.includes("questions"), false);
    return tool();
  }, new Runtime());
  const result = await provider.select(INPUT, NOW, { async authorizeAnswer() { throw new Error("unexpected_search"); } });
  assert.equal(calls, 1);
  assert.equal(result.answer?.kind, "knowledge");
  assert.deepEqual(result.usage, { classification: { inputTokens: 800, outputTokens: 60, cachedTokens: 200 } });
});

test("clarification replies use the original question and a small answer-only tool set", async () => {
  let calls = 0;
  const provider = new GroqProvider(CONFIG, async (_url, init) => {
    calls++;
    const body = JSON.parse(String(init?.body));
    assert.equal(body.tools.length, 1);
    assert.equal(body.tools[0].function.name, "answer_question");
    assert.match(body.messages[1].content, /Who is Gandhi\?/);
    assert.match(body.messages[1].content, /Mahatma Gandhi/);
    assert.ok(Buffer.byteLength(JSON.stringify(body.tools)) < 1_500);
    return tool({ mode: "knowledge", text: "Mahatma Gandhi led a nonviolent movement for Indian independence." });
  }, new Runtime());
  const result = await provider.select({ ...INPUT, text: "Mahatma Gandhi", answerContext: FOLLOW_UP }, NOW, ALLOWED);
  assert.equal(result.answer?.kind, "knowledge");
  assert.equal(calls, 1);
});

test("freshness uses the original question and search receives the clarification reply", async () => {
  let calls = 0;
  let searches = 0;
  const provider = new GroqProvider(CONFIG, async (_url, init) => {
    const body = JSON.parse(String(init?.body));
    if (++calls === 1) return tool();
    assert.match(body.messages[1].content, /India match today/);
    assert.match(body.messages[1].content, /Cricket/);
    return searched();
  }, new Runtime());
  const result = await provider.select({ ...INPUT, text: "Cricket", answerContext: {
    ...FOLLOW_UP, question: "India match today", prompt: "Which sport?",
  } }, NOW, { async authorizeAnswer() { searches++; return true; } });
  assert.equal(result.answer?.kind, "information");
  assert.equal(calls, 2);
  assert.equal(searches, 1);
});

test("follow-up commands cannot bypass the selector or execute a phone action", async () => {
  for (const text of ["Pause music", "Call Mom", "Turn on the torch"]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return tool({ operation: "pause" }, "control_media"); }, new Runtime());
    await assert.rejects(provider.select({ ...INPUT, text, answerContext: FOLLOW_UP }, NOW, ALLOWED), AssistantProviderError);
    assert.equal(calls, 1);
  }
});

test("the second reply exhausts clarification and a searched answer cannot reopen the loop", async () => {
  const provider = new GroqProvider(CONFIG, async (_url, init) => {
    const body = JSON.parse(String(init?.body));
    assert.equal(body.tools[0].function.parameters.properties.mode.enum.includes("clarification"), false);
    return tool({ mode: "clarification", text: "Which person?" });
  }, new Runtime());
  const result = await provider.select({ ...INPUT, text: "The other person", answerContext: {
    ...FOLLOW_UP, turns: [{ prompt: FOLLOW_UP.prompt, reply: "The politician" }], remaining: 0,
  } }, NOW, ALLOWED);
  assert.equal(result.answer?.kind, "unavailable");
});

test("an audio knowledge answer makes one transcription and one completion", async () => {
  const urls: string[] = [];
  const provider = new GroqProvider(CONFIG, async (url) => {
    urls.push(String(url));
    return String(url).endsWith("transcriptions") ? Response.json({ text: INPUT.text,
      segments: [{ avg_logprob: -0.1, no_speech_prob: 0.01 }] }) : tool();
  }, new Runtime());
  const result = await provider.select({ kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav", supportsAnswers: true }, NOW, ALLOWED);
  assert.equal(result.answer?.text, KNOWLEDGE.text);
  assert.equal(urls.length, 2);
});

test("live questions use one bounded browser completion after the search quota", async () => {
  const order: string[] = [];
  const provider = new GroqProvider(CONFIG, async (_url, init) => {
    const body = JSON.parse(String(init?.body));
    if (body.tools[0].type === "browser_search") {
      order.push("browser");
      assert.equal(body.tool_choice, "required");
      assert.equal(body.max_completion_tokens, 2_048);
      assert.equal(body.model, CONFIG.actionModel);
      assert.equal(body.messages[1].content, `Current phone time: ${NOW}\nQuestion: What happened in todays India match?`);
      assert.equal(body.tools.length, 1);
      return searched();
    }
    order.push("selector");
    return tool({ mode: "lookup", text: "" });
  }, new Runtime());
  const result = await provider.select({ ...INPUT, text: "What happened in todays India match?" }, NOW,
    { async authorizeAnswer() { order.push("quota"); return true; } });
  assert.deepEqual(order, ["selector", "quota", "browser"]);
  assert.equal(result.answer?.kind, "information");
  assert.equal(result.answer?.sources.length, 1);
  assert.equal(result.usage?.answer?.outputTokens, 110);
});

test("server freshness guard sends a mistaken memory answer for current facts through search", async () => {
  for (const text of ["India match result today", "What is the weather?", "Who is the current prime minister?"]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => ++calls === 1 ? tool() : searched(), new Runtime());
    assert.equal((await provider.select({ ...INPUT, text }, NOW, ALLOWED)).answer?.kind, "information");
    assert.equal(calls, 2);
  }
});

test("a selector's lookup text never replaces the original question", async () => {
  for (const args of [{ mode: "lookup", text: "A model-rewritten query" }, { mode: "lookup" }]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async (_url, init) => {
      calls++;
      if (calls === 1) return tool(args);
      assert.match(JSON.parse(String(init?.body)).messages[1].content, /Question: Who is Sachin\?/);
      return searched();
    }, new Runtime());
    assert.equal((await provider.select(INPUT, NOW, ALLOWED)).answer?.kind, "information");
    assert.equal(calls, 2);
  }
});

test("browser metadata has a separate bounded allowance from the tiny answer sent to the phone", async () => {
  for (const size of [70_000, 270_000]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => {
      if (++calls === 1) return tool({ mode: "lookup", text: "" });
      return Response.json({ choices: [{ finish_reason: "stop", message: {
        content: JSON.stringify({ kind: "information", text: "A short result." }),
        executed_tools: [{ type: "browser_search", browser_results: [
          { title: "Report", url: "https://example.com/report", content: "x".repeat(size) }
        ] }]
      } }] });
    }, new Runtime());
    if (size < 262_144) assert.equal((await provider.select(INPUT, NOW, ALLOWED)).answer?.text, "A short result.");
    else await assert.rejects(provider.select(INPUT, NOW, ALLOWED), AssistantProviderError);
    assert.equal(calls, 2);
  }
});

test("clarification and unknown answers do not consume search", async () => {
  for (const mode of ["clarification", "unavailable"]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return tool({ mode, text: "Which Gandhi do you mean?" }); }, new Runtime());
    const result = await provider.select(INPUT, NOW, { async authorizeAnswer() { throw new Error("unexpected_search"); } });
    assert.equal(result.answer?.kind, mode);
    assert.equal(calls, 1);
  }
});

test("search quota denial and storage failure prevent all browser requests", async () => {
  for (const fail of [false, true]) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async () => { calls++; return tool({ mode: "lookup", text: "" }); }, new Runtime());
    await assert.rejects(provider.select(INPUT, NOW, { async authorizeAnswer() {
      if (fail) throw new Error("store_unavailable");
      return false;
    } }));
    assert.equal(calls, 1);
  }
});

test("old clients, disabled servers and missing quota context cannot select answers", async () => {
  for (const [enabled, optIn, hasContext] of [[false, true, true], [true, false, true], [true, true, false]] as const) {
    const provider = new GroqProvider({ ...CONFIG, answersEnabled: enabled }, async (_url, init) => {
      const body = JSON.parse(String(init?.body));
      assert.equal(body.tools.some((t: any) => t.function?.name === "answer_question"), false);
      return tool();
    }, new Runtime());
    await assert.rejects(provider.select({ ...INPUT, supportsAnswers: optIn }, NOW, hasContext ? ALLOWED : undefined), AssistantProviderError);
  }
});

test("answer setup uses the existing selection without an answer or search", async () => {
  for (const reason of ["answers_disabled", "answers_unavailable"] as const) {
    let calls = 0;
    const provider = new GroqProvider(CONFIG, async (_url, init) => {
      calls++;
      const body = JSON.parse(String(init?.body));
      assert.equal(body.tools.some((t: any) => t.function?.name === "answer_question"), false);
      assert.equal(body.tools.some((t: any) => t.function?.name === "short_answers_required"), true);
      assert.match(body.messages[0].content, /Do not answer or search/);
      return tool({}, "short_answers_required");
    }, new Runtime());
    const result = await provider.select({ ...INPUT, supportsAnswers: false, answerSetup: reason }, NOW,
      { async authorizeAnswer() { throw new Error("unexpected_search"); } });
    assert.equal(result.selection.name, "short_answers_required");
    assert.equal(result.answer, undefined);
    assert.equal(calls, 1);
    await provider.select({ ...INPUT, text: "Pause music", supportsAnswers: false, answerSetup: reason }, NOW);
    assert.equal(calls, 1);
  }
});

test("exact commands skip the selector but never bypass action version, negation or whole-request checks", async () => {
  let calls = 0;
  const provider = new GroqProvider(CONFIG, async () => { calls++; return tool({}, "unsupported"); }, new Runtime());
  for (const text of ["Pause music", "Next song", "Turn on the torch", "Play music"]) {
    const result = await provider.select({ ...INPUT, text }, NOW, ALLOWED);
    assert.equal(result.answer, undefined);
    assert.notEqual(result.selection.name, "unsupported");
  }
  assert.equal(calls, 0);
  for (const text of ["Don't pause music", "Pause music and call Mom", "Tell me how to turn on the torch", "Can you tell me who is Modi?"]) {
    await provider.select({ ...INPUT, text }, NOW, ALLOWED);
  }
  await provider.select({ ...INPUT, text: "Pause music", actionVersion: 4 }, NOW, ALLOWED);
  assert.equal(calls, 5);
});

test("one retry allowance spans selection and search and never retranscribes", async () => {
  let calls = 0;
  const runtime = new Runtime();
  const provider = new GroqProvider(CONFIG, async () => {
    calls++;
    return calls === 1 || calls === 3 ? new Response("unavailable", { status: 503 }) : tool({ mode: "lookup", text: "" });
  }, runtime);
  await assert.rejects(provider.select(INPUT, NOW, ALLOWED), AssistantProviderError);
  assert.equal(calls, 3);
  assert.deepEqual(runtime.waits, [200]);
});

test("answer deadline includes time already spent selecting and rejects late responses", async () => {
  let calls = 0;
  const runtime = new Runtime();
  const provider = new GroqProvider(CONFIG, async () => {
    calls++;
    runtime.at = calls === 1 ? 900 : 2_001;
    return calls === 1 ? tool({ mode: "lookup", text: "" }) : searched();
  }, runtime);
  await assert.rejects(provider.select(INPUT, NOW, ALLOWED), (error: unknown) => {
    assert.ok(error instanceof AssistantProviderError);
    assert.equal(error.code, "timeout");
    assert.equal(error.failure?.usage?.classification?.inputTokens, 800);
    return true;
  });
  assert.equal(calls, 2);
});

test("compact sequence declarations reduce request bytes while strict action validation remains", () => {
  const before = Buffer.byteLength(JSON.stringify(functionDeclarationsFor(5)));
  const after = Buffer.byteLength(JSON.stringify(compactGroqTools(5)));
  assert.ok(after < before * 0.75, `${after} bytes vs ${before} bytes`);
  assert.equal(parseFunctionSelection({ name: "run_actions", args: { actions: [
    { action: "flashlight", args: { on: true, extra: "bad" } }, { action: "open_app", args: { name: "Clock" } }
  ] } }, 5).type, "error");
  assert.equal(parseFunctionSelection({ name: "run_actions", args: { actions: [
    { action: "flashlight", args: { on: true } }, { action: "open_app", args: { name: "Clock" } }
  ] } }, 5).type, "actions");
});
