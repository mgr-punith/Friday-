import assert from "node:assert/strict";
import test from "node:test";
import { isFridayWav } from "../src/core/wav";
import { createPcmWav } from "./helpers";

test("accepts 16 kHz mono 16-bit PCM WAV", () => {
  assert.equal(isFridayWav(createPcmWav()), true);
});

test("rejects incompatible audio formats", () => {
  assert.equal(isFridayWav(createPcmWav({ sampleRate: 44_100 })), false);
  assert.equal(isFridayWav(createPcmWav({ channels: 2 })), false);
  assert.equal(isFridayWav(createPcmWav({ bitsPerSample: 8 })), false);
});

test("rejects truncated and trailing data", () => {
  const valid = createPcmWav();
  assert.equal(isFridayWav(valid.subarray(0, valid.length - 1)), false);
  assert.equal(isFridayWav(Buffer.concat([valid, Buffer.from([0])])), false);
  assert.equal(isFridayWav(Buffer.from("not a wav")), false);
});
