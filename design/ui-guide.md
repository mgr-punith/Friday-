# Friday UI guide

Use this guide when adding or changing Friday screens. Keep the existing graphite theme, Geist font, Untitled UI outline icons and native Kotlin Views.

## Reference and spacing review

On 20 November 2026, we inspected **Android Settings → Display & brightness** and Friday on the connected Vivo V2168 running Android 14. The Settings reference uses aligned labels, consistent rows and groups separated by space. Friday keeps its own colors, navigation capsule and voice orb.

Friday's Settings screen stacked a 20 dp page inset, 24 dp section gaps and 66 dp rows. Start Friday added 64 dp option rows and a separate gap below each. These layers made short lists occupy too much of the screen. Use one spacing value for each relationship rather than adding margins to both neighboring views.

The reference screenshots remain in `/tmp/friday-spacing-review` on the development machine. They are local review artifacts and do not ship in the APK. Measurements below define Friday's controls; they are not measurements of Vivo's Settings app.

## Spacing

| Relationship | Friday value |
|---|---:|
| Screen left/right inset | 16 dp |
| Utility screen top / bottom padding, inside system insets | 12 / 16 dp |
| Heading to subtitle | 4 dp |
| Heading group to content | 16 dp |
| Section label to first row | 8 dp |
| Between related rows | 8 dp |
| Between sections | 16 dp |
| Standard control horizontal / vertical padding | 12 / 8 dp |
| Two-line navigation row minimum height | 60 dp |
| Start Friday option row minimum height | 56 dp |
| Button or icon tap target minimum | 48 × 48 dp |
| Back control to heading | 8 dp |
| Bottom navigation outside padding | 16 dp horizontal, 8 dp vertical |

Use 4, 8, 12 and 16 dp for ordinary component spacing. Reserve larger spacing for the voice stage or a separate task group. Use minimum heights and wrapping text for rows. Let them grow with system font size.

## Typography

| Use | Size |
|---|---:|
| Screen heading / home voice status | 19 sp |
| Compact assistant status / dialog title | 17 sp |
| Small Friday header | 15 sp |
| Buttons, fields and option labels | 13 sp |
| Body copy, status details and tooltips | 12 sp |
| Section labels and bottom tabs | 11 sp |

Use regular Geist for body copy and controls. Use bold for headings and row titles. Keep labels at 11 sp or larger. Support Android font scaling; scroll long content instead of shrinking text to fit.

## Icons and Back

Use the existing Untitled UI outline vectors. Home and Settings use 19 dp icons. Info and close controls use 20 dp glyphs in 48 dp targets. Forward chevrons use 16 dp.

Show a **24 dp left chevron followed by “Back”** for deeper screens. Make the icon and label one native button with one accessibility target. In a phone-action editor, Back returns to the action list; from the list it returns to Settings. Keep Android Back behavior consistent with the visible control.

The mic retains its 64 dp view and a smaller internal glyph. A smaller drawing must not reduce its tap area.

## Screen rules

- **Home:** one orb, one status and one primary microphone control. Keep the orb size and position stable across voice states. Use flexible space for the voice stage. Keep controls and bottom navigation clear of system bars.
- **Settings:** use the shared page gutter, row height and section gaps. Add a subtitle only when it explains the current state or the result of tapping.
- **Start Friday:** show “Hey Friday” and “Tap to speak” as native radio choices. Put optional explanations behind the info buttons. Keep the required microphone disclosure before enabling wake listening.
- **Dialogs:** use the existing Friday dialog. Show a short title, the information needed for the decision and explicit action buttons. Keep permission disclosures complete.
- **Navigation:** retain Home and Settings in one Activity. Keep scroll and result state when switching tabs. Use one Back control per deeper screen.

## Copy

Apply Stop Slop to labels, messages, help and documentation. Name the action or current state. Keep optional help behind the info control. Report an action only after Android confirms it. Keep errors inside the voice surface unless the user must make a decision.

## Implementation and review

Use `AppUi` for utility screens, rows, fields, buttons and Back. Use `VoiceSurface` for the full and compact voice screens, and `BottomNavigation` for top-level tabs. Change the shared component before introducing a local override.

Before completing a UI change:

- Review the normal phone layout first. Confirm labels remain available and targets remain at least 48 dp. The owner asked to stop large-font checks; do not repeat them for spacing changes without a new request.
- Check selected states, info popups and Back behavior. Keep decorative icons out of the accessibility tree when visible text gives the same label.
- Check both system themes and reduced motion. Friday retains its dark palette in both themes.
- Build debug and release, run the existing tests and lint, then report APK size. Mark hardware or recognition checks that were not run.

Keep screenshots and recordings out of app resources. This guide requires no new dependency, model or raster image asset.
