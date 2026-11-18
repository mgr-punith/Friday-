import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { parseFunctionSelection, type CommandResponse } from "../src/core/action-schema";
import type { SecretProvider } from "../src/core/contracts";
import { GeminiProvider } from "../src/providers/gemini";

interface EvaluationCase {
  readonly phrase: string;
  readonly expected: CommandResponse;
}

void main().catch((error: unknown) => {
  console.error(errorMessage(error));
  process.exitCode = 1;
});

async function main(): Promise<void> {
  const apiKey = required("GEMINI_API_KEY");
  const model = required("GEMINI_MODEL");
  const thinkingLevel = parseThinkingLevel(process.env.GEMINI_THINKING_LEVEL ?? "MINIMAL");
  const delayMs = parseNonNegativeInteger(process.env.EVAL_DELAY_MS ?? "0", "EVAL_DELAY_MS");
  const limit = parseNonNegativeInteger(process.env.EVAL_LIMIT ?? "0", "EVAL_LIMIT");
  const now = "2026-11-17T09:30:00+05:30";
  const cases = await loadCases();
  const selectedCases = limit > 0 ? cases.slice(0, limit) : cases;

  const secrets: SecretProvider = {
    async getSecrets() {
      return {
        geminiApiKey: apiKey,
        jwtSigningKey: "local-evaluation-only-not-used-for-requests"
      };
    }
  };
  const provider = new GeminiProvider(
    { model, thinkingLevel, timeoutMs: 6_000 },
    secrets
  );

  let passed = 0;
  const latencies: number[] = [];
  for (const evaluationCase of selectedCases) {
    const startedAt = performance.now();
    try {
      const result = await provider.select({ kind: "text", text: evaluationCase.phrase }, now);
      const actual = parseFunctionSelection(result.selection);
      assert.deepEqual(actual, evaluationCase.expected);
      passed += 1;
      latencies.push(result.durationMs);
      console.log(`PASS ${result.durationMs.toFixed(0)}ms | ${evaluationCase.phrase}`);
    } catch (error) {
      const elapsed = performance.now() - startedAt;
      console.log(`FAIL ${elapsed.toFixed(0)}ms | ${evaluationCase.phrase} | ${errorMessage(error)}`);
    }
    if (delayMs > 0) await delay(delayMs);
  }

  const accuracy = selectedCases.length === 0 ? 0 : (passed / selectedCases.length) * 100;
  console.log(`\nModel: ${model}`);
  console.log(`Result: ${passed}/${selectedCases.length} (${accuracy.toFixed(1)}%)`);
  if (latencies.length > 0) {
    latencies.sort((left, right) => left - right);
    console.log(`Median Gemini latency: ${percentile(latencies, 0.5).toFixed(0)}ms`);
    console.log(`P95 Gemini latency: ${percentile(latencies, 0.95).toFixed(0)}ms`);
  }

  if (passed !== selectedCases.length) process.exitCode = 1;
}

async function loadCases(): Promise<EvaluationCase[]> {
  const file = path.resolve(process.cwd(), "../docs/test-phrases.md");
  const text = await readFile(file, "utf8");
  const cases: EvaluationCase[] = [];
  for (const line of text.split("\n")) {
    if (!line.startsWith("| `")) continue;
    const columns = line.slice(1, -1).split("|").map((column) => column.trim());
    if (columns.length !== 3) throw new Error(`Invalid phrase row: ${line}`);
    const phrase = unwrapCode(columns[0] ?? "");
    const action = unwrapCode(columns[1] ?? "");
    const args = JSON.parse(unwrapCode(columns[2] ?? "")) as Record<string, unknown>;
    cases.push({
      phrase,
      expected: action === "unsupported"
        ? { type: "unsupported" }
        : { type: "action", action: parseAction(action), args }
    });
  }
  if (cases.length === 0) throw new Error("No evaluation phrases found");
  return cases;
}

function unwrapCode(value: string): string {
  if (!value.startsWith("`") || !value.endsWith("`")) throw new Error(`Expected code value: ${value}`);
  return value.slice(1, -1);
}

function parseAction(value: string): Exclude<CommandResponse, { type: "unsupported" }>["action"] {
  if (
    value === "call_contact" || value === "call_number" || value === "set_alarm" ||
    value === "set_timer" || value === "open_app" || value === "close_app" ||
    value === "set_ringer_mode"
  ) return value;
  throw new Error(`Unknown expected action: ${value}`);
}

function required(name: string): string {
  const value = process.env[name]?.trim();
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

function parseNonNegativeInteger(value: string, name: string): number {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) throw new Error(`${name} must be a non-negative integer`);
  return parsed;
}

function percentile(sorted: readonly number[], fraction: number): number {
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1);
  return sorted[Math.max(0, index)] ?? 0;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "unknown error";
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
