#!/usr/bin/env python3
"""Check local Chrome resolution through Friday's Phone actions screen."""

import argparse
import re
import subprocess
import time
from xml.etree import ElementTree


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()

    def adb(*command):
        return subprocess.check_output(
            [args.adb, "-s", args.serial, *command], timeout=40, text=True
        )

    def shell(*command):
        return adb("shell", *command)

    def hierarchy():
        shell("uiautomator", "dump", "/data/local/tmp/friday-app-lookup.xml")
        xml = adb("exec-out", "cat", "/data/local/tmp/friday-app-lookup.xml")
        return list(ElementTree.fromstring(xml).iter("node"))

    def has_label(node, label):
        return (node.get("text") == label or
                node.get("content-desc", "").split("\n")[0].startswith(label))

    def tap(rows, label):
        candidates = [row for row in rows if has_label(row, label)]
        assert candidates, f"Missing control: {label}"
        node = min(candidates, key=lambda row: row.get("clickable") != "true")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        assert x2 > x1 and y2 > y1, f"Offscreen control: {label}"
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        time.sleep(0.35)

    def home():
        shell("am", "start", "-W", "--activity-clear-top", "-n",
              "com.example.assistant/.MainActivity")
        rows = hierarchy()
        if any(has_label(node, "Home") and node.get("selected") != "true" for node in rows):
            tap(rows, "Home")

    def open_app(target):
        home()
        tap(hierarchy(), "Settings")
        rows = hierarchy()
        if not any(has_label(node, "Try a phone action") for node in rows):
            shell("input", "swipe", "800", "1770", "800", "750", "300")
            rows = hierarchy()
        tap(rows, "Try a phone action")
        tap(hierarchy(), "Open an app")
        field = next(node for node in hierarchy()
                     if node.get("class") == "android.widget.EditText")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", field.get("bounds")))
        shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        shell("input", "keycombination", "113", "29")
        shell("input", "keyevent", "67")
        shell("input", "text", target.replace(" ", "%s"))
        entered = [node.get("text", "").casefold() for node in hierarchy()
                   if node.get("class") == "android.widget.EditText"]
        # Some keyboards capitalize the words in Friday's name field.
        assert target.casefold() in entered, f"Failed to enter {target}"
        tap(hierarchy(), "Open app")
        time.sleep(1)
        state = shell("dumpsys", "activity", "activities")
        active = next((line for line in state.splitlines()
                       if "topResumedActivity=" in line or "mResumedActivity:" in line), "")
        return "com.android.chrome/" in active

    assert "package:com.android.chrome" in shell("pm", "list", "packages", "com.android.chrome").splitlines(), "Chrome must be installed"
    try:
        for target in ("Google Chrome", "Open the Google Chrome app please"):
            assert open_app(target), f"{target} did not launch Chrome"
            print("PASS:", target, "launched com.android.chrome", flush=True)
        # The absent variant must not launch a different Chrome product.
        if "package:com.chrome.beta" not in shell("pm", "list", "packages", "com.chrome.beta").splitlines():
            assert not open_app("Google Chrome Beta"), "Chrome Beta opened stable Chrome"
            assert any(node.get("text") == "I couldn't find that app" for node in hierarchy())
            print("PASS: missing Chrome Beta shows app-not-found feedback", flush=True)
        else:
            print("SKIP: missing-Beta check because Chrome Beta is installed", flush=True)
    finally:
        home()


if __name__ == "__main__":
    main()
