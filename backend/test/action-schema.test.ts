import assert from "node:assert/strict";
import test from "node:test";
import { parseExpectedAction, parseFunctionSelection } from "../src/core/action-schema";

test("accepts every v1 action with valid arguments", () => {
  const cases = [
    ["call_contact", { name: "Mom" }, { type: "action", action: "call_contact", args: { name: "Mom" } }],
    ["call_number", { number: "+91 98765 43210" }, { type: "action", action: "call_number", args: { number: "+91 98765 43210" } }],
    ["set_alarm", { hour: 6, minute: 30, label: "Run" }, { type: "action", action: "set_alarm", args: { hour: 6, minute: 30, label: "Run" } }],
    ["set_timer", { seconds: 90 }, { type: "action", action: "set_timer", args: { seconds: 90 } }],
    ["open_app", { name: "Camera" }, { type: "action", action: "open_app", args: { name: "Camera" } }],
    ["close_app", {}, { type: "action", action: "close_app", args: {} }],
    [
      "set_ringer_mode",
      { mode: "vibrate", utterance: "Switch to vibrate" },
      { type: "action", action: "set_ringer_mode", args: { mode: "vibrate" } }
    ]
  ] as const;

  for (const [name, args, expected] of cases) {
    assert.deepEqual(parseFunctionSelection({ name, args }), expected);
  }
});

test("trims short spoken names", () => {
  assert.deepEqual(parseFunctionSelection({ name: "open_app", args: { name: "  Camera  " } }), {
    type: "action",
    action: "open_app",
    args: { name: "Camera" }
  });
});

test("separates clear unsupported requests from unclear audio", () => {
  assert.deepEqual(parseFunctionSelection({ name: "unsupported", args: {} }), { type: "unsupported" });
  assert.deepEqual(parseFunctionSelection({ name: "unclear_command", args: {} }), { type: "error", reason: "bad_audio" });
  assert.deepEqual(parseFunctionSelection({ name: "toggle_wifi", args: {} }), { type: "error", reason: "bad_audio" });
});

test("ringer actions require matching command words", () => {
  const valid = [
    { mode: "silent", utterance: "Put my phone on silent" },
    { mode: "silent", utterance: "Mute my phone" },
    { mode: "vibrate", utterance: "Make it vibrate" },
    { mode: "normal", utterance: "Turn the ringer back on" }
  ];
  for (const args of valid) {
    assert.deepEqual(parseFunctionSelection({ name: "set_ringer_mode", args }), {
      type: "action",
      action: "set_ringer_mode",
      args: { mode: args.mode }
    });
  }

  const invalid = [
    { mode: "silent", utterance: "background noise" },
    { mode: "silent", utterance: "Switch to vibrate" },
    { mode: "vibrate", utterance: "Put my phone on silent" },
    { mode: "normal", utterance: "Open the camera" },
    { mode: "silent" }
  ];
  for (const args of invalid) {
    assert.deepEqual(parseFunctionSelection({ name: "set_ringer_mode", args }), { type: "error", reason: "bad_audio" });
  }
});

test("evaluation expectations keep the private ringer utterance out of the public contract", () => {
  assert.deepEqual(parseExpectedAction("set_ringer_mode", { mode: "silent" }), {
    type: "action",
    action: "set_ringer_mode",
    args: { mode: "silent" }
  });
});

test("rejects invalid ranges, extra keys, and malformed arguments", () => {
  const invalid = [
    { name: "set_alarm", args: { hour: 24, minute: 0 } },
    { name: "set_alarm", args: { hour: 7, minute: 60 } },
    { name: "set_timer", args: { seconds: 0 } },
    { name: "set_timer", args: { seconds: 1.5 } },
    { name: "set_ringer_mode", args: { mode: "loud", utterance: "Make it loud" } },
    { name: "open_app", args: { name: "", packageName: "bad" } },
    { name: "close_app", args: { force: true } },
    { name: "call_contact", args: null }
  ];

  for (const selection of invalid) {
    assert.deepEqual(parseFunctionSelection(selection), { type: "error", reason: "bad_audio" });
  }
});
