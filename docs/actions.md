# Friday actions

This is the v1 action contract. The Android action test screen runs these actions directly with typed Kotlin inputs. The backend returns the same names and argument shapes from text or WAV commands.

| Action | Args | Android behavior |
|---|---|---|
| `call_contact` | `name`: nonempty string | Match contacts locally; require a strong match or an explicit choice. Requires contacts and phone permissions. |
| `call_number` | `number`: string | Start an `ACTION_CALL` from the visible UI. Formatting is normalized; USSD and post-dial instructions are rejected. |
| `set_alarm` | `hour`: 0–23, `minute`: 0–59, optional `label` | Send `ACTION_SET_ALARM` to the clock app with skip-UI requested. |
| `set_timer` | `seconds`: positive integer | Send `ACTION_SET_TIMER` to the clock app with skip-UI requested. |
| `open_app` | `name`: nonempty string | Match installed launcher labels or known app names such as “Google Chrome” to “Chrome”. Ask if there is more than one match. |
| `close_app` | optional `name` | Go to Home. Android does not allow Friday to close another app. |
| `set_ringer_mode` | `mode`: `silent`, `vibrate`, `normal` | Change the ringer mode and check the resulting value. Silent mode requires Notification Policy access from Android Settings. |

Android actions return `Success(confirmation)`, `NeedsPermission(which)`, `NeedsUnlock`, or `Failed(reason)`. `NeedsUnlock` is a local Android result; it does not change the backend response schema. Missing permissions, unavailable handlers, invalid input, and OEM failures produce feedback rather than a crash. Contacts and launcher labels never leave the phone.

Clock intents cannot prove the other app saved an alarm or timer, so confirmations say “Alarm request sent…” and “Timer request sent…”. Calling says “Calling…” after Android starts the call UI; it does not promise a connected call. Going Home says “Went to home screen”.

The test screen and voice screen ask before placing a call. The backend validates Gemini-selected actions against this contract. The visible app records and sends a command after the user taps the microphone or invokes the selected assistant. In the opt-in debug wake trial, “Hey Friday” can open that same command panel. Wait for Listening before speaking the command. The wake detector selects no phone action and uploads no ambient audio. Release builds retain the existing tap and system-assistant entry points.

A wake-opened debug panel closes three seconds after a no-clear-command or action error, then resumes local wake listening. The user can say “Hey Friday” again without tapping. Tapping retry cancels the close. Permission prompts and manually opened panels stay open. Recovery does not repeat the command or start another cloud recording.

Cloud commands in the account-enabled APK require Google sign-in. The app uses Friday account credentials and the Vercel router rejects anonymous install tokens. See [account setup and deployment order](google-sign-in.md). Local debug action tools do not use cloud recognition.

An accepted wake entry says “What can I do for you?” before command capture. Recording waits for the greeting's completion and a short speaker-tail gap. A missing or stalled TTS engine has a bounded fallback. Leaving the app or cancelling invalidates the pending recording. Ordinary tap entry points start capture without the wake greeting.

After success, Friday speaks one short result, such as “Done. Silent mode on”. Dispatch to Clock retains “Timer request sent…” or “Alarm request sent…”. The compact panel closes 1.2 seconds after speech ends, with a 12-second watchdog for a stalled engine. Without speech, it closes after the short readable delay. The full Home screen remains open. New commands, errors, permission requests, leaving the app and user interaction cancel that success close; no command is repeated by dismissal.

In debug builds, **Settings → Start Friday** offers **Hey Friday** and **Tap to speak**. The radio buttons select a choice; the primary button applies it. On a new phone with the wake-enabled APK, **Download Hey Friday** downloads the pinned 61 KB model, shows download and installation progress, and verifies its size and SHA-256 before activation. A failed or cancelled download can be retried. Friday keeps the verified model in private storage and reuses it for later wake sessions. Downloading starts no microphone and needs no storage permission. **Use Hey Friday** retains the separate disclosure and microphone/notification permission flow. Info icons open short explanations without changing the choice. Hey Friday uses local wake detection and a silent notification with Pause/Resume. Pause releases the wake microphone before the notification shows Paused. Resume needs a notification tap. Tap to speak stops the listener and removes the wake notification. Switching choices invalidates earlier notification actions. The saved choice never starts the microphone on boot.

**Settings → Default assistant** opens Android's assistant settings through a public settings intent. If that screen is unavailable or blocked, Friday tries Default apps, then Settings. The user chooses Friday; opening Settings grants nothing. Friday checks the active voice service after returning. Cancelling leaves the choice unchanged and exposes **Open Default apps** without reopening Settings. Hey Friday setup uses the same routes with a **Set default assistant** button, and starts listening only after verifying the selection and microphone/notification access. Friday uses public APIs available on Android 8 and newer; manufacturers and device policy can restrict selection. The phone controls its assistant gesture.

## Locked-phone policy

The lock-screen wake entry remains part of the opt-in debug trial. Enable wake listening while the phone is unlocked. The selected assistant can request Friday's compact panel above keyguard; opening the panel does not unlock the phone.

| Phone state | Actions allowed without unlocking |
|---|---|
| Locked, or lock state unavailable | `call_contact`, `call_number`, `set_timer`, and `set_ringer_mode` |
| Unlocked | The supported actions above, subject to permissions and call confirmation |

Opening apps, setting alarms and going Home require unlocking. Calls do not. Friday resolves a requested contact locally and displays the existing **Start a phone call?** confirmation with its target while the phone is locked. The user must tap **Call**; canceling starts nothing. Friday checks permission before starting the call. App-name resolution and protected action details remain hidden until unlock.

For a blocked action, Friday shows “Unlock your phone, then ask again” and an **Unlock phone** button. That button requests Android's unlock flow. Cancelling it runs no action. After unlocking, repeat the blocked command. A wake itself does not dismiss keyguard. Permission setup also requires unlocking, including when a call or sound-mode command needs access.

A wake-opened locked panel closes after its result or unlock reminder and permits the existing listener to resume. A touch cancels the pending close. Ambient audio stays on the phone; the fresh command recording uses the existing backend flow. This policy does not establish screen-off wake accuracy or that a particular clock app accepts locked timer requests.

## Name and audio interpretation

For app and contact commands, Gemini returns the spoken name without words such as “open” or “call”. If those words reach the phone as part of the name, Friday can remove them from the start before matching. App names can differ from launcher labels or include spaces, as in “Whats App” for “WhatsApp”. Friday checks known product names against installed packages, so asking for Google Chrome does not open Chrome Beta. Contact matching keeps its existing thresholds and prompts.

For audio, Gemini can also select `unclear_command` when it hears silence, noise, music, or uncertain words. The backend returns `{"type":"error","reason":"bad_audio"}` and Android does not run an action. A clear unsupported request still returns `{"type":"unsupported"}`. Ringer changes include the exact command words inside the private Gemini function call; the backend checks for a matching silent, vibrate, or normal-mode phrase and removes those words from its response.
