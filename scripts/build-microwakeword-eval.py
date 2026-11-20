#!/usr/bin/env python3
"""Build a pinned microWakeWord test runtime outside the Android app."""

import argparse
import hashlib
import shutil
import subprocess
import urllib.request
from pathlib import Path


REVISION = "1feed57767ae9a433f3357eab332573848b11844"
BASE = f"https://raw.githubusercontent.com/home-assistant/android/{REVISION}/microwakeword/src/main/cpp"
FILES = {
    "CMakeLists.txt": "a885ce41a565519235be4439d79f4ae530e57bc48fc7365bcda89b15f9613e01",
    "MicroWakeWordEngine.cpp": "c523a255435a990412c66de99e443b8567d701338091d123f4a6bdbac1f4f769",
    "MicroWakeWordEngine.h": "337593bd01f1103b1afde39419b6647e6f4eebaf70ad2416dbf70640543ce1eb",
    "MicroFrontendWrapper.cpp": "32f761aacc11f99ef67b33e4e2ebfef6e81d815618745cf07495d1ae53a00a81",
    "MicroFrontendWrapper.h": "856d6e9c38a9ff4d8ec1a3d23d3f156c65bafb9a0cf35e7251c563a03e70bdeb",
    "Logging.h": "a919e0935f1b6dc1db8465a4b653e21c0492a9d484213e2478f8bc535962b4bc",
}


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("Pinned upstream build layout changed")
    return text.replace(old, new, 1)


def evaluation_cmake(text, debug_jni=False):
    text = replace_once(text, "include(FetchContent)",
                        "set(CMAKE_POSITION_INDEPENDENT_CODE ON)\ninclude(FetchContent)")
    text = replace_once(text, "    MicroWakeWord_jni.cpp\n", "    FridayWakeEval.cpp\n")
    text = replace_once(text, "    android\n    log\n", "")
    text += """
if(ANDROID)
    target_link_libraries(microwakeword android log)
endif()
add_executable(friday_wake_eval FridayWakeMain.cpp)
target_link_libraries(friday_wake_eval microwakeword)
"""
    if debug_jni:
        text += "\nif(ANDROID)\n    target_sources(microwakeword PRIVATE FridayWakeJni.cpp)\nendif()\n"
    return text


def prepare_sources(work, debug_jni=False):
    upstream = work / "upstream"
    source = work / "native-src"
    upstream.mkdir(parents=True, exist_ok=True)
    source.mkdir(exist_ok=True)
    for name, digest in FILES.items():
        path = upstream / name
        if path.exists():
            data = path.read_bytes()
        else:
            with urllib.request.urlopen(f"{BASE}/{name}", timeout=45) as response:
                data = response.read(128 * 1024)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Upstream SHA-256 mismatch: {name}")
        path.write_bytes(data)
        if name == "CMakeLists.txt":
            (source / name).write_text(evaluation_cmake(data.decode(), debug_jni))
        else:
            shutil.copyfile(path, source / name)
    for name in ("FridayWakeEval.cpp", "FridayWakeMain.cpp"):
        shutil.copyfile(Path(__file__).with_name("microwakeword-native") / name, source / name)
    if debug_jni:
        shutil.copyfile(Path(__file__).resolve().parents[1] /
                        "app/src/debug/cpp/FridayWakeJni.cpp", source / "FridayWakeJni.cpp")
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work_dir", type=Path, help="Build directory outside this repository")
    parser.add_argument("--android-ndk", type=Path, help="Cross-compile using this installed NDK")
    parser.add_argument("--abi", choices=("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
                        default="arm64-v8a")
    parser.add_argument("--reuse-dependencies", type=Path, help="Existing _deps source directory")
    parser.add_argument("--debug-jni", action="store_true", help="Build the approved debug-only Android JNI trial")
    args = parser.parse_args()
    try:
        work = args.work_dir.resolve()
        if work.is_relative_to(Path(__file__).resolve().parents[1]):
            raise ValueError("Keep native build artifacts outside the repository")
        if args.debug_jni and not args.android_ndk:
            raise ValueError("--debug-jni requires --android-ndk")
        source = prepare_sources(work, args.debug_jni)
        build = work / (f"native-{args.abi}" if args.android_ndk else "native-linux")
        command = ["cmake", "-S", str(source), "-B", str(build), "-G", "Unix Makefiles",
                   "-DCMAKE_BUILD_TYPE=MinSizeRel", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                   "-DCMAKE_CXX_FLAGS=-fno-exceptions"]
        if args.android_ndk:
            ndk = args.android_ndk.resolve()
            toolchain = ndk / "build/cmake/android.toolchain.cmake"
            if not toolchain.is_file():
                raise ValueError("Android NDK toolchain does not exist")
            command += [f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={args.abi}",
                        "-DANDROID_PLATFORM=26", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                        "-DANDROID_STL=c++_static"]
        if args.reuse_dependencies:
            for name in ("kissfft", "tflite_micro", "flatbuffers", "gemmlowp", "ruy"):
                path = (args.reuse_dependencies / f"{name}-src").resolve()
                if not path.is_dir():
                    raise ValueError(f"Missing cached dependency source: {name}")
                command.append(f"-DFETCHCONTENT_SOURCE_DIR_{name.upper()}={path}")
        subprocess.run(command, check=True)
        subprocess.run(["cmake", "--build", str(build), "--target", "friday_wake_eval",
                        "--parallel", "2"], check=True)
        print(f"Evaluation runtime: {build / 'libmicrowakeword.so'}")
        print(f"PCM test executable: {build / 'friday_wake_eval'}")
        if args.debug_jni:
            destination = work / "android-debug-jni" / args.abi
            destination.mkdir(parents=True, exist_ok=True)
            library = destination / "libmicrowakeword.so"
            shutil.copyfile(build / "libmicrowakeword.so", library)
            prebuilt = ndk / "toolchains/llvm/prebuilt"
            strip = next(prebuilt.glob("*/bin/llvm-strip"), None)
            if strip is None:
                raise ValueError("NDK llvm-strip not found")
            subprocess.run([str(strip), "--strip-unneeded", str(library)], check=True)
            print(f"Debug JNI directory: {destination.parent}")
            print(f"Stripped {args.abi} runtime: {library.stat().st_size} bytes")
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
