# Debug wake-detector trial

The owner approved microWakeWord as the first phone trial on 19 November 2026, then chose cutoff **0.30** for a debug wake-to-command trial after testing the sensitivity settings. This build can count local “Hey Friday” detections or open Friday's existing assistant panel. The current community model missed phrases and produced false wakes in the [recorded evaluation](microwakeword-evaluation.md); the debug trial does not replace those measurements or establish release accuracy.

## Build and install

Use the existing pinned native build helper. Keep its work directory outside the repository. It downloads pinned runtime sources and native build dependencies on its first run; it does not train or download a model. This uses the small inference runtime, without the rejected training workspace.

```bash
python3 .local/tools/build-microwakeword-eval.py /tmp/friday-microwakeword-20261119 \
  --android-ndk "$ANDROID_HOME/ndk/27.1.12297006" \
  --abi arm64-v8a --debug-jni

./gradlew assembleDebug \
  -PwakeTrialNativeDir=/tmp/friday-microwakeword-20261119/android-debug-jni

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pass `--reuse-dependencies /path/to/native-linux/_deps` to reuse the standalone evaluation's source downloads. The helper builds and strips the library, then places it under `android-debug-jni/arm64-v8a`. Standard debug builds omit that library unless the Gradle property points to it. Release builds ignore the property and exclude the trial source set.

For setup on the phone, open **Settings → Start Friday**, select **Hey Friday**, then tap **Download Hey Friday**. Friday downloads the pinned 61 KB community model over HTTPS, shows percentage progress and an installing state, verifies the exact size and SHA-256, then renames the private temporary file into place. Tap **Use Hey Friday** after installation to agree to the microphone disclosure and allow microphone/notification access. Downloading starts no microphone and needs no storage permission. The model stays outside the APK.

Setup offers retry for a missing or corrupt model. Cancel or Back cancels the current task; a cancelled or failed update preserves an existing verified model. Leaving the process during a download cannot activate an incomplete file. A build without the native runtime tells you to install the wake-enabled APK. The current native runtime supports ARM64 phones. Friday downloads model data only; it does not download executable code.

The download uses jsDelivr's HTTPS mirror of the pinned GitHub revision. Both copies contain 60,832 bytes with SHA-256 `04aaffa5f111704db18ec593251a3dfbb009638009e081c9c87830755c3563a6`. Setup allows 25 seconds before offering retry. Android's connection/read timeouts alone did not end a stalled request to GitHub's raw-file host within the phone check's 35-second deadline, so keep that case in the network regression checklist.

Developers can still side-load the pinned model from the earlier evaluation:

```bash
python3 .local/tools/install-wake-trial-model.py /path/to/hey_friday.tflite \
  --serial DEVICE_SERIAL --adb /path/to/adb
```

The installer verifies the exact 60,832-byte model and SHA-256 before copying it, then verifies the phone copy. `run-as` limits this route to debuggable installs. The file stays in private `files/wake-trial/hey_friday.tflite`, outside the APK. Replacing it with a different model requires a code review and new identity checks. A missing, truncated or modified model prevents capture.

## Try it

### Start options

Open **Settings → Start Friday** for the two entry choices:

- **Hey Friday**: while unlocked, apply the choice, agree to the microphone disclosure, and allow microphone/notification access. If setup shows **Set default assistant**, tap it and choose Friday in Android's assistant settings. Friday verifies your selection on return before starting listening. Cancelling keeps listening off and shows **Open Default apps** as another route. The choice uses the measured command cutoff of **0.30** and the verified private model.
- **Tap to speak**: apply the choice to stop wake listening and remove its notification. The microphone button and existing manual shortcuts remain available.

The rows show only their names. Tap the info icon to read a short explanation; dismiss it with Back or an outside tap. Help does not select the option or start the microphone. Selection alone starts no recording.

Hey Friday shows a silent notification with an Untitled UI **Pause Circle** or **Play Circle** icon. Pause releases the recorder and native handle, stops the microphone foreground service, and leaves a normal notification with Resume. Resume uses an explicit notification tap to start the service. During a phone command, the control still offers Pause while wake capture waits for the visible assistant. The label **Friday is open** distinguishes that handoff from a user pause. Switching to Tap invalidates pending controls and removes the notification. Enabling Hey Friday turns off the separate Ready notification to avoid two shortcuts.

The preference stores a choice, not permission to start from boot. After restarting the phone, open Friday and start again. Android can remove notifications or stop the app through its own controls; the app does not bypass those controls. The entry-choice screen, notification controls, runtime and service remain debug-only.

`WakeUsagePolicy` validates mode/revision changes and adoption of an existing command trial. A notification action consumes a revision, so a delayed or duplicate tap cannot restart a later session or override Tap to speak. The service rejects missing permissions and keeps `START_NOT_STICKY`. The notification uses native `RemoteViews` within system decoration, 48 dp icon targets, system-theme foreground colors and a single-line status that can ellipsize at large text sizes. Its accessibility label retains the action name. No library, model or backend endpoint was added.

Run the phone check with the installed native debug build and approved private model:

```bash
python3 .local/tools/wake-usage-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb
```

The checker opens both help popups, verifies exclusive selection and disclosure cancellation when consent is new, then tests notification Pause/Resume, repeated toggles, both system themes and Tap mode. It returns Home before notification Resume so a wake-opened panel cannot occupy the microphone during the background check. Its no-command preview uses the privileged debug broadcast receiver; launching the legacy preview Activity can bring an existing setup task forward on Android 11. It restores the system theme and ends with background wake listening off. `--controls-only` skips already-checked option help during a notification investigation. Large-text, rotation and reduced-motion checks require `--display-checks`; the default run changes none of those settings. The owner asked to stop large-font tests, so do not add that flag without a new request. The checker never submits a deliberate cloud command or measures recognition accuracy. Keep the phone untouched while it runs.

### Advanced detector comparison

1. Open Friday → Settings → Wake detector test.
2. Confirm **Sensitivity → 0.30** and **On wake → Open Friday**. Read the microphone notice and tap **Start local test**. Allow microphone and notifications if Android asks. The test requires notifications so you retain a Stop control outside the app.
3. Press Home. Say “Hey Friday”, wait for the compact panel to show **Listening**, then say a command such as “Open Calculator”. Dismiss the panel or return Home to resume wake listening. If Friday hears no clear command or reports an error, a wake-opened panel closes after three seconds, then resumes wake listening. Say “Hey Friday” again after it closes. Tapping retry cancels the close. Permission prompts and manually opened panels stay open. The first version does not retain words spoken during the handoff, so leave a pause after the wake phrase.
4. If the panel does not open, tap **Wake heard · Tap to speak** in the wake-test notification. Choose Friday as the default assistant for wake opening. The debug implementation also requests the panel above the lock screen, under the command policy below.
5. Speak unrelated sentences and play background noise. Record false detections as well as missed phrases. Use **On wake → Count detections** for comparisons without command capture.
6. Tap **Stop test** in the screen or notification to turn wake listening off. On Vivo, expand Friday's notification group and then the wake-test notification to reveal its action.

Sensitivity starts at the owner-selected command cutoff, `0.30`. Tap the Sensitivity row while stopped to cycle through `0.10` and the model default, `0.66`. Lower cutoffs caught more recorded phrases but also triggered on more negative clips. Start resets the count and records the chosen cutoff and mode for that run. Compare candidates at their defaults before tuning against these same recordings.

In command mode, Friday pauses the wake recorder while its assistant or permission setup is open. Command capture waits for wake-recorder cleanup. Dismissal permits the existing foreground service to resume after an 800 ms delay, once command capture has released the microphone. A wake-opened panel shows an error for three seconds before closing through the same dismissal path. A new command, touch, permission prompt, success, lifecycle pause or Stop cancels the pending close. Recovery checks the originating wake run before dismissing; it does not start another cloud recording. This prevents the wake detector from hearing Friday's TTS while the panel is visible. Count-only mode stops when the normal assistant opens. Stop, process death, force-stop, task removal and phone restart require a manual Start. No boot receiver starts this service.

### Lock-screen wake

Start the debug listener while the phone is unlocked, then lock the phone. A local wake requests the selected assistant session and a compact panel that can show above keyguard and turn on the screen. The panel does not dismiss the lock. The wake service keeps the same single-use handoff and notification fallback.

While locked, Friday allows **calls**, **timers** and **silent, vibrate or normal sound mode**. A call still requires the normal **Start a phone call?** confirmation. Tap **Call** to start it or **Cancel** to stop. App launches, alarms and Home navigation require unlocking. Friday checks before resolving app names or showing a protected action, and again before an Android effect. An unavailable lock state uses the locked policy.

For a blocked command, Friday says and shows **Unlock your phone, then ask again**. Tap **Unlock phone** to request Android's unlock flow. Cancelling or failing that flow runs no command. After unlocking, repeat the blocked request. Permission setup also requires unlocking. Friday does not unlock the phone in response to the wake phrase.

A panel opened by a locked wake closes after the result or unlock reminder so wake listening can resume. The result close accounts for TTS completion and includes a timeout if the engine supplies no completion callback. A touch or a new command cancels the close. Pause and Stop retain their microphone off behavior, and a phone restart still requires the user to start listening again.

The lock-screen entry, metadata and wake service remain debug-only. The local action guard also protects existing Android entry points. This section describes the implementation, not device verification or production availability. Screen-off wake delivery, secure-lock behavior, clock-handler support and battery use require phone checks; see [testing](testing.md).

Run the lock-screen regression after starting the listener through **Start Friday**:

```bash
python3 .local/tools/locked-wake-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb
```

The checker keeps the phone locked, verifies microphone sample progress while the screen stays off, and injects four labeled previews. The call preview must show the normal target confirmation; the checker cancels it without tapping **Call**. Apps must show the unlock prompt without the requested app name. Timer and sound-mode previews must pass preparation. Each panel must close and permit another wake. The debug receiver accepts these previews only from a sender with Android's privileged `DUMP` permission, such as ADB shell. It cannot start the listener or run a phone action. Release builds exclude it.

The checker saves sanitized JSON, without screenshots or raw UI/window dumps. `--require-secure` also requires an existing secure keyguard; the script does not set a PIN, unlock the phone, or change credentials. These synthetic previews do not measure wake recognition or prove that a clock app accepted a timer. Use fresh spoken attempts for those checks.

## Implementation

The debug service captures mono 16 kHz PCM through `AudioRecord`, reuses a 320-sample input buffer and sends it to a small JNI bridge. The pinned native engine extracts audio features and runs TFLite Micro. It averages probabilities over the model's ten-frame window. The runtime first receives four seconds of generated silence, with warm-up detections excluded. The sphere and command UI do not receive these audio frames.

The worker owns the native handle from creation through cleanup. Stop interrupts capture; cleanup releases the recorder and native handle before another command can use the microphone. Generation-scoped state and a one-use wake request reject duplicate or stale launches. The selected `VoiceInteractionService` requests the existing assistant session; the wake service does not launch a background Activity directly. If that path is unavailable, the notification holds a one-use tap request. The screen polls state twice per second while visible and only redraws changed state. Elapsed time updates every five seconds. No ambient microphone audio or transcript enters storage or a backend request. After handoff, the existing recorder captures a fresh command for the existing backend flow.

The debug build saves the latest **20 completed runs**, with the selected cutoff, mode, counts, up to **64 detection offsets**, opened-panel count, capture time and compute time, in private `files/wake-trial/run-history.json`. It marks injected test wakes separately. `adb shell dumpsys activity service com.puneet.friday/.debug.wake.WakeTrialService` returns the current aggregate state while the service exists. The report contains no command text, contacts, audio or backend tokens. These metrics cannot label missed phrases or distinguish genuine speech from false triggers without known test attempts.

Native attribution and license text live in the debug-only `wake-trial-licenses.txt` asset. The runtime adds no Google Play Services, Android speech recognizer, Gemini SDK or AWS SDK dependency.

## Automated checks

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew assembleDebug assembleRelease test lintDebug \
  -PwakeTrialNativeDir=/tmp/friday-microwakeword-20261119/android-debug-jni
python3 scripts/check-release-manifest.py
python3 .local/tools/wake-trial-phone-check.py \
  --serial DEVICE_SERIAL --adb /path/to/adb --lifecycle
python3 .local/tools/wake-trial-layout-check.py \
  --serial DEVICE_SERIAL --adb /path/to/adb
python3 .local/tools/wake-command-phone-check.py \
  --serial DEVICE_SERIAL --adb /path/to/adb
```

The phone checker uses generated silent PCM by default. Its debug replay hook reads one fixed private PCM path and writes a run-tagged result file. It never opens the microphone for replay. It checks JNI argument rejection, an expected zero detections for silence, missing/corrupt-model rejection, foreground capture, Home, Stop, restart and assistant handoff. The `--lifecycle` option opens the microphone for the capture checks. It also checks short screen-off capture on a phone without a secure lock. Without `--lifecycle`, the checker only replays PCM. It restores the approved model and removes its test input and result files in cleanup. The layout checker tests a reachable 48dp Start control in portrait and landscape at 200% text, then restores font and rotation settings. It uses short, slow drags to avoid flinging past that control.

To compare a known recording, convert a copy to mono 16 kHz signed 16-bit little-endian PCM, then pass `--pcm /path/to/recording.pcm --expect-ms ...`. Recordings stay on the computer and connected phone. The checker does not upload them. The debug replay activity is absent from release builds.

The command checker injects labeled synthetic wakes into the opted-in service. Its silence case waits for the real command recorder to return no clear command. It supplies app-not-found and permission previews with the original wake handoff so the test does not pause the panel to inject a result. It checks wake rearming without taps, a second wake, retained manual and permission panels, retry cancellation and notification Stop. These checks do not measure live wake recognition or spoken-command accuracy.

Regression checks cover the microphone-service manifest prerequisites, release component/permission/library/model exclusions, model identity, rapid Start/Stop, interrupted microphone handoff and stale callbacks. The first lint run did not recognize the microphone prerequisite inherited from the main manifest; declaring it beside the debug service fixed that failure. A phone-check failure exposed ADB's missing-file output before a replay result existed. The checker now ignores missing, partial and stale results until the matching run completes. A notification check also exposed a stale UI dump after Android could not reach an idle state. The screen now skips unchanged redraws, and the checker deletes old dumps and requires a fresh hierarchy before tapping a control. The notification Stop test matches the system notification control rather than the app button beneath it.

## Limits and next candidates

This first runtime build targets ARM64. Other ABIs, API 26 execution, Android 15+ behavior, 16 KB-page devices, long locked-screen sessions, battery drain and multi-speaker wake accuracy need separate tests. A short microphone smoke test does not measure false wakes per hour.

Trial the other candidates one at a time after the owner reviews this one. PocketSphinx needs its external acoustic model. Vosk has a larger runtime and external model; the measured Sherpa artifact needs a smaller packaging approach before it meets Friday's release budget. Porcupine needs a separate AccessKey and licensing decision. None of those dependencies or models belongs to this first integration.

## Initial count-only validation on 19 November 2026

The native helper completed with `--android-ndk /home/puneet/Android/Sdk/ndk/27.1.12297006 --abi arm64-v8a --reuse-dependencies /tmp/friday-microwakeword-20261119/native-linux/_deps --debug-jni`. The JNI runtime takes **748,352 bytes**. The external model takes **60,832 bytes**. The installed debug APK takes **2,082,378 bytes**; the release APK takes **227,348 bytes**, below the 15,000,000-byte limit. Release runtime dependencies remain Kotlin stdlib and annotations.

Android build and verification used the cached Gradle 9.4.1 executable, with these arguments:

```text
--offline --no-daemon assembleDebug assembleRelease test lintDebug --console=plain -PwakeTrialNativeDir=/tmp/friday-microwakeword-20261119/android-debug-jni
--offline --no-daemon :app:dependencies --configuration releaseRuntimeClasspath --console=plain
```

The build passed **83 action**, **183 voice** and **34 wake-trial** checks. Lint reported **0 errors and 39 warnings**. This did not exercise the pinned 9.1.0 wrapper. The first sandboxed Gradle start could not load `libnative-platform.so`; verification completed after granting access to the host's cached build environment.

`FRIDAY_MICRO_NATIVE_LIBRARY=/tmp/friday-microwakeword-20261119/native-linux/libmicrowakeword.so python3 -m unittest discover -s scripts -p 'test_*.py'` passed **46 tests**, including the four compiled C-interface checks. `python3 scripts/check-release-manifest.py`, APK model/runtime exclusion checks and `git diff --check` passed.

On the connected Vivo V2168, API 34, the JNI replay of recording 3 returned **3.52, 18.22, 21.61 and 33.82 seconds**. Recording 4 returned **16.39 seconds**. These match the standalone evaluation. The final installed APK passed recording-4 replay, missing/corrupt-model rejection, real microphone start, Home, short screen-off capture, screen Stop, Stop through Vivo's grouped notification, repeated restart and returning to the normal assistant. These checks do not involve new live spoken wake phrases. The model's earlier misses and false wakes still apply.

The phone checker removed its temporary private PCM and replay results and restored the approved model. The test ended with the microphone off. Nothing was pushed or deployed.

`python3 .local/tools/wake-trial-layout-check.py --serial DEVICE_SERIAL --adb /path/to/adb` passed in portrait and landscape at 200% system text, with a reachable Start control of at least 48dp. Visual inspection confirmed the stopped screen and both large-text layouts. The checker restored the original font and rotation settings. Its first fast-swipe attempt flung past the Start control; short, slow drags fixed the checker without changing app layout.

## Owner's live observation on 19 November 2026

After the automated checks, the owner reported saying “Hey Friday” a few times and seeing the detection counter at **10**. The owner did not specify the number of attempts, starting count or cutoff. Record this as a manual observation; it does not establish an accuracy rate or distinguish correct detections from duplicate or false triggers.

The owner then reported testing all three cutoffs, finding that **0.10** detected more phrases, and said it was “working okay”. Keep **0.10** as a candidate for further comparison. The owner supplied no per-cutoff attempt counts, duplicate counts, false-trigger counts or confirmed noise-test duration. This feedback did not establish an accuracy rate. The owner then reported that **0.30** also worked and approved building the command trial at that cutoff.

A subsequent ADB read of the visible test screen showed **6 detections**, **52 seconds** of listening, the last detection at **48 seconds**, **Microphone off**, and selected sensitivity **0.10**. Reading the screen did not stop or reset the trial. The stopped screen lets the owner change sensitivity, so the selected value alone did not prove the cutoff for the completed run. A filtered `FridayWakeTrial` logcat read returned no entries. That first build kept the latest session in memory without run history. The command-trial build adds bounded metadata reports. These counters cannot identify missed phrases, duplicate detections or false triggers without labeled attempts.

For a measured comparison, note the cutoff, then Stop and Start to reset the counter. Say “Hey Friday” ten times, leaving at least three seconds between phrases. Note each missed phrase or multiple increments for one phrase. Then spend five minutes in normal conversation or background noise without saying the wake phrase, and record any count increase separately.

## Wake-to-command validation on 20 November 2026

The final debug APK installed on the Vivo V2168, API 34. Its size is **2,092,947 bytes**. The release APK is **227,376 bytes**, below 15,000,000 bytes. APK and merged-manifest checks exclude wake code, models, native runtime and microphone-service permissions from release. Neither APK contains a model or recording. The installed private model still matches its pinned SHA-256. No new dependency was added; the release runtime classpath contains Kotlin stdlib **2.2.10** and annotations **13.0**.

The cached Gradle **9.4.1** executable passed these arguments; the pinned **9.1.0** wrapper was not exercised:

```text
--offline --no-daemon assembleDebug assembleRelease test lintDebug :app:dependencies --configuration releaseRuntimeClasspath --console=plain -PwakeTrialNativeDir=/tmp/friday-microwakeword-20261119/android-debug-jni
```

The build passed **83 action**, **183 voice** and **70 wake-trial** checks, for **336 Kotlin checks**. Lint reported **0 errors and 39 warnings**. The first start needed host-cache access after the sandbox could not load `libnative-platform.so`. The native-enabled Python command documented above passed **49 tests**. `python3 scripts/check-release-manifest.py`, APK packaging checks and `git diff --check` passed.

`python3 .local/tools/wake-command-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb --output /tmp/friday-wake-command-check/final` passed on the final installed APK. It checked system-assistant opening, wake-recorder release before command capture, resuming the same run after dismissal, forced notification fallback, notification Stop while paused, no resume after Stop, bounded saved metadata and stale wake rejection. Its two wake events were **synthetic**, and the saved report marks them as such. This check validates integration, not live wake recognition or a completed spoken command. The owner still needs to try the complete spoken wake and command sequence.

The first fallback check tapped Vivo's grouped preview, which expanded the group without opening the command panel. The checker now expands the group before tapping notification content. A second check encountered the shade's closing animation after notification launch; it now waits and retries when SystemUI has no matching control. The Python suite covers both control-selection cases. The corrected final phone check passed. The older count-only check also exposed task navigation that left the trial Activity above MainActivity. The checker now uses an explicit clear-top launch and verifies the resumed component; the Python suite covers the navigation arguments and real activity-dump examples. A weak reference to the bound assistant service removed the two new static-context lint warnings without suppressing them.

The final count-only run passed `.local/tools/wake-trial-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb --pcm /tmp/friday-microwakeword-20261119/recording-4.pcm --expect-ms 16390 --lifecycle --output /tmp/friday-wake-command-check/count-only-verified`. It returned the expected **16.39-second** replay event and passed five JNI argument checks, missing/corrupt model rejection, live microphone start, Home, short screen-off capture, screen and notification Stop, restart and return to the verified foreground assistant.

`.local/tools/wake-trial-layout-check.py --serial DEVICE_SERIAL --adb /path/to/adb --output /tmp/friday-wake-command-check/layout` passed at **200% text** in portrait and landscape. Visual inspection confirmed a reachable Start control in both layouts. The checker restored font and rotation settings. A final service query found no wake service, and the private model hash still matched the approved model. The phone remains ready for the owner to tap Start; the tests left its wake microphone off.

Long listening sessions, battery drain, multi-speaker accuracy, physical secure-lock behavior and a seamless “Hey Friday, command” utterance remain unverified. Production wake listening remains disabled. No code was pushed or deployed.

## Error recovery on 20 November 2026

The owner reported six passing manual tests, then reported that an error panel needed a tap before another command. Before changing the install, the service dump showed the current 0.30 run in `PAUSED`, with three detections, three opened panels, no active command capture and one visible assistant. The supplied screenshot showed an app-not-found result for “Open inbox”. That screenshot does not prove the recorder heard silence.

The wake bridge now tracks which compact panel consumed a wake request. After an error, it schedules one close after three seconds. It checks the same active command-trial generation before closing; the existing microphone gate then rearms local detection after its 800 ms delay. It does not repeat the recording or upload ambient audio. A retry tap, new command, permission screen, success, lifecycle pause or Stop invalidates the pending close. Manually opened panels keep their existing behavior.

The phone checker needed corrections. Opening an error preview through a new Activity intent pauses that Activity, which invalidates its wake origin; synthetic error previews now travel with the original handoff. Waiting for a UI hierarchy during a silent recording can outlast capture and dismissal, so the checker reads its retry target from a stable manual error panel. It also finishes that temporary manual panel before the next synthetic trigger, rather than leaving another Activity in the task stack. Retry must retain the panel even if the retried recording has already ended in silence. Python regressions cover the intent route, stable retry target and completed retry capture. An expanded unrelated alert also pushed Friday below the notification viewport; the checker now scrolls an open shade before looking for Friday’s controls, and a Python check covers that navigation choice. It preserves a failure hierarchy before cleanup can replace it.


The final installed debug APK takes **2,095,546 bytes**, an increase of 2,599 bytes from the previous wake-to-command build. The release APK still takes **227,376 bytes**, below 15,000,000 bytes. No dependency or model was added. I reused the existing debug APK’s 748,352-byte ARM64 runtime because the earlier temporary native-build directory was no longer present. APK and manifest checks passed; release excludes the trial code and runtime, and both APKs exclude models and recordings. The release runtime classpath still contains only Kotlin stdlib 2.2.10 and annotations 13.0.

Commands run for final verification:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle --offline --no-daemon assembleDebug assembleRelease test lintDebug :app:dependencies --configuration releaseRuntimeClasspath --console=plain -PwakeTrialNativeDir=/tmp/friday-wake-error-recovery/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
python3 .local/tools/wake-command-phone-check.py --serial 192.168.1.15:42091 --adb /home/puneet/Android/Sdk/platform-tools/adb --output /tmp/friday-wake-error-recovery/phone-final-pass
git diff --check
```

The cached Gradle 9.4.1 build passed **83 action**, **183 voice** and **93 wake** checks, for **359 Kotlin checks**. Lint reported **0 errors and 38 warnings**. This did not exercise the pinned Gradle 9.1.0 wrapper. The Python suite ran **54 tests: 50 passed and four skipped**. Those four need the unavailable Linux native-evaluation library; the phone checks did exercise the installed Android runtime and model. The initial sandboxed Gradle start could not load its native library, and host ADB access needed the local socket outside the sandbox.

The final phone checker passed on the Vivo V2168, API 34. It verified real silent command capture, automatic recovery, another wake without a tap, an injected app-not-found result, retained manual and permission panels, retry starting a fresh recording and cancelling dismissal, notification fallback, Stop, no restart after Stop, bounded reports and stale-request rejection. Its six wake events were synthetic and the report marks them as such. I inspected the no-command and app-not-found screenshots; both show the error and “Returning to Hey Friday” without clipped controls. These checks do not establish live wake recognition accuracy, noise rejection or battery life. The tests left wake listening off. No backend deployment or push was needed.

## Files changed for this trial

- `app/src/debug/kotlin/com/puneet/friday/debug/wake/`: detector/model checks, session state, foreground capture, test screen, JNI replay, microphone gate/handoff, assistant bridge and bounded metadata reports.
- `app/src/debug/cpp/FridayWakeJni.cpp` and `app/src/debug/assets/wake-trial-licenses.txt`: JNI bridge and native notices.
- `app/src/debug/AndroidManifest.xml`, debug/release `DebugTools.kt` and `app/build.gradle.kts`: source-set isolation, service permissions, optional debug native path and wake-trial JVM checks.
- `MainActivity.kt`, `PermissionsActivity.kt`, `SettingsPanel.kt`, `entry/FridayVoiceInteractionService.kt`, `entry/FridayVoiceSession.kt` and `voice/VoiceRecorder.kt`: debug entry, disclosures, assistant-session bridge and microphone ownership hooks. Release hooks contain no trial implementation.
- `app/src/testDebug/kotlin/com/puneet/friday/debug/wake/WakeTrialLogicTests.kt`: 93 dependency-free checks, including error recovery and cancellation.
- `.local/tools/build-microwakeword-eval.py`, `install-wake-trial-model.py`, `wake-trial-phone-check.py`, `wake-command-phone-check.py`, `wake-trial-layout-check.py`, `wake_trial_checks.py`, `test_wake_trial.py` and `check-release-manifest.py`: reproducible build, private model installation, phone/layout tests and release guards.
- `README.md`, `docs/actions.md`, `docs/testing.md`, `docs/microwakeword-evaluation.md` and this guide: setup, behavior, results and remaining limits.

Earlier app-name matching and backend changes remain in the worktree from the preceding task. This trial did not deploy them.
