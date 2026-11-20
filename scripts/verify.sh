#!/usr/bin/env bash
set -euo pipefail

friday_repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$friday_repo_dir"

python3 -m unittest discover -s scripts -p 'test_*.py'

if rg -n --glob '*.{kt,java,kts,xml}' \
    'android\.speech\.SpeechRecognizer|com\.google\.android\.gms|com\.google\.firebase|com\.google\.mlkit|software\.amazon\.awssdk|com\.amazonaws|material-icons-extended' \
    app/src app/build.gradle.kts; then
    printf '%s\n' 'The Android app contains a forbidden device dependency or API.' >&2
    exit 1
fi

if rg -n 'unsupportedDialog|"Feature not built yet"' app/src/main; then
    printf '%s\n' 'Unsupported requests must stay inside the main screen.' >&2
    exit 1
fi

./gradlew assembleDebug assembleRelease test lintDebug --console=plain "$@"

python3 scripts/check-release-manifest.py

# A recording import target belongs only in debug builds.
if rg -l 'AudioEvalImportActivity|Friday audio test' \
    app/build/intermediates/merged_manifest/release \
    app/build/intermediates/merged_manifests/release; then
    printf '%s\n' 'The release manifest includes the debug recording importer.' >&2
    exit 1
fi

shopt -s nullglob
friday_release_apks=(app/build/outputs/apk/release/*.apk)
if (( ${#friday_release_apks[@]} == 0 )); then
    printf '%s\n' 'No release APK found.' >&2
    exit 1
fi

for friday_apk in "${friday_release_apks[@]}"; do
    friday_apk_bytes="$(stat -c '%s' -- "$friday_apk")"
    printf '%s: %s bytes\n' "$friday_apk" "$friday_apk_bytes"
    if (( friday_apk_bytes > 15000000 )); then
        printf '%s\n' 'Release APK exceeds the 15 MB limit.' >&2
        exit 1
    fi
done
