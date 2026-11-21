# Friday: accounts, memory and conversational voice

Planning note · 21 November 2026

The owner wants users to register, manage their details, retain useful memory and speak with Friday through a more natural voice experience. This note records the agreed Google sign-in approach, the proposed later phases and the gaps in the current app. Login implementation and dependency approval remain pending.

The current v1 instructions describe a command assistant that cannot answer questions or chat. Conversational answers, stored personal memory and longer voice sessions would expand that scope. Update the project rules, data contract and disclosures when implementing that expansion. Keep native Kotlin, the 15 MB release APK limit, backend-only provider secrets and support for phones without Google Play Services.

## Confirmed account decisions

- Build account registration and sign-in first. Memory, conversational replies and a new voice model belong to later phases.
- Use **Continue with Google** for both new and returning users.
- Require sign-in before cloud features, including command uploads. The new flow will have no guest cloud access.
- Use Google's official native sign-in through **Credential Manager and Google Play Services**, as the owner requested. Google OAuth/OpenID Connect supplies the identity; Friday's backend manages Friday sessions.
- Keep browser-based Google sign-in as the fallback for phones without Play Services, preserving the existing device-support requirement. Users will need a Google account and internet access.
- Keep the existing backend on Vercel and use the owner's new Friday Google Cloud project for sign-in.
- Use **`com.puneet.friday`** as the Android package. Create Android OAuth clients with that package and the signing-certificate fingerprint for each build.

The account implementation now exists in the working tree: verified Google login, expiring Friday sessions, encrypted local credentials, refresh/sign-out and account command quotas. It still needs backend deployment and a completed real Google login test. See [implementation and setup](google-sign-in.md). Memory and conversation remain future phases.

Setup progress: Google Cloud lists **Friday backend** as a Web application client and **Friday Android Debug** as an Android client. The owner reported saving the Web client ID as `GOOGLE_WEB_CLIENT_ID` in Vercel for Production and Preview. Its value stays in environment settings. The rebuilt debug APK uses `com.puneet.friday`, and its signing certificate matches the fingerprint supplied during setup. The Google Cloud listing confirms that both clients exist; it does not verify their settings or a completed login. End-to-end verification remains pending.

## Current implementation and missing pieces

| Area | Available now | Missing for the proposed experience |
|---|---|---|
| Registration | Verified Google login and expiring Friday sessions in the new code; rollout pending | Real client/configuration checks and migration of existing installs |
| User details | Local settings, verified session name/email and sign-out | Permanent profile, profile editing, device list and account deletion |
| Backend access | Account quotas, rotating refresh credentials, session revocation, login/global limits | Pilot enrollment, separate device limits and allowances for chat/generated audio |
| Understanding | One audio request selects one supported phone action | General answers, clarifying questions, follow-up turns and conversational context |
| Memory | No conversation or personal memory store | Consent, selective storage, retrieval, editing, deletion and isolation between users |
| Speech | Android TextToSpeech speaks short confirmations if an English engine exists | Consistent assistant voice, generated-answer playback, streaming and spoken interruption handling |
| Actions | Calls, alarms, timers, app opening, Home and sound modes with local validation | Conversation-aware confirmations, action-result feedback and protection against repeated execution |
| Wake phrase | Opt-in ARM64 debug trial, local model download, notification Pause/Resume and assistant entry | Release integration, broader accuracy checks, long screen-off tests and battery measurements |
| Privacy | Sensitive-permission disclosures; normal command audio is not saved | Account/memory controls, retention rules, public privacy policy and completed Play Data safety documentation |
| Operations | Build/tests, Vercel deployment checks and credential guards | Account support controls, conversation/voice cost measurements and production monitoring for the new flow |

The production backend uses Vercel and Upstash Redis. The new code adds account routes and expiring login/session records alongside usage counters. It adds no permanent profile, memory, chat or live-audio API. The existing AWS adapter still uses install-token auth and needs account support before it can serve the new APK.

## Proposed first-run and daily flow

First run:

1. Show a short welcome and the account/data explanation.
2. Tap **Continue with Google** and choose a Google account through Credential Manager. Use browser sign-in on a phone without Play Services. Require an authenticated Friday session before sending a command to the backend. Consider invitation-based enrollment during the pilot.
3. Ask for an optional display name. Keep other details optional unless a feature needs them.
4. Explain command audio processing and ask for microphone access when the user starts voice setup. Ask for contacts/calls access when setting up calling.
5. Offer **Tap to speak** or **Hey Friday**. Keep model download and the default-assistant selection in this setup. Starting background listening still requires a user action.
6. Offer memory as a separate choice. Users can use phone commands without enabling saved memory.
7. Open the current Home screen with the orb and microphone. Put profile, voice and memory controls in Settings.

Daily use should open the assistant without repeating registration. Refresh an existing account session in the background. If the session expires or the owner revokes access, stop cloud requests and offer sign-in. Signing out must clear account context and stop background wake listening until the user enables it again. Switching accounts must clear the previous user's memory and playback from the phone.

Keep the current compact screens, Geist font, icons and info popups. This proposal does not require a chat bar on Home. Offer readable answers and an optional text-entry fallback without crowding the voice controls.

## Accounts and API access

Use Google's official OAuth/OpenID Connect service, with native account selection through Credential Manager. Do not use the deprecated GoogleSignInClient integration. The Android client obtains a Google ID token for the backend's web client ID and sends it to Friday over HTTPS. The backend must validate the signature, issuer, audience, expiry and a single-use login nonce before issuing a Friday session. Use Google's stable subject identifier for account identity, rather than email as a unique key. Do not accept an ID token by decoding it without verification.

The native ID-token sign-in flow does not require an OAuth client secret in the app. Keep any client secret needed by the browser fallback on the backend. Bind the fallback authorization-code exchange to the requesting app, use PKCE and state/nonce checks, validate callback destinations and link ownership, and keep session tokens out of callback URLs. Request identity/profile scopes only, with no access to Gmail, Drive or Google contacts. Do not request Google offline access when Friday only needs identity.

### Native sign-in dependencies and setup

The owner approved the three native sign-in libraries. The initial candidate download measurements were:

| Library | Stable version checked | Published AAR download |
|---|---|---|
| `androidx.credentials:credentials` | `1.6.0` | 634,646 bytes |
| `androidx.credentials:credentials-play-services-auth` | `1.6.0` | 177,081 bytes |
| `com.google.android.libraries.identity.googleid:googleid` | `1.2.1` | 81,867 bytes |

Those candidate AARs total **893,594 bytes, about 873 KiB**, before transitive dependencies. The implemented Google ID version is **1.1.1**: 1.2.1 failed the project's Kotlin metadata compatibility check. Credential Manager and its provider use 1.6.0. The dependency graph includes AndroidX, Kotlin/coroutines and the audited Play Services authentication modules. See [the test record](testing.md) for the measured APK, rather than using AAR download sizes as APK growth. No backend dependency was added.

Configure an Android OAuth client for the package and signing-certificate fingerprint, and a web client ID for server-side token validation. Include debug and release signing identities as appropriate; Play App Signing uses its own app-signing certificate. Set up consent branding and test users before testing. The browser fallback also needs an exact HTTPS callback URL registered with Google. The owner will enter any secret in backend environment settings, not chat or Git.

Checked Google's [Credential Manager overview](https://developer.android.com/identity/sign-in/credential-manager-siwg), [sign-in implementation guide](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation), [OpenID Connect specification guidance](https://developers.google.com/identity/openid-connect/openid-connect) and [web OAuth setup](https://developers.google.com/identity/protocols/oauth2/web-server) on 21 November 2026. The candidate versions and AAR sizes above come from Google's Maven repository. Durable account storage is a separate decision; Google sign-in does not store Friday profiles or memory.

Implement verified enrollment, returning-user sign-in, recovery, device sessions, short-lived access credentials, refresh/rotation, sign-out, revocation and deletion. Protect locally retained session credentials using Android Keystore-backed storage and keep provider keys on the backend.

Bind every profile, memory, session and quota to the authenticated user on the server. Ignore a client-supplied user ID as proof of ownership. Test that one user's credentials cannot read or change another user's data. Shared phones and two accounts on one phone need explicit isolation tests.

Accounts allow quotas and revocation; they do not prove the caller uses the official APK. Keep registration/verification throttling, per-user and per-device allowances, per-IP abuse controls and global limits. Limit voice duration, generated audio, concurrent sessions and provider spend as well as command counts. Set invite expiry and redemption limits if using invites. Do not embed a shared API secret in the app.

Migrate the existing anonymous installs without leaving public registration as a bypass around the new account limits. An install UUID alone must not let someone claim an account or its memory. Define the transition and rollback before retiring anonymous tokens.

## User details and memory

Start with a small profile: internal user ID, verified sign-in identifier, optional display name, timezone, English locale, voice choice, consent records and account status. Do not request birth date, address or a phone number without a feature that needs it. Keep phone permissions, app aliases and contact matching on the device.

| Information | Proposed handling |
|---|---|
| Current conversation | Keep a bounded session context, with an expiry and a clear end-session control |
| User preferences | Save chosen voice, response length and optional name; retrieve only what the request needs |
| Explicit remembered facts | Offer opt-in saving, show what Friday saved and let the user edit or forget it |
| Past conversations | Decide separately whether to save them; do not treat memory consent as permission to retain all chats |
| Contacts and installed apps | Keep lists and resolution on the phone; local aliases must not bypass contact-match or call-confirmation checks |
| Audio | Preserve the current no-storage policy for ordinary use unless a separate feature and consent require a change |

Memory is a store of selected facts, not training a separate model for each user. For an initial version, use structured preferences and a small session summary. A vector database is not required for this starting point. Choose durable storage with access controls, backups and deletion support before adding account records; do not put them in expiring usage-counter keys.

Add **Memory**, **Remember this**, **Forget this**, editing, deletion and an off switch. Confirm what the user wants saved when the meaning is uncertain. Record a fact's source and update time, expire stale facts and prevent deleted facts from returning through old summaries or caches. Treat stored facts as user data, not as instructions that can override the action rules. Avoid inferring sensitive personal details from ambient speech.

Memory can improve relevance and reduce repeated questions. It does not guarantee faster replies: retrieval and longer prompts add work. Cache small preferences per user, bound context, invalidate caches after edits/deletion and keep account renewal outside the command path. Measure time to first audio and phone-action completion separately. Never share personalised responses through a cache across users.

## Natural speech and conversation

Generating an answer and speaking it are separate capabilities. The current device TTS can speak text, but the backend currently generates no conversational answer text. A new talking model also does not grant Android permissions or execute phone tasks by itself.

Use this order for evaluation:

1. Add short conversational answers and clarification with the existing device TTS as an initial voice baseline.
2. Compare cloud-generated speech for voice quality, Indian English intelligibility, first-audio delay, data use and cost. Keep a text/device-TTS fallback.
3. Evaluate a live speech-to-speech service if continuous conversation and spoken interruptions justify the added transport and session work. Check current model support, retention terms, tool calling, quotas and pricing before choosing it.

The native app needs playback buffering, audio-focus handling, Bluetooth/headset support, Stop, timeouts, disconnect recovery and an orb driven by actual playback. Live conversation also needs turn boundaries, echo handling and interruption detection so Friday stops talking when the user speaks. It must not interpret its own speaker output as a new command or leave the microphone running after a session ends.

The current Vercel adapter buffers a request and returns one JSON result; its configured function duration is 15 seconds. It implements no persistent voice connection. Keep Vercel for account and request/response APIs, and validate the selected transport before promising live voice. A persistent WebSocket relay would need a suitable separate host; choosing it is a later hosting decision. Longer timeouts alone do not provide streaming.

Cloud speech adds recurring cost. Do not assume that existing command-model access includes free live voice. Measure account verification, storage, model input/output, generated audio minutes and concurrent sessions before setting user allowances.

## Keep phone actions correct during conversation

Extend the response contract to distinguish an answer, a clarification, a proposed action and an error. Version it so the current app does not break during rollout. Preserve one model-selection call for a simple command; do not add a mandatory transcription/classification chain to every request. Additional calls for multi-turn conversation or speech need separate measurement.

The phone must validate a proposed action, resolve names locally, check permissions and apply the lock-screen policy. Keep explicit call confirmation. Conversation examples such as “Can Friday call people?” must not place a call. A phrase such as “yes” can confirm only the current, unexpired pending question or action; unrelated speech and background noise must not confirm it.

Report an action's actual Android result back to the session before announcing success. Give each action a one-use identity and guard against duplicates after a retry, reconnect, rotation or repeated callback. Do not replay a call or timer after an uncertain network result. Expire pending confirmations and clear them on account change.

Keep private remembered information out of locked-screen replies until the user unlocks. Apply the existing policy for phone actions, including the visible call confirmation, independently of memory and voice-model output. A natural voice must not imply that Friday recognised or authenticated the speaker.

## Privacy, support and release work

Add account settings, a device/session list, memory controls, export, sign-out and account deletion. Define retention for session context, saved facts, optional history, metadata and backups. Propagate deletion to caches and summaries; document the backup deletion window. Give support/admin access only to the records needed for the issue, and record administrative changes without logging command content.

Revise the microphone disclosure and publish a privacy policy that matches the selected authentication, storage and voice providers. The current no-audio-storage statement must stay true. Saving text history or memory needs its own explanation. The repository does not currently contain the planned privacy-policy or Play Data safety drafts. Add an in-app way to report generated answers when conversational content is introduced.

Keep logs free of audio, transcripts, personal memory and tokens. Monitor request timings, error categories, quota usage and aggregate cost. Test permission denial, missing TTS, poor/offline networks, interrupted playback, account expiry, cross-user access, deletion, duplicate actions, noisy input and unsupported requests. Continue locked-screen, false-wake and battery testing before promoting Hey Friday from debug to release.

## Suggested implementation order

| Phase | Deliverable | Completion evidence |
|---|---|---|
| 1. Identity and access | Sign-up/sign-in, controlled enrollment, sessions, profile, account quotas and anonymous-install migration | Two test users remain isolated; expired/revoked sessions and bypass attempts fail; the existing command flow still works |
| 2. Memory controls | Opt-in preferences, bounded session context and edit/forget/delete | Corrections and deletions affect later replies; no previous user's data remains after sign-out or switching accounts |
| 3. Conversational replies | Answers, clarifications, follow-up turns, device TTS and generated-answer reporting | Existing action regression suite passes; no action on a casual mention or a stale confirmation; speech stops on command |
| 4. Voice comparison | Measured cloud TTS and live-voice prototypes | Indian English quality, first-audio P50/P95, interruptions, fallback behavior and cost recorded before provider selection |
| 5. Release readiness | Wake reliability, battery checks, privacy/data declarations and packaging | Real-device results, account/memory security checks, signed release checks and APK at or below 15 MB |

For each implementation phase, run appropriate automated and phone checks, add regressions for reproducible failures, report APK size after app changes and stop to report the result. Credential checks remain mandatory before any commit or push.

## Decisions to make before implementation

- Whether the pilot also requires an invitation. Google sign-in is required for cloud access; guest cloud commands are not part of the approved flow.
- Check the configured package and fingerprint during the first real login test. The native sign-in dependencies are approved and added.
- Which durable account-storage service to use, with its recurring cost. Google provides sign-in identity; Friday must manage its own account records, sessions and quotas.
- Whether saved memory and conversation history default to off, and their retention/deletion windows. Recommendation: memory opt-in and no saved conversation history at first.
- Whether the first conversation version uses the current device TTS. Recommendation: establish that baseline before paying for live voice.
- The monthly spend limit and account allowances for conversation and speech.
- Which general questions Friday should answer. Web search, current facts and additional languages remain separate scope choices.

Next step: finish verification, then the owner pushes the code and lets Vercel deploy the account routes. Test native sign-in with the Web client ID already saved in Vercel; it needs no Google client secret. Browser fallback configuration and durable account storage remain separate setup steps. The owner will enter additional secrets in backend settings when setup reaches that step. The [voice-provider comparison](voice-provider-notes.md) is marked for later, including a step-by-step credential setup.
