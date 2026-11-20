import type { FunctionSelection } from "./contracts";

export const SYSTEM_PROMPT =
  "You control an Android phone. The user speaks English. Pick exactly one function. " +
  "The speaker may use Indian English pronunciation and phrasing. Require a clear request, not a keyword mentioned in conversation. " +
  "Interpret double and triple digit words in phone numbers. " +
  "For audio, use unclear_command for silence, noise, music, or words you cannot understand. Never guess an action. " +
  "Use unsupported only for a clear request outside the supported actions. Never answer questions. " +
  "For set_ringer_mode, copy the exact command words into utterance. Use set_timer for durations. " +
  "Use the supplied current phone time for clock times such as tomorrow.";

export const FUNCTION_DECLARATIONS = [
  {
    name: "call_contact",
    description: "Call, phone, or ring a saved contact when asked to place a call.",
    parameters: {
      type: "object",
      properties: { name: { type: "string", description: "Full name as spoken, without the call verb." } },
      required: ["name"]
    }
  },
  {
    name: "call_number",
    description: "Call or dial a spoken phone number.",
    parameters: {
      type: "object",
      properties: { number: { type: "string", description: "Phone number as spoken." } },
      required: ["number"]
    }
  },
  {
    name: "set_alarm",
    description: "Set an alarm at a clock time.",
    parameters: {
      type: "object",
      properties: {
        hour: { type: "integer", minimum: 0, maximum: 23 },
        minute: { type: "integer", minimum: 0, maximum: 59 },
        label: { type: "string", description: "Optional short alarm label." }
      },
      required: ["hour", "minute"]
    }
  },
  {
    name: "set_timer",
    description: "Start a timer for a duration.",
    parameters: {
      type: "object",
      properties: { seconds: { type: "integer", minimum: 1 } },
      required: ["seconds"]
    }
  },
  {
    name: "open_app",
    description: "Open, launch, start, or go to a named Android app.",
    parameters: {
      type: "object",
      properties: { name: { type: "string", description: "Full app name as spoken (e.g. Google Chrome), without the launch verb." } },
      required: ["name"]
    }
  },
  {
    name: "close_app",
    description: "Leave the current app by going to the Home screen.",
    parameters: {
      type: "object",
      properties: { name: { type: "string", description: "Optional spoken app name." } }
    }
  },
  {
    name: "set_ringer_mode",
    description: "Change the phone ringer mode.",
    parameters: {
      type: "object",
      properties: {
        mode: { type: "string", enum: ["silent", "vibrate", "normal"] },
        utterance: { type: "string", description: "Exact command words heard in the audio or text." }
      },
      required: ["mode", "utterance"]
    }
  },
  {
    name: "unclear_command",
    description: "Use when audio is silence, noise, music, non-speech, or the spoken words are uncertain.",
    parameters: { type: "object", properties: {} }
  },
  {
    name: "unsupported",
    description: "Use for a clear request for questions, chat, web, unsupported phone controls, or non-English speech.",
    parameters: { type: "object", properties: {} }
  }
] as const;

export const FUNCTION_NAMES = FUNCTION_DECLARATIONS.map((declaration) => declaration.name);

export type ActionName =
  | "call_contact"
  | "call_number"
  | "set_alarm"
  | "set_timer"
  | "open_app"
  | "close_app"
  | "set_ringer_mode";

export type CommandResponse =
  | { readonly type: "action"; readonly action: ActionName; readonly args: Record<string, unknown> }
  | { readonly type: "unsupported" }
  | { readonly type: "error"; readonly reason: "bad_audio" };

export function parseFunctionSelection(selection: FunctionSelection): CommandResponse {
  const args = isRecord(selection.args) ? selection.args : {};

  switch (selection.name) {
    case "call_contact": {
      const name = readShortString(args, "name");
      return name && hasOnlyKeys(args, ["name"])
        ? action("call_contact", { name })
        : badAudio();
    }
    case "call_number": {
      const number = readShortString(args, "number", 40);
      return number && hasOnlyKeys(args, ["number"])
        ? action("call_number", { number })
        : badAudio();
    }
    case "set_alarm": {
      const hour = readInteger(args, "hour", 0, 23);
      const minute = readInteger(args, "minute", 0, 59);
      const label = readOptionalShortString(args, "label");
      if (hour === undefined || minute === undefined || label === null) return badAudio();
      if (!hasOnlyKeys(args, ["hour", "minute", "label"])) return badAudio();
      return action("set_alarm", label === undefined ? { hour, minute } : { hour, minute, label });
    }
    case "set_timer": {
      const seconds = readInteger(args, "seconds", 1, 2_147_483_647);
      return seconds !== undefined && hasOnlyKeys(args, ["seconds"])
        ? action("set_timer", { seconds })
        : badAudio();
    }
    case "open_app": {
      const name = readShortString(args, "name");
      return name && hasOnlyKeys(args, ["name"])
        ? action("open_app", { name })
        : badAudio();
    }
    case "close_app": {
      const name = readOptionalShortString(args, "name");
      if (name === null || !hasOnlyKeys(args, ["name"])) return badAudio();
      return action("close_app", name === undefined ? {} : { name });
    }
    case "set_ringer_mode": {
      const mode = args.mode;
      const utterance = readShortString(args, "utterance", 160);
      return (mode === "silent" || mode === "vibrate" || mode === "normal") &&
        utterance !== undefined && matchesRingerCommand(mode, utterance) &&
        hasOnlyKeys(args, ["mode", "utterance"])
        ? action("set_ringer_mode", { mode })
        : badAudio();
    }
    case "unclear_command":
      return badAudio();
    case "unsupported":
      return hasOnlyKeys(args, []) ? unsupported() : badAudio();
    default:
      return badAudio();
  }
}

export function parseExpectedAction(name: string, args: unknown): CommandResponse {
  if (name === "set_ringer_mode" && isRecord(args) && hasOnlyKeys(args, ["mode"])) {
    const mode = args.mode;
    if (mode === "silent" || mode === "vibrate" || mode === "normal") {
      return action("set_ringer_mode", { mode });
    }
  }
  return parseFunctionSelection({ name, args });
}

function action(actionName: ActionName, args: Record<string, unknown>): CommandResponse {
  return { type: "action", action: actionName, args };
}

function unsupported(): CommandResponse {
  return { type: "unsupported" };
}

function badAudio(): CommandResponse {
  return { type: "error", reason: "bad_audio" };
}

function matchesRingerCommand(mode: "silent" | "vibrate" | "normal", utterance: string): boolean {
  const words: string[] = utterance.toLowerCase().match(/[a-z]+/g) ?? [];
  if (mode === "silent") return words.includes("silent") || words.includes("mute") || words.includes("muted");
  if (mode === "vibrate") return words.some((word) => word.startsWith("vibrat"));
  return words.includes("normal") || words.includes("ring") || words.includes("ringer");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  const allowedKeys = new Set(allowed);
  return Object.keys(value).every((key) => allowedKeys.has(key));
}

function readShortString(
  value: Record<string, unknown>,
  key: string,
  maxLength = 100
): string | undefined {
  const item = value[key];
  if (typeof item !== "string") return undefined;
  const trimmed = item.trim();
  return trimmed.length > 0 && trimmed.length <= maxLength ? trimmed : undefined;
}

function readOptionalShortString(
  value: Record<string, unknown>,
  key: string,
  maxLength = 100
): string | undefined | null {
  if (!(key in value)) return undefined;
  return readShortString(value, key, maxLength) ?? null;
}

function readInteger(
  value: Record<string, unknown>,
  key: string,
  minimum: number,
  maximum: number
): number | undefined {
  const item = value[key];
  return typeof item === "number" && Number.isInteger(item) && item >= minimum && item <= maximum
    ? item
    : undefined;
}
