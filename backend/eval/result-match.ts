import { isDeepStrictEqual } from "node:util";
import type { CommandResponse } from "../src/core/action-schema";

export interface ResultMatch {
  readonly passed: boolean;
  readonly exact: boolean;
  readonly difference: "type" | "action" | "arguments" | null;
}

export function matchEvaluationResult(actual: CommandResponse, expected: CommandResponse): ResultMatch {
  const exact = isDeepStrictEqual(actual, expected);
  if (exact) return { passed: true, exact, difference: null };
  if (actual.type !== expected.type) return { passed: false, exact, difference: "type" };
  if (actual.type === "action" && expected.type === "action") {
    if (actual.action !== expected.action) return { passed: false, exact, difference: "action" };
    // Android goes Home regardless of the optional app name; it never closes an app.
    if (actual.action === "close_app") return { passed: true, exact, difference: null };
    const equivalent = isDeepStrictEqual(comparableArgs(actual), comparableArgs(expected));
    return { passed: equivalent, exact, difference: equivalent ? null : "arguments" };
  }
  return { passed: false, exact, difference: "arguments" };
}

function comparableArgs(response: Extract<CommandResponse, { type: "action" }>): Record<string, unknown> {
  const args = { ...response.args };
  if (response.action === "open_app" || response.action === "call_contact") {
    if (typeof args.name === "string") {
      args.name = args.name.normalize("NFD").replace(/\p{M}+/gu, "")
        .toLowerCase().replace(/[’']/g, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
    }
  }
  if (response.action === "call_number" && typeof args.number === "string") {
    // Ignore visual formatting only. Different digits and the international prefix still fail.
    args.number = args.number.replace(/[\s().-]/g, "");
  }
  return args;
}
