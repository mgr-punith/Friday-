"""Regression checks for debug-only packaging, external models and the JNI build switch."""

import importlib.util
import tempfile
import unittest
import contextlib
import io
import re
import subprocess
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile
from xml.etree import ElementTree

from wake_trial_checks import check_apk, check_release_manifest


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


INSTALL = module("wake_model_install", "install-wake-trial-model.py")
BUILD = module("wake_build", "build-microwakeword-eval.py")
PHONE = module("wake_phone", "wake-trial-phone-check.py")
COMMAND = module("wake_command_phone", "wake-command-phone-check.py")
USAGE = module("wake_usage_phone", "wake-usage-phone-check.py")
LOCKED = module("locked_wake_phone", "locked-wake-phone-check.py")
SETUP = module("wake_model_setup_phone", "wake-model-setup-phone-check.py")


class WakeTrialTests(unittest.TestCase):
    def test_model_setup_taps_xml_elements_and_rejects_offscreen_controls(self):
        node = ElementTree.fromstring('<node text="Download Hey Friday" bounds="[42,320][780,448]"/>')
        self.assertEqual(SETUP.tap_point(node), (411, 384))
        for bounds in ("", "[0,0][0,0]", "[-10,0][100,48]", "[0,10][100,5]", "[0,0]"):
            with self.subTest(bounds=bounds), self.assertRaises(AssertionError):
                SETUP.tap_point(ElementTree.fromstring(f'<node bounds="{bounds}"/>'))
        with self.assertRaises(AssertionError):
            SETUP.tap_point(None)

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

    def test_locked_wake_injection_does_not_require_an_activity_behind_secure_keyguard(self):
        for outcome in ("lock-call", "lock-app", "lock-timer", "lock-sound"):
            args = LOCKED.synthetic_locked_wake_arguments(outcome)
            self.assertEqual(args[:2], ["am", "broadcast"])
            self.assertEqual(args[args.index("-n") + 1], LOCKED.TEST_RECEIVER)
            self.assertEqual(args[args.index("-a") + 1], LOCKED.TEST_ACTION)
            self.assertEqual(args[-3:], ["--es", "debug_wake_outcome", outcome])
        for outcome in (None, "", "speaking", "unknown"):
            with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                LOCKED.synthetic_locked_wake_arguments(outcome)

    def test_locked_wake_reads_the_keyguard_delegate_instead_of_unrelated_showing_flags(self):
        policy = """Window policy state:
  UnrelatedController:
    showing=false
    secure=true
  mKeyguardDelegate:
    KeyguardServiceDelegate
      showing=true
      inputRestricted=false
      secure=false
      deviceHasKeyguard=true
      screenState=SCREEN_STATE_OFF
      interactiveState=INTERACTIVE_STATE_SLEEP
"""
        self.assertEqual(LOCKED.parse_keyguard(policy), {"showing": True, "secure": False, "screenOff": True})
        secure = policy.replace("      secure=false", "      secure=true")
        self.assertEqual(LOCKED.parse_keyguard(secure), {"showing": True, "secure": True, "screenOff": True})
        awake = policy.replace("SCREEN_STATE_OFF", "SCREEN_STATE_ON")
        self.assertFalse(LOCKED.parse_keyguard(awake)["screenOff"])

    def test_locked_wake_does_not_infer_missing_keyguard_fields(self):
        for policy in ("", "No window policy available", "showing=true\nsecure=true",
                       "OtherController:\n  showing=true\n  secure=true"):
            with self.subTest(policy=policy):
                self.assertEqual(LOCKED.parse_keyguard(policy), {"showing": None, "secure": None, "screenOff": False})
        incomplete = "KeyguardServiceDelegate\n  showing=true\n"
        self.assertEqual(LOCKED.parse_keyguard(incomplete), {"showing": True, "secure": None, "screenOff": False})
        hidden = "KeyguardServiceDelegate\n  showing=false\n  secure=true\n"
        self.assertFalse(LOCKED.parse_keyguard(hidden)["showing"])

    def test_locked_wake_accepts_explicit_keyguard_field_aliases(self):
        self.assertEqual(LOCKED.parse_keyguard("  mKeyguardShowing=true\n  mKeyguardSecure=false\n"),
                         {"showing": True, "secure": False, "screenOff": False})
        self.assertEqual(LOCKED.parse_keyguard("  isKeyguardShowing=false\n  isKeyguardSecure=true\n"),
                         {"showing": False, "secure": True, "screenOff": False})

    def test_locked_wake_requires_the_assistant_to_be_the_current_window_focus(self):
        current = "mCurrentFocus=Window{abc u0 com.example.assistant/com.example.assistant.AssistantActivity}"
        self.assertTrue(LOCKED.assistant_focused(current))
        for windows in (
            "mCurrentFocus=null",
            "mCurrentFocus=Window{abc u0 com.android.systemui}\n"
            "mFocusedApp=ActivityRecord{def com.example.assistant/.AssistantActivity}",
            "Window #2 Window{abc u0 com.example.assistant/.AssistantActivity}",
            "mCurrentFocus=Window{abc u0 com.example.assistant/.MainActivity}",
        ):
            with self.subTest(windows=windows):
                self.assertFalse(LOCKED.assistant_focused(windows))

    def test_locked_wake_requires_the_same_run_and_exclusive_command_mode_capture(self):
        live = {"generation": 42, "phase": "LISTENING", "wakeCapture": True,
                "commandCapture": False, "commandMode": True, "visibleAssistants": 0}
        self.assertTrue(LOCKED.listening(live, 42))
        for change in ({"generation": 43}, {"phase": "PAUSED"}, {"wakeCapture": False},
                       {"commandCapture": True}, {"commandMode": False}, {"visibleAssistants": 1}):
            with self.subTest(change=change):
                self.assertFalse(LOCKED.listening({**live, **change}, 42))
        for missing in live:
            with self.subTest(missing=missing):
                self.assertFalse(LOCKED.listening({key: value for key, value in live.items() if key != missing}, 42))
        self.assertFalse(LOCKED.listening(None, 42))

    def test_single_top_assistant_reconfigures_lock_screen_window_before_consuming_a_new_intent(self):
        root = Path(__file__).resolve().parents[1]
        activity = (root / "app/src/main/kotlin/com/example/assistant/AssistantActivity.kt").read_text()
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

    def test_notification_navigation_reopens_a_collapsed_shade_instead_of_swiping_the_launcher(self):
        launcher = ElementTree.fromstring('<node package="com.vivo.launcher" content-desc="Home screen 1"/>')
        self.assertEqual(USAGE.notification_step([launcher], "Pause Hey Friday"), ("open", None))
        stack = ElementTree.fromstring('<node package="com.android.systemui" resource-id="com.android.systemui:id/notification_stack_scroller"/>')
        self.assertEqual(USAGE.notification_step([stack], "Pause Hey Friday"), ("scroll", None))
        control = ElementTree.fromstring('<node package="com.android.systemui" content-desc="Pause Hey Friday" enabled="true"/>')
        self.assertEqual(USAGE.notification_step([stack, control], "Pause Hey Friday"), ("tap", control))

    def test_usage_notification_expansion_targets_fridays_group(self):
        root = ElementTree.fromstring('''<hierarchy>
            <node resource-id="com.android.systemui:id/notification_stack_scroller">
                <node><node text="Other app"/><node content-desc="Expand" clickable="true" bounds="other"/></node>
                <node><node text="Friday"/><node content-desc="Expand" clickable="true" bounds="friday"/></node>
            </node>
        </hierarchy>''')
        step, node = USAGE.notification_step(list(root.iter("node")), "Resume Hey Friday")
        self.assertEqual(step, "expand")
        self.assertEqual(node.get("bounds"), "friday")
    def test_ui_dump_retries_after_android_kills_its_process_without_reading_stale_xml(self):
        from unittest.mock import Mock
        discard = Mock()
        dump = Mock(side_effect=[subprocess.CalledProcessError(137, "uiautomator"), None])
        read = Mock(return_value=b'<hierarchy><node text="Resume Hey Friday"/></hierarchy>')
        data, rows = USAGE.fresh_hierarchy(discard, dump, read)
        self.assertEqual(discard.call_count, 2)
        self.assertEqual(dump.call_count, 2)
        self.assertEqual(read.call_count, 1)
        self.assertEqual(rows[0].get("text"), "Resume Hey Friday")
        self.assertTrue(data.startswith(b"<hierarchy"))

    def test_ui_dump_fails_after_bounded_retries_without_returning_partial_data(self):
        from unittest.mock import Mock
        dump = Mock(side_effect=subprocess.CalledProcessError(137, "uiautomator"))
        read = Mock()
        with self.assertRaises(AssertionError):
            USAGE.fresh_hierarchy(Mock(), dump, read)
        self.assertEqual(dump.call_count, 4)
        read.assert_not_called()
        with self.assertRaises(AssertionError):
            USAGE.fresh_hierarchy(Mock(), Mock(), Mock(return_value=b"<hierarchy"))
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
        source = (root / "app/src/debug/kotlin/com/example/assistant/debug/wake/WakeUsageNotification.kt").read_text()
        # Android 11 crashes the app asynchronously when System UI applies a
        # RemoteViews reflection action for View.setAlpha(float).
        self.assertNotRegex(source, r'setFloat\s*\([^\n]*"setAlpha"')
        self.assertRegex(source, r'setInt\s*\(R\.id\.wake_usage_toggle,\s*"setImageAlpha",\s*if \(stopping\) 128 else 255\)')

    def test_paused_notification_does_not_reuse_the_foreground_notification_id(self):
        root = Path(__file__).resolve().parents[1] / "app/src/debug/kotlin/com/example/assistant/debug/wake"
        # Protect the Android cancellation boundary; this needs distinct IDs,
        # beyond the pure preference/session checks.
        notification = (root / "WakeUsageNotification.kt").read_text()
        service = (root / "WakeTrialService.kt").read_text()
        foreground = re.search(r"const val NOTIFICATION_ID = (\d+)", service).group(1)
        paused = re.search(r"const val PAUSED_NOTIFICATION_ID = (\d+)", notification).group(1)
        self.assertNotEqual(foreground, paused)

    def test_notification_listener_check_accepts_kotlin_float_serialization(self):
        live = {"usageControlled": True, "phase": "LISTENING", "wakeCapture": True,
                "commandCapture": False, "commandMode": True, "threshold": 0.30000001192092896}
        self.assertTrue(USAGE.is_controlled_listener(live))
        for change in ({"threshold": 0.1}, {"threshold": None}, {"commandCapture": True},
                       {"usageControlled": False}, {"phase": "STOPPING"}, {"wakeCapture": False}):
            with self.subTest(change=change):
                self.assertFalse(USAGE.is_controlled_listener({**live, **change}))
        self.assertFalse(USAGE.is_controlled_listener(None))
    def test_info_and_notification_controls_use_the_accessible_label_and_enabled_state(self):
        enabled = ElementTree.fromstring('<node content-desc="Pause Hey Friday" enabled="true"/>')
        disabled = ElementTree.fromstring('<node content-desc="Pause Hey Friday" enabled="false"/>')
        other = ElementTree.fromstring('<node text="Resume Hey Friday" enabled="true"/>')
        self.assertIs(USAGE.control([disabled, other, enabled], "Pause Hey Friday"), enabled)
        self.assertIsNone(USAGE.control([disabled, other], "Pause Hey Friday"))

    def test_entry_choice_checks_native_radio_state_instead_of_matching_label_text(self):
        rows = ElementTree.fromstring('''<hierarchy>
            <node class="android.widget.RadioButton" text="Hey Friday" checked="false"/>
            <node class="android.widget.RadioButton" text="Tap to speak" checked="true"/>
            <node class="android.widget.TextView" text="Hey Friday" checked="true"/>
        </hierarchy>''')
        self.assertEqual(USAGE.checked_choice(list(rows)), ["Tap to speak"])
    def test_notification_navigation_scrolls_an_open_shade_when_friday_is_below_other_alerts(self):
        stack = ElementTree.fromstring('<node resource-id="com.android.systemui:id/notification_stack_scroller"/>')
        bar = ElementTree.fromstring('<node resource-id="com.android.systemui:id/status_bar"/>')
        self.assertTrue(COMMAND.notification_shade_visible([bar, stack]))
        self.assertFalse(COMMAND.notification_shade_visible([bar]))
        self.assertFalse(COMMAND.notification_shade_visible([]))

    def test_retry_retains_the_panel_even_after_the_retried_recording_times_out(self):
        panel = {"generation": 42, "phase": "PAUSED", "wakeCapture": False,
                 "commandCapture": True, "visibleAssistants": 1}
        self.assertTrue(COMMAND.has_retained_panel(panel, 42))
        self.assertTrue(COMMAND.has_retained_panel({**panel, "commandCapture": False}, 42))
        for changed in ({"generation": 43}, {"phase": "STOPPED"}, {"wakeCapture": True}, {"visibleAssistants": 0}):
            with self.subTest(changed=changed):
                self.assertFalse(COMMAND.has_retained_panel({**panel, **changed}, 42))
        self.assertFalse(COMMAND.has_retained_panel(None, 42))

    def test_retry_target_comes_from_a_stable_error_panel_not_transient_recording(self):
        recording = ElementTree.fromstring('<node content-desc="Stop listening" enabled="true"/>')
        disabled = ElementTree.fromstring('<node content-desc="Start listening" enabled="false"/>')
        retry = ElementTree.fromstring('<node content-desc="Start listening" enabled="true"/>')
        self.assertIsNone(COMMAND.retry_control([recording, disabled]))
        self.assertIs(COMMAND.retry_control([recording, disabled, retry]), retry)

    def test_wake_outcome_injection_uses_the_original_handoff_instead_of_pausing_the_panel(self):
        arguments = COMMAND.synthetic_wake_arguments(outcome="missing-app")
        self.assertEqual(arguments[arguments.index("-n") + 1], COMMAND.TRIAL)
        self.assertNotIn(COMMAND.ASSISTANT, arguments)
        self.assertNotIn("debug_ui_state", arguments)
        self.assertEqual(arguments[-3:], ["--es", "debug_wake_outcome", "missing-app"])
        self.assertNotIn("debug_wake_outcome", COMMAND.synthetic_wake_arguments())

    def test_notification_recovery_preview_does_not_foreground_an_existing_setup_task(self):
        for outcome in ("no-command", "missing-app"):
            args = COMMAND.synthetic_wake_broadcast_arguments(outcome)
            self.assertEqual(args[:2], ["am", "broadcast"])
            self.assertIn("--receiver-foreground", args)
            self.assertEqual(args[args.index("-n") + 1], COMMAND.PACKAGE + "/.debug.wake.WakeTrialTestReceiver")
            self.assertEqual(args[-3:], ["--es", "debug_wake_outcome", outcome])
            self.assertNotIn(COMMAND.TRIAL, args)
            self.assertNotIn(COMMAND.ASSISTANT, args)
        for outcome in (None, "", "permission", "unknown"):
            with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                COMMAND.synthetic_wake_broadcast_arguments(outcome)

    def test_error_recovery_requires_the_same_run_and_exclusive_local_wake_capture(self):
        listening = {"generation": 42, "phase": "LISTENING", "wakeCapture": True,
                     "commandCapture": False, "visibleAssistants": 0}
        self.assertTrue(COMMAND.has_rearmed(listening, 42))
        for changed in ({"generation": 43}, {"phase": "PAUSED"}, {"wakeCapture": False},
                        {"commandCapture": True}, {"visibleAssistants": 1}):
            with self.subTest(changed=changed):
                self.assertFalse(COMMAND.has_rearmed({**listening, **changed}, 42))
        self.assertFalse(COMMAND.has_rearmed(None, 42))
        self.assertFalse(COMMAND.has_rearmed({"generation": 42, "phase": "LISTENING"}, 42))

    def test_assistant_handoff_returns_to_main_instead_of_foregrounding_trial_task(self):
        arguments = PHONE.assistant_launch_arguments()
        self.assertIn("--activity-clear-top", arguments)
        self.assertEqual(arguments[-1], "com.example.assistant/.MainActivity")
        self.assertTrue(PHONE.assistant_is_foreground("topResumedActivity=ActivityRecord{abc u0 com.example.assistant/.MainActivity t620 d0}"))
        self.assertFalse(PHONE.assistant_is_foreground("topResumedActivity=ActivityRecord{abc u0 com.example.assistant/.debug.wake.WakeTrialActivity t620 d0}\nHist #0: com.example.assistant/.MainActivity"))

    def test_notification_content_expands_group_before_launching(self):
        action = ElementTree.fromstring('<node text="Wake heard · Tap to speak" package="com.android.systemui"/>')
        expand = ElementTree.fromstring('<node content-desc="Expand"/>')
        self.assertEqual(COMMAND.notification_control([action], action.get("text"), expand), (expand, False))
        self.assertEqual(COMMAND.notification_control([action], action.get("text"), None), (action, True))
        stop = ElementTree.fromstring('<node text="Stop test" package="com.android.systemui"/>')
        self.assertEqual(COMMAND.notification_control([stop], "Stop test", expand), (stop, True))
        self.assertEqual(COMMAND.notification_control([], "Stop test", expand), (expand, False))
        self.assertEqual(COMMAND.notification_control([], "Stop test", None), (None, False))

    def test_command_status_requires_structured_debug_metadata(self):
        for data in ("", "No services match", "{", "[]", '{"phase":"PAUSED"}', '{"generation":1,"phase":"unknown"}'):
            self.assertIsNone(COMMAND.parse_status(data))
        row = COMMAND.parse_status('SERVICE debug\n {"generation":2,"phase":"PAUSED","detections":3}\n')
        self.assertEqual(row["detections"], 3)

    def test_notification_expansion_targets_fridays_group(self):
        rows = PHONE.parse_hierarchy(b'''<hierarchy>
          <node><node text="Other app"/><node resource-id="android:id/expand_button" content-desc="Expand" clickable="true" bounds="other"/></node>
          <node><node text="Friday wake test is using the microphone"/><node resource-id="android:id/expand_button" content-desc="Expand" clickable="true" bounds="friday"/></node>
        </hierarchy>''')
        self.assertEqual(PHONE.notification_expand(rows, "Friday wake test is using the microphone").get("bounds"), "friday")
        self.assertIsNone(PHONE.notification_expand(rows, "Missing notification"))

    def test_ui_poll_rejects_missing_and_partial_hierarchies(self):
        for data in (b"", b"cat: No such file or directory", b"<hierarchy", b"<error/>"):
            with self.subTest(data=data):
                self.assertIsNone(PHONE.parse_hierarchy(data))
        rows = PHONE.parse_hierarchy(b'<hierarchy><node text="Stop test" package="com.android.systemui"/></hierarchy>')
        self.assertEqual(rows[0].get("package"), "com.android.systemui")

    def test_adb_replay_poll_ignores_missing_partial_and_stale_results(self):
        for data in (b"", b"cat: replay-result.json: No such file or directory\n", b"{", b"[]",
                     b'{"runId":"previous","ok":true}', b'{"runId":"current","ok":"true"}'):
            with self.subTest(data=data):
                self.assertIsNone(PHONE.parse_replay_result(data, "current"))
        self.assertTrue(PHONE.parse_replay_result(b'{"runId":"current","ok":true}', "current")["ok"])
        self.assertFalse(PHONE.parse_replay_result(b'{"runId":"current","ok":false}', "current")["ok"])

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
            '<application><activity android:name="com.example.assistant.debug.wake.WakeUsageActivity"/></application>',
            '<application><activity android:name="com.example.assistant.debug.wake.WakeTrialActivity"/></application>',
            '<application><service android:name="com.example.assistant.debug.wake.WakeTrialService"/></application>',
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
                ("classes.dex", b"Lcom/example/assistant/debug/wake/WakeUsageNotification;"),
                ("lib/arm64-v8a/libmicrowakeword.so", b"runtime"),
                ("assets/hey_friday.tflite", b"model"),
                ("assets/recording.pcm", b"audio"),
                ("classes.dex", b"Lcom/example/assistant/debug/wake/WakeTrialActivity;"),
            ):
                with ZipFile(path, "w") as archive:
                    archive.writestr(name, data)
                with self.subTest(name=name), self.assertRaises(AssertionError):
                    check_apk(path)
            with ZipFile(path, "w") as archive:
                archive.writestr("lib/arm64-v8a/libmicrowakeword.so", b"runtime")
            check_apk(path, release=False, require_runtime=True)
            with ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"normal app")
            check_apk(path)
            with self.assertRaises(AssertionError):
                check_apk(path, release=False, require_runtime=True)

    def test_unknown_or_modified_model_rejected_before_install(self):
        for data in (b"", b"corrupt", b"0" * INSTALL.MODEL_BYTES, b"0" * (INSTALL.MODEL_BYTES + 1)):
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                INSTALL.validate_model(data)

    def test_jni_requires_explicit_android_build_before_downloading(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("sys.argv", ["build-microwakeword-eval.py", directory, "--debug-jni"]), \
                patch.object(BUILD, "prepare_sources") as sources, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                BUILD.main()
            sources.assert_not_called()

    def test_jni_is_not_added_to_the_normal_evaluation_build(self):
        text = "include(FetchContent)\n    MicroWakeWord_jni.cpp\n    android\n    log\n"
        self.assertNotIn("target_sources(microwakeword PRIVATE FridayWakeJni.cpp)", BUILD.evaluation_cmake(text))
        self.assertIn("target_sources(microwakeword PRIVATE FridayWakeJni.cpp)", BUILD.evaluation_cmake(text, debug_jni=True))


if __name__ == "__main__":
    unittest.main()
