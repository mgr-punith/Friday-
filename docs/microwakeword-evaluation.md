# microWakeWord: recordings 3 and 4

The tested community “Hey Friday” model needs better recognition before Friday can use it for commands. Its default settings detected 6 of 25 split clips and triggered on 4 of 56 negative clips. The native runtime fits the size budget for an ARM64 experiment. The standalone evaluation below added no app dependency or background microphone service. The owner then approved a [debug-only phone trial](wake-detector-trial.md), with an external model and detections only. Release builds still use tap shortcuts.

## Recordings and results

The owner confirmed that recording 3 contains repeated “Hey Friday” phrases and recording 4 contains “Hey Friday” followed by commands. I copied only those two files from the connected Vivo. The originals remain on the phone. Test copies and the segmentation manifest live under gitignored `docs/private-audio/wake-recordings/`.

Both originals use stereo 48 kHz AAC. FFmpeg converted them to mono 16 kHz 16-bit PCM, without volume normalization. Recording 3 contains 55.104 seconds of decoded audio; recording 4 contains 44.373 seconds. Neither converted recording has samples at the PCM clipping limit.

Silence detection at `-35 dB`, with a minimum pause of 450 ms, produced 17 candidate phrase clips from recording 3 and 8 from recording 4. The clips retain 300 ms before speech and 500 ms after it, bounded by the original recording. Regions shorter than 250 ms were excluded. These are automatic boundaries, not human-reviewed word timestamps. They can miss quiet speech or split an utterance. Treat the counts below as an initial comparison on these candidate clips.

At the model's default cutoff, it detected 5 of 17 isolated clips and 1 of 8 wake-plus-command clips.

| Model probability cutoff | Detected positive clips / 25 | Negative clips that triggered / 56 |
|---|---:|---:|
| `0.66`, model default | 6 | 4 |
| `0.30` | 13 | 8 |
| `0.10` | 19 | 10 |

The negative set contains the previous 32 generated near phrases and 24 private command/noise recordings. It lasts 135.8 seconds. Lowering the cutoff increases detections and false wakes. I chose these follow-up cutoffs after the default run, so they do not provide independent validation. The default model also detected 14 of 24 generated positive clips from the earlier comparison.

I ran the complete, unsplit recordings through the native CLI on the computer and Vivo V2168, Android 14/API 34. Both platforms returned the same event timestamps:

| Full recording | Detection times from the recording start |
|---|---|
| 3 | 3.52, 18.22, 21.61 and 33.82 seconds |
| 4 | 16.39 seconds |

An independent desktop pass using `pymicro-features==2.0.2` and `ai-edge-litert==2.3.0`, with the same quantization, warm-up and detection window, returned those same timestamps. Those packages live in a temporary test directory. They add no app or backend dependency. The agreement across both inference paths supports treating this as a model-recognition problem rather than a native bridge failure.

The full-file test preserves adaptation and cooldown across phrases; it differs from scoring independent clips. The test executable reads prerecorded PCM and never opens a microphone. It does not prove foreground-service, locked-screen, audio handoff or battery behavior. I removed its temporary phone directory after testing.

## Model and runtime

The official ESPHome model listing checked in this evaluation has no Friday model. This test uses the community model from [JohnnyPrimus/Custom_V2_MicroWakeWords](https://github.com/JohnnyPrimus/Custom_V2_MicroWakeWords/tree/2987c5ae4e4c8e7ac277bdede0904d99b4ed477a/models/hey_friday), at revision `2987c5ae4e4c8e7ac277bdede0904d99b4ed477a`. Its metadata names NathanCu as the author. The repository contains an Apache 2.0 license. This work does not establish the model's training-data coverage or accuracy for other speakers.

- Model file: **60,832 bytes**.
- Model SHA-256: `04aaffa5f111704db18ec593251a3dfbb009638009e081c9c87830755c3563a6`.
- Metadata SHA-256: `5325d686c865a5de29cdd40880836a542dec86bcf2afc873d072027867e88d74`.
- Declared settings: cutoff `0.66`, window `10`, feature step `10 ms`, tensor arena `25,544 bytes`.

The test runtime builds [Home Assistant's native microWakeWord engine](https://github.com/home-assistant/android/tree/1feed57767ae9a433f3357eab332573848b11844/microwakeword) at revision `1feed57767ae9a433f3357eab332573848b11844`. The build helper checks hashes of the downloaded engine and build files. That pinned CMake configuration pins and hashes TFLite Micro, KissFFT, FlatBuffers, gemmlowp and ruy source archives. Friday's test bridge replaces the JNI entry point with a small C interface; it preserves the upstream engine and feature extractor.

The engine extracts 40 audio features, runs the quantized streaming model and averages probabilities over the declared window. It ignores the first 100 inference slices and applies the same cooldown after a detection. The evaluator feeds four seconds of silent PCM before each independent clip to warm that state. It excludes warm-up events from scoring. Its CPU ratio includes decoder setup and warm-up, so that ratio does not estimate phone battery use.

The stripped API 26 ARM64 shared library takes **747,120 bytes**, with the C++ runtime linked statically. The standalone PCM executable takes **270,312 bytes** and would not belong in an APK. `llvm-readelf` reports `0x4000` alignment for the library's load segments. Execution was tested on API 34, not API 26 or a 16 KB-page device.

These are standalone artifact measurements, not an APK size comparison. This evaluation did not measure other CPU architectures, JNI integration or APK packaging. The later debug trial measures its JNI library and APK separately and side-loads the approved model into app-private storage. It does not bundle or auto-download the model.

## Repeat the tests

Build the desktop test runtime outside the repository. This needs Python 3, CMake, a C/C++ compiler, an internet connection for the pinned sources, and no app or backend dependencies:

```bash
python3 .local/tools/build-microwakeword-eval.py "$wake_dir"
```

Download the pinned model and metadata into that same test directory:

```bash
curl -fsSL 'https://raw.githubusercontent.com/JohnnyPrimus/Custom_V2_MicroWakeWords/2987c5ae4e4c8e7ac277bdede0904d99b4ed477a/models/hey_friday/hey_friday.json' \
  -o "$wake_dir/hey_friday.json"
curl -fsSL 'https://raw.githubusercontent.com/JohnnyPrimus/Custom_V2_MicroWakeWords/2987c5ae4e4c8e7ac277bdede0904d99b4ed477a/models/hey_friday/hey_friday.tflite' \
  -o "$wake_dir/hey_friday.tflite"

python3 .local/tools/wake-phrase-eval.py microwakeword "$wake_dir" \
  --native-library "$wake_dir/native-linux/libmicrowakeword.so" \
  --micro-config "$wake_dir/hey_friday.json" \
  --model-sha256 04aaffa5f111704db18ec593251a3dfbb009638009e081c9c87830755c3563a6 \
  --positive docs/private-audio/wake-recordings/isolated \
  --negative "$synthetic_dir/negative" --negative docs/private-audio
```

Repeat with `--positive docs/private-audio/wake-recordings/with-command` for recording 4. Use both positive directories and `--micro-cutoff 0.30` or `--micro-cutoff 0.10` for the follow-up comparisons. The evaluator checks the model hash, phrase, local file path and declared bounds before loading native code. It does not download models or send recordings over a network.

The private segmentation manifest stores source times for the candidate clips. To prepare new recordings, retain the original file, convert a copy with `ffmpeg -i INPUT -ar 16000 -ac 1 -c:a pcm_s16le OUTPUT.wav`, then inspect `silencedetect=noise=-35dB:d=0.45` boundaries before scoring. Do not treat speech-to-text hypotheses as human labels. The owner's recordings have not been used for training.

For ARM64, use the installed NDK and reuse the desktop build's source downloads:

```bash
python3 .local/tools/build-microwakeword-eval.py "$wake_dir" \
  --android-ndk "$ANDROID_HOME/ndk/27.1.12297006" --abi arm64-v8a \
  --reuse-dependencies "$wake_dir/native-linux/_deps"

"$ANDROID_HOME/ndk/27.1.12297006/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  --strip-unneeded "$wake_dir/native-arm64-v8a/libmicrowakeword.so" \
  "$wake_dir/native-arm64-v8a/friday_wake_eval"
```

The first experimental Android build used the directory name `native-arm64`; the reusable helper names it `native-arm64-v8a`. Both use the same source and compiler settings. To run on a phone, convert a WAV to raw `s16le` PCM, copy the executable, library, model and PCM into a temporary test directory, and set `LD_LIBRARY_PATH` to that directory. The CLI accepts `MODEL PCM CUTOFF WINDOW FEATURE_STEP_MS`. Remove the temporary phone copies after testing; retain the owner's originals.

## Regression checks

Run the portable tests with `python3 -m unittest discover -s scripts -p 'test_*.py'`. To include the four compiled-interface checks:

```bash
FRIDAY_MICRO_NATIVE_LIBRARY="$wake_dir/native-linux/libmicrowakeword.so" \
  python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

All 37 tests passed in this evaluation, including the four native checks. Without that environment variable, those four tests skip; the other 33 need no downloaded runtime or model. Checks cover mismatched wake phrases, changed model/source hashes, unsafe paths, oversized or corrupt models, invalid thresholds and settings, partial audio frames, warm-up scoring, and native cleanup.

The first GCC 16 desktop build failed on private placement-delete operators in TFLite Micro. `-fno-exceptions` fixed the build; the helper includes this flag, and a regression test checks it. A later build requested the new CLI target before CMake had regenerated its target list and failed with `No rule to make target`. The helper now configures before building, which the same regression test checks. These failures concern the experimental native build, not Friday's Android app.

The Android regression build also passed with cached Gradle 9.4.1 using `--offline --no-daemon assembleDebug assembleRelease test lintDebug --console=plain`: 83 action checks, 183 voice checks, zero lint errors and 36 existing warnings. The release APK remains 227,316 bytes. The pinned 9.1.0 wrapper installation was not exercised. Backend `npm run verify` passed its 78 tests, type check, Vercel checks and bundle builds. `python3 scripts/check-release-manifest.py` and `git diff --check` passed. Nothing was deployed or pushed.

## Next decision

Keep microWakeWord as a runtime candidate and replace or improve this model before release integration. The owner rejected the large training setup and chose one-at-a-time debug detector trials. Validate candidates against human-reviewed Indian English wake recordings and longer negative recordings from speakers outside the training set. Include near phrases and normal conversations. Passing this short owner's test alone would still leave multi-speaker accuracy, hours of false-wake testing and battery testing outstanding.
