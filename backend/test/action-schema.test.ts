import assert from "node:assert/strict";
import test from "node:test";
import {
  functionNamesFor,
  parseExpectedAction,
  parseFunctionSelection
} from "../src/core/action-schema";
import { actionProtocolVersion } from "../src/core/capabilities";

test("media controls require opt-in and accept only transport operations", () => {
  assert.equal(actionProtocolVersion("4"), 4);
  assert.equal(actionProtocolVersion("4", "1"), 5);
  assert.equal(actionProtocolVersion("5"), 5);
  assert.equal(actionProtocolVersion("3", "1"), 3);
  assert.equal(functionNamesFor(4).includes("control_media"), false);
  assert.equal(functionNamesFor(5).includes("control_media"), true);
  for (const operation of ["pause", "resume", "next", "previous"]) {
    const args = { operation };
    const selection = { name: "control_media", args };
    assert.deepEqual(parseFunctionSelection(selection, 4), { type: "unsupported" });
    assert.deepEqual(parseFunctionSelection(selection, 5), { type: "action", action: "control_media", args });
  }
  for (const args of [{}, { operation: "stop" }, { operation: "pause", appName: "YouTube" }]) {
    assert.deepEqual(parseFunctionSelection({ name: "control_media", args }, 5), { type: "error", reason: "bad_audio" });
  }
});

test("named media is a validated terminal v4 handoff", () => {
  const args = { query: "Believer", appName: "Spotify" };
  const play = { name: "play_media", args };
  assert.equal(actionProtocolVersion("4"), 4);
  for (const version of [1, 2, 3] as const) {
    assert.equal(functionNamesFor(version).includes(play.name), false);
    assert.deepEqual(parseFunctionSelection(play, version), { type: "unsupported" });
  }
  assert.equal(functionNamesFor(4).includes(play.name), true);
  assert.deepEqual(parseFunctionSelection(play, 4), { type: "action", action: play.name, args });
  for (const fields of [{}, { query: "x".repeat(161) }, { query: "\nBeliever" },
    { ...args, package: "app" }, { ...args, appName: null }, { ...args, appName: 42 },
    { ...args, appName: false }, { ...args, appName: [] }, { ...args, appName: {} },
    { ...args, appName: "" }, { ...args, appName: " " }, { ...args, appName: "\nYouTube" }]) {
    assert.deepEqual(parseFunctionSelection({ ...play, args: fields }, 4), { type: "error", reason: "bad_audio" });
  }
  const media = { action: play.name, args };
  const light = { action: "flashlight", args: { on: true } };
  const sequence = (actions: unknown[]) => parseFunctionSelection({ name: "run_actions", args: { actions } }, 4);
  assert.equal(sequence([light, media]).type, "actions");
  assert.equal(sequence([media, light]).type, "unsupported");
});

test("accepts every v1 action with valid arguments", () => {
  const cases = [
    ["call_contact", { name: "Mom" }, { type: "action", action: "call_contact", args: { name: "Mom" } }],
    ["call_number", { number: "+91 98765 43210" }, { type: "action", action: "call_number", args: { number: "+91 98765 43210" } }],
    ["set_alarm", { hour: 6, minute: 30, label: "Run" }, { type: "action", action: "set_alarm", args: { hour: 6, minute: 30, label: "Run" } }],
    ["set_timer", { seconds: 90 }, { type: "action", action: "set_timer", args: { seconds: 90 } }],
    ["open_app", { name: "Camera" }, { type: "action", action: "open_app", args: { name: "Camera" } }],
    ["open_app", { name: "Google Chrome" }, { type: "action", action: "open_app", args: { name: "Google Chrome" } }],
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

test("new device controls and ordered commands require an opted-in client", () => {
  const flashlight = { action: "flashlight", args: { on: false } };
  const volume = { action: "set_volume", args: { stream: "media", percent: 50 } };
  const launch = { action: "open_app", args: { name: "Clock" } };
  const sequence = (actions: unknown[], expanded = true) =>
    parseFunctionSelection({ name: "run_actions", args: { actions } }, expanded ? 2 : 1);
  const actions = [flashlight, volume, launch];

  assert.deepEqual(sequence(actions, false), { type: "unsupported" });
  assert.deepEqual(parseFunctionSelection({ name: "flashlight", args: flashlight.args }), { type: "unsupported" });
  assert.deepEqual(sequence(actions), {
    type: "actions",
    actions: actions.map(step => ({ type: "action", ...step }))
  });
  assert.deepEqual(sequence([launch, flashlight]), { type: "unsupported" });
  assert.deepEqual(sequence([flashlight, { action: "take_screenshot", args: {} }]), { type: "error", reason: "bad_audio" });
  assert.deepEqual(sequence([flashlight, { action: "run_actions", args: { actions } }]), { type: "error", reason: "bad_audio" });
  assert.deepEqual(sequence([flashlight, { action: "set_volume", args: { stream: "alarm", percent: 101 } }]), { type: "error", reason: "bad_audio" });
  assert.deepEqual(sequence([flashlight, { action: "flashlight", args: { on: "false" } }]), { type: "error", reason: "bad_audio" });
  assert.deepEqual(sequence([flashlight]), { type: "error", reason: "bad_audio" });
  assert.deepEqual(sequence([...actions, flashlight]), { type: "error", reason: "bad_audio" });
});

test("negotiates v1, v2, and v3 without exposing newer actions to older clients", () => {
  const settings = { name: "open_settings", args: { setting: "airplane_mode", state: "on" } };

  assert.equal(actionProtocolVersion(undefined), 1);
  assert.equal(actionProtocolVersion("2"), 2);
  assert.equal(actionProtocolVersion("3"), 3);
  assert.equal(actionProtocolVersion("99"), 1);
  assert.equal(functionNamesFor(1).includes("flashlight"), false);
  assert.equal(functionNamesFor(2).includes("open_settings"), false);
  assert.equal(functionNamesFor(3).includes("open_settings"), true);
  assert.deepEqual(parseFunctionSelection(settings, 1), { type: "unsupported" });
  assert.deepEqual(parseFunctionSelection(settings, 2), { type: "unsupported" });
  assert.deepEqual(parseFunctionSelection(settings, 3), {
    type: "action", action: "open_settings", args: { setting: "airplane_mode", state: "on" }
  });
});

test("validates allowlisted settings arguments and keeps handoffs terminal", () => {
  for (const selection of [
    { name: "open_settings", args: {} },
    { name: "open_settings", args: { setting: "wifi" } },
    { name: "open_settings", args: { setting: "airplane_mode", state: "toggle" } },
    { name: "open_settings", args: { setting: "airplane_mode", intent: "android.settings.SETTINGS" } }
  ]) {
    assert.deepEqual(parseFunctionSelection(selection, 3), { type: "error", reason: "bad_audio" });
  }

  const settings = { action: "open_settings", args: { setting: "airplane_mode", state: "off" } };
  const flashlight = { action: "flashlight", args: { on: false } };
  const sequence = (actions: unknown[]) =>
    parseFunctionSelection({ name: "run_actions", args: { actions } }, 3);
  assert.deepEqual(sequence([settings, flashlight]), { type: "unsupported" });
  assert.deepEqual(sequence([flashlight, settings]), {
    type: "actions",
    actions: [
      { type: "action", ...flashlight },
      { type: "action", ...settings }
    ]
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

test("turning vibrate or silent mode off restores normal sound and rejects the opposite mode", () => {
  for (const utterance of ["Turn off vibrate mode", "Turn off vibrate mode off", "Switch vibration off",
    "Turn my phone's vibrate mode off", "Turn off silent mode", "Disable silent mode", "Unmute my phone"]) {
    assert.deepEqual(parseFunctionSelection({ name: "set_ringer_mode", args: { mode: "normal", utterance } }), {
      type: "action", action: "set_ringer_mode", args: { mode: "normal" }
    }, utterance);
    for (const mode of ["silent", "vibrate"]) assert.deepEqual(
      parseFunctionSelection({ name: "set_ringer_mode", args: { mode, utterance } }),
      { type: "error", reason: "bad_audio" }, `Must not enable ${mode}: ${utterance}`);
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
