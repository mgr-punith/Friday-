import { createHmac, timingSafeEqual } from "node:crypto";
import type { RedisEvalSender } from "../redis/usage-store";
import { parseReport, type AnswerResponse } from "./answers";

export interface SpeechLimits {
  readonly accountMinute: number;
  readonly accountDailyCharacters: number;
  readonly globalMonthlyCharacters: number;
}
export interface SpeechServices {
  readonly provider: { readonly clientVersion: SpeechClientVersion; synthesize(text: string): Promise<Uint8Array> };
  readonly usage: { reserve(identity: string, answerId: string, characters: number, at: Date): Promise<boolean> };
}
export type SpeechClientVersion = "wavenet-v1" | "elevenlabs-v1";

/** Bind synthesis to one signed answer and the authenticated account; no arbitrary text endpoint. */
export function speechToken(answer: AnswerResponse, identity: string, key: string, clientVersion: SpeechClientVersion): string {
  return createHmac("sha256", key).update(JSON.stringify(["friday-speech:v2", clientVersion, identity,
    answer.answerId, answer.issuedAt, answer.text])).digest("base64url");
}
export function readSpeechRequest(value: unknown, identity: string, key: string, at: Date, clientVersion: SpeechClientVersion): AnswerResponse | null {
  if (!record(value) || Object.keys(value).length !== 2 || !record(value.answer) || typeof value.speechToken !== "string" ||
    !/^[A-Za-z0-9_-]{43}$/.test(value.speechToken)) return null;
  const report = parseReport({ answer: value.answer, reason: "other" }, key, at);
  if (!report || Math.floor(at.getTime() / 1_000) - report.answer.issuedAt > 120) return null;
  const expected = speechToken(report.answer, identity, key, clientVersion);
  return timingSafeEqual(Buffer.from(expected), Buffer.from(value.speechToken)) ? report.answer : null;
}

const RESERVE = `
if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
local amount = tonumber(ARGV[1])
for index = 3, 5 do
  local step = index == 3 and 1 or amount
  local current = tonumber(redis.call('GET', KEYS[index]) or '0')
  if current + step > tonumber(ARGV[(index - 3) * 2 + 2]) then return 0 end
end
redis.call('SET', KEYS[2], '1', 'EX', 240, 'NX')
for index = 3, 5 do
  redis.call('INCRBY', KEYS[index], index == 3 and 1 or amount)
  redis.call('EXPIRE', KEYS[index], tonumber(ARGV[(index - 3) * 2 + 3]))
end
return 1
`;
export class RedisSpeechUsage {
  constructor(private readonly redis: RedisEvalSender, private readonly limits: SpeechLimits) {}
  async reserve(identity: string, answerId: string, characters: number, at: Date): Promise<boolean> {
    if (!Number.isInteger(characters) || characters < 1 || characters > 600) return false;
    const day = at.toISOString().slice(0, 10);
    const month = day.slice(0, 7);
    const minute = at.toISOString().slice(0, 16);
    const monthEnd = Date.UTC(at.getUTCFullYear(), at.getUTCMonth() + 1, 1);
    const monthlyTtl = Math.ceil((monthEnd - at.getTime()) / 1_000) + 86_400;
    const result = await this.redis.eval<unknown>(RESERVE, [
      `friday:v1:install:${identity}:blocked`, `friday:speech:v1:answer:${answerId}`,
      `friday:speech:v1:account:${identity}:minute:${minute}`, `friday:speech:v1:account:${identity}:day:${day}`,
      `friday:speech:v1:global:month:${month}`
    ], [characters, this.limits.accountMinute, 120, this.limits.accountDailyCharacters, 172_800,
      this.limits.globalMonthlyCharacters, monthlyTtl]);
    return result === 1 || result === "1";
  }
}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
