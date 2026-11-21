#!/usr/bin/env python3
"""Audit the approved Google-login exception without allowing unrelated device SDKs."""

import argparse
import re
from pathlib import Path


APPROVED_GMS = {
    "play-services-auth", "play-services-auth-api-phone", "play-services-auth-base",
    "play-services-auth-blockstore", "play-services-base", "play-services-basement",
    "play-services-fido", "play-services-identity-credentials", "play-services-tasks",
}
REQUIRED = {
    "androidx.credentials:credentials:1.6.0",
    "androidx.credentials:credentials-play-services-auth:1.6.0",
    "com.google.android.libraries.identity.googleid:googleid:1.1.1",
}


def violations(report):
    failures = []
    if not re.search(r"releaseRuntimeClasspath\b", report):
        failures.append("Release runtime dependency report missing")
    if re.search(r"\b(?:FAILED|Could not resolve|Could not find)\b", report):
        failures.append("Dependency resolution failed")
    for group, artifact in re.findall(r"([\w.\-]+):([\w.\-]+):[^\s]+", report):
        if group == "com.google.android.gms" and artifact not in APPROVED_GMS:
            failures.append(f"Unapproved Google module: {group}:{artifact}")
        if group.startswith(("com.google.firebase", "com.google.mlkit", "software.amazon.awssdk", "com.amazonaws")) or "mlkit" in artifact.lower() or artifact == "material-icons-extended":
            failures.append(f"Forbidden app module: {group}:{artifact}")
    # Check the root declarations, not an older version referenced by a transitive edge.
    for coordinate in REQUIRED:
        if not re.search(r"^[+\\]--- " + re.escape(coordinate) + r"(?: \(\*\))?\s*$", report, re.MULTILINE):
            failures.append("Approved login dependency changed: " + coordinate)
    if "googleid:1.2.1" in report:
        failures.append("Google ID 1.2.1 requires newer Kotlin metadata than this build supports")
    return sorted(set(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    failures = violations(args.report.read_text(encoding="utf-8"))
    for failure in failures:
        print(failure)
    if failures:
        return 1
    print("App dependency boundary passed: approved Google login only; no Firebase, ML Kit or AWS SDK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
