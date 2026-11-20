"""Optional checks of the compiled C interface, without a microphone or model download."""

import ctypes
import os
import unittest


@unittest.skipUnless(os.environ.get("FRIDAY_MICRO_NATIVE_LIBRARY"),
                     "Set FRIDAY_MICRO_NATIVE_LIBRARY to a compiled desktop test runtime")
class MicroWakeWordNativeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.library = ctypes.CDLL(os.environ["FRIDAY_MICRO_NATIVE_LIBRARY"])
        cls.create = cls.library.friday_wake_create
        cls.create.argtypes = [ctypes.POINTER(ctypes.c_uint8), ctypes.c_size_t,
                               ctypes.c_float, ctypes.c_int, ctypes.c_int]
        cls.create.restype = ctypes.c_void_p
        cls.process = cls.library.friday_wake_process
        cls.process.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int16), ctypes.c_size_t]
        cls.process.restype = ctypes.c_int

    def test_null_or_oversized_model_rejected(self):
        data = (ctypes.c_uint8 * 8)()
        self.assertIsNone(self.create(None, 8, 0.66, 10, 10))
        self.assertIsNone(self.create(data, 0, 0.66, 10, 10))
        self.assertIsNone(self.create(data, 2 * 1024 * 1024 + 1, 0.66, 10, 10))

    def test_nonfinite_cutoff_and_invalid_settings_rejected(self):
        data = (ctypes.c_uint8 * 8)()
        for cutoff in (float("nan"), float("inf"), 0, -1, 1.1):
            with self.subTest(cutoff=cutoff):
                self.assertIsNone(self.create(data, 8, cutoff, 10, 10))
        self.assertIsNone(self.create(data, 8, 0.66, 0, 10))
        self.assertIsNone(self.create(data, 8, 0.66, 101, 10))
        self.assertIsNone(self.create(data, 8, 0.66, 10, 20))

    def test_corrupt_flatbuffer_rejected_without_crashing(self):
        data = (ctypes.c_uint8 * 8).from_buffer_copy(b"\x00\x00\x00\x00TFL3")
        self.assertIsNone(self.create(data, len(data), 0.66, 10, 10))

    def test_null_decoder_and_invalid_frame_rejected(self):
        data = (ctypes.c_int16 * 480)()
        self.assertEqual(self.process(None, data, 160), -1)
        # Rejection must happen before any dereference of this sentinel handle.
        self.assertEqual(self.process(1, None, 160), -1)
        self.assertEqual(self.process(1, data, 481), -1)


if __name__ == "__main__":
    unittest.main()
