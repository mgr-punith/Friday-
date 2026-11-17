# Hey Friday

Hey Friday is a small, fast Android voice assistant for phone commands: calls, alarms, timers, apps, and ringer modes. It is native Kotlin and is designed to work without Google Play Services.

## Current milestone

Milestone 1 adds phone actions and permission setup to the native Kotlin framework-Views app. The action test screen takes typed inputs and performs calls, clock requests, app launches, Home navigation, and ringer changes. Contacts and launcher labels are resolved on a worker thread and stay on the device. Weak matches fail; ambiguous matches ask for a choice. Calls require a final confirmation showing the resolved target. Missing permissions, invalid inputs, and unavailable Android handlers return short feedback.

Setup shows Friday's disclosure before each microphone, contacts, or phone permission prompt. Access is optional and can be managed later; sound mode access opens Android's Do Not Disturb settings. Friday uses compact graphite controls and its own dialogs. Clock confirmations report that a request was sent, and Home navigation never claims another app was closed. See [the action contract](docs/actions.md).

The home and utility screens use a near-black background, flat graphite surfaces, concise labels, and one clear action at a time. The action screen opens a focused editor instead of showing every form in one stack. Friday bundles the Geist variable font from Google Fonts and uses three Untitled UI outline icons. Neither asset adds a runtime dependency.

The reusable Aurora orb uses flowing blue, cyan, and lilac, a soft pearly highlight, spherical depth, and a restrained atmospheric glow. Its circular silhouette stays fixed. API 33+ hardware rendering uses a small procedural AGSL shader; API 26–32, software canvases, and shader compilation failure use a cached Canvas approximation. There are no packaged orb images or animation dependencies. Animation pauses when the view or app is hidden and respects the system animator setting.

Idle is continuously animated. The component preserves the existing state API and provides movement parameters for preview, listening, processing, and speaking. Voice capture, backend requests, and the wake-phrase service are not connected yet; the mic button demonstrates stronger internal gradient movement without recording audio, even when microphone access is granted. Audio amplitude and spectrum hooks are prepared for later voice integration. Hardware rendering schedules frames with display vsync; software rendering uses a limited cadence. Frame rate depends on the device.

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
```

The verification script builds debug and release APKs, runs the action logic checks and debug lint, then enforces the 15 MB release APK limit. `./gradlew test` runs 46 dependency-free JVM matching and validation checks through the custom `actionLogicTest` task; no JUnit or new app dependency is added. Android permissions and side effects require a phone. See [the testing checklist](docs/testing.md).

The placeholder package is `com.example.assistant`. The app targets Android API 37 and supports API 26 and newer.
