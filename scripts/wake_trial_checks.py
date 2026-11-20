"""Packaging guards for the opt-in debug wake-detector experiment."""

from xml.etree import ElementTree
from zipfile import ZipFile

ANDROID = "{http://schemas.android.com/apk/res/android}"
MODEL_EXTENSIONS = (".tflite", ".onnx", ".pcm", ".wav")


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
                    assert b"com/example/assistant/debug/wake/" not in archive.read(name), \
                        "Release includes compiled trial code"
        if require_runtime:
            assert runtimes == ["lib/arm64-v8a/libmicrowakeword.so"], "Expected the approved ARM64 debug runtime"
