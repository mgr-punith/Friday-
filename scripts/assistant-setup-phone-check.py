#!/usr/bin/env python3
"""Check assistant setup through Android UI. Selection testing restores Friday on exit."""

import argparse
import importlib.util
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

PACKAGE = "com.example.assistant"
MAIN = PACKAGE + "/.MainActivity"
USAGE = PACKAGE + "/.debug.wake.WakeUsageActivity"
SERVICE = PACKAGE + "/.debug.wake.WakeTrialService"
ASSISTANT = PACKAGE + "/.entry.FridayVoiceInteractionService"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=Path("/tmp/friday-assistant-setup-check"))
    parser.add_argument("--exercise-selection", action="store_true",
                        help="Temporarily select None through Settings, then restore Friday; stops wake listening")
    parser.add_argument("--resume-hey-friday", action="store_true",
                        help="After the selection test, tap Use/Resume Hey Friday and verify local capture")
    args = parser.parse_args()
    if args.resume_hey_friday and not args.exercise_selection:
        parser.error("--resume-hey-friday requires --exercise-selection")
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial]
    report = {}
    selection_changed = False
    spec = importlib.util.spec_from_file_location("wake_command", Path(__file__).with_name("wake-command-phone-check.py"))
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)

    def adb(*parts):
        return subprocess.check_output([*command, *parts], timeout=45)

    def shell(*parts):
        return adb("shell", *parts).decode().strip()

    def hierarchy():
        path = "/data/local/tmp/friday-assistant-setup.xml"
        for _ in range(3):
            try:
                shell("uiautomator", "dump", path)
                data = adb("exec-out", "cat", path)
                rows = list(ElementTree.fromstring(data).iter("node"))
                (args.output / "latest.xml").write_bytes(data)
                return rows
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ElementTree.ParseError):
                continue
        raise AssertionError("No current UI hierarchy")

    def control(label, rows=None, contains=False):
        if rows is None:
            rows = hierarchy()
        return next((row for row in rows if row.get("enabled") == "true" and
                     (row.get("text") == label or row.get("content-desc") == label or
                      (contains and label in row.get("text", "")))), None)

    def tap(label, contains=False):
        node = control(label, contains=contains)
        assert node is not None, "Missing control: " + label
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        assert x2 > x1 and y2 > y1, "Offscreen control: " + label
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def screenshot(name):
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def selected():
        value = shell("settings", "get", "secure", "voice_interaction_service")
        return value in (ASSISTANT, PACKAGE + "/com.example.assistant.entry.FridayVoiceInteractionService")

    def listener():
        return helpers.parse_status(shell("dumpsys", "activity", "service", SERVICE))

    def open_activity(component):
        shell("cmd", "statusbar", "collapse")
        shell("am", "start", "-W", "--activity-clear-top", "-n", component)

    def back_to_friday():
        for _ in range(5):
            if any(row.get("package") == PACKAGE for row in hierarchy()):
                return
            shell("input", "keyevent", "KEYCODE_BACK")
        raise AssertionError("Back did not return to Friday")

    def choose_assistant(name):
        # This is the public Android screen, not a vendor component or secure-setting write.
        for label in ("Default digital assistant app", "Default assistant app", "Default assist app", "Assist app"):
            if control(label) is not None:
                tap(label)
                break
        assert control(name) is not None, "Android chooser does not list " + name
        tap(name)
        if name == "Friday" and not selected():
            for label in ("OK", "Allow", "Use Friday"):
                if control(label) is not None:
                    tap(label)
                    break
        assert selected() == (name == "Friday"), "Android did not apply the requested selection"

    policy = shell("dumpsys", "window", "policy")
    delegate = policy.find("KeyguardServiceDelegate")
    assert delegate >= 0 and "showing=false" in policy[delegate:delegate + 1100], "Unlock the phone before testing"
    assert selected(), "Select Friday first; this checker restores only Friday"
    try:
        if args.exercise_selection:
            open_activity(USAGE)
            tap("Tap to speak")
            tap("Use Tap to speak")
            assert listener() is None, "Tap mode did not stop the listener"
        open_activity(MAIN)
        tap("Settings")
        assert control("Friday selected", contains=True) is not None, "Settings misreports the selected assistant"
        screenshot("selected")
        tap("Default assistant", contains=True)
        assert not any(row.get("package") == PACKAGE for row in hierarchy()), "Assistant settings did not open"
        screenshot("android-assistant-settings")
        back_to_friday()
        assert selected() and control("Friday selected", contains=True) is not None, "Cancel changed selection"
        report["directSettingsAndSelectedReturn"] = True
        if args.exercise_selection:
            tap("Default assistant", contains=True)
            # Mark before interaction so a later assertion still restores the original choice.
            selection_changed = True
            choose_assistant("None")
            back_to_friday()
            assert control("Choose Friday in Android Settings", contains=True) is not None
            assert control("Friday selected", contains=True) is None, "False success after deselection"
            tap("Open Default apps")
            assert not any(row.get("package") == PACKAGE for row in hierarchy()), "Default apps fallback did not open"
            screenshot("default-apps-fallback")
            back_to_friday()
            report["settingsDeselectionAndFallback"] = True
            open_activity(USAGE)
            tap("Hey Friday")
            tap("Set default assistant")
            assert not any(row.get("package") == PACKAGE for row in hierarchy()), "Wake setup did not open Settings"
            back_to_friday()
            assert control("Set default assistant") is not None and control("Open Default apps") is not None
            assert listener() is None and not selected(), "Cancel granted selection or started the microphone"
            screenshot("cancelled-wake-setup")
            time.sleep(0.5)
            assert control("Set default assistant") is not None, "Cancel reopened settings in a loop"
            report["wakeCancellationKeepsMicrophoneOff"] = True
            tap("Set default assistant")
            choose_assistant("Friday")
            back_to_friday()
            selection_changed = False
            report["confirmedSelection"] = selected()
            if args.resume_hey_friday:
                # Successful return can finish setup itself. Reopen only if it did not start the service.
                if listener() is None:
                    open_activity(USAGE)
                    rows = hierarchy()
                    label = next((label for label in ("Use Hey Friday", "Resume Hey Friday") if control(label, rows)), None)
                    assert label is not None, "No Hey Friday start control after verified selection"
                    tap(label)
                shell("input", "keyevent", "KEYCODE_HOME")
                deadline = time.monotonic() + 15
                state = None
                while time.monotonic() < deadline:
                    state = listener()
                    if state and state.get("phase") == "LISTENING" and state.get("wakeCapture"):
                        break
                    time.sleep(0.2)
                assert state and state.get("phase") == "LISTENING" and state.get("wakeCapture"), state
                assert not state.get("commandCapture") and abs(state["threshold"] - 0.3) < 0.0001, state
                report["verifiedSelectionStartsLocalListening"] = True
            else:
                open_activity(USAGE)
                tap("Tap to speak")
                tap("Use Tap to speak")
                assert listener() is None
        report["passed"] = True
    finally:
        if selection_changed and not selected():
            shell("am", "start", "-W", "-a", "android.settings.VOICE_INPUT_SETTINGS")
            choose_assistant("Friday")
            report["selectionRestoredAfterFailure"] = selected()
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
