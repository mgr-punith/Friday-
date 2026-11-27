import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import type { CommandResponse } from "./action-schema";
import type { SpeechClientVersion } from "./speech";

export interface ConfirmationSpeech {
  readonly text: string;
  readonly speechId: string;
  readonly issuedAt: number;
  readonly token: string;
}

/** Use only validated actions and fixed phrases; private names and labels never enter speech. */
export function signConfirmation(response: CommandResponse, identity: string, key: string, at: Date): ConfirmationSpeech | null {
  const text = confirmationText(response);
  if (!text) return null;
  const content = { text, speechId: randomUUID(), issuedAt: Math.floor(at.getTime() / 1_000) };
  return { ...content, token: token(content, identity, key) };
}

export function readConfirmationSpeech(value: unknown, identity: string, key: string, at: Date,
  clientVersion: SpeechClientVersion): ConfirmationSpeech | null {
  if (clientVersion !== "elevenlabs-v1" || !record(value) || Object.keys(value).length !== 1 ||
    !record(value.confirmation)) return null;
  const speech = value.confirmation;
  if (Object.keys(speech).length !== 4 || typeof speech.text !== "string" || !speech.text || speech.text.length > 200 ||
    /[\u0000-\u001f\u007f]/.test(speech.text) || typeof speech.speechId !== "string" ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(speech.speechId) ||
    typeof speech.issuedAt !== "number" || !Number.isSafeInteger(speech.issuedAt) ||
    typeof speech.token !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(speech.token)) return null;
  const age = Math.floor(at.getTime() / 1_000) - speech.issuedAt;
  if (age < 0 || age > 120) return null;
  const content = { text: speech.text, speechId: speech.speechId, issuedAt: speech.issuedAt };
  return timingSafeEqual(Buffer.from(token(content, identity, key)), Buffer.from(speech.token))
    ? { ...content, token: speech.token } : null;
}

function token(content: Omit<ConfirmationSpeech, "token">, identity: string, key: string): string {
  return createHmac("sha256", key).update(JSON.stringify(["friday-confirmation:v1", "elevenlabs-v1", identity,
    content.speechId, content.issuedAt, content.text])).digest("base64url");
}

function confirmationText(response: CommandResponse): string | null {
  if (response.type === "actions") {
    const phrases = response.actions.map(confirmationText);
    return phrases.every((text): text is string => text !== null) ? phrases.join(". ") : null;
  }
  if (response.type !== "action") return null;
  switch (response.action) {
    case "set_ringer_mode": return { silent: "Silent on", vibrate: "Vibrate on", normal: "Normal mode" }[String(response.args.mode)] ?? null;
    case "flashlight": return response.args.on ? "Flashlight on" : "Flashlight off";
    case "set_volume": return `${{ media: "Media", ring: "Ring", alarm: "Alarm" }[String(response.args.stream)]} volume set`;
    case "close_app": return "Went to home screen";
    case "set_alarm": return "Alarm requested";
    case "set_timer": return "Timer requested";
    case "open_app": return "Opening app";
    case "open_settings": return "Check settings";
    case "open_music": return response.args.play === true ? "Music requested" : "Music app opened";
    case "play_media": return "Music requested";
    case "control_media": return { pause: "Pause requested", resume: "Resume requested", next: "Next track requested",
      previous: "Previous track requested" }[String(response.args.operation)] ?? null;
    case "call_contact": case "call_number": return null;
  }
}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
