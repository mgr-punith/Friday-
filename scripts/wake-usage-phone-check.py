#!/usr/bin/env python3
"""Check the debug entry choices, tap help, and real notification microphone controls."""

import argparse
import importlib.util
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

PACKAGE = "com.example.assistant"
ACTIVITY = PACKAGE + "/.debug.wake.WakeUsageActivity"
SERVICE = PACKAGE + "/.debug.wake.WakeTrialService"
HEY_HELP = ("Say “Hey Friday” while using other apps. Pause or resume from the notification. "
            "Setup downloads a 61 KB model once. Wake detection runs on your phone.")
TAP_HELP = "Open Friday and tap the microphone to give a command."


def control(rows, label):
    return next((row for row in rows if row.get("enabled") == "true" and
                 (row.get("text") == label or row.get("content-desc") == label)), None)


def checked_choice(rows):
    return [row.get("text") for row in rows if row.get("class") == "android.widget.RadioButton" and
            row.get("checked") == "true"]


def is_controlled_listener(row):
    return row is not None and row.get("usageControlled") is True and row.get("phase") == "LISTENING" and \
        row.get("wakeCapture") is True and row.get("commandCapture") is False and row.get("commandMode") is True and \
        isinstance(row.get("threshold"), (int, float)) and abs(row["threshold"] - 0.3) < 0.0001


def fresh_hierarchy(discard, dump, read):
    """Retry only the read operation when Android kills its UI dump process."""
    for _ in range(4):
        discard()
        try:
            dump()
            data = read()
            root = ElementTree.fromstring(data)
            if root.tag == "hierarchy":
                return data, list(root.iter("node"))
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ElementTree.ParseError):
            continue
    raise AssertionError("No fresh UI hierarchy after four attempts")


def notification_step(rows, label):
    if not any(row.get("resource-id", "").endswith("/notification_stack_scroller") for row in rows):
        return "open", None
    system_rows = [row for row in rows if row.get("package") == "com.android.systemui"]
    action = control(system_rows, label)
    if action is not None:
        return "tap", action
    candidates = []
    for node in rows:
        children = list(node.iter("node"))
        if not any("Friday" in child.get("text", "") for child in children):
            continue
        expand = next((child for child in children if child.get("content-desc") == "Expand" and
                       child.get("clickable") == "true"), None)
        if expand is not None:
            candidates.append((len(children), expand))
    if candidates:
        return "expand", min(candidates, key=lambda item: item[0])[1]
    return "scroll", None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=Path("/tmp/friday-wake-usage-check"))
    parser.add_argument("--controls-only", action="store_true", help="Skip already-checked option help during notification debugging")
    parser.add_argument("--display-checks", action="store_true", help="Also test large text, rotation and reduced motion; changes display settings temporarily")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial]
    spec = importlib.util.spec_from_file_location("wake_command", Path(__file__).with_name("wake-command-phone-check.py"))
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)

    def adb(*parts):
        return subprocess.check_output([*command, *parts], timeout=45)

    def shell(*parts):
        return adb("shell", *parts).decode().strip()

    def status():
        return helpers.parse_status(shell("dumpsys", "activity", "service", SERVICE))

    def wait(predicate, label):
        deadline = time.monotonic() + 15
        value = None
        while time.monotonic() < deadline:
            value = status()
            if predicate(value):
                return value
            time.sleep(0.2)
        raise AssertionError(f"{label}: {value}")

    def hierarchy():
        path = "/data/local/tmp/friday-wake-usage.xml"
        data, rows = fresh_hierarchy(lambda: shell("rm", "-f", path),
            lambda: shell("uiautomator", "dump", path), lambda: adb("exec-out", "cat", path))
        (args.output / "latest.xml").write_bytes(data)
        return rows

    def tap_node(node):
        assert node is not None, "Missing control"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        assert x2 > x1 and y2 > y1, "Control is offscreen"
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(label):
        tap_node(control(hierarchy(), label))

    def open_options():
        shell("cmd", "statusbar", "collapse")
        shell("am", "start", "-W", "--activity-clear-top", "-n", ACTIVITY)

    def screenshot(name):
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def notification_toggle(label):
        shell("cmd", "statusbar", "collapse")
        if label == "Resume Hey Friday":
            # A real wake can open Friday behind the shade during this test.
            # Resume is a background-capture check, so dismiss that panel first.
            shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(0.3)
        shell("cmd", "statusbar", "expand-notifications")
        time.sleep(0.4)
        for _ in range(10):
            rows = hierarchy()
            step, node = notification_step(rows, label)
            if step == "open":
                shell("cmd", "statusbar", "expand-notifications")
                time.sleep(0.4)
            elif step == "tap":
                screenshot("notification-" + ("pause" if label.startswith("Pause") else "resume"))
                # Re-read after the screenshot: System UI can move a card as
                # the foreground-service header or another alert changes.
                current_step, current_node = notification_step(hierarchy(), label)
                if current_step != "tap":
                    continue
                tap_node(current_node)
                return
            elif step == "expand":
                tap_node(node)
            else:
                width, height = map(int, re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1])
                shell("input", "swipe", str(width // 2), str(height * 4 // 5), str(width // 2), str(height // 2), "500")
        raise AssertionError(f"Missing notification toggle: {label}")

    def pick_tap():
        open_options()
        tap("Tap to speak")
        tap("Use Tap to speak")
        wait(lambda row: row is None, "Tap mode releases the wake service")

    report = {}
    settings = [("system", "font_scale"), ("system", "accelerometer_rotation"), ("system", "user_rotation"),
                ("global", "animator_duration_scale")] if args.display_checks else []
    original = {(namespace, key): shell("settings", "get", namespace, key) for namespace, key in settings}
    original_night = shell("cmd", "uimode", "night").split()[-1]
    try:
        pick_tap()
        open_options()
        rows = hierarchy()
        assert checked_choice(rows) == ["Tap to speak"], "Default/restored choice is not exclusive"
        assert not any(row.get("text") in (HEY_HELP, TAP_HELP) for row in rows), "Options repeat the hidden explanation"
        screenshot("options")
        help_cases = () if args.controls_only else (("Hey Friday", HEY_HELP), ("Tap to speak", TAP_HELP))
        for label, explanation in help_cases:
            tap("About " + label)
            popup = hierarchy()
            assert any(row.get("text") == explanation for row in popup), "Missing tap help"
            screenshot("help-" + ("hey" if label == "Hey Friday" else "tap"))
            assert status() is None, "Info opened the microphone"
            shell("input", "keyevent", "KEYCODE_BACK")
            assert checked_choice(hierarchy()) == ["Tap to speak"], "Help changed the selection"
        report["tapHelpPreservesChoiceAndMicOff"] = "Skipped by --controls-only" if args.controls_only else True
        tap("Hey Friday")
        assert status() is None, "Choosing the radio started audio before consent"
        tap("Use Hey Friday")
        rows = hierarchy()
        if control(rows, "Not now") is not None:
            tap_node(control(rows, "Not now"))
            assert status() is None, "Declining disclosure started the mic"
            tap("Use Hey Friday")
            tap("Allow Hey Friday")
            report["explicitDisclosure"] = True
        else:
            report["explicitDisclosure"] = "Already agreed in a previous run"
        shell("input", "keyevent", "KEYCODE_HOME")
        live = wait(lambda row: row is not None and row.get("phase") == "LISTENING" and row.get("wakeCapture"), "Hey Friday begins local listening")
        assert is_controlled_listener(live), live
        report["heyListening"] = live
        notification_toggle("Pause Hey Friday")
        wait(lambda row: row is None, "Pause removes service after microphone release")
        time.sleep(1)
        assert status() is None, "Paused listener restarted"
        assert control(hierarchy(), "Resume Hey Friday") is not None, "Paused notification lost its Resume icon"
        report["pauseReleasesMicrophone"] = True
        # The only start input here is the actual notification PendingIntent,
        # while the app has no visible Activity.
        notification_toggle("Resume Hey Friday")
        resumed = wait(lambda row: row is not None and row.get("phase") == "LISTENING" and row.get("wakeCapture"), "Notification resumes from background")
        assert resumed["generation"] > live["generation"] and not resumed["commandCapture"], resumed
        report["backgroundNotificationResume"] = resumed
        for _ in range(2):
            notification_toggle("Pause Hey Friday")
            wait(lambda row: row is None, "Repeated pause")
            notification_toggle("Resume Hey Friday")
            wait(lambda row: row is not None and row.get("phase") == "LISTENING" and row.get("wakeCapture"), "Repeated resume")
        report["repeatedToggle"] = True
        for night in ("no", "yes"):
            shell("cmd", "uimode", "night", night)
            time.sleep(0.5)
            notification_toggle("Pause Hey Friday")
            wait(lambda row: row is None, "Theme-change pause")
            screenshot("notification-theme-" + night + "-paused")
            notification_toggle("Resume Hey Friday")
            wait(is_controlled_listener, "Theme-change resume")
            screenshot("notification-theme-" + night + "-listening")
        report["notificationSystemThemes"] = True
        shell("cmd", "statusbar", "collapse")
        shell("input", "keyevent", "KEYCODE_HOME")
        before_wake = wait(is_controlled_listener, "Local listener is ready for the recovery preview")
        shell(*helpers.synthetic_wake_broadcast_arguments("no-command"))
        wait(lambda row: row is not None and row.get("openedCommands", 0) == before_wake["openedCommands"] + 1 and
             row.get("phase") == "PAUSED" and row.get("visibleAssistants") == 1,
             "Labeled synthetic wake opens the existing assistant")
        recovered = wait(lambda row: is_controlled_listener(row) and row["generation"] == before_wake["generation"],
                         "Wake-originated no-command panel rearms after the notification cycles")
        assert recovered["syntheticDetections"] == before_wake["syntheticDetections"] + 1, recovered
        report["syntheticWakeErrorRecovery"] = recovered
        pick_tap()
        shell("input", "keyevent", "KEYCODE_HOME")
        shell("cmd", "statusbar", "expand-notifications")
        assert not any(control(hierarchy(), label) is not None for label in ("Pause Hey Friday", "Resume Hey Friday")), "Tap mode retained a wake control"
        report["tapChoiceRemovesNotificationAndListener"] = True

        if args.display_checks:
            shell("settings", "put", "system", "font_scale", "2.0")
            shell("settings", "put", "global", "animator_duration_scale", "0")
            open_options()
            for label in ("Hey Friday", "Tap to speak", "About Hey Friday", "About Tap to speak"):
                node = control(hierarchy(), label)
                assert node is not None, f"Large text hides {label}"
                if label.startswith("About"):
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                    assert min(x2 - x1, y2 - y1) >= 144, "Info target is smaller than 48 dp at this device density"
            screenshot("options-font-200")
            tap("About Hey Friday")
            assert any(row.get("text") == HEY_HELP for row in hierarchy()), "Scaled tooltip loses text"
            screenshot("help-font-200")
            shell("input", "keyevent", "KEYCODE_BACK")
            shell("settings", "put", "system", "accelerometer_rotation", "0")
            shell("settings", "put", "system", "user_rotation", "1")
            time.sleep(1)
            rows = hierarchy()
            assert checked_choice(rows) == ["Tap to speak"], "Rotation loses the selected choice"
            screenshot("options-landscape-font-200")
            report["font200LandscapeReducedMotion"] = True
        else:
            report["displayChecks"] = "Not requested"
        report["ok"] = True
    except Exception as error:
        report["ok"] = False
        report["error"] = str(error)
        report["failureService"] = status()
        try:
            preferences = ElementTree.fromstring(adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/friday_wake_usage.xml"))
            report["failurePreferences"] = {node.get("name"): node.get("value", node.text) for node in preferences}
            dump = shell("dumpsys", "notification")
            report["failureNotificationRecords"] = [line.strip() for line in dump.splitlines()
                if "NotificationRecord(" in line and "pkg=" + PACKAGE in line]
        except Exception as diagnostic_error:
            report["diagnosticError"] = type(diagnostic_error).__name__
        screenshot("failure")
        if (args.output / "latest.xml").exists():
            (args.output / "failure.xml").write_bytes((args.output / "latest.xml").read_bytes())
        raise
    finally:
        if original_night in ("yes", "no", "auto", "custom"):
            shell("cmd", "uimode", "night", original_night)
        for (namespace, key), value in original.items():
            if value == "null":
                shell("settings", "delete", namespace, key)
            else:
                shell("settings", "put", namespace, key, value)
        (args.output / "report.json").write_text(json.dumps(report, indent=2))
        pick_tap()
        open_options()


if __name__ == "__main__":
    main()
