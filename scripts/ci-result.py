#!/usr/bin/env python3
"""Fail the required CI status when a selected job fails, cancels or gets skipped."""

import os


def failures(selection_result, selected, results):
    if selection_result != "success":
        return ["CI change selection failed"]
    errors = []
    for suite in ("TOOLING", "BACKEND", "SAM", "ANDROID"):
        enabled = selected.get(suite)
        result = results.get(suite)
        expected = "success" if enabled == "true" else "skipped"
        if enabled not in {"true", "false"} or result != expected:
            errors.append(f"{suite}: expected {expected}, got {result}")
    return errors


if __name__ == "__main__":
    suites = ("TOOLING", "BACKEND", "SAM", "ANDROID")
    errors = failures(os.environ.get("CHANGES_RESULT"),
                      {s: os.environ.get(s + "_SELECTED") for s in suites},
                      {s: os.environ.get(s + "_RESULT") for s in suites})
    for error in errors:
        print(error)
    if errors:
        raise SystemExit(1)
    print("All selected checks passed")
