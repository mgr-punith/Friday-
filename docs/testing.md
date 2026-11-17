# Friday testing

## Repeatable build checks

Run from the repository root:

```bash
./scripts/verify.sh
```

The script runs `./gradlew assembleDebug assembleRelease test lintDebug --console=plain`, reports each release APK size, and fails if any exceeds 15,000,000 bytes. Extra Gradle arguments are forwarded, for example `./scripts/verify.sh --offline`. Release APKs are unsigned until local signing is configured.

`./gradlew test` invokes `:app:actionLogicTest`, a JavaExec task that compiles and runs the pure Kotlin checks in `app/src/test/kotlin/com/example/assistant/actions/ActionLogicTests.kt`. The suite contains 46 assertions covering name normalization, exact and fuzzy matching, ambiguous candidates, weak-match rejection, empty and bounded inputs, phone-number formatting and unsafe dial strings, alarm ranges, timer durations, and typed action validation. It requires no JUnit or additional dependency. A failure exits with an error; success prints the number of checks passed.

These checks do not execute Android permissions, contacts providers, clock apps, calls, or ringer changes. Record phone results separately. Review `./gradlew :app:dependencies` after dependency changes to confirm the app remains free of Google service and AWS SDK dependencies.

## Phone checklist

Install with `./gradlew installDebug` and launch Friday. Use a test phone and known contacts; record the device model, Android version, and each result.

| Area | Check and expected result |
|---|---|
| Permission setup | Open each sensitive disclosure. Cancel or press Back: no system permission prompt. Tap “Agree and allow”: Android manages the request. Deny access and continue: home remains usable. |
| Permission management | Reopen setup after granting or denying access; labels reflect Android's state. After permanent denial, agreeing opens app settings. Sound mode access opens Do Not Disturb settings and refreshes on return. |
| Missing permissions | With phone or contacts access denied, try the corresponding action. Friday shows the required access without crashing or starting a call. Silent mode without policy access supplies recovery feedback. |
| Contact matching | Try an exact known contact, an accented name, a missing name, and a weak short prefix. Missing or weak matches must not call. Two similar contacts ask which one; multiple numbers must be distinguishable. |
| Final call confirmation | Choose a known contact or enter a valid number. The final dialog shows the resolved target. Cancel: no call. Only confirm using a number approved for the test; verify Android opens the call UI. |
| Input validation | Try an empty name, invalid alarm hour/minute, zero timer, and a dial string such as `*123#`. Friday rejects invalid input before side effects. |
| Alarm and timer | Send a clearly labelled test alarm and short timer. Confirm the request in the installed clock app, then remove it. Friday reports “request sent”; it cannot verify another app saved it. Test an unavailable handler on a suitable device if available. |
| App launch and Home | Open a known launcher app; try an unknown label. Duplicate labels require a choice. Go to Home opens the launcher and reports “Went to home screen”. |
| Sound modes | Record the original mode. With required access, try normal, vibrate, and silent; verify Android's actual mode and restore the original setting. Device restrictions should produce feedback. |
| Lifecycle | Begin local resolution, then leave the action screen. No delayed action should launch from the background. Return and retry; the screen remains usable. |
| UI and orb | Check graphite dialogs, readable labels, focused action editors, keyboard scrolling, enlarged system text, and landscape. Idle and Preview keep a fixed circle; disabling animators freezes ambient motion. Leaving the app pauses animation. |
| Voice status | Granted microphone access still does not start capture. The home mic button is a visual preview; backend voice commands and “Hey Friday” detection remain unavailable. |

Mark untested cases explicitly. Passing JVM checks or dispatching an Android intent does not prove the target app completed its work.
