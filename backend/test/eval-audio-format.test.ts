import assert from "node:assert/strict";
import test from "node:test";
import { parseEvaluationAudioFormat, prepareAudioCases } from "../eval/audio-format";
import type { EvaluationCase } from "../eval/cases";
import { createPcmWav } from "./helpers";

const AUDIO_CASE: EvaluationCase = {
  label: "private.wav",
  input: { kind: "audio", bytes: createPcmWav(), mimeType: "audio/wav" },
  expected: { type: "unsupported" }
};

test("keeps WAV evaluation cases unchanged by default", async () => {
  const cases = await prepareAudioCases([AUDIO_CASE], "wav");
  assert.equal(cases[0]?.input, AUDIO_CASE.input);
});

test("converts audio cases to M4A through the injected encoder", async () => {
  const encoded = Uint8Array.from([1, 2, 3]);
  let sourceBytes = 0;
  const cases = await prepareAudioCases([AUDIO_CASE], "m4a", async (wavBytes) => {
    sourceBytes = wavBytes.byteLength;
    return encoded;
  });

  assert.equal(sourceBytes, AUDIO_CASE.input.kind === "audio" ? AUDIO_CASE.input.bytes.byteLength : 0);
  assert.deepEqual(cases[0]?.input, { kind: "audio", bytes: encoded, mimeType: "audio/mp4" });
});

test("validates the requested evaluation audio format", () => {
  assert.equal(parseEvaluationAudioFormat("WAV"), "wav");
  assert.equal(parseEvaluationAudioFormat("m4a"), "m4a");
  assert.throws(() => parseEvaluationAudioFormat("aac"), /EVAL_AUDIO_FORMAT/);
});
