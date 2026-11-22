# Friday model and upload measurements

## Decision status

On 22 November 2026, the owner approved switching the Vercel command backend to **Groq Whisper Large V3 Turbo → GPT OSS 20B** after reviewing Gemini free-tier quota errors and Groq's `tool_use_failed` responses. [Groq setup](groq-backend.md) records the request fields, retries and verification. The owner will add Groq's environment variables in Vercel. The older comparisons below remain historical; they do not prove the new deployment's phone-to-action latency.

On 22 November, the owner chose to keep the current backend. A [Kotlin, Rust and C++ phone benchmark](audio-processing-benchmark.md) found that skipping unused whole-clip pitch analysis reduced three-second recording preparation from about 15.28 ms to 1.88 ms in Kotlin. The app now uses that optimization. Native prototypes saved another 1.09–1.26 ms, so command audio stays in Kotlin and the existing C++ wake engine stays in place. These figures exclude capture and cloud latency.

## Method

Use the same commands, recordings, fixed phone time, timeout, and network location for each run. Run from Mumbai or the intended backend region. Report action and argument accuracy with median, P95, maximum latency, and failures. Keep recordings and transcripts out of logs and Git. Text tests cover action classification; private WAV tests cover accent recognition.

The evaluation runner supports direct Gemini requests and the sequential Groq path. The Vercel backend now has a separate production Groq provider with confidence checks, a shared deadline and bounded recovery. Both use plain `fetch`; changing the provider adds no dependency.

## Measurements from 19 November 2026

Each row records one development request. A full benchmark needs the private audio suite. We sent “Set a timer for two minutes” in a 16 kHz mono WAV.

| Path | Result | Measured Gemini latency |
|---|---|---:|
| Gemini 3.1 Flash-Lite, short WAV | Correct `set_timer` | 2,868 ms |
| Gemini 3.1 Flash-Lite, full WAV | Correct `set_timer` | 1,831 ms |
| Gemini 3.1 Flash-Lite, deployed backend | Correct `set_timer` | 1,584 ms |
| Gemini 3.5 Flash-Lite, text | Correct result | about 7,500 ms |
| Gemini 3.5 Flash-Lite, audio | Timed out | 10,000 ms deadline |

Additional production checks with Gemini 3.1 Flash-Lite:

- Synthetic noise returned `bad_audio`; Gemini took 2,117 ms.
- An unsupported text request returned `unsupported`; Gemini took 991 ms.

The local keys and two private Indian English recording manifests are configured. The initial single-command checks predate those recording suites.

### Text classification suite

The suite contains 66 expected actions. Provider limits interrupted both full runs, so use accuracy among completed responses for model behavior and keep provider failures separate.

| Provider | Completed responses | Correct | Completed accuracy | Median completed latency | P95 completed latency | Provider failures |
|---|---:|---:|---:|---:|---:|---|
| Gemini 3.1 Flash-Lite | 34 | 32 | 94.1% | 1,111 ms | 2,744 ms | 31 busy, 1 timeout |
| Groq GPT OSS 20B | 14 | 13 | 92.9% | 474 ms | 744 ms | 48 HTTP 429, 4 HTTP 400 |

The Gemini run used a 100 ms gap. The Groq run used a 250 ms gap. We observed provider rejections at these rates. The evaluator supports `EVAL_OFFSET` and `EVAL_LIMIT` for resumed chunks. These historical text figures use strict JSON equality and predate the Android-equivalent matching checks.

Gemini 3.5 Flash-Lite returned the first text case correctly in 3,126 ms. Gemini 3.1 Flash-Lite returned the same first case correctly in 1,538 ms during the connectivity check. One case does not establish a median, but 3.1 was faster in this pair.

Gemini listed `gemini-2.5-flash-lite`, but its `generateContent` endpoint returned HTTP 404 for this key. We removed it from the comparison.

## Groq comparison

Groq's API lists `whisper-large-v3-turbo` and `openai/gpt-oss-20b` for this account. Groq bills at least 10 seconds for each audio request, including shorter commands. The two API calls run one after the other, so the comparison must use end-to-end latency, not model speed in isolation.

One two-minute timer recording completed through Whisper Large V3 Turbo and GPT OSS 20B in 784 ms with WAV. Transcription took 577 ms and classification took 207 ms. The action was correct. Treat this as a connectivity and pipeline check, not an accuracy result.

## AAC upload check

The same recorded command produced these host-side files:

| Format | Size |
|---|---:|
| 16 kHz mono PCM WAV | 70,492 bytes |
| AAC in M4A at 32 kbit/s | 8,687 bytes |

AAC reduced this sample by 61,805 bytes, or 87.7%. The same timer recording produced these request results:

| Provider | WAV | M4A | Result |
|---|---:|---:|---|
| Gemini 3.1 Flash-Lite | 1,939 ms | 3,598 ms | Both correct |
| Groq two-step | 784 ms | 897 ms | Both correct |

Single requests vary with network and provider load. This pair does not show a latency gain from M4A. The evaluation runner can convert each private WAV case to mono 16 kHz M4A at 32 kbit/s and report upload size, accuracy, and latency. Friday still records and uploads WAV. Measure Android encoding time and device compatibility before changing the app.

## Runtime settings

Friday's AWS fallback uses arm64, 256 MB memory, a 15-second function timeout, and reserved concurrency 20. Keep those values until a controlled load test shows a latency or capacity problem. Production runs on Vercel, so AWS memory and concurrency changes would not affect it.

## Owner voice recordings

We imported two recordings from a Vivo V2168 running Android 14. The first used 8 kHz AMR; the second used 48 kHz stereo AAC. We cut individual clips at pauses and converted them to mono 16 kHz PCM WAV. Conversion cannot restore the first recording's lost frequencies. Neither source reproduces Friday's native capture pipeline.

Each suite contains ten labeled phone commands and two silence/noise cases. We excluded extra uncertain phrases in the second recording. The benchmark accepts formatting that Android's matcher handles, while requiring correct action arguments. Calls never ran during these provider tests.

The first suite used strict JSON equality. Gemini 3.1 passed 7/12 with two timeouts; Gemini 3.5 passed 9/12; the original Groq configuration passed 5/12 with three HTTP 400 failures. A Camera capitalization difference counted as a mismatch. Do not compare those counts with the Android-equivalent scoring below.

### Higher-quality suite

| Provider | Correct / 12 | Median API duration | P95 API duration | Noise cases | Other result |
|---|---:|---:|---:|---:|---|
| Gemini 3.1 Flash-Lite | 11 | 2,778 ms | 8,792 ms | 2/2 rejected | Contact-name argument mismatch |
| Gemini 3.5 Flash-Lite | 9 | 6,197 ms | 7,841 ms | 2/2 rejected | Three argument mismatches |
| Groq Whisper + GPT OSS 20B, corrected settings | 11 | 896 ms | 1,883 ms | 2/2 rejected | App-name argument mismatch |

We ran provider requests from the local development machine with five-second gaps. We have not measured this suite from Mumbai. Durations include client-to-provider network and provider processing; they exclude phone recording, local AAC conversion, Friday's backend overhead, and Android actions. This sample covers one speaker and does not estimate population accuracy.

GPT OSS 20B needs room for reasoning before its function call. The original 100-token cap produced `tool_use_failed` HTTP 400 responses. A diagnostic noise request with 512 tokens and `reasoning_effort=low` returned `unclear_command`. The corrected 12-case run produced no HTTP 400 failures. Unit tests guard the request fields.

Keep production on Gemini 3.1 and WAV for this handoff. Groq's latency merits a follow-up from Mumbai, but its model differs from the originally planned Llama and the owner must approve a production provider change. The 2.5-second phone-to-action median target remains unverified. We did not tune production memory or concurrency without a deployed load measurement.

Android verification: 58 action checks and 164 voice checks passed. The release APK remains 214,440 bytes; the recording importer belongs only to debug builds. Backend verification passed 78 tests. Android lint reported zero errors and 25 warnings.

We verified the final debug importer on the unlocked Vivo phone. Sharing the second recording created a 1,523,662-byte `recording.audio` file. The pulled file's SHA-256 matched the original recording, and the importer removed `pending.audio`. Launching the activity without a shared file produced no AndroidRuntime crash. Release manifest checks exclude the importer.

## Verification commands

Run the build checks from the repository root and backend directory:

```bash
GRADLE_USER_HOME=/tmp/friday-gradle ./scripts/verify.sh --offline --no-daemon
cd backend
npm run verify
```

The higher-quality audio comparison used the same private manifest, 10-second timeout, WAV format, and five-second gap:

```bash
EVAL_PROVIDER=gemini GEMINI_MODEL=gemini-3.1-flash-lite EVAL_AUDIO_FORMAT=wav EVAL_DELAY_MS=5000 node --env-file=.env build-test/eval/run.js --audio-manifest ../docs/private-audio/session-2-manifest.json
EVAL_PROVIDER=gemini GEMINI_MODEL=gemini-3.5-flash-lite EVAL_AUDIO_FORMAT=wav EVAL_DELAY_MS=5000 node --env-file=.env build-test/eval/run.js --audio-manifest ../docs/private-audio/session-2-manifest.json
EVAL_PROVIDER=groq EVAL_AUDIO_FORMAT=wav EVAL_DELAY_MS=5000 node --env-file=.env build-test/eval/run.js --audio-manifest ../docs/private-audio/session-2-manifest.json
```

The final Groq command used the corrected GPT OSS settings. Benchmark runs exit with code 1 when any case mismatches, times out, or encounters a provider error. This is a measured evaluation result; the separate mocked build checks must pass. We did not run SAM commands in this handoff because the local SAM CLI is unavailable.

## Completion criteria

Run at least 10 private Indian English recordings through each path:

| Provider path |
|---|
| Gemini 3.1 Flash-Lite |
| A second current Gemini model that accepts audio and forced function calls |
| Groq Whisper Large V3 Turbo plus GPT OSS 20B |

Record accuracy, median, P95, maximum latency, timeouts, request region, and date. Keep Gemini 3.1 Flash-Lite unless another path improves command accuracy without missing the 2.5-second median target or raising cost beyond the owner's budget.
