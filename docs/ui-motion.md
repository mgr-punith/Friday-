# Assistant surface states

The same native `AssistantOrbView` is intended for a large in-app voice surface and a future compact round wake surface. The owner-approved Aurora direction uses flowing blue and lilac gradients, a soft pearly highlight, subtle spherical depth, and a restrained atmospheric glow. The sphere remains perfectly circular and stationary, with no border, outline, ring, or particles.

On API 33+ hardware canvases, a small AGSL shader compresses coordinates across the spherical surface and combines a low-frequency smooth noise sample with sine waves to bend a broad blue, cyan, and lilac flow band. A soft highlight and shading give depth. API 26–32, software canvases, and shader compilation failure use transformed cached linear and radial gradients as a softer approximation. Paints, shaders, and drawing geometry are reused rather than recreated each frame. Hardware rendering schedules frames with display vsync; software rendering uses a limited cadence. No images or animation dependencies ship in the APK.

| State | Visual behavior | Meaning |
|---|---|---|
| Idle | Slow, subtle internal gradient flow | Available, not recording |
| Preview | Stronger internal flow with a visible preview message | UI demonstration only; microphone is off |
| Listening | Internal flow responds to normalized audio input | Reserved for active microphone capture |
| Processing | Slightly more active internal flow | Reserved for command interpretation |
| Speaking | Gentle internal movement responds to audio input | Reserved for spoken confirmations |
| Success | Returns to calm flow; a short confirmation supplies meaning | Reserved for an action Android confirmed |
| Error | Returns to calm flow; a short recovery message supplies meaning | Reserved for an action that failed or needs recovery |

Only Idle and Preview are currently connected to the home screen. The audio amplitude and spectrum hooks prepare the component for future voice integration; they do not implement microphone capture or wake detection. The interface must never show the listening state before microphone capture starts or claim an action before Android confirms it.

Animation pauses while the view or app is hidden or detached. The clock resumes without advancing through the hidden interval. Disabling system animators freezes nonessential motion while retaining text feedback. State transitions adjust internal movement without resizing or shifting the sphere.
