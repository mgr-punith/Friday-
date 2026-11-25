import assert from "node:assert/strict";
import test from "node:test";
import { createAnswerContinuation, readAnswerContinuation } from "../src/core/answer-continuation";
import { TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

const KEY = TEST_SECRETS.jwtSigningKey;
test("continuations preserve only two clarification replies without plaintext or server storage", () => {
  const first = createAnswerContinuation("Who is Gandhi?", "Mahatma Gandhi or Indira Gandhi?", TEST_INSTALL_ID, KEY, TEST_NOW)!;
  const context = readAnswerContinuation(first, TEST_INSTALL_ID, KEY, TEST_NOW)!;
  assert.equal(context.question, "Who is Gandhi?");
  assert.equal(context.remaining, 1);
  assert.deepEqual(context.turns, []);
  assert.equal(Buffer.from(first, "base64url").includes(Buffer.from(context.question)), false);
  assert.notEqual(first, createAnswerContinuation(context.question, context.prompt, TEST_INSTALL_ID, KEY, TEST_NOW));
  const second = createAnswerContinuation("Mahatma", "Do you mean his life or his role in independence?", TEST_INSTALL_ID, KEY, TEST_NOW, context)!;
  const next = readAnswerContinuation(second, TEST_INSTALL_ID, KEY, TEST_NOW)!;
  assert.deepEqual(next.turns, [{ prompt: context.prompt, reply: "Mahatma" }]);
  assert.equal(next.remaining, 0);
  assert.equal(next.expiresAt, context.expiresAt);
  assert.equal(createAnswerContinuation("His life", "One more question?", TEST_INSTALL_ID, KEY, TEST_NOW, next), null);
});

test("changed, oversized, expired and cross-account continuations fail before provider work", () => {
  const token = createAnswerContinuation("What happened in India's match?", "Which sport?", TEST_INSTALL_ID, KEY, TEST_NOW)!;
  const changed = (token[0] === "A" ? "B" : "A") + token.slice(1);
  for (const value of ["", "x".repeat(4_097), "not a token", changed]) {
    assert.equal(readAnswerContinuation(value, TEST_INSTALL_ID, KEY, TEST_NOW), null);
  }
  assert.equal(readAnswerContinuation(token, "another-account", KEY, TEST_NOW), null);
  assert.equal(readAnswerContinuation(token, TEST_INSTALL_ID, "example-other-signing-value", TEST_NOW), null);
  assert.equal(readAnswerContinuation(token, TEST_INSTALL_ID, KEY, new Date(TEST_NOW.getTime() + 120_000)), null);
  assert.equal(readAnswerContinuation(token, TEST_INSTALL_ID, KEY, new Date(TEST_NOW.getTime() - 1_000)), null);
  assert.equal(createAnswerContinuation("q".repeat(501), "Which sport?", TEST_INSTALL_ID, KEY, TEST_NOW), null);
  assert.equal(createAnswerContinuation("Question\nwith controls", "Which sport?", TEST_INSTALL_ID, KEY, TEST_NOW), null);
});
