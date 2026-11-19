import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import type { EvaluationCase } from "./cases";

export type EvaluationAudioFormat = "wav" | "m4a";
export type M4aEncoder = (wavBytes: Uint8Array) => Promise<Uint8Array>;

export async function prepareAudioCases(
  cases: readonly EvaluationCase[],
  format: EvaluationAudioFormat,
  encodeM4a: M4aEncoder = encodeWavAsM4a
): Promise<EvaluationCase[]> {
  if (format === "wav") return [...cases];
  const preparedCases: EvaluationCase[] = [];
  for (const evaluationCase of cases) {
    if (evaluationCase.input.kind !== "audio") {
      preparedCases.push(evaluationCase);
      continue;
    }
    preparedCases.push({
      ...evaluationCase,
      input: {
        kind: "audio" as const,
        bytes: await encodeM4a(evaluationCase.input.bytes),
        mimeType: "audio/mp4" as const
      }
    });
  }
  return preparedCases;
}

export function parseEvaluationAudioFormat(value: string): EvaluationAudioFormat {
  const normalized = value.trim().toLowerCase();
  if (normalized === "wav" || normalized === "m4a") return normalized;
  throw new Error("EVAL_AUDIO_FORMAT must be wav or m4a");
}

async function encodeWavAsM4a(wavBytes: Uint8Array): Promise<Uint8Array> {
  const directory = await mkdtemp(path.join(os.tmpdir(), "friday-aac-eval-"));
  const inputFile = path.join(directory, "input.wav");
  const outputFile = path.join(directory, "output.m4a");
  try {
    await writeFile(inputFile, wavBytes);
    await runFfmpeg([
      "-hide_banner",
      "-loglevel", "error",
      "-y",
      "-i", inputFile,
      "-ac", "1",
      "-ar", "16000",
      "-c:a", "aac",
      "-b:a", "32k",
      outputFile
    ]);
    return await readFile(outputFile);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

function runFfmpeg(argumentsList: readonly string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    execFile("ffmpeg", argumentsList, { timeout: 30_000 }, (error) => {
      if (error) {
        reject(new Error("AAC conversion failed. Install ffmpeg and check the source WAV files."));
        return;
      }
      resolve();
    });
  });
}
