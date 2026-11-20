#!/usr/bin/env python3
"""Check the installed debug wake trial: JNI replay, model rejection and mic lifecycle."""

import argparse
import json
import re
import shlex
import subprocess
import time
import uuid
from pathlib import Path
from xml.etree import ElementTree

PACKAGE = "com.example.assistant"
ACTIVITY = PACKAGE + "/.debug.wake.WakeTrialActivity"
ROOT = "files/wake-trial/"
MODEL = ROOT + "hey_friday.tflite"


def assistant_launch_arguments():
    # Bringing a task forward can leave WakeTrialActivity on top of its MainActivity.
    return ("am", "start", "-W", "--activity-clear-top", "-n", PACKAGE + "/.MainActivity")


def assistant_is_foreground(state):
    return re.search(r"(?:topResumedActivity|mResumedActivity)=.*" + re.escape(PACKAGE + "/.MainActivity"), state) is not None


def parse_replay_result(data, run_id):
    try:
        result = json.loads(data)
        if isinstance(result, dict) and result.get("runId") == run_id and isinstance(result.get("ok"), bool):
            return result
    except (ValueError, UnicodeError):
        pass
    return None


def parse_hierarchy(data):
    try:
        root = ElementTree.fromstring(data)
        if root.tag == "hierarchy":
            return list(root.iter("node"))
    except ElementTree.ParseError:
        pass
    return None


def notification_expand(rows, title):
    candidates = []
    for node in rows:
        children = list(node.iter("node"))
        if not any(child.get("text") == title for child in children):
            continue
        buttons = [child for child in children if child.get("resource-id") == "android:id/expand_button" and
                   child.get("content-desc") == "Expand" and child.get("clickable") == "true"]
        if buttons:
            candidates.append((len(children), buttons[0]))
    return min(candidates, key=lambda item: item[0])[1] if candidates else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--pcm", type=Path, help="Optional 16 kHz mono signed 16-bit little-endian PCM recording")
    parser.add_argument("--expect-ms", type=int, nargs="*", help="Expected replay event times in milliseconds")
    parser.add_argument("--lifecycle", action="store_true", help="Also open the microphone and test background/stop/handoff")
    parser.add_argument("--output", type=Path, default=Path("/tmp/friday-wake-trial-check"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial]

    def adb(*parts, data=None):
        return subprocess.check_output([*command, *parts], input=data, timeout=40)

    def shell(*parts):
        return adb("shell", *parts).decode().strip()

    def private_write(path, data):
        remote = shlex.join(["run-as", PACKAGE, "sh", "-c", "cat > " + path])
        adb("shell", "-T", remote, data=data)

    def private_read(path):
        return adb("exec-out", "run-as", PACKAGE, "cat", path)

    def start(replay=False, run_id=""):
        shell("cmd", "statusbar", "collapse")
        parts = ["am", "start", "-W", "--activity-clear-top", "-n", ACTIVITY, "--ez", "debug_wake_commands", "false"]
        if replay:
            parts += ["--ez", "debug_wake_replay", "true", "--es", "debug_wake_replay_id", run_id]
        shell(*parts)

    def hierarchy():
        for _ in range(4):
            shell("rm", "-f", "/data/local/tmp/friday-wake-trial.xml")
            shell("uiautomator", "dump", "/data/local/tmp/friday-wake-trial.xml")
            xml = adb("exec-out", "cat", "/data/local/tmp/friday-wake-trial.xml")
            rows = parse_hierarchy(xml)
            if rows is not None:
                (args.output / "latest.xml").write_bytes(xml)
                return rows
            time.sleep(0.25)
        raise AssertionError("Could not read a fresh UI hierarchy")

    def tap_node(node, label):
        assert node is not None, f"Missing control: {label}"
        assert node.get("enabled") == "true", f"Disabled control: {label}"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        assert x2 > x1 and y2 > y1, f"Offscreen control: {label}"
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(label, package=None):
        rows = hierarchy()
        node = next((node for node in rows if (package is None or node.get("package") == package) and
                     (node.get("text", "").casefold() == label.casefold() or node.get("content-desc") == label)), None)
        tap_node(node, label)

    def notification_stop():
        shell("cmd", "statusbar", "expand-notifications")
        for _ in range(5):
            rows = hierarchy()
            action = next((node for node in rows if node.get("package") == "com.android.systemui" and
                           node.get("text", "").casefold() == "stop test"), None)
            if action is not None:
                tap_node(action, "Stop test notification action")
                return
            expand = notification_expand(rows, "Friday wake test is using the microphone")
            tap_node(expand, "Expand Friday notification")
        raise AssertionError("Wake-test notification has no Stop action")

    def wait_label(label):
        for _ in range(6):
            if any(node.get("text") == label for node in hierarchy()):
                return
            time.sleep(0.2)
        raise AssertionError(f"Missing status: {label}")

    def replay():
        run_id = uuid.uuid4().hex
        shell("run-as", PACKAGE, "rm", "-f", ROOT + "replay-result.json")
        start(replay=True, run_id=run_id)
        for _ in range(80):
            # Avoid printing expected missing-file errors from polling.
            result = subprocess.run([*command, "exec-out", "run-as", PACKAGE, "cat", ROOT + "replay-result.json"],
                                    capture_output=True, timeout=15)
            parsed = parse_replay_result(result.stdout, run_id)
            if result.returncode == 0 and parsed is not None:
                return parsed
            time.sleep(0.25)
        raise AssertionError("JNI replay timed out")

    def recording_active():
        state = shell("dumpsys", "activity", "services", PACKAGE)
        return "WakeTrialService" in state and "isForeground=true" in state

    original = private_read(MODEL)
    pcm = args.pcm.read_bytes() if args.pcm else b"\0\0" * 32_000
    assert pcm and len(pcm) <= 3_200_000 and len(pcm) % 2 == 0, "Expected at most 100 seconds of 16-bit PCM"
    results = {}
    try:
        private_write(ROOT + "replay.pcm", pcm)
        result = replay()
        assert result.get("ok"), result
        assert result.get("jniChecks") == 5, result
        expected = args.expect_ms if args.expect_ms is not None else ([] if args.pcm is None else None)
        if expected is not None:
            assert result["detectionsMs"] == expected, result
        results["replay"] = result
        print("PASS: JNI validation and private PCM replay", result, flush=True)
        for name, invalid in (("missing", b""), ("corrupt", b"\0" * len(original))):
            private_write(MODEL, invalid)
            result = replay()
            assert result.get("ok") is False and "model" in result.get("error", "").lower(), result
            results[name + "ModelRejected"] = True
            print("PASS:", name, "model rejected without crashing", flush=True)
        private_write(MODEL, original)
        if args.lifecycle:
            start()
            tap("Start local test")
            wait_label("Listening for Hey Friday")
            assert recording_active(), "Missing microphone foreground service"
            results["microphoneStart"] = True
            shell("input", "keyevent", "KEYCODE_HOME")
            time.sleep(1)
            assert recording_active(), "Home unexpectedly stopped the foreground trial"
            policy = shell("dumpsys", "window", "policy")
            if re.search(r"^\s+secure=false$", policy, re.MULTILINE):
                try:
                    shell("input", "keyevent", "KEYCODE_SLEEP")
                    time.sleep(1)
                    assert recording_active(), "Screen-off stopped the foreground trial"
                    results["shortScreenOff"] = True
                finally:
                    shell("input", "keyevent", "KEYCODE_WAKEUP")
                    shell("wm", "dismiss-keyguard")
            else:
                results["shortScreenOff"] = "not tested: phone has a secure lock"
            start()
            wait_label("Listening for Hey Friday")
            tap("Stop test")
            wait_label("Microphone off")
            assert not recording_active(), "Stop left a foreground microphone service"
            results["homeAndStop"] = True
            tap("Start local test")
            wait_label("Listening for Hey Friday")
            notification_stop()
            shell("cmd", "statusbar", "collapse")
            start()
            wait_label("Microphone off")
            assert not recording_active(), "Notification Stop left the microphone running"
            results["notificationStop"] = True
            # Duplicate Start events must leave one recorder. The UI disables changes while stopping.
            for _ in range(2):
                tap("Start local test")
                wait_label("Listening for Hey Friday")
                tap("Stop test")
                wait_label("Microphone off")
            results["restart"] = True
            tap("Start local test")
            wait_label("Listening for Hey Friday")
            shell(*assistant_launch_arguments())
            foreground = shell("dumpsys", "activity", "activities")
            assert assistant_is_foreground(foreground), "Normal assistant did not become foreground"
            for _ in range(20):
                if not recording_active():
                    break
                time.sleep(0.1)
            assert not recording_active(), "Assistant and trial competed for microphone ownership"
            results["assistantHandoff"] = True
            print("PASS: microphone foreground service, Home, screen/notification Stop, restart and assistant handoff", flush=True)
        (args.output / "report.json").write_text(json.dumps(results, indent=2) + "\n")
    finally:
        shell("cmd", "statusbar", "collapse")
        private_write(MODEL, original)
        shell("run-as", PACKAGE, "rm", "-f", ROOT + "replay.pcm", ROOT + "replay-result.json", ROOT + "replay-result.pending")
        shell("rm", "-f", "/data/local/tmp/friday-wake-trial.xml")
        shell(*assistant_launch_arguments())
    print("Report:", args.output / "report.json")


if __name__ == "__main__":
    main()
