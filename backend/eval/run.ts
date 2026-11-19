import path from "node:path";
import { parseFunctionSelection } from "../src/core/action-schema";
import {
  AssistantProviderError,
  type SecretProvider
} from "../src/core/contracts";
import { parseEvaluationAudioFormat, prepareAudioCases } from "./audio-format";
import { buildBenchmarkSummary, type EvaluationObservation } from "./benchmark";
import { loadAudioCases, loadTextCases } from "./cases";
import { GeminiEvaluationProvider } from "./gemini";
import { GroqEvaluationProvider } from "./groq";
import type { EvaluationProvider } from "./provider";
import { matchEvaluationResult } from "./result-match";

void main().catch((error: unknown) => {
  console.error(errorMessage(error));
  process.exitCode = 1;
});

async function main(): Promise<void> {
  const delayMs = parseInteger(process.env.EVAL_DELAY_MS ?? "0", "EVAL_DELAY_MS", 0, 60_000);
  const limit = parseInteger(process.env.EVAL_LIMIT ?? "0", "EVAL_LIMIT", 0, 10_000);
  const offset = parseInteger(process.env.EVAL_OFFSET ?? "0", "EVAL_OFFSET", 0, 10_000);
  const now = "2026-11-17T09:30:00+05:30";
  const audioManifest = audioManifestArgument(process.argv.slice(2));
  const cases = audioManifest
    ? await loadAudioCases(audioManifest)
    : await loadTextCases(path.resolve(process.cwd(), "../docs/test-phrases.md"));
  const remainingCases = cases.slice(offset);
  const limitedCases = limit > 0 ? remainingCases.slice(0, limit) : remainingCases;
  const audioFormat = parseEvaluationAudioFormat(process.env.EVAL_AUDIO_FORMAT ?? "wav");
  if (!audioManifest && audioFormat !== "wav") {
    throw new Error("EVAL_AUDIO_FORMAT only applies to --audio-manifest runs");
  }
  const selectedCases = await prepareAudioCases(limitedCases, audioFormat);
  const provider = createProvider(process.env);
  const observations: EvaluationObservation[] = [];
  for (const [index, evaluationCase] of selectedCases.entries()) {
    const caseNumber = offset + index + 1;
    const startedAt = performance.now();
    try {
      const result = await provider.select(evaluationCase.input, now);
      const actual = parseFunctionSelection(result.selection);
      const { passed, exact, difference } = matchEvaluationResult(actual, evaluationCase.expected);
      observations.push({
        passed,
        completed: true,
        durationMs: result.durationMs,
        ...(evaluationCase.input.kind === "audio" ? { inputBytes: evaluationCase.input.bytes.byteLength } : {}),
        stages: result.stages,
        ...(passed ? {} : { failure: `wrong_${difference ?? "result"}` })
      });
      console.log(`${passed ? "PASS" : "FAIL"} ${result.durationMs.toFixed(0)}ms | case ${caseNumber}${passed ? (exact ? "" : " | equivalent_result") : ` | wrong_${difference}`}`);
    } catch (error) {
      const elapsed = performance.now() - startedAt;
      const failure = failureCode(error);
      observations.push({
        passed: false,
        completed: false,
        durationMs: elapsed,
        ...(evaluationCase.input.kind === "audio" ? { inputBytes: evaluationCase.input.bytes.byteLength } : {}),
        failure
      });
      console.log(`FAIL ${elapsed.toFixed(0)}ms | case ${caseNumber} | ${failure}`);
    }
    if (delayMs > 0) await delay(delayMs);
  }

  const summary = buildBenchmarkSummary(provider.name, provider.models, observations);
  printSummary(summary);
  console.log(`SUMMARY_JSON=${JSON.stringify(summary)}`);
  if (summary.failed > 0) process.exitCode = 1;
}

function createProvider(environment: NodeJS.ProcessEnv): EvaluationProvider {
  const providerName = (environment.EVAL_PROVIDER ?? "gemini").trim().toLowerCase();
  if (providerName === "gemini") return createGeminiProvider(environment);
  if (providerName === "groq") {
    return new GroqEvaluationProvider({
      apiKey: required(environment, "GROQ_API_KEY"),
      transcriptionModel: environment.GROQ_TRANSCRIPTION_MODEL?.trim() || "whisper-large-v3-turbo",
      actionModel: environment.GROQ_ACTION_MODEL?.trim() || "openai/gpt-oss-20b",
      timeoutMs: parseInteger(environment.GROQ_TIMEOUT_MS ?? "10000", "GROQ_TIMEOUT_MS", 500, 30_000)
    });
  }
  throw new Error("EVAL_PROVIDER must be gemini or groq");
}

function createGeminiProvider(environment: NodeJS.ProcessEnv): EvaluationProvider {
  const apiKey = required(environment, "GEMINI_API_KEY");
  const model = required(environment, "GEMINI_MODEL");
  const thinkingLevel = parseThinkingLevel(environment.GEMINI_THINKING_LEVEL ?? "MINIMAL");
  const timeoutMs = parseInteger(environment.GEMINI_TIMEOUT_MS ?? "10000", "GEMINI_TIMEOUT_MS", 500, 30_000);
  const secrets: SecretProvider = {
    async getSecrets() {
      return {
        geminiApiKey: apiKey,
        jwtSigningKey: "local-evaluation-only-not-used-for-requests"
      };
    }
  };
  return new GeminiEvaluationProvider({ model, thinkingLevel, timeoutMs }, secrets);
}

function printSummary(summary: ReturnType<typeof buildBenchmarkSummary>): void {
  console.log(`\nProvider: ${summary.provider}`);
  console.log(`Models: ${summary.models.join(" + ")}`);
  console.log(`Result: ${summary.passed}/${summary.cases} (${summary.accuracyPercent.toFixed(1)}%)`);
  console.log(`Completed responses: ${summary.passed}/${summary.completedCases} (${summary.completedAccuracyPercent.toFixed(1)}%)`);
  printLatency("End-to-end", summary.latency);
  printUpload(summary.upload);
  for (const [stage, latency] of Object.entries(summary.stages)) printLatency(stage, latency);
  for (const [failure, count] of Object.entries(summary.failures)) console.log(`Failures ${failure}: ${count}`);
}

function printUpload(upload: { medianBytes: number | null; p95Bytes: number | null; maxBytes: number | null }): void {
  if (upload.medianBytes === null || upload.p95Bytes === null || upload.maxBytes === null) return;
  console.log(`Upload: median ${upload.medianBytes} bytes, P95 ${upload.p95Bytes} bytes, max ${upload.maxBytes} bytes`);
}

function printLatency(
  label: string,
  latency: { medianMs: number | null; p95Ms: number | null; maxMs: number | null }
): void {
  if (latency.medianMs === null || latency.p95Ms === null || latency.maxMs === null) return;
  console.log(`${label}: median ${latency.medianMs.toFixed(0)}ms, P95 ${latency.p95Ms.toFixed(0)}ms, max ${latency.maxMs.toFixed(0)}ms`);
}

function audioManifestArgument(argumentsList: readonly string[]): string | null {
  if (argumentsList.length === 0) return null;
  if (argumentsList.length !== 2 || argumentsList[0] !== "--audio-manifest" || !argumentsList[1]?.trim()) {
    throw new Error("Use --audio-manifest <path-to-manifest.json>");
  }
  return argumentsList[1];
}

function required(environment: NodeJS.ProcessEnv, name: string): string {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required. Put it in backend/.env for local evaluation.`);
  return value;
}

function parseThinkingLevel(value: string): "MINIMAL" | "LOW" | "MEDIUM" | "HIGH" {
  const normalized = value.trim().toUpperCase();
  if (normalized === "MINIMAL" || normalized === "LOW" || normalized === "MEDIUM" || normalized === "HIGH") {
    return normalized;
  }
  throw new Error("GEMINI_THINKING_LEVEL must be MINIMAL, LOW, MEDIUM, or HIGH");
}

function parseInteger(value: string, name: string, minimum: number, maximum: number): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
  }
  return parsed;
}

function failureCode(error: unknown): string {
  if (error instanceof AssistantProviderError) return error.code;
  const message = errorMessage(error);
  if (/^gemini_http_\d{3}$/.test(message)) return message;
  if (/^groq_(transcription|classification)_(timeout|invalid_response|http_\d{3})$/.test(message)) return message;
  return "request_failed";
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "unknown error";
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
