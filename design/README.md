# Friday visual direction

The images in `reference/` are the owner-approved visual references for Friday. They are design references only and must not ship in the APK.

## Source references

- `reference/friday-app-theme.png`: overall near-black, graphite, and violet mobile UI direction.
- `reference/friday-navigation.png`: owner-supplied graphite navigation capsule and selected-icon pill.
- `reference/friday-voice-orb.png`: historical spherical talking-wave direction. The owner subsequently selected Aurora for the orb interior: flowing blue and lilac gradients.

[Orb reference research](orb-research.md) records how the linked examples render their motion and how Friday's native implementation differs.

## Design rules

- Keep the interface dark in both system themes: near-black background, slightly lighter graphite surfaces, restrained borders.
- Use neutral graphite controls, pale slate for the primary action, and visible focus treatment. Keep blue and lilac gradients inside the assistant orb, with a soft pearly highlight, spherical shading, and a restrained atmospheric glow.
- Keep one dominant action per screen and preserve generous spacing around the voice orb.
- Render icons and the orb as native vectors/Canvas drawing. Do not package these reference PNGs into app resources.
- Minimum Android touch target: 48 dp. Normal text must maintain at least 4.5:1 contrast.
- Idle and silence freeze the orb. Microphone energy drives its internal flow during listening; device TTS audio can drive it during playback. State changes preserve the circle's size and position. Processing uses text and a cancel control.
- The orb has no border, outline, ring, particle, filament, or neon decoration.
- Respect the system animator/reduced-motion setting.
- Use bundled Geist for app text. The variable font comes from Google Fonts under the SIL Open Font License; keep `docs/licenses/Geist-OFL.txt` with the source.
- Use Untitled UI's free outline icons for interface actions. Friday currently includes Microphone 01, Chevron Right, Arrow Left, X Close, Home Line, and Settings 01 as Android vectors. Do not add the React package to the app.

Options shown in the theme reference are placeholders. Navigation, commands, routines, and settings will be selected in later milestones.

## Stop-slop rule

- Do not use generic AI-dashboard layouts, oversized marketing copy, filler cards, glass effects without purpose, decorative gradients, or excessive pills.
- Do not invent commands, settings, routines, user data, or completed functionality. Unapproved options remain absent.
- Every screen gets one clear visual priority, concise product-specific copy, and only controls that have a defined job.
- Reuse the semantic colors, type scale, radii, icon treatment, and 4/8 dp spacing rhythm defined here.
- Motion must explain listening, processing, success, or failure. Never animate merely to make an empty screen feel busy.
- Prefer native Android behavior and honest states over presentation-only mock functionality.

## Voice screen

The launcher screen contains a small Friday header, the Aurora orb, status text, fixed voice controls, and a Home/Settings bottom bar. A compact bottom panel serves the assistant, notification, widget, and tile entry points. The panel keeps the previous app visible and provides a close button and Android Back dismissal. Settings contains setup controls and phone-action tools.

Status and results can scroll above the fixed controls. The interpreted action appears under “Understood” after the backend responds. This text comes from action arguments, not speech transcription. Use the same type scale and semantic colors in both surfaces. Keep suggestion chips and chat inputs absent.

Typography uses 20 sp screen headings, 18 sp compact assistant status, 13 sp body copy, 14 sp controls, and 11 sp bottom-tab labels. Text follows Android font scaling; controls keep 48 dp or larger touch targets. Landscape places voice controls beside scrolling status content.

The owner’s navigation reference uses a graphite capsule and a small selected-icon pill. Friday applies that treatment to its Home and Settings destinations, with 21 dp Untitled UI icons. The home orb uses a 168 dp view; the drawn sphere occupies 70% of that view. The microphone sits above the navigation capsule. The language/footer caption is removed.

## Navigation for future tabs

Main destinations use `TabHostView` inside one Activity. Create each page once and keep its view attached while hidden. Switch visibility and update the shared navigation capsule; do not start a new Activity or rebuild the page on a tab tap. Reselecting the active tab does nothing. Keep scroll position and completed results. Stop microphone and pending voice actions before hiding Home. Deeper tools and permission disclosures can use separate Activities with Android Back navigation. API 33+ registers the native back callback while Settings is selected; API 26–32 handles the legacy Back key.
