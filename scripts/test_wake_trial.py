"""CI checks for Android debug/release packaging and component boundaries."""

import re
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree

from wake_trial_checks import JNI_SYMBOL_PREFIX, check_apk, check_release_manifest, check_signing_certificate


class WakeTrialTests(unittest.TestCase):
    def test_signing_check_accepts_current_and_older_android_tool_output(self):
        digest = "a1" * 20
        expected = ":".join(["A1"] * 20)
        for prefix in ("Signer #1", "V2 Signer", "V1 Signer #1"):
            with self.subTest(prefix=prefix):
                output = f"{prefix}: certificate SHA-1 digest: {digest.upper()}\n"
                output += f"{prefix}: public key SHA-1 digest: {'02' * 20}\n"
                check_signing_certificate(output, expected)


    def test_signing_check_rejects_missing_wrong_or_additional_certificates(self):
        digest = "01" * 20
        for output in (
            "",
            f"V2 Signer: public key SHA-1 digest: {digest}\n",
            f"V2 Signer: certificate SHA-1 digest: {'02' * 20}\n",
            f"V2 Signer: certificate SHA-1 digest: {digest}\nSigner #2: certificate SHA-1 digest: {'02' * 20}\n",
        ):
            with self.subTest(output=output), self.assertRaisesRegex(AssertionError, "signing certificate"):
                check_signing_certificate(output, digest)


    def test_locked_preview_receiver_requires_shell_permission_and_stays_out_of_release(self):
        root = Path(__file__).resolve().parents[1]
        android = "{http://schemas.android.com/apk/res/android}"
        manifest = ElementTree.parse(root / "app/src/debug/AndroidManifest.xml").getroot()
        receiver = next((item for item in manifest.iter("receiver") if
                         item.get(android + "name", "").endswith(".debug.wake.WakeTrialTestReceiver")), None)
        self.assertIsNotNone(receiver)
        self.assertEqual(receiver.get(android + "permission"), "android.permission.DUMP")
        self.assertEqual(receiver.get(android + "exported"), "true")
        main = ElementTree.parse(root / "app/src/main/AndroidManifest.xml").getroot()
        self.assertFalse(any("WakeTrialTestReceiver" in item.get(android + "name", "") for item in main.iter("receiver")))


    def test_single_top_assistant_reconfigures_lock_screen_window_before_consuming_a_new_intent(self):
        root = Path(__file__).resolve().parents[1]
        activity = (root / "app/src/main/kotlin/com/puneet/friday/AssistantActivity.kt").read_text()
        new_intent = re.search(r"override fun onNewIntent\(intent: Intent\)\s*\{(.*?)\n    \}", activity, re.DOTALL)
        self.assertIsNotNone(new_intent, "A reused assistant Activity must handle a lock-screen wake intent")
        body = new_intent.group(1)
        self.assertIn("DebugTools.configureLockedWindow(this)", body)
        self.assertIn("super.onNewIntent(intent)", body)
        self.assertLess(body.index("DebugTools.configureLockedWindow(this)"), body.index("super.onNewIntent(intent)"))
        android = "{http://schemas.android.com/apk/res/android}"
        manifest = ElementTree.parse(root / "app/src/main/AndroidManifest.xml").getroot()
        assistant = next(row for row in manifest.find("application").findall("activity")
                         if row.get(android + "name") == ".AssistantActivity")
        self.assertEqual(assistant.get(android + "launchMode"), "singleTop")


    def test_notification_icon_has_explicit_light_and_dark_colors(self):
        root = Path(__file__).resolve().parents[1] / "app/src/debug/res"
        android = "{http://schemas.android.com/apk/res/android}"
        layout = ElementTree.parse(root / "layout/notification_wake_usage.xml").getroot()
        icon = layout.find("ImageButton")
        self.assertEqual(icon.get(android + "tint"), "@color/wake_notification_foreground")
        self.assertEqual(icon.get(android + "layout_width"), "48dp")
        self.assertEqual(icon.get(android + "layout_height"), "48dp")
        for qualifier, expected in (("values", "#202124"), ("values-night", "#F3F4F7")):
            colors = ElementTree.parse(root / qualifier / "colors.xml").getroot()
            self.assertEqual(colors.find("color[@name='wake_notification_foreground']").text, expected)


    def test_notification_alpha_uses_a_method_available_to_android_11_remote_views(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/debug/kotlin/com/puneet/friday/debug/wake/WakeUsageNotification.kt").read_text()
        # Android 11 crashes the app asynchronously when System UI applies a
        # RemoteViews reflection action for View.setAlpha(float).
        self.assertNotRegex(source, r'setFloat\s*\([^\n]*"setAlpha"')
        self.assertRegex(source, r'setInt\s*\(R\.id\.wake_usage_toggle,\s*"setImageAlpha",\s*if \(stopping\) 128 else 255\)')


    def test_paused_notification_does_not_reuse_the_foreground_notification_id(self):
        root = Path(__file__).resolve().parents[1] / "app/src/debug/kotlin/com/puneet/friday/debug/wake"
        # Protect the Android cancellation boundary; this needs distinct IDs,
        # beyond the pure preference/session checks.
        notification = (root / "WakeUsageNotification.kt").read_text()
        service = (root / "WakeTrialService.kt").read_text()
        foreground = re.search(r"const val NOTIFICATION_ID = (\d+)", service).group(1)
        paused = re.search(r"const val PAUSED_NOTIFICATION_ID = (\d+)", notification).group(1)
        self.assertNotEqual(foreground, paused)


    def test_debug_microphone_service_declares_all_prerequisites(self):
        root = Path(__file__).resolve().parents[1]
        manifest = ElementTree.parse(root / "app/src/debug/AndroidManifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        permissions = {item.get(android + "name") for item in manifest.findall("uses-permission")}
        self.assertTrue({"android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                         "android.permission.RECORD_AUDIO"}.issubset(permissions))
        service = next(item for item in manifest.find("application").findall("service")
                       if item.get(android + "name", "").endswith("WakeTrialService"))
        self.assertEqual(service.get(android + "foregroundServiceType"), "microphone")
        self.assertEqual(service.get(android + "exported"), "false")
        self.assertEqual(service.get(android + "stopWithTask"), "true")


    def test_release_rejects_trial_components_and_microphone_service_permissions(self):
        for content in (
            '<application><activity android:name="com.puneet.friday.debug.wake.WakeUsageActivity"/></application>',
            '<application><activity android:name="com.puneet.friday.debug.wake.WakeTrialActivity"/></application>',
            '<application><service android:name="com.puneet.friday.debug.wake.WakeTrialService"/></application>',
            '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/><application/>',
            '<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/><application/>',
        ):
            with self.subTest(content=content), self.assertRaises(AssertionError):
                check_release_manifest('<manifest xmlns:android="http://schemas.android.com/apk/res/android">' + content + '</manifest>')
        check_release_manifest('<manifest><application/></manifest>')


    def test_native_library_and_model_packaging_guards(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.apk"
            for name, data in (
                ("classes.dex", b"Lcom/puneet/friday/debug/wake/WakeUsageNotification;"),
                ("lib/arm64-v8a/libmicrowakeword.so", b"runtime"),
                ("assets/hey_friday.tflite", b"model"),
                ("assets/recording.pcm", b"audio"),
                ("classes.dex", b"Lcom/puneet/friday/debug/wake/WakeTrialActivity;"),
            ):
                with ZipFile(path, "w") as archive:
                    archive.writestr(name, data)
                with self.subTest(name=name), self.assertRaises(AssertionError):
                    check_apk(path)
            with ZipFile(path, "w") as archive:
                symbols = b"\0".join(JNI_SYMBOL_PREFIX + method for method in (b"create", b"process", b"destroy")) + b"\0"
                archive.writestr("lib/arm64-v8a/libmicrowakeword.so", symbols)
            check_apk(path, release=False, require_runtime=True)
            with ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"normal app")
            check_apk(path)
            with self.assertRaises(AssertionError):
                check_apk(path, release=False, require_runtime=True)


    def test_debug_apk_rejects_a_runtime_for_a_different_package_or_missing_jni_method(self):
        prefix = b"Java_com_puneet_friday_debug_wake_MicroWakeWordNative_"
        for symbols in (
            b"runtime",
            b"Java_com_other_app_debug_wake_MicroWakeWordNative_create\0",
            prefix + b"create\0" + prefix + b"process\0",
        ):
            with self.subTest(symbols=symbols), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "stale-native.apk"
                with ZipFile(path, "w") as archive:
                    archive.writestr("lib/arm64-v8a/libmicrowakeword.so", symbols)
                with self.assertRaisesRegex(AssertionError, "JNI bridge"):
                    check_apk(path, release=False, require_runtime=True)



if __name__ == "__main__":
    unittest.main()
