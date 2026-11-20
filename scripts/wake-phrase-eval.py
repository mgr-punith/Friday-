#!/usr/bin/env python3
"""Measure offline wake detection on labelled 16 kHz mono PCM WAV files."""

import argparse
import ctypes
import json
import math
import time
import wave
from pathlib import Path


RATE = 16_000
FRAME = RATE // 50  # 20 ms, as in Friday's recorder
PHRASE = "hey friday"
VOSK_CONFUSERS = ("hey freddie", "hi friday", "friday", "next friday", "hey fried rice")


def audio_files(paths):
    files = []
    for path in paths:
        matches = sorted(path.glob("*.wav")) if path.is_dir() else [path]
        if not matches or any(not match.is_file() for match in matches):
            raise ValueError(f"No WAV files at {path}")
        files.extend(matches)
    return files


def read_pcm(path):
    with wave.open(str(path), "rb") as wav:
        if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) != (RATE, 1, 2):
            raise ValueError(f"{path}: expected 16 kHz mono 16-bit PCM WAV")
        samples = wav.readframes(wav.getnframes())
        if not samples or len(samples) != wav.getnframes() * 2:
            raise ValueError(f"{path}: empty or truncated audio")
        return samples


def sherpa_detector(model_dir, keywords):
    import numpy as np
    from sherpa_onnx import KeywordSpotter

    def model_file(name):
        for pattern in (f"{name}*.int8.onnx", f"{name}*.onnx"):
            candidates = sorted(model_dir.glob(pattern))
            if len(candidates) == 1:
                return str(candidates[0])
            if len(candidates) > 1:
                break
        raise ValueError(f"Expected a single {name} ONNX model in {model_dir}")

    spotter = KeywordSpotter(
        tokens=str(model_dir / "tokens.txt"),
        encoder=model_file("encoder"),
        decoder=model_file("decoder"),
        joiner=model_file("joiner"),
        keywords_file=str(keywords),
        num_threads=1,
        provider="cpu",
    )

    def detect(pcm):
        samples = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768
        # Sherpa's upstream example adds 660 ms to drain the streaming lookahead.
        samples = np.pad(samples, (0, RATE * 66 // 100))
        stream = spotter.create_stream()
        wakes = 0
        for offset in range(0, len(samples), FRAME):
            stream.accept_waveform(RATE, samples[offset : offset + FRAME])
            if offset + FRAME >= len(samples):
                stream.input_finished()
            while spotter.is_ready(stream):
                spotter.decode_stream(stream)
                if spotter.get_result(stream).casefold() == PHRASE:
                    wakes += 1
                    spotter.reset_stream(stream)
        return wakes

    return detect


def vosk_detector(model_dir, grammar_mode="phrase-only"):
    from vosk import KaldiRecognizer, Model, SetLogLevel

    SetLogLevel(-1)
    model = Model(str(model_dir))
    phrases = [PHRASE, *VOSK_CONFUSERS] if grammar_mode == "competing" else [PHRASE]
    grammar = json.dumps([*phrases, "[unk]"])

    def count_match(result):
        text = json.loads(result).get("text", "").casefold()
        if grammar_mode == "phrase-only":
            return int(text == PHRASE)
        return int(f" {PHRASE} " in f" {text} ")

    def detect(pcm):
        recognizer = (KaldiRecognizer(model, RATE) if grammar_mode == "full"
                      else KaldiRecognizer(model, RATE, grammar))
        wakes = 0
        frame_bytes = FRAME * 2
        for offset in range(0, len(pcm), frame_bytes):
            if recognizer.AcceptWaveform(pcm[offset : offset + frame_bytes]):
                wakes += count_match(recognizer.Result())
        return wakes + count_match(recognizer.FinalResult())

    return detect


def pocketsphinx_detector(model_dir, library, dictionary, threshold):
    native = ctypes.CDLL(str(library.resolve()))
    pointer = ctypes.c_void_p
    for name, arguments, result in (
        ("ps_config_parse_json", [pointer, ctypes.c_char_p], pointer),
        ("ps_config_free", [pointer], ctypes.c_int),
        ("ps_init", [pointer], pointer),
        ("ps_start_stream", [pointer], ctypes.c_int),
        ("ps_start_utt", [pointer], ctypes.c_int),
        ("ps_process_raw", [pointer, ctypes.POINTER(ctypes.c_int16), ctypes.c_size_t,
                            ctypes.c_int, ctypes.c_int], ctypes.c_int),
        ("ps_end_utt", [pointer], ctypes.c_int),
        ("ps_get_hyp", [pointer, ctypes.POINTER(ctypes.c_int32)], ctypes.c_char_p),
        ("ps_free", [pointer], ctypes.c_int),
    ):
        function = getattr(native, name)
        function.argtypes, function.restype = arguments, result
    config = json.dumps({
        "hmm": str(model_dir.resolve()), "dict": str(dictionary.resolve()),
        "keyphrase": PHRASE, "kws_threshold": threshold,
        "samprate": RATE, "loglevel": "ERROR",
    }).encode()

    def detect(pcm):
        settings = native.ps_config_parse_json(None, config)
        if not settings:
            raise ValueError("PocketSphinx rejected its configuration")
        decoder = native.ps_init(settings)
        try:
            if not decoder:
                raise ValueError("PocketSphinx could not load the model")
            if native.ps_start_stream(decoder) < 0 or native.ps_start_utt(decoder) < 0:
                raise ValueError("PocketSphinx could not start")
            samples = (ctypes.c_int16 * FRAME)()
            events = 0
            previous = b""
            for offset in range(0, len(pcm), FRAME * 2):
                chunk = pcm[offset : offset + FRAME * 2]
                ctypes.memmove(samples, chunk, len(chunk))
                if native.ps_process_raw(decoder, samples, len(chunk) // 2, 0, 0) < 0:
                    raise ValueError("PocketSphinx could not decode a frame")
                current = native.ps_get_hyp(decoder, None) or b""
                if current != previous:
                    events += max(0, current.count(PHRASE.encode()) - previous.count(PHRASE.encode()))
                    previous = current
            if native.ps_end_utt(decoder) < 0:
                raise ValueError("PocketSphinx could not finish")
            current = native.ps_get_hyp(decoder, None) or b""
            return events + max(0, current.count(PHRASE.encode()) - previous.count(PHRASE.encode()))
        finally:
            if decoder:
                native.ps_free(decoder)
            native.ps_config_free(settings)

    return detect


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("engine", choices=("sherpa", "vosk", "pocketsphinx", "microwakeword"))
    parser.add_argument("model_dir", type=Path)
    parser.add_argument("--keywords", type=Path, help="Sherpa token file for Hey Friday")
    parser.add_argument("--native-library", type=Path, help="Locally built evaluation shared library")
    parser.add_argument("--dictionary", type=Path, help="PocketSphinx pronunciation dictionary")
    parser.add_argument("--threshold", type=float, default=1e-20, help="PocketSphinx keyword threshold")
    parser.add_argument("--vosk-grammar", choices=("phrase-only", "competing", "full"),
                        default="phrase-only", help="Vosk vocabulary for the comparison")
    parser.add_argument("--micro-config", type=Path, help="Local microWakeWord v2 model JSON")
    parser.add_argument("--model-sha256", help="Expected microWakeWord model SHA-256")
    parser.add_argument("--expected-phrase", default=PHRASE, help="Expected microWakeWord phrase")
    parser.add_argument("--micro-cutoff", type=float, help="Override the model's probability cutoff")
    parser.add_argument("--positive", type=Path, action="append", default=[], help="Wake WAV or directory")
    parser.add_argument("--negative", type=Path, action="append", default=[], help="Non-wake WAV or directory")
    args = parser.parse_args(argv)

    try:
        groups = {"positive": audio_files(args.positive), "negative": audio_files(args.negative)}
        files = [path.resolve() for group in groups.values() for path in group]
        if not files or len(files) != len(set(files)):
            raise ValueError("Supply at least one WAV and list each file once")
        if not args.model_dir.is_dir():
            raise ValueError("Model directory does not exist")
        if args.engine == "sherpa" and (args.keywords is None or not args.keywords.is_file()):
            raise ValueError("Sherpa requires an existing --keywords file")
        if args.engine == "pocketsphinx":
            if not all(path is not None and path.is_file()
                       for path in (args.native_library, args.dictionary)):
                raise ValueError("PocketSphinx requires --native-library and --dictionary files")
            if not math.isfinite(args.threshold) or args.threshold <= 0:
                raise ValueError("Threshold must be a positive finite number")
        if args.engine == "microwakeword":
            from microwakeword_eval import WARMUP_MS, load_model, native_detector

            if args.native_library is None or not args.native_library.is_file():
                raise ValueError("microWakeWord requires an existing --native-library")
            if args.micro_config is None or not args.micro_config.is_file():
                raise ValueError("microWakeWord requires an existing --micro-config")
            micro_model = load_model(args.model_dir, args.micro_config, args.expected_phrase,
                                     args.model_sha256, args.micro_cutoff)

        start = time.perf_counter()
        if args.engine == "sherpa":
            detect = sherpa_detector(args.model_dir, args.keywords)
        elif args.engine == "vosk":
            detect = vosk_detector(args.model_dir, args.vosk_grammar)
        elif args.engine == "microwakeword":
            detect = native_detector(args.native_library, micro_model)
        else:
            detect = pocketsphinx_detector(args.model_dir, args.native_library,
                                          args.dictionary, args.threshold)
        report = {"engine": args.engine,
                  "detector_setup_ms": round((time.perf_counter() - start) * 1000),
                  "cpu_includes_per_clip_model_load": args.engine in ("pocketsphinx", "microwakeword")}
        cpu_seconds = 0
        audio_seconds = 0
        for label, paths in groups.items():
            duration = triggered = events = 0
            for path in paths:
                pcm = read_pcm(path)
                duration += len(pcm) / (2 * RATE)
                start = time.process_time()
                wakes = detect(pcm)
                cpu_seconds += time.process_time() - start
                triggered += int(wakes > 0)
                events += wakes
            report[label] = {"clips": len(paths), "triggered_clips": triggered, "events": events,
                             "audio_seconds": round(duration, 2)}
            audio_seconds += duration
        report["decode_cpu_seconds_per_audio_second"] = round(cpu_seconds / audio_seconds, 4)
        if args.engine == "pocketsphinx":
            # The native decoder loads per clip to avoid adaptation across test labels.
            report["threshold"] = args.threshold
        if args.engine == "vosk":
            report["grammar"] = args.vosk_grammar
            report["wake_match"] = ("exact completed utterance" if args.vosk_grammar == "phrase-only"
                                    else "whole phrase in completed utterance")
        if args.engine == "microwakeword":
            report.update({"model_sha256": micro_model["sha256"],
                           "wake_phrase": micro_model["phrase"],
                           "probability_cutoff": micro_model["cutoff"],
                           "sliding_window_size": micro_model["window"],
                           "per_clip_warmup_ms": WARMUP_MS,
                           "cpu_includes_warmup": True})
        print(json.dumps(report, indent=2))
    except (AttributeError, ImportError, OSError, ValueError, wave.Error) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
