#!/usr/bin/env python3
"""Check debug wake handoff with labeled synthetic triggers, without sending a command."""

import argparse
import importlib.util
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "com.example.assistant"
TRIAL = PACKAGE + "/.debug.wake.WakeTrialActivity"
SERVICE = PACKAGE + "/.debug.wake.WakeTrialService"
ASSISTANT = PACKAGE + "/.AssistantActivity"


def parse_status(data):
    for line in data.splitlines():
        try:
            row = json.loads(line.strip())
        except (ValueError, UnicodeError):
            continue
        if isinstance(row, dict) and isinstance(row.get("generation"), int) and row.get("phase") in {
            "STARTING", "LISTENING", "HANDOFF", "PAUSED", "STOPPING", "STOPPED"
        }:
            return row
    return None


def notification_control(rows, label, expand):
    action = next((row for row in rows if row.get("package") == "com.android.systemui" and
                   row.get("text", "").casefold() == label.casefold()), None)
    # Vivo exposes a group's preview text before tapping it can launch its content intent.
    if expand is not None and (action is None or label.casefold() != "stop test"):
        return expand, False
    return action, action is not None


def has_rearmed(row, generation):
    return row is not None and row.get("generation") == generation and row.get("phase") == "LISTENING" and \
        row.get("wakeCapture") is True and row.get("commandCapture") is False and row.get("visibleAssistants") == 0


def has_retained_panel(row, generation):
    return row is not None and row.get("generation") == generation and row.get("phase") == "PAUSED" and \
        row.get("wakeCapture") is False and row.get("visibleAssistants") == 1


def synthetic_wake_arguments(fallback=False, outcome=None):
    arguments = ["am", "start", "-W", "-n", TRIAL, "--ez", "debug_wake_trigger", "true",
                 "--ez", "debug_wake_fallback", str(fallback).lower()]
    if outcome is not None:
        arguments += ["--es", "debug_wake_outcome", outcome]
    return arguments


def synthetic_wake_broadcast_arguments(outcome):
    if outcome not in {"no-command", "missing-app", "lock-call", "lock-app", "lock-timer", "lock-sound"}:
        raise ValueError("A safe labeled wake preview is required")
    return ["am", "broadcast", "--receiver-foreground", "-a", PACKAGE + ".debug.TRIGGER_WAKE_TEST",
            "-n", PACKAGE + "/.debug.wake.WakeTrialTestReceiver", "--es", "debug_wake_outcome", outcome]


def retry_control(rows):
    # Read this from the stable manual error panel. Waiting for a hierarchy while
    # recording can outlast both the silence timeout and automatic dismissal.
    return next((row for row in rows if row.get("content-desc") == "Start listening" and
                 row.get("enabled") == "true"), None)


def notification_shade_visible(rows):
    return any(row.get("resource-id", "").endswith("/notification_stack_scroller") for row in rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=Path("/tmp/friday-wake-command-check"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial]
    spec = importlib.util.spec_from_file_location("wake_phone_helpers", Path(__file__).with_name("wake-trial-phone-check.py"))
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)

    def adb(*parts):
        return subprocess.check_output([*command, *parts], timeout=40)

    def shell(*parts):
        return adb("shell", *parts).decode().strip()

    def status():
        return parse_status(shell("dumpsys", "activity", "service", SERVICE))

    def wait(predicate, description, seconds=15):
        deadline = time.monotonic() + seconds
        value = None
        while time.monotonic() < deadline:
            value = status()
            if predicate(value):
                return value
            time.sleep(0.15)
        raise AssertionError(f"{description}: {value}")

    def hierarchy():
        path = "/data/local/tmp/friday-wake-command.xml"
        for _ in range(4):
            shell("rm", "-f", path)
            shell("uiautomator", "dump", path)
            data = adb("exec-out", "cat", path)
            rows = helpers.parse_hierarchy(data)
            if rows is not None:
                (args.output / "latest.xml").write_bytes(data)
                return rows
        raise AssertionError("No fresh UI hierarchy")

    def tap_node(node):
        assert node is not None and node.get("enabled") == "true", "Missing or disabled control"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        assert x2 > x1 and y2 > y1, "Control is offscreen"
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(label):
        for _ in range(5):
            rows = hierarchy()
            node = next((row for row in rows if row.get("text") == label), None)
            if node is not None:
                tap_node(node)
                return
            size = shell("wm", "size")
            width, height = map(int, re.findall(r"(\d+)x(\d+)", size)[-1])
            shell("input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 2), "700")
        raise AssertionError(f"Missing control: {label}")

    def notification_action(label):
        shell("cmd", "statusbar", "expand-notifications")
        for _ in range(10):
            rows = hierarchy()
            expand = helpers.notification_expand(rows, "Friday wake test is paused")
            if expand is None:
                expand = helpers.notification_expand(rows, "Friday wake test is using the microphone")
            node, final = notification_control(rows, label, expand)
            if node is None:
                if notification_shade_visible(rows):
                    # Unrelated expanded notifications can push Friday below the
                    # visible list. Scroll without dismissing or opening them.
                    width, height = map(int, re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1])
                    shell("input", "swipe", str(width // 2), str(height * 4 // 5),
                          str(width // 2), str(height // 2), "500")
                else:
                    # A notification launch may still be collapsing the shade.
                    shell("cmd", "statusbar", "expand-notifications")
                time.sleep(0.3)
                continue
            (args.output / "notification-before-tap.png").write_bytes(adb("exec-out", "screencap", "-p"))
            tap_node(node)
            if final:
                return
        raise AssertionError(f"Missing notification action: {label}")

    def trigger(fallback=False, outcome=None):
        shell(*synthetic_wake_arguments(fallback, outcome))

    report = {}
    try:
        shell("cmd", "statusbar", "collapse")
        shell(*helpers.assistant_launch_arguments())
        shell("am", "start", "-W", "--activity-clear-top", "-n", TRIAL)
        if status() is not None:
            tap("Stop test")
            wait(lambda row: row is None, "Previous trial stopped")
        tap("Start local test")
        initial = wait(lambda row: row is not None and row["phase"] == "LISTENING", "Trial listening")
        assert abs(initial["threshold"] - 0.30) < 0.0001 and initial["commandMode"], initial
        report["start"] = initial
        shell("input", "keyevent", "KEYCODE_HOME")
        trigger()
        opened = wait(lambda row: row is not None and row["openedCommands"] == 1 and row["commandCapture"],
                      "System assistant opened and acquired microphone")
        assert not opened["wakeCapture"] and opened["phase"] == "PAUSED", opened
        assert opened["syntheticDetections"] == 1, opened
        activity = shell("dumpsys", "activity", "activities")
        assert re.search(r"(?:topResumedActivity|mResumedActivity)=.*AssistantActivity", activity), "Command panel is not foreground"
        report["systemSessionHandoff"] = opened
        shell("input", "keyevent", "KEYCODE_HOME")
        resumed = wait(lambda row: row is not None and row["phase"] == "LISTENING" and row["wakeCapture"], "Wake resumed after dismissal")
        assert resumed["generation"] == initial["generation"] and not resumed["commandCapture"], resumed
        report["resumeAfterDismissal"] = resumed

        opened_count = 1
        for scenario in ("no-command", "missing-app"):
            # Exercise real silence capture; inject only the app-lookup failure.
            # Launching a preview with a new Activity intent would pause the panel
            # and intentionally invalidate its wake origin before testing recovery.
            trigger(outcome="missing-app" if scenario == "missing-app" else None)
            opened_count += 1
            if scenario == "no-command":
                wait(lambda row: row is not None and row["openedCommands"] == opened_count and row["commandCapture"],
                     "Silent recording started")
            wait(lambda row: row is not None and row["openedCommands"] == opened_count and row["visibleAssistants"] == 1 and
                 not row["commandCapture"], f"{scenario} reached an outcome", seconds=14)
            # Require recovery with no tap, Back or Home input after the wake.
            (args.output / f"recovery-{scenario}.png").write_bytes(adb("exec-out", "screencap", "-p"))
            recovered = wait(lambda row: has_rearmed(row, initial["generation"]), f"{scenario} rearmed without a tap")
            assert recovered["openedCommands"] == opened_count, recovered
            report["automaticRecovery:" + scenario] = recovered

        # A manually opened panel must retain the error so its user can retry.
        shell("am", "start", "-W", "-n", ASSISTANT, "--es", "debug_ui_state", "no-command")
        time.sleep(4.2)
        manual = status()
        assert manual is not None and manual["phase"] == "PAUSED" and manual["visibleAssistants"] == 1, manual
        assert not manual["wakeCapture"] and not manual["commandCapture"], manual
        mic = retry_control(hierarchy())
        assert mic is not None, "Manual error panel has no retry microphone"
        report["manualErrorRetained"] = manual
        # Finish this ADB-created manual task before a later synthetic trigger
        # launches its temporary trial Activity. Home would leave it in the stack.
        shell("input", "keyevent", "KEYCODE_BACK")
        wait(lambda row: has_rearmed(row, initial["generation"]), "Wake resumed after manual panel dismissal")

        trigger(outcome="permission")
        opened_count += 1
        wait(lambda row: row is not None and row["openedCommands"] == opened_count and row["visibleAssistants"] == 1,
             "Permission scenario opened")
        time.sleep(4.2)
        permission = status()
        assert permission is not None and permission["phase"] == "PAUSED" and permission["visibleAssistants"] == 1, permission
        assert not permission["wakeCapture"] and not permission["commandCapture"], permission
        report["permissionRetained"] = permission
        shell("input", "keyevent", "KEYCODE_HOME")
        wait(lambda row: has_rearmed(row, initial["generation"]), "Wake resumed after permission panel dismissal")

        trigger(outcome="no-command")
        opened_count += 1
        wait(lambda row: row is not None and row["openedCommands"] == opened_count and row["visibleAssistants"] == 1 and
             not row["commandCapture"], "Retry scenario opened")
        tap_node(mic)
        retry_started = wait(lambda row: row is not None and row["openedCommands"] == opened_count and row["commandCapture"],
                             "Retry started a new command recording")
        report["retryStarted"] = retry_started
        time.sleep(4.2)
        retry = status()
        # Silence can finish the retried recording before this check. The pending
        # close must stay cancelled even after that second no-command result.
        assert has_retained_panel(retry, initial["generation"]) and retry["openedCommands"] == opened_count, retry
        report["retryCancelledDismissal"] = retry
        shell("input", "keyevent", "KEYCODE_HOME")
        wait(lambda row: has_rearmed(row, initial["generation"]), "Wake resumed after retry dismissal")

        trigger(fallback=True)
        pending = wait(lambda row: row is not None and row["phase"] == "HANDOFF" and not row["wakeCapture"], "Fallback awaiting notification tap")
        assert pending["openedCommands"] == opened_count and pending["syntheticDetections"] == opened_count + 1, pending
        notification_action("Wake heard · Tap to speak")
        opened_count += 1
        fallback = wait(lambda row: row is not None and row["openedCommands"] == opened_count and row["commandCapture"], "Notification opened command capture")
        assert not fallback["wakeCapture"], fallback
        report["notificationFallback"] = fallback
        notification_action("Stop test")
        shell("cmd", "statusbar", "collapse")
        wait(lambda row: row is None, "Notification Stop removed service")
        shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(1.2)
        assert status() is None, "Dismissal restarted a stopped listener"
        report["stopWhilePaused"] = True
        rows = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/wake-trial/run-history.json"))
        last = next(row for row in reversed(rows) if row["generation"] == initial["generation"] and row["createdAtMs"] == initial["createdAtMs"])
        assert last["phase"] == "STOPPED" and last["syntheticDetections"] == opened_count, last
        assert len(rows) <= 20 and len(last["detectionTimesMs"]) <= 64, rows
        report["savedMetadata"] = last

        stale = f'{last["createdAtMs"]}:{last["generation"]}:{last["detections"]}'
        shell("am", "start", "-W", "-n", ASSISTANT, "--ez", "start_listening", "true", "--es", "friday_debug_wake_token", stale)
        screen = hierarchy()
        assert any(row.get("content-desc") == "Start listening" for row in screen), "Stale wake request started recording"
        report["staleWakeRejected"] = True
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print("PASS: system-session handoff, exclusive mic, automatic error recovery, second wake without tapping, manual/permission retention, retry cancellation, fallback/Stop, metadata and stale requests")
        print("Synthetic wakes, real silent capture and injected app/permission outcomes test recovery, not live wake recognition. No command audio was deliberately submitted.")
    except BaseException as error:
        report["failure"] = str(error)
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        (args.output / "failure.png").write_bytes(adb("exec-out", "screencap", "-p"))
        latest = args.output / "latest.xml"
        if latest.exists():
            (args.output / "failure.xml").write_bytes(latest.read_bytes())
        raise
    finally:
        shell("cmd", "statusbar", "collapse")
        shell(*helpers.assistant_launch_arguments())
        if status() is not None:
            shell("am", "start", "-W", "--activity-clear-top", "-n", TRIAL)
            tap("Stop test")
            wait(lambda row: row is None, "Cleanup stopped listener")
        shell("rm", "-f", "/data/local/tmp/friday-wake-command.xml")
    print("Report:", args.output / "report.json")


if __name__ == "__main__":
    main()
