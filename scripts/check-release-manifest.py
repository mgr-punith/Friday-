#!/usr/bin/env python3
"""Keep developer UI launch hooks private in release builds."""
from pathlib import Path
from xml.etree import ElementTree
from wake_trial_checks import check_apk, check_release_manifest

manifest_files = list(Path("app/build/intermediates").glob("merged_manifest/release/**/AndroidManifest.xml"))
manifest_files += list(Path("app/build/intermediates").glob("merged_manifests/release/**/AndroidManifest.xml"))
assert manifest_files, "Missing merged release manifest"
android = "{http://schemas.android.com/apk/res/android}"
for path in manifest_files:
    check_release_manifest(path.read_text())
    application = ElementTree.parse(path).getroot().find("application")
    activities = {item.get(android + "name"): item for item in application.findall("activity")}
    for name in ("AssistantActivity", "PermissionsActivity"):
        item = activities.get("com.puneet.friday." + name)
        assert item is not None, f"Missing {name}"
        assert item.get(android + "exported") == "false", f"{name} exposes a private release entry point"
    assert activities["com.puneet.friday.MainActivity"].get(android + "enableOnBackInvokedCallback") == "true", "Launcher must enable native predictive Back"
    assert "com.puneet.friday.SettingsActivity" not in activities, "Settings must share the launcher Activity"
    assert not any("AudioEvalImport" in name for name in activities), "Release includes recording importer"
for apk in Path("app/build/outputs/apk/release").glob("*.apk"):
    check_apk(apk)
print("Release assistant entry points stay private")
