"""Local-only microWakeWord model validation and native evaluation adapter."""

import ctypes
import hashlib
import json
import math
from pathlib import Path


RATE = 16_000
FRAME = RATE // 100
WARMUP_MS = 4_000
MAX_MODEL_BYTES = 2 * 1024 * 1024


def load_model(directory, config_path, expected_phrase, expected_hash, cutoff=None):
    config = json.loads(config_path.read_text())
    if not isinstance(config, dict) or config.get("type") != "micro" or config.get("version") != 2:
        raise ValueError("Expected a microWakeWord v2 model configuration")
    phrase = config.get("wake_word")
    if not isinstance(phrase, str) or " ".join(phrase.casefold().split()) != expected_phrase.casefold():
        raise ValueError("Model wake phrase does not match --expected-phrase")
    filename = config.get("model")
    if (not isinstance(filename, str) or Path(filename).name != filename
            or not filename.endswith(".tflite") or "\\" in filename):
        raise ValueError("Model filename must name a local .tflite file")
    path = (directory / filename).resolve()
    if path.parent != directory.resolve():
        raise ValueError("Model must stay inside the model directory")
    if not 8 <= path.stat().st_size <= MAX_MODEL_BYTES:
        raise ValueError("Model must be between 8 bytes and 2 MiB")
    data = path.read_bytes()
    if data[4:8] != b"TFL3":
        raise ValueError("Model does not have a TFLite file identifier")
    digest = hashlib.sha256(data).hexdigest()
    if not isinstance(expected_hash, str) or digest != expected_hash.lower():
        raise ValueError("Model SHA-256 does not match --model-sha256")
    settings = config.get("micro")
    if not isinstance(settings, dict):
        raise ValueError("Model requires microWakeWord detection settings")
    probability = settings.get("probability_cutoff") if cutoff is None else cutoff
    if (type(probability) not in (int, float) or not math.isfinite(probability)
            or not 0 < probability <= 1):
        raise ValueError("Probability cutoff must be finite and greater than 0, at most 1")
    window = settings.get("sliding_window_size")
    if type(window) is not int or not 1 <= window <= 100:
        raise ValueError("Sliding window size must be an integer from 1 to 100")
    if settings.get("feature_step_size") != 10:
        raise ValueError("This evaluator supports models with a 10 ms feature step")
    arena = settings.get("tensor_arena_size")
    if type(arena) is not int or not 1 <= arena <= 65_536:
        raise ValueError("Model tensor arena exceeds the evaluator's 64 KiB arena")
    return {"data": data, "sha256": digest, "phrase": phrase,
            "cutoff": probability, "window": window, "feature_step_ms": 10}


def native_detector(library, model):
    native = ctypes.CDLL(str(library.resolve()))
    pointer = ctypes.c_void_p
    for name, arguments, result in (
        ("friday_wake_create", [ctypes.POINTER(ctypes.c_uint8), ctypes.c_size_t,
                                ctypes.c_float, ctypes.c_int, ctypes.c_int], pointer),
        ("friday_wake_process", [pointer, ctypes.POINTER(ctypes.c_int16), ctypes.c_size_t], ctypes.c_int),
        ("friday_wake_destroy", [pointer], None),
    ):
        function = getattr(native, name)
        function.argtypes, function.restype = arguments, result
    data = (ctypes.c_uint8 * len(model["data"])).from_buffer_copy(model["data"])

    def detect(pcm):
        if not pcm or len(pcm) % 2:
            raise ValueError("Expected nonempty 16-bit PCM")
        decoder = native.friday_wake_create(
            data, len(data), model["cutoff"], model["window"], model["feature_step_ms"]
        )
        if not decoder:
            raise ValueError("microWakeWord could not initialize the model")
        try:
            samples = (ctypes.c_int16 * FRAME)()
            # The reference runtime ignores 100 inference slices at startup.
            # Warm each independent clip; do not score warm-up events.
            for _ in range(WARMUP_MS // 10):
                if native.friday_wake_process(decoder, samples, FRAME) < 0:
                    raise ValueError("microWakeWord warm-up failed")
            events = 0
            for offset in range(0, len(pcm), FRAME * 2):
                chunk = pcm[offset : offset + FRAME * 2]
                ctypes.memmove(samples, chunk, len(chunk))
                result = native.friday_wake_process(decoder, samples, len(chunk) // 2)
                if result not in (0, 1):
                    raise ValueError("microWakeWord could not decode a frame")
                events += result
            return events
        finally:
            native.friday_wake_destroy(decoder)

    return detect
