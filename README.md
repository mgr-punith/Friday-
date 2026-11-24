# Friday

Friday is a native Android voice assistant for quick phone tasks. It sends one spoken request to a small backend and runs the validated actions on the phone.

## What Friday can do

- Call an exact saved contact directly when the phone is unlocked and the contact has one number. Choose between similar contacts and confirm when Friday needs help.
- Set an alarm or timer using the phone's clock app.
- Open an installed app or go to the Home screen.
- Say “Play some music” to choose an installed music app. Say “Open Spotify” to open Spotify when it’s installed.
- Say “Play Believer” or “Play Believer in Spotify” to request a song. With a v4 backend, Friday sends the title to a compatible player. The player controls playback.
- Say “Pause music,” “Resume music,” “Next track,” or “Previous track.” With a v5 backend, Friday sends the request to the player Android selects for media buttons.
- Switch the ringer between silent, vibrate, and normal.
- Turn the flashlight on or off and set media, ring, or alarm volume.
- Open Airplane mode settings and tell you which switch to change. Android requires this final tap.
- Run up to three supported actions in one request. Say “Turn off the flashlight, set media volume to half, and open Clock.”
- Use Friday as the selected Android assistant, a Quick Settings tile, or a notification shortcut.

Friday does not answer general questions, search the web, or chat. Those requests receive a short unsupported response.

## How it works

1. You tap the microphone or open Friday from an enabled entry point.
2. Android records a short 16 kHz mono WAV command. Friday trims silence and sends the recording over HTTPS.
3. The Groq backend transcribes the audio with Whisper Large V3 Turbo, then GPT OSS 20B selects one function from Friday's action list using the current phone time. Friday keeps the transcript in memory for that request.
4. The backend validates one action or a short sequence.
5. Friday resolves contacts and installed apps on the phone, runs the actions in order, then shows and speaks the result when Android has an English text-to-speech engine.

The phone does not upload contacts or installed-app lists. Friday does not save command recordings. Account-enabled cloud commands require Google sign-in.

Home has one Speak button and a Settings shortcut. Settings groups the account, entry points and phone tools, with their current values beside the labels. About Friday shows the build version and license notices. In Start Friday, the white info bubbles explain each option; selecting a radio button or opening help starts no microphone. Use the primary button to apply the choice.

## Technology

| Area | Technology |
| --- | --- |
| Android app | Kotlin, Android framework Views, Gradle, minimum API 26, target API 37 |
| Audio | Android `AudioRecord`, 16 kHz mono PCM WAV, device `TextToSpeech` for confirmations |
| Sign-in | Android Credential Manager with Google Play Services; browser OAuth fallback is available when configured |
| Backend | TypeScript on Vercel Functions in Mumbai |
| Command selection | Groq Whisper + GPT OSS 20B over REST, with one required tool call; Gemini remains an explicit rollback option |
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

The app package is `com.puneet.friday`. The opt-in **Hey Friday** detector is available in debug builds that include the local Friday model. Pass `-PfridayWakeModelDir=.local/tools/wake-model/android-assets` to `./gradlew assembleDebug` to package those weights. The model stays in the ignored local folder and out of release APKs.

### Backend

```bash
cd backend
npm ci
npm run verify
npm run test:sam
```

`npm run verify` type-checks the backend, runs mocked tests, checks Vercel routing and builds the size-limited bundles. `npm run test:sam` checks the AWS packaging path without deploying. AWS deployment requires owner-managed credentials; Codex does not deploy it.

For real model evaluation, configure `.env` locally and run `EVAL_PROVIDER=groq npm run eval` or select Gemini. Keep `.env` out of Git. Evaluation sends requests to the selected provider and consumes quota. [Groq setup and error handling](docs/groq-backend.md) covers the approved switch.

Vercel production setup and backend variables are documented in [the backend deployment guide](docs/backend-deployment.md). Google client setup is in [Google sign-in](docs/google-sign-in.md). Do not commit secrets.

## Try a command

Invoke Friday from the assistant shortcut, tile, widget or notification to open a floating card above the current app. The orb moves with your voice. If Friday cannot hear a command, tap the microphone to retry. Tap ×, tap outside the card or press Back to dismiss it.

After building and installing the app, sign in and grant the permissions for the action you want to test. Tap the orb or **Speak**, wait for **Listening**, then say:

```text
Set a timer for two minutes
```

Friday should send the timer request to your clock app and speak a short confirmation. On Home, Friday shows command feedback in a brief toast at the top center. Speech can finish after Clock takes focus from either Home or the compact assistant. The compact assistant closes after one completed command; Home retains the result, and an enabled Hey Friday listener resumes when speech ends. Exact results depend on the installed clock app and Android permissions.

In a wake-enabled debug build, select **Settings → Start Friday → Hey Friday** and tap **Use Hey Friday**. The model is already in the APK. Allow the requested permissions and choose Friday as Android’s default assistant. Say your command after the short tone. Friday accepts quieter speech for the command that follows a wake. If a wake doesn’t open the assistant, Friday resumes detection after eight seconds.

Pause Hey Friday before changing **Settings → Start Friday → Wake sensitivity**. Standard uses the model’s calibrated cutoff. Higher sensitivity lowers it and can wake Friday during other speech.

To investigate false wakes in a debug build, open **Settings → Start Friday → Wake audio tracker**. Start tracking for up to 15 minutes. Friday keeps the latest 16 clips, each with up to two seconds from before a wake, in private storage. Use Android debugging to retrieve those clips or delete them from the tracker menu.

## Privacy and permissions

Friday asks for microphone, contacts, and phone permissions when their actions need them. Contact matching stays on the phone. The backend receives the spoken command audio and current phone time, but not the contact list or installed-app list. The app does not store command audio. Google sign-in is required before cloud commands.

Open **Settings → Account** to see your Google name and email, sign out on this phone, or request deletion of your Friday account across devices.

See [Google sign-in and session handling](docs/google-sign-in.md), [the action contract](docs/actions.md), and the testing and deployment docs for full behavior and known limits.

## Development notes

- Read [AGENTS.md](AGENTS.md) before making repository changes.
- Use `docs/actions.md` as the action schema source of truth.
- Keep phone test tools and private audio samples in the ignored `.local/tools/` area.
- Keep Git safeguards enabled with `git config core.hooksPath .githooks`.
- Before committing, run `python3 scripts/check-secrets.py --staged`.
- Before pushing, run `python3 scripts/check-secrets.py --history HEAD`.

The [testing guide](docs/testing.md) records verification and device coverage. The [model notes](docs/model-notes.md) track accuracy and latency measurements. Wake phrase recognition remains opt-in debug work; its current phone results do not establish production accuracy.

The [audio-processing comparison](docs/audio-processing-benchmark.md) records Kotlin, Rust and C++ measurements on the Vivo. Friday processes command audio and runs its bundled wake detector in Kotlin.

The wake-enabled debug build can keep Hey Friday listening after you remove Friday from Recents. [Background listening setup](docs/wake-detector-trial.md#background-listening) links to optional battery settings. Pause stops the microphone; restart or force-stop requires a user-started Resume. All-day battery drain remains unmeasured.
