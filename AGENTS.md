# AGENTS.md

Instructions for AI coding agents (Codex) working on this repo. Read this fully before changing anything.

## Project goal

A fast, light, English-only voice assistant for quick phone tasks. It can **replace the phone's default assistant** (the app Android opens on a power-button long press). It does not depend on Google on the device, so it runs on any Android phone, with or without Google services.

Think "Gemini, but smaller and faster, doing only small jobs". It is a command app, not a chatbot.

## Scope (v1)

Does:
- Call a contact or a number.
- Set an alarm or a timer.
- Open an app.
- "Close" an app (really: go to the Home screen, see the close_app notes).
- Switch silent, vibrate, or normal sound mode.

Does not (yet): answer questions, search the web, chat, or understand languages other than English.

Anything else the user says gets a short "Sorry, I can't do that yet".

## Scale

- About 500 users now. Plan for 5,000 without redesign.
- Assume about 10 voice commands per active user per day. At 5,000 users that is a peak of roughly 5 to 10 backend requests per second.
- Every command is exactly one Gemini call. Keep the prompt and the output tiny, because that is both the cost and the speed.

## Hard rules (never break these)

1. **Release APK must be 15 MB or less.** Check the size after every dependency or asset change and report it.
2. **No required Google dependency on the device.** The app must work fully on a phone without Google Play Services. Do not use `com.google.android.gms`, `com.google.firebase`, ML Kit, Google Sign-In, `SpeechRecognizer`, or a Gemini SDK in the app. If an optional Google feature would help, ask the owner first, and it must be skippable with no change in behavior.
3. **Secrets stay on the backend.** The app never holds a Gemini or AWS key, and no AWS SDK goes in the app. The only config value in the app is the backend URL. Never commit keys. `.env` files are gitignored.
4. **Never ask for, store, or print AWS credentials.** Do not run `sam deploy` or any command that changes the AWS account unless the owner explicitly asks. The owner deploys.
5. **Native Kotlin only.** No Flutter, React Native, or WebView-based UI.
6. **No AI or audio models bundled in the APK.**
7. **Ask before adding any dependency** (app or backend) and state its size cost.
8. **Never claim something was tested if it wasn't.** If the SDK, network, or a phone is not available in your environment, say so.
9. **Be honest in confirmations.** Only say an action happened if it did.

## Architecture

```
Phone (Kotlin)                          Backend (AWS Lambda, Mumbai)

assistant opens --- GET /v1/warm -----> wakes Lambda, loads keys (fire and forget)
mic -> AudioRecord (16 kHz WAV)
speech ends --- POST /v1/command -----> Gemini (audio in, forced function call)
      <-- {"type":"action","action":...,"args":{...}} --
app runs the Android action and shows a short confirmation
```

One round trip. The audio goes straight to Gemini, so there is no separate speech-to-text step and no local matcher in v1. Every voice command needs internet.

## Speed rules (speed is the main goal)

1. **One hop.** Audio goes to Gemini directly. Do not add a speech-to-text service.
2. **Tiny output.** Gemini must answer with a function call only (forced). No chat text. The app writes the confirmation text itself from the action and args.
3. **Fastest suitable model.** Use the fastest Flash-Lite class Gemini model that supports audio input and function calling (check current docs). Minimal or no thinking, temperature 0, max output tokens about 100, short system prompt, compact function declarations.
4. **Warm up while the user speaks.** When the assistant opens, send `GET /v1/warm` (fire and forget) so Lambda start-up and the TLS connection overlap with the user speaking. Reuse that connection for the command.
5. **Lean Lambda.** arm64, esbuild bundle, no SDK for Gemini (plain `fetch` to the REST API), secrets read once and cached.
6. **Small upload.** 16 kHz mono, trim silence. Compress to AAC later if measurements show upload time matters.
7. **Resolve names on the phone.** Contacts and app names are matched locally, so the backend never needs the user's contacts or installed apps.
8. **Measure everything.** Log per-stage timings in debug builds: record end, upload, Lambda, Gemini, action. The backend returns a `Server-Timing` header.

## Tech stack

| Part | Choice |
|---|---|
| App language | Kotlin |
| App UI | Minimal. Jetpack Compose or plain Views, whichever gives the smaller APK and faster start. Measure. |
| Networking | `HttpURLConnection` (built in) with keep-alive. OkHttp only if justified. |
| JSON | `org.json` (built in) |
| Async | Kotlin coroutines |
| AI | Gemini API (audio in, function calling), called only from our backend |
| Speech output | Android `TextToSpeech`, short confirmations only |
| Settings storage | `SharedPreferences`. No Room. |
| Backend compute | AWS Lambda, Node.js (latest supported runtime), TypeScript, arm64, behind a Lambda Function URL |
| Backend data | DynamoDB (on-demand) for usage counters |
| Backend secrets | SSM Parameter Store (SecureString) |
| Backend deploy | AWS SAM (`template.yaml`) |
| Region | `ap-south-1` (Mumbai) |
| Build | Gradle (wrapper), JDK 17 or newer (owner has Temurin 21) |

Not allowed in the app without asking: Retrofit, Hilt/Dagger, Room, Glide/Coil, `material-icons-extended`.

## Repo layout

```
/app          Android app
/backend      AWS SAM project (template.yaml, src/, test/)
/docs         actions.md (action schema), test-phrases.md, model-notes.md
AGENTS.md
README.md
```

Use the placeholder package `com.example.assistant`. The owner will rename it later.

## Android app

### Entry points

1. `MainActivity` with one mic button and a debug screen with one button per action (using sample args). Build this first.
2. Selectable as the **default digital assistant** (`VoiceInteractionService` and `VoiceInteractionSessionService`) so a power-button long press opens it. Check current Android docs for exact requirements. If Compose inside the session is awkward, use a simple overlay or a transparent Activity instead.
3. Fallbacks: Quick Settings tile (`TileService`) and a home-screen widget.

Wake phrase is now in scope: "Hey Friday". It must be detected locally so ambient audio is never streamed continuously. Implement it only after the core visible-app voice loop works, using a user-started microphone foreground service with a persistent notification. It must not restart microphone capture from a boot receiver on targetSdk 34+, and it must have a clear off switch.

### Permissions (one-time onboarding screen, then no more prompts)

| Permission | Needed for |
|---|---|
| `RECORD_AUDIO` | Listening |
| Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`) | Silent mode |
| `CALL_PHONE` | Calling |
| `READ_CONTACTS` | Calling by name |
| `com.android.alarm.permission.SET_ALARM` | Alarms and timers |
| `INTERNET` | Backend calls |

Do Not Disturb access is not a normal prompt. Send the user to `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`.

### Actions

Every action returns one of: `Success`, `NeedsPermission(which)`, `Failed(reason)`. Never crash on a missing permission.

| Action | Args | Implementation |
|---|---|---|
| `call_contact` | `name` | Match against contacts locally (`ContactsContract`), then `TelecomManager.placeCall` or `ACTION_CALL` |
| `call_number` | `number` | Same as above |
| `set_alarm` | `hour` 0 to 23, `minute` 0 to 59, optional `label` | `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI = true`. Uses the phone's clock app. Report failure if none handles it. |
| `set_timer` | `seconds` | `AlarmClock.ACTION_SET_TIMER` with `EXTRA_SKIP_UI = true` |
| `open_app` | `name` | Match against launcher app labels locally, then `getLaunchIntentForPackage`. Use a `<queries>` entry for launcher apps. Do not use `QUERY_ALL_PACKAGES`. |
| `close_app` | optional `name` | Go to the Home screen (`Intent.ACTION_MAIN` with `CATEGORY_HOME`, `FLAG_ACTIVITY_NEW_TASK`). See notes below. |
| `set_ringer_mode` | `mode`: silent, vibrate, normal | `AudioManager.setRingerMode`. Silent needs Do Not Disturb access. Check `isNotificationPolicyAccessGranted` first. |

Extras, only after the core works: `set_volume` (`stream`, `percent`) with `AudioManager.setStreamVolume`, and `flashlight` (`on`) with `CameraManager.setTorchMode`.

Out of scope: Wi-Fi, Bluetooth, and mobile data toggles (modern Android blocks these for normal apps).

`close_app` notes:
- A normal app cannot close another app. Since Android 14, `killBackgroundProcesses` only affects the caller's own app.
- Do not request `KILL_BACKGROUND_PROCESSES` and do not use an AccessibilityService for this.
- The confirmation must say "Went to home screen", never "Closed".

Calling notes:
- Start the call from the visible assistant UI. Android 10 and later block activity launches from the background.
- Normalize names (lowercase, strip accents and extra spaces) and fuzzy match. Audio can be misheard, so require a good match. Below the threshold say "I couldn't find that contact". If two contacts match about equally, ask "which one?". Never call on a weak guess.

### Confirmations (less talk)

- The app builds the text itself, for example "Alarm set for 6:30 AM", "Silent mode on", "Calling Mom".
- Show it on screen with a short sound. Speak it with `TextToSpeech` only if an engine exists. If none exists (common without Google), skip speech. Never crash.
- Errors are short: "Sorry, I can't do that yet", "No internet", "Try again".

### Audio

- `AudioRecord`, 16 kHz, mono, 16-bit PCM, uploaded as WAV.
- Start recording immediately when the assistant opens.
- Stop on about 700 ms of silence (simple energy-based detection) or after 10 seconds. Trim leading and trailing silence before upload.
- Do not store audio on the device after upload.

### Backend access

- `BuildConfig` holds `BACKEND_URL`. Nothing else.
- On first run, create a random install ID (a UUID) and keep it in private app storage. Never use IMEI, Android ID, or the advertising ID.
- Call `POST /v1/register` with the install ID to get a token. Send it as `Authorization: Bearer <token>` on every other call.
- Re-register shortly before the token expires. Do this in the background at app start so it is never in the command path. If a call fails on an expired token, re-register once and retry.
- Call `GET /v1/warm` when the assistant opens, without waiting for the result.
- Contacts and the installed-app list never leave the device.

### Performance targets (measure and log in debug builds)

| Step | Target |
|---|---|
| Open assistant to listening | under 300 ms |
| Speech ends to action done | under 2.5 s on good network (median) |

Keep `Application.onCreate` nearly empty. Initialize everything lazily.

### Size budget

- Enable R8 minify and resource shrinking in release builds.
- Target well under 15 MB. Aim for about 8 MB or less.
- After each milestone run `ls -lh app/build/outputs/apk/release/*.apk` and report the size.

## Backend (AWS)

### Setup

- One Lambda function (arm64, 256 to 512 MB, measure) behind a **Lambda Function URL** with auth type NONE. Our own token check protects it. Routes stay `/v1/...`.
- No web framework. Use a plain handler with a tiny router, bundled with esbuild. Function URLs send binary bodies base64 encoded, so decode `isBase64Encoded` bodies.
- Timeout about 15 seconds. Region `ap-south-1`.
- Set a **reserved concurrency** cap to limit runaway cost. Start around 20 and raise toward 50 for 5,000 users. Make it a SAM parameter.
- Set CloudWatch log retention to 7 to 14 days in the template.
- Keep core logic in plain TypeScript modules with no AWS imports. Only the thin handler and the DynamoDB and SSM adapters touch AWS.
- `template.yaml` defines the function, the Function URL, the DynamoDB table (with TTL), log retention, and a least-privilege IAM role (that table plus the specific SSM parameters only). Secrets are never in the template.

### Endpoints

**`POST /v1/register`** (no auth)
- Body: `{"installId": "<uuid>"}`
- Response: `{"token": "<jwt>", "expiresIn": 2592000}`
- Token: HS256 JWT signed with a key from SSM (Node `crypto`, no extra library), `sub` is the install ID, lifetime about 30 days.
- Rate limit per source IP and a global daily cap on new registrations. Mobile networks share IPs, so keep the per-IP limit generous.

**`GET /v1/warm`** (token optional)
- Initializes secrets and clients, then returns 204. Must be cheap and must not call Gemini.

**`POST /v1/command`** (needs `Authorization: Bearer <token>`)
- Voice: raw WAV bytes (16 kHz, mono), `Content-Type: audio/wav`. Reject bodies over about 1 MB.
- Text (debug box and tests): `Content-Type: application/json`, body `{"text": "..."}`.
- Header `X-Now`: the phone's current time with offset, for example `2026-11-16T09:30:00+05:30`.
- Response is one of:

```json
{"type": "action", "action": "set_alarm", "args": {"hour": 6, "minute": 30}}
{"type": "unsupported"}
{"type": "error", "reason": "busy"}
```

  `reason` is one of `busy`, `timeout`, `bad_audio`, `unauthorized`.
- Header `Server-Timing` includes the Gemini duration.

Action names and args must match the Actions table. Keep `docs/actions.md` as the single source of truth and update it whenever actions change.

### Gemini rules

- Read the model name from env var `GEMINI_MODEL`. Never hardcode it. Models get retired, so changing it must not need a code change.
- Call the REST `generateContent` endpoint with `fetch`. No SDK. Check the current docs for the request format for inline audio and for forced function calling (mode ANY).
- Send audio inline as base64 with the right MIME type.
- Declare one function per action plus `unsupported`, so the model always has a valid choice. Force a function call.
- System prompt, short: "You control an Android phone. The user speaks English. Pick exactly one function. Use `unsupported` for anything else. Never answer questions." Relative times like "in 20 minutes" use `set_timer`. Use `X-Now` for "tomorrow" and similar.
- Thinking: minimal or off. The parameter differs by model generation, so check the docs. Temperature 0. Max output tokens about 100.
- Validate the returned args against a schema (ranges, required fields). Anything invalid becomes `unsupported`.
- Timeout about 6 seconds, return `error` with `timeout`. At most one retry on a 5xx, and only if it fits the time budget.
- Use a **paid-tier** API key for real users (see Owner tasks).
- Never log audio, transcripts, or tokens.
- Keep a small `AssistantProvider` interface so the model or provider can change without touching the app.

### Abuse and cost protection

- Per install ID: a daily cap and a short burst limit on `/v1/command`. Counters live in DynamoDB using atomic increments with a TTL. Make the limits environment parameters (start with about 100 per day and 12 per minute).
- Global daily cap on `/v1/command` as a circuit breaker. When it trips, return `error` with `busy`. The owner sets the number from the budget.
- Support blocking an install ID with a flag in DynamoDB.
- If DynamoDB is unreachable, fail closed with `error`.
- Keep it to a few small DynamoDB writes per request, run in parallel with the Gemini call where safe.

### Security

- Keys (`GEMINI_API_KEY` and the JWT signing key) live in SSM Parameter Store as SecureString. The function reads them once at cold start and caches them in memory. Provide `.env.example` and docs with fake values only.
- HTTPS only. The app must disable cleartext traffic in release builds.

## Dev environment (Fedora, VS Code and terminal)

```bash
sudo dnf install git nodejs npm   # JDK 21 and adb are already installed
```

- Use a Node.js version that the Lambda runtime supports. If Fedora's `nodejs` does not match, install a matching version with `fnm` or `nvm`.
- The owner installs the AWS CLI and the AWS SAM CLI following the AWS docs, and configures credentials. Codex does not handle credentials.
- Already present: JDK 21 (Temurin), `adb`, and `ANDROID_HOME=~/Android/Sdk`. `kotlinc` and system `gradle` are not installed and are not needed: use `./gradlew`. Create the project with the Android CLI (the `android` binary in the SDK's `cmdline-tools` directory; run `android create --help` for template options). It generates the Gradle wrapper, so system Gradle is not needed. Then strip template extras we do not need and re-check the dependency and size rules above. Use `android docs` for current official guidance.
- If the SDK is incomplete, install Android command-line tools into `~/Android/Sdk`, then set `ANDROID_HOME` and add `cmdline-tools/latest/bin` and `platform-tools` to `PATH`.
- Use `sdkmanager` (or `android sdk`) to install `platform-tools`, the latest stable `platforms;android-XX`, and matching `build-tools`. Run `sdkmanager --licenses`.
- Test on a real phone: enable Developer options and Wireless debugging (or USB debugging), then check `adb devices`.
- Targets: `minSdk 26`, `compileSdk 37` (installed platform), `targetSdk 36` or higher. Google Play requires at least 36 for new apps and updates since 31 Aug 2026.

## Commands

```bash
# Android
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
./gradlew assembleRelease          # then check APK size
./gradlew bundleRelease            # App Bundle for Play
./gradlew :app:dependencies        # confirm no gms, firebase, or mlkit
adb logcat

# Backend
cd backend
npm install
npm run build                      # esbuild bundle
npm test                           # unit tests with mocked Gemini, DynamoDB, SSM
npm run eval                       # owner runs: real Gemini with the phrase list, prints accuracy and latency
sam validate                       # checks template.yaml, no AWS changes
sam build
# Owner only: sam deploy --guided
```

`sam local` needs a container runtime. Unit tests must not.

Release signing uses a local keystore configured outside git. Never commit it.

## Google Play readiness

Plan for these from the start. They are easy now and painful to retrofit.

- **Target API:** `targetSdk` must be 36 or higher.
- **Upload format:** Play takes an Android App Bundle. Use `./gradlew bundleRelease`. Keep the universal APK under 15 MB. Use Play App Signing. The upload keystore stays out of git.
- **Prominent disclosure:** Before each sensitive permission request (microphone, contacts, phone), show our own screen saying what is collected, why, and that voice audio is sent to our backend and processed by Google's Gemini. The user must tap an explicit agree button. Back or home does not count as consent. Only then show the system permission prompt.
- **Contacts stay on the device.** Match names locally. Never upload the contact list or contact details. Only the spoken audio goes to the backend.
- **Privacy policy:** A public web page (not a PDF), linked from the store listing and inside the app. It must cover voice audio sent to the backend (hosted on AWS in Mumbai) and to Gemini, the anonymous install ID, how long anything is kept (audio is not stored), and how to delete data. Keep logging minimal so the policy stays true.
- **Data safety form:** Answers must match what the app really does, including voice audio and the install ID. Draft them from the actual data flow in `docs/play-data-safety.md`.
- **Later, if we add free-text answers or web search:** add an in-app "Report this answer" option. Google requires in-app reporting for generative AI content.
- **Foreground service (wake phrase):** Declare the `microphone` service type and its permission, and complete the Play Console declaration. With targetSdk 34 or higher it cannot be started from a boot receiver, so the user must open the app to start listening after a restart.

## Owner tasks (not for Codex)

- AWS account: choose the **Paid plan**, not the Free plan. The Free plan closes the account after 6 months or when credits run out, which would take the backend down. Credits still apply on the Paid plan.
- Check the credit expiry date on the Billing and Cost Management credits page, and plan for the earlier date if pages disagree.
- Set up AWS Budgets alerts at low amounts. AWS credits do not pay for Gemini, so set a budget alert on the Gemini side too.
- Gemini: create the API key in a project with **billing enabled (paid tier)** before real users. Free-tier content can be used to improve Google's products, and users' voice should not go there. Check the rate limits for your tier before reaching 5,000 users.
- Check Lambda concurrency in Service Quotas. New accounts can start with a low limit. Request an increase before the app reaches 5,000 users.
- Store the secrets in SSM Parameter Store as SecureString (Gemini key and a long random JWT signing key), then run the first `sam deploy`.
- Record about 10 short WAV samples of your own voice and about 50 test phrases for `docs/test-phrases.md`.
- Create the Play developer account. A personal account created after 13 Nov 2023 needs a closed test with at least 12 testers opted in for 14 continuous days before production access. Line up testers early.
- Host the privacy policy page.
- Phones without Google services cannot use the Play Store. Plan a second channel (a signed APK on a website or another store).
- Register as a developer in the Android Developer Console. Google is rolling out developer verification for apps installed outside Play.

## Milestones (do one at a time, stop and report after each)

0. **Skeleton.** Project builds, installs on a phone, shows the mic button. Record the baseline APK size.
1. **Actions.** All v1 actions work from the debug screen. Onboarding screen shows a prominent disclosure, then asks for each permission. Local contact and app-name matching with unit tests.
2. **Backend.** SAM project with `/v1/register`, `/v1/warm`, and `/v1/command` (audio and text), schema validation, rate and cost limits. Tests use mocked Gemini, DynamoDB, and SSM. `npm run eval` and `docs/test-phrases.md`. `sam validate` passes. The owner handles the first deploy.
3. **Voice loop.** Register in the background, warm up, record, upload, run the action, show the confirmation. Debug timing logs for every stage.
4. **Entry points.** Default assistant role, Quick Settings tile, widget, TextToSpeech with fallback.
5. **Speed pass.** Measure median and slow-case timings. Compare two Gemini models and one two-step alternative (Groq Whisper Large v3 Turbo for speech, then a small Llama model for the action), and record the results in `docs/model-notes.md`. Groq bills a 10-second minimum per request and the two calls run one after the other, so measure from Mumbai before deciding. Try AAC upload. Tune memory and concurrency.
6. **Release prep.** App Bundle build, draft privacy policy text, draft `docs/play-data-safety.md`, final size and permission audit.

## Definition of done (every task)

- Code builds and tests pass. State exactly which commands you ran.
- APK size reported for any change that touches the app.
- No Google service dependencies on the device, and no AWS SDK in the app.
- Permission-missing cases handled, not crashed.
- `docs/actions.md` and README updated if behavior changed.
- Final summary lists what changed, what you could not verify, and any decision you need from the owner.

## Style

- Kotlin official code style for the app. Strict TypeScript for the backend. Small files, clear names, no cleverness.
- Keep the backend logic pure and testable.
- Make the smallest change that solves the task. Do not refactor unrelated code.

## Open decisions (ask the owner, do not assume)

- Which AI to use: a Gemini model, or the Groq two-step alternative (decide from the milestone 5 measurements).
- Whether to add web questions later (Gemini with Google Search grounding) and other languages.
- Whether to pay for always-warm Lambda (provisioned concurrency) if the warm-up ping is not enough.
- Whether to add offline speech recognition (downloaded model) so the most common commands work without internet.
- Final app name and package name.
