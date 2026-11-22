import type { RedisEvalSender } from "../redis/usage-store";

/** Expiring authentication transactions only. This is not a profile or memory store. */
export interface AuthStore {
  get(key: string): Promise<string | null>;
  put(key: string, value: string, ttl: number): Promise<void>;
  delete(key: string): Promise<void>;
  compareAndSet(key: string, expected: string, replacement: string | null, ttl: number): Promise<boolean>;
  createSession(key: string, accountId: string, value: string, ttl: number, loginStartedAt: number): Promise<boolean>;
  deleteAccount(key: string, accountId: string, expected: string, receipt: string, at: number): Promise<boolean>;
  deletionReceipt(key: string): Promise<string | null>;
}

const PREFIX = "friday:auth:v1:";
const CAS = `
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
if ARGV[2] == '' then redis.call('DEL', KEYS[1])
else redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) end
return 1`;
const CREATE_SESSION = `
local deletedAt = tonumber(redis.call('GET', KEYS[2]) or '0')
if deletedAt >= tonumber(ARGV[3]) then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
return 1`;

// Existing sessions use opaque IDs with no account index. Scan in the same
// transaction as revocation so refresh and login cannot restore erased data.
const DELETE_ACCOUNT = `
if redis.call('GET', KEYS[2]) == ARGV[4] then return 1 end
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
local cursor = '0'
local sessions = {KEYS[1]}
local scanned = 0
repeat
  local page = redis.call('SCAN', cursor, 'MATCH', ARGV[3], 'COUNT', 256)
  cursor = tostring(page[1])
  for _, key in ipairs(page[2]) do
    local value = redis.call('GET', key)
    if value then
      local ok, session = pcall(cjson.decode, value)
      if not ok or type(session) ~= 'table' then return -1 end
      if session.accountId == ARGV[2] and key ~= KEYS[1] then
        sessions[#sessions + 1] = key
      end
    end
  end
  scanned = scanned + 1
  if scanned == 256 and cursor ~= '0' then return -1 end
until cursor == '0'

local deletedAt = math.max(tonumber(ARGV[5]), tonumber(redis.call('GET', KEYS[3]) or '0'))
redis.call('SET', KEYS[3], tostring(deletedAt), 'EX', 600)
for _, key in ipairs(sessions) do redis.call('DEL', key) end
redis.call('SET', KEYS[2], ARGV[4], 'EX', 3600)
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
    return updateResult(result);
  }

  async createSession(key: string, accountId: string, value: string, ttl: number, loginStartedAt: number): Promise<boolean> {
    const result = await this.client.eval<unknown>(CREATE_SESSION, [PREFIX + key, PREFIX + "deleted:" + accountId], [value, ttl, loginStartedAt]);
    return updateResult(result);
  }

  async deleteAccount(key: string, accountId: string, expected: string, receipt: string, at: number): Promise<boolean> {
    const result = await this.client.eval<unknown>(DELETE_ACCOUNT,
      [PREFIX + key, PREFIX + "receipt:" + key, PREFIX + "deleted:" + accountId],
      [expected, accountId, PREFIX + "session:*", receipt, at]);
    return updateResult(result);
  }

  async deletionReceipt(key: string): Promise<string | null> {
    return this.get("receipt:" + key);
  }
}

function updateResult(result: unknown): boolean {
  if (result !== 0 && result !== 1 && result !== "0" && result !== "1") throw new Error("auth_store_update_failed");
  return result === 1 || result === "1";
}
