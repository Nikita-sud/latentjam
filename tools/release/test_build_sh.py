# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
"""The F-Droid prebuild lines are frozen into every future build block: they must keep working with build.sh."""
import os
import re
import subprocess
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def frozen_prebuild_lines():
    return re.findall(r"^\s+prebuild: (.+)$", (HERE / "fdroid/recipe.yml").read_text(), re.M)


def run_line(line, home):
    # fdroidserver substitutes $$name$$ with absolute paths and runs the line with bash -e in the recipe's subdir.
    line = line.replace("$$onnxruntime$$", "/build/srclib/onnxruntime").replace("$$NDK$$", "/sdk/ndk/28.2.13676358")
    line = line.replace("$$SDK$$", "/sdk")
    env = dict(os.environ, LATENTJAM_DRY_RUN="1", HOME=str(home))
    return subprocess.run(["bash", "-e", "-c", line], cwd=ROOT / "androidApp", env=env, capture_output=True, text=True)


class FrozenPrebuildTest(unittest.TestCase):
    def test_four_lines_one_per_abi(self):
        lines = frozen_prebuild_lines()
        self.assertEqual([re.search(r"fdroid-prebuild (\S+)", l).group(1) for l in lines],
                         ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"])

    def test_every_frozen_line_runs(self):
        for line in frozen_prebuild_lines():
            with self.subTest(line=line):
                result = run_line(line, "/home/vagrant")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("sdkmanager cmake;3.22.1", result.stderr)
                arm64 = "arm64-v8a" in line
                self.assertEqual("ort_source_build.sh /build/srclib/onnxruntime /sdk/ndk/28.2.13676358 /sdk"
                                 in result.stderr, arm64)
                if arm64:
                    # The ORT work dir lives on disk outside the scanned tree, and is removed afterwards.
                    self.assertIn("rm -rf /home/vagrant/.cache/latentjam-ort", result.stderr)

    def test_unknown_abi_and_wrong_arguments_fail(self):
        for line in ["bash ../tools/release/build.sh fdroid-prebuild mips",
                     "bash ../tools/release/build.sh fdroid-prebuild arm64-v8a",
                     "bash ../tools/release/build.sh fdroid-prebuild x86 /extra"]:
            with self.subTest(line=line):
                self.assertNotEqual(run_line(line, "/tmp").returncode, 0)


if __name__ == "__main__":
    unittest.main()
