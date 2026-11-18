import assert from "node:assert/strict";
import test from "node:test";
import type { TransactWriteItemsCommandOutput } from "@aws-sdk/client-dynamodb";
import { DynamoUsageLimitStore, type DynamoSender } from "../src/aws/usage-store";
import { TEST_INSTALL_ID, TEST_NOW } from "./helpers";

const LIMITS = {
  registerPerIpDaily: 200,
  registerGlobalDaily: 10_000,
  commandPerInstallMinute: 12,
  commandPerInstallDaily: 100,
  commandGlobalDaily: 100_000
};

test("registration uses one atomic transaction for IP and global counters", async () => {
  const commands: any[] = [];
  const client: DynamoSender = {
    async send(command): Promise<TransactWriteItemsCommandOutput> {
      commands.push(command);
      return { $metadata: {} };
    }
  };
  const store = new DynamoUsageLimitStore(client, "FridayUsage", LIMITS);
  assert.equal(await store.consumeRegistration("identity-hash", TEST_NOW), true);

  const items = commands[0].input.TransactItems;
  assert.equal(items.length, 2);
  assert.equal(items[0].Update.Key.pk.S.includes("identity-hash"), true);
  assert.equal(items[1].Update.Key.pk.S, "registration:global:2026-11-17");
  assert.equal(items[0].Update.ExpressionAttributeValues[":limit"].N, "200");
});

test("commands check the blocked flag and three counters atomically", async () => {
  const commands: any[] = [];
  const client: DynamoSender = {
    async send(command): Promise<TransactWriteItemsCommandOutput> {
      commands.push(command);
      return { $metadata: {} };
    }
  };
  const store = new DynamoUsageLimitStore(client, "FridayUsage", LIMITS);
  assert.equal(await store.consumeCommand(TEST_INSTALL_ID, TEST_NOW), true);

  const items = commands[0].input.TransactItems;
  assert.equal(items.length, 4);
  assert.equal(items[0].ConditionCheck.Key.pk.S, `install:${TEST_INSTALL_ID}`);
  assert.equal(items[1].Update.Key.pk.S.includes(":minute:2026-11-17T08:30"), true);
  assert.equal(items[2].Update.Key.pk.S.includes(":day:2026-11-17"), true);
  assert.equal(items[3].Update.Key.pk.S, "command:global:day:2026-11-17");
});

test("a canceled transaction returns limited while infrastructure errors fail closed", async () => {
  const canceled: DynamoSender = {
    async send(): Promise<TransactWriteItemsCommandOutput> {
      const error = Object.assign(new Error("limited"), {
        name: "TransactionCanceledException",
        CancellationReasons: [{ Code: "ConditionalCheckFailed" }]
      });
      throw error;
    }
  };
  const limitedStore = new DynamoUsageLimitStore(canceled, "FridayUsage", LIMITS);
  assert.equal(await limitedStore.consumeCommand(TEST_INSTALL_ID, TEST_NOW), false);

  const unavailable: DynamoSender = {
    async send(): Promise<TransactWriteItemsCommandOutput> {
      throw new Error("unavailable");
    }
  };
  const failingStore = new DynamoUsageLimitStore(unavailable, "FridayUsage", LIMITS);
  await assert.rejects(failingStore.consumeCommand(TEST_INSTALL_ID, TEST_NOW), /unavailable/);

  const conflicted: DynamoSender = {
    async send(): Promise<TransactWriteItemsCommandOutput> {
      const error = Object.assign(new Error("conflict"), {
        name: "TransactionCanceledException",
        CancellationReasons: [{ Code: "TransactionConflict" }]
      });
      throw error;
    }
  };
  const conflictedStore = new DynamoUsageLimitStore(conflicted, "FridayUsage", LIMITS);
  await assert.rejects(conflictedStore.consumeCommand(TEST_INSTALL_ID, TEST_NOW), /conflict/);
});
