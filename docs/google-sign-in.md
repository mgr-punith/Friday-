# Google sign-in

Friday requires a verified Google account before sending a cloud command. The Android app uses Credential Manager with the Google Play Services provider. Phones without that provider use browser OAuth once the owner configures its callback. Friday's backend remains on Vercel.

This code has not been deployed. The production `/v1/auth/config` route returned 404 during the 21 November check. Installing the new APK alone cannot enable sign-in against that older deployment.

## Owner setup

1. Keep the Web application client ID in Vercel as `GOOGLE_WEB_CLIENT_ID`. The owner reported adding it for Production and Preview. No native-login client secret goes in the app.
2. Check the Android Debug OAuth client uses package `com.puneet.friday` and the debug signing certificate. Add the release/Play app-signing identities before distributing a signed release. Use Google's consent-screen test-user controls while the OAuth app is in testing.
3. Deploy the reviewed backend change to Vercel before distributing the account-enabled APK. Test a Preview deployment first. The deployment now runs `npm run verify` before packaging.
4. On the phone, tap **Continue with Google**, select a test account and complete consent. Confirm a phone command still works, then sign out and confirm cloud commands stop.

Browser fallback requires two more Vercel settings. The owner enters the secret in Vercel, not chat or Git:

| Variable | Value |
|---|---|
| `GOOGLE_WEB_CLIENT_SECRET` | Secret from the Web application OAuth client |
| `GOOGLE_OAUTH_REDIRECT_URI` | `https://friday-backend-seven.vercel.app/v1/auth/google/callback` |

Add that exact callback under the Web client's authorized redirect URIs. Preview deployments need an explicitly configured, matching callback if browser login is tested there. Do not point a Preview callback at Production while expecting it to share transactions with a separate Redis database.

The browser flow returns to `com.puneet.friday://signin` with a transaction ID. That URL contains no session token or Google authorization code. The initiating app must still supply its private verifier. Verified Android App Links require an owned HTTPS domain and an `assetlinks.json` deployment; that release setup remains separate.

Native and browser sign-in need real Google Cloud client configuration to verify end to end. The mocked server tests do not establish that a configured client or signing certificate is correct.

## Session handling

The backend verifies Google's RSA signature, fixed issuer, Web-client audience, expiry, issued-at time and a short-lived nonce. It derives Friday's account identity from Google's stable subject identifier. It does not trust an email or a client-supplied account ID as proof of identity.

Login transactions last five minutes and bind the install UUID to an SHA-256 verifier challenge. The backend consumes a transaction once. Browser OAuth also uses state and PKCE, and verifies the ID-token nonce after exchanging the code.

Friday access tokens last 15 minutes. Rotating refresh credentials expire after 30 days without a refresh. The backend checks the expiring Redis session on each authorized command so online sign-out revokes access at once. A distinct logout credential lets sign-out win a race with refresh. The app clears its local account before waiting for the network and retains an encrypted pending logout when offline.

The app encrypts credentials and account labels in private SharedPreferences with Android Keystore AES-GCM. It prevents a stale login or refresh from saving credentials after cancellation or sign-out. It never saves the Google ID token. Logs exclude credentials and provider error messages.

Upstash stores expiring authentication transactions and session records, including the verified name/email used in Settings. It stores a hash of each refresh token, not its original value. This phase adds no permanent profile, conversation history or memory database. Session expiry alone is not a complete account-deletion feature; deletion controls and the public privacy policy remain release work.

Command quotas now use the authenticated account identity across devices. Existing `COMMAND_PER_INSTALL_*` environment names retain their names for deployment compatibility, but the account-enabled Vercel router supplies the account identity. The global limit and login-issuance limits remain active. Separate device quotas and pilot invitations remain future choices.

The Vercel router rejects anonymous `/v1/register` calls and old install-only tokens. The AWS fallback still implements the older install-token protocol. It is **not ready for this account-enabled APK** and must receive equivalent account support before use.

## Protocol

| Endpoint | Purpose |
|---|---|
| `GET /v1/auth/config` | Public client ID and browser availability |
| `POST /v1/auth/challenge` | Rate-limited nonce and verifier-bound transaction |
| `POST /v1/auth/google` | Verify the native Google ID token and create a Friday session |
| `GET /v1/auth/google/callback` | Validate browser state and retain the code for app redemption |
| `POST /v1/auth/browser` | Complete the browser exchange with the app-held verifier |
| `POST /v1/auth/refresh` | Rotate a refresh credential and issue a short access token |
| `POST /v1/auth/logout` | Revoke the specified session using its logout credential |

Auth responses use `Cache-Control: no-store`. Failed verification or unavailable Redis does not grant access. Warm-up remains public and does not call Gemini.

## Approved Android dependencies

The owner approved Credential Manager, its Play Services provider and Google ID on 21 November:

- `androidx.credentials:credentials:1.6.0`
- `androidx.credentials:credentials-play-services-auth:1.6.0`
- `com.google.android.libraries.identity.googleid:googleid:1.1.1`

Google ID 1.2.1 failed compilation because its Kotlin metadata requires a newer compiler than this project's embedded Kotlin 2.2 compiler. This change pins the compatible 1.1.1 release; compiler checks remain enabled. CI compiles the app and audits the pinned root dependencies plus the specific Play Services authentication modules. Firebase, ML Kit, unrelated Play Services modules and AWS app SDKs remain forbidden.

See [the test record](testing.md) for measured APK size, checks run and remaining phone tests.
