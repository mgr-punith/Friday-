import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("app_dependencies", Path(__file__).with_name("check-app-dependencies.py"))
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def report():
    return "releaseRuntimeClasspath - Release runtime\n" + "\n".join("+--- " + name for name in sorted(AUDIT.REQUIRED))


class DependencyBoundaryTests(unittest.TestCase):
    def test_allows_approved_login_graph(self):
        graph = report() + "\n" + "\n".join("|    +--- com.google.android.gms:" + name + ":1.0.0" for name in AUDIT.APPROVED_GMS)
        self.assertEqual(AUDIT.violations(graph), [])
        self.assertEqual(AUDIT.violations(report().replace("googleid:1.1.1", "googleid:1.1.1 (*)")), [])

    def test_rejects_unrelated_google_firebase_mlkit_aws_and_large_icons(self):
        for module in ("com.google.android.gms:play-services-location:1.0", "com.google.firebase:firebase-auth:1.0", "com.google.mlkit:text-recognition:1.0", "software.amazon.awssdk:s3:1.0", "com.amazonaws:aws-android-sdk-core:1.0", "androidx.compose.material:material-icons-extended:1.0"):
            self.assertTrue(AUDIT.violations(report() + "\n+--- " + module), module)

    def test_rejects_empty_missing_and_unresolved_reports(self):
        self.assertTrue(AUDIT.violations(""))
        self.assertTrue(AUDIT.violations(report().replace("googleid:1.1.1", "googleid:1.1.2")))
        self.assertTrue(AUDIT.violations(report() + "\n+--- missing:dependency:1.0 FAILED"))

    def test_compiler_metadata_regression_is_blocked(self):
        self.assertTrue(AUDIT.violations(report().replace("googleid:1.1.1", "googleid:1.2.1")))


if __name__ == "__main__":
    unittest.main()
