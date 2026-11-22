# Kotlin, Rust and C++ audio processing

Measured on 22 November 2026, on the connected Vivo V2168 running Android 14/API 34 and ARM64.

## Decision

Keep Kotlin for Friday's command recording and audio preparation. Keep the existing C++ microWakeWord engine for the debug wake detector. The owner chose to keep the TypeScript backend on Vercel.

Rust produced the fastest result in this experiment. For a three-second clip, it saved about 1.26 ms beyond the optimized Kotlin version. I chose the Kotlin change because it removes about 13.41 ms of unused work from that clip without adding a native library or build toolchain to the app.

## Method and correctness

I compiled the repository's `PcmProcessor` and `WavEncoder` with Kotlin 2.2.10 and tested them in a separate Android ART process through ADB. I compiled equivalent Rust and C++ implementations for Android API 26, then called them from the same Kotlin benchmark through JNI. The measurement includes input copies, output allocation and returning results across JNI.

The benchmark used generated 16 kHz mono PCM buffers, with silence, voice-like waves, short inputs and integer boundary cases. It captured no microphone audio and sent no network requests. These synthetic buffers test numerical equivalence and processing speed; they do not establish speech-recognition accuracy.

Two fresh ART processes ran the full comparison using R8 release optimization. Each scenario warmed each implementation for at least 400 ms, then collected 120 timing batches per implementation in shuffled order. A batch repeated short operations to reduce clock overhead. The table reports the mean of the two run medians and the range of their P95 values. These are batch-average operation times, not the P95 of individual live commands.

Each full run passed **275 output comparisons**. The native versions matched Kotlin's floating-point levels within 0.000002 and produced the same acceptance decisions and WAV bytes. An earlier D8 comparison of Kotlin and C++ gave similar results. Android reported thermal status 0; its thermal HAL supplied no temperature readings. The phone was charging, so these results do not measure battery use.

## Three-second recording preparation

This operation checks the captured clip, removes DC offset, applies bounded gain and writes the WAV header and PCM bytes. Recording and silence detection happen before this operation.

| Implementation | Median | P95 across the two runs |
| --- | ---: | ---: |
| Current Kotlin | 15.284 ms | 15.873–15.878 ms |
| Current algorithm in C++ through JNI | 10.467 ms | 10.594–10.602 ms |
| Current algorithm in Rust through JNI | 10.158 ms | 10.282–10.314 ms |
| Optimized Kotlin | 1.878 ms | 1.987–1.999 ms |
| Optimized C++ through JNI | 0.788 ms | 0.852–0.866 ms |
| Optimized Rust through JNI | 0.623 ms | 0.659–0.692 ms |

`PcmProcessor.hasSpeech` used to compute pitch autocorrelation across the complete recording, then read only its amplitude. The optimized versions skip that unused correlation. `SpeechEndDetector` still checks voicing on the captured 20 ms frames, with the existing 0.20 threshold and noise gates. The app now uses this measured Kotlin optimization.

For generated ten-second buffers, preparation took about 50.96 ms in current Kotlin, 6.22 ms in optimized Kotlin, 2.63 ms in optimized C++ and 2.07 ms in optimized Rust. That buffer length tests the processing limit; the live recorder can reject a command that reaches its maximum duration without a speech end.

## Twenty-millisecond frame analysis

This operation calculates volume, display levels and pitch correlation for one 320-sample frame.

| Implementation | Median | P95 across the two runs |
| --- | ---: | ---: |
| Kotlin | 0.04144 ms | 0.04283–0.04302 ms |
| C++ through JNI | 0.03039 ms | 0.03152–0.03169 ms |
| Rust through JNI | 0.02994 ms | 0.03095–0.03096 ms |

The native versions save about 0.011 ms per frame. These results support keeping the current Kotlin frame processor. Friday's wake inference already runs through the native C++ engine; wrapping that engine in Rust would not change its inference algorithm.

## Size and build cost

| Prototype | Stripped ARM64 shared library |
| --- | ---: |
| C++ plus JNI bridge | 222,024 bytes |
| Rust plus JNI bridge | 506,408 bytes |

These are standalone prototype sizes, not measured APK increases. Both libraries have 16 KB ELF load-segment alignment and depend on Android's `libc`, `libm` and `libdl`. Neither prototype enters Friday's APK. Other CPU architectures and 16 KB-page devices remain untested.

The experiment used NDK 27.1.12297006 with `-O3` and disabled floating-point contraction, and upstream Rust 1.98.1 with optimization level 3 and abort-on-panic. Fedora's Rust compiler rejected the upstream Android standard library despite the matching version number; I downloaded matching compiler and target archives, verified their published SHA-256 hashes and extracted them under `/tmp`. No app or backend dependency was added.

The benchmark source, ADB helper and experimental builders stay in ignored `.local/tools/audio-language-bench/`. Reports and binaries stay under `/tmp/friday-rust-measure/`. CI does not depend on these files.

## App verification

I built the Kotlin change with the cached Gradle 9.4.1 distribution, preserving the existing wake runtime through `-PwakeTrialNativeDir=/tmp/friday-rust-measure/wake-jni`. `assembleDebug`, `assembleRelease`, `test` and `lintDebug` passed. The test tasks ran **101 action, 221 voice and 189 wake checks**, for 511 checks. Debug lint reported zero errors and 52 warnings. This did not verify the wrapper's pinned Gradle 9.1.0 download.

The debug APK is **7,016,765 bytes**, the same size as before this change. The unsigned release APK is **847,608 bytes**, an increase of 12 bytes, below the 15,000,000-byte limit. The release dependency audit allows the approved Google login libraries and excludes Firebase, ML Kit and AWS SDKs. The release manifest guard passed. ZIP checks confirmed that neither APK contains a model, recording or either native audio prototype, and that the debug wake library matches the previous build's hash.

`adb install -r` installed the updated wake-enabled debug APK on the Vivo. The installed model still matches its pinned hash, and the visible Start Friday setup started the local wake listener. At **22 November 2026, 2:06 PM IST**, the owner confirmed that Friday works well and reported an improvement after this change. I saved that confirmation with the measurements. It describes the owner's live experience; this experiment still does not establish a phone-to-action latency median.

Additional checks: `python3 scripts/check-app-dependencies.py /tmp/friday-rust-measure/release-dependencies.txt`, `python3 scripts/check-release-manifest.py`, local Markdown links and `git diff --check` passed. The credential scan passed before documentation; run it again before publishing.

## Limits and next measurement

This experiment measures local computation after buffers become available. It excludes Android microphone startup, actual hardware capture delay, greeting playback, the recorder's 840 ms silence window, upload, authentication, Redis, Gemini and Android actions. It does not measure missing recognizer engines, noisy-room accuracy, false wakes or long-running energy use.

C++ and Rust can avoid managed allocation for work that stays inside native code. Our JNI prototypes still allocate Kotlin return arrays, and native allocation and operating-system scheduling can still introduce delays. Choosing AAudio or Oboe also requires a capture benchmark on the intended phones; using a native language does not establish lower capture latency. Oboe would require separate dependency approval and an APK size measurement.

Next, measure Friday's full speech-end-to-action path on live phone commands. Keep the current provider and audio format while establishing that baseline. See [model and upload measurements](model-notes.md).

## Commands

The isolated experiment used:

```bash
python3 .local/tools/audio-language-bench/build.py
python3 .local/tools/audio-language-bench/run_phone.py --serial "$friday_device_serial" --round 1
python3 .local/tools/audio-language-bench/run_phone.py --serial "$friday_device_serial" --round 2
python3 .local/tools/audio-language-bench/run_phone.py --serial "$friday_device_serial" --round 0 --cpp-only
python3 .local/tools/audio-language-bench/run_phone.py --serial "$friday_device_serial" --round 0 --d8 --cpp-only
```

The build helper requires the verified Rust toolchain under `/tmp/friday-rust-measure/toolchain` and the existing cached Kotlin compiler and Android SDK. It does not install packages. Treat this as a local experiment; the ignored helper is not a CI build command.

The app verification used:

```bash
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle --offline --no-daemon -PwakeTrialNativeDir=/tmp/friday-rust-measure/wake-jni assembleDebug assembleRelease test lintDebug --console=plain
/home/puneet/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/bin/gradle --offline --no-daemon :app:dependencies --configuration releaseRuntimeClasspath --console=plain
```
