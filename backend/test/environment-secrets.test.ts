import assert from "node:assert/strict";
import test from "node:test";
import { EnvironmentSecretProvider } from "../src/env/secrets";

test("returns trimmed environment secrets", async () => {
  const provider = new EnvironmentSecretProvider(
    "  development-gemini-key  ",
    "  a-long-jwt-signing-key-with-more-than-thirty-two-bytes  "
  );
  assert.deepEqual(await provider.getSecrets(), {
    geminiApiKey: "development-gemini-key",
    jwtSigningKey: "a-long-jwt-signing-key-with-more-than-thirty-two-bytes"
  });
});

test("rejects short environment secrets", () => {
  assert.throws(
    () => new EnvironmentSecretProvider("short", "a-long-jwt-signing-key-with-more-than-thirty-two-bytes"),
    /invalid_gemini_api_key/
  );
  assert.throws(
    () => new EnvironmentSecretProvider("development-gemini-key", "short"),
    /invalid_jwt_signing_key/
  );
});
