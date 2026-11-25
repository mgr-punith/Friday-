import { createCipheriv, createDecipheriv, createHmac, randomBytes } from "node:crypto";

export interface AnswerContext {
  readonly question: string;
  readonly turns: readonly { readonly prompt: string; readonly reply: string }[];
  readonly prompt: string;
  readonly remaining: 0 | 1;
  readonly expiresAt: number;
}

const SCOPE = "friday-answer-continuation:v1";
const LIFETIME_SECONDS = 120;
const MAX_TOKEN_LENGTH = 4_096;

/** Keep at most two clarification replies in an encrypted, account-bound, short-lived token. */
export function createAnswerContinuation(
  questionOrReply: string,
  prompt: string,
  identity: string,
  signingKey: string,
  at: Date,
  previous?: AnswerContext,
): string | null {
  if (!boundedText(questionOrReply, 500) || !boundedText(prompt, 600) || previous?.remaining === 0) return null;
  const context: AnswerContext = previous ? {
    question: previous.question,
    turns: [...previous.turns, { prompt: previous.prompt, reply: questionOrReply }],
    prompt, remaining: 0, expiresAt: previous.expiresAt,
  } : { question: questionOrReply, turns: [], prompt, remaining: 1,
    expiresAt: Math.floor(at.getTime() / 1_000) + LIFETIME_SECONDS };
  if (!validContext(context, at)) return null;
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", contextKey(signingKey), iv);
  cipher.setAAD(Buffer.from(`${SCOPE}\0${identity}`));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(context), "utf8"), cipher.final()]);
  const token = Buffer.concat([iv, cipher.getAuthTag(), ciphertext]).toString("base64url");
  return token.length <= MAX_TOKEN_LENGTH ? token : null;
}

export function readAnswerContinuation(
  token: string,
  identity: string,
  signingKey: string,
  at: Date,
): AnswerContext | null {
  if (token.length < 64 || token.length > MAX_TOKEN_LENGTH || !/^[A-Za-z0-9_-]+$/.test(token)) return null;
  try {
    const bytes = Buffer.from(token, "base64url");
    const decipher = createDecipheriv("aes-256-gcm", contextKey(signingKey), bytes.subarray(0, 12));
    decipher.setAAD(Buffer.from(`${SCOPE}\0${identity}`));
    decipher.setAuthTag(bytes.subarray(12, 28));
    const plaintext = Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]);
    const context: unknown = JSON.parse(plaintext.toString("utf8"));
    return validContext(context, at) ? context : null;
  } catch { return null; }
}

function contextKey(signingKey: string): Buffer {
  return createHmac("sha256", signingKey).update(SCOPE).digest();
}

function validContext(value: unknown, at: Date): value is AnswerContext {
  if (!record(value) || Object.keys(value).some(key => !["question", "turns", "prompt", "remaining", "expiresAt"].includes(key)) ||
    !boundedText(value.question, 500) || !boundedText(value.prompt, 600) || !Array.isArray(value.turns) ||
    ![0, 1].includes(Number(value.remaining)) || typeof value.remaining !== "number" ||
    value.turns.length + value.remaining !== 1 || typeof value.expiresAt !== "number" || !Number.isSafeInteger(value.expiresAt)) return false;
  const now = Math.floor(at.getTime() / 1_000);
  if (value.expiresAt <= now || value.expiresAt > now + LIFETIME_SECONDS) return false;
  return value.turns.every(turn => record(turn) && Object.keys(turn).length === 2 &&
    boundedText(turn.prompt, 600) && boundedText(turn.reply, 500));
}

function boundedText(value: unknown, length: number): value is string {
  return typeof value === "string" && value.trim().length > 0 && value.length <= length && !/[\u0000-\u001f\u007f]/.test(value);
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
