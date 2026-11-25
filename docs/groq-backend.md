# Groq command backend

Use `backend/` as the Vercel project root in Mumbai. Friday sends one authenticated 16 kHz mono WAV to `/v1/command`; the backend checks the account and Redis limits before provider work.

## Vercel configuration

Set these for Production and Preview, then deploy the reviewed backend:

```env
AI_PROVIDER=groq
GROQ_API_KEY=replace-with-your-key-in-vercel
TRANSCRIPTION_PROVIDER=groq
GROQ_TRANSCRIPTION_MODEL=whisper-large-v3-turbo
GROQ_ACTION_MODEL=openai/gpt-oss-20b
GROQ_TIMEOUT_MS=10000
GROQ_MAX_COMPLETION_TOKENS=256
ANSWERS_ENABLED=true
GROQ_ANSWER_TIMEOUT_MS=18000
ANSWER_PER_ACCOUNT_MINUTE=3
ANSWER_PER_ACCOUNT_DAILY=20
ANSWER_GLOBAL_DAILY=1000
```

Keep the Google sign-in, JWT and Upstash variables. Enter secrets in Vercel's Environment Variables UI. See [voice providers](voice-providers.md) to select NVIDIA Parakeet and enable WaveNet.

## Request behavior

Groq receives the transcript and selects one required tool call. Friday uses temperature zero, low reasoning and a 256-token selector cap, then validates the action. Clear single-action requests and factual questions send only the relevant tool schemas. Exact supported commands can skip selection. Debug text skips transcription.

Recognition and selection share ten seconds and one extra request for recovery. Current-information answers can extend the total deadline to eighteen seconds and add one search completion capped at 512 generated tokens. Search has separate quotas; Groq sends queries to Exa. Friday can ask up to two clarification questions; reply requests cannot execute phone actions. Users must accept the disclosure before cloud requests and opt in before answers/search.

Friday rejects malformed actions and empty recognition. Whisper also checks no-speech/confidence metadata. Provider failures return `busy` or `timeout`; permanent errors do not retry. A selector JSON failure can use the one recovery request without repeating successful recognition. Friday keeps recordings and transcripts in memory and logs codes, timings and token counts.

## Verify and roll back

```bash
cd backend
npm run verify
```

To restore Whisper, set `TRANSCRIPTION_PROVIDER=groq` and redeploy. To restore Gemini, set `AI_PROVIDER=gemini`, `TRANSCRIPTION_PROVIDER=groq`, `ANSWERS_ENABLED=false`, `SPEECH_PROVIDER=device`, configure `GEMINI_API_KEY` and `GEMINI_MODEL`, then redeploy. The AWS fallback uses the older install-token flow and cannot serve the account-enabled APK.

Groq applies account quotas; billed speech requests have a ten-second minimum. Check [speech pricing](https://console.groq.com/docs/speech-to-text) and [rate limits](https://console.groq.com/docs/rate-limits) before inviting users.
