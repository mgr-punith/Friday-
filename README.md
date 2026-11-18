# Hey Friday

Hey Friday is a small, fast Android voice assistant for phone commands: calls, alarms, timers, apps, and ringer modes. It is native Kotlin and is designed to work without Google Play Services.

## Current milestone

Milestone 3 connects the visible Android app to the deployed backend. Tap the microphone to record one 16 kHz command. Friday warms the Vercel Function while you speak, uploads a trimmed WAV, receives one validated action, then runs it from the visible activity. The app registers an anonymous install ID in the background and refreshes its token before expiry.

One Vercel Function in Mumbai accepts 16 kHz WAV or debug text, calls Gemini through REST, validates one forced function call, and returns the action contract in [docs/actions.md](docs/actions.md). Upstash Redis applies per-install and global limits. Vercel stores the Gemini and JWT values in its encrypted environment. The backend does not log audio, text commands, tokens, contacts, or app names. The AWS SAM deployment remains available as a fallback.

The native Kotlin framework-Views app includes phone actions and permission setup. The action test screen takes typed inputs and performs calls, clock requests, app launches, Home navigation, and ringer changes. Contacts and launcher labels are resolved on a worker thread and stay on the device. Weak matches fail; ambiguous matches ask for a choice. Calls require a final confirmation showing the resolved target. Missing permissions, invalid inputs, and unavailable Android handlers return short feedback.

Friday treats each request as a phone command, selects one supported action, resolves device data on the phone, and completes the action from the visible UI. If the request needs a feature outside the current action set, the app shows a Friday-styled popup with the commands available now. It does not claim that an unavailable feature ran.

Setup shows Friday's disclosure before each microphone, contacts, or phone permission prompt. Silent mode has a separate disclosure before Android's Notification Policy settings. The microphone disclosure explains that recorded commands go to Friday's Vercel backend and Google Gemini, and that Friday does not store audio. Access is optional and can be managed later. Friday uses compact graphite controls and its own dialogs. Clock confirmations report that a request was sent, and Home navigation never claims another app was closed. See [the action contract](docs/actions.md).

The home and utility screens use a near-black background, flat graphite surfaces, concise labels, and one clear action at a time. The action screen opens a focused editor instead of showing every form in one stack. Friday bundles the Geist variable font from Google Fonts and uses three Untitled UI outline icons. Neither asset adds a runtime dependency.

The reusable Aurora orb uses blue, cyan, lilac, and pearl light fields with spherical depth and a restrained atmospheric glow. Its circular silhouette stays fixed. API 33+ hardware rendering uses a small procedural AGSL shader; API 26–32, software canvases, and shader compilation failure use a cached Canvas approximation. There are no packaged orb images or animation dependencies. Animation pauses when the view or app is hidden and respects the system animator setting.

Idle and quiet listening are frozen. During recording, raw microphone energy must cross an adaptive room-noise gate before the orb moves. Low, mid, and high energy change the internal flow while the sphere stays still. Motion settles after speech ends. Hardware rendering schedules active frames with display vsync; software rendering uses a limited cadence. Frame rate depends on the device.

The intended wake experience is:

1. The user explicitly enables always-listening mode.
2. Saying “Hey Friday” opens a compact round assistant surface over the current context.
3. Live microphone energy gently changes the internal gradient movement while the user speaks; the sphere stays stationary.
4. The surface transitions to processing, success, or error without claiming an action before Android confirms it.

Always-listening mode will require a user-started microphone foreground service, a persistent notification, and a local or downloaded wake-phrase detector. It will not continuously upload ambient audio.

## Build

```bash
./scripts/verify.sh
./gradlew test
./gradlew :app:dependencies

cd backend
npm ci
npm run verify
sam validate --lint --template-file template.yaml
sam build --template-file template.yaml
```

The Android verification script builds debug and release APKs, runs dependency-free action and voice checks, runs debug lint, then enforces the 15 MB release APK limit. The voice checks cover WAV structure, silence trimming, speech-end detection, response parsing, malformed arguments, token refresh rules, and backend URL validation. `npm run verify` type-checks the backend, runs mocked tests, checks `vercel.json`, and creates size-limited Vercel and Lambda bundles. GitHub CI also validates and builds the AWS fallback. Vercel runs its platform build for each Git-connected preview and production deployment. See [the testing checklist](docs/testing.md) and [backend deployment guide](docs/backend-deployment.md).

The placeholder package is `com.example.assistant`. The app targets Android API 37 and supports API 26 and newer.
