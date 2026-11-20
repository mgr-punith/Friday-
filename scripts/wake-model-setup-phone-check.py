#!/usr/bin/env python3
"""Test first-device wake model setup without enabling the microphone or changing credentials."""

import argparse
import hashlib
import importlib.util
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "com.example.assistant"
ACTIVITY = PACKAGE + "/.debug.wake.WakeUsageActivity"
SERVICE = PACKAGE + "/.debug.wake.WakeTrialService"
MODEL = "files/wake-trial/hey_friday.tflite"
PREFERENCES = "shared_prefs/friday_wake_usage.xml"
EXPECTED_BYTES = 60_832
EXPECTED_HASH = "04aaffa5f111704db18ec593251a3dfbb009638009e081c9c87830755c3563a6"


def approved(data):
    return len(data) == EXPECTED_BYTES and hashlib.sha256(data).hexdigest() == EXPECTED_HASH


def tap_point(node):
    assert node is not None, "Missing enabled control"
    bounds = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
    assert len(bounds) == 4, "Control has no bounds"
    left, top, right, bottom = bounds
    assert 0 <= left < right and 0 <= top < bottom, "Control is offscreen"
    return (left + right) // 2, (top + bottom) // 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=Path("/tmp/friday-wake-model-setup-check"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial]
    spec = importlib.util.spec_from_file_location("wake_usage", Path(__file__).with_name("wake-usage-phone-check.py"))
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    lock_spec = importlib.util.spec_from_file_location("locked_wake", Path(__file__).with_name("locked-wake-phone-check.py"))
    lock_helpers = importlib.util.module_from_spec(lock_spec)
    lock_spec.loader.exec_module(lock_helpers)

    def adb(*parts, data=None):
        return subprocess.check_output([*command, *parts], input=data, timeout=45)

    def shell(*parts):
        return adb("shell", *parts).decode().strip()

    def exists(path):
        return subprocess.run([*command, "shell", "run-as", PACKAGE, "test", "-f", path],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15).returncode == 0

    def microphone_off():
        state = shell("dumpsys", "activity", "service", SERVICE)
        assert "wakeCapture=true" not in state and "WakeTrialService{" not in state, "Stop Hey Friday before setup testing"
        assert "(nothing)" in state or "No services match" in state, "Wake service must stay stopped"

    def hierarchy():
        path = "/data/local/tmp/friday-wake-model-setup.xml"
        _, rows = helpers.fresh_hierarchy(lambda: shell("rm", "-f", path),
            lambda: shell("uiautomator", "dump", path), lambda: adb("exec-out", "cat", path))
        return rows

    def tap(label):
        node = helpers.control(hierarchy(), label)
        assert node is not None, f"Missing enabled control: {label}"
        x, y = tap_point(node)
        shell("input", "tap", str(x), str(y))

    def open_options():
        shell("cmd", "statusbar", "collapse")
        shell("am", "start", "-W", "--activity-clear-top", "-n", ACTIVITY)

    def screenshot(name):
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

    def downloaded():
        deadline = time.monotonic() + 35
        rows = []
        while time.monotonic() < deadline:
            rows = hierarchy()
            if helpers.control(rows, "Use Hey Friday") is not None:
                data = adb("exec-out", "run-as", PACKAGE, "cat", MODEL)
                assert approved(data), "Installed model differs from the pinned hash or size"
                microphone_off()
                return
            assert not any("Couldn't" in row.get("text", "") for row in rows), "Download reported an error"
            time.sleep(0.2)
        screenshot("download-failure")
        labels = [row.get("text") for row in rows if row.get("package") == PACKAGE and row.get("text")]
        raise AssertionError(f"The model download did not complete: {labels}")

    backups = {}
    report = {}
    keyguard = lock_helpers.parse_keyguard(shell("dumpsys", "window", "policy"))
    assert keyguard["showing"] is False, "Unlock the connected phone before running the setup check"
    microphone_off()
    for path in (MODEL, PREFERENCES):
        assert not exists(path + ".setup-backup"), f"Restore the earlier backup first: {path}"
    shell("am", "force-stop", PACKAGE)
    try:
        for path in (MODEL, PREFERENCES):
            backups[path] = exists(path)
            if backups[path]:
                shell("run-as", PACKAGE, "mv", path, path + ".setup-backup")
        open_options()
        assert helpers.checked_choice(hierarchy()) == ["Tap to speak"], "Fresh setup must default to Tap"
        tap("Hey Friday")
        assert helpers.control(hierarchy(), "Download Hey Friday") is not None
        microphone_off()
        screenshot("download-option")
        tap("Download Hey Friday")
        screenshot("download-in-progress")
        downloaded()
        screenshot("download-complete")
        report["firstDownloadVerifiedNoMicrophone"] = True
        tap("Use Hey Friday")
        assert helpers.control(hierarchy(), "Allow Hey Friday") is not None, "Enabling still needs explicit disclosure"
        screenshot("enable-disclosure")
        tap("Not now")
        microphone_off()
        report["downloadDoesNotReplaceConsent"] = True
        shell("am", "force-stop", PACKAGE)
        open_options()
        assert helpers.checked_choice(hierarchy()) == ["Tap to speak"], "Download must not save an enabled choice"
        tap("Hey Friday")
        assert helpers.control(hierarchy(), "Use Hey Friday") is not None, "Reopening must reuse the verified model"
        report["reopenUsesVerifiedModel"] = True

        shell("am", "force-stop", PACKAGE)
        adb("shell", f"run-as {PACKAGE} sh -c 'cat > {MODEL}'", data=b"invalid-test-model")
        open_options()
        tap("Hey Friday")
        assert helpers.control(hierarchy(), "Download Hey Friday") is not None, "Corrupt private data must offer a new download"
        screenshot("corrupt-model-retry")
        tap("Download Hey Friday")
        shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)
        microphone_off()
        parts = shell("run-as", PACKAGE, "ls", "-a", "files/wake-trial")
        assert not any(name.endswith(".part") for name in parts.splitlines()), "Leaving setup retained a partial file"
        data = adb("exec-out", "run-as", PACKAGE, "cat", MODEL)
        report["backDuringDownload"] = "Completed before Back" if approved(data) else "Cancelled before activation"
        open_options()
        tap("Hey Friday")
        if helpers.control(hierarchy(), "Download Hey Friday") is not None:
            tap("Download Hey Friday")
        downloaded()
        report["corruptionAndRetry"] = True
        screenshot("retry-complete")
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
    finally:
        shell("am", "force-stop", PACKAGE)
        for path, had_original in backups.items():
            if had_original:
                shell("run-as", PACKAGE, "mv", path + ".setup-backup", path)
            elif path == PREFERENCES and exists(path):
                shell("run-as", PACKAGE, "rm", path)
        microphone_off()


if __name__ == "__main__":
    main()
