# Friday UI checkpoint

Saved on 19 November 2026. The owner resumed work and authorized publishing the UI milestone on a dedicated branch. Merge requires a separate instruction. Complete this step before starting wake detection.

## Working state

- Repository: `/home/puneet/zix/project/friday`
- UI branch: `feature/assistant-ui`, created from `origin/main` at `621a5bb`. The earlier `feature/hey-friday` branch remains at that base.
- Publish the UI changes and tests together. Keep wake detection in a later branch.
- The debug APK is installed on the Vivo V2168, Android API 34. The owner reconnected the phone and reported that the app works. Wireless debugging addresses change between sessions.
- The phone test script restores font scale, rotation, and system theme and opens the normal Friday home screen when it finishes.

## Accepted UI

- Keep Geist, near-black backgrounds, graphite controls, and Untitled UI outline icons.
- Home contains the Aurora orb, status, and microphone controls. Setup and phone-action tools belong in Settings.
- Home orb view: 168 dp. The sphere occupies 70% of the view. Keep its circular shape and position fixed.
- Idle and silence freeze the orb. Microphone energy and low/mid/high balance move the internal gradients during speech.
- Use smaller text: 20 sp screen headings, 18 sp compact status, 13 sp body text, 14 sp buttons, and 11 sp navigation labels. Respect Android font scaling.
- Keep the microphone lower, above the navigation capsule.
- Remove the “English · Phone commands” footer. Keep suggestion chips and chat inputs absent.
- Use the owner's rounded graphite navigation reference, with a pill behind the selected icon. Home Line and Settings 01 come from Untitled UI.
- Save the supplied reference in `design/reference/friday-navigation.png`. Design images do not ship in the APK.

## Navigation and interaction

Home and Settings share one Activity and one navigation bar. `TabHostView` creates Settings on first use, retains both views, and switches their visibility. Repeated taps on the selected tab do nothing. Settings keeps its scroll position; Home keeps the latest completed result across tab switches. Future main tabs must use this pattern instead of starting another Activity.

Opening Settings stops microphone capture, pending voice work, and speech. Returning Home does not start recording. Android Back from Settings returns Home. The launcher enables native predictive Back; a separate API-gated helper registers the callback on API 33+. The physical Back key supplies the older-device fallback.

Notification, widget, tile, and digital-assistant entry points target `AssistantActivity`, a visible compact bottom panel over the previous app. It has a close control, outside dismissal, and separate task affinity to avoid exposing the full launcher screen underneath. The panel is private in release and exposed only in debug for ADB QA.

Listening shows a square finish control and a separate Cancel button. Processing permits cancellation, disconnects the active command connection, and rejects late results. Controls remain visible while status and result content scroll. Landscape places status and controls beside each other.

Results show the interpreted action under “Understood.” This text describes returned action arguments; it is not a transcript. Unsupported requests and errors stay inside the voice surface. Call confirmations and contact choices retain the existing action safeguards.

Device TextToSpeech callbacks supply the speaking state, stop/replay controls, and playback-timed PCM energy for the orb. Utterance identities reject callbacks from cancelled, replaced, or completed speech. Engines without suitable English support retain the visible result; engines that omit PCM retain the speaking label with a frozen orb.

## Verification completed

Last full Android command:

```bash
GRADLE_USER_HOME=/tmp/friday-gradle ./scripts/verify.sh --offline --no-daemon
```

This passed debug/release builds, 58 action checks, 183 voice checks, lint, the release entry-point check, and the APK size limit. Lint reported zero errors and 36 warnings. Gradle needed execution outside the sandbox because its local lock service could not initialize within it. The publication check used cached Gradle 9.4.1 with offline dependencies: `gradle assembleDebug assembleRelease test lintDebug --offline --no-daemon --console=plain`. It passed the same 58 action and 183 voice checks and lint. The repository keeps Gradle 9.1.0; its fresh download was too slow to complete locally, so GitHub CI must verify that pinned version before merge.

Final release APK: **225,616 bytes**, at `app/build/outputs/apk/release/app-release-unsigned.apk`. It is unsigned. No runtime dependencies, audio models, or orb images were added.

The final debug APK was installed with:

```bash
/home/puneet/Android/Sdk/platform-tools/adb -s 192.168.1.13:37375 install -r app/build/outputs/apk/debug/app-debug.apk
```

Phone regression command:

```bash
python3 scripts/ui-phone-check.py --serial 192.168.1.13:37375 --adb /home/puneet/Android/Sdk/platform-tools/adb
```

**33 phone UI checks passed in the publication recheck.** They cover selected navigation tabs, the same Activity instance, tab reselection, retained scroll and results, Android Back, compact states, cancellation, permission recovery, unsupported feedback, microphone lifecycle, 150% text, rotated landscape, and both system themes. Most result states use labelled debug previews and do not execute phone actions. Publication screenshots and the report are in `/tmp/friday-ui-publish-check`; temporary evidence may disappear after a reboot.

The phone also supplied `ttsSpeaking=true` and `ttsAudioAvailable=true` during a debug spoken preview. The screenshot showed the speaking label, square stop control, and changed orb flow. This verifies playback callbacks and visual state; it does not verify audible voice quality or exact audio/animation synchronization.

`git diff --check` passed. The Android verification script checks forbidden device APIs/dependencies and keeps the recording importer out of release. Backend code did not change in this UI phase. Before publication, `npm run verify` passed all 78 tests, type checking, the Vercel configuration checks, and both backend bundles.

## Regression coverage added

- JVM checks for TTS cancellation, replacement, duplicate completion, action descriptions, audio levels without speech classification, and tab reselection.
- `scripts/check-release-manifest.py` verifies private assistant/permission entry points, predictive Back opt-in, absence of a separate Settings Activity, and exclusion of the debug recording importer.
- `scripts/ui-phone-check.py` verifies device UI states and navigation with saved screenshots and restores temporary device settings.
- Publication testing fixed the panel-dismissal assertion to ignore Friday widgets on the launcher; it now checks the active Activity and close control.
- Phone tests caught result/control overflow and a missing microphone accessibility label after Activity recreation. Both were fixed and included in the final checks.
- Build/lint checks caught the debug manifest override and legacy Back handling. The final build and phone Back check pass.

## Files to resume from

- `MainActivity.kt`: voice lifecycle and shared tab host.
- `AssistantActivity.kt`: compact contextual panel.
- `SettingsPanel.kt`: retained Settings content and permission/role controls.
- `ui/VoiceSurface.kt`, `BottomNavigation.kt`, `TabHostView.kt`, `TabNavigation.kt`: shared presentation and navigation.
- `ui/ActionPresentation.kt`, `AppUi.kt`, `MicButtonView.kt`, `AssistantOrbView.kt`, `FridayDialog.kt`: result copy, smaller typography, controls, and orb rendering.
- `voice/ConfirmationSpeaker.kt`, `SpeechSession.kt`, `FridayBackendClient.kt`, `PcmProcessor.kt`: playback feedback and cancellation.
- Main/debug manifests, theme/colors, and Home Line, Settings 01, X Close vectors.
- `VoiceLogicTests.kt`, `scripts/verify.sh`, and the two new verification scripts.
- README, `design/README.md`, `docs/testing.md`, and `docs/ui-motion.md` document the final behavior.

Paths above are relative to `app/src/main/kotlin/com/example/assistant` unless another prefix is shown. Check `git status --short` for the full change list before resuming. Do not discard or overwrite the saved work.

## Work still outside this UI phase

“Hey Friday” detection and conversational question answering remain unimplemented. Keep production on the owner-approved Gemini 3.1 Flash-Lite and WAV path. The 2.5-second median phone-to-action target remains unverified.

Before release, run real spoken commands through the updated panel, including confirmation/replay/stop, call cancellation, notification/widget/tile launches, and the physical power-button shortcut. This phase verified API 34 on one phone; API 26–32 fallback, newer-device predictive Back gestures, phones without Google services, absent TTS engines, and sustained frame-rate performance need device coverage. Tab retention currently applies within the live Activity; Android recreation still resets the voice result. Review that separately if session persistence becomes a requirement.

Keep Stop Slop mandatory for UI copy and documentation only. Do not add dependencies without permission. Keep secrets backend-only. Do not print or store credentials in a handoff.
