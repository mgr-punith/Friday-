# Friday testing

## Repeatable build checks

Run from the repository root:

```bash
./scripts/verify.sh
```

The script runs `./gradlew assembleDebug assembleRelease test lintDebug --console=plain`, reports each release APK size, and fails if any exceeds 15,000,000 bytes. Extra Gradle arguments are forwarded, for example `./scripts/verify.sh --offline`. Release APKs are unsigned until local signing is configured.

`./gradlew test` invokes two JavaExec tasks. `:app:actionLogicTest` runs the pure Kotlin action checks in `app/src/test/kotlin/com/example/assistant/actions/ActionLogicTests.kt`. `:app:voiceLogicTest` runs the audio and backend-contract checks in `app/src/test/kotlin/com/example/assistant/voice/VoiceLogicTests.kt`. They require no JUnit or additional dependency. A failed assertion exits with an error.

The action checks cover name normalization, exact and fuzzy matching, ambiguous candidates, weak-match rejection, bounded inputs, phone-number formatting and unsafe dial strings, alarm ranges, timer durations, and typed action validation. The voice checks cover canonical WAV headers, format rejection, upload size, silence trimming, speech detection, normalized audio levels, silence-stop behavior, every action response, malformed and extra arguments, backend error responses, `Server-Timing`, token refresh rules, and HTTPS backend URL validation.

These checks do not execute Android permissions, microphone hardware, network requests, contacts providers, clock apps, calls, or ringer changes. Record phone results separately. Review `./gradlew :app:dependencies` after dependency changes to confirm the app remains free of Google service and AWS SDK dependencies.

## Backend checks

Run from `backend/`:

```bash
npm ci
npm run verify
sam validate --lint --template-file template.yaml
sam build --template-file template.yaml
```

The Node tests mock Gemini, Redis, DynamoDB, and SSM. They cover JWT tampering and expiry, WAV structure, Vercel request adaptation, Vercel route configuration, authentication, input limits, action validation, one-call enforcement, Gemini retry and timeout handling, blocked installs, and atomic usage limits. `npm run build` fails if either backend bundle exceeds 5 MB. Vercel runs the platform build after the repository is linked; the local check does not need project credentials.

`npm run eval` sends the phrases in [test-phrases.md](test-phrases.md) to the configured Gemini model. It needs a key in ignored `backend/.env`, uses real quota, and prints accuracy plus median and P95 Gemini latency. CI does not run it because no real key belongs in GitHub checks.

Use real recordings to measure Indian English audio accuracy. Copy [indian-english-audio-eval.example.json](indian-english-audio-eval.example.json) to `docs/indian-english-audio-eval.json`, record 10 to 20 commands as 16 kHz mono 16-bit PCM WAV files under `docs/private-audio/`, and run:

```bash
cd backend
npm run eval:audio -- ../docs/indian-english-audio-eval.json
```

Git ignores the manifest and recordings. Keep names in the manifest aligned with the words you spoke. The result measures action and argument accuracy from audio; the text evaluation does not measure accent recognition.

## Phone checklist

Install with `./gradlew installDebug` and launch Friday. Use a test phone and known contacts; record the device model, Android version, and each result.

| Area | Check and expected result |
|---|---|
| Permission setup | Open each sensitive disclosure. Cancel or press Back: no system permission prompt. Tap “Agree and allow”: Android manages the request. Deny access and continue: home remains usable. |
| Permission management | Reopen setup after granting or denying access; labels reflect Android's state. After permanent denial, agreeing opens app settings. |
| Missing permissions | With phone or contacts access denied, try the corresponding action. Friday shows the required access without crashing or starting a call. |
| Contact matching | Try an exact known contact, Puneet/Punith, Pradeep/Pradip, a missing name, and a weak short prefix. Missing or weak matches must not call. Two contacts with the same phonetic form ask which one; multiple numbers must be distinguishable. |
| Final call confirmation | Choose a known contact or enter a valid number. The final dialog shows the resolved target. Cancel: no call. Only confirm using a number approved for the test; verify Android opens the call UI. |
| Input validation | Try an empty name, invalid alarm hour/minute, zero timer, and a dial string such as `*123#`. Friday rejects invalid input before side effects. |
| Alarm and timer | Send a test alarm with a distinct label and a short timer. Confirm the request in the installed clock app, then remove it. Friday reports “request sent”; it cannot verify another app saved it. Test an unavailable handler on a suitable device if available. |
| App launch and Home | Open a known launcher app; try an unknown label. Duplicate labels require a choice. Go to Home opens the launcher and reports “Went to home screen”. |
| Sound modes | Record the original mode. Try normal and vibrate, verify Android's actual mode, then restore it. A silent request must say `Silent mode is unavailable. Try vibrate mode.` and must not open Do Not Disturb settings. |
| Lifecycle | Begin local resolution, then leave the action screen. No delayed action should launch from the background. Return and retry; the screen remains usable. |
| UI and orb | Check graphite dialogs, readable labels, focused action editors, keyboard scrolling, enlarged system text, and landscape. Compare two idle screenshots several seconds apart: the orb must match. While listening, quiet input stays frozen and speech moves only the internal light fields. The circle must not resize or shift. Disabling animators freezes motion. Leaving the app pauses animation. |
| Voice permission | Deny microphone access, then tap the home mic. Friday shows a permission action and does not crash. Grant access through Friday's disclosure, then return to the home screen. |
| Voice command | Tap the mic and say “Set a timer for two minutes”. Listening reacts to speech, stops after silence, changes to processing, and sends one timer request. Confirm the clock app received it. |
| No speech and network | Start recording without speaking, then stop after one second. Friday reports “I couldn't hear a clear command” without uploading. Short manual stops stay local; natural speech can still finish through silence detection. Disable the network, record a clear command, and confirm the app reports “No internet” without losing usability. |
| Unsupported request | Ask a question or request an unavailable phone feature. Friday shows “Feature not built yet” and lists the commands available now. No Android action starts. |
| Voice lifecycle | Start recording, press Home, then return. Capture stops and no delayed phone action starts while Friday is hidden. Debug logcat includes `FridayVoice` record, audio, upload, server, and action timings for completed requests. |

Mark untested cases explicitly. Passing JVM checks or dispatching an Android intent does not prove the target app completed its work.
