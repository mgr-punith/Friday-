#!/usr/bin/env python3
"""Check that Android and backend capability metadata match the canonical contract."""

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "scripts/action-capabilities.json"
BACKEND = ROOT / "backend/src/core/capabilities.ts"
ANDROID = ROOT / "app/src/main/kotlin/com/puneet/friday/actions/PhoneCapabilityRegistry.kt"


def violations(contract_text: str, backend_text: str, android_text: str) -> list[str]:
    failures: list[str] = []
    try:
        value = json.loads(contract_text)
    except json.JSONDecodeError:
        return ["Action capability contract is not valid JSON"]
    if not isinstance(value, dict) or set(value) != {"protocolVersion", "capabilities"}:
        return ["Action capability contract has unexpected top-level fields"]
    version = value.get("protocolVersion")
    capabilities = value.get("capabilities")
    if not isinstance(version, int) or version < 1 or not isinstance(capabilities, list):
        return ["Action capability contract has an invalid version or capability list"]

    expected: dict[str, tuple[int, str, bool]] = {}
    for item in capabilities:
        if not isinstance(item, dict) or set(item) != {
            "id", "minimumVersion", "sequence", "requiresUnlock"
        }:
            failures.append("Action capability contract has an invalid capability entry")
            continue
        capability_id = item.get("id")
        minimum = item.get("minimumVersion")
        sequence = item.get("sequence")
        requires_unlock = item.get("requiresUnlock")
        if (
            not isinstance(capability_id, str)
            or not re.fullmatch(r"[a-z][a-z0-9_]*", capability_id)
            or not isinstance(minimum, int)
            or minimum < 1
            or minimum > version
            or sequence not in {"preparatory", "terminal"}
            or not isinstance(requires_unlock, bool)
        ):
            failures.append("Action capability contract has an invalid capability value")
            continue
        if capability_id in expected:
            failures.append(f"Duplicate action capability: {capability_id}")
            continue
        expected[capability_id] = (minimum, sequence, requires_unlock)

    backend_version = re.search(r"LATEST_ACTION_PROTOCOL_VERSION\s*=\s*(\d+)", backend_text)
    android_version = re.search(r"ACTION_PROTOCOL_VERSION\s*=\s*(\d+)", android_text)
    if backend_version is None or int(backend_version.group(1)) != version:
        failures.append("Backend action protocol version differs from the canonical contract")
    if android_version is None or int(android_version.group(1)) != version:
        failures.append("Android action protocol version differs from the canonical contract")

    backend = {
        match.group(1): (int(match.group(2)), match.group(3))
        for match in re.finditer(
            r'^\s{2}([a-z][a-z0-9_]*): \{ minimumVersion: (\d+), '
            r'sequencePosition: "(preparatory|terminal)" \},?$',
            backend_text,
            re.MULTILINE,
        )
    }
    android = {
        match.group(1): (
            int(match.group(2)),
            match.group(4).lower(),
            match.group(3) == "true",
        )
        for match in re.finditer(
            r'^\s+(?:is\s+)?PhoneAction\.[A-Za-z]+\s+->\s+Capability\('
            r'"([a-z][a-z0-9_]*)",\s*(\d+),\s*(true|false),\s*'
            r'SequencePosition\.(PREPARATORY|TERMINAL)\)',
            android_text,
            re.MULTILINE,
        )
    }

    expected_backend = {key: (item[0], item[1]) for key, item in expected.items()}
    if backend != expected_backend:
        failures.append("Backend capability metadata differs from scripts/action-capabilities.json")
    if android != expected:
        failures.append("Android capability metadata differs from scripts/action-capabilities.json")
    return failures


def main() -> int:
    failures = violations(
        CONTRACT.read_text(encoding="utf-8"),
        BACKEND.read_text(encoding="utf-8"),
        ANDROID.read_text(encoding="utf-8"),
    )
    for failure in failures:
        print(failure)
    if failures:
        return 1
    print("Action capability contract passed: Android and backend metadata match")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
