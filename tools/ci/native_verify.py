#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify vendored bytes/notices, then exercise native contracts on the host."""
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def main():
    vendor = ROOT / "core/map-native/src/main/cpp/vendor"
    manifest = json.loads((vendor / "manifest.sha256.json").read_text())
    actual = {p.relative_to(vendor).as_posix() for p in vendor.rglob("*")
              if p.is_file() and p.name != "manifest.sha256.json"}
    if actual != set(manifest):
        raise SystemExit("Review added/removed vendored files before changing the manifest")
    for name, sha in manifest.items():
        if hashlib.sha256((vendor / name).read_bytes()).hexdigest() != sha:
            raise SystemExit("Modified vendored source: " + name)
    notices = (ROOT / "core/map-native/THIRD-PARTY-NOTICES.txt").read_bytes()
    for path in ("androidApp/src/main/assets/ml", "iosApp/iosApp/ml"):
        if (ROOT / path / "MAP-NATIVE-NOTICES.txt").read_bytes() != notices:
            raise SystemExit("Bundled map notices differ: " + path)
    if b"This product includes software developed by the Delft University of Technology." not in notices:
        raise SystemExit("Missing BSD acknowledgement")
    ebur = ROOT / "core/audio-dsp/src/main/cpp/vendor/libebur128"
    for line in (ebur / "UPSTREAM.txt").read_text().splitlines():
        if "  " in line and len(line.split("  ")[0]) == 64:
            expected, filename = line.split("  ", 1)
            if hashlib.sha256((ebur / filename).read_bytes()).hexdigest() != expected:
                raise SystemExit("Modified libebur128 source: " + filename)
    bundled_notice = (ROOT / "composeApp/src/commonMain/composeResources/files/licenses/libebur128.txt").read_text()
    if (ebur / "COPYING").read_text() not in bundled_notice or (ebur / "queue/sys/queue.h").read_text().split("#ifndef")[0].rstrip() not in bundled_notice:
        raise SystemExit("Missing libebur128 MIT/BSD notices")
    audio_build = ROOT / "build/audio-contracts"
    subprocess.run(["cmake", "-S", str(ROOT / "core/audio-dsp/src/main/cpp"), "-B", str(audio_build),
                    "-DLJ_EQ_TESTS=ON", "-DCMAKE_BUILD_TYPE=Debug",
                    "-DCMAKE_C_FLAGS=-fsanitize=address,undefined -fno-omit-frame-pointer",
                    "-DCMAKE_CXX_FLAGS=-fsanitize=address,undefined -fno-omit-frame-pointer",
                    "-DCMAKE_EXE_LINKER_FLAGS=-fsanitize=address,undefined",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-fsanitize=address,undefined"], check=True)
    subprocess.run(["cmake", "--build", str(audio_build), "--parallel", "2"], check=True)
    subprocess.run(["ctest", "--test-dir", str(audio_build), "--output-on-failure"], check=True)
    build = ROOT / "build/native-contracts"
    subprocess.run(["cmake", "-S", str(ROOT / "core/map-native/src/main/cpp"),
                    "-B", str(build), "-DLJMAP_TESTS=ON", "-DCMAKE_BUILD_TYPE=Debug",
                    "-DCMAKE_CXX_FLAGS=-fsanitize=address,undefined -fno-omit-frame-pointer",
                    "-DCMAKE_EXE_LINKER_FLAGS=-fsanitize=address,undefined",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-fsanitize=address,undefined"], check=True)
    subprocess.run(["cmake", "--build", str(build), "--parallel", "2"], check=True)
    subprocess.run(["ctest", "--test-dir", str(build), "--output-on-failure"], check=True)

if __name__ == "__main__":
    main()
