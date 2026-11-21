# Backend deployment

Vercel hosts Friday's primary backend in Mumbai. The repository keeps the AWS SAM deployment as a fallback.

## Local verification

Use Node.js 24:

```bash
cd backend
npm ci
npm run verify
```

`npm run verify` checks the Vercel route map and function settings without a Vercel token. Vercel runs the platform build when it creates a preview or production deployment from Git.

The project uses `backend/public` for a small root page. Vercel deploys the routes in `backend/api` as Functions.

Keep `@types/node` in production dependencies. Vercel's Function transpiler reads the project's TypeScript configuration after the static build and needs the Node declarations.

`npm run eval` needs a Gemini key. Copy `.env.example` to `.env`, replace the fake value, run the evaluation, then keep `.env` outside Git.

## Vercel project

Import the GitHub repository into Vercel and set the root directory to `backend`. Vercel deploys the function in Mumbai (`bom1`) and routes these paths through one shared function:

- `POST /v1/register`
- `GET /v1/warm`
- `POST /v1/command`
- `/v1/auth/...` for verified Google login and Friday sessions

Connect the production branch to `main`. Vercel creates preview deployments for other branches and deploys production after a push to `main`.

## Upstash Redis

Create one Redis database in or near Mumbai through the Vercel Marketplace or Upstash console. Friday stores rate-limit counters, blocked-identity flags, five-minute login transactions and expiring account sessions. Sessions include verified name/email and a refresh-token hash. Browser transactions can retain an authorization code until app redemption or expiry. Friday does not store command audio, text commands, contacts or installed-app lists. See [session retention and account setup](google-sign-in.md).

The Upstash Vercel integration can add these values to the project:

- `UPSTASH_REDIS_REST_URL`
- `UPSTASH_REDIS_REST_TOKEN`

## Vercel secrets

Add each value through Vercel project settings. Apply them to Production and Preview only when preview deployments need live services.

- `GEMINI_API_KEY`: paid-tier Gemini key for production
- `JWT_SIGNING_KEY`: random value with at least 32 characters
- `GEMINI_MODEL`: `gemini-3.1-flash-lite`
- `GEMINI_THINKING_LEVEL`: `MINIMAL`
- `GEMINI_TIMEOUT_MS`: `10000`
- `GOOGLE_WEB_CLIENT_ID`: the Web application OAuth client ID; required for the account router

Do not send these values in chat, store them in GitHub, or commit them. Friday reads them from Vercel's encrypted environment at function start.

Optional limit variables keep the defaults unless you set them:

Native Google sign-in needs no client secret. Browser fallback also needs `GOOGLE_WEB_CLIENT_SECRET`, `GOOGLE_OAUTH_REDIRECT_URI` and the exact callback in Google's Web OAuth client. Follow [Google sign-in setup](google-sign-in.md) when configuring them. Do not add unused voice-provider credentials yet.

- `REGISTER_PER_IP_DAILY=200`
- `REGISTER_GLOBAL_DAILY=10000`
- `COMMAND_PER_INSTALL_MINUTE=12`
- `COMMAND_PER_INSTALL_DAILY=100`
- `COMMAND_GLOBAL_DAILY=100000`

## Deployment order

1. Import the repository and select `backend` as the root directory.
2. Add the Upstash integration.
3. Add the Gemini and JWT values.
4. deploy a preview and test `/v1/warm`.
5. Promote the tested deployment or merge the branch into `main`.

Copy the production URL into Android `BuildConfig.BACKEND_URL` during milestone 3.

The account-enabled APK requires the new backend routes before its first sign-in. Deploy and test that backend before distributing the APK. The router disables anonymous registration and old install tokens, so this rollout also requires existing users to install the account-enabled app. There is no anonymous cloud bypass. Check [change-based CI and deployment verification](ci-checks.md) before merging.

## AWS fallback

The SAM template, DynamoDB adapter, and SSM adapter remain in the repository. Run these checks before an AWS fallback deployment:

```bash
cd backend
npm run verify
sam validate --lint --template-file template.yaml
sam build --template-file template.yaml
```

The owner configures AWS credentials and runs `sam deploy`. Codex does not handle AWS credentials.

The AWS adapter still uses the earlier anonymous install-token protocol. Its packaging checks remain in CI, but it cannot serve the account-enabled APK until equivalent account support is implemented. Do not switch the new app to it as a login fallback.
