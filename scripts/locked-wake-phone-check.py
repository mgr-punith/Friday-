#!/usr/bin/env python3
"""Check locked-screen debug wake with synthetic outcomes and no phone action effects.

Enable the opt-in command listener before running. The script never enables it,
unlocks the phone, changes credentials, or sends a deliberate cloud command.
"""

import argparse
import importlib.util
import json
import re
import subprocess
import time
from pathlib import Path

PACKAGE = "com.example.assistant"
SERVICE = PACKAGE + "/.debug.wake.WakeTrialService"
TEST_RECEIVER = PACKAGE + "/.debug.wake.WakeTrialTestReceiver"
TEST_ACTION = PACKAGE + ".debug.TRIGGER_WAKE_TEST"
SAFE_STATUS = {"generation", "phase", "commandMode", "threshold", "detections", "syntheticDetections",
               "openedCommands", "wakeCapture", "commandCapture", "visibleAssistants", "elapsedMs", "liveWakeAudioMs"}


def load_helpers(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def bool_field(data, *names):
    for name in names:
        match = re.search(r"(?m)^\s*" + re.escape(name) + r"\s*[:=]\s*(true|false)\b", data)
        if match:
            return match.group(1) == "true"
    return None


def parse_keyguard(data):
    lines = data.splitlines()
    block = ""
    for index, line in enumerate(lines):
        if "KeyguardServiceDelegate" not in line:
            continue
        indent = len(line) - len(line.lstrip())
        end = index + 1
        while end < len(lines) and (not lines[end].strip() or
                                   len(lines[end]) - len(lines[end].lstrip()) > indent):
            end += 1
        block = "\n".join(lines[index + 1:end])
        break
    showing = bool_field(block, "showing", "mIsShowing")
    secure = bool_field(block, "secure", "mSecure")
    return {
        "showing": showing if showing is not None else bool_field(data, "mKeyguardShowing", "isKeyguardShowing"),
        "secure": secure if secure is not None else bool_field(data, "mKeyguardSecure", "isKeyguardSecure"),
        "screenOff": re.search(r"\bscreenState\s*=\s*SCREEN_STATE_OFF\b", data) is not None,
    }


def assistant_focused(data):
    return any(re.search(r"\bmCurrentFocus\s*=", line) and
               PACKAGE in line and "AssistantActivity" in line for line in data.splitlines())


def listening(row, generation=None):
    return row is not None and row.get("phase") == "LISTENING" and row.get("wakeCapture") is True and \
        row.get("commandCapture") is False and row.get("commandMode") is True and \
        row.get("visibleAssistants") == 0 and (generation is None or row.get("generation") == generation)


def synthetic_locked_wake_arguments(outcome):
    if outcome not in {"lock-call", "lock-app", "lock-timer", "lock-sound"}:
        raise ValueError("A labeled lock-screen preview is required")
    return ["am", "broadcast", "--receiver-foreground", "-a", TEST_ACTION, "-n", TEST_RECEIVER,
            "--es", "debug_wake_outcome", outcome]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=Path("/tmp/friday-locked-wake-check"))
    parser.add_argument("--require-secure", action="store_true", help="Require an existing PIN/password/biometric keyguard")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial]
    usage = load_helpers("locked_wake_usage", "wake-usage-phone-check.py")
    wake = load_helpers("locked_wake_command", "wake-command-phone-check.py")
    report = {"synthetic": True, "recognitionMeasured": False, "screenOffWaitSeconds": 2,
              "realPhoneActionsExecuted": False, "scenarios": {}, "ok": False}
    initial = None
    hierarchy_path = "/data/local/tmp/friday-locked-wake.xml"

    def adb(*parts):
        result = subprocess.run([*command, *parts], capture_output=True, timeout=40)
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, parts)
        return result.stdout

    def shell(*parts):
        return adb("shell", *parts).decode(errors="replace").strip()

    def status():
        return wake.parse_status(shell("dumpsys", "activity", "service", SERVICE))

    def safe_status():
        row = status()
        return {key: value for key, value in (row or {}).items() if key in SAFE_STATUS} or None

    def keyguard():
        return parse_keyguard(shell("dumpsys", "window", "policy"))

    def focused():
        return assistant_focused(shell("dumpsys", "window", "displays"))

    def wait_for_focus():
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if focused():
                return True
            time.sleep(0.1)
        return False

    def locked(state):
        assert state["showing"] is True, "A showing keyguard could not be verified"
        if args.require_secure:
            assert state["secure"] is True, "A secure keyguard is required; the script never changes screen-lock setup"

    def wait(predicate, description, seconds=15):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            row = status()
            if predicate(row):
                return row
            time.sleep(0.15)
        raise AssertionError(description)

    def hierarchy():
        _, nodes = usage.fresh_hierarchy(lambda: shell("rm", "-f", hierarchy_path),
            lambda: shell("uiautomator", "dump", hierarchy_path), lambda: adb("exec-out", "cat", hierarchy_path))
        return nodes

    try:
        initial = status()
        assert listening(initial), "Start the opt-in Hey Friday command listener before running this check"
        report["initial"] = safe_status()
        for scenario in ("lock-call", "lock-app", "lock-timer", "lock-sound"):
            ready = wait(lambda row: listening(row, initial["generation"]), "Listener was not ready before " + scenario)
            assert isinstance(ready.get("liveWakeAudioMs"), (int, float)), "Install the debug build with live wake-audio timing before testing"
            shell("input", "keyevent", "KEYCODE_SLEEP")
            time.sleep(0.5)
            before_lock = keyguard()
            locked(before_lock)
            assert before_lock["screenOff"], "Screen-off state could not be verified"
            asleep = status()
            assert listening(asleep, initial["generation"]), "Wake capture stopped when the screen turned off"
            audio_start = asleep.get("liveWakeAudioMs")
            assert isinstance(audio_start, (int, float)), "Live wake-audio timing is unavailable"
            time.sleep(2)
            before = status()
            assert listening(before, initial["generation"]), "Wake capture stopped while the screen was off"
            after_wait_lock = keyguard()
            locked(after_wait_lock)
            assert after_wait_lock["screenOff"], "The screen did not remain off during the capture check"
            audio_end = before.get("liveWakeAudioMs")
            assert isinstance(audio_end, (int, float)) and audio_end > audio_start, "Wake microphone samples did not advance while the screen was off"
            result = {"screenOff": before_lock, "screenOffAfterWait": after_wait_lock, "synthetic": True,
                      "screenOffAudioProgressMs": audio_end - audio_start}
            report["scenarios"][scenario] = result
            shell(*synthetic_locked_wake_arguments(scenario))
            opened = wait(lambda row: row is not None and row.get("phase") == "PAUSED" and
                row.get("wakeCapture") is False and row.get("commandCapture") is False and
                row.get("visibleAssistants") == 1 and row.get("openedCommands") == before["openedCommands"] + 1,
                "Locked assistant did not open for " + scenario, seconds=20)
            assert opened.get("syntheticDetections") == before["syntheticDetections"] + 1, "Synthetic wake was not labeled"
            assert wait_for_focus(), "AssistantActivity is not the focused window"
            during_lock = keyguard()
            locked(during_lock)
            nodes = hierarchy()
            labels = {node.get(key, "") for node in nodes for key in ("text", "content-desc")}
            if scenario == "lock-app":
                assert "Private test app" not in labels, "A blocked command exposed its app name"
            expected = {"Start a phone call?", "Call 5550100 using this phone?", "Call", "Cancel"} \
                if scenario == "lock-call" else ({"Unlock to continue", "Unlock your phone, then ask again.", "Unlock phone"} \
                if scenario == "lock-app" else {"Preview: Lock-screen action allowed"})
            result["expectedCopyPresent"] = sorted(expected & labels)
            result["assistantUiPresent"] = any(node.get("package") == PACKAGE for node in nodes)
            assert expected <= labels, "Expected locked-screen outcome was not visible for " + scenario
            result.update({"assistantFocused": True, "keyguardWhileVisible": during_lock,
                           "expectedCopyVisible": True,
                           "wakeCaptureWhileVisible": opened["wakeCapture"]})
            if scenario == "lock-call":
                shell("input", "keyevent", "KEYCODE_BACK")
                time.sleep(0.3)
                result["callTargetDisplayed"] = True
                result["callConfirmationCancelled"] = True
            elif scenario == "lock-app":
                result["privateTargetHidden"] = True
            recovered = wait(lambda row: listening(row, initial["generation"]),
                             "Listener did not rearm after " + scenario, seconds=14)
            assert recovered["openedCommands"] == before["openedCommands"] + 1, "Unexpected extra assistant opening"
            after_lock = keyguard()
            locked(after_lock)
            result.update({"automaticRecovery": True, "keyguardAfterRecovery": after_lock})
            print("PASS:", scenario, flush=True)
        report["secureKeyguardVerified"] = all(r["screenOff"]["secure"] is True for r in report["scenarios"].values())
        if not report["secureKeyguardVerified"]:
            report["compatibilityLimitation"] = "PIN/password/biometric keyguard behavior was not verified; this phone uses an unsecured or unverified keyguard."
        report["ok"] = True
    except Exception as error:
        # Never print raw window dumps, UI text, notification data or ADB output.
        report["failure"] = {"type": type(error).__name__, "message": str(error)}
        try:
            report["failureService"] = safe_status()
            report["failureKeyguard"] = keyguard()
        except Exception:
            report["failureDiagnosticsUnavailable"] = True
        raise
    finally:
        if listening(initial):
            try:
                if focused():
                    shell("input", "keyevent", "KEYCODE_BACK")
                    report["cleanupDismissedPanel"] = True
                shell("input", "keyevent", "KEYCODE_SLEEP")
                wait(lambda row: listening(row, initial["generation"]), "Original listener did not remain active", seconds=10)
                report["listenerLeftActive"] = True
            except Exception:
                report["listenerLeftActive"] = False
                report["ok"] = False
        try:
            shell("rm", "-f", hierarchy_path)
        except Exception:
            pass
        (args.output / "report.json").write_text(json.dumps(report, indent=2))
    assert report["ok"], "Listener cleanup failed; inspect report.json"
    print("Synthetic locked-wake checks passed. Report:", args.output / "report.json", flush=True)


if __name__ == "__main__":
    main()
