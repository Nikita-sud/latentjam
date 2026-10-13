#!/usr/bin/env python3
"""Build a shell gesture DEX JAR with installed JDK/SDK tools; no downloads or device access."""
# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0

import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--javac", required=True, type=Path)
    parser.add_argument("--android-jar", required=True, type=Path)
    parser.add_argument("--d8", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    for path in (args.javac, args.android_jar, args.d8):
        if not path.is_file():
            parser.error("Missing tool or SDK input: " + str(path))
    if args.output.suffix != ".jar":
        parser.error("--output must end in .jar")
    source = Path(__file__).with_name("MapGesture.java").resolve()
    with tempfile.TemporaryDirectory(prefix="latentjam-map-gesture-build-") as work:
        work = Path(work)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([
            str(args.javac), "--release", "8", "-cp", str(args.android_jar),
            "-d", str(classes), str(source),
        ], check=True)
        jar = work / "map-gesture.jar"
        subprocess.run([
            str(args.d8), "--lib", str(args.android_jar), "--min-api", "23",
            "--output", str(jar), *map(str, sorted(classes.glob("*.class"))),
        ], check=True)
        with zipfile.ZipFile(jar) as archive:
            if "classes.dex" not in archive.namelist():
                raise RuntimeError("D8 produced no classes.dex")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(jar, args.output)
    print("source_sha256=" + hashlib.sha256(source.read_bytes()).hexdigest())
    print("jar_sha256=" + hashlib.sha256(args.output.read_bytes()).hexdigest())
    print("Built " + str(args.output))


if __name__ == "__main__":
    main()
