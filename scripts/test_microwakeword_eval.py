"""Model identity, bounds and streaming cleanup checks; no downloads required."""

import ctypes
import contextlib
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import microwakeword_eval as MICRO

SPEC = importlib.util.spec_from_file_location(
    "micro_build", Path(__file__).with_name("build-microwakeword-eval.py")
)
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class MicroWakeWordTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="friday-micro-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data = b"\x08\x00\x00\x00TFL3test-model"
        (self.root / "hey_friday.tflite").write_bytes(self.data)
        self.digest = hashlib.sha256(self.data).hexdigest()
        self.config = {
            "type": "micro", "version": 2, "wake_word": "hey friday",
            "model": "hey_friday.tflite",
            "micro": {"probability_cutoff": 0.66, "sliding_window_size": 10,
                      "feature_step_size": 10, "tensor_arena_size": 25_544},
        }
        self.config_file = self.root / "hey_friday.json"

    def load(self, phrase="hey friday", digest=None, cutoff=None):
        self.config_file.write_text(json.dumps(self.config))
        return MICRO.load_model(self.root, self.config_file, phrase,
                                self.digest if digest is None else digest, cutoff)

    def test_pinned_model_and_declared_settings(self):
        model = self.load()
        self.assertEqual(model["sha256"], self.digest)
        self.assertEqual(model["cutoff"], 0.66)
        self.assertEqual(model["window"], 10)

    def test_declared_wake_phrase_must_match_requested_phrase(self):
        self.config["wake_word"] = "Hey Jarvis"
        with self.assertRaisesRegex(ValueError, "wake phrase"):
            self.load()

    def test_model_hash_must_match(self):
        for digest in ("", "incorrect", "0" * 64):
            with self.subTest(digest=digest), self.assertRaisesRegex(ValueError, "SHA-256"):
                self.load(digest=digest)

    def test_remote_and_parent_model_paths_rejected(self):
        for filename in ("../hey_friday.tflite", "/hey_friday.tflite",
                         "https://example.test/model.tflite", "..\\model.tflite"):
            self.config["model"] = filename
            with self.subTest(filename=filename), self.assertRaisesRegex(ValueError, "local"):
                self.load()

    def test_symlink_outside_model_directory_rejected(self):
        subdir = self.root / "nested"
        subdir.mkdir()
        alias = subdir / "model.tflite"
        alias.symlink_to(self.root / "hey_friday.tflite")
        self.config["model"] = "model.tflite"
        self.config_file.write_text(json.dumps(self.config))
        with self.assertRaisesRegex(ValueError, "inside"):
            MICRO.load_model(subdir, self.config_file, "hey friday", self.digest)

    def test_corrupt_or_oversized_model_rejected(self):
        path = self.root / "hey_friday.tflite"
        for data in (b"", b"not-a-tflite-model", b"0" * (MICRO.MAX_MODEL_BYTES + 1)):
            path.write_bytes(data)
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                self.load()

    def test_invalid_cutoff_window_and_arena_rejected(self):
        for field, values in (
            ("probability_cutoff", (0, -1, 1.1, float("nan"), float("inf"), True)),
            ("sliding_window_size", (0, 101, True, 2.5)),
            ("tensor_arena_size", (0, 65_537, True)),
            ("feature_step_size", (0, 20)),
        ):
            original = self.config["micro"][field]
            for value in values:
                self.config["micro"][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    self.load()
            self.config["micro"][field] = original

    def test_explicit_cutoff_override_preserves_model_identity(self):
        model = self.load(cutoff=0.8)
        self.assertEqual(model["cutoff"], 0.8)
        self.assertEqual(model["sha256"], self.digest)

    def detector(self):
        native = SimpleNamespace(
            friday_wake_create=Mock(return_value=42),
            friday_wake_process=Mock(return_value=0),
            friday_wake_destroy=Mock(),
        )
        with patch.object(ctypes, "CDLL", return_value=native):
            detect = MICRO.native_detector(self.root / "library.so", self.load())
        return native, detect

    def test_warmup_detections_do_not_count_and_tail_is_preserved(self):
        native, detect = self.detector()
        lengths = []

        def process(_decoder, _samples, count):
            lengths.append(count)
            return 1

        native.friday_wake_process.side_effect = process
        self.assertEqual(detect(b"\x00\x00" * 161), 2)
        self.assertEqual(lengths, [160] * (MICRO.WARMUP_MS // 10) + [160, 1])
        native.friday_wake_destroy.assert_called_once_with(42)

    def test_bad_pcm_does_not_start_decoder(self):
        native, detect = self.detector()
        for data in (b"", b"x"):
            with self.subTest(data=data), self.assertRaisesRegex(ValueError, "16-bit PCM"):
                detect(data)
        native.friday_wake_create.assert_not_called()

    def test_model_initialization_failure(self):
        native, detect = self.detector()
        native.friday_wake_create.return_value = None
        with self.assertRaisesRegex(ValueError, "initialize"):
            detect(b"\x00\x00")
        native.friday_wake_destroy.assert_not_called()

    def test_native_failure_releases_decoder(self):
        for stage in ("warmup", "audio"):
            with self.subTest(stage=stage):
                native, detect = self.detector()
                native.friday_wake_process.side_effect = (
                    [-1] if stage == "warmup" else [0] * (MICRO.WARMUP_MS // 10) + [-1]
                )
                with self.assertRaises(ValueError):
                    detect(b"\x00\x00")
                native.friday_wake_destroy.assert_called_once_with(42)

    def test_builder_rejects_changed_upstream_layout(self):
        with self.assertRaisesRegex(ValueError, "layout changed"):
            BUILD.evaluation_cmake("unexpected upstream file")

    def test_builder_rejects_modified_cached_source_without_downloading(self):
        upstream = self.root / "upstream"
        upstream.mkdir()
        (upstream / "CMakeLists.txt").write_text("tampered")
        with patch.object(BUILD.urllib.request, "urlopen") as fetch:
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                BUILD.prepare_sources(self.root)
        fetch.assert_not_called()

    def test_builder_configures_before_building_and_disables_exceptions(self):
        with patch("sys.argv", ["build-microwakeword-eval.py", str(self.root)]), \
                patch.object(BUILD, "prepare_sources", return_value=self.root / "native-src"), \
                patch.object(BUILD.subprocess, "run") as run, \
                contextlib.redirect_stdout(io.StringIO()):
            BUILD.main()
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(len(commands), 2)
        self.assertEqual(commands[0][:2], ["cmake", "-S"])
        self.assertIn("-DCMAKE_CXX_FLAGS=-fno-exceptions", commands[0])
        self.assertEqual(commands[1][:2], ["cmake", "--build"])


if __name__ == "__main__":
    unittest.main()
