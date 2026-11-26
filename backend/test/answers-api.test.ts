import assert from "node:assert/strict";
import test from "node:test";
import { createApi, type FunctionUrlEvent } from "../src/core/api";
import { parseReport, signAnswer, type AnswerResponse } from "../src/core/answers";
import { signInstallToken } from "../src/core/jwt";
import { readAnswerContinuation } from "../src/core/answer-continuation";
import { MockAssistant, MockUsage, RecordingLogger, StaticClock, StaticSecrets, TEST_INSTALL_ID,
  TEST_NOW, TEST_SECRETS } from "./helpers";
const KNOWLEDGE = { kind: "knowledge" as const, text: "Sachin Tendulkar is a former Indian cricketer.", sources: [] };
function harness(enabled = true) {
  const assistant = new MockAssistant();
  assistant.result = { selection: { name: "answer_question", args: { mode: "knowledge", text: KNOWLEDGE.text } },
    answer: KNOWLEDGE, durationMs: 12, timingMetric: "groq", stages: { classification: 12 } };
  const usage = new MockUsage();
  const answerUsage = new MockUsage();
  const logger = new RecordingLogger();
  const reports: { answer: AnswerResponse; reason: string }[] = [];
  let failReports = false;
  const api = createApi({ assistant, usage, secrets: new StaticSecrets(), clock: new StaticClock(), logger,
    ...(enabled ? { answers: { usage: answerUsage, reports: { async save(answer: AnswerResponse, reason: string) {
      if (failReports) throw new Error("private storage detail");
      reports.push({ answer, reason });
    } } } } : {}) });
  return { api, assistant, usage, answerUsage, reports, logger, failReports() { failReports = true; } };
}
function event(path = "/v1/command", payload: unknown = { text: "Who is Sachin?" }): FunctionUrlEvent {
  return { rawPath: path, headers: { "content-type": "application/json", "x-now": "2026-11-17T14:00:00+05:30",
    "x-friday-answers": "1", authorization: "Bearer " + signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW) },
  requestContext: { http: { method: "POST" } }, body: JSON.stringify(payload) };
}

test("authenticated opted-in clients receive a signed bounded answer rather than an action", async () => {
  const h = harness();
  const response = await h.api.handle(event());
  assert.equal(response.statusCode, 200);
  const answer = JSON.parse(response.body ?? "null");
  assert.equal(answer.type, "answer");
  assert.equal(answer.action, undefined);
  assert.equal(h.assistant.calls[0]?.input.supportsAnswers, true);
  assert.equal(h.usage.commandCalls.length, 1);
  assert.equal(h.answerUsage.commandCalls.length, 0);
  assert.ok(parseReport({ answer, reason: "incorrect" }, TEST_SECRETS.jwtSigningKey, TEST_NOW));
  assert.match(response.headers?.["server-timing"] ?? "", /classification;dur=12/);
});

test("answer setup distinguishes disabled client consent from unavailable server answers", async () => {
  for (const enabled of [false, true]) {
    for (const optedIn of [false, true]) {
      const h = harness(enabled);
      const request = event();
      const headers = { ...request.headers, "x-friday-answers": optedIn ? "2" : undefined,
        "x-friday-answer-setup": "1" };
      h.assistant.result = { selection: { name: "short_answers_required", args: {} }, durationMs: 10 };
      const response = await h.api.handle({ ...request, headers });
      if (enabled && optedIn) {
        assert.equal(response.statusCode, 503);
        assert.deepEqual(h.logger.entries, [{ code: "assistant_invalid_answer_setup" }]);
      } else {
        assert.equal(response.statusCode, 200);
        assert.deepEqual(JSON.parse(response.body!), { type: "unsupported",
          reason: optedIn ? "answers_unavailable" : "answers_disabled" });
      }
      assert.equal(h.assistant.calls[0]?.input.answerSetup,
        enabled && optedIn ? undefined : optedIn ? "answers_unavailable" : "answers_disabled");
      assert.equal(h.answerUsage.commandCalls.length, 0);
      assert.equal(h.usage.commandCalls.length, 1);
    }
  }
});

test("setup hints cannot bypass opt-in, validation or quotas and preserve unsupported commands", async () => {
  const h = harness();
  const request = event();
  h.assistant.result = { selection: { name: "short_answers_required", args: {} }, durationMs: 10 };
  assert.equal((await h.api.handle(request)).statusCode, 503);
  const setup = { ...request, headers: { ...request.headers, "x-friday-answers": undefined, "x-friday-answer-setup": "1" } };
  h.assistant.result = { selection: { name: "short_answers_required", args: { text: "Unrequested answer" } }, durationMs: 10 };
  assert.equal((await h.api.handle(setup)).statusCode, 503);
  h.assistant.result = { selection: { name: "unsupported", args: {} }, durationMs: 10 };
  assert.deepEqual(JSON.parse((await h.api.handle(setup)).body!), { type: "unsupported" });
  h.usage.commandAllowed = false;
  const before = h.assistant.calls.length;
  assert.equal((await h.api.handle(setup)).statusCode, 429);
  assert.equal(h.assistant.calls.length, before);
});

test("version two supports two account-bound clarification replies and then stops", async () => {
  const h = harness();
  const base = event();
  const request = { ...base, headers: { ...base.headers, "x-friday-answers": "2" } };
  const setClarification = (question: string) => {
    h.assistant.result = { selection: { name: "answer_question", args: {} }, question,
      answer: { kind: "clarification", text: "Which Gandhi do you mean?", sources: [] }, durationMs: 10 };
  };
  setClarification("Who is Gandhi?");
  const first = JSON.parse((await h.api.handle(request)).body!);
  assert.equal(first.kind, "clarification");
  const context = readAnswerContinuation(first.continuation, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW)!;
  assert.equal(context.question, "Who is Gandhi?");
  setClarification("The prime minister");
  const reply = { ...request, body: JSON.stringify({ text: "The prime minister" }),
    headers: { ...request.headers, "x-friday-answer-context": first.continuation } };
  const second = JSON.parse((await h.api.handle(reply)).body!);
  assert.ok(second.continuation);
  assert.deepEqual(h.assistant.calls[1]?.input.answerContext, context);
  setClarification("The other one");
  const last = JSON.parse((await h.api.handle({ ...reply,
    headers: { ...reply.headers, "x-friday-answer-context": second.continuation } })).body!);
  assert.equal(last.kind, "unavailable");
  assert.equal(last.continuation, undefined);
  assert.equal(h.usage.commandCalls.length, 3);
  assert.equal(h.answerUsage.commandCalls.length, 0);
});

test("invalid continuations, disabled answers and old clients cannot start a follow-up", async () => {
  for (const version of ["0", "1", "2"]) {
    const h = harness();
    const request = event();
    const response = await h.api.handle({ ...request,
      headers: { ...request.headers, "x-friday-answers": version, "x-friday-answer-context": "x".repeat(80) } });
    assert.equal(response.statusCode, 400);
    assert.equal(h.assistant.calls.length, 0);
    assert.equal(h.usage.commandCalls.length, 0);
  }
});

test("follow-ups cannot execute actions and search results cannot start another clarification", async () => {
  const h = harness();
  const base = event();
  const request = { ...base, headers: { ...base.headers, "x-friday-answers": "2" } };
  h.assistant.result = { selection: { name: "answer_question", args: {} }, question: "Which match?",
    answer: { kind: "clarification", text: "Which sport?", sources: [] }, durationMs: 10 };
  const first = JSON.parse((await h.api.handle(request)).body!);
  h.assistant.result = { selection: { name: "call_number", args: { number: "12345" } }, durationMs: 10 };
  const rejected = await h.api.handle({ ...request,
    headers: { ...request.headers, "x-friday-answer-context": first.continuation } });
  assert.equal(rejected.statusCode, 503);
  assert.deepEqual(h.logger.entries, [{ code: "assistant_invalid_answer_reply" }]);
  h.assistant.result = { selection: { name: "answer_question", args: {} }, question: "Which match?",
    answer: { kind: "clarification", text: "Which match?", sources: [] }, durationMs: 10, stages: { answer: 5 } };
  const searched = JSON.parse((await h.api.handle(request)).body!);
  assert.equal(searched.kind, "unavailable");
  assert.equal(searched.continuation, undefined);
});

test("disabled and non-opted-in clients never receive free text from an unexpected provider answer", async () => {
  for (const enabled of [true, false]) {
    const h = harness(enabled);
    const request = event();
    const response = await h.api.handle(enabled ? { ...request, headers: { ...request.headers, "x-friday-answers": "0" } } : request);
    assert.equal(response.statusCode, 503);
    assert.equal(h.assistant.calls[0]?.input.supportsAnswers, undefined);
    assert.deepEqual(JSON.parse(response.body ?? "null"), { type: "error", reason: "busy" });
    assert.deepEqual(h.logger.entries, [{ code: "assistant_invalid_answer" }]);
  }
});

test("authentication, command quota and quota store failure stop all provider work", async () => {
  for (const failure of ["auth", "quota", "store"]) {
    const h = harness();
    let request = event();
    if (failure === "auth") request = { ...request, headers: { ...request.headers, authorization: "Bearer invalid" } };
    if (failure === "quota") h.usage.commandAllowed = false;
    if (failure === "store") h.usage.consumeCommand = async () => { throw new Error("private store detail"); };
    const response = await h.api.handle(request);
    assert.equal(response.statusCode, failure === "auth" ? 401 : failure === "quota" ? 429 : 503);
    assert.equal(h.assistant.calls.length, 0);
    assert.equal(h.answerUsage.commandCalls.length, 0);
  }
});

test("answer reports authenticate and verify the signed answer before storing", async () => {
  const h = harness();
  const answer = signAnswer(KNOWLEDGE, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  const request = event("/v1/answers/report", { answer, reason: "incorrect" });
  const missingAuth = await h.api.handle({ ...request, headers: { "content-type": "application/json" } });
  assert.equal(missingAuth.statusCode, 401);
  assert.equal((await h.api.handle(event("/v1/answers/report", { answer: { ...answer, text: "Changed" }, reason: "incorrect" }))).statusCode, 400);
  assert.equal((await h.api.handle(event("/v1/answers/report", { answer, reason: "invalid" }))).statusCode, 400);
  assert.equal(h.reports.length, 0);
  assert.equal((await h.api.handle(request)).statusCode, 204);
  assert.deepEqual(h.reports, [{ answer, reason: "incorrect" }]);
  assert.equal(h.usage.commandCalls.length, 1);
});

test("report limits, oversized input and storage errors return failure without a false confirmation", async () => {
  const h = harness();
  const answer = signAnswer(KNOWLEDGE, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  const request = event("/v1/answers/report", { answer, reason: "incorrect" });
  assert.equal((await h.api.handle({ ...request, body: " ".repeat(16_385) })).statusCode, 400);
  h.usage.commandAllowed = false;
  assert.equal((await h.api.handle(request)).statusCode, 429);
  h.usage.commandAllowed = true;
  h.failReports();
  assert.equal((await h.api.handle(request)).statusCode, 503);
  assert.equal(h.reports.length, 0);
  assert.equal(JSON.stringify(h.logger.entries).includes("private"), false);
  assert.equal((await harness(false).api.handle(request)).statusCode, 404);
});
