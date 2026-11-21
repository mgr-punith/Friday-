# “Hey Friday” detector evaluation

Keep the tap shortcuts for now. None of the three detectors passed this check. Sherpa missed nine of 24 generated wake clips, and Vosk woke on 16 of 32 phrases that did not contain “Hey Friday”. PocketSphinx missed 13 of 24 wake clips at a threshold with no false wakes in this set. This branch does not start a background listener.

The [microWakeWord follow-up](microwakeword-evaluation.md) adds a fourth candidate and tests the owner's new recordings 3 and 4. Its community Friday model also failed the initial recognition check. The follow-up includes a small ARM64 runtime measurement, phone execution results and reproducible build tools.

| Clips | Count | Sherpa-ONNX 2.0.2 | Vosk 0.3.45 |
|---|---:|---:|---:|
| Generated “Hey Friday” | 12 | 7 detected | 12 detected |
| Generated “Hey Friday, open Calculator” with added noise | 12 | 8 detected | 11 detected |
| Generated non-wake phrases, including “Hi Friday” and “Hey Freddie” | 32 | 2 false wakes | 16 false wakes |
| Private recordings of Indian English commands, room noise and silence (73 seconds) | 24 | 0 false wakes | 0 false wakes |

I ran the [Sherpa English model](https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html#sherpa-onnx-kws-zipformer-gigaspeech-3-3m-2024-01-01-english) with its INT8 files and the default keyword threshold of 0.25. I ran the [Vosk Indian English model](https://alphacephei.com/vosk/models) with a grammar for “Hey Friday” and unknown speech. Vosk scored exact matches in completed utterances. Both received 16 kHz mono PCM in 20 ms frames. The generated positives use US and UK eSpeak voices at three speeds. They test the pipeline; they do not measure wake accuracy for Indian English speakers. Neither engine sent audio over the network.

| Candidate | Published Android runtime | Files needed on the phone |
|---|---|---|
| Sherpa-ONNX 2.0.2, static ONNX Runtime AAR | 40.0 MB AAR; 24.9 MB of uncompressed ARM64 native code | 5.25 MB for the three model files and tokens used in the test |
| Vosk Android 0.3.75 and JNA 5.18.1 | 40.3 MB of uncompressed native code across the four current Android architectures; 10.2 MB for ARM64 | The Indian English model download is 37.6 MB; the files take 56.7 MB after extraction |

These are published artifacts and archive measurements. Friday did not build an APK with either library. The separate Sherpa mobile model export failed with a tensor shape error on the desktop runtime; the table uses the standard export. The recognition tests ran on a computer. They do not establish phone CPU use, battery drain, or locked-screen behavior.

## Smaller runtime and vocabulary checks

I built [PocketSphinx v5.1.1](https://github.com/cmusphinx/pocketsphinx/tree/v5.1.1) and tested its English acoustic model with the same 24 generated positives and 56 negatives. Each clip gets a new decoder, followed by 20 ms audio frames. This prevents acoustic adaptation from carrying across test labels.

| PocketSphinx keyword threshold | Wake clips detected / 24 | Non-wake clips that triggered / 56 |
|---|---:|---:|
| `1e-10` | 0 | 0 |
| `1e-20` | 1 | 0 |
| `1e-30` | 8 | 0 |
| `1e-40` | 11 | 0 |
| `1e-50` | 12 | 6 |

These runs use the full `cmudict-en-us.dict` from the pinned source archive. I chose the thresholds on this test set, so the table does not measure accuracy on unseen recordings. The 56 negatives include 32 generated near phrases and 24 private command/noise clips. The six false wakes at `1e-50` occur in the generated set; one clip triggers twice.

The stripped ARM64 shared library takes 548,208 bytes. The acoustic model files take 6,609,581 bytes, including their README. Friday would need to download model files after the user opts in because the project forbids bundled models. This measurement excludes Android integration code, other CPU architectures and APK packaging. I added no runtime or model to the app.

I ran the native executable on the Vivo V2168, Android 14/API 34, on 19 November 2026. It loaded the library and model and completed five prerecorded WAV inputs without a loader error. None produced a wake in the CLI's whole-file `single` mode. That mode differs from the streaming evaluator, so these five clips establish native compatibility only. I did not test live microphone capture, a foreground service, lock-screen entry, battery use or sustained listening. The microphone stayed off.

I also tested two Vosk vocabulary changes:

| Vosk vocabulary | Wake clips detected / 24 | Generated false wakes / 32 | Private negative false wakes / 24 |
|---|---:|---:|---:|
| Full model vocabulary | 0 | 0 | 0 |
| Wake phrase plus competing near phrases | 11 | 4 | 0 |

The competing grammar includes “Hey Freddie”, “Hi Friday”, “Friday”, “Next Friday” and “Hey fried rice”. These modes look for the whole phrase within a completed utterance; the original phrase-only baseline requires an exact completed utterance. Neither mode provides a good tradeoff on this set. No real positive wake recordings were available, so none of these results validates Indian English wake recognition.

## Repeat the comparison

Install the pinned Python tools in a separate test environment. Download the [standard Sherpa model archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2) and [Vosk Indian English model archive](https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip) into a temporary directory. Keep recordings and model files outside git. From the project root, set `wake_dir` to the directory where you extracted both archives. The fixture script needs `espeak-ng` and `ffmpeg`.

```bash
python -m pip install numpy sherpa-onnx==2.0.2 vosk==0.3.45
python .local/tools/wake-phrase-fixtures.py "$wake_dir/synthetic"
printf '%s\n' '▁HE Y ▁F RI DAY' > "$wake_dir/hey-friday.txt"

python .local/tools/wake-phrase-eval.py sherpa \
  "$wake_dir/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01" \
  --keywords "$wake_dir/hey-friday.txt" \
  --positive "$wake_dir/synthetic/wake" --negative "$wake_dir/synthetic/negative"

python .local/tools/wake-phrase-eval.py vosk "$wake_dir/vosk-model-small-en-in-0.4" \
  --positive "$wake_dir/synthetic/wake" --negative "$wake_dir/synthetic/negative"
```

Repeat each command with `--positive "$wake_dir/synthetic/wake-command-plus-noise" --negative docs/private-audio` for the second set. Supply your own labelled wake recordings with `--positive`. Each invocation reports clip counts, trigger counts and desktop CPU time. It does not print transcripts.

For the Vosk follow-up runs, add `--vosk-grammar competing` or `--vosk-grammar full`. The default remains `phrase-only`. The report includes the vocabulary mode and matching rule.

For PocketSphinx, extract the pinned [v5.1.1 source archive](https://github.com/cmusphinx/pocketsphinx/archive/refs/tags/v5.1.1.tar.gz) into the test directory. These commands need a local C compiler and CMake:

```bash
cmake -S "$wake_dir/pocketsphinx-5.1.1" -B "$wake_dir/ps-linux" \
  -G 'Unix Makefiles' -DBUILD_TESTING=OFF -DBUILD_SHARED_LIBS=ON \
  -DCMAKE_BUILD_TYPE=MinSizeRel
cmake --build "$wake_dir/ps-linux" --target pocketsphinx --parallel 2

python3 .local/tools/wake-phrase-eval.py pocketsphinx \
  "$wake_dir/pocketsphinx-5.1.1/model/en-us/en-us" \
  --native-library "$wake_dir/ps-linux/libpocketsphinx.so" \
  --dictionary "$wake_dir/pocketsphinx-5.1.1/model/en-us/cmudict-en-us.dict" \
  --threshold 1e-40 \
  --positive "$wake_dir/synthetic/wake" \
  --positive "$wake_dir/synthetic/wake-command-plus-noise" \
  --negative "$wake_dir/synthetic/negative" --negative docs/private-audio
```

Repeat with the other thresholds to reproduce the sweep. `detector_setup_ms` measures detector setup: PocketSphinx loads its shared library at this stage and loads a decoder/model for each clip. `decode_cpu_seconds_per_audio_second` includes those per-clip loads for PocketSphinx; `cpu_includes_per_clip_model_load` states this difference. CPU time from a computer does not estimate phone battery drain. The fixture generator uses a fixed noise seed, but different eSpeak or FFmpeg versions can change the generated audio.

### Native Android build regression

The first ARM64 build failed to link `__android_log_vprint`. Adding `-DCMAKE_C_STANDARD_LIBRARIES=-llog` fixed the link. Keep that flag in the reproduction:

```bash
cmake -S "$wake_dir/pocketsphinx-5.1.1" -B "$wake_dir/ps-arm64" \
  -G 'Unix Makefiles' -DBUILD_TESTING=OFF -DBUILD_SHARED_LIBS=ON \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/27.1.12297006/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_C_STANDARD_LIBRARIES=-llog
cmake --build "$wake_dir/ps-arm64" --target pocketsphinx pocketsphinx_main --parallel 2
```

After a native build, check that the shared library lists `liblog.so` as a dependency and has `LOAD` segment alignment of at least `0x4000` with the NDK's `llvm-readelf -l -d`. The tested ARM64 binary passes those checks. Only API 34 execution was tested; segment alignment does not prove API 26 or 16 KB-page device support. Keep the native binaries and models in a temporary directory. The app has no PocketSphinx dependency, and CI does not download these tools or build this experiment.

### Evaluator regression tests

Run `python3 -m unittest discover -s scripts -p 'test_*.py'`. These dependency-free tests cover PCM format/truncation, duplicate labels, model-path and threshold validation, partial final frames, native error cleanup, Vosk vocabulary scoring, and clip/event counts. `scripts/verify.sh` runs them before the Android build. Mocked decoder tests check the harness, not recognition accuracy.

## Before adding wake listening

Collect real “Hey Friday” recordings before tuning another detector. Start with ten isolated wake phrases and ten wake phrases followed by a command, spoken at different distances and volumes. Use a separate set from several Indian English speakers to validate the chosen settings. Include other people talking, taps, traffic and television in negative recordings. A short test with zero false wakes does not establish a rate for an hour of listening.

On Android, the user must start the microphone service from a visible setting and have a persistent notification with an off control. Friday must release the wake listener’s microphone when the command panel starts recording and keep ambient audio on the phone. Check the system assistant session on the Vivo to see whether it can show the compact panel from another app or the lock screen. The fallback is a notification the user can tap. Android restricts [starting activities in the background](https://developer.android.com/guide/components/activities/background-starts) and [starting a microphone service](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone). Do not start the microphone from a boot receiver.
