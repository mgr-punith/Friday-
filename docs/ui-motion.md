# Assistant surface states

The same native `AssistantOrbView` is intended for two sizes: a large in-app voice surface and a compact round wake surface. Its violet glass sphere and crossing light ribbons follow the approved references in `design/`. Motion is generated on Android Canvas; no animation library or media asset ships in the APK.

| State | Visual behavior | Meaning |
|---|---|---|
| Idle | Static violet light ribbons in the sphere | Available, not recording |
| Preview | Moving violet ribbons with a visible preview message | UI demonstration only; microphone is off |
| Listening | Violet ribbons driven by normalized microphone amplitude | Microphone is actively capturing speech |
| Processing | Rotating lilac arc | The command is being interpreted |
| Success | Green check | Android confirmed the action succeeded |
| Error | Red cross plus a short text recovery message | The action failed or needs recovery |

The interface must never show the listening state before microphone capture starts. Reduced-motion settings freeze nonessential ambient movement while retaining clear semantic state changes.
