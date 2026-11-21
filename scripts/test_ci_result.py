import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("ci_result", Path(__file__).with_name("ci-result.py"))
CI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CI)


class RequiredStatusTests(unittest.TestCase):
    def test_docs_only_passes_with_skipped_builds(self):
        self.assertEqual(CI.failures("success", dict.fromkeys(("TOOLING", "BACKEND", "SAM", "ANDROID"), "false"), dict.fromkeys(("TOOLING", "BACKEND", "SAM", "ANDROID"), "skipped")), [])

    def test_selected_suite_must_pass_and_unselected_must_skip(self):
        selected = {"TOOLING": "false", "BACKEND": "true", "SAM": "false", "ANDROID": "false"}
        results = {"TOOLING": "skipped", "BACKEND": "success", "SAM": "skipped", "ANDROID": "skipped"}
        self.assertEqual(CI.failures("success", selected, results), [])
        for result in ("failure", "cancelled", "skipped", None):
            self.assertTrue(CI.failures("success", selected, {**results, "BACKEND": result}))

    def test_failed_sam_build_fails_required_checks_until_it_passes(self):
        selected = {"TOOLING": "false", "BACKEND": "true", "SAM": "true", "ANDROID": "false"}
        results = {"TOOLING": "skipped", "BACKEND": "success", "SAM": "failure", "ANDROID": "skipped"}
        self.assertEqual(CI.failures("success", selected, results), ["SAM: expected success, got failure"])
        self.assertEqual(CI.failures("success", selected, {**results, "SAM": "success"}), [])

    def test_missing_plan_and_failed_selection_cannot_look_green(self):
        self.assertTrue(CI.failures("failure", {}, {}))
        self.assertTrue(CI.failures("cancelled", {}, {}))
        self.assertTrue(CI.failures("success", {}, {}))


if __name__ == "__main__":
    unittest.main()
