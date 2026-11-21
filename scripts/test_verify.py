"""Keep Android regressions active when CI skips unrelated tool tests."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class AndroidVerificationTests(unittest.TestCase):
    def test_skipping_tool_tests_still_blocks_on_android_regressions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copyfile(Path(__file__).with_name("verify.sh"), scripts / "verify.sh")
            (scripts / "check-secrets.py").write_text("", encoding="utf-8")
            (scripts / "test_wake_trial.py").write_text(
                "import unittest\n"
                "class AndroidRegression(unittest.TestCase):\n"
                "    def test_android_guard(self):\n"
                "        self.fail('android_regression_detected')\n",
                encoding="utf-8",
            )
            (scripts / "test_unrelated.py").write_text(
                "raise RuntimeError('unrelated_tool_test_ran')\n", encoding="utf-8"
            )
            (root / "app/src/main").mkdir(parents=True)
            (root / "app/build.gradle.kts").touch()
            (root / "gradlew").write_text("#!/bin/sh\nexit 77\n", encoding="utf-8")
            (root / "gradlew").chmod(0o755)
            result = subprocess.run(
                ["bash", str(scripts / "verify.sh")],
                env={**os.environ, "FRIDAY_SKIP_TOOL_TESTS": "1"},
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertIn("android_regression_detected", result.stderr)
            self.assertNotIn("unrelated_tool_test_ran", result.stderr)


if __name__ == "__main__":
    unittest.main()
