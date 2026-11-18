import assert from "node:assert/strict";
import test from "node:test";
import type { GetParametersCommandOutput } from "@aws-sdk/client-ssm";
import { CachedSsmSecretProvider, type SsmSender } from "../src/aws/secrets";

const GEMINI_NAME = "/friday/test/gemini-api-key";
const JWT_NAME = "/friday/test/jwt-signing-key";

test("loads both SecureString values once and caches them", async () => {
  let calls = 0;
  const client: SsmSender = {
    async send(): Promise<GetParametersCommandOutput> {
      calls += 1;
      return {
        Parameters: [
          { Name: JWT_NAME, Value: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes" },
          { Name: GEMINI_NAME, Value: "development-gemini-key" }
        ],
        $metadata: {}
      };
    }
  };
  const provider = new CachedSsmSecretProvider(client, GEMINI_NAME, JWT_NAME);
  const [first, second] = await Promise.all([provider.getSecrets(), provider.getSecrets()]);
  assert.deepEqual(first, second);
  assert.equal(calls, 1);
});

test("does not cache a failed secret load", async () => {
  let calls = 0;
  const client: SsmSender = {
    async send(): Promise<GetParametersCommandOutput> {
      calls += 1;
      if (calls === 1) throw new Error("temporary");
      return {
        Parameters: [
          { Name: JWT_NAME, Value: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes" },
          { Name: GEMINI_NAME, Value: "development-gemini-key" }
        ],
        $metadata: {}
      };
    }
  };
  const provider = new CachedSsmSecretProvider(client, GEMINI_NAME, JWT_NAME);
  await assert.rejects(provider.getSecrets(), /temporary/);
  await provider.getSecrets();
  assert.equal(calls, 2);
});

test("rejects missing and short secret values", async () => {
  const missing: SsmSender = {
    async send(): Promise<GetParametersCommandOutput> {
      return { InvalidParameters: [GEMINI_NAME], $metadata: {} };
    }
  };
  await assert.rejects(
    new CachedSsmSecretProvider(missing, GEMINI_NAME, JWT_NAME).getSecrets(),
    /missing_secret_parameter/
  );

  const short: SsmSender = {
    async send(): Promise<GetParametersCommandOutput> {
      return {
        Parameters: [
          { Name: GEMINI_NAME, Value: "short" },
          { Name: JWT_NAME, Value: "also-short" }
        ],
        $metadata: {}
      };
    }
  };
  await assert.rejects(
    new CachedSsmSecretProvider(short, GEMINI_NAME, JWT_NAME).getSecrets(),
    /invalid_gemini_api_key/
  );
});
