# Voice provider evaluation, deferred

The owner requested a later task on 21 November 2026: compare a different assistant voice, choose a free allowance where possible, and guide backend credential setup one step at a time. No cloud voice provider or TTS credential has been added in this change.

Friday currently speaks with an installed Android TextToSpeech engine. That adds no Friday backend speech bill or API key. Available voices and offline support depend on the installed engine. A phone without an English engine keeps the visible response and can still run commands.

For a consistent cloud voice, first compare Google Cloud Text-to-Speech **Chirp 3: HD** against **WaveNet**, using Indian English voices where supported. Google's pricing page checked on 21 November lists a monthly free allowance of one million characters for Chirp 3: HD and four million for WaveNet/Standard. Billing is required, and usage beyond the allowance costs money. Recheck the rates and supported voice list before enabling a provider. This allowance does not make Gemini's answer generation free.

Use short greetings and generic confirmations for the first comparison. Keep spoken contact labels on the phone so adding cloud speech does not send locally resolved contact details to another service. Measure Indian English pronunciation, time to first audio, data use and fallback behavior. The app needs cancellation, audio focus, no self-triggering, and an orb driven by playback before replacing the current voice.

## Setup to do later

1. Choose the provider and a tested voice after hearing samples. Confirm the monthly limit and privacy terms.
2. In Google Cloud, enable Cloud Text-to-Speech in the chosen project and configure billing and quotas. Budget alerts alone do not stop charges.
3. Choose backend authentication using the service's current official guidance. Prefer short-lived/workload credentials where Vercel supports them. If a service-account key is needed, the owner configures it in encrypted backend settings and rotates it; it does not go in the APK, chat, recordings or Git.
4. Add provider/project/voice settings on the backend only. Proposed names such as `TTS_PROVIDER`, `GOOGLE_CLOUD_PROJECT` and `TTS_VOICE` are not implemented yet. Select the credential environment format at that step rather than adding unused secrets now. Google OAuth login client credentials are not Text-to-Speech credentials.
5. Implement an authenticated, quota-limited speech endpoint with bounded text and audio responses. Keep the current action path and device-TTS fallback. No large voice model enters the APK.
6. Test provider outages, exceeded limits, cancellation, repeat wake commands, speaker echo, Bluetooth and locked-screen output before rollout. Document actual cost and delay.

Sources: [Google Cloud Text-to-Speech pricing](https://cloud.google.com/text-to-speech/pricing), [authentication](https://cloud.google.com/text-to-speech/docs/authentication), and [Gemini speech generation](https://ai.google.dev/gemini-api/docs/speech-generation). Gemini speech generation is another candidate, but model availability, quota, retention and paid production terms need a separate check. We have not selected or configured it.
