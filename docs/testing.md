# Friday testing

## Regression rule

Add a repeatable test when a build or feature failure has a deterministic reproduction. Keep hardware, OEM, permission-panel, launcher, and other device-only failures in the phone checklist with the exact reproduction steps. Do not mark a device case as automated coverage.

## Sign-in exchange regression, 21 November 2026

The owner selected a Google account on the connected Vivo V2168, then saw Friday's generic connection error. The production backend returned 200 for a temporary login challenge and 503 for an exchange with an invalid dummy ID token. That probe used no Google account, real token or Gemini request.

The Redis SDK decoded the saved login JSON into an object. `RedisAuthStore.get` requires the original string, so it rejected the result before Google token verification. The new test in `backend/test/account-tokens.test.ts` reproduces `invalid_auth_store_response` using the installed SDK with a mocked HTTP response. Setting `automaticDeserialization: false` fixes the test and preserves the original JSON bytes for Lua's atomic comparison. The test also checks missing records and compare-and-set arguments. Android now reports an unavailable sign-in service without attributing it to the phone's connection.

`npm run verify` passed all 102 backend tests, type checks, configuration checks and bundle limits. `npm run test:sam` passed both packaging tests. `python3 -m unittest discover -s scripts -p 'test_*.py'` passed all 39 Python tests. The release dependency audit, manifest and APK guards, credential scan, shell syntax and `git diff --check` passed.

The pinned Gradle 9.1.0 wrapper started a slow distribution download before it could run the offline build. That download was stopped. These commands used the existing cached Gradle 9.4.1 and passed both APK builds, all 510 Kotlin checks and debug lint with zero errors and 53 warnings:

```bash
friday_signin_gradle=/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle
"$friday_signin_gradle" --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m assembleDebug assembleRelease test lintDebug --console=plain -PwakeTrialNativeDir=/tmp/friday-package-native/android-debug-jni
"$friday_signin_gradle" --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m :app:dependencies --configuration releaseRuntimeClasspath --console=plain -PwakeTrialNativeDir=/tmp/friday-package-native/android-debug-jni > /tmp/friday-signin-fix-dependencies.log
python3 scripts/check-app-dependencies.py /tmp/friday-signin-fix-dependencies.log
python3 scripts/check-release-manifest.py
python3 scripts/check-secrets.py
bash -n scripts/verify.sh
git diff --check
```

Debug APK: **6,255,145 bytes**. Unsigned release APK: **847,252 bytes**, below the 15 MB limit. The updated debug APK installed on the Vivo V2168 with `adb install -r app/build/outputs/apk/debug/app-debug.apk`. The local account checker passed all 10 signed-out UI, callback rejection and microphone-gate checks on that build. Production still needs the backend fix deployed; a successful real account exchange remains unverified.

## SAM clean-build regression, 21 November 2026

After CI split the backend and AWS jobs, the AWS runner installed dependencies but had no generated `dist/handler.js`. The SAM Makefile only checked and copied that file, so the build failed at `test -f dist/handler.js`. The final Required checks job then reported `SAM: expected success, got failure`. That status check behaved as intended.

The Makefile now runs `npm run build` before checking and copying the handler. The AWS job runs `npm run test:sam` before `sam build`. Two tests execute the real Make target in temporary workspaces using the installed dependencies: one starts without `dist` and verifies the packaged JavaScript, and one injects invalid source alongside a stale bundle and confirms that compilation failure stops packaging. These tests belong to CI and remain public. They require Make, but no AWS credentials, API calls or downloads. Vercel verification does not run them.

Verification commands:

```bash
cd backend
npm run test:sam
npm run verify
cd ..
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-secrets.py
git diff --check
```

Both SAM packaging tests, all 101 backend tests and all 38 Python tool tests passed. The Python regressions cover the exact failed-SAM status and require it to pass before the final status succeeds. The workflow YAML and SAM step order passed inspection. SAM CLI remains unavailable locally, so this verifies the Make target used by SAM; GitHub still runs the complete `sam validate` and `sam build`. Android files and dependencies did not change.

## Local phone tools

The owner keeps phone automation, recording evaluations and experimental runtime builders in ignored `.local/tools/`. Commands below that use that path require the owner's local checkout; GitHub clones do not include those helpers. Git hooks, build guards and automated CI tests remain public. Existing versions of moved tools remain in Git history.

Run CI tool checks with `python3 -m unittest discover -s scripts -p 'test_*.py'`. On 21 November, all 37 passed after the separation. The local helper suite uses `python3 -m unittest discover -s .local/tools -p 'test_*.py'`: 64 passed and four desktop-native checks skipped because no native test library was configured. The Android packaging checks run in CI without importing a phone helper.

## Account and voice feedback verification, 21 November 2026

The account phase adds the approved Credential Manager/Google login dependencies, browser fallback, backend account sessions and sign-out. The sign-in screen uses the existing Geist font and graphite theme, with a Google account card, the official Google mark, a primary Continue with Google control, a browser alternative and a tap-to-open disclosure. The owner approved these login dependencies; unrelated Google services remain excluded.

Commands run:

```bash
friday_account_gradle=/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle
"$friday_account_gradle" --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m assembleDebug assembleRelease test lintDebug --console=plain -PwakeTrialNativeDir=/tmp/friday-package-native/android-debug-jni
"$friday_account_gradle" --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m :app:dependencies --configuration releaseRuntimeClasspath --console=plain > /tmp/friday-login-dependencies-current.log
python3 scripts/check-app-dependencies.py /tmp/friday-login-dependencies-current.log
python3 scripts/check-release-manifest.py
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s .local/tools -p 'test_*.py'
cd backend
npm run verify
node --test --test-isolation=none build-test/test/*.test.js scripts/*.test.mjs
```

Both APK builds passed, along with **510 Kotlin checks**: 101 action, 220 voice and 189 wake. Lint reported zero errors and 53 warnings. The release dependency allowlist and private-entry-point checks passed. ZIP checks confirmed that the release excludes native wake libraries, models and audio fixtures, and that the debug runtime has the expected JNI symbols. Debug APK: **6,255,145 bytes**. Unsigned release APK: **847,212 bytes**. These runs used cached Gradle 9.4.1; they do not verify installation of the wrapper's pinned 9.1.0 distribution.

Backend verification passed **101 individual tests**, including Google RSA/JWKS checks, nonce/audience rejection, replay/expiry, refresh and logout races, account quotas, anonymous-token rejection, Redis failures and browser PKCE. It produced a **112,986-byte Vercel bundle** and **756,108-byte Lambda bundle**. SAM CLI was unavailable locally; template validation/build remain CI checks. The AWS adapter still uses the previous install-token protocol and cannot serve the account-enabled APK.

The verified debug APK installed on the Vivo V2168 running Android 14. The local account checker passed **10 checks**: sign-in controls, account disclosure, malformed OAuth callbacks, cancellation, the signed-out command gate and no active recording. No test selected a Google account or granted sensitive permissions.

The speech preview checker passed **14 checks** for TTS start/completion, keeping full Home open, closing a compact result after speech, retaining a manual error panel, greeting playback and single completion, and preventing recording while signed out. Its saved event sequence confirms that the compact TTS callback precedes dismissal. These are labelled previews: they send no command and execute no phone action. They do not verify audible quality, spoken wake accuracy or the signed-in command loop.

The first preview attempts exposed two test-environment issues: the Vivo filtered debug-priority markers, and the helper requested `dumpsys window windows`, which omitted the current-focus field. Debug-only markers now use info priority; the helper queries the full window dump and waits for focus. Local regressions cover short/full component names and rejection of background activity records. A failed early dependency-report lookup also selected a prior failed build log; the fresh offline dependency report passed the allowlist.

Google ID 1.2.1 failed compilation because its Kotlin metadata required a newer compiler. The approved dependency now pins compatible 1.1.1, with a CI guard against restoring the incompatible version. No compiler-check bypass was added. Missing Google credentials now have an explicit browser sign-in message, and CI treats Credential Manager misuse as a fatal lint finding.

Real Google login remains unverified: production `/v1/auth/config` returned 404 before this phase's backend deployment. Deploy the backend, verify the OAuth client/signing identity, then test sign-in, a command, refresh and sign-out on the phone. Browser login also needs its Web-client secret and matching callback configuration. See [Google sign-in setup](google-sign-in.md). No code was committed, pushed or deployed during this verification.

## Repeatable build checks

Enable the local credential hooks with `git config core.hooksPath .githooks`. Before committing, run `python3 scripts/check-secrets.py --staged`; before pushing, run `python3 scripts/check-secrets.py --history HEAD`. The hooks enforce those checks for normal Git commands. The push hook scans each outgoing branch's history, so deleting a credential in a later commit does not hide it. The separate Credential check workflow scans history on pushes and pull requests. CI runs after publication; the local hooks provide the check before publication.

`python3 scripts/check-secrets.py` checks tracked working files and nonignored new files. It skips ignored local files such as `.env`. Checks print filenames, line numbers and categories, without matched values. They cover recognised provider key formats, literal credential configuration, private key files and private config paths. Examples may contain placeholders. A few existing mock values have exact hash/path exceptions; test files still undergo the scan. Pattern checks cannot identify every secret format. Do not bypass failures or print a detected value during review.

On 20 November 2026, the working files and committed history passed. `python3 -m unittest discover -s scripts -p 'test_*.py'` passed **82 tests**, with four optional native tests skipped. The 12 credential regressions cover provider patterns, private files, configuration lookups, dummy fixture boundaries, staged bytes that differ from the working copy, deleted credentials in history, identical blobs under private filenames, missing history, malformed push updates, and actual local commit/push rejection in temporary repositories. The first test run caught an environment lookup treated as a literal; the corrected classifier passed that regression. Hook shell syntax and the workflow's YAML parse passed. These changes add no app dependency or asset and do not change the APK.

Run from the repository root:

```bash
./scripts/verify.sh
```

The script runs the dependency-free Python tool tests with `python3 -m unittest discover -s scripts -p 'test_*.py'`, then `./gradlew assembleDebug assembleRelease test lintDebug --console=plain`, reports each release APK size, and fails if any exceeds 15,000,000 bytes. Extra Gradle arguments are forwarded, for example `./scripts/verify.sh --offline`. Release APKs are unsigned until local signing is configured.

`./gradlew test` invokes three JavaExec tasks. `:app:actionLogicTest` runs the pure Kotlin action checks in `app/src/test/kotlin/com/puneet/friday/actions/ActionLogicTests.kt`. `:app:voiceLogicTest` runs the audio and backend-contract checks in `app/src/test/kotlin/com/puneet/friday/voice/VoiceLogicTests.kt`. `:app:wakeTrialLogicTest` runs the debug wake-state and private-model installation checks in `app/src/testDebug/`. These tasks require no JUnit or additional dependency. A failed assertion exits with an error.

The action checks cover name normalization, exact and fuzzy matching, ambiguous candidates, weak-match rejection, bounded inputs, phone-number formatting and unsafe dial strings, alarm ranges, timer durations, and typed action validation. The voice checks cover canonical WAV headers, format rejection, upload size, silence trimming, speech detection, normalized audio levels, silence-stop behavior, every action response, malformed and extra arguments, backend error responses, `Server-Timing`, token refresh rules, HTTPS backend URL validation, entry-point auto-listen gates, and Quick Settings API boundaries.

The verification script also scans the Android source for banned device dependencies and APIs. This guard covers the earlier assistant-service regression that referenced `SpeechRecognizer`, which Friday must not use.

These checks do not execute Android permissions, microphone hardware, network requests, contacts providers, clock apps, calls, or ringer changes. Record phone results separately. Review `./gradlew :app:dependencies` after dependency changes. The approved Google-login exception has a dependency allowlist in `scripts/check-app-dependencies.py`; unrelated Google SDKs and AWS SDKs remain excluded.

### Local build environment regression

On 19 November 2026, a sandboxed Gradle run failed before project configuration with `Failed to load native library 'libnative-platform.so' for Linux amd64`. Repeating the same build with approved access to the native build tools passed. If this recurs, confirm that Gradle can load its native library before treating the result as an app compilation failure.

That verification used cached Gradle 9.4.1 with `--offline --no-daemon assembleDebug assembleRelease test lintDebug --console=plain`. The repository wrapper still pins 9.1.0; its local distribution download was incomplete. This run does not verify the pinned wrapper installation. Keep this case as an environment check rather than an automated app test.

During the 20 November model-setup check, one build process ended with status 143 during lint without a compiler diagnostic. Do not count that attempt as a successful verification. Repeat the unfinished check and record its final exit status. This is an environment reproduction, not an automated app test.

## Android package verification, 21 November 2026

The owner selected `com.puneet.friday`. The application ID, Kotlin namespace/source directories, assistant service XML, native JNI exports and phone-check scripts now use that package. A comparison against the previous source confirmed that the Android changes affect package references only. The renamed build installs as a separate app; permissions, assistant selection, settings and the private wake model need setup again.

Commands run with the cached Gradle installation:

```bash
friday_package_gradle=/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle
"$friday_package_gradle" --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m assembleRelease test lintDebug :app:dependencies --configuration releaseRuntimeClasspath --console=plain
python3 .local/tools/build-microwakeword-eval.py /tmp/friday-package-native --android-ndk /home/puneet/Android/Sdk/ndk/27.1.12297006 --abi arm64-v8a --reuse-dependencies /tmp/friday-package-native/cached-dependencies --debug-jni
"$friday_package_gradle" --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m assembleDebug --console=plain -PwakeTrialNativeDir=/tmp/friday-package-native/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
python3 scripts/check-secrets.py
git diff --check
```

The native build used the existing pinned dependencies, with archive hashes checked against the pinned CMake file. The build passed **489 Kotlin checks**: 101 action, 199 voice and 189 wake. Lint reported **0 errors and 40 warnings**. The Python suite passed **85 tests** and skipped four optional desktop-native tests. No app dependency, model or permission was added. Release runtime dependencies remain Kotlin stdlib and annotations.

The signed debug APK takes **2,004,175 bytes** and the unsigned release APK takes **229,524 bytes**. AAPT2 confirmed `com.puneet.friday` in both packaged manifests. Packaging checks confirmed model exclusion, private release entry points and the ARM64 debug runtime's three renamed JNI exports. The native runtime takes **748,128 bytes**. Debug signing still uses the existing certificate.

The new JNI packaging guard rejected the earlier debug APK's runtime, which exports methods for the previous package. Its regression covers a different package and a missing native method. A temporary signing check also exposed an outdated assumption about SDK tool output: SDK 37 labels the certificate `V2 Signer`, while older versions use `Signer #1`. The shared certificate check and regressions now accept both labels, reject public-key digests as certificate evidence and reject missing, wrong or additional signing certificates.

No phone was connected during this verification. Installation, assistant selection, permissions and wake behavior under the new app identity still need a phone check. Google login is not implemented; the Android OAuth client must use the new package and this build's certificate fingerprint. The existing Web client ID remains valid.

## Wake detector experiments

See [the detector comparison](wake-phrase-evaluation.md) for pinned models, commands and measured failures. No detector has passed the accuracy check. Release builds do not start an ambient microphone listener; the opt-in debug trial has a separate guide below. The Python tests cover evaluation inputs, counts and native-error cleanup with mocks. They need no model downloads and do not measure wake recognition. The PocketSphinx ARM64 native link regression, including the required `-llog` flag, has a separate reproduction in the comparison. Native CLI execution on the Vivo establishes model loading and WAV decoding, without foreground-service or battery coverage.

The [microWakeWord follow-up](microwakeword-evaluation.md) covers recordings 3 and 4, a pinned community Friday model, native runtime size and the Android file-decoding check. `.local/tools/build-microwakeword-eval.py` keeps native sources and artifacts outside the app. The Python suite skips four compiled-interface tests unless `FRIDAY_MICRO_NATIVE_LIBRARY` points to a desktop test library. CI does not download a wake model or compile the experimental runtime. The opt-in debug APK can include the runtime; Start Friday can download its pinned model after a user tap. Developer side-loading remains available. Release builds exclude both.

### First-phone Hey Friday setup

`WakeModelInstallerTests` runs with `:app:wakeTrialLogicTest`. It uses fake HTTP responses and temporary directories to check download progress, the installation callback, exact length and hash checks, redirects/HTTP errors, connection timeouts, interrupted transfers, bounded reads, cancellation before and during work, corrupt local files, retry and atomic replacement failure. Failures must leave the existing model intact and remove temporary files. These tests download no model and start no microphone.

On a connected ARM64 phone with the wake-enabled debug APK, stop Hey Friday and run:

```bash
python3 .local/tools/wake-model-setup-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb
```

The checker backs up the private model and wake-choice preferences, simulates first-phone setup, downloads and verifies the real pinned model, checks that download alone starts no microphone, declines the separate enable disclosure, and repeats setup with a corrupt model. It tests leaving during transfer and retry, then restores the original model and preferences with the listener off. It changes no credentials, Android permissions, text size or display settings. Screenshots and a report stay under `/tmp/friday-wake-model-setup-check`.

Check offline failure, a connection that remains at 0% for 25 seconds, and denied microphone/notification access on the new phone. Download must offer retry; denied listening permissions must leave the microphone off and keep Tap to speak available. A build without the native runtime must explain which APK is needed. The current runtime supports ARM64; do not treat testing on one phone as coverage of other chipsets or Android versions.

The setup follows Android's guidance for [background network work](https://developer.android.com/training/basics/network-ops/connecting), [private app storage](https://developer.android.com/training/data-storage/app-specific) and [permissions in context](https://developer.android.com/training/permissions/requesting). Internet access uses the manifest permission; private model storage needs no runtime storage prompt. Friday asks for microphone and notification access when the user enables Hey Friday after download.

### Setup verification, 20 November 2026

The final build used the cached Gradle distribution because the repository's pinned 9.1.0 wrapper download remains incomplete:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle \
  --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m \
  assembleDebug assembleRelease test lintDebug --console=plain \
  -PwakeTrialNativeDir=/tmp/friday-wake-error-recovery/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
python3 .local/tools/wake-model-setup-phone-check.py \
  --serial 192.168.1.15:42091 --adb /home/puneet/Android/Sdk/platform-tools/adb
git diff --check
```

The build passed **101 action, 183 voice and 189 wake checks**, for **473 Kotlin checks**. Lint reported **0 errors and 40 warnings**. The Python suite passed **68 tests** and skipped four optional desktop-native tests. APK guards confirmed that both builds exclude model/audio files and release excludes the debug wake runtime and code. No dependency or permission was added. The debug APK is **2,120,953 bytes**; unsigned release is **229,056 bytes**.

The Vivo V2168 on API 34 passed the phone setup check: first download with the pinned hash, download without microphone activation, separate disclosure cancellation, reuse after reopening, corruption detection, Back cancelling before activation and a successful retry. Installing the final APK update preserved the verified private model. The checker restored the prior model and wake-choice preferences, left listening off, and changed no Android permissions or display settings. Screenshots and its report remain under `/tmp/friday-wake-model-setup-check`.

An earlier attempt reached the 25-second retry message while HTTPS remained at 0%; later attempts completed through the CDN. The XML-tap failure in the first checker run now has a Python regression test, and the checker requires an unlocked phone before changing its fixtures. These results cover the connected Vivo; the owner's other phone was not connected. Permission denial on that phone, other ABIs and other Android versions remain unverified. This task did not measure wake recognition accuracy.

Changed setup files: `WakeUsageActivity.kt`, `WakeModelInstaller.kt`, the model URL in `WakeTrialSession.kt`, debug model attribution, `WakeModelInstallerTests.kt`, `WakeTrialLogicTests.kt`, the setup/usage phone checkers, `test_wake_trial.py`, README, the action contract and the wake setup/testing guides.

### Android 11 wake-notification regression

On 20 November 2026, the Realme RMX2050 running Android 11 had the current debug APK, the approved private model, microphone access and Friday selected as its assistant. Resuming Hey Friday crashed the app with `RemoteServiceException: Bad notification`. Android rejected the RemoteViews reflection call `ImageButton.setAlpha(float)` while inflating the foreground notification.

The notification now uses `setImageAlpha(int)`, which Android 11 marks as a remotable method. The Python suite guards against reintroducing the rejected float setter. To reproduce the device case, resume Hey Friday, open the notification, pause it, then resume from the background. Android must inflate both notification states without a crash; Pause must release capture before the stopped state, and Resume must acquire a new local capture session.

The first phone checker run encountered a live wake while testing notification controls. A visible Friday panel then occupied the microphone during Resume. The checker now returns Home before background Resume. Its legacy Activity-based synthetic preview also brought an existing setup task forward on Android 11. Recovery now uses the existing privileged debug broadcast receiver, with a Python regression check to keep that preview from launching an Activity.

Large-text, rotation and reduced-motion checks now require the explicit `--display-checks` flag. The owner asked to stop large-font checks; this regression omits that flag.

Verification commands:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle \
  --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m \
  assembleDebug assembleRelease test lintDebug --console=plain \
  -PwakeTrialNativeDir=/tmp/friday-wake-error-recovery/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
python3 .local/tools/wake-usage-phone-check.py \
  --serial adb-5e5f8889-85EmCi._adb-tls-connect._tcp \
  --adb /home/puneet/Android/Sdk/platform-tools/adb --controls-only \
  --output /tmp/friday-realme-wake-verified
git diff --check
```

The build passed **473 Kotlin checks**. Lint reported **0 errors and 40 warnings**. The Python suite passed **70 tests** and skipped four optional desktop-native checks. APK guards and the release-manifest check passed. Debug APK: **2,120,915 bytes**. Unsigned release: **229,056 bytes**. No dependency, permission or model asset was added; the release still excludes the debug wake runtime and components.

The final Realme phone run passed startup, microphone release on Pause, background Resume, repeated toggles, both notification themes, one labeled no-command wake and automatic recovery, then Tap-mode notification/listener removal. Help checks were skipped by `--controls-only`; large-text and rotation checks were not requested. The report remains at `/tmp/friday-realme-wake-verified/report.json`. The injected wake proves handoff and recovery, not recognition accuracy. Spoken commands, secure-lock behavior and long background battery use were not tested in this regression.

Changed files for this fix: `WakeUsageNotification.kt`, the two wake command/usage phone checkers, `test_wake_trial.py`, README and the wake testing/setup guides. The model file and default assistant selection remain in place on the Realme.

### Default-assistant setup regression

Friday previously requested `ROLE_ASSISTANT` through `RoleManager.createRequestRoleIntent`. Android's [role configuration](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml) marks that role as non-requestable. AOSP's request activity can close without displaying a chooser. Its Android 11 role configuration has the same restriction.

Friday now opens `ACTION_VOICE_INPUT_SETTINGS`, then tries `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` and `ACTION_SETTINGS` if Android rejects a route. Both Settings and Start Friday verify the selected voice service when the user returns. The unit checks simulate absent handlers, security restrictions, exhausted fallbacks and the explicit Default apps route. These checks add 16 assertions to the voice test runner.

On 20 November 2026, the connected Realme RMX2050 running Android 11 passed the new UI regression. The checker opened assistant settings from Friday, returned with Friday selected, selected None through Android's chooser, checked the unselected state and opened the Default apps fallback. In Start Friday, cancelling kept the wake service off and left the setup screen open without a settings loop. Selecting Friday again started local wake capture at **0.30**. The checker restored Friday as the default and left Hey Friday listening. It changed no font, rotation or display setting and downloaded no model.

Commands:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle \
  --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx768m \
  assembleDebug assembleRelease test lintDebug --console=plain \
  -PwakeTrialNativeDir=/tmp/friday-wake-error-recovery/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
python3 .local/tools/assistant-setup-phone-check.py \
  --serial adb-5e5f8889-85EmCi._adb-tls-connect._tcp \
  --adb /home/puneet/Android/Sdk/platform-tools/adb \
  --exercise-selection --resume-hey-friday \
  --output /tmp/friday-realme-assistant-setup
git diff --check
```

The build passed **489 Kotlin checks**: 101 action, 199 voice and 189 wake checks. The Python suite passed 70 tests and skipped four optional desktop-native tests. Lint reported **0 errors and 40 warnings**. The release-manifest and APK packaging checks passed. Debug APK: **2,121,558 bytes**. Unsigned release: **229,540 bytes**. No dependency, permission, model or image asset was added. The build log is `/tmp/friday-assistant-setup-build.log`; phone screenshots and `report.json` are in `/tmp/friday-realme-assistant-setup`.

The phone checker requires an unlocked phone with Friday selected. `--exercise-selection` stops wake listening and tests None → Friday through the system UI. It attempts to restore Friday if a later check fails. Use `--resume-hey-friday` to leave verified local capture running; otherwise the selection test ends in Tap to speak. Keep the phone untouched while it runs. Devices with different chooser labels may need a manual check.

These results cover the Realme's settings navigation and local listener startup. They do not verify other manufacturers, managed-device restrictions, power-button mappings, voice-command accuracy or long background use. Android requires the user to select the assistant; Friday cannot grant that role itself. Unsupported or restricted settings routes return feedback and retain manual entry points.

Changed files for this task: `entry/AssistantSettings.kt`, `SettingsPanel.kt`, `WakeUsageActivity.kt`, `entry/AssistantSettingsTests.kt`, `VoiceLogicTests.kt`, `assistant-setup-phone-check.py`, the existing UI checker label, README, the action contract and the wake setup/testing guides.

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
| App launch and Home | With Chrome installed, say “Open Google Chrome” and “Could you launch the Google Chrome app please”. Both should open Chrome. Try “Open Whats App” and “Open Phone Pe” with those apps installed. Ask for Chrome Beta while it is absent: Friday must not open stable Chrome. Unknown labels fail, and duplicate labels require a choice. Go to Home opens the launcher and reports “Went to home screen”. |
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
| Default assistant | Tap Settings → Default assistant and select Friday in Android's settings. Return and check for “Friday selected”. Cancel without changing the selection and check the fallback. Try the phone's assistant gesture from the launcher and another app; power-button behavior depends on the manufacturer. Restore the original assistant after the test if required. |
| Spoken confirmation | Complete a harmless action with an English TTS engine installed and confirm Friday speaks the short on-screen result once. Disable or remove the TTS engine on a suitable test device and confirm Friday keeps the on-screen result without crashing. |

Mark untested cases explicitly. Passing JVM checks or dispatching an Android intent does not prove the target app completed its work.

The local app-name regression covers brand names, spaces in spoken names, launch-verb wrappers and ambiguity. In the 19 November 2026 Vivo API 34 check, the previous debug APK rejected “Google Chrome” in Phone actions; the updated APK opened `com.android.chrome` for both “Google Chrome” and “Open the Google Chrome app please”. The absent Chrome Beta returned app-not-found feedback without opening stable Chrome. This tests local app resolution, not microphone recognition. The backend prompt changes need deployment before they affect spoken commands. After deployment, record the phrases above in your own voice.

Run `python3 .local/tools/app-name-phone-check.py --serial <adb-address> --adb <path-to-adb>` on the unlocked test phone with Chrome installed. The script opens Chrome through Friday's Phone actions screen and returns to Home. It checks for the expected app before reporting success.

## Voice UI regression checks

### 20 November 2026: start controls and compact spacing

Before the spacing changes, the Vivo API 34 notification run passed real microphone start, Pause releasing the service, Resume from the background, repeated toggles, both system notification themes, and removal of the listener and notifications after choosing Tap to speak. It used separate notification IDs for the active service and paused notification. The wake/error recovery check injected a labeled synthetic wake; it did not measure recognition accuracy. The command was:

```bash
python3 .local/tools/wake-usage-phone-check.py --serial 192.168.1.15:42091 --adb /home/puneet/Android/Sdk/platform-tools/adb --controls-only --output /tmp/friday-wake-usage/phone-separated-final
```

After applying [the UI guide](../design/ui-guide.md), the debug APK installed on the same phone. Normal-size hierarchy checks and screenshots verified Home, Settings, the Start Friday options, info help, and the compact permission panel. Checked controls retained at least 48 dp targets. The Ready notification row decreased from 203 to 184 pixels at the phone's unchanged density. The Start Friday screen and action editor each had one visible Back button; the editor returned to the action list, then to Settings. Screenshots remain under `/tmp/friday-spacing-review`.

The owner asked to stop large-font tests. Do not repeat them for this spacing pass. The temporary phone checker stopped before completing its system-theme pass after configuration changes; this pass does not establish a full UI regression result. A partially visible target in a scroll view must be scrolled into view before measuring it. Wait for rotation to settle before sending coordinate-based gestures.

Build and checks for the spacing changes:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle --offline --no-daemon assembleDebug assembleRelease test lintDebug --console=plain -PwakeTrialNativeDir=/tmp/friday-wake-error-recovery/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
git diff --check
/home/puneet/Android/Sdk/platform-tools/adb -s 192.168.1.15:42091 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
```

The build passed 83 action, 183 voice and 138 debug wake checks. Python passed 59 tests and skipped four compiled native-runtime tests. Lint reported zero errors and 39 warnings. Release manifest checks passed; the source guard found no forbidden Google service or AWS SDK dependencies/APIs. The unsigned release APK is **227,464 bytes**; debug is **2,109,306 bytes**. The release APK contains no native runtime or model. No dependency changed. This used cached Gradle 9.4.1; it did not verify the pinned 9.1.0 wrapper installation. Voice recognition, live commands and OEM widget rendering were not retested for these visual changes.

### 20 November 2026: locked wake and action policy

The initial lock policy allowed timers and silent/vibrate/normal modes while locked. The follow-up now allows calls too; calls still require Friday's explicit **Start a phone call?** confirmation. App launches, alarms and Home navigation require unlocking. The app checks before protected app-name lookup and before Android effects. The Unlock phone button requests Android's unlock flow; the user repeats a blocked command after unlocking.

The phone checks exposed a screen-wake lifecycle problem: Android could pause the assistant before its window gained focus. Friday now keeps the pending wake token through that transition and waits for a resumed, focused window before accepting it or starting command capture. Debug previews follow the same gate and do not open the command recorder. An existing singleTop assistant window also restores its lock-screen flags before consuming a new intent.

Build and offline checks:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1024m assembleDebug assembleRelease test lintDebug --console=plain -PwakeTrialNativeDir=/tmp/friday-wake-error-recovery/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
git diff --check
```

The final build passed **101 action, 183 voice and 138 wake checks**. Python passed **67 tests** and skipped four compiled native-runtime checks. Lint reported zero errors and 40 warnings. The release manifest and APK guards passed; the source guard found no forbidden Google device APIs or AWS SDK imports. The unsigned release APK is **229,176 bytes**; debug is **2,111,968 bytes**. No dependency changed. This used cached Gradle 9.4.1 rather than the pinned 9.1.0 wrapper. Two intermediate full build processes ended with exit 143; the final single-worker build completed.

The final debug APK installed on the Vivo V2168, Android 14/API 34. The locked preview suite passed on the phone's showing but unsecured keyguard. PIN, password and biometric keyguard checks remain unverified. The earlier Activity-based trigger could not run behind keyguard; the checker now uses a debug-only receiver protected by `android.permission.DUMP`.

```bash
python3 .local/tools/locked-wake-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb
```

This checker tests four synthetic outcomes, hidden app names, keyguard preservation and no-tap listener recovery. The call case displays the final confirmation, then the test cancels it. Timer and sound-mode cases stop after preparation and show a Preview label; they do not change the ringer or send a clock request. It saves sanitized JSON in `/tmp/friday-locked-wake-check/report.json`. Real spoken screen-off wakes, timer delivery, sound changes, biometric unlock and cancellation, long sleep and battery use still need phone checks. This pass does not run font-size or theme tests.

The follow-up calling build installed on the same Vivo phone. The call preview showed **Start a phone call?** with the sample target above a showing keyguard, accepted Back to cancel, and returned the wake listener to listening. App, timer and sound-mode previews also passed. Microphone sample counters advanced while the screen stayed off before each preview. The phone reported an unsecured keyguard, so this run does not verify PIN or biometric behavior. The test used synthetic wakes and placed no calls. The call preview replaces the action closure with a preview result, so its Call button cannot dial. After the checks, Friday returned to the owner's original **Tap to speak** setting and stopped the wake service.

Commands for this follow-up:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1024m assembleDebug assembleRelease test lintDebug --console=plain -PwakeTrialNativeDir=/tmp/friday-wake-error-recovery/android-debug-jni
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check-release-manifest.py
/home/puneet/Android/Sdk/platform-tools/adb -s 192.168.1.15:42091 install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
python3 .local/tools/locked-wake-phone-check.py --serial 192.168.1.15:42091 --adb /home/puneet/Android/Sdk/platform-tools/adb --output /tmp/friday-locked-call-check
git diff --check
```

The build passed 101 action, 183 voice and 138 wake checks. Python passed 67 tests and skipped four compiled native-runtime checks. Lint reported zero errors and 40 warnings. The release guards passed, and the source scan found no forbidden Google device APIs or AWS SDK imports. The unsigned release APK is **229,056 bytes**; debug is **2,111,985 bytes**. No dependency changed. Build and offline test logs remain in `/tmp/friday-call-verify-build.log` and `/tmp/friday-call-verify-python.log`; the sanitized phone report is `/tmp/friday-locked-call-check/report.json`.

Run `python3 .local/tools/wake-usage-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb` to check **Settings → Start Friday**, exclusive radio selection, tap info popups, no microphone from selection or help, disclosure cancellation, notification Pause/Resume, repeated toggles and switching to Tap to speak. It uses the verified private model and an opt-in debug runtime. It returns Home before testing background Resume, including when a live wake has opened Friday behind the shade. Recovery uses the privileged debug broadcast receiver so the test cannot foreground an existing setup task. The script sends no deliberate cloud command, restores the system theme and ends with Tap to speak selected. Large-text, rotation and reduced-motion checks require the separate `--display-checks` flag; the owner asked to stop large-font tests, so omit it without a new request. The default run changes none of those settings. The script saves screenshots and a JSON report under `/tmp/friday-wake-usage-check` by default. Keep the phone untouched during the run. Notification tests use real user-interface taps, rather than an ADB start-service command.

Also check microphone and notification permission denial, blocked notification channels, an unavailable model/runtime, the default-assistant chooser, and Pause while a wake command panel is open. After restarting the phone, confirm that Friday requires a user action to restart wake listening. A saved selection alone must not reopen the microphone.

The opt-in debug wake test has a separate [setup and test guide](wake-detector-trial.md). Command mode defaults to **0.30** and opens the existing assistant after a wake; count-only mode retains the detector comparison. Check missed phrases and false detections at each cutoff. Test the notification's Stop action, Home, screen lock, permission denial, blocked notifications and rapid Start/Stop. Command mode must release the wake microphone before command capture, pause while the assistant or permissions are open, and resume after dismissal. Wake Friday and stay silent: after the no-clear-command message, the panel must close after three seconds and accept another wake without a tap. Repeat with an app-not-found error. Tapping retry must cancel the close. A manually opened error panel and a permission prompt must remain open. Stop and phone restart must leave its microphone off. The release packaging check rejects its runtime, model, components and foreground-service permissions.

Run `python3 .local/tools/wake-command-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb` for system-session opening, microphone handoff, dismissal resume, no-tap recovery after injected no-command and app-not-found results, a second wake, manual and permission panel retention, retry cancellation, the forced notification fallback, Stop while paused, saved metadata and stale-request rejection. This test injects labeled synthetic wake events and error outcomes into an opted-in listener. It does not measure recognition or submit a deliberate phone command. The debug report marks synthetic detections separately. Use fresh spoken attempts for recognition tests.

Install a debug APK on an unlocked phone, then run:

```bash
python3 .local/tools/ui-phone-check.py --serial DEVICE_SERIAL --adb /path/to/adb
```

The script checks the launcher, icon bottom navigation with selected tabs, compact assistant panel, processing cancellation, unsupported and error messages, permission recovery, interpreted action, panel dismissal, microphone lifecycle, 150% text, landscape, and both system themes. It uses debug preview states without executing phone commands. It restores text size, rotation, and system theme in a `finally` block. Screenshots, the latest hierarchy, and the report go to `/tmp/friday-ui-check` by default. Keep the phone untouched during the run.

The release manifest check in `scripts/verify.sh` keeps the assistant panel and permission screens private. Debug builds expose the panel for ADB visual tests; release builds do not. Phone regression checks assert that tab switches retain the same Activity instance, Settings scroll, and the latest Home result. Reselecting a tab does not launch a page, and Android Back returns to Home. JVM tests cover tab reselection and TTS cancellation, replacement and duplicate callbacks, timer and alarm formatting, and honest Home-navigation copy.

Manual playback checks: run a successful command, confirm the spoken result matches the visible result, stop speech with the square control, replay it, and start a new recording. Check that an old utterance cannot resume after leaving the app. Engines that do not supply PCM callbacks show the speaking state with a frozen orb. No English TTS engine should leave the result readable and hide replay.
