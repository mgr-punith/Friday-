import { Redis } from "@upstash/redis";
import type { RuntimeConfig } from "../config";
import type { UsageLimitStore } from "../core/contracts";

const DAY_SECONDS = 86_400;
const KEY_PREFIX = "friday:v1";

const CONSUME_COUNTERS_SCRIPT = `
for index, key in ipairs(KEYS) do
  local limit = tonumber(ARGV[(index - 1) * 2 + 1])
  local current = tonumber(redis.call("GET", key) or "0")
  if current >= limit then
    return 0
  end
end
for index, key in ipairs(KEYS) do
  local ttl = tonumber(ARGV[(index - 1) * 2 + 2])
  redis.call("INCR", key)
  redis.call("EXPIRE", key, ttl)
end
return 1
`;

const CONSUME_COMMAND_SCRIPT = `
if redis.call("EXISTS", KEYS[1]) == 1 then
  return 0
end
for index = 2, #KEYS do
  local argument = (index - 2) * 2 + 1
  local limit = tonumber(ARGV[argument])
  local current = tonumber(redis.call("GET", KEYS[index]) or "0")
  if current >= limit then
    return 0
  end
end
for index = 2, #KEYS do
  local argument = (index - 2) * 2 + 1
  local ttl = tonumber(ARGV[argument + 1])
  redis.call("INCR", KEYS[index])
  redis.call("EXPIRE", KEYS[index], ttl)
end
return 1
`;

export interface RedisEvalSender {
  eval<TData>(script: string, keys: string[], args: Array<string | number>): Promise<TData>;
}

export class RedisUsageLimitStore implements UsageLimitStore {
  constructor(
    private readonly client: RedisEvalSender,
    private readonly limits: RuntimeConfig
  ) {}

  async consumeRegistration(identityHash: string, at: Date): Promise<boolean> {
    const day = utcDay(at);
    const dayTtl = secondsUntil(endOfUtcDayEpochSeconds(at) + 2 * DAY_SECONDS, at);
    const result = await this.client.eval<unknown>(
      CONSUME_COUNTERS_SCRIPT,
      [
        `${KEY_PREFIX}:registration:ip:${identityHash}:${day}`,
        `${KEY_PREFIX}:registration:global:${day}`
      ],
      [
        this.limits.registerPerIpDaily,
        dayTtl,
        this.limits.registerGlobalDaily,
        dayTtl
      ]
    );
    return allowResult(result);
  }

  async consumeCommand(installId: string, at: Date): Promise<boolean> {
    const minute = utcMinute(at);
    const day = utcDay(at);
    const dayTtl = secondsUntil(endOfUtcDayEpochSeconds(at) + 2 * DAY_SECONDS, at);
    const result = await this.client.eval<unknown>(
      CONSUME_COMMAND_SCRIPT,
      [
        `${KEY_PREFIX}:install:${installId}:blocked`,
        `${KEY_PREFIX}:command:install:${installId}:minute:${minute}`,
        `${KEY_PREFIX}:command:install:${installId}:day:${day}`,
        `${KEY_PREFIX}:command:global:day:${day}`
      ],
      [
        this.limits.commandPerInstallMinute,
        2 * 60 * 60,
        this.limits.commandPerInstallDaily,
        dayTtl,
        this.limits.commandGlobalDaily,
        dayTtl
      ]
    );
    return allowResult(result);
  }
}

export function createRedisClient(url: string, token: string): RedisEvalSender {
  const redis = new Redis({
    url,
    token,
    cache: "no-store",
    enableTelemetry: false,
    keepAlive: true,
    readYourWrites: true,
    retry: { retries: 1, backoff: () => 50 }
  });
  return {
    eval<TData>(script: string, keys: string[], args: Array<string | number>): Promise<TData> {
      return redis.eval<Array<string | number>, TData>(script, keys, args);
    }
  };
}

function allowResult(value: unknown): boolean {
  if (value === 1 || value === "1") return true;
  if (value === 0 || value === "0") return false;
  throw new Error("invalid_redis_limit_result");
}

function utcDay(at: Date): string {
  return at.toISOString().slice(0, 10);
}

function utcMinute(at: Date): string {
  return at.toISOString().slice(0, 16);
}

function endOfUtcDayEpochSeconds(at: Date): number {
  return Math.floor(Date.UTC(at.getUTCFullYear(), at.getUTCMonth(), at.getUTCDate() + 1) / 1000);
}

function secondsUntil(epochSeconds: number, at: Date): number {
  return Math.max(1, epochSeconds - Math.floor(at.getTime() / 1000));
}
