import type { RedisEvalSender } from "../redis/usage-store";

/** Expiring authentication transactions only. This is not a profile or memory store. */
export interface AuthStore {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, ttl: number): Promise<void>;
  delete(key: string): Promise<void>;
  compareAndSet(key: string, expected: string, replacement: string | null, ttl: number): Promise<boolean>;
}

const PREFIX = "friday:auth:v1:";
const CAS = `
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
if ARGV[2] == '' then redis.call('DEL', KEYS[1])
else redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) end
return 1`;

export class RedisAuthStore implements AuthStore {
  constructor(private readonly client: RedisEvalSender) {}

  async get(key: string): Promise<string | null> {
    const value = await this.client.eval<unknown>("return redis.call('GET', KEYS[1])", [PREFIX + key], []);
    if (value === null || value === false) return null;
    if (typeof value !== "string") throw new Error("invalid_auth_store_response");
    return value;
  }

  async put(key: string, value: string, ttl: number): Promise<void> {
    const result = await this.client.eval<unknown>("return redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])", [PREFIX + key], [value, ttl]);
    if (result !== "OK") throw new Error("auth_store_write_failed");
  }

  async delete(key: string): Promise<void> {
    const result = await this.client.eval<unknown>("return redis.call('DEL', KEYS[1])", [PREFIX + key], []);
    if (result !== 0 && result !== 1) throw new Error("auth_store_delete_failed");
  }

  async compareAndSet(key: string, expected: string, replacement: string | null, ttl: number): Promise<boolean> {
    const result = await this.client.eval<unknown>(CAS, [PREFIX + key], [expected, replacement ?? "", ttl]);
    if (result !== 0 && result !== 1 && result !== "0" && result !== "1") throw new Error("auth_store_update_failed");
    return result === 1 || result === "1";
  }
}
