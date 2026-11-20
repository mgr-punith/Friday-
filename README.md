# Hey Friday

Hey Friday is a small, fast Android voice assistant for phone commands: calls, alarms, timers, apps, and ringer modes. It is native Kotlin and is designed to work without Google Play Services.

See [the UI guide](design/ui-guide.md) for spacing, typography, icon sizes and Back navigation.

## Current milestone

The UI phase is merged. Friday keeps Home and Settings in one Activity, opens a compact assistant panel from shortcuts, and shows voice and playback controls. The current phase evaluates “Hey Friday” detectors. Sherpa, Vosk, PocketSphinx and the tested community microWakeWord model have not passed the accuracy check; tap shortcuts remain available. The current branch also improves local app-name matching for commands such as “Open Google Chrome”. See the [detector comparison](docs/wake-phrase-evaluation.md), [microWakeWord recording tests](docs/microwakeword-evaluation.md) and [model measurements](docs/model-notes.md). Production still uses Gemini 3.1 Flash-Lite and WAV.

Debug builds include an audio import target for developer recordings shared from the phone's recorder. These test files stay in app-specific storage until removed. Release builds exclude the importer, and normal voice capture does not save audio. See [recording tests](docs/testing.md).

The approved debug-only wake trial lives under **Settings → Wake detector test**. An opt-in ARM64 build adds the microWakeWord runtime; **Settings → Start Friday → Hey Friday → Download Hey Friday** installs the pinned 61 KB model in private storage. The command trial starts at cutoff **0.30**, with **On wake → Open Friday**. Tap Start local test, say “Hey Friday”, wait for the command panel to show Listening, then speak a command. Friday releases the wake recorder before command capture and pauses wake listening while the assistant or permission setup is open. Dismiss the panel to resume wake listening. A wake-opened panel closes three seconds after a no-command or action error so you can say “Hey Friday” again without tapping. Tapping retry cancels that close; permission prompts and manually opened panels stay open. The debug implementation can request the assistant panel over the lock screen. If the system assistant session cannot open, tap the wake-test notification. A separate Count detections mode runs no commands. Stop test turns the listener off; a phone restart requires another manual Start. The test saves up to 20 runs of counts and timings in private debug storage, without ambient audio or transcripts. Release builds exclude the screen, runtime and microphone service. See [setup and phone checks](docs/wake-detector-trial.md). This trial does not establish production wake accuracy.

You can keep a silent “Friday ready” notification, add a Quick Settings tile or home-screen widget, and choose Friday as the default assistant. Each entry point opens a compact bottom panel over the current app and starts one recording. The launcher opens the full voice screen. The microphone stays off until you tap an entry point or invoke the selected assistant.

Tap **Settings → Default assistant** to open Android's assistant settings, then select Friday. If the phone blocks that screen or lacks it, Friday tries Default apps, then Settings. Returning without selecting Friday shows an **Open Default apps** fallback. Friday checks the selected voice service when you return. Android requires you to make the selection; manufacturers can change the screens and assistant gestures, so Friday cannot guarantee a particular power-button shortcut on every phone.

Tap the microphone to record one 16 kHz command. Friday warms the Vercel Function while you speak, uploads a trimmed WAV, receives one validated action, then runs it from the visible activity. The app registers an anonymous install ID in the background and refreshes its token before expiry. Successful actions can use Android TextToSpeech for a short confirmation. Friday keeps the on-screen confirmation when no English TTS engine is available.

One Vercel Function in Mumbai accepts 16 kHz WAV or debug text, calls Gemini through REST, validates one forced function call, and returns the action contract in [docs/actions.md](docs/actions.md). Upstash Redis applies per-install and global limits. Vercel stores the Gemini and JWT values in its encrypted environment. The backend does not log audio, text commands, tokens, contacts, or app names. The AWS SAM deployment remains available as a fallback.

The native Kotlin framework-Views app includes phone actions and permission setup. The action test screen takes typed inputs and performs calls, clock requests, app launches, Home navigation, and ringer changes. Friday matches contacts and installed apps on the device. “Open Google Chrome” opens the app listed as “Chrome”. When several matches are plausible, Friday asks you to choose. Before a call, Friday shows the contact or number and asks you to confirm. Missing permissions, invalid inputs, and unavailable Android handlers return short feedback.

Friday treats each request as a phone command, selects one supported action, resolves device data on the phone, and completes the action from the visible UI. If the request needs a feature outside the current action set, the main screen says “That feature isn't built yet”. Friday does not open a modal or claim that the action ran.

Setup shows Friday's disclosure before each microphone, contacts, or phone permission prompt. Silent mode has a separate disclosure before Android's Notification Policy settings. The microphone disclosure explains that recorded commands go to Friday's Vercel backend and Google Gemini, and that Friday does not store audio. Access is optional and can be managed later. Friday uses compact graphite controls and its own dialogs. Clock confirmations report that a request was sent, and Home navigation never claims another app was closed. See [the action contract](docs/actions.md).

The home screen focuses on the orb and voice controls. A rounded Home and Settings bottom bar uses Untitled UI outline icons and exposes the selected destination. Both tabs share one Activity; Friday creates their views once and retains Settings scroll and the latest Home result. Opening Settings stops active microphone or command work. Android Back returns from Settings to Home. Settings holds the ready notification, assistant selection, permissions, and phone-action tools. Voice controls stay visible below scrolling status and result content. The button changes from a microphone to a stop control while listening, cancelling a pending command, or stopping speech. Results include the interpreted action, without claiming to display a transcript. Friday keeps unsupported requests and errors inside the voice surface. The screens use a near-black background, graphite controls, the Geist font, and Untitled UI outline icons.

Android TextToSpeech supplies short confirmations when an English engine exists. Playback callbacks show the speaking state. Engines that supply 16-bit PCM also drive the orb with playback-timed audio energy. Friday offers stop and replay controls, cancels stale speech callbacks, and stops voice work when the screen leaves the foreground. Devices without a suitable engine retain the on-screen result.

The reusable Aurora orb uses blue, cyan, lilac, and pearl light fields with spherical depth and a restrained atmospheric glow. Its circular silhouette stays fixed. API 33+ hardware rendering uses a small procedural AGSL shader; API 26–32, software canvases, and shader compilation failure use a cached Canvas approximation. There are no packaged orb images or animation dependencies. Animation pauses when the view or app is hidden and respects the system animator setting.

Idle and quiet listening are frozen. During recording, raw microphone energy must cross an adaptive room-noise gate before the orb moves. Low, mid, and high energy change the internal flow while the sphere stays still. Motion settles after speech ends. Hardware rendering schedules active frames with display vsync; software rendering uses a limited cadence. Frame rate depends on the device.

The debug build also has **Settings → Start Friday**. Choose **Hey Friday** for the wake phrase or **Tap to speak** to keep background wake listening off. Each option has a tap-to-open info popup. Enabling Hey Friday requires agreement to the microphone disclosure, microphone and notification access, and Friday as the default assistant. Its silent notification has a **Pause/Resume** icon. Pause releases the microphone and stops the foreground service; Resume starts it from a notification tap. Choosing Tap to speak removes that notification. The selection does not restart the microphone after a phone restart. The advanced Wake detector test remains available. On a new phone, tap **Download Hey Friday** once. Friday keeps the installed model in private storage and reuses it after reopening the app or restarting the phone. Friday shows download progress, verifies the model, then installs it. Tap **Use Hey Friday** to enable listening after setup. Downloading needs no storage permission and starts no microphone. These controls require the wake-enabled debug APK on a 64-bit ARM phone; a build without its native runtime shows a separate message.

The [phone checks](docs/testing.md#android-11-wake-notification-regression) include notification startup and Pause/Resume on the Realme RMX2050 running Android 11.

While the phone is locked, the debug command policy allows calls, timers and sound-mode changes. Calls still show the normal **Start a phone call?** confirmation; **Call** starts it, and **Cancel** does not. App launches, alarms and Home navigation require unlocking. Friday shows “Unlock your phone, then ask again” before resolving an app name. Tap **Unlock phone** to open Android's unlock flow, then repeat that blocked command. Friday does not unlock the phone on a wake or replay a blocked command. Permission setup also requires unlocking. This describes the implementation; see the testing notes for device coverage and remaining limits.

The debug wake-to-command trial uses this flow:

1. The user explicitly enables always-listening mode.
2. Saying “Hey Friday” requests Friday's existing compact command panel over the current app or lock screen, with a notification fallback.
3. Live microphone energy gently changes the internal gradient movement while the user speaks; the sphere stays stationary.
4. The surface transitions to processing, success, or error without claiming an action before Android confirms it.

The debug trial uses a user-started microphone foreground service, a persistent notification and a downloaded local wake detector. Ambient audio stays on the phone. Say the command after the Listening indicator; this trial does not preserve words spoken during the wake-to-panel handoff. Release builds retain tap shortcuts. See the [first detector comparison](docs/wake-phrase-evaluation.md).

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

The Android verification script runs dependency-free Python tool tests, builds debug and release APKs, runs action, voice and debug wake checks, runs debug lint, then enforces the 15 MB release APK limit. The voice checks cover WAV structure, silence trimming, speech-end detection, response parsing, malformed arguments, token refresh rules, and backend URL validation. `npm run verify` type-checks the backend, runs mocked tests, checks `vercel.json`, and creates size-limited Vercel and Lambda bundles. GitHub CI also validates and builds the AWS fallback. Vercel runs its platform build for each Git-connected preview and production deployment. See [the testing checklist](docs/testing.md) and [backend deployment guide](docs/backend-deployment.md).

The placeholder package is `com.example.assistant`. The app targets Android API 37 and supports API 26 and newer.
