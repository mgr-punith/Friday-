#!/usr/bin/env python3
"""Side-load the approved model into a debuggable Friday install, outside its APK."""

import argparse
import hashlib
import shlex
import subprocess
from pathlib import Path

MODEL_BYTES = 60_832
MODEL_SHA256 = "04aaffa5f111704db18ec593251a3dfbb009638009e081c9c87830755c3563a6"
MODEL_PATH = "files/wake-trial/hey_friday.tflite"
PACKAGE = "com.example.assistant"


def validate_model(data):
    if len(data) != MODEL_BYTES or hashlib.sha256(data).hexdigest() != MODEL_SHA256:
        raise ValueError("Model size or SHA-256 differs from the approved Hey Friday model")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    try:
        if args.model.stat().st_size != MODEL_BYTES:
            raise ValueError("Unexpected model size")
        data = args.model.read_bytes()
        validate_model(data)
        command = [args.adb, "-s", args.serial]
        # run-as fails for release APKs. No storage permission or shared directory is needed.
        subprocess.run([*command, "shell", "run-as", PACKAGE, "pwd"], check=True, capture_output=True, timeout=15)
        script = ("umask 077 && mkdir -p files/wake-trial && chmod 700 files/wake-trial && "
                  "cat > files/wake-trial/model.pending && chmod 600 files/wake-trial/model.pending && "
                  "mv files/wake-trial/model.pending " + MODEL_PATH)
        remote = shlex.join(["run-as", PACKAGE, "sh", "-c", script])
        subprocess.run([*command, "shell", "-T", remote], input=data, check=True, capture_output=True, timeout=30)
        copied = subprocess.check_output([*command, "exec-out", "run-as", PACKAGE, "cat", MODEL_PATH], timeout=15)
        validate_model(copied)
        print(f"Installed and hash-verified {len(copied)} model bytes in debug app-private storage.")
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
