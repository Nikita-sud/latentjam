#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A local mirror of the source archives ONNX Runtime's CMake fetches for the reduced Android build.

    python3 tools/runtime/ort_deps_mirror.py ORT_SOURCE MIRROR_DIR [--offline]

Reads ORT_SOURCE/cmake/deps.txt (name;url;sha1) and places each archive the reduced ARM64 build uses at
MIRROR_DIR/<url without https://>, the layout `--cmake_deps_mirror_dir` (onnxruntime_CMAKE_DEPS_MIRROR_DIR) looks up.
Every file is checked against deps.txt's SHA-1; one already present and matching is kept, so a mirror prepared
elsewhere (an F-Droid srclib, a CI cache) is used as it is, and --offline fails instead of downloading what is missing.
The prebuilt `protoc_binary` is never mirrored: ort_source_build.sh builds protoc from the protobuf sources instead.
"""
import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

# The dependencies a CPU-only Android build of ORT 1.26.0 fetches (its _deps directory), minus protoc_binary.
NEEDED = ("abseil_cpp", "date", "eigen", "flatbuffers", "microsoft_gsl", "kleidiai", "mp11", "json", "onnx", "protobuf",
          "pytorch_cpuinfo", "re2", "safeint")


def sha1(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("mirror", type=Path)
    parser.add_argument("--offline", action="store_true", help="never download; fail when an archive is missing")
    args = parser.parse_args()
    deps = {}
    for line in (args.source / "cmake/deps.txt").read_text().splitlines():
        if line and not line.startswith("#"):
            name, url, digest = line.split(";")
            deps[name] = (url, digest)
    missing = [n for n in NEEDED if n not in deps]
    if missing:
        sys.exit(f"deps.txt lacks {missing}: a different ORT version; review NEEDED")
    for name in NEEDED:
        url, digest = deps[name]
        if not url.startswith("https://"):
            continue
        target = args.mirror / url[len("https://"):]
        if target.is_file() and sha1(target) == digest:
            print(f"{name}: present")
            continue
        if args.offline:
            sys.exit(f"{name}: {target} missing or wrong, and --offline")
        target.parent.mkdir(parents=True, exist_ok=True)
        partial = target.with_suffix(target.suffix + ".part")
        with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as out:
            while block := response.read(1 << 20):
                out.write(block)
        got = sha1(partial)
        if got != digest:
            partial.unlink()
            sys.exit(f"{name}: SHA-1 {got} != {digest} from deps.txt")
        partial.replace(target)
        print(f"{name}: downloaded {target.stat().st_size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
