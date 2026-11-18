# Assistant surface states

The same native `AssistantOrbView` is intended for a large in-app voice surface and a future compact round wake surface. The owner-approved Aurora direction uses flowing blue and lilac gradients, a soft pearly highlight, subtle spherical depth, and a restrained atmospheric glow. The sphere remains perfectly circular and stationary, with no border, outline, ring, or particles.

On API 33+ hardware canvases, a small AGSL shader compresses coordinates across the spherical surface and combines a low-frequency smooth noise sample with sine waves to bend a broad blue, cyan, and lilac flow band. A soft highlight and shading give depth. API 26–32, software canvases, and shader compilation failure use transformed cached linear and radial gradients as a softer approximation. Paints, shaders, and drawing geometry are reused rather than recreated each frame. Hardware rendering schedules frames with display vsync; software rendering uses a limited cadence. No images or animation dependencies ship in the APK.

| State | Visual behavior | Meaning |
|---|---|---|
| Idle | Frozen light fields | Available, not recording |
| Preview | Stronger internal flow with a visible preview message | Optional UI demonstration; microphone is off |
| Listening | Quiet frames stay frozen. Speech energy and low/mid/high balance drive the internal flow. | Active microphone capture after a tap |
| Processing | The last speech motion settles into a frozen frame. | Backend request and local action preparation |
| Speaking | Gentle internal movement responds to audio input | Reserved for spoken confirmations |
| Success | Frozen light fields; a short confirmation supplies meaning | Reserved for an action Android confirmed |
| Error | Frozen light fields; a short recovery message supplies meaning | Reserved for an action that failed or needs recovery |

Idle, Listening, Processing, Success, and Error are connected to the visible app voice loop. Raw microphone RMS crosses an adaptive room-noise gate before it can move the orb. Low, mid, and high energy alter the shape of that movement. Friday reports success only after Android accepts the requested action. Wake detection remains separate and is not implemented in this milestone.

Animation pauses while the view or app is hidden or detached. The clock resumes without advancing through the hidden interval. Android's reduced-motion setting freezes the orb while text continues to show status. State transitions never resize or shift the sphere.
