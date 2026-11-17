# Friday actions

This is the v1 action contract. The backend is not implemented yet. The action test screen runs these actions directly on Android with typed Kotlin inputs.

| Action | Args | Android behavior |
|---|---|---|
| `call_contact` | `name`: nonempty string | Match contacts locally; require a strong match or an explicit choice. Requires contacts and phone permissions. |
| `call_number` | `number`: string | Start an `ACTION_CALL` from the visible UI. Formatting is normalized; USSD and post-dial instructions are rejected. |
| `set_alarm` | `hour`: 0–23, `minute`: 0–59, optional `label` | Send `ACTION_SET_ALARM` to the clock app with skip-UI requested. |
| `set_timer` | `seconds`: positive integer | Send `ACTION_SET_TIMER` to the clock app with skip-UI requested. |
| `open_app` | `name`: nonempty string | Match launcher labels locally and start the selected app. |
| `close_app` | optional `name` | Go to Home. Android does not allow Friday to close another app. |
| `set_ringer_mode` | `mode`: `silent`, `vibrate`, `normal` | Change the ringer mode and check the resulting value. Silent/DND transitions require notification policy access. |

All actions return `Success(confirmation)`, `NeedsPermission(which)`, or `Failed(reason)`. Missing permissions, unavailable handlers, invalid input, and OEM failures produce feedback rather than a crash. Contacts and launcher labels never leave the phone.

Clock intents cannot prove the other app saved an alarm or timer, so confirmations say “Alarm request sent…” and “Timer request sent…”. Calling says “Calling…” after Android starts the call UI; it does not promise a connected call. Going Home says “Went to home screen”.

The test screen asks before placing a call. No text parser, backend request, voice recording, or wake-phrase detection is connected in this milestone.
