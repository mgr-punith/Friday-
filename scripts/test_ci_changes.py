"""Regression checks for selective CI routing; no GitHub token or third-party action."""

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("ci_changes", Path(__file__).with_name("ci-changes.py"))
CI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CI)


class ChangeRoutingTests(unittest.TestCase):
    def selected(self, *paths):
        return {name for name, enabled in CI.select_checks(paths).items() if enabled}

    def test_app_only_runs_android(self):
        for path in ("app/src/main/kotlin/com/puneet/friday/MainActivity.kt", "app/src/test/kotlin/Test.kt", "app/src/main/res/drawable/ic_google.xml", "gradle/libs.versions.toml", "app/proguard-rules.pro"):
            self.assertEqual(self.selected(path), {"android"})

    def test_vercel_auth_only_does_not_build_android_or_aws(self):
        for path in ("backend/src/auth/api.ts", "backend/src/vercel/index.ts", "backend/vercel.json", "backend/test/account-api.test.ts", "backend/src/redis/usage-store.ts"):
            self.assertEqual(self.selected(path), {"backend"})

    def test_shared_backend_and_dependencies_also_validate_aws(self):
        for path in ("backend/src/core/api.ts", "backend/src/providers/gemini.ts", "backend/template.yaml", "backend/package-lock.json", "backend/build.mjs"):
            self.assertEqual(self.selected(path), {"backend", "sam"})

    def test_docs_only_skip_builds_and_tools_remain_tested(self):
        self.assertEqual(self.selected("README.md", "docs/testing.md", "design/ui-guide.md", ".agents/skills/stop-slop/SKILL.md"), set())
        self.assertEqual(self.selected("scripts/test_wake_trial.py"), {"tooling"})
        self.assertEqual(self.selected("scripts/check-app-dependencies.py"), {"android", "tooling"})

    def test_workflows_security_and_unknown_paths_run_everything(self):
        for path in (".github/workflows/ci.yml", ".githooks/pre-commit", "scripts/check-secrets.py", "new-build-system/config.yml", "scripts/ci-changes.py"):
            self.assertEqual(self.selected(path), set(CI.SUITES))
        self.assertTrue(all(CI.select_checks([], force=True).values()))

    def test_combines_scopes_and_unsafe_or_missing_base_selects_full(self):
        self.assertEqual(self.selected("app/build.gradle.kts", "backend/src/auth/api.ts"), {"android", "backend"})
        self.assertIsNone(CI.changed_paths("0" * 40, "a" * 40))
        self.assertIsNone(CI.changed_paths("--all", "a" * 40))
        self.assertIsNone(CI.changed_paths("a" * 40, "b" * 40))

    def test_git_diff_keeps_deleted_and_both_renamed_scopes(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args], stderr=subprocess.PIPE).decode().strip()
            git("init", "--quiet")
            git("config", "user.name", "CI test")
            git("config", "user.email", "ci@example.invalid")
            (Path(directory) / "app").mkdir()
            (Path(directory) / "app/old.txt").write_text("same content")
            git("add", "--all")
            git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "Before")
            base = git("rev-parse", "HEAD")
            (Path(directory) / "backend/src/auth").mkdir(parents=True)
            (Path(directory) / "app/old.txt").rename(Path(directory) / "backend/src/auth/new.ts")
            git("add", "--all")
            git("-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m", "After")
            head = git("rev-parse", "HEAD")
            output = subprocess.check_output(["git", "-C", directory, "diff", "--name-only", "--no-renames", "-z", base, head, "--"])
            self.assertEqual(self.selected(*[p.decode() for p in output.split(b"\0") if p]), {"android", "backend"})


if __name__ == "__main__":
    unittest.main()
