# Hey Friday

Hey Friday is a small, fast Android voice assistant for phone commands: calls, alarms, timers, apps, and ringer modes. It is native Kotlin and is designed to work without Google Play Services.

## Current milestone

Milestone 0 provides the Android project skeleton and a dependency-free framework-Views interface with one reusable assistant orb. The visual references and rules live in `design/`. The interface uses a near-black and violet theme, with a fixed circular blue-sky orb and slow, procedurally evolving white clouds. Android 13+ uses an AGSL shader; API 26–32 and software canvases use cached gradient clouds at approximately 30 FPS. There are no packaged cloud images, borders, rings, or particles. Animation pauses when the view or app is hidden and respects the system animator setting.

Idle is continuously animated. The component preserves the existing state API and provides movement parameters for preview, listening, processing, and speaking. Voice capture and the wake-phrase service are not connected yet; the mic button demonstrates a slightly faster cloud preview without recording audio. Audio input hooks are prepared for later voice integration.

The intended wake experience is:

1. The user explicitly enables always-listening mode.
2. Saying “Hey Friday” opens a compact round assistant surface over the current context.
3. Live microphone energy gently changes cloud movement while the user speaks; the sphere stays stationary.
4. The surface transitions to processing, success, or error without claiming an action before Android confirms it.

Always-listening mode will require a user-started microphone foreground service, a persistent notification, and a local or downloaded wake-phrase detector. It will not continuously upload ambient audio.

## Build

```bash
./gradlew assembleDebug
./gradlew test
./gradlew assembleRelease
./gradlew :app:dependencies
```

The placeholder package is `com.example.assistant`. The app targets Android API 37 and supports API 26 and newer.
