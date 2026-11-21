"""Packaging guards for the opt-in debug wake-detector experiment."""

from xml.etree import ElementTree
from zipfile import ZipFile
import re

ANDROID = "{http://schemas.android.com/apk/res/android}"
MODEL_EXTENSIONS = (".tflite", ".onnx", ".pcm", ".wav")
JNI_SYMBOL_PREFIX = b"Java_com_puneet_friday_debug_wake_MicroWakeWordNative_"


def check_signing_certificate(output, expected_sha1):
    digests = {match.group(1).lower() for line in output.splitlines()
               if (match := re.search(r"\bcertificate SHA-1 digest:\s*([0-9a-fA-F]{40})\s*$", line))}
    assert digests == {expected_sha1.replace(":", "").lower()}, "APK signing certificate does not match"


def check_release_manifest(xml):
    root = ElementTree.fromstring(xml)
    permissions = {node.get(ANDROID + "name", "") for node in root.findall("uses-permission")}
    assert not any(name.startswith("android.permission.FOREGROUND_SERVICE") for name in permissions), \
        "Release includes debug foreground-service permissions"
    application = root.find("application")
    assert application is not None
    assert not any(".debug.wake." in item.get(ANDROID + "name", "") for item in application), \
        "Release includes a wake-trial component"


def check_apk(path, release=True, require_runtime=False):
    with ZipFile(path) as archive:
        names = archive.namelist()
        assert not any(name.lower().endswith(MODEL_EXTENSIONS) for name in names), \
            "APK includes an audio model or test recording"
        runtimes = [name for name in names if "microwakeword" in name.lower() and name.endswith(".so")]
        if release:
            assert not runtimes, "Release includes the trial runtime"
            assert not any("wake-trial" in name for name in names), "Release includes trial assets"
            for name in names:
                if name.endswith(".dex"):
                    assert b"com/puneet/friday/debug/wake/" not in archive.read(name), \
                        "Release includes compiled trial code"
        if require_runtime:
            assert runtimes == ["lib/arm64-v8a/libmicrowakeword.so"], "Expected the approved ARM64 debug runtime"
            library = archive.read(runtimes[0])
            for method in (b"create", b"process", b"destroy"):
                assert JNI_SYMBOL_PREFIX + method + b"\0" in library, \
                    "Wake runtime does not match the app's JNI bridge; rebuild it for this package"
