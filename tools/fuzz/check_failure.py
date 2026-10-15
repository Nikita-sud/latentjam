#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prove that the installed Jazzer reports a real Java finding as a failed process."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

MARKER = "LATENTJAM_DELIBERATE_JAZZER_FAILURE"
TARGET = "io.github.nikitasud.latentjam.fuzz.GateFailureSmoke"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jazzer", required=True, type=Path)
    args = parser.parse_args()
    javac = str(Path(os.environ["JAVA_HOME"]) / "bin/javac") if os.environ.get("JAVA_HOME") else shutil.which("javac")
    if not javac:
        parser.error("javac is required (set JAVA_HOME to a JDK)")
    with tempfile.TemporaryDirectory(prefix="latentjam-fuzz-gate-") as temporary:
        root = Path(temporary)
        source = root / "GateFailureSmoke.java"
        source.write_text(
            "package io.github.nikitasud.latentjam.fuzz;\n"
            "public final class GateFailureSmoke {\n"
            "  public static void fuzzerTestOneInput(byte[] data) {\n"
            f'    throw new IllegalStateException("{MARKER}");\n'
            "  }\n}\n"
        )
        subprocess.run([javac, "-d", str(root), str(source)], check=True, timeout=30)
        seed = root / "seed"
        seed.write_bytes(b"intentional gate smoke")
        crashes = root / "crashes"
        crashes.mkdir()
        command = [str(args.jazzer.resolve()), "--cp=" + str(root), "--target_class=" + TARGET,
                   "--instrumentation_includes=io.github.nikitasud.latentjam.**",
                   "--jvm_args=-Xmx512m", "--reproducer_path=" + str(crashes),
                   "-artifact_prefix=" + str(crashes) + os.sep, "-timeout=10", str(seed)]
        try:
            subprocess.run(command, cwd=root, check=True, capture_output=True, text=True, timeout=60)
        except subprocess.CalledProcessError as failure:
            output = (failure.stdout or "") + (failure.stderr or "")
            artifacts = list(crashes.glob("Crash_*.java"))
            if MARKER not in output or not artifacts:
                raise SystemExit("Jazzer failed before proving its finding exit contract:\n" + output)
            print(f"Jazzer finding exits nonzero ({failure.returncode}); Java reproducer created; check=True propagated failure.")
        else:
            raise SystemExit("Jazzer reported success for a deliberately throwing target")


if __name__ == "__main__":
    main()
