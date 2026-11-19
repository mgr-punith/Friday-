import assert from "node:assert/strict";
import test from "node:test";
import { matchEvaluationResult } from "../eval/result-match";

test("accepts capitalization and spacing handled by Android name matching", () => {
  const result = matchEvaluationResult(
    { type: "action", action: "open_app", args: { name: "camera" } },
    { type: "action", action: "open_app", args: { name: "Camera" } }
  );
  assert.equal(result.passed, true);
  assert.equal(result.exact, false);
});

test("accepts phone number separators but rejects wrong digits", () => {
  const expected = { type: "action", action: "call_number", args: { number: "+919876543210" } } as const;
  assert.equal(matchEvaluationResult(
    { type: "action", action: "call_number", args: { number: "+91 (98765) 43210" } }, expected
  ).passed, true);
  assert.equal(matchEvaluationResult(
    { type: "action", action: "call_number", args: { number: "+919876543219" } }, expected
  ).passed, false);
});

test("keeps timer values, action choices, and noise rejection strict", () => {
  assert.equal(matchEvaluationResult(
    { type: "action", action: "set_timer", args: { seconds: 40 } },
    { type: "action", action: "set_timer", args: { seconds: 45 } }
  ).difference, "arguments");
  assert.equal(matchEvaluationResult({ type: "unsupported" }, { type: "error", reason: "bad_audio" }).passed, false);
  assert.equal(matchEvaluationResult(
    { type: "action", action: "set_ringer_mode", args: { mode: "silent" } },
    { type: "error", reason: "bad_audio" }
  ).passed, false);
});

test("Home navigation ignores the optional app name", () => {
  assert.equal(matchEvaluationResult(
    { type: "action", action: "close_app", args: { name: "Camera" } },
    { type: "action", action: "close_app", args: {} }
  ).passed, true);
});
