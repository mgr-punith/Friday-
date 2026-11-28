import assert from "node:assert/strict";
import test from "node:test";
import type { AccountGateway } from "../src/auth/api";
import { createApi, type FunctionUrlEvent } from "../src/core/api";
import { signAnswer, type AnswerResponse } from "../src/core/answers";
import { signInstallToken } from "../src/core/jwt";
import { RedisSpeechUsage, readSpeechRequest, speechToken } from "../src/core/speech";
import { createPcmWav, MockAssistant, MockUsage, RecordingLogger, StaticClock, StaticSecrets,
  TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

const CONTENT = { kind: "knowledge" as const, text: "Sachin Tendulkar is a former Indian cricketer.", sources: [] };
function request(answer = signAnswer(CONTENT, TEST_SECRETS.jwtSigningKey, TEST_NOW), identity = TEST_INSTALL_ID,
  capability = speechToken(answer, identity, TEST_SECRETS.jwtSigningKey, "wavenet-v1")): FunctionUrlEvent {
  return { rawPath: "/v1/speech", requestContext: { http: { method: "POST" } }, headers: {
    authorization: "Bearer " + signInstallToken(identity, TEST_SECRETS.jwtSigningKey, TEST_NOW),
    "content-type": "application/json", "x-friday-speech": "wavenet-v1"
  }, body: JSON.stringify({ answer, speechToken: capability }) };
}
function harness(speechEnabled = true, accounts?: AccountGateway, clientVersion: "wavenet-v1" | "elevenlabs-v1" = "wavenet-v1", at = TEST_NOW) {
  const assistant = new MockAssistant();
  assistant.result = { selection: { name: "answer_question", args: {} }, answer: CONTENT, durationMs: 12 };
  const usage = new MockUsage(); const logger = new RecordingLogger();
  let allowed = true; let failReserve = false; let failSpeech = false;
  const reservations: { identity: string; answerId: string; characters: number }[] = [];
  const used = new Set<string>();
  const syntheses: string[] = []; const audio = createPcmWav();
  const api = createApi({ assistant, usage, logger, secrets: new StaticSecrets(), clock: new StaticClock(at),
    answers: { usage: new MockUsage(), reports: { async save() {} } }, ...(accounts ? { accounts } : {}),
    ...(speechEnabled ? { speech: {
      usage: { async reserve(identity, answerId, characters) {
        if (failReserve) throw new Error("private store detail");
        reservations.push({ identity, answerId, characters });
        if (!allowed || used.has(answerId)) return false;
        used.add(answerId); return true;
      } }, provider: { clientVersion, async synthesize(text) {
        syntheses.push(text); if (failSpeech) throw new Error("private provider text and key"); return audio;
      } }
    } } : {}) });
  return { api, assistant, usage, logger, reservations, syntheses, audio,
    deny() { allowed = false; }, failReserve() { failReserve = true; }, failSpeech() { failSpeech = true; } };
}

test("signed answer speech capabilities bind text and issuance to the authenticated account", () => {
  const answer = signAnswer(CONTENT, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  const token = speechToken(answer, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, "wavenet-v1");
  const payload = { answer, speechToken: token };
  assert.deepEqual(readSpeechRequest(payload, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW, "wavenet-v1"), answer);
  assert.equal(readSpeechRequest(payload, "different-account", TEST_SECRETS.jwtSigningKey, TEST_NOW, "wavenet-v1"), null);
  assert.equal(readSpeechRequest(payload, TEST_INSTALL_ID, "different-key", TEST_NOW, "wavenet-v1"), null);
  assert.equal(readSpeechRequest(payload, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, new Date(TEST_NOW.getTime() + 121_000), "wavenet-v1"), null);
  assert.ok(readSpeechRequest(payload, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, new Date(TEST_NOW.getTime() + 120_000), "wavenet-v1"));
  for (const invalid of [null, { text: CONTENT.text }, { ...payload, speechToken: "short" }, { ...payload, extra: true },
    { ...payload, answer: { ...answer, text: "Changed text" } }, { ...payload, answer: { ...answer, reportToken: "a".repeat(43) } },
    { ...payload, answer: { ...answer, issuedAt: answer.issuedAt + 1 } }, { ...payload, answer: { ...answer, continuation: "a".repeat(80) } }]) {
    assert.equal(readSpeechRequest(invalid, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW, "wavenet-v1"), null);
  }
});

test("only clients with speech consent receive speech tokens, and command responses never synthesize", async () => {
  for (const enabled of [true, false]) for (const consent of [true, false]) {
    const h = harness(enabled); const speech = request();
    const command: FunctionUrlEvent = { ...speech, rawPath: "/v1/command", body: JSON.stringify({ text: "Who is Sachin?" }),
      headers: { ...speech.headers, "x-now": "2026-11-17T14:00:00+05:30", "x-friday-answers": "2",
        "x-friday-speech": consent ? "wavenet-v1" : undefined } };
    const response = await h.api.handle(command);
    assert.equal(response.statusCode, 200);
    const answer = JSON.parse(response.body!) as AnswerResponse;
    assert.equal(typeof answer.speechToken === "string", enabled && consent);
    assert.equal(h.syntheses.length, 0); assert.equal(h.reservations.length, 0);
    if (answer.speechToken) assert.equal(answer.speechToken, speechToken(answer, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, "wavenet-v1"));
  }
});

test("authentication and signed-text checks precede quota and billable provider work", async () => {
  const answer = signAnswer(CONTENT, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  const base = request(answer);
  for (const [patch, status] of [
    [{ headers: { ...base.headers, authorization: "Bearer invalid" } }, 401],
    [{ headers: { ...base.headers, "content-type": "text/plain" } }, 400],
    [{ headers: { ...base.headers, "x-friday-speech": undefined } }, 400],
    [{ body: JSON.stringify({ text: CONTENT.text }) }, 400],
    [{ body: "{" }, 400], [{ body: " ".repeat(16_385) }, 400],
    [{ body: JSON.stringify({ answer: { ...answer, text: "Changed" }, speechToken: speechToken(answer, TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, "wavenet-v1") }) }, 400]
  ] as const) {
    const h = harness();
    assert.equal((await h.api.handle({ ...base, ...patch })).statusCode, status);
    assert.equal(h.reservations.length, 0); assert.equal(h.syntheses.length, 0);
  }
});

test("speech capabilities require consent to the configured provider before quota or synthesis", async () => {
  for (const configured of ["wavenet-v1", "elevenlabs-v1"] as const) {
    for (const consent of ["wavenet-v1", "elevenlabs-v1", "unknown", undefined]) {
      const h = harness(true, undefined, configured);
      const base = request();
      const headers = { ...base.headers, "x-friday-speech": consent };
      const command = await h.api.handle({ ...base, rawPath: "/v1/command", headers: {
        ...headers, "x-now": "2026-11-17T14:00:00+05:30", "x-friday-answers": "2"
      }, body: JSON.stringify({ text: "Who is Sachin?" }) });
      assert.equal(command.statusCode, 200);
      const answer = JSON.parse(command.body!) as AnswerResponse;
      assert.equal(typeof answer.speechToken === "string", configured === consent);
      const response = await h.api.handle({ ...base, headers, body: JSON.stringify({
        answer: { ...answer, speechToken: undefined }, speechToken: answer.speechToken ?? "a".repeat(43)
      }) });
      assert.equal(response.statusCode, configured === consent ? 200 : 400);
      assert.equal(h.reservations.length, configured === consent ? 1 : 0);
      assert.equal(h.syntheses.length, configured === consent ? 1 : 0);
    }
  }
});

test("switching providers invalidates outstanding speech tokens", async () => {
  const previous = harness();
  const base = request();
  const response = await previous.api.handle({ ...base, rawPath: "/v1/command", headers: {
    ...base.headers, "x-now": "2026-11-17T14:00:00+05:30", "x-friday-answers": "2"
  }, body: JSON.stringify({ text: "Who is Sachin?" }) });
  const { speechToken: token, ...answer } = JSON.parse(response.body!) as AnswerResponse;
  const current = harness(true, undefined, "elevenlabs-v1");
  const speech = await current.api.handle({ ...base, headers: { ...base.headers, "x-friday-speech": "elevenlabs-v1" },
    body: JSON.stringify({ answer, speechToken: token }) });
  assert.equal(speech.statusCode, 400);
  assert.equal(current.reservations.length, 0);
  assert.equal(current.syntheses.length, 0);
});

test("speech reserves characters once before returning binary WAV with no persistent answer storage", async () => {
  const h = harness(); const answer = signAnswer(CONTENT, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  const response = await h.api.handle(request(answer));
  assert.equal(response.statusCode, 200); assert.equal(response.isBase64Encoded, true);
  assert.equal(response.headers?.["content-type"], "audio/wav");
  assert.equal(response.headers?.["cache-control"], "no-store");
  assert.deepEqual(Buffer.from(response.body!, "base64"), h.audio);
  assert.deepEqual(h.reservations, [{ identity: TEST_INSTALL_ID, answerId: answer.answerId, characters: CONTENT.text.length }]);
  assert.deepEqual(h.syntheses, [CONTENT.text]); assert.equal(h.usage.commandCalls.length, 0);
});

test("revoked account tokens and other accounts cannot reuse a speech capability", async () => {
  let accountId: string | null = "a".repeat(43);
  const accounts: AccountGateway = { async handle() { return { statusCode: 404 }; }, async authorize(token) {
    return token === "account-access-token" && accountId ? { accountId, installId: TEST_INSTALL_ID, sessionId: "b".repeat(43) } : null;
  } };
  const h = harness(true, accounts); const answer = signAnswer(CONTENT, TEST_SECRETS.jwtSigningKey, TEST_NOW);
  const base = request(answer, TEST_INSTALL_ID, speechToken(answer, accountId!, TEST_SECRETS.jwtSigningKey, "wavenet-v1"));
  const signedIn = { ...base, headers: { ...base.headers, authorization: "Bearer account-access-token" } };
  assert.equal((await h.api.handle(signedIn)).statusCode, 200);
  accountId = "c".repeat(43);
  assert.equal((await h.api.handle(signedIn)).statusCode, 400);
  accountId = null;
  assert.equal((await h.api.handle(signedIn)).statusCode, 401);
  assert.equal((await h.api.handle(base)).statusCode, 401, "Account-enabled speech rejects install tokens");
  assert.equal(h.syntheses.length, 1);
});

test("disabled speech, quota denial and store/provider failures return a device-fallback response", async () => {
  assert.equal((await harness(false).api.handle(request())).statusCode, 404);
  const limited = harness(); limited.deny();
  assert.equal((await limited.api.handle(request())).statusCode, 429);
  assert.equal(limited.syntheses.length, 0);
  for (const failure of ["store", "provider"]) {
    const h = harness(); if (failure === "store") h.failReserve(); else h.failSpeech();
    assert.equal((await h.api.handle(request())).statusCode, 503);
    assert.equal(h.syntheses.length, failure === "store" ? 0 : 1);
    assert.equal(JSON.stringify(h.logger.entries).includes("private"), false);
  }
});

test("opted-in phone actions issue short confirmation speech without synthesizing before execution", async () => {
  const h = harness(true, undefined, "elevenlabs-v1");
  h.assistant.result = { selection: { name: "set_ringer_mode", args: { mode: "vibrate", utterance: "Turn on vibrate" } }, durationMs: 12 };
  const base = request();
  const response = await h.api.handle({ ...base, rawPath: "/v1/command", body: JSON.stringify({ text: "Turn on vibrate" }),
    headers: { ...base.headers, "x-now": "2026-11-17T14:00:00+05:30", "x-friday-speech": "elevenlabs-v1", "x-friday-confirmations": "1" } });
  assert.equal(response.statusCode, 200);
  const action = JSON.parse(response.body!);
  assert.equal(action.confirmationSpeech?.text, "Vibrate on");
  assert.deepEqual(h.syntheses, []);
  const speech = await h.api.handle({ ...base, headers: { ...base.headers, "x-friday-speech": "elevenlabs-v1", "x-friday-confirmations": "1" },
    body: JSON.stringify({ confirmation: action.confirmationSpeech }) });
  assert.equal(speech.statusCode, 200);
  assert.deepEqual(h.syntheses, ["Vibrate on"]);
  assert.equal(h.reservations[0]?.characters, 10);
  assert.equal((await h.api.handle({ ...base, headers: { ...base.headers,
    "x-friday-speech": "elevenlabs-v1", "x-friday-confirmations": "1" },
    body: JSON.stringify({ confirmation: action.confirmationSpeech }) })).statusCode, 429);
  assert.deepEqual(h.syntheses, ["Vibrate on"], "Replaying a capability cannot bill a second synthesis");
});

function confirmationCommand(): FunctionUrlEvent {
  const base = request();
  return { ...base, rawPath: "/v1/command", body: JSON.stringify({ text: "Turn on vibrate" }), headers: {
    ...base.headers, "x-now": "2026-11-17T14:00:00+05:30", "x-friday-actions": "6",
    "x-friday-speech": "elevenlabs-v1", "x-friday-confirmations": "1"
  } };
}

test("confirmation speech needs new consent and the ElevenLabs configuration", async () => {
  for (const configured of ["wavenet-v1", "elevenlabs-v1"] as const) for (const consent of [undefined, "0", "1"]) {
    const h = harness(true, undefined, configured);
    h.assistant.result = { selection: { name: "close_app", args: {} }, durationMs: 12 };
    const base = confirmationCommand();
    const response = await h.api.handle({ ...base, headers: { ...base.headers,
      "x-friday-speech": configured, "x-friday-confirmations": consent } });
    assert.equal(response.statusCode, 200);
    assert.equal(Boolean(JSON.parse(response.body!).confirmationSpeech), configured === "elevenlabs-v1" && consent === "1");
    assert.deepEqual(h.syntheses, []); assert.deepEqual(h.reservations, []);
  }
});

test("confirmation phrases omit private names and labels and describe handoffs as requests", async () => {
  const examples = [
    ["call_contact", { name: "Private contact" }, undefined],
    ["call_number", { number: "+919876543210" }, undefined],
    ["set_alarm", { hour: 6, minute: 30, label: "Private alarm" }, "Alarm requested"],
    ["set_timer", { seconds: 600 }, "Timer requested"],
    ["close_app", { name: "Private app" }, "Went to home screen"],
    ["open_app", { name: "Private app" }, "Opening app"],
    ["set_volume", { stream: "media", percent: 50 }, "Media volume set"],
    ["adjust_volume", { stream: "media", direction: "up", percent: 50 }, undefined],
    ["flashlight", { on: false }, "Flashlight off"],
    ["open_settings", { setting: "airplane_mode", state: "on" }, "Check settings"],
    ["play_media", { query: "Private song" }, "Music requested"],
    ["open_music", {}, "Music app opened"],
    ["control_media", { operation: "pause" }, "Pause requested"],
    ["run_actions", { actions: [{ action: "flashlight", args: { on: true } },
      { action: "set_timer", args: { seconds: 60 } }] }, "Flashlight on. Timer requested"],
    ["run_actions", { actions: [{ action: "flashlight", args: { on: true } },
      { action: "call_contact", args: { name: "Private contact" } }] }, undefined],
    ["unsupported", {}, undefined],
  ] as const;
  for (const [name, args, text] of examples) {
    const h = harness(true, undefined, "elevenlabs-v1");
    h.assistant.result = { selection: { name, args }, durationMs: 12 };
    const response = await h.api.handle(confirmationCommand());
    assert.equal(response.statusCode, 200, name);
    assert.equal(JSON.parse(response.body!).confirmationSpeech?.text, text, name);
    assert.deepEqual(h.syntheses, []);
  }
});

test("confirmation capabilities reject tampering, other identities, missing consent and expiry before billing", async () => {
  const at = new Date(TEST_NOW);
  const h = harness(true, undefined, "elevenlabs-v1", at);
  h.assistant.result = { selection: { name: "close_app", args: {} }, durationMs: 12 };
  const base = confirmationCommand();
  const issued = JSON.parse((await h.api.handle(base)).body!).confirmationSpeech;
  const speech = { ...base, rawPath: "/v1/speech", body: JSON.stringify({ confirmation: issued }) };
  const otherId = "123e4567-e89b-42d3-a456-426614174001";
  for (const [patch, status] of [
    [{ headers: { ...base.headers, authorization: "Bearer invalid" } }, 401],
    [{ headers: { ...base.headers, authorization: "Bearer " + signInstallToken(otherId, TEST_SECRETS.jwtSigningKey, TEST_NOW) } }, 400],
    [{ headers: { ...base.headers, "x-friday-confirmations": undefined } }, 400],
    [{ headers: { ...base.headers, "x-friday-speech": "wavenet-v1" } }, 400],
    ...[{ ...issued, text: "Calling private contact" }, { ...issued, issuedAt: issued.issuedAt + 1 },
      { ...issued, token: "a".repeat(43) }, { ...issued, speechId: otherId }, { ...issued, extra: true }]
      .map(confirmation => [{ body: JSON.stringify({ confirmation }) }, 400] as const),
    [{ body: JSON.stringify({ confirmation: issued, text: "Extra" }) }, 400],
  ] as const) assert.equal((await h.api.handle({ ...speech, ...patch })).statusCode, status);
  at.setTime(TEST_NOW.getTime() - 1_000);
  assert.equal((await h.api.handle(speech)).statusCode, 400, "Future issuance is invalid");
  at.setTime(TEST_NOW.getTime() + 121_000);
  assert.equal((await h.api.handle(speech)).statusCode, 400, "Capability expires after two minutes");
  assert.deepEqual(h.reservations, []); assert.deepEqual(h.syntheses, []);
  at.setTime(TEST_NOW.getTime() + 120_000);
  assert.equal((await h.api.handle(speech)).statusCode, 200);
});

test("confirmation speech uses the same fail-closed quotas and single provider attempt as answers", async () => {
  for (const failure of ["quota", "store", "provider"] as const) {
    const h = harness(true, undefined, "elevenlabs-v1");
    h.assistant.result = { selection: { name: "close_app", args: {} }, durationMs: 12 };
    const command = confirmationCommand();
    const issued = JSON.parse((await h.api.handle(command)).body!).confirmationSpeech;
    if (failure === "quota") h.deny(); else if (failure === "store") h.failReserve(); else h.failSpeech();
    const response = await h.api.handle({ ...command, rawPath: "/v1/speech", body: JSON.stringify({ confirmation: issued }) });
    assert.equal(response.statusCode, failure === "quota" ? 429 : 503);
    assert.equal(h.syntheses.length, failure === "provider" ? 1 : 0);
    assert.equal(JSON.stringify(h.logger.entries).includes("private"), false);
  }
});

test("confirmation speech honors account revocation and cannot move to another account", async () => {
  let accountId: string | null = "a".repeat(43);
  const accounts: AccountGateway = { async handle() { return { statusCode: 404 }; }, async authorize(token) {
    return token === "account-access-token" && accountId ? { accountId, installId: TEST_INSTALL_ID, sessionId: "b".repeat(43) } : null;
  } };
  const h = harness(true, accounts, "elevenlabs-v1");
  h.assistant.result = { selection: { name: "close_app", args: {} }, durationMs: 12 };
  const base = confirmationCommand();
  const signedIn = { ...base, headers: { ...base.headers, authorization: "Bearer account-access-token" } };
  const confirmation = JSON.parse((await h.api.handle(signedIn)).body!).confirmationSpeech;
  const speech = { ...signedIn, rawPath: "/v1/speech", body: JSON.stringify({ confirmation }) };
  accountId = "c".repeat(43);
  assert.equal((await h.api.handle(speech)).statusCode, 400);
  accountId = null;
  assert.equal((await h.api.handle(speech)).statusCode, 401);
  assert.equal((await h.api.handle({ ...speech, headers: { ...base.headers } })).statusCode, 401);
  assert.deepEqual(h.syntheses, []); assert.deepEqual(h.reservations, []);
});

test("speech quota sends account, replay, burst, day and calendar-month counters in one atomic call", async () => {
  let call: { script: string; keys: string[]; args: (number | string)[] } | undefined;
  const store = new RedisSpeechUsage({ async eval<T>(script: string, keys: string[], args: (number | string)[]) {
    call = { script, keys, args }; return 1 as T;
  } }, { accountMinute: 12, accountDailyCharacters: 10_000, globalMonthlyCharacters: 3_500_000 });
  assert.equal(await store.reserve(TEST_INSTALL_ID, "answer-id", 80, TEST_NOW), true);
  assert.deepEqual(call!.keys, [`friday:v1:install:${TEST_INSTALL_ID}:blocked`, "friday:speech:v1:answer:answer-id",
    `friday:speech:v1:account:${TEST_INSTALL_ID}:minute:2026-11-17T08:30`,
    `friday:speech:v1:account:${TEST_INSTALL_ID}:day:2026-11-17`, "friday:speech:v1:global:month:2026-11"]);
  assert.deepEqual(call!.args.slice(0, 6), [80, 12, 120, 10_000, 172_800, 3_500_000]);
  assert.equal(call!.args[6], Math.ceil((Date.UTC(2026, 11, 1) - TEST_NOW.getTime()) / 1_000) + 86_400);
  assert.equal(call!.args.some(value => String(value).includes(CONTENT.text)), false);
  call = undefined;
  assert.equal(await store.reserve(TEST_INSTALL_ID, "answer-id", 601, TEST_NOW), false);
  assert.equal(call, undefined);
  const denied = new RedisSpeechUsage({ async eval<T>() { return 0 as T; } }, { accountMinute: 12, accountDailyCharacters: 10_000, globalMonthlyCharacters: 3_500_000 });
  assert.equal(await denied.reserve(TEST_INSTALL_ID, "answer-id", 80, TEST_NOW), false);
});
