import { readFile } from "node:fs/promises";
import path from "node:path";
import { parseExpectedAction, type CommandResponse } from "../src/core/action-schema";
import type { CommandInput } from "../src/core/contracts";
import { isFridayWav } from "../src/core/wav";

export interface EvaluationCase {
  readonly label: string;
  readonly input: CommandInput;
  readonly expected: CommandResponse;
}

export async function loadTextCases(file: string): Promise<EvaluationCase[]> {
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
      label: phrase,
      input: { kind: "text", text: phrase },
      expected: expectedResponse(action, args)
    });
  }
  if (cases.length === 0) throw new Error("No evaluation phrases found");
  return cases;
}

export async function loadAudioCases(manifestFile: string): Promise<EvaluationCase[]> {
  const manifestPath = path.resolve(manifestFile);
  const manifest = parseAudioManifest(JSON.parse(await readFile(manifestPath, "utf8")) as unknown);
  const manifestDirectory = path.dirname(manifestPath);
  const cases: EvaluationCase[] = [];
  for (const entry of manifest) {
    const audioPath = path.resolve(manifestDirectory, entry.file);
    const relativePath = path.relative(manifestDirectory, audioPath);
    if (relativePath.startsWith("..") || path.isAbsolute(relativePath) || path.extname(audioPath).toLowerCase() !== ".wav") {
      throw new Error(`${entry.file}: WAV must stay inside the manifest directory`);
    }
    const bytes = await readFile(audioPath);
    if (bytes.byteLength > 1_000_000) throw new Error(`${entry.file}: WAV exceeds 1,000,000 bytes`);
    if (!isFridayWav(bytes)) throw new Error(`${entry.file}: expected 16 kHz mono 16-bit PCM WAV`);
    cases.push({
      label: path.basename(entry.file),
      input: { kind: "audio", bytes, mimeType: "audio/wav" },
      expected: parseExpected(entry.expected)
    });
  }
  if (cases.length === 0) throw new Error("No audio evaluation cases found");
  return cases;
}

interface AudioManifestEntry {
  readonly file: string;
  readonly expected: unknown;
}

function parseAudioManifest(value: unknown): AudioManifestEntry[] {
  if (!Array.isArray(value)) throw new Error("Audio evaluation manifest must be an array");
  return value.map((entry, index) => {
    if (!isRecord(entry) || !hasOnlyKeys(entry, ["file", "expected"])) {
      throw new Error(`Invalid audio evaluation entry at index ${index}`);
    }
    const file = typeof entry.file === "string" ? entry.file.trim() : "";
    if (!file || path.isAbsolute(file)) {
      throw new Error(`Audio evaluation file must be a relative path at index ${index}`);
    }
    return { file, expected: entry.expected };
  });
}

function parseExpected(value: unknown): CommandResponse {
  if (!isRecord(value) || !hasOnlyKeys(value, ["type", "action", "args"])) {
    throw new Error("Invalid expected audio result");
  }
  if (value.type === "unsupported" && value.action === undefined && value.args === undefined) {
    return { type: "unsupported" };
  }
  if (value.type !== "action" || typeof value.action !== "string" || !isRecord(value.args)) {
    throw new Error("Invalid expected audio action");
  }
  const parsed = parseExpectedAction(value.action, value.args);
  if (parsed.type !== "action") throw new Error("Invalid expected audio action arguments");
  return parsed;
}

function expectedResponse(action: string, args: Record<string, unknown>): CommandResponse {
  if (action === "unsupported") return { type: "unsupported" };
  const parsed = parseExpectedAction(action, args);
  if (parsed.type !== "action") throw new Error(`Invalid expected action: ${action}`);
  return parsed;
}

function unwrapCode(value: string): string {
  if (!value.startsWith("`") || !value.endsWith("`")) throw new Error(`Expected code value: ${value}`);
  return value.slice(1, -1);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  const allowedKeys = new Set(allowed);
  return Object.keys(value).every((key) => allowedKeys.has(key));
}
