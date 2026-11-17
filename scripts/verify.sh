#!/usr/bin/env bash
set -euo pipefail

friday_repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$friday_repo_dir"

./gradlew assembleDebug assembleRelease test lintDebug --console=plain "$@"

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
