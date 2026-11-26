import type {
  AssistantProvider,
  AssistantProviderResult,
  Clock,
  CommandInput,
  QuotaDecision,
  SafeLogger,
  SecretBundle,
  SecretProvider,
  UsageLimitStore
} from "../src/core/contracts";

export const TEST_INSTALL_ID = "123e4567-e89b-42d3-a456-426614174000";
export const TEST_NOW = new Date("2026-11-17T08:30:00.000Z");
export const TEST_SECRETS: SecretBundle = {
  geminiApiKey: "test-gemini-key-do-not-use",
  jwtSigningKey: "test-jwt-signing-key-with-at-least-thirty-two-bytes"
};

export class StaticSecrets implements SecretProvider {
  calls = 0;

  constructor(private readonly value: SecretBundle = TEST_SECRETS) {}

  async getSecrets(): Promise<SecretBundle> {
    this.calls += 1;
    return this.value;
  }
}

export class StaticClock implements Clock {
  constructor(private readonly value: Date = TEST_NOW) {}

  now(): Date {
    return new Date(this.value);
  }
}

export class MockUsage implements UsageLimitStore {
  registrationAllowed = true;
  commandAllowed = true;
  registrationCalls: Array<{ identityHash: string; at: Date }> = [];
  commandCalls: Array<{ installId: string; at: Date }> = [];

  async consumeRegistration(identityHash: string, at: Date): Promise<boolean> {
    this.registrationCalls.push({ identityHash, at });
    return this.registrationAllowed;
  }

  async consumeCommand(installId: string, at: Date): Promise<boolean> {
    this.commandCalls.push({ installId, at });
    return this.commandAllowed;
  }

  async reserveCommand(installId: string, at: Date): Promise<QuotaDecision> {
    return await this.consumeCommand(installId, at) ? { allowed: true } : { allowed: false, scope: "blocked" };
  }
}

export class MockAssistant implements AssistantProvider {
  calls: Array<{ input: CommandInput; now: string }> = [];
  result: AssistantProviderResult = {
    selection: { name: "set_timer", args: { seconds: 300 } },
    durationMs: 12.5
  };
  failure: unknown;

  async select(input: CommandInput, now: string): Promise<AssistantProviderResult> {
    this.calls.push({ input, now });
    if (this.failure) throw this.failure;
    return this.result;
  }
}

export class RecordingLogger implements SafeLogger {
  readonly entries: Array<{ code: string; requestId?: string }> = [];

  error(code: string, requestId?: string): void {
    this.entries.push(requestId ? { code, requestId } : { code });
  }
}

export function createPcmWav(options?: {
  sampleRate?: number;
  channels?: number;
  bitsPerSample?: number;
  sampleCount?: number;
}): Buffer {
  const sampleRate = options?.sampleRate ?? 16_000;
  const channels = options?.channels ?? 1;
  const bitsPerSample = options?.bitsPerSample ?? 16;
  const sampleCount = options?.sampleCount ?? 160;
  const bytesPerSample = bitsPerSample / 8;
  const dataSize = sampleCount * channels * bytesPerSample;
  const buffer = Buffer.alloc(44 + dataSize);

  buffer.write("RIFF", 0, "ascii");
  buffer.writeUInt32LE(buffer.length - 8, 4);
  buffer.write("WAVE", 8, "ascii");
  buffer.write("fmt ", 12, "ascii");
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(channels, 22);
  buffer.writeUInt32LE(sampleRate, 24);
  buffer.writeUInt32LE(sampleRate * channels * bytesPerSample, 28);
  buffer.writeUInt16LE(channels * bytesPerSample, 32);
  buffer.writeUInt16LE(bitsPerSample, 34);
  buffer.write("data", 36, "ascii");
  buffer.writeUInt32LE(dataSize, 40);
  return buffer;
}
