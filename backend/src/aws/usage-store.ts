import {
  DynamoDBClient,
  TransactWriteItemsCommand,
  type TransactWriteItemsCommandOutput,
  type TransactWriteItem
} from "@aws-sdk/client-dynamodb";
import type { UsageLimitStore } from "../core/contracts";

const DAY_SECONDS = 86_400;

export interface DynamoSender {
  send(command: TransactWriteItemsCommand): Promise<TransactWriteItemsCommandOutput>;
}

export interface UsageLimits {
  readonly registerPerIpDaily: number;
  readonly registerGlobalDaily: number;
  readonly commandPerInstallMinute: number;
  readonly commandPerInstallDaily: number;
  readonly commandGlobalDaily: number;
}

export class DynamoUsageLimitStore implements UsageLimitStore {
  constructor(
    private readonly client: DynamoSender,
    private readonly tableName: string,
    private readonly limits: UsageLimits
  ) {}

  async consumeRegistration(identityHash: string, at: Date): Promise<boolean> {
    const day = utcDay(at);
    const expiresAt = endOfUtcDayEpochSeconds(at) + 2 * DAY_SECONDS;
    return this.transact([
      counter(
        this.tableName,
        `registration:ip:${identityHash}:${day}`,
        this.limits.registerPerIpDaily,
        expiresAt
      ),
      counter(
        this.tableName,
        `registration:global:${day}`,
        this.limits.registerGlobalDaily,
        expiresAt
      )
    ]);
  }

  async consumeCommand(installId: string, at: Date): Promise<boolean> {
    const minute = utcMinute(at);
    const day = utcDay(at);
    const minuteExpiresAt = Math.floor(at.getTime() / 1000) + 2 * 60 * 60;
    const dayExpiresAt = endOfUtcDayEpochSeconds(at) + 2 * DAY_SECONDS;

    return this.transact([
      {
        ConditionCheck: {
          TableName: this.tableName,
          Key: { pk: { S: `install:${installId}` } },
          ConditionExpression: "attribute_not_exists(#blocked) OR #blocked = :false",
          ExpressionAttributeNames: { "#blocked": "blocked" },
          ExpressionAttributeValues: { ":false": { BOOL: false } }
        }
      },
      counter(
        this.tableName,
        `command:install:${installId}:minute:${minute}`,
        this.limits.commandPerInstallMinute,
        minuteExpiresAt
      ),
      counter(
        this.tableName,
        `command:install:${installId}:day:${day}`,
        this.limits.commandPerInstallDaily,
        dayExpiresAt
      ),
      counter(
        this.tableName,
        `command:global:day:${day}`,
        this.limits.commandGlobalDaily,
        dayExpiresAt
      )
    ]);
  }

  private async transact(items: TransactWriteItem[]): Promise<boolean> {
    try {
      await this.client.send(new TransactWriteItemsCommand({ TransactItems: items }));
      return true;
    } catch (error) {
      if (isLimitExceeded(error)) return false;
      throw error;
    }
  }
}

export function createDynamoClient(): DynamoDBClient {
  return new DynamoDBClient({ maxAttempts: 2 });
}

function counter(
  tableName: string,
  key: string,
  limit: number,
  expiresAt: number
): TransactWriteItem {
  return {
    Update: {
      TableName: tableName,
      Key: { pk: { S: key } },
      UpdateExpression: "SET #count = if_not_exists(#count, :zero) + :one, #expiresAt = :expiresAt",
      ConditionExpression: "attribute_not_exists(#count) OR #count < :limit",
      ExpressionAttributeNames: {
        "#count": "count",
        "#expiresAt": "expiresAt"
      },
      ExpressionAttributeValues: {
        ":zero": { N: "0" },
        ":one": { N: "1" },
        ":limit": { N: String(limit) },
        ":expiresAt": { N: String(expiresAt) }
      }
    }
  };
}

function utcDay(at: Date): string {
  return at.toISOString().slice(0, 10);
}

function utcMinute(at: Date): string {
  return at.toISOString().slice(0, 16);
}

function endOfUtcDayEpochSeconds(at: Date): number {
  return Math.floor(
    Date.UTC(at.getUTCFullYear(), at.getUTCMonth(), at.getUTCDate() + 1) / 1000
  );
}

function isLimitExceeded(value: unknown): boolean {
  if (
    typeof value !== "object" ||
    value === null ||
    !("name" in value) ||
    value.name !== "TransactionCanceledException" ||
    !("CancellationReasons" in value) ||
    !Array.isArray(value.CancellationReasons)
  ) return false;

  return value.CancellationReasons.some((reason: unknown) => (
    typeof reason === "object" &&
    reason !== null &&
    "Code" in reason &&
    reason.Code === "ConditionalCheckFailed"
  ));
}
