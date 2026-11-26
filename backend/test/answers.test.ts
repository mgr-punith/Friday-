import assert from "node:assert/strict";
import test from "node:test";
import { localWeatherClarification, needsCurrentInformation, parseReport, publicSourceUrl, readBrowserAnswer, readKnowledgeAnswer,
  RedisAnswerReportStore, signAnswer, validateAnswer, type AnswerContent } from "../src/core/answers";
import { safeAiUsage } from "../src/core/ai-usage";
import { TEST_NOW, TEST_SECRETS } from "./helpers";

const KNOWLEDGE: AnswerContent = { kind: "knowledge", text: "Sachin Tendulkar is a former Indian cricketer.", sources: [] };
const NOW = "2026-11-25T14:00:00+05:30";
function browser(content: unknown, tools: unknown = [{ type: "browser_search", browser_results: [
  { title: "Match report", url: "https://www.example.com/sport/report" }
] }]) {
  return { choices: [{ finish_reason: "stop", message: { content: JSON.stringify(content), executed_tools: tools } }] };
}

test("stable knowledge is bounded and cannot contain executable arguments or invented sources", () => {
  assert.deepEqual(readKnowledgeAnswer({ mode: "knowledge", text: KNOWLEDGE.text }), KNOWLEDGE);
  assert.equal(readKnowledgeAnswer({ mode: "lookup", text: "" }), null);
  assert.equal(readKnowledgeAnswer({ mode: "knowledge", text: KNOWLEDGE.text, action: "call_number" }), null);
  for (const text of ["", " ", "a".repeat(601), "word ".repeat(81), "one\ntwo"]) {
    assert.equal(validateAnswer({ ...KNOWLEDGE, text }), null);
  }
  assert.equal(validateAnswer({ ...KNOWLEDGE, sources: [{ title: "Fake", url: "https://example.com" }] }), null);
  assert.equal(validateAnswer({ kind: "information", text: "Result", sources: [] }), null);
});

test("time-sensitive routing overrides a memory answer but allows explicit historical questions", () => {
  for (const question of ["What happened in todays India match?", "India match result", "What is the weather?",
    "Who is the prime minister?", "What is Sachin's age?", "Latest news", "Who won in 2026?"]) {
    assert.equal(needsCurrentInformation(question, "A remembered answer.", NOW), true, question);
  }
  for (const question of ["Who is Sachin?", "Who was Gandhi?", "Who won the 2011 cricket match?", "Who was president in 2001?"]) {
    assert.equal(needsCurrentInformation(question, "A historical fact.", NOW), false, question);
  }
  assert.equal(needsCurrentInformation("Who is Modi?", "Narendra Modi is the prime minister of India.", NOW), true);
  assert.equal(needsCurrentInformation("Who is Modi?", "Narendra Modi became prime minister in 2014.", NOW), false);
});

test("weather asks for a city before search when the request has no location", () => {
  assert.deepEqual(localWeatherClarification("What is the weather today?"),
    { kind: "clarification", text: "Which city should I check?", sources: [] });
  assert.equal(localWeatherClarification("Weather forecast in Mumbai today"), null);
  assert.deepEqual(localWeatherClarification("What is the weather for tomorrow?"),
    { kind: "clarification", text: "Which city should I check?", sources: [] });
  assert.deepEqual(localWeatherClarification("What is the weather in next few days?"),
    { kind: "clarification", text: "Which city should I check?", sources: [] });
  assert.equal(localWeatherClarification("What time is it?"), null);
});

test("source URLs accept HTTPS websites and reject device, credential and local links", () => {
  assert.equal(publicSourceUrl("https://example.com/report?a=1"), "https://example.com/report?a=1");
  for (const url of ["http://example.com", "intent://open", "javascript:alert(1)", "file:///data/local",
    "https://user:password@example.com", "https://example.com:444/", "https://127.0.0.1/", "https://[::1]/",
    "https://localhost", "https://service.local", "https://service.internal", "https://example.com\\other",
    "https://example.com/\n", "https://0x7f000001/", "https://2130706433/", "https://localhost./", "https://service.local./"]) {
    assert.equal(publicSourceUrl(url), null, url);
  }
});

test("browser answers use provider tool URLs and deduplicate sources", () => {
  const result = readBrowserAnswer(browser({ kind: "information", text: "The match has finished." }, [
    { type: "browser_search", browser_results: [{ title: "Report", url: "https://example.com/report" },
      { title: "Same", url: "https://example.com/report" }, { title: "Local", url: "https://127.0.0.1" }],
    search_results: { results: [{ title: "Schedule", url: "https://example.org/schedule" }] } }
  ]));
  assert.deepEqual(result?.sources, [{ title: "Report", url: "https://example.com/report" },
    { title: "Schedule", url: "https://example.org/schedule" }]);
  assert.equal(readBrowserAnswer(browser({ kind: "information", text: "Result", sources: ["https://fake.example.com"] })), null);
  assert.equal(readBrowserAnswer(browser({ kind: "information", text: "Result" }, []))?.kind, "unavailable");
  assert.equal(readBrowserAnswer(browser({ kind: "clarification", text: "Which India match do you mean?" }))?.sources.length, 0);
});

test("malformed, truncated and executable browser output cannot become an answer", () => {
  assert.equal(readBrowserAnswer(null), null);
  assert.equal(readBrowserAnswer({ choices: [] }), null);
  const value = browser({ kind: "information", text: "Result" });
  assert.equal(readBrowserAnswer({ choices: [{ ...value.choices[0], finish_reason: "length" }] }), null);
  assert.equal(readBrowserAnswer(browser({ kind: "action", text: "Call someone" })), null);
  assert.equal(readBrowserAnswer(browser({ kind: "information", text: "Result", action: "open_app" })), null);
  assert.equal(readBrowserAnswer({ choices: [{ finish_reason: "stop", message: { content: "not JSON" } }] }), null);
});

test("report signatures reject changed answers, extra fields, expiry and invalid reasons", () => {
  const signed = signAnswer(KNOWLEDGE, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  assert.deepEqual(parseReport({ answer: signed, reason: "incorrect" }, TEST_SECRETS.jwtSigningKey, TEST_NOW),
    { answer: signed, reason: "incorrect" });
  for (const answer of [{ ...signed, text: "Changed" }, { ...signed, kind: "clarification" },
    { ...signed, reportToken: "A".repeat(43) }, { ...signed, question: "Private question" },
    { ...signed, issuedAt: signed.issuedAt + 1 }]) {
    assert.equal(parseReport({ answer, reason: "incorrect" }, TEST_SECRETS.jwtSigningKey, TEST_NOW), null);
  }
  assert.equal(parseReport({ answer: signed, reason: "unknown" }, TEST_SECRETS.jwtSigningKey, TEST_NOW), null);
  assert.equal(parseReport({ answer: signed, reason: "incorrect" }, TEST_SECRETS.jwtSigningKey,
    new Date(TEST_NOW.getTime() + 3_601_000)), null);
});

test("report storage expires in seven days, deduplicates and omits identity and signature", async () => {
  let script = "";
  let keys: string[] = [];
  let args: (number | string)[] = [];
  const store = new RedisAnswerReportStore({ async eval<T>(s: string, k: string[], a: (number | string)[]) {
    script = s; keys = k; args = a; return 1 as T;
  } });
  const signed = signAnswer(KNOWLEDGE, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  await store.save(signed, "incorrect");
  assert.match(script, /'NX', 'EX', 604800/);
  assert.equal(keys[0], `friday:answer-report:v1:${signed.answerId}`);
  const stored = JSON.parse(String(args[0]));
  assert.deepEqual(Object.keys(stored).sort(), ["answerId", "issuedAt", "reason", "sources", "text"]);
  const failed = new RedisAnswerReportStore({ async eval<T>() { return null as T; } });
  await assert.rejects(failed.save(signed, "incorrect"), /answer_report_failed/);
});

test("usage logs retain only allowlisted stages and non-negative numeric counters", () => {
  const value = safeAiUsage({ classification: { inputTokens: 400, outputTokens: 20, cachedTokens: 1_000, text: "private" },
    answer: { inputTokens: -1, outputTokens: Number.NaN, cachedTokens: 2 },
    question: { inputTokens: 1, outputTokens: 1, cachedTokens: 0 } } as never);
  assert.deepEqual(value, { classification: { inputTokens: 400, outputTokens: 20, cachedTokens: 400 },
    answer: { inputTokens: 0, outputTokens: 0, cachedTokens: 0 } });
});
