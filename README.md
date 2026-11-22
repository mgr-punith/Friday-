# Friday

Friday is a native Android voice assistant for quick phone tasks. It sends one spoken command to a small backend, receives a validated action, and carries it out on the phone.

## What Friday can do

- Call a saved contact or a phone number, with confirmation before dialing.
- Set an alarm or timer using the phone's clock app.
- Open an installed app or go to the Home screen.
- Switch the ringer between silent, vibrate, and normal.
- Use Friday as the selected Android assistant, a Quick Settings tile, or a notification shortcut.

Friday does not answer general questions, search the web, or chat. Those requests receive a short unsupported response.

## How it works

1. You tap the microphone or open Friday from an enabled entry point.
2. Android records a short 16 kHz mono WAV command. Friday trims silence and sends the recording over HTTPS.
3. The backend sends the audio and current phone time to Gemini. Gemini selects one function from Friday's action list.
4. The backend validates the result and returns a compact action response.
5. Friday resolves contacts and installed apps on the phone, runs the action, then shows and speaks a short confirmation when Android has an English text-to-speech engine.

The phone does not upload contacts or installed-app lists. Friday does not save command recordings. Account-enabled cloud commands require Google sign-in.

## Technology

| Area | Technology |
| --- | --- |
| Android app | Kotlin, Android framework Views, Gradle, minimum API 26, target API 37 |
| Audio | Android `AudioRecord`, 16 kHz mono PCM WAV, device `TextToSpeech` for confirmations |
| Sign-in | Android Credential Manager with Google Play Services; browser OAuth fallback is available when configured |
| Backend | TypeScript on Vercel Functions in Mumbai |
| Command selection | Gemini REST API with a forced function call; no Gemini SDK in the app or backend |
| Account and limits | Upstash Redis for expiring sessions, login transactions, and request limits |
| AWS fallback | AWS SAM, Lambda, DynamoDB, and SSM files remain in the repository; the AWS route still needs account support before it can serve the current app |
| CI | GitHub Actions selects Android, backend, SAM, or repository-tool checks based on changed files |

## Repository layout

```text
app/       Native Android app and Kotlin checks
backend/   TypeScript API, Vercel adapter, AWS SAM fallback, and backend tests
docs/      Action contract, setup guides, test records, and design notes
scripts/   Build checks, Git safeguards, and CI selection tools
design/    UI guidance and design references
```

## Build and verify

### Requirements

- JDK 17 or newer (the project is verified with JDK 21)
- Android SDK with API 37 and matching build tools
- Node.js 24 for backend checks
- Python 3 for repository verification scripts
- Git

The Gradle wrapper is included. A system Gradle installation is not needed.

### Android

```bash
./gradlew assembleDebug
./gradlew test
./gradlew assembleRelease
```

Run the full Android checks with:

```bash
./scripts/verify.sh
```

This builds debug and release APKs, runs the Kotlin action, voice, and wake-trial checks, runs debug lint, checks app dependencies, and enforces the 15 MB release APK limit. Check APK sizes with:

```bash
ls -lh app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk
```

The app package is `com.puneet.friday`. Debug and release builds use different features. The opt-in Hey Friday wake detector is debug-only and needs a wake-enabled debug APK with the native runtime; standard builds do not contain it.

### Backend

```bash
cd backend
npm ci
npm run verify
npm run test:sam
```

`npm run verify` type-checks the backend, runs mocked tests, checks Vercel routing and builds the size-limited bundles. `npm run test:sam` checks the AWS packaging path without deploying. AWS deployment requires owner-managed credentials; Codex does not deploy it.

For real Gemini evaluation, copy `.env.example` to `.env`, set development credentials locally, and run `npm run eval`. Keep `.env` out of Git. Evaluation sends requests to Gemini and can use API quota.

Vercel production setup and backend variables are documented in [the backend deployment guide](docs/backend-deployment.md). Google client setup is in [Google sign-in](docs/google-sign-in.md). Do not commit secrets.

## Try a command

After building and installing the app, sign in and grant the permissions for the action you want to test. Tap the microphone, wait for **Listening**, then say:

```text
Set a timer for two minutes
```

Friday should return a timer action, open the phone's clock app, and show a short confirmation. The compact assistant closes after one completed command; its short speech can finish after Clock opens, and an enabled Hey Friday listener resumes when speech ends. Exact results depend on the installed clock app and Android permissions.

## Privacy and permissions

Friday asks for microphone, contacts, and phone permissions when their actions need them. Contact matching stays on the phone. The backend receives the spoken command audio and current phone time, but not the contact list or installed-app list. The app does not store command audio. Google sign-in is required before cloud commands.

See [Google sign-in and session handling](docs/google-sign-in.md), [the action contract](docs/actions.md), and the testing and deployment docs for full behavior and known limits.

## Development notes

- Read [AGENTS.md](AGENTS.md) before making repository changes.
- Use `docs/actions.md` as the action schema source of truth.
- Keep phone test tools and private audio samples in the ignored `.local/tools/` area.
- Keep Git safeguards enabled with `git config core.hooksPath .githooks`.
- Before committing, run `python3 scripts/check-secrets.py --staged`.
- Before pushing, run `python3 scripts/check-secrets.py --history HEAD`.

The [testing guide](docs/testing.md) records verification and device coverage. The [model notes](docs/model-notes.md) track accuracy and latency measurements. Wake phrase recognition remains opt-in debug work; its current phone results do not establish production accuracy.

The [audio-processing comparison](docs/audio-processing-benchmark.md) records Kotlin, Rust and C++ measurements on the Vivo. Friday keeps command audio in Kotlin and skips unused pitch analysis after capture; the wake detector uses its existing C++ runtime.

The wake-enabled debug build can keep Hey Friday listening after you remove Friday from Recents. [Background listening setup](docs/wake-detector-trial.md#background-listening) links to optional battery settings. Pause stops the microphone; restart or force-stop requires a user-started Resume. All-day battery drain remains unmeasured.
