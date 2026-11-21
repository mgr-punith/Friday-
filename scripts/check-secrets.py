#!/usr/bin/env python3
"""Reject credential patterns without printing matched values. Uses only the standard library."""

import argparse
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath


PATTERNS = {
    "Google API key": rb"\bAIza[0-9A-Za-z_-]{35}\b",
    "Google OAuth client secret": rb"\bGOCSPX-[0-9A-Za-z_-]{20,}\b",
    "Groq API key": rb"\bgsk_[A-Za-z0-9]{40,}\b",
    "GitHub token": rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b",
    "AWS access key ID": rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
    "Private key": rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----",
    "Signed JWT": rb"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{20,}\b",
}
KEY = (rb"(?:GEMINI_API_KEY|GROQ_API_KEY|JWT_SIGNING_KEY|GOOGLE_WEB_CLIENT_SECRET|UPSTASH_[A-Z0-9_]*TOKEN|"
       rb"(?:KV_REST_API|REDIS_REST_API|GITHUB|GH|VERCEL)_TOKEN|"
       rb"AWS_(?:ACCESS_KEY_ID|SECRET_ACCESS_KEY|SESSION_TOKEN))")
QUOTED_ASSIGNMENT = re.compile(rb"(?i)(?<![A-Za-z0-9_])[\"']?" + KEY +
                               rb"[\"']?\s*[:=]\s*([\"'])([^\"'\r\n]+)\1")
ENV_ASSIGNMENT = re.compile(rb"(?im)^\s*(?:export\s+)?" + KEY +
                            rb"\s*=\s*([A-Za-z0-9+/_.:=@%-]{16,})\s*(?:#.*)?$")
DATABASE_URL = re.compile(rb"\b(?:rediss?|postgres(?:ql)?|mysql|mongodb(?:\+srv)?)://[^\s:/@]+:([^\s/@]+)@")
# Exact dummy values from existing mock configurations. No file-wide exclusions.
# These hashes exempt only the generic literal rule, never provider-key patterns.
FIXTURE_HASHES = {
    "backend/scripts/check-vercel-entry.mjs": {
        "7a51d064a1a216a692f753fcdab276e4ff201a01d8b66f56d50d4d719fd0dc87",
        "e1b85b27d6bcb05846c18e6a48f118e89f0c0587140de9fb3359f8370d0dba08",
    },
    "backend/test/config.test.ts": {
        "1489a0966c0dce4c46001b77258d9b7cfa278e2525a5369ee1ffab166862f6a4",
        "2e1e9876dde33723454fd06f66a5b4689912169d06603e9c9ba8ba52112c55cf",
        "6e75134048813f8c21f559dede1189da052fa840e0a1fbfd968b915861da58d5",
        "182f1185f3da6ce4e8f840b4aba368dfe676a2fc76c7577aab1126926e7440a5",
        "297427e0e50786da6717d6e183e2c5c2b3d67071cd09e87057dacaaead93b58c",
    },
}


def placeholder(value):
    value = value.strip(b"\"'").lower()
    return (value.startswith((b"replace-with-", b"your-", b"example-", b"${", b"$(")) or
            re.fullmatch(rb"process\.env\.[a-z0-9_]+", value) is not None or
            (value.startswith(b"<") and value.endswith(b">")) or
            value.endswith((b"-for-tests", b"-for-testing")))


def private_path(name):
    path = PurePosixPath(name)
    return (any(part in {".aws", ".ssh", ".vercel"} for part in path.parts) or
            (path.name.startswith(".env") and path.name not in {".env.example", ".env.eval.example"}) or
            path.name in {"keystore.properties", "signing.properties", ".netrc"} or
            path.suffix.lower() in {".pem", ".key", ".jks", ".keystore", ".p12", ".pfx"})


def scan_bytes(name, data):
    findings = set()
    if private_path(name):
        findings.add((name, 1, "Private configuration or key file"))
    for label, pattern in PATTERNS.items():
        for match in re.finditer(pattern, data):
            findings.add((name, data.count(b"\n", 0, match.start()) + 1, label))
    for pattern, group in ((QUOTED_ASSIGNMENT, 2), (ENV_ASSIGNMENT, 1), (DATABASE_URL, 1)):
        for match in pattern.finditer(data):
            value = match.group(group).strip(b"\"'")
            fixture = hashlib.sha256(value).hexdigest() in FIXTURE_HASHES.get(name, set())
            if len(value) >= 16 and not placeholder(value) and not fixture:
                findings.add((name, data.count(b"\n", 0, match.start()) + 1, "Credential literal"))
    return findings


def git(*parts):
    return subprocess.check_output(["git", *parts], stderr=subprocess.PIPE)


def git_blobs(objects):
    # Read binary blobs by length; filenames and blob contents can contain newlines.
    payload = b"".join(oid + b"\n" for oid, _ in objects)
    result = subprocess.run(["git", "cat-file", "--batch"], input=payload,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    offset = 0
    for oid, name in objects:
        end = result.stdout.index(b"\n", offset)
        header = result.stdout[offset:end].split()
        if len(header) != 3 or header[0] != oid:
            raise ValueError("Cannot read a Git object")
        size = int(header[2])
        offset = end + 1
        data = result.stdout[offset:offset + size]
        offset += size + 1
        if header[1] == b"blob":
            yield name, data


def history_objects(refs):
    objects = set()
    # Visit tree entries as well as unique contents: identical bytes under a private
    # filename still need to fail, even if Git first saw that blob as a safe example.
    for commit in git("rev-list", *refs, "--").splitlines():
        for item in git("ls-tree", "--full-tree", "-r", "-z", os.fsdecode(commit)).split(b"\0"):
            if item:
                info, name = item.split(b"\t", 1)
                _, kind, oid = info.split()
                if kind == b"blob":
                    objects.add((oid, os.fsdecode(name)))
    return sorted(objects)


def staged_objects():
    objects = []
    for item in git("ls-files", "--stage", "-z").split(b"\0"):
        if not item:
            continue
        info, name = item.split(b"\t", 1)
        mode, oid, stage = info.split()
        if stage != b"0":
            raise ValueError("Resolve the index conflict before scanning")
        if mode != b"160000":
            objects.append((oid, os.fsdecode(name)))
    return objects


def worktree_files():
    for name in git("ls-files", "--cached", "--others", "--exclude-standard", "-z").split(b"\0"):
        if not name:
            continue
        path = Path(os.fsdecode(name))
        if path.is_symlink():
            yield str(path), os.fsencode(os.readlink(path))
        elif path.is_file():
            yield str(path), path.read_bytes()


def push_refs(lines):
    refs = []
    for line in lines:
        fields = line.split()
        if len(fields) != 4 or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", fields[1]):
            raise ValueError("Invalid pre-push update")
        if set(fields[1]) != {"0"}:
            refs.append(fields[1])
    return refs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--staged", action="store_true", help="Scan the full Git index before a commit")
    mode.add_argument("--history", nargs="+", metavar="REF", help="Scan blobs reachable from these commits")
    mode.add_argument("--pre-push", action="store_true", help="Read Git push updates from standard input")
    args = parser.parse_args()
    try:
        os.chdir(os.fsdecode(git("rev-parse", "--show-toplevel")).strip())
        refs = push_refs(sys.stdin) if args.pre_push else args.history
        if args.pre_push and not refs:
            return 0
        files = git_blobs(history_objects(refs)) if refs else git_blobs(staged_objects()) if args.staged else worktree_files()
        findings = set()
        count = 0
        for name, data in files:
            count += 1
            findings.update(scan_bytes(name, data))
        if findings:
            print("Credential check failed. File locations only; matched values are hidden.", file=sys.stderr)
            for name, line, label in sorted(findings):
                print(f"{name!r}:{line}: {label}", file=sys.stderr)
            return 1
        print(f"Credential check passed: {count} file versions scanned.")
        return 0
    except (OSError, ValueError, subprocess.SubprocessError):
        print("Credential check could not finish. Commit/push blocked; no file contents printed.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
