#!/usr/bin/env python3
"""Check the stopped debug trial at 200% text in portrait and landscape; restore settings."""

import argparse
import re
import struct
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, default=Path("/tmp/friday-wake-trial-check/layout"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    command = [args.adb, "-s", args.serial]
    package = "com.example.assistant"

    def adb(*parts):
        return subprocess.check_output([*command, *parts], timeout=40)

    def shell(*parts):
        return adb("shell", *parts).decode().strip()

    def settings(key, value):
        if value == "null":
            shell("settings", "delete", "system", key)
        else:
            shell("settings", "put", "system", key, value)

    original = {key: shell("settings", "get", "system", key)
                for key in ("font_scale", "accelerometer_rotation", "user_rotation")}
    try:
        settings("font_scale", "2.0")
        settings("accelerometer_rotation", "0")
        density = int(re.findall(r"density:\s*(\d+)", shell("wm", "density"))[-1]) / 160
        for rotation, name in (("0", "portrait"), ("1", "landscape")):
            settings("user_rotation", rotation)
            time.sleep(1)
            shell("am", "start", "-W", "--activity-clear-top", "-n", package + "/.debug.wake.WakeTrialActivity")
            found = False
            observed = []
            for _ in range(12):
                screenshot = adb("exec-out", "screencap", "-p")
                width, height = struct.unpack(">II", screenshot[16:24])
                shell("rm", "-f", "/data/local/tmp/friday-wake-layout.xml")
                shell("uiautomator", "dump", "/data/local/tmp/friday-wake-layout.xml")
                xml = adb("exec-out", "cat", "/data/local/tmp/friday-wake-layout.xml")
                nodes = list(ElementTree.fromstring(xml).iter("node"))
                (args.output / (name + "-latest.png")).write_bytes(screenshot)
                (args.output / (name + "-latest.xml")).write_bytes(xml)
                node = next((node for node in nodes if node.get("package") == package and node.get("text") == "Start local test"), None)
                if node is not None:
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
                    observed.append({"bounds": [x1, y1, x2, y2], "screen": [width, height], "density": density})
                    if x2 - x1 >= density * 48 - 2 and y2 - y1 >= density * 48 - 2 and y2 < height - density * 24:
                        assert node.get("enabled") == "true", "The stopped test must have an accessible Start button"
                        (args.output / (name + "-large-text.png")).write_bytes(screenshot)
                        (args.output / (name + "-large-text.xml")).write_bytes(xml)
                        found = True
                        break
                # Slow short drags avoid flinging past the control at large text sizes.
                shell("input", "swipe", str(width // 2), str(int(height * 0.75)), str(width // 2), str(int(height * 0.55)), "1000")
            assert found, f"Cannot reach a full 48dp Start button in {name} at 200% text: {observed}"
            print(f"PASS: {name}, 200% text, reachable enabled Start control >=48dp", flush=True)
    finally:
        for key, value in original.items():
            settings(key, value)
        shell("rm", "-f", "/data/local/tmp/friday-wake-layout.xml")
        shell("am", "start", "-W", "--activity-clear-top", "-n", package + "/.debug.wake.WakeTrialActivity")


if __name__ == "__main__":
    main()
