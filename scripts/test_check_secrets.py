"""Credential guard tests use constructed dummy strings and temporary Git repositories."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-secrets.py").resolve()
SPEC = importlib.util.spec_from_file_location("secret_check", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class CredentialPatternsTests(unittest.TestCase):
    def test_provider_keys_and_private_keys(self):
        values = [b"AIza" + b"a" * 35, b"gsk_" + b"a" * 48, b"ghp_" + b"a" * 36,
                  b"github_pat_" + b"a" * 60, b"AKIA" + b"A" * 16,
                  b"-----BEGIN " + b"OPENSSH PRIVATE KEY-----", b"GOCSPX-" + b"A" * 28]
        for value in values:
            with self.subTest(length=len(value)):
                self.assertTrue(CHECK.scan_bytes("source.txt", value))

    def test_sensitive_literals_and_database_passwords(self):
        value = b"synthetic" + b"9bJ0k2L4m6N8p0Q2r4T6v8X0"
        assignments = [b'GEMINI_API_KEY="' + value + b'"', b"AWS_SECRET_ACCESS_KEY=" + value,
                       b'export GROQ_API_KEY=' + value,
                       b'{"UPSTASH_REDIS_REST_KV_REST_API_TOKEN": "' + value + b'"}',
                       b'GOOGLE_WEB_CLIENT_SECRET="' + value + b'"',
                       b'rediss://default:' + value + b'@example.com']
        for data in assignments:
            self.assertTrue(CHECK.scan_bytes("config.txt", data))

    def test_signed_jwt(self):
        token = b"eyJ" + b"a" * 20 + b"." + b"b" * 20 + b"." + b"c" * 30
        self.assertTrue(CHECK.scan_bytes("source.txt", token))

    def test_examples_and_runtime_lookups(self):
        self.assertFalse(CHECK.scan_bytes("backend/.env.example", b"GEMINI_API_KEY=replace-with-a-key"))
        self.assertFalse(CHECK.scan_bytes("source.py", b'GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")'))
        self.assertFalse(CHECK.scan_bytes("source.ts", b"GEMINI_API_KEY = process.env.GEMINI_API_KEY"))
        self.assertFalse(CHECK.scan_bytes("backend/.env.example", b"GOOGLE_WEB_CLIENT_SECRET=replace-with-browser-client-secret"))
        self.assertFalse(CHECK.scan_bytes("source.ts", b'GOOGLE_WEB_CLIENT_ID="123456789-test.apps.googleusercontent.com"'))

    def test_private_filenames_and_identical_example_contents(self):
        for name in ("backend/.env", "backend/.env.production", ".aws/credentials", "upload.jks", "private.pem"):
            self.assertTrue(CHECK.scan_bytes(name, b"replace-with-a-key"))
        self.assertFalse(CHECK.scan_bytes("backend/.env.eval.example", b"replace-with-a-key"))

    def test_fixture_exception_is_exact_and_limited_to_one_file(self):
        value = (b"1234567890" * 2)[:16]
        data = b'GEMINI_API_KEY: "' + value + b'"'
        path = "backend/scripts/check-vercel-entry.mjs"
        self.assertFalse(CHECK.scan_bytes(path, data))
        self.assertTrue(CHECK.scan_bytes("backend/src/config.ts", data))
        self.assertTrue(CHECK.scan_bytes(path, data.replace(value, value + b"a")))
        self.assertTrue(CHECK.scan_bytes(path, data + b"\n" + b"AIza" + b"a" * 35))


class GitCredentialGuardTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Friday credential tests")
        self.git("config", "user.email", "friday-tests@example.invalid")

    def git(self, *parts):
        return subprocess.run(["git", *parts], cwd=self.repo, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=True).stdout

    def scan(self, *parts, input=None):
        return subprocess.run(["python3", str(SCRIPT), *parts], cwd=self.repo, input=input,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def commit(self):
        self.git("add", "--all")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "Synthetic fixture")

    def test_index_uses_staged_bytes_even_after_worktree_cleanup(self):
        secret = b"gsk_" + b"A" * 48
        path = self.repo / "config with spaces.txt"
        path.write_bytes(secret)
        self.git("add", "--all")
        path.write_text("clean")
        result = self.scan("--staged")
        self.assertEqual(result.returncode, 1)
        self.assertFalse(secret in result.stdout + result.stderr, "Scanner printed a matched value")
        self.assertIn(b"config with spaces.txt", result.stderr)

    def test_history_and_push_scan_deleted_credentials(self):
        path = self.repo / "config.txt"
        path.write_bytes(b"gsk_" + b"A" * 48)
        self.commit()
        path.write_text("clean")
        self.commit()
        self.assertEqual(self.scan("--staged").returncode, 0)
        self.assertEqual(self.scan("--history", "HEAD").returncode, 1)
        oid = self.git("rev-parse", "HEAD").decode().strip()
        update = f"refs/heads/main {oid} refs/heads/main {'0' * 40}\n".encode()
        self.assertEqual(self.scan("--pre-push", input=update).returncode, 1)

    def test_history_checks_private_aliases_of_the_same_blob(self):
        (self.repo / "safe.txt").write_text("same contents")
        self.commit()
        (self.repo / ".env").write_text("same contents")
        self.commit()
        self.assertEqual(self.scan("--history", "HEAD").returncode, 1)

    def test_unavailable_history_and_malformed_push_fail_closed(self):
        self.assertEqual(self.scan("--history", "missing-ref").returncode, 2)
        self.assertEqual(self.scan("--pre-push", input=b"bad input\n").returncode, 2)
        update = f"(delete) {'0' * 40} refs/heads/main {'a' * 40}\n".encode()
        self.assertEqual(self.scan("--pre-push", input=update).returncode, 0)

    def test_real_precommit_hook_blocks_credentials(self):
        hooks = self.repo / ".githooks"
        hooks.mkdir()
        hook = hooks / "pre-commit"
        hook.write_text(f'#!/bin/sh\nexec python3 "{SCRIPT}" --staged\n')
        hook.chmod(0o755)
        self.git("config", "core.hooksPath", ".githooks")
        (self.repo / "config.txt").write_bytes(b"gsk_" + b"A" * 48)
        self.git("add", "--all")
        result = subprocess.run(["git", "commit", "-m", "Blocked synthetic fixture"], cwd=self.repo,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"Credential check failed", result.stderr)

    def test_real_prepush_hook_blocks_an_earlier_deleted_credential(self):
        path = self.repo / "config.txt"
        path.write_bytes(b"gsk_" + b"A" * 48)
        self.commit()
        path.write_text("clean")
        self.commit()
        remote = self.repo / "remote.git"
        self.git("init", "--bare", "--quiet", str(remote))
        hooks = self.repo / ".githooks"
        hooks.mkdir()
        hook = hooks / "pre-push"
        hook.write_text(f'#!/bin/sh\nexec python3 "{SCRIPT}" --pre-push\n')
        hook.chmod(0o755)
        self.git("config", "core.hooksPath", ".githooks")
        result = subprocess.run(["git", "push", str(remote), "HEAD:refs/heads/main"], cwd=self.repo,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"Credential check failed", result.stderr)
        remote_ref = subprocess.run(["git", "--git-dir", str(remote), "rev-parse", "--verify", "refs/heads/main"],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(remote_ref.returncode, 0, "Blocked push created a remote branch")


if __name__ == "__main__":
    unittest.main()
