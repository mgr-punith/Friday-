# Groq command backend

The owner approved switching Vercel from Gemini to Groq on 22 November 2026. Gemini free-tier quota errors interrupted earlier evaluation runs. The Groq logs also showed `400 tool_use_failed` with “Failed to parse tool call arguments as JSON”. The earlier corrected GPT OSS test used 512 completion tokens and low reasoning; it passed 11 of 12 private cases with a median provider duration of 896 ms. That result came from the development machine, not Mumbai or Friday's capture path.

## Vercel settings

In **Friday backend → Settings → Environment Variables**, add these for Production and Preview:

```env
AI_PROVIDER=groq
GROQ_API_KEY=replace-with-your-key-in-vercel
GROQ_TRANSCRIPTION_MODEL=whisper-large-v3-turbo
GROQ_ACTION_MODEL=openai/gpt-oss-20b
GROQ_TIMEOUT_MS=10000
GROQ_MAX_COMPLETION_TOKENS=512
```

Keep `GOOGLE_WEB_CLIENT_ID`, `JWT_SIGNING_KEY` and the Upstash Redis URL/token. Keep browser OAuth variables if that fallback is configured. Enter the real Groq key in Vercel; it never belongs in the app, chat or Git. Changing environment variables affects the next deployment, so deploy the reviewed code before testing the switch. Existing deployments use Gemini until `AI_PROVIDER=groq` takes effect. The Groq configuration needs no Gemini key or model.

To roll back, set `AI_PROVIDER=gemini`, configure `GEMINI_API_KEY`, `GEMINI_MODEL=gemini-3.1-flash-lite`, `GEMINI_THINKING_LEVEL=MINIMAL` and `GEMINI_TIMEOUT_MS=10000`, then deploy. The AWS fallback retains its older Gemini/install-token flow; it does not serve the account-enabled APK.

## Request and response

Friday's app still sends one authenticated WAV request to `/v1/command`. The backend checks its account, input and Redis limits before calling Groq. Whisper receives the WAV as multipart data with English, temperature zero and `verbose_json`. The backend rejects empty, uncertain or non-speech segments before asking GPT OSS to select an action.

Classification uses the existing action declarations, `tool_choice=required`, `parallel_tool_calls=false`, temperature zero, `max_completion_tokens=512`, `reasoning_effort=low` and `include_reasoning=false`. GPT OSS does not support `reasoning_format`, so Friday omits it. Its completion budget covers reasoning and tool arguments; reducing it to the old 100-token value can cause malformed or incomplete calls. The backend accepts one known function with JSON object arguments and validates those arguments against Friday's action contract. It ignores prose and never parses an action from an error's `failed_generation` field.

Groq returned `close_app` with `name=""` in the first live run. Friday now treats an empty optional app name or alarm label as omitted. Required contact/app names remain strict. The tool schemas reject additional properties, and the prompt asks the model to omit unspoken optional fields.

The successful app response stays `{"type":"action","action":"set_timer","args":{"seconds":120}}`. `Server-Timing` reports `groq`, `transcription` and `classification` durations; the updated app reads the total Groq duration. Debug text commands skip transcription. Neither Friday's backend logs nor its persistent stores contain audio or transcripts. The selected processor receives the audio/transcript under its own terms.

## Error handling

Transcription and classification share a ten-second deadline and one extra request allowance for the whole command. The handler never repeats a successful transcription when classification fails.

| Provider response | Friday behavior |
|---|---|
| `400 tool_use_failed` during classification | Retry once with twice the completion budget, up to 2,048 tokens, if time remains. Preserve the original command; do not use the failed generation. |
| Ordinary `400`, `401`, `403`, `404`, `422` | Return a short `busy` error and a safe stage/status log code. Correct the request, key or model configuration; repeating it does not fix it. |
| `500`, `502`, `503`, `504`, `408` or a network failure | Retry once after 200–299 ms backoff, respecting a longer `Retry-After` if it fits the deadline. |
| `429` with a `Retry-After` of one second or less | Retry once after the indicated delay when the deadline permits. |
| `429` with a longer or missing retry delay | Return HTTP 429 with `reason=busy` and `Retry-After`; do not keep resending audio. |
| Deadline/abort | Return `reason=timeout`. |
| Malformed, multiple, unknown or truncated tool calls | Return `busy`; run no phone action. |
| Empty or uncertain Whisper audio | Return `bad_audio`; skip classification. |

Logs contain codes such as `groq_classification_http_404` or `groq_transcription_http_429` plus the backend request ID. They omit provider messages, transcripts, tokens and generated arguments. A free account still has request/token/audio limits. Switching providers does not remove quotas; check Groq's account limits before inviting users. Groq bills at least ten seconds per audio request when billing applies.

## Verification

The production-provider regressions in `backend/test/groq.test.ts` exercise both stages, the exact reported JSON failure, repair budget, permanent errors, server retry, 429 cooldown, shared deadline, audio confidence, one-call validation, API quota gating and safe logging. The tests use mocked responses and add no dependency. Run `npm run verify` before deployment; it also checks routes, bundles and the Vercel entry point.

The final local audio evaluation completed **12 requests with no HTTP failures**, matched **11/12**, and rejected both silence/noise cases. Median total provider duration: **1,127 ms**. P95 and maximum: **1,299 ms**. One app name was transcribed differently from the expected name; the phone's matcher thresholds were not weakened. These measurements use the owner's existing private recordings from the development machine, five seconds between cases. They exclude Friday recording, backend authentication/quotas and Android actions, and do not prove performance from Mumbai. Keep the local helper and result JSON outside Git.

Sources checked on 22 November: [speech-to-text](https://console.groq.com/docs/speech-to-text), [tool use](https://console.groq.com/docs/tool-use) and [reasoning](https://console.groq.com/docs/reasoning). Deployment and a fresh phone command remain separate checks.
