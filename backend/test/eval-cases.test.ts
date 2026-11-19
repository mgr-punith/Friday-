import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { loadAudioCases, loadTextCases } from "../eval/cases";
import { createPcmWav } from "./helpers";

test("loads the documented text evaluation cases", async () => {
  const cases = await loadTextCases(path.resolve(process.cwd(), "../docs/test-phrases.md"));
  assert.equal(cases.length >= 60, true);
  assert.equal(cases.some((entry) => entry.label === "Call Punith"), true);
});

test("loads private WAV evaluation cases with validated expected actions", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "friday-audio-eval-"));
  try {
    await writeFile(path.join(directory, "call-punith.wav"), createPcmWav());
    await writeFile(path.join(directory, "manifest.json"), JSON.stringify([{
      file: "call-punith.wav",
      expected: { type: "action", action: "call_contact", args: { name: "Punith" } }
    }]));

    const cases = await loadAudioCases(path.join(directory, "manifest.json"));
    assert.equal(cases.length, 1);
    assert.equal(cases[0]?.input.kind, "audio");
    assert.deepEqual(cases[0]?.expected, {
      type: "action",
      action: "call_contact",
      args: { name: "Punith" }
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects audio files outside the private manifest directory", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "friday-audio-eval-"));
  try {
    await writeFile(path.join(directory, "manifest.json"), JSON.stringify([{
      file: "../outside.wav",
      expected: { type: "unsupported" }
    }]));
    await assert.rejects(
      loadAudioCases(path.join(directory, "manifest.json")),
      /WAV must stay inside the manifest directory/
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("loads silence and noise cases that must return bad audio", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "friday-audio-eval-"));
  try {
    await writeFile(path.join(directory, "noise.wav"), createPcmWav());
    await writeFile(path.join(directory, "manifest.json"), JSON.stringify([{
      file: "noise.wav",
      expected: { type: "error", reason: "bad_audio" }
    }]));

    const cases = await loadAudioCases(path.join(directory, "manifest.json"));
    assert.deepEqual(cases[0]?.expected, { type: "error", reason: "bad_audio" });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("rejects mixed expectation fields rather than hiding a mislabeled case", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "friday-audio-eval-"));
  try {
    await writeFile(path.join(directory, "sample.wav"), createPcmWav());
    await writeFile(path.join(directory, "manifest.json"), JSON.stringify([{
      file: "sample.wav",
      expected: { type: "unsupported", reason: "bad_audio" }
    }]));
    await assert.rejects(loadAudioCases(path.join(directory, "manifest.json")), /Invalid expected/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
