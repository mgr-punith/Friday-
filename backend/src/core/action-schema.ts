import type { FunctionSelection } from "./contracts";
import {
  LATEST_ACTION_PROTOCOL_VERSION,
  canPrecedeAnotherAction,
  supportsAction,
  type ActionName,
  type ActionProtocolVersion
} from "./capabilities";

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
    description: "Open, launch, start, or go to a named Android app, including requests to open a music player by name.",
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

const DEVICE_CONTROLS = [
  {
    name: "open_music",
    description: "Find an installed music app when the user asks to play music or open a music player without naming an app. The phone can ask the user to choose. Do not use for questions, specific tracks, or audio controls.",
    parameters: { type: "object", properties: {} }
  },
  {
    name: "flashlight",
    description: "Turn the phone flashlight, flash light, or torch on or off. Require an explicit instruction.",
    parameters: {
      type: "object", properties: { on: { type: "boolean" } }, required: ["on"]
    }
  },
  {
    name: "set_volume",
    description: "Set media, ring, or alarm volume to a specific percentage. Use media when no stream is named. Do not infer a percentage for a relative change.",
    parameters: {
      type: "object",
      properties: {
        stream: { type: "string", enum: ["media", "ring", "alarm"] },
        percent: { type: "integer", minimum: 0, maximum: 100 }
      },
      required: ["stream", "percent"]
    }
  }
] as const;

const SETTINGS_HANDOFFS = [
  {
    name: "open_settings",
    description: "Open an allowlisted Android Settings screen when Friday cannot change the setting directly. For airplane mode, the user completes the switch in Settings. This action never claims the setting changed.",
    parameters: {
      type: "object",
      properties: {
        setting: { type: "string", enum: ["airplane_mode"] },
        state: { type: "string", enum: ["on", "off"], description: "Requested state, only when the user said on or off." }
      },
      required: ["setting"]
    }
  }
] as const;

const MAX_ACTIONS = 3;
const PLAY_MEDIA = {
  name: "play_media",
  description: "Request a specific song, artist, or album in a music player. Include the player only if the user named it.",
  parameters: {
    type: "object",
    properties: {
      query: { type: "string", description: "Spoken title and any artist, without the play command or player name." },
      appName: { type: "string", description: "Optional music player name as spoken." }
    },
    required: ["query"]
  }
} as const;
const baseActions = FUNCTION_DECLARATIONS.filter(tool => tool.name !== "unsupported" && tool.name !== "unclear_command");
const actionDeclarations = [...baseActions, ...DEVICE_CONTROLS, ...SETTINGS_HANDOFFS, PLAY_MEDIA];

function actionSequence(version: ActionProtocolVersion) {
  const supported = actionDeclarations.filter(tool =>
    supportsAction(tool.name as ActionName, version));
  return {
    name: "run_actions",
    description: "Run two or three supported, explicitly requested actions in spoken order. Only flashlight, volume, and ringer changes can precede another action. A call, clock request, app launch, Settings handoff, or Home must be last. If any part is unsupported, use unsupported for the whole request.",
    parameters: {
      type: "object",
      properties: {
        actions: {
          type: "array", minItems: 2, maxItems: MAX_ACTIONS,
          items: {
            anyOf: supported.map(tool => ({
              type: "object",
              properties: {
                action: { type: "string", enum: [tool.name] },
                args: { ...tool.parameters, additionalProperties: false }
              },
              required: ["action", "args"],
              additionalProperties: false
            }))
          }
        }
      },
      required: ["actions"]
    }
  } as const;
}

export function functionDeclarationsFor(version: ActionProtocolVersion) {
  if (version === 1) return FUNCTION_DECLARATIONS;
  const additions = version >= 3 ? [...DEVICE_CONTROLS, ...SETTINGS_HANDOFFS] : DEVICE_CONTROLS;
  return [...FUNCTION_DECLARATIONS, ...additions, ...(version >= 4 ? [PLAY_MEDIA] : []), actionSequence(version)] as const;
}

export function functionNamesFor(version: ActionProtocolVersion): readonly string[] {
  return functionDeclarationsFor(version).map(tool => tool.name);
}

export function actionInstructionsFor(version: ActionProtocolVersion): string {
  if (version === 1) return "";
  const settings = version >= 3
    ? " Use open_settings for its allowlisted settings. It opens Android Settings; the user completes the change."
    : "";
  const music = version >= 4 ? " Use play_media for named music, including a title followed by a player name. Use open_music for generic music requests and open_app to open a named player." : "";
  return " For several actions, use run_actions for the entire request in spoken order. If any step is unsupported or must leave Friday before a later step, return unsupported without picking a subset." + settings + music;
}

export const EXPANDED_FUNCTION_DECLARATIONS = functionDeclarationsFor(2);
export const EXPANDED_FUNCTION_NAMES = EXPANDED_FUNCTION_DECLARATIONS.map(tool => tool.name);
export const LATEST_FUNCTION_DECLARATIONS = functionDeclarationsFor(LATEST_ACTION_PROTOCOL_VERSION);
export const LATEST_FUNCTION_NAMES = functionNamesFor(LATEST_ACTION_PROTOCOL_VERSION);

export type { ActionName, ActionProtocolVersion } from "./capabilities";

type ActionResponse = { readonly type: "action"; readonly action: ActionName; readonly args: Record<string, unknown> };

export type CommandResponse =
  | ActionResponse
  | { readonly type: "actions"; readonly actions: readonly ActionResponse[] }
  | { readonly type: "unsupported" }
  | { readonly type: "error"; readonly reason: "bad_audio" };

export function parseFunctionSelection(
  selection: FunctionSelection,
  version: ActionProtocolVersion = 1
): CommandResponse {
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
    case "flashlight":
      if (!supportsAction("flashlight", version)) return unsupported();
      return typeof args.on === "boolean" && hasOnlyKeys(args, ["on"])
        ? action("flashlight", { on: args.on })
        : badAudio();
    case "open_music":
      if (!supportsAction("open_music", version)) return unsupported();
      return hasOnlyKeys(args, []) ? action("open_music", {}) : badAudio();
    case "play_media": {
      if (!supportsAction("play_media", version)) return unsupported();
      if (!hasOnlyKeys(args, ["query", "appName"]) || Object.values(args).some(value =>
        typeof value !== "string" || /[\u0000-\u001f\u007f-\u009f]/u.test(value))) return badAudio();
      const query = readShortString(args, "query", 160);
      const appName = readOptionalShortString(args, "appName");
      if (!query || appName === null) return badAudio();
      return action("play_media", appName === undefined ? { query } : { query, appName });
    }
    case "set_volume": {
      if (!supportsAction("set_volume", version)) return unsupported();
      const stream = args.stream;
      const percent = readInteger(args, "percent", 0, 100);
      return (stream === "media" || stream === "ring" || stream === "alarm") &&
        percent !== undefined && hasOnlyKeys(args, ["stream", "percent"])
        ? action("set_volume", { stream, percent })
        : badAudio();
    }
    case "open_settings": {
      if (!supportsAction("open_settings", version)) return unsupported();
      const setting = args.setting;
      const state = args.state;
      if (setting !== "airplane_mode" ||
          (state !== undefined && state !== "on" && state !== "off") ||
          !hasOnlyKeys(args, ["setting", "state"])) return badAudio();
      return action("open_settings", state === undefined ? { setting } : { setting, state });
    }
    case "run_actions":
      return version >= 2 ? parseSequence(args, version) : unsupported();
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
  return parseFunctionSelection({ name, args }, LATEST_ACTION_PROTOCOL_VERSION);
}

function parseSequence(args: Record<string, unknown>, version: ActionProtocolVersion): CommandResponse {
  const requested = args.actions;
  if (!hasOnlyKeys(args, ["actions"]) || !Array.isArray(requested) || requested.length < 2 || requested.length > MAX_ACTIONS) return badAudio();
  const actions: ActionResponse[] = [];
  for (const step of requested) {
    if (!isRecord(step) || !hasOnlyKeys(step, ["action", "args"]) ||
        typeof step.action !== "string" || step.action === "run_actions" || !isRecord(step.args)) return badAudio();
    const result = parseFunctionSelection({ name: step.action, args: step.args }, version);
    if (result.type !== "action") return result;
    if (actions.length < requested.length - 1 && !canPrecedeAnotherAction(result.action)) return unsupported();
    actions.push(result);
  }
  return { type: "actions", actions };
}

function action(actionName: ActionName, args: Record<string, unknown>): ActionResponse {
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
