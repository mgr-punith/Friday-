# Friday testing

## Regression rule

Add a repeatable test when a build or feature failure has a deterministic reproduction. Keep hardware, OEM, permission-panel, launcher, and other device-only failures in the phone checklist with the exact reproduction steps. Do not mark a device case as automated coverage.

## Repeatable build checks

Run from the repository root:

```bash
./scripts/verify.sh
```

The script runs `./gradlew assembleDebug assembleRelease test lintDebug --console=plain`, reports each release APK size, and fails if any exceeds 15,000,000 bytes. Extra Gradle arguments are forwarded, for example `./scripts/verify.sh --offline`. Release APKs are unsigned until local signing is configured.

`./gradlew test` invokes two JavaExec tasks. `:app:actionLogicTest` runs the pure Kotlin action checks in `app/src/test/kotlin/com/example/assistant/actions/ActionLogicTests.kt`. `:app:voiceLogicTest` runs the audio and backend-contract checks in `app/src/test/kotlin/com/example/assistant/voice/VoiceLogicTests.kt`. They require no JUnit or additional dependency. A failed assertion exits with an error.

The action checks cover name normalization, exact and fuzzy matching, ambiguous candidates, weak-match rejection, bounded inputs, phone-number formatting and unsafe dial strings, alarm ranges, timer durations, and typed action validation. The voice checks cover canonical WAV headers, format rejection, upload size, silence trimming, speech detection, normalized audio levels, silence-stop behavior, every action response, malformed and extra arguments, backend error responses, `Server-Timing`, token refresh rules, HTTPS backend URL validation, entry-point auto-listen gates, and Quick Settings API boundaries.

The verification script also scans the Android source for banned device dependencies and APIs. This guard covers the earlier assistant-service regression that referenced `SpeechRecognizer`, which Friday must not use.

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

`npm run eval` sends the phrases in [test-phrases.md](test-phrases.md) to the selected evaluation provider. Copy `backend/.env.eval.example` to ignored `backend/.env` and add the required development key. The run uses real quota and prints overall accuracy, accuracy among completed responses, failures, median, P95, maximum latency, and a `SUMMARY_JSON` line. End-to-end latency includes wrong results, request failures, and timeouts. Stage latency includes stages that returned. CI does not run the evaluation because no real key belongs in GitHub checks.

Use `EVAL_OFFSET` and `EVAL_LIMIT` to resume a rate-limited suite without repeating earlier requests. Case numbers follow the selected manifest or phrase table. An offset of 10 starts at case 11. Keep a copy of each chunk's summary; the runner does not merge chunks.

Evaluation accepts capitalization, spacing, and accents that Android's name matcher handles. It ignores visual separators in phone numbers and the optional app name in Home navigation. It still rejects changed phone-number digits, different names, wrong timer or alarm values, wrong actions, and noise that selects an action. Output includes case numbers and mismatch categories, with no command text or transcripts.

Use the same phrase file, private WAV manifest, request delay, and timeout for each model comparison. Change only the provider or model variables between runs:

```bash
cd backend

EVAL_PROVIDER=gemini GEMINI_MODEL=gemini-3.1-flash-lite npm run eval
EVAL_PROVIDER=gemini GEMINI_MODEL=<second-audio-capable-model> npm run eval
EVAL_PROVIDER=groq npm run eval
```

The Groq text run measures only action classification. Use `npm run eval:audio` for the full sequential path through Whisper Large V3 Turbo and the action model. Node uses plain `fetch` for these requests. This code adds no production dependency and does not change the deployed provider.

For the AAC upload experiment, run each audio suite once with WAV and once with M4A:

```bash
EVAL_AUDIO_FORMAT=wav npm run eval:audio -- ../docs/indian-english-audio-eval.json
EVAL_AUDIO_FORMAT=m4a npm run eval:audio -- ../docs/indian-english-audio-eval.json
```

The M4A run calls local `ffmpeg` with mono 16 kHz AAC at 32 kbit/s before the API timing begins. It reports upload sizes with accuracy and latency. These numbers exclude conversion time, phone recording, Friday backend limits, and Android actions. Friday's Android app and production backend still accept WAV only.

Use real recordings to measure Indian English audio accuracy. Copy [indian-english-audio-eval.example.json](indian-english-audio-eval.example.json) to `docs/indian-english-audio-eval.json`, record 10 to 20 commands as 16 kHz mono 16-bit PCM WAV files under `docs/private-audio/`, and run:

```bash
cd backend
npm run eval:audio -- ../docs/indian-english-audio-eval.json
```

Git ignores the manifest and recordings. Keep names in the manifest aligned with the words you spoke. The result measures action and argument accuracy from audio; the text evaluation does not measure accent recognition.

Debug builds include a “Friday audio test” target in Android's Share menu. Sharing an audio file imports up to 5 MB into Friday's app-specific `files/audio-eval/recording.audio` folder. ADB can pull this file for local tests. The import does not send audio to a backend or perform a phone action. The release manifest must exclude this activity; `scripts/verify.sh` enforces that boundary. Normal voice commands remain in memory and do not use this storage.

For recordings with several commands, check each clip's start and end before labeling it. Keep unclear extra speech out of scored cases. Test silence and noise with `{"type":"error","reason":"bad_audio"}` as the expectation. The backend benchmark does not prove the phone's local speech detector rejected the same clip.

## Phone checklist

Install with `./gradlew installDebug` and launch Friday. Use a test phone and known contacts; record the device model, Android version, and each result.

| Area | Check and expected result |
|---|---|
| Permission setup | Open each sensitive disclosure. Cancel or press Back: no system permission prompt. Tap “Agree and allow”: Android manages the request. Deny access and continue: home remains usable. |
| Permission management | Reopen setup after granting or denying access; labels reflect Android's state. After permanent denial, agreeing opens app settings. Silent mode access opens Android's Notification Policy settings and refreshes on return. |
| Missing permissions | With phone or contacts access denied, try the corresponding action. Friday shows the required access without crashing or starting a call. |
| Contact matching | Try an exact known contact, Puneet/Punith, Pradeep/Pradip, a missing name, and a weak short prefix. Missing or weak matches must not call. Two contacts with the same phonetic form ask which one; multiple numbers must be distinguishable. |
| Final call confirmation | Choose a known contact or enter a valid number. The final dialog shows the resolved target. Cancel: no call. Only confirm using a number approved for the test; verify Android opens the call UI. |
| Input validation | Try an empty name, invalid alarm hour/minute, zero timer, and a dial string such as `*123#`. Friday rejects invalid input before side effects. |
| Alarm and timer | Send a test alarm with a distinct label and a short timer. Confirm the request in the installed clock app, then remove it. Friday reports “request sent”; it cannot verify another app saved it. Test an unavailable handler on a suitable device if available. |
| App launch and Home | Open a known launcher app; try an unknown label. Duplicate labels require a choice. Go to Home opens the launcher and reports “Went to home screen”. |
| Sound modes | Record the original mode. Without Silent mode access, request silent and confirm Friday offers permission management without changing the mode. Grant access, then test silent, vibrate, and normal. Verify Android's mode after each command and restore the original setting. |
| Lifecycle | Begin local resolution, then leave the action screen. No delayed action should launch from the background. Return and retry; the screen remains usable. |
| UI and orb | Check graphite dialogs, readable labels, focused action editors, keyboard scrolling, enlarged system text, and landscape. Compare two idle screenshots several seconds apart: the orb must match. While listening, quiet input stays frozen and speech moves only the internal light fields. The circle must not resize or shift. Disabling animators freezes motion. Leaving the app pauses animation. |
| Voice permission | Deny microphone access, then tap the home mic. Friday shows a permission action and does not crash. Grant access through Friday's disclosure, then return to the home screen. |
| Voice command | Tap the mic and say “Set a timer for two minutes”. Listening reacts to speech, stops after silence, changes to processing, and sends one timer request. Confirm the clock app received it. |
| No speech and network | Start recording without speaking, then stop after one second. Friday reports “I couldn't hear a clear command” without uploading. Short manual stops stay local; natural speech can still finish through silence detection. Disable the network, record a clear command, and confirm the app reports “No internet” without losing usability. |
| Noise rejection | Make steady, changing, tonal, and broadband noise without speaking. Friday reports that it did not hear a clear command. It must not change sound mode, place a call, open an app, create an alarm or timer, or show the unsupported-feature dialog. |
| Unsupported request | Ask a question or request an unavailable phone feature. Friday shows “That feature isn't built yet” on the main screen. No dialog or Android action starts. |
| Voice lifecycle | Start recording, press Home, then return. Capture stops and no delayed phone action starts while Friday is hidden. Debug logcat includes `FridayVoice` record, audio, upload, server, and action timings for completed requests. |
| Ready notification | Turn on Ready notification and allow notifications if Android asks. Confirm one ongoing “Friday ready” notification appears without sound, vibration, or lights. Confirm Android shows no microphone privacy indicator before you tap it. Tap the notification: Friday opens and starts listening. |
| Ready notification controls | Turn the notification off and confirm it disappears. Turn it on, force-stop Friday from Android Settings, then reopen Friday and confirm the notification returns. Update or reinstall the debug build and confirm it returns. Restart the phone and confirm the enabled notification returns before and after unlock. On Vivo, also allow Friday to auto-start if Funtouch OS blocks the boot receiver. If Android notifications are blocked, Friday must direct you to app notification settings. |
| Quick Settings | Add the Friday tile, confirm its label and “Tap to speak” subtitle where Android supports subtitles, then tap it. Friday opens and starts listening. Test from another app and from the launcher. |
| Home-screen widget | Add the Friday widget. Confirm the text remains readable with large system text and that the whole widget is a clear tap target. Tap it from the launcher; Friday opens and starts listening. |
| Default assistant | Choose Friday under Power button assistant. Long-press the power button from the launcher and another app. Friday opens and starts listening. Restore the original assistant after the test if required. |
| Spoken confirmation | Complete a harmless action with an English TTS engine installed and confirm Friday speaks the short on-screen result once. Disable or remove the TTS engine on a suitable test device and confirm Friday keeps the on-screen result without crashing. |

Mark untested cases explicitly. Passing JVM checks or dispatching an Android intent does not prove the target app completed its work.

## Voice UI regression checks

Install a debug APK on an unlocked phone, then run:

```bash
python3 scripts/ui-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb
```

The script checks the launcher, icon bottom navigation with selected tabs, compact assistant panel, processing cancellation, unsupported and error messages, permission recovery, interpreted action, panel dismissal, microphone lifecycle, 150% text, landscape, and both system themes. It uses debug preview states without executing phone commands. It restores text size, rotation, and system theme in a `finally` block. Screenshots, the latest hierarchy, and the report go to `/tmp/friday-ui-check` by default. Keep the phone untouched during the run.

The release manifest check in `scripts/verify.sh` keeps the assistant panel and permission screens private. Debug builds expose the panel for ADB visual tests; release builds do not. Phone regression checks assert that tab switches retain the same Activity instance, Settings scroll, and the latest Home result. Reselecting a tab does not launch a page, and Android Back returns to Home. JVM tests cover tab reselection and TTS cancellation, replacement and duplicate callbacks, timer and alarm formatting, and honest Home-navigation copy.

Manual playback checks: run a successful command, confirm the spoken result matches the visible result, stop speech with the square control, replay it, and start a new recording. Check that an old utterance cannot resume after leaving the app. Engines that do not supply PCM callbacks show the speaking state with a frozen orb. No English TTS engine should leave the result readable and hide replay.
