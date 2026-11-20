#!/usr/bin/env python3
"""Make synthetic 'Hey Friday' and near-phrase WAV clips with eSpeak NG and FFmpeg."""

import argparse
import math
import random
import subprocess
import sys
import tempfile
import wave
from array import array
from pathlib import Path


VOICES = ("en-us+m2", "en-us+f3", "en-gb+m3", "en-gb+f2")
NON_WAKE = (
    "Hey Freddie", "Hi Friday", "Friday", "Next Friday", "Hey, fried rice",
    "Hey, I will see you Friday", "Turn on silent mode", "Hey, what time is it today",
)


def generate(output):
    output.mkdir()
    for group in ("wake", "wake-command-plus-noise", "negative"):
        (output / group).mkdir()
    rng = random.Random(20261119)
    with tempfile.TemporaryDirectory(prefix="friday-wake-synthesis-") as temporary:
        raw = Path(temporary) / "raw.wav"
        pcm = Path(temporary) / "pcm.wav"

        def synthesize(text, voice, speed, destination, add_noise=False):
            subprocess.run(
                ["espeak-ng", "-v", voice, "-s", str(speed), "-w", str(raw), text], check=True
            )
            subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(raw),
                 "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(pcm)], check=True
            )
            with wave.open(str(pcm), "rb") as wav:
                samples = array("h", wav.readframes(wav.getnframes()))
            # WAV stores little-endian samples.
            if sys.byteorder != "little":
                samples.byteswap()
            samples = array("h", [0]) * 2_880 + samples + array("h", [0]) * 7_200
            if add_noise:
                rms = math.sqrt(sum(value * value for value in samples) / len(samples))
                peak = rms * 10 ** (-15 / 20) * math.sqrt(3)
                samples = array("h", (
                    min(32767, max(-32768, round(value + rng.uniform(-peak, peak))))
                    for value in samples
                ))
            if sys.byteorder != "little":
                samples.byteswap()
            with wave.open(str(destination), "wb") as wav:
                wav.setparams((1, 2, 16_000, 0, "NONE", "not compressed"))
                wav.writeframes(samples.tobytes())

        for index, voice in enumerate(VOICES):
            for speed in (140, 175, 210):
                filename = f"voice-{index}-{speed}.wav"
                synthesize("Hey Friday", voice, speed, output / "wake" / filename)
                synthesize("Hey Friday, open Calculator", voice, speed,
                           output / "wake-command-plus-noise" / filename, add_noise=True)
            for text in NON_WAKE:
                synthesize(text, voice, 175, output / "negative" / f"voice-{index}-{text}.wav")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="New directory for the WAV files")
    args = parser.parse_args()
    try:
        generate(args.output)
    except (OSError, subprocess.CalledProcessError) as exc:
        parser.error(str(exc))
