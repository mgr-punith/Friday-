#!/usr/bin/env python3
"""Select related CI suites from a Git diff. Unknown paths run the full suite."""

import argparse
import json
import os
import re
import subprocess
from pathlib import PurePosixPath


SUITES = ("android", "backend", "sam", "tooling")


def select_checks(paths, force=False):
    selected = {name: bool(force) for name in SUITES}
    for name in paths:
        path = PurePosixPath(name)
        if name.startswith(".github/") or name in {"scripts/ci-changes.py", "scripts/test_ci_changes.py", "scripts/ci-result.py", "scripts/test_ci_result.py", "scripts/verify.sh"}:
            return dict.fromkeys(SUITES, True)
        # These files do not enter an APK, backend bundle or deployment template.
        if path.suffix.lower() == ".md" or name in {"LICENSE", ".gitignore", ".gitattributes", ".editorconfig"} or name.startswith(".agents/"):
            continue
        if name.startswith("app/") or name.startswith("gradle/") or name in {"gradlew", "gradlew.bat", "gradle.properties", "build.gradle.kts", "settings.gradle.kts"}:
            selected["android"] = True
        elif name.startswith("backend/"):
            selected["backend"] = True
            # Vercel-only auth/Redis changes do not need the AWS artifact rebuilt.
            if not name.startswith(("backend/src/auth/", "backend/src/vercel/", "backend/src/env/", "backend/src/redis/", "backend/test/", "backend/eval/", "backend/public/", "backend/api/")) and name not in {"backend/vercel.json", "backend/.env.example", "backend/.env.eval.example"}:
                selected["sam"] = True
        elif name.startswith(("scripts/", ".githooks/")):
            selected["tooling"] = True
            if name in {"scripts/check-secrets.py", "scripts/test_check_secrets.py"} or name.startswith(".githooks/"):
                return dict.fromkeys(SUITES, True)
            if name in {"scripts/check-app-dependencies.py", "scripts/check-release-manifest.py"}:
                selected["android"] = True
        elif name.startswith("design/") and path.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".svg", ".mp4"}:
            continue
        else:
            return dict.fromkeys(SUITES, True)
    return selected


def changed_paths(base, head, merge_base=False):
    if not base or not re.fullmatch(r"[0-9a-fA-F]{40,64}", base) or set(base) == {"0"}:
        return None
    if not re.fullmatch(r"[0-9a-fA-F]{40,64}", head):
        return None
    try:
        if merge_base:
            base = subprocess.check_output(["git", "merge-base", base, head], stderr=subprocess.PIPE).decode().strip()
        # No rename detection: include both old and new locations, plus deletions.
        result = subprocess.run(["git", "diff", "--name-only", "--no-renames", "-z", base, head, "--"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return [os.fsdecode(value) for value in result.stdout.split(b"\0") if value]
    except (OSError, subprocess.SubprocessError):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.environ.get("CI_BASE_SHA", ""))
    parser.add_argument("--head", default=os.environ.get("CI_HEAD_SHA", ""))
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--merge-base", action="store_true")
    args = parser.parse_args()
    paths = changed_paths(args.base, args.head, args.merge_base)
    selected = select_checks(paths or [], force=args.force or paths is None)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for name, enabled in selected.items():
                output.write(f"{name}={str(enabled).lower()}\n")
    print(json.dumps({"checks": selected, "changedFiles": len(paths) if paths is not None else None, "fallbackToFull": paths is None}))


if __name__ == "__main__":
    main()
