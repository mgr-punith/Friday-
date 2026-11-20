#!/usr/bin/env python3
"""Test wake-evaluation inputs and reporting without models or native dependencies."""

import contextlib
import ctypes
import importlib.util
import io
import json
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location(
    "wake_phrase_eval", Path(__file__).with_name("wake-phrase-eval.py")
)
EVAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVAL)


class WakeEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="friday-wake-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model = self.root / "model"
        self.model.mkdir()
        self.audio = self.root / "positive.wav"
        self.write_wav(self.audio)
        self.library = self.root / "library.so"
        self.dictionary = self.root / "dictionary.txt"
        self.library.touch()
        self.dictionary.touch()

    @staticmethod
    def write_wav(path, rate=16_000, channels=1, width=2, pcm=b"\x00\x01" * 321):
        with wave.open(str(path), "wb") as wav:
            wav.setparams((channels, width, rate, 0, "NONE", "not compressed"))
            wav.writeframes(pcm)

    def arguments(self, engine="pocketsphinx"):
        return [engine, str(self.model), "--positive", str(self.audio),
                "--native-library", str(self.library), "--dictionary", str(self.dictionary)]

    def assert_bad_arguments(self, arguments, message):
        error = io.StringIO()
        with patch.object(EVAL, "pocketsphinx_detector") as load, \
                contextlib.redirect_stderr(error), self.assertRaises(SystemExit) as raised:
            EVAL.main(arguments)
        self.assertEqual(raised.exception.code, 2)
        self.assertIn(message, error.getvalue())
        load.assert_not_called()

    def test_pcm_accepts_partial_final_frame(self):
        self.assertEqual(len(EVAL.read_pcm(self.audio)), 642)

    def test_pcm_rejects_wrong_format(self):
        for options in ({"rate": 8000}, {"channels": 2}, {"width": 1}):
            with self.subTest(options=options):
                self.write_wav(self.audio, **options)
                with self.assertRaisesRegex(ValueError, "16 kHz mono 16-bit"):
                    EVAL.read_pcm(self.audio)

    def test_pcm_rejects_empty_and_truncated_data(self):
        for pcm in (b"", b"\x00\x01" * 321):
            with self.subTest(empty=not pcm):
                self.write_wav(self.audio, pcm=pcm)
                if pcm:
                    self.audio.write_bytes(self.audio.read_bytes()[:-1])
                with self.assertRaisesRegex(ValueError, "empty or truncated"):
                    EVAL.read_pcm(self.audio)

    def test_pcm_rejects_non_wav(self):
        self.audio.write_bytes(b"not a WAV file")
        with self.assertRaises(wave.Error):
            EVAL.read_pcm(self.audio)

    def test_directory_lists_only_wavs_in_order(self):
        folder = self.root / "clips"
        folder.mkdir()
        for name in ("b.wav", "a.wav", "readme.txt"):
            (folder / name).touch()
        self.assertEqual([p.name for p in EVAL.audio_files([folder])], ["a.wav", "b.wav"])

    def test_missing_or_empty_audio_directory_rejected(self):
        for path in (self.model, self.root / "missing.wav"):
            with self.subTest(path=path):
                with self.assertRaisesRegex(ValueError, "No WAV files"):
                    EVAL.audio_files([path])

    def test_duplicate_labels_rejected(self):
        self.assert_bad_arguments(self.arguments() + ["--negative", str(self.audio)],
                                  "list each file once")

    def test_symlink_duplicate_rejected(self):
        alias = self.root / "alias.wav"
        alias.symlink_to(self.audio)
        self.assert_bad_arguments(self.arguments() + ["--negative", str(alias)],
                                  "list each file once")

    def test_no_audio_rejected(self):
        self.assert_bad_arguments(["pocketsphinx", str(self.model)], "at least one WAV")

    def test_missing_model_rejected(self):
        arguments = self.arguments()
        arguments[1] = str(self.root / "missing")
        self.assert_bad_arguments(arguments, "Model directory does not exist")

    def test_pocketsphinx_requires_native_files(self):
        for path in (self.library, self.dictionary):
            with self.subTest(path=path):
                path.unlink()
                self.assert_bad_arguments(self.arguments(), "requires --native-library")
                path.touch()

    def test_threshold_must_be_positive_and_finite(self):
        for value in ("0", "-1", "nan", "inf"):
            with self.subTest(value=value):
                self.assert_bad_arguments(self.arguments() + [f"--threshold={value}"],
                                          "positive finite number")

    def test_sherpa_requires_keywords(self):
        self.assert_bad_arguments(self.arguments("sherpa"), "--keywords")

    def test_report_preserves_labels_and_counts_events(self):
        negative = self.root / "negative.wav"
        self.write_wav(negative)
        # One positive clip triggers twice; one negative clip triggers once.
        with patch.object(EVAL, "pocketsphinx_detector", return_value=Mock(side_effect=[2, 1])), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            EVAL.main(self.arguments() + ["--negative", str(negative)])
        report = json.loads(output.getvalue())
        self.assertEqual(report["positive"]["triggered_clips"], 1)
        self.assertEqual(report["positive"]["events"], 2)
        self.assertEqual(report["negative"]["triggered_clips"], 1)
        self.assertEqual(report["negative"]["events"], 1)
        self.assertEqual(report["positive"]["clips"], 1)
        self.assertEqual(report["positive"]["audio_seconds"], 0.02)
        self.assertTrue(report["cpu_includes_per_clip_model_load"])
        self.assertIn("detector_setup_ms", report)
        self.assertNotIn("model_load_ms", report)
        self.assertNotIn(str(self.audio), output.getvalue())

    def test_vosk_modes_preserve_near_phrase_rejection(self):
        for mode in ("phrase-only", "competing", "full"):
            with self.subTest(mode=mode):
                recognizer = Mock()
                recognizer.AcceptWaveform.return_value = False
                recognizer.FinalResult.return_value = json.dumps({"text": "hey freddie"})
                factory = Mock(return_value=recognizer)
                module = SimpleNamespace(Model=Mock(), KaldiRecognizer=factory, SetLogLevel=Mock())
                with patch.dict("sys.modules", {"vosk": module}):
                    detect = EVAL.vosk_detector(self.model, mode)
                    self.assertEqual(detect(b"\x00\x00" * 321), 0)
                    recognizer.FinalResult.return_value = json.dumps({"text": "hey friday"})
                    self.assertEqual(detect(b"\x00\x00" * 321), 1)
                    recognizer.FinalResult.return_value = json.dumps(
                        {"text": "hey friday open calculator"}
                    )
                    self.assertEqual(detect(b"\x00\x00" * 321), int(mode != "phrase-only"))
                self.assertEqual(len(factory.call_args.args), 2 if mode == "full" else 3)

    def native_decoder(self):
        names = ("ps_config_parse_json", "ps_config_free", "ps_init", "ps_start_stream",
                 "ps_start_utt", "ps_process_raw", "ps_end_utt", "ps_get_hyp", "ps_free")
        native = SimpleNamespace(**{name: Mock(return_value=0) for name in names})
        native.ps_config_parse_json.return_value = 100
        native.ps_init.return_value = 200
        native.ps_get_hyp.return_value = b""
        return native

    def test_native_frames_include_partial_tail_and_count_new_events(self):
        native = self.native_decoder()
        native.ps_get_hyp.side_effect = [b"hey friday", b"hey friday", b"hey friday hey friday"]
        lengths = []
        native.ps_process_raw.side_effect = lambda _d, _p, size, _s, _f: lengths.append(size) or 0
        with patch.object(ctypes, "CDLL", return_value=native):
            detect = EVAL.pocketsphinx_detector(self.model, self.library, self.dictionary, 1e-40)
            self.assertEqual(detect(b"\x00\x00" * 321), 2)
        self.assertEqual(lengths, [320, 1])
        native.ps_free.assert_called_once_with(200)
        native.ps_config_free.assert_called_once_with(100)

    def test_native_failure_releases_decoder_and_config(self):
        native = self.native_decoder()
        native.ps_process_raw.return_value = -1
        with patch.object(ctypes, "CDLL", return_value=native):
            detect = EVAL.pocketsphinx_detector(self.model, self.library, self.dictionary, 1e-40)
            with self.assertRaisesRegex(ValueError, "decode a frame"):
                detect(b"\x00\x00" * 320)
        native.ps_free.assert_called_once_with(200)
        native.ps_config_free.assert_called_once_with(100)

    def test_native_missing_model_releases_config(self):
        native = self.native_decoder()
        native.ps_init.return_value = None
        with patch.object(ctypes, "CDLL", return_value=native):
            detect = EVAL.pocketsphinx_detector(self.model, self.library, self.dictionary, 1e-40)
            with self.assertRaisesRegex(ValueError, "load the model"):
                detect(b"\x00\x00" * 320)
        native.ps_free.assert_not_called()
        native.ps_config_free.assert_called_once_with(100)


if __name__ == "__main__":
    unittest.main()
