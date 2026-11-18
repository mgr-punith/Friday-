import {
  GetParametersCommand,
  SSMClient,
  type GetParametersCommandOutput
} from "@aws-sdk/client-ssm";
import type { SecretBundle, SecretProvider } from "../core/contracts";

export interface SsmSender {
  send(command: GetParametersCommand): Promise<GetParametersCommandOutput>;
}

export class CachedSsmSecretProvider implements SecretProvider {
  private cached: Promise<SecretBundle> | undefined;

  constructor(
    private readonly client: SsmSender,
    private readonly geminiApiKeyParameterName: string,
    private readonly jwtSigningKeyParameterName: string
  ) {}

  getSecrets(): Promise<SecretBundle> {
    if (!this.cached) {
      this.cached = this.load().catch((error: unknown) => {
        this.cached = undefined;
        throw error;
      });
    }
    return this.cached;
  }

  private async load(): Promise<SecretBundle> {
    const output = await this.client.send(
      new GetParametersCommand({
        Names: [this.geminiApiKeyParameterName, this.jwtSigningKeyParameterName],
        WithDecryption: true
      })
    );

    if ((output.InvalidParameters?.length ?? 0) > 0) throw new Error("missing_secret_parameter");
    const values = new Map<string, string>();
    for (const parameter of output.Parameters ?? []) {
      if (parameter.Name && parameter.Value) values.set(parameter.Name, parameter.Value.trim());
    }

    const geminiApiKey = values.get(this.geminiApiKeyParameterName);
    const jwtSigningKey = values.get(this.jwtSigningKeyParameterName);
    if (!geminiApiKey || geminiApiKey.length < 16) throw new Error("invalid_gemini_api_key");
    if (!jwtSigningKey || jwtSigningKey.length < 32) throw new Error("invalid_jwt_signing_key");
    return { geminiApiKey, jwtSigningKey };
  }
}

export function createSsmClient(): SSMClient {
  return new SSMClient({ maxAttempts: 2 });
}
