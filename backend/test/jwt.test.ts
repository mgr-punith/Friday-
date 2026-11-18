import assert from "node:assert/strict";
import test from "node:test";
import {
  hashRegistrationIdentity,
  isInstallId,
  signInstallToken,
  verifyInstallToken
} from "../src/core/jwt";
import { TEST_INSTALL_ID, TEST_NOW, TEST_SECRETS } from "./helpers";

test("signs and verifies an install token", () => {
  const token = signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW, 300);
  assert.equal(verifyInstallToken(token, TEST_SECRETS.jwtSigningKey, TEST_NOW), TEST_INSTALL_ID);
});

test("rejects tampered, expired, and wrong-key tokens", () => {
  const token = signInstallToken(TEST_INSTALL_ID, TEST_SECRETS.jwtSigningKey, TEST_NOW, 30);
  const tampered = `${token.slice(0, -1)}${token.endsWith("a") ? "b" : "a"}`;
  assert.equal(verifyInstallToken(tampered, TEST_SECRETS.jwtSigningKey, TEST_NOW), null);
  assert.equal(verifyInstallToken(token, "another-signing-key-with-at-least-thirty-two-bytes", TEST_NOW), null);
  assert.equal(verifyInstallToken(token, TEST_SECRETS.jwtSigningKey, new Date(TEST_NOW.getTime() + 31_000)), null);
});

test("validates UUID install identifiers", () => {
  assert.equal(isInstallId(TEST_INSTALL_ID), true);
  assert.equal(isInstallId("not-a-uuid"), false);
  assert.equal(isInstallId("00000000-0000-0000-0000-000000000000"), false);
});

test("hashes registration identities without retaining the source IP", () => {
  const one = hashRegistrationIdentity("192.0.2.1", TEST_SECRETS.jwtSigningKey);
  const two = hashRegistrationIdentity("192.0.2.1", TEST_SECRETS.jwtSigningKey);
  const other = hashRegistrationIdentity("192.0.2.2", TEST_SECRETS.jwtSigningKey);
  assert.equal(one, two);
  assert.notEqual(one, other);
  assert.equal(one.includes("192.0.2.1"), false);
});
