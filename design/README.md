# Friday visual direction

The images in `reference/` are the owner-approved visual references for Friday. They are design references only and must not ship in the APK.

## Source references

- `reference/friday-app-theme.png`: overall near-black, graphite, and violet mobile UI direction.
- `reference/friday-voice-orb.png`: historical spherical talking-wave direction. The owner subsequently selected Aurora for the orb interior: flowing blue and lilac gradients.

[Orb reference research](orb-research.md) records how the linked examples render their motion and how Friday's native implementation differs.

## Design rules

- Keep the interface dark in both system themes: near-black background, slightly lighter graphite surfaces, restrained borders.
- Use neutral graphite controls, pale slate for the primary action, and visible focus treatment. Keep blue and lilac gradients inside the assistant orb, with a soft pearly highlight, spherical shading, and a restrained atmospheric glow.
- Keep one dominant action per screen and preserve generous spacing around the voice orb.
- Render icons and the orb as native vectors/Canvas drawing. Do not package these reference PNGs into app resources.
- Minimum Android touch target: 48 dp. Normal text must maintain at least 4.5:1 contrast.
- Motion communicates voice state through internal gradient flow. Idle drifts slowly; listening, processing, and speaking increase movement without changing the orb's fixed circular silhouette. The current preview simulates motion while the microphone remains off.
- The orb has no border, outline, ring, particle, filament, or neon decoration.
- Respect the system animator/reduced-motion setting.
- Use bundled Geist for app text. The variable font comes from Google Fonts under the SIL Open Font License; keep `docs/licenses/Geist-OFL.txt` with the source.
- Use Untitled UI's free outline icons for interface actions. Friday currently includes Microphone 01, Chevron Right, and Arrow Left as Android vectors. Do not add the React package to the app.

Options shown in the theme reference are placeholders. Navigation, commands, routines, and settings will be selected in later milestones.

## Stop-slop rule

- Do not use generic AI-dashboard layouts, oversized marketing copy, filler cards, glass effects without purpose, decorative gradients, or excessive pills.
- Do not invent commands, settings, routines, user data, or completed functionality. Unapproved options remain absent.
- Every screen gets one clear visual priority, concise product-specific copy, and only controls that have a defined job.
- Reuse the semantic colors, type scale, radii, icon treatment, and 4/8 dp spacing rhythm defined here.
- Motion must explain listening, processing, success, or failure. Never animate merely to make an empty screen feel busy.
- Prefer native Android behavior and honest states over presentation-only mock functionality.
